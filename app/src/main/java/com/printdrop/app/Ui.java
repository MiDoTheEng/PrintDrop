package com.printdrop.app;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small shared helpers: colours, sizes, simple views. */
final class Ui {
    static final int BG = 0xFF14171C;
    static final int CARD = 0xFF1E232B;
    static final int CHIP = 0xFF2A313B;
    static final int TEXT = 0xFFE8EAED;
    static final int DIM = 0xFF9AA4B1;
    static final int ACCENT = 0xFF2EC4B6;
    static final int ON_ACCENT = 0xFF06231F;
    static final int BAD = 0xFFFF8A80;
    static final int GOOD = 0xFF5FD38D;
    static final int ON_GOOD = 0xFF06230F;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView label(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A rounded button. */
    static TextView button(Context c, String s, boolean primary) {
        TextView t = label(c, s, primary ? 17 : 15, primary ? ON_ACCENT : TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 14);
        t.setPadding(p, 0, p, 0);
        t.setMinHeight(dp(c, primary ? 56 : 46));
        t.setBackground(round(primary ? ACCENT : CHIP, dp(c, primary ? 16 : 12)));
        t.setClickable(true);
        return t;
    }

    /** A card with a small heading; add rows to it. */
    static LinearLayout card(Context c, String heading) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 14), p, p);
        l.setBackground(round(CARD, dp(c, 18)));
        TextView h = label(c, heading.toUpperCase(), 12, ACCENT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, 0, 0, dp(c, 8));
        l.addView(h);
        return l;
    }

    /** "Paper ............ A4": a setting row. Returns the value view; tap anywhere on the row. */
    static TextView setting(Context c, LinearLayout into, String name, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        TextView n = label(c, name, 16, TEXT);
        row.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView v = label(c, "", 16, ACCENT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        row.setOnClickListener(tap);
        into.addView(row);
        return v;
    }

    static String displayName(Context c, Uri u) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(u,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null) name = "Document";
        return name;
    }
}
