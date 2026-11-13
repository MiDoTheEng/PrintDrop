package com.printdrop.app;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes rendered pages in the formats printers accept when they cannot take a PDF:
 * PWG raster (Mopria / IPP Everywhere), Apple raster (AirPrint) and PCL 5c.
 */
final class Raster {
    private Raster() {}

    // ---------------------------------------------------------------- PWG raster

    static void pwgStart(OutputStream out) throws IOException {
        out.write(new byte[]{'R', 'a', 'S', '2'});
    }

    /** sides: 0 one-sided, 1 two-sided long edge, 2 two-sided short edge. */
    static void pwgPage(OutputStream o, Rows r, int dpi, boolean colour, boolean grey,
                        Paper paper, int totalPages, int sides) throws IOException {
        byte[] h = new byte[1796];
        int w = r.width(), ht = r.height();
        int bpp = colour ? 24 : 8;
        str(h, 0, "PwgRaster");
        int32(h, 272, sides > 0 ? 1 : 0);          // Duplex
        int32(h, 276, dpi);
        int32(h, 280, dpi);
        int32(h, 340, 1);                          // NumCopies
        int32(h, 352, paper.wPt);
        int32(h, 356, paper.hPt);
        int32(h, 368, sides == 2 ? 1 : 0);         // Tumble
        int32(h, 372, w);
        int32(h, 376, ht);
        int32(h, 384, 8);                          // bits per colour
        int32(h, 388, bpp);
        int32(h, 392, w * bpp / 8);                // bytes per line
        int32(h, 396, 0);                          // chunky
        int32(h, 400, colour ? 19 : 18);           // sRGB : sGray
        int32(h, 420, colour ? 3 : 1);
        int32(h, 452, totalPages);
        int32(h, 456, 1);                          // CrossFeedTransform
        int32(h, 460, 1);                          // FeedTransform
        int32(h, 464, 0);                          // image box
        int32(h, 468, 0);
        int32(h, 472, w);
        int32(h, 476, ht);
        int32(h, 480, 0xFFFFFF);                   // AlternatePrimary: white
        int32(h, 484, 4);                          // normal quality
        str(h, 1732, paper.ipp);
        o.write(h);
        lines(o, r, colour, grey);
    }

    // -------------------------------------------------------------- Apple raster

    static void urfStart(OutputStream out, int pages) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.write("UNIRAST\0".getBytes(StandardCharsets.US_ASCII));
        d.writeInt(pages);
        d.flush();
    }

    static void urfPage(OutputStream o, Rows r, int dpi, boolean colour, boolean grey, int sides)
            throws IOException {
        byte[] h = new byte[32];
        h[0] = (byte) (colour ? 24 : 8);
        h[1] = (byte) (colour ? 1 : 0);
        h[2] = (byte) (sides == 1 ? 3 : sides == 2 ? 2 : 1);
        h[3] = 4;
        int32(h, 12, r.width());
        int32(h, 16, r.height());
        int32(h, 20, dpi);
        o.write(h);
        lines(o, r, colour, grey);
    }

    /**
     * The pixel coding PWG and Apple raster share: each line starts with how many more times it
     * repeats, then runs of pixels (0..127 = repeat the next pixel n+1 times,
     * 129..255 = 257-n pixels follow as they are).
     */
    static void lines(OutputStream o, Rows r, boolean colour, boolean grey) throws IOException {
        int w = r.width(), h = r.height();
        int bytes = colour ? 3 : 1;
        int[] cur = new int[w], next = new int[w];
        byte[] buf = new byte[w * (bytes + 1) + 16];
        boolean haveNext = false;
        int y = 0;
        while (y < h) {
            if (haveNext) {
                int[] t = cur;
                cur = next;
                next = t;
                haveNext = false;
            } else {
                load(r, y, cur, colour, grey);
            }
            int rep = 1;
            while (y + rep < h && rep < 256) {
                load(r, y + rep, next, colour, grey);
                if (same(cur, next, w)) {
                    rep++;
                } else {
                    haveNext = true;
                    break;
                }
            }
            int n = 0;
            buf[n++] = (byte) (rep - 1);
            int i = 0;
            while (i < w) {
                int run = 1;
                while (i + run < w && run < 128 && cur[i + run] == cur[i]) run++;
                if (run > 1) {
                    buf[n++] = (byte) (run - 1);
                    n = put(buf, n, cur[i], bytes);
                    i += run;
                } else {
                    int j = i + 1;
                    while (j < w && j - i < 128 && !(j + 1 < w && cur[j] == cur[j + 1])) j++;
                    int lit = j - i;
                    if (lit == 1) {
                        buf[n++] = 0;
                        n = put(buf, n, cur[i], bytes);
                    } else {
                        buf[n++] = (byte) (257 - lit);
                        for (int k = i; k < j; k++) n = put(buf, n, cur[k], bytes);
                    }
                    i = j;
                }
            }
            o.write(buf, 0, n);
            y += rep;
        }
    }

    private static void load(Rows r, int y, int[] px, boolean colour, boolean grey) {
        r.row(y, px);
        int w = px.length;
        for (int i = 0; i < w; i++) {
            int p = px[i];
            if (!colour) {
                px[i] = luma(p);
            } else if (grey) {
                int g = luma(p);
                px[i] = (g << 16) | (g << 8) | g;
            } else {
                px[i] = p & 0xFFFFFF;
            }
        }
    }

    static int luma(int p) {
        return (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
    }

    private static boolean same(int[] a, int[] b, int w) {
        for (int i = 0; i < w; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static int put(byte[] b, int n, int p, int bytes) {
        if (bytes == 3) {
            b[n++] = (byte) (p >> 16);
            b[n++] = (byte) (p >> 8);
        }
        b[n++] = (byte) p;
        return n;
    }

    // -------------------------------------------------------------------- PCL 5c

    static final int PCL_DPI = 300;
    private static final String ESC = "\u001b";

    static void pclStart(OutputStream o, Paper paper, int copies, int sides) throws IOException {
        ascii(o, ESC + "%-12345X@PJL JOB NAME=\"PrintDrop\"\r\n@PJL ENTER LANGUAGE=PCL\r\n");
        ascii(o, ESC + "E");
        ascii(o, ESC + "&l" + paper.pcl + "A");
        ascii(o, ESC + "&l0O");
        ascii(o, ESC + "&l" + sides + "S");
        ascii(o, ESC + "&l" + Math.max(1, copies) + "X");
        ascii(o, ESC + "&l0E");
        ascii(o, ESC + "*t" + PCL_DPI + "R");
    }

    /** The page must have been rendered at PCL_DPI for the given paper. */
    static void pclPage(OutputStream o, Rows r, boolean grey, Paper paper) throws IOException {
        int off = paper.pclOffset;
        int w = r.width() - 2 * off, h = r.height();
        ascii(o, ESC + "*p0x0Y");
        ascii(o, ESC + "*v6W");
        o.write(new byte[]{0, 3, 0, 8, 8, 8});   // RGB, one pixel = three bytes
        ascii(o, ESC + "*r" + w + "s" + h + "T");
        ascii(o, ESC + "*r1A");
        ascii(o, ESC + "*b2M");
        int[] px = new int[r.width()];
        byte[] raw = new byte[w * 3];
        byte[] packed = new byte[w * 3 + w * 3 / 64 + 16];
        for (int y = 0; y < h; y++) {
            r.row(y, px);
            int n = 0;
            for (int x = 0; x < w; x++) {
                int p = px[x + off];
                if (grey) {
                    byte g = (byte) luma(p);
                    raw[n++] = g;
                    raw[n++] = g;
                    raw[n++] = g;
                } else {
                    raw[n++] = (byte) (p >> 16);
                    raw[n++] = (byte) (p >> 8);
                    raw[n++] = (byte) p;
                }
            }
            int len = packBits(raw, n, packed);
            ascii(o, ESC + "*b" + len + "W");
            o.write(packed, 0, len);
        }
        ascii(o, ESC + "*rC");
        o.write(0x0C);
    }

    static void pclEnd(OutputStream o) throws IOException {
        ascii(o, ESC + "E" + ESC + "%-12345X@PJL EOJ\r\n" + ESC + "%-12345X");
    }

    /** TIFF PackBits over bytes. Returns the packed length. */
    static int packBits(byte[] in, int len, byte[] out) {
        int n = 0, i = 0;
        while (i < len) {
            int run = 1;
            while (i + run < len && run < 128 && in[i + run] == in[i]) run++;
            if (run > 1) {
                out[n++] = (byte) (1 - run);
                out[n++] = in[i];
                i += run;
            } else {
                int j = i + 1;
                while (j < len && j - i < 128 && !(j + 1 < len && in[j] == in[j + 1])) j++;
                out[n++] = (byte) (j - i - 1);
                System.arraycopy(in, i, out, n, j - i);
                n += j - i;
                i = j;
            }
        }
        return n;
    }

    // --------------------------------------------------------------------- JPEG

    /** Marks a JPEG made by Android with its real resolution, so printers size it correctly. */
    static void jpegDensity(byte[] j, int dpi) {
        if (j.length > 18 && (j[0] & 255) == 0xFF && (j[1] & 255) == 0xD8
                && (j[2] & 255) == 0xFF && (j[3] & 255) == 0xE0
                && j[6] == 'J' && j[7] == 'F' && j[8] == 'I' && j[9] == 'F') {
            j[13] = 1;
            j[14] = (byte) (dpi >> 8);
            j[15] = (byte) dpi;
            j[16] = (byte) (dpi >> 8);
            j[17] = (byte) dpi;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void ascii(OutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void int32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void str(byte[] b, int at, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(x, 0, b, at, Math.min(63, x.length));
    }
}
