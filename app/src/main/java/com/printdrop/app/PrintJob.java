package com.printdrop.app;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Decides how to send a document to a printer, and sends it. */
final class PrintJob {
    static final int AUTO = 0, PDF = 1, PWG = 2, URF = 3, JPEG = 4, PCL = 5;
    static final String[] SEND_AS = {"Automatic", "PDF as it is", "PWG raster (Mopria)",
            "Apple raster (AirPrint)", "JPEG, page by page", "PCL"};

    interface Progress {
        void say(String s);
    }

    Printer printer;
    File pdf;
    /** Zero-based pages to print, in order. */
    List<Integer> pages = new ArrayList<>();
    String name = "PrintDrop";
    Paper paper = Paper.A4;
    int copies = 1;
    boolean colour = true;
    /** 0 one-sided, 1 long edge, 2 short edge. */
    int sides;
    int sendAs = AUTO;
    volatile boolean cancelled;

    /** Returns a sentence saying what happened. Blocks; run off the main thread. */
    String run(Progress pr) throws IOException {
        Printer p = printer;
        if (!p.probed || (!p.ipp() && p.rawPort == 0)) {
            pr.say("Checking the printer…");
            Ipp.probe(p);
        }
        if (!p.ipp() && p.rawPort == 0)
            throw new IOException("The printer at " + p.host + " is not answering. "
                    + "Check that this device is on the same Wi-Fi as the printer.");
        try (Pages.Doc doc = new Pages.Doc(pdf)) {
            List<Integer> list = new ArrayList<>();
            for (int i : pages) if (i >= 0 && i < doc.count) list.add(i);
            if (list.isEmpty()) throw new IOException("There are no pages to print.");
            boolean whole = list.size() == doc.count;
            for (int i = 0; whole && i < list.size(); i++) whole = list.get(i) == i;
            int how = sendAs == AUTO ? choose(p, doc, list, whole) : sendAs;
            if (!p.ipp() && (how == PWG || how == URF))
                throw new IOException("This printer only takes PDF, JPEG or PCL. Change \"Send as\".");
            switch (how) {
                case PDF:
                    sendPdf(p, list, whole, pr);
                    return done("PDF");
                case PWG:
                    sendPwg(p, doc, list, pr);
                    return done("PWG raster");
                case URF:
                    sendUrf(p, doc, list, pr);
                    return done("Apple raster");
                case JPEG:
                    sendJpeg(p, doc, list, pr);
                    return done("JPEG");
                default:
                    sendPcl(p, doc, list, pr);
                    return done("PCL");
            }
        }
    }

    private String done(String how) {
        return "Sent to " + printer.title() + " as " + how + ". If nothing comes out, try another "
                + "\"Send as\" choice.";
    }

    private int choose(Printer p, Pages.Doc doc, List<Integer> list, boolean whole) {
        if (!p.ipp()) return PCL;
        boolean pdf = p.has("application/pdf");
        boolean pwg = p.has("image/pwg-raster");
        boolean urf = p.has("image/urf");
        if (pdf && (whole || p.pageRanges) && (p.scaling || doc.fits(list, paper))) return PDF;
        if (pwg) return PWG;
        if (urf) return URF;
        if (pdf && (whole || p.pageRanges)) return PDF;
        if (p.has("image/jpeg")) return JPEG;
        return PCL;
    }

    private void check() throws IOException {
        if (cancelled) throw new IOException("Cancelled.");
    }

    private Ipp.Req request(Printer p, String format) throws IOException {
        Ipp.Req q = new Ipp.Req(0x0002, p);
        q.add(Ipp.T_NAME, "requesting-user-name", "PrintDrop");
        q.add(Ipp.T_NAME, "job-name", name);
        q.add(Ipp.T_MIME, "document-format", format);
        q.job();
        q.addInt(Ipp.T_INT, "copies", Math.max(1, copies));
        if (p.media.isEmpty() || p.media.contains(paper.ipp))
            q.add(Ipp.T_KEYWORD, "media", paper.ipp);
        q.add(Ipp.T_KEYWORD, "sides", sides == 1 ? "two-sided-long-edge"
                : sides == 2 ? "two-sided-short-edge" : "one-sided");
        q.add(Ipp.T_KEYWORD, "print-color-mode", colour ? "color" : "monochrome");
        return q;
    }

    private void submit(Printer p, Ipp.Req q, Ipp.Body body) throws IOException {
        Ipp.Reply r = Ipp.send(p, q.done(), body, 180000);
        if (!r.ok()) throw new IOException(r.problem());
    }

    private String type(Printer p, String wanted) {
        return p.has(wanted) ? wanted : "application/octet-stream";
    }

    // ---------------------------------------------------------------------- PDF

    private void sendPdf(Printer p, List<Integer> list, boolean whole, Progress pr) throws IOException {
        pr.say("Sending the PDF…");
        Ipp.Body body = out -> {
            try (InputStream in = new FileInputStream(pdf)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    check();
                    out.write(b, 0, n);
                }
            }
        };
        if (!p.ipp()) {
            Ipp.raw(p, body);
            return;
        }
        Ipp.Req q = request(p, type(p, "application/pdf"));
        if (p.scaling) q.add(Ipp.T_KEYWORD, "print-scaling", "fit");
        if (!whole) {
            if (!p.pageRanges)
                throw new IOException("This printer cannot pick pages out of a PDF. "
                        + "Print all pages, or change \"Send as\".");
            boolean first = true;
            int i = 0;
            while (i < list.size()) {
                int from = list.get(i), to = from;
                while (i + 1 < list.size() && list.get(i + 1) == to + 1) {
                    to++;
                    i++;
                }
                i++;
                q.addRange(first ? "page-ranges" : "", from + 1, to + 1);
                first = false;
            }
        }
        submit(p, q, body);
    }

    // ------------------------------------------------------------------- raster

    private int nearest(List<Integer> dpis) {
        int best = 0;
        for (int d : dpis) {
            if (d <= 0) continue;
            if (best == 0 || Math.abs(d - 300) < Math.abs(best - 300)
                    || (Math.abs(d - 300) == Math.abs(best - 300) && d < best)) best = d;
        }
        return best == 0 ? 300 : best;
    }

    private void sendPwg(final Printer p, final Pages.Doc doc, final List<Integer> list,
                         final Progress pr) throws IOException {
        final int dpi = nearest(p.pwgDpi);
        boolean rgb = p.pwgTypes.isEmpty() || p.pwgTypes.contains("srgb_8");
        boolean grey = p.pwgTypes.contains("sgray_8");
        final boolean asColour = rgb && (colour || !grey);
        Ipp.Req q = request(p, "image/pwg-raster");
        submit(p, q, out -> {
            Raster.pwgStart(out);
            for (int i = 0; i < list.size(); i++) {
                check();
                pr.say("Printing page " + (i + 1) + " of " + list.size() + "…");
                Bitmap b = doc.render(list.get(i), paper, dpi);
                try {
                    Raster.pwgPage(out, Pages.rows(b), dpi, asColour, !colour, paper, list.size(), sides);
                } finally {
                    b.recycle();
                }
            }
        });
    }

    private void sendUrf(final Printer p, final Pages.Doc doc, final List<Integer> list,
                         final Progress pr) throws IOException {
        List<Integer> dpis = new ArrayList<>();
        boolean rgb = false, grey = false;
        for (String s : p.urf) {
            if (s.startsWith("RS")) {
                for (String d : s.substring(2).split("-")) {
                    try {
                        dpis.add(Integer.parseInt(d));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (s.startsWith("SRGB24")) {
                rgb = true;
            } else if (s.startsWith("W8")) {
                grey = true;
            }
        }
        final int dpi = nearest(dpis);
        final boolean asColour = (rgb || !grey) && (colour || !grey);
        Ipp.Req q = request(p, "image/urf");
        submit(p, q, out -> {
            Raster.urfStart(out, list.size());
            for (int i = 0; i < list.size(); i++) {
                check();
                pr.say("Printing page " + (i + 1) + " of " + list.size() + "…");
                Bitmap b = doc.render(list.get(i), paper, dpi);
                try {
                    Raster.urfPage(out, Pages.rows(b), dpi, asColour, !colour, sides);
                } finally {
                    b.recycle();
                }
            }
        });
    }

    private void sendJpeg(Printer p, Pages.Doc doc, List<Integer> list, Progress pr) throws IOException {
        final int dpi = 300;
        for (int i = 0; i < list.size(); i++) {
            check();
            pr.say("Printing page " + (i + 1) + " of " + list.size() + "…");
            Bitmap b = doc.render(list.get(i), paper, dpi);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
            try {
                if (!colour) grey(b);
                b.compress(Bitmap.CompressFormat.JPEG, 92, bytes);
            } finally {
                b.recycle();
            }
            final byte[] jpeg = bytes.toByteArray();
            Raster.jpegDensity(jpeg, dpi);
            Ipp.Body body = out -> out.write(jpeg);
            if (p.ipp()) {
                Ipp.Req q = request(p, type(p, "image/jpeg"));
                if (p.scaling) q.add(Ipp.T_KEYWORD, "print-scaling", "fit");
                submit(p, q, body);
            } else {
                for (int c = 0; c < Math.max(1, copies); c++) Ipp.raw(p, body);
            }
        }
    }

    private static void grey(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int g = Raster.luma(row[x]);
                row[x] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    private void sendPcl(final Printer p, final Pages.Doc doc, final List<Integer> list,
                         final Progress pr) throws IOException {
        Ipp.Body body = out -> {
            Raster.pclStart(out, paper, copies, sides);
            for (int i = 0; i < list.size(); i++) {
                check();
                pr.say("Printing page " + (i + 1) + " of " + list.size() + "…");
                Bitmap b = doc.render(list.get(i), paper, Raster.PCL_DPI);
                try {
                    Raster.pclPage(out, Pages.rows(b), !colour, paper);
                } finally {
                    b.recycle();
                }
            }
            Raster.pclEnd(out);
        };
        if (p.ipp()) {
            Ipp.Req q = new Ipp.Req(0x0002, p);
            q.add(Ipp.T_NAME, "requesting-user-name", "PrintDrop");
            q.add(Ipp.T_NAME, "job-name", name);
            q.add(Ipp.T_MIME, "document-format", type(p, "application/vnd.hp-PCL"));
            submit(p, q, body);
        } else {
            Ipp.raw(p, body);
        }
    }
}
