package com.printdrop.app;

/** A rendered page, read one row of pixels at a time. */
interface Rows {
    int width();

    int height();

    /** Fills out[0..width) with the pixels of row y as 0xAARRGGBB. */
    void row(int y, int[] out);
}
