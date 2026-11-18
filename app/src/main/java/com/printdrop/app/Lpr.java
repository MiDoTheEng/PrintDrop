package com.printdrop.app;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Sends a file to a printer's LPR port (515). Canon office machines print JPEG and TIFF files
 * sent this way without any driver or extra licence ("Direct Print").
 */
final class Lpr {
    private Lpr() {}

    private static final String[] QUEUES = {"lp", "print", "PASSTHRU"};
    private static int counter = (int) (System.currentTimeMillis() % 1000);

    /** Sends one file of the given length as one job, printed 'copies' times. */
    static void send(Printer p, String jobName, long length, Ipp.Body body, int copies)
            throws IOException {
        IOException last = null;
        for (String queue : QUEUES) {
            try {
                once(p, queue, jobName, length, body, copies);
                return;
            } catch (Refused e) {
                last = e;   // this queue name was not accepted, try the next one
            }
        }
        throw last != null ? last : new IOException("The printer refused the job.");
    }

    /** The printer said no before any of the file was sent. */
    private static final class Refused extends IOException {
        Refused(String s) {
            super(s);
        }
    }

    private static void once(Printer p, String queue, String jobName, long length, Ipp.Body body,
                             int copies) throws IOException {
        String num = String.format("%03d", (counter = (counter + 1) % 1000));
        String me = "printdrop";
        String data = "dfA" + num + me;
        String control = "cfA" + num + me;
        String title = jobName.replaceAll("[^\\x20-\\x7E]", "_");
        if (title.length() > 99) title = title.substring(0, 99);
        StringBuilder c = new StringBuilder();
        c.append('H').append(me).append('\n');
        c.append("PPrintDrop\n");
        c.append('J').append(title).append('\n');
        for (int i = 0; i < Math.max(1, copies); i++) c.append('l').append(data).append('\n');
        c.append('U').append(data).append('\n');
        c.append('N').append(title).append('\n');
        byte[] cf = c.toString().getBytes(StandardCharsets.ISO_8859_1);

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p.host, p.lpdPort > 0 ? p.lpdPort : 515), 6000);
            s.setSoTimeout(60000);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            InputStream in = s.getInputStream();

            ascii(out, "\u0002" + queue + "\n");
            out.flush();
            int a = in.read();
            if (a != 0) throw new Refused("The printer refused the job on queue \"" + queue + "\".");

            ascii(out, "\u0002" + cf.length + " " + control + "\n");
            out.flush();
            ack(in);
            out.write(cf);
            out.write(0);
            out.flush();
            ack(in);

            ascii(out, "\u0003" + length + " " + data + "\n");
            out.flush();
            ack(in);
            body.write(out);
            out.write(0);
            out.flush();
            ack(in);
        }
    }

    private static void ack(InputStream in) throws IOException {
        int a = in.read();
        if (a < 0) throw new IOException("The printer closed the connection while receiving the job.");
        if (a != 0) throw new IOException("The printer refused the job (LPR code " + a + ").");
    }

    private static void ascii(OutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
