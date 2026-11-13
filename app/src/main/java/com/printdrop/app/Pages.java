package com.printdrop.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Opens PDFs, turns photos into a PDF, and draws pages at print resolution. */
final class Pages {
    private Pages() {}

    /** Copies whatever the user picked into the app's own cache, so it can be read freely. */
    static File copyIn(Context c, Uri u, String name) throws IOException {
        File f = new File(c.getCacheDir(), name);
        try (InputStream in = c.getContentResolver().openInputStream(u);
             OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("The file could not be opened.");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        return f;
    }

    /** Lays each photo out on its own page of the given paper and saves that as a PDF. */
    static File photosToPdf(Context c, List<Uri> photos, Paper paper) throws IOException {
        File f = new File(c.getCacheDir(), "photos.pdf");
        PdfDocument doc = new PdfDocument();
        try {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            int n = 0;
            for (Uri u : photos) {
                Bitmap b = photo(c, u);
                if (b == null) continue;
                PdfDocument.Page page = doc.startPage(
                        new PdfDocument.PageInfo.Builder(paper.wPt, paper.hPt, ++n).create());
                Canvas cv = page.getCanvas();
                float bw = b.getWidth(), bh = b.getHeight();
                boolean turn = bw > bh;
                float cw = turn ? bh : bw, ch = turn ? bw : bh;
                float margin = 14;
                float s = Math.min((paper.wPt - 2 * margin) / cw, (paper.hPt - 2 * margin) / ch);
                Matrix m = new Matrix();
                if (turn) {
                    m.postRotate(90);
                    m.postTranslate(bh, 0);
                }
                m.postScale(s, s);
                m.postTranslate((paper.wPt - cw * s) / 2, (paper.hPt - ch * s) / 2);
                cv.drawBitmap(b, m, paint);
                doc.finishPage(page);
                b.recycle();
            }
            if (n == 0) throw new IOException("The photos could not be opened.");
            try (OutputStream out = new FileOutputStream(f)) {
                doc.writeTo(out);
            }
        } finally {
            doc.close();
        }
        return f;
    }

    /** Loads a photo upright, no larger than needed for a sharp print. */
    private static Bitmap photo(Context c, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int longest = Math.max(o.outWidth, o.outHeight);
            if (longest <= 0) return null;
            int sample = 1;
            while (longest / (sample * 2) >= 3000) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            int turn = 0;
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                if (in != null) {
                    int or = new ExifInterface(in).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (or == ExifInterface.ORIENTATION_ROTATE_90) turn = 90;
                    else if (or == ExifInterface.ORIENTATION_ROTATE_180) turn = 180;
                    else if (or == ExifInterface.ORIENTATION_ROTATE_270) turn = 270;
                }
            } catch (Exception ignored) {
            }
            if (turn != 0) {
                Matrix m = new Matrix();
                m.postRotate(turn);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** An open PDF. */
    static final class Doc implements Closeable {
        private final ParcelFileDescriptor fd;
        private final PdfRenderer renderer;
        final int count;

        Doc(File f) throws IOException {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                renderer = new PdfRenderer(fd);
            } catch (SecurityException e) {
                fd.close();
                throw new IOException("This PDF is password-protected.");
            } catch (Exception e) {
                fd.close();
                throw new IOException("This PDF could not be opened.");
            }
            count = renderer.getPageCount();
        }

        /** The paper a page belongs on: its own size if that is a known one, else the smallest that holds it. */
        Paper paperFor(int index) {
            float w, h;
            try (PdfRenderer.Page p = renderer.openPage(index)) {
                w = Math.min(p.getWidth(), p.getHeight());
                h = Math.max(p.getWidth(), p.getHeight());
            }
            for (Paper x : Paper.ALL)
                if (Math.abs(w - x.wPt) <= 8 && Math.abs(h - x.hPt) <= 8) return x;
            if (w <= Paper.A4.wPt + 8 && h <= Paper.A4.hPt + 8) return Paper.A4;
            return Paper.A3;
        }

        /** "A4 and A3" - the papers this document's pages call for. */
        String sizes() {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (int i = 0; i < count && i < 500; i++) set.add(paperFor(i).label);
            return String.join(" and ", set);
        }

        /** True when every listed page is already the size of the paper (either way up). */
        boolean fits(List<Integer> pages, Paper paper) {
            for (int i : pages) {
                try (PdfRenderer.Page p = renderer.openPage(i)) {
                    float w = Math.min(p.getWidth(), p.getHeight());
                    float h = Math.max(p.getWidth(), p.getHeight());
                    if (Math.abs(w - paper.wPt) > 6 || Math.abs(h - paper.hPt) > 6) return false;
                }
            }
            return true;
        }

        /**
         * Draws one page onto a white sheet of the given paper. A page that is already the
         * paper's size is drawn at true scale; anything else is shrunk or enlarged to fit
         * inside a small margin. Landscape pages are turned.
         */
        Bitmap render(int index, Paper paper, int dpi) throws IOException {
            int W = paper.wPx(dpi), H = paper.hPx(dpi);
            Bitmap b;
            try {
                b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                throw new IOException("Not enough memory for this paper size. Close other apps and try again.");
            }
            b.eraseColor(Color.WHITE);
            try (PdfRenderer.Page p = renderer.openPage(index)) {
                float pw = p.getWidth(), ph = p.getHeight();
                boolean turn = pw > ph;
                float cw = turn ? ph : pw, ch = turn ? pw : ph;
                boolean same = Math.abs(cw - paper.wPt) <= 6 && Math.abs(ch - paper.hPt) <= 6;
                float s, dx, dy;
                if (same) {
                    s = W / cw;
                    dx = 0;
                    dy = 0;
                } else {
                    float margin = dpi * 0.17f;
                    s = Math.min((W - 2 * margin) / cw, (H - 2 * margin) / ch);
                    dx = (W - cw * s) / 2;
                    dy = (H - ch * s) / 2;
                }
                Matrix m = new Matrix();
                if (turn) {
                    m.postRotate(90);
                    m.postTranslate(ph, 0);
                }
                m.postScale(s, s);
                m.postTranslate(dx, dy);
                p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            }
            return b;
        }

        @Override public void close() {
            try {
                renderer.close();
            } catch (Exception ignored) {
            }
            try {
                fd.close();
            } catch (Exception ignored) {
            }
        }
    }

    static Rows rows(final Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        return new Rows() {
            @Override public int width() {
                return w;
            }

            @Override public int height() {
                return h;
            }

            @Override public void row(int y, int[] out) {
                b.getPixels(out, 0, w, 0, y, w, 1);
            }
        };
    }
}
