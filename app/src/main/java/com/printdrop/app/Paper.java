package com.printdrop.app;

/** A paper size the app can print on. Always portrait; landscape pages are turned to fit. */
final class Paper {
    final String label;
    /** The name printers know this size by. */
    final String ipp;
    /** Size in points (1/72 inch). */
    final int wPt, hPt;
    final double wMm, hMm;
    /** PCL paper code. */
    final int pcl;
    /** PCL left offset of the printable page, in dots at 300 dpi. */
    final int pclOffset;

    private Paper(String label, String ipp, int wPt, int hPt, double wMm, double hMm,
                  int pcl, int pclOffset) {
        this.label = label;
        this.ipp = ipp;
        this.wPt = wPt;
        this.hPt = hPt;
        this.wMm = wMm;
        this.hMm = hMm;
        this.pcl = pcl;
        this.pclOffset = pclOffset;
    }

    static final Paper A4 = new Paper("A4", "iso_a4_210x297mm", 595, 842, 210, 297, 26, 71);
    static final Paper A3 = new Paper("A3", "iso_a3_297x420mm", 842, 1191, 297, 420, 27, 71);
    static final Paper LETTER = new Paper("Letter", "na_letter_8.5x11in", 612, 792, 215.9, 279.4, 2, 75);
    static final Paper[] ALL = {A4, A3, LETTER};

    int wPx(int dpi) {
        return (int) Math.round(wMm / 25.4 * dpi);
    }

    int hPx(int dpi) {
        return (int) Math.round(hMm / 25.4 * dpi);
    }

    static Paper byLabel(String s) {
        for (Paper p : ALL) if (p.label.equals(s)) return p;
        return A4;
    }
}
