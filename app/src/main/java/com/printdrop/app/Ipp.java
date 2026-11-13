package com.printdrop.app;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A small IPP client: ask a printer what it can do, and send it a job. */
final class Ipp {
    private Ipp() {}

    static final int T_INT = 0x21, T_BOOL = 0x22, T_ENUM = 0x23, T_RANGE = 0x33,
            T_NAME = 0x42, T_KEYWORD = 0x44, T_URI = 0x45, T_CHARSET = 0x47,
            T_LANG = 0x48, T_MIME = 0x49;

    /** Writes whatever follows the IPP header: the document. */
    interface Body {
        void write(OutputStream out) throws IOException;
    }

    /** Builds an IPP request. */
    static final class Req {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream d = new DataOutputStream(bytes);
        private boolean jobGroup;

        Req(int operation, Printer p) throws IOException {
            d.writeShort(0x0101);
            d.writeShort(operation);
            d.writeInt(1);
            d.writeByte(0x01);
            add(T_CHARSET, "attributes-charset", "utf-8");
            add(T_LANG, "attributes-natural-language", "en");
            add(T_URI, "printer-uri", "ipp://" + p.host + ":" + p.ippPort + p.ippPath);
        }

        Req add(int tag, String name, String value) throws IOException {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            d.writeByte(tag);
            d.writeShort(n.length);
            d.write(n);
            d.writeShort(v.length);
            d.write(v);
            return this;
        }

        Req addInt(int tag, String name, int value) throws IOException {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            d.writeByte(tag);
            d.writeShort(n.length);
            d.write(n);
            d.writeShort(4);
            d.writeInt(value);
            return this;
        }

        Req addRange(String name, int from, int to) throws IOException {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            d.writeByte(T_RANGE);
            d.writeShort(n.length);
            d.write(n);
            d.writeShort(8);
            d.writeInt(from);
            d.writeInt(to);
            return this;
        }

        /** Everything added after this describes the job (copies, paper, ...). */
        Req job() throws IOException {
            if (!jobGroup) {
                d.writeByte(0x02);
                jobGroup = true;
            }
            return this;
        }

        byte[] done() throws IOException {
            d.writeByte(0x03);
            d.flush();
            return bytes.toByteArray();
        }
    }

    static final class Reply {
        int status;
        final Map<String, List<Object>> attrs = new HashMap<>();

        boolean ok() {
            return status < 0x0100;
        }

        List<Object> all(String name) {
            List<Object> l = attrs.get(name);
            return l == null ? new ArrayList<>() : l;
        }

        String text(String name) {
            for (Object o : all(name)) if (o instanceof String) return (String) o;
            return "";
        }

        String problem() {
            switch (status) {
                case 0x0401:
                case 0x0402:
                case 0x0403:
                    return "The printer refused the job (it wants a login or does not allow this).";
                case 0x040A:
                    return "The printer does not accept this file type. Try another \"Send as\" choice.";
                case 0x040B:
                    return "The printer did not accept one of the settings.";
                case 0x0506:
                    return "The printer is not accepting jobs right now.";
                case 0x0507:
                    return "The printer is busy. Try again in a moment.";
                default:
                    String m = text("status-message");
                    return "The printer answered with error 0x" + Integer.toHexString(status)
                            + (m.isEmpty() ? "." : ": " + m);
            }
        }
    }

    /** Sends a request and reads the reply. body may be null. */
    static Reply send(Printer p, byte[] header, Body body, int readTimeoutMs) throws IOException {
        URL url = new URL("http", p.host, p.ippPort, p.ippPath);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/ipp");
            c.setRequestProperty("Accept", "application/ipp");
            if (body == null) c.setFixedLengthStreamingMode(header.length);
            else c.setChunkedStreamingMode(32768);
            try (OutputStream o = c.getOutputStream()) {
                o.write(header);
                if (body != null) body.write(o);
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403)
                throw new IOException("The printer asks for a user name and password.");
            if (code == 426)
                throw new IOException("The printer only accepts encrypted (IPPS) printing.");
            if (code != 200) throw new IOException("The printer answered HTTP " + code + ".");
            try (InputStream in = c.getInputStream()) {
                return parse(in);
            }
        } finally {
            c.disconnect();
        }
    }

    static Reply parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        Reply r = new Reply();
        in.readShort();
        r.status = in.readUnsignedShort();
        in.readInt();
        List<Object> current = null;
        try {
            while (true) {
                int tag = in.readUnsignedByte();
                if (tag == 0x03) break;
                if (tag < 0x10) continue;
                int nl = in.readUnsignedShort();
                byte[] nb = new byte[nl];
                in.readFully(nb);
                int vl = in.readUnsignedShort();
                byte[] vb = new byte[vl];
                in.readFully(vb);
                if (nl > 0) {
                    current = new ArrayList<>();
                    r.attrs.put(new String(nb, StandardCharsets.UTF_8), current);
                }
                if (current == null) continue;
                if ((tag == T_INT || tag == T_ENUM) && vl == 4) {
                    current.add(int32(vb, 0));
                } else if (tag == T_BOOL && vl == 1) {
                    current.add(vb[0] != 0);
                } else if (tag == 0x32 && vl == 9) {
                    current.add(new int[]{int32(vb, 0), int32(vb, 4), vb[8]});
                } else if (tag >= 0x41 && tag <= 0x49) {
                    current.add(new String(vb, StandardCharsets.UTF_8));
                } else {
                    current.add(vb);
                }
            }
        } catch (EOFException ignored) {
            // some printers leave out the end tag
        }
        return r;
    }

    private static int int32(byte[] b, int at) {
        return ((b[at] & 255) << 24) | ((b[at + 1] & 255) << 16) | ((b[at + 2] & 255) << 8) | (b[at + 3] & 255);
    }

    private static final String[] WANTED = {
            "printer-make-and-model", "printer-name", "document-format-supported",
            "pwg-raster-document-resolution-supported", "pwg-raster-document-type-supported",
            "urf-supported", "page-ranges-supported", "print-scaling-supported", "media-supported"};

    private static boolean ask(Printer p) {
        try {
            Req q = new Req(0x000B, p);
            q.add(T_NAME, "requesting-user-name", "PrintDrop");
            q.add(T_KEYWORD, "requested-attributes", WANTED[0]);
            for (int i = 1; i < WANTED.length; i++) q.add(T_KEYWORD, "", WANTED[i]);
            Reply r = send(p, q.done(), null, 8000);
            if (!r.ok() || r.attrs.isEmpty()) return false;
            p.model = r.text("printer-make-and-model");
            if (p.model.isEmpty()) p.model = r.text("printer-name");
            for (Object o : r.all("document-format-supported"))
                if (o instanceof String) p.formats.add((String) o);
            for (Object o : r.all("pwg-raster-document-resolution-supported"))
                if (o instanceof int[]) p.pwgDpi.add(((int[]) o)[0]);
            for (Object o : r.all("pwg-raster-document-type-supported"))
                if (o instanceof String) p.pwgTypes.add((String) o);
            for (Object o : r.all("urf-supported"))
                if (o instanceof String) p.urf.add((String) o);
            for (Object o : r.all("media-supported"))
                if (o instanceof String) p.media.add((String) o);
            for (Object o : r.all("page-ranges-supported"))
                if (o instanceof Boolean) p.pageRanges = (Boolean) o;
            p.scaling = !r.all("print-scaling-supported").isEmpty();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean open(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Finds out how to reach the printer and what it accepts. Blocks; call off the main thread. */
    static void probe(Printer p) {
        p.clearCaps();
        List<Object[]> tries = new ArrayList<>();
        if (p.ippPort > 0) tries.add(new Object[]{p.ippPort, p.ippPath});
        tries.add(new Object[]{631, "/ipp/print"});
        tries.add(new Object[]{631, "/ipp"});
        tries.add(new Object[]{631, "/"});
        tries.add(new Object[]{80, "/ipp"});
        tries.add(new Object[]{80, "/ipp/print"});
        boolean found = false;
        int closedPort = -1;
        for (Object[] t : tries) {
            int port = (Integer) t[0];
            if (port == closedPort) continue;
            if (!open(p.host, port, 1500)) {
                closedPort = port;
                continue;
            }
            p.ippPort = port;
            p.ippPath = (String) t[1];
            p.clearCaps();
            if (ask(p)) {
                found = true;
                break;
            }
        }
        if (!found) {
            p.ippPort = 0;
            p.clearCaps();
        }
        p.rawPort = open(p.host, 9100, 1500) ? 9100 : 0;
        p.probed = true;
    }

    /** Sends bytes straight to the printer's raw port. */
    static void raw(Printer p, Body body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p.host, p.rawPort), 6000);
            s.setSoTimeout(4000);
            OutputStream o = new java.io.BufferedOutputStream(s.getOutputStream(), 32768);
            body.write(o);
            o.flush();
            s.shutdownOutput();
            try {
                InputStream in = s.getInputStream();
                byte[] b = new byte[512];
                while (in.read(b) >= 0) { /* wait for the printer to take it all */ }
            } catch (IOException ignored) {
            }
        }
    }
}
