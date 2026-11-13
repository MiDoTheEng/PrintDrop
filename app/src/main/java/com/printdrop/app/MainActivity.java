package com.printdrop.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The whole app: pick a printer, pick a file, print. */
public class MainActivity extends Activity {
    private static final int PICK_PDF = 1, PICK_PHOTOS = 2;
    private static final String[] SIDES = {"One-sided", "Two-sided, flip on long edge",
            "Two-sided, flip on short edge"};

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout column;

    private Printer printer;
    private File pdf;                       // a picked PDF, copied into the cache
    private List<Uri> photos;               // or picked photos
    private String docName = "";
    private int pageCount;
    private String pageSpec = "";           // "" = all pages
    private Paper paper = Paper.A4;
    private boolean autoPaper = true;       // match each page's own size
    private String docSizes = "";
    private int copies = 1, sides, sendAs;
    private boolean colour = true;
    private PrintJob running;

    private TextView printerName, printerInfo, fileName, fileInfo, status, printButton;
    private TextView vCopies, vColour, vPaper, vSides, vPages, vSendAs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("printdrop", MODE_PRIVATE);
        printer = Printer.load(prefs.getString("printer", null));
        String savedPaper = prefs.getString("paper", "Auto");
        autoPaper = savedPaper.equals("Auto");
        paper = Paper.byLabel(savedPaper);
        colour = prefs.getBoolean("colour", true);
        sides = prefs.getInt("sides", 0);
        sendAs = prefs.getInt("sendAs", 0);
        build();
        refresh();
        if (printer != null) probe(printer);
        take(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        take(i);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fitColumn();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    // ------------------------------------------------------------------- screen

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        FrameLayout frame = new FrameLayout(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame);
        setContentView(scroll);
        fitColumn();

        TextView title = Ui.label(this, "PrintDrop", 28, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        TextView sub = Ui.label(this, "Find a printer on the Wi-Fi and print to it.", 14, Ui.DIM);
        sub.setPadding(0, 0, 0, Ui.dp(this, 14));
        column.addView(sub);

        // Printer
        LinearLayout pc = Ui.card(this, "Printer");
        printerName = Ui.label(this, "", 18, Ui.TEXT);
        printerName.setTypeface(Typeface.DEFAULT_BOLD);
        pc.addView(printerName);
        printerInfo = Ui.label(this, "", 13, Ui.DIM);
        printerInfo.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
        pc.addView(printerInfo);
        TextView find = Ui.button(this, "Find printers", false);
        find.setOnClickListener(v -> findPrinters());
        TextView ip = Ui.button(this, "Enter address", false);
        ip.setOnClickListener(v -> enterAddress());
        pc.addView(pair(find, ip));
        addCard(pc);

        // File
        LinearLayout fc = Ui.card(this, "What to print");
        fileName = Ui.label(this, "", 18, Ui.TEXT);
        fileName.setTypeface(Typeface.DEFAULT_BOLD);
        fc.addView(fileName);
        fileInfo = Ui.label(this, "", 13, Ui.DIM);
        fileInfo.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
        fc.addView(fileInfo);
        TextView pickPdf = Ui.button(this, "Choose PDF", false);
        pickPdf.setOnClickListener(v -> pick(PICK_PDF));
        TextView pickPhotos = Ui.button(this, "Choose photos", false);
        pickPhotos.setOnClickListener(v -> pick(PICK_PHOTOS));
        fc.addView(pair(pickPdf, pickPhotos));
        addCard(fc);

        // Options
        LinearLayout oc = Ui.card(this, "Options");
        copiesRow(oc);
        vColour = Ui.setting(this, oc, "Colour", v -> {
            colour = !colour;
            save();
            refresh();
        });
        vPaper = Ui.setting(this, oc, "Paper", v -> {
            String[] names = new String[Paper.ALL.length + 1];
            names[0] = "Automatic (match each page of the file)";
            for (int i = 0; i < Paper.ALL.length; i++) names[i + 1] = Paper.ALL[i].label;
            choose("Paper", names, w -> {
                autoPaper = w == 0;
                if (w > 0) paper = Paper.ALL[w - 1];
            });
        });
        vSides = Ui.setting(this, oc, "Sides", v -> choose("Sides", SIDES, w -> sides = w));
        vPages = Ui.setting(this, oc, "Pages", v -> askPages());
        vSendAs = Ui.setting(this, oc, "Send as",
                v -> choose("Send as", PrintJob.SEND_AS, w -> sendAs = w));
        addCard(oc);

        printButton = Ui.button(this, "Print", true);
        printButton.setOnClickListener(v -> print());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        column.addView(printButton, lp);

        status = Ui.label(this, "", 14, Ui.DIM);
        status.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 24));
        column.addView(status);
    }

    /** Keeps the content a comfortable width on a tablet. */
    private void fitColumn() {
        if (column == null) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        int max = Ui.dp(this, 620);
        int side = Math.max(Ui.dp(this, 16), (screen - max) / 2);
        column.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 16));
    }

    private void addCard(View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        column.addView(card, lp);
    }

    private LinearLayout pair(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        la.rightMargin = Ui.dp(this, 5);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lb.leftMargin = Ui.dp(this, 5);
        row.addView(a, la);
        row.addView(b, lb);
        return row;
    }

    private void copiesRow(LinearLayout into) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 52));
        row.addView(Ui.label(this, "Copies", 16, Ui.TEXT), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView minus = Ui.button(this, "−", false);
        TextView plus = Ui.button(this, "+", false);
        vCopies = Ui.label(this, "1", 18, Ui.ACCENT);
        vCopies.setTypeface(Typeface.DEFAULT_BOLD);
        vCopies.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> {
            copies = Math.max(1, copies - 1);
            refresh();
        });
        plus.setOnClickListener(v -> {
            copies = Math.min(99, copies + 1);
            refresh();
        });
        int w = Ui.dp(this, 48);
        row.addView(minus, new LinearLayout.LayoutParams(w, Ui.dp(this, 44)));
        row.addView(vCopies, new LinearLayout.LayoutParams(Ui.dp(this, 52),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(w, Ui.dp(this, 44)));
        into.addView(row);
    }

    private interface Picked {
        void at(int which);
    }

    private void choose(String title, String[] items, Picked p) {
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, w) -> {
            p.at(w);
            save();
            refresh();
        }).show();
    }

    private void refresh() {
        if (printer == null) {
            printerName.setText("No printer yet");
            printerInfo.setText("Tap Find printers while on the same Wi-Fi as the printer.");
        } else {
            printerName.setText(printer.title());
            printerInfo.setText(printer.summary());
        }
        if (pageCount == 0) {
            fileName.setText("Nothing chosen");
            fileInfo.setText("Choose a PDF or photos, or share a file to PrintDrop from another app.");
        } else {
            fileName.setText(docName);
            fileInfo.setText((pageCount == 1 ? "1 page" : pageCount + " pages")
                    + (docSizes.isEmpty() ? "" : " · " + docSizes));
        }
        vCopies.setText(String.valueOf(copies));
        vColour.setText(colour ? "Colour" : "Black & white");
        vPaper.setText(autoPaper ? "Automatic" : paper.label);
        vSides.setText(sides == 0 ? "One-sided" : sides == 1 ? "Two-sided (long edge)"
                : "Two-sided (short edge)");
        vPages.setText(pageSpec.isEmpty() ? "All" : pageSpec);
        vSendAs.setText(PrintJob.SEND_AS[sendAs]);
        printButton.setText(running != null ? "Cancel" : "Print");
        printButton.setAlpha(printer != null && pageCount > 0 ? 1f : 0.45f);
    }

    private void save() {
        prefs.edit().putString("printer", printer == null ? null : printer.save())
                .putString("paper", autoPaper ? "Auto" : paper.label).putBoolean("colour", colour)
                .putInt("sides", sides).putInt("sendAs", sendAs).apply();
    }

    private void say(String s, boolean bad) {
        status.setTextColor(bad ? Ui.BAD : Ui.DIM);
        status.setText(s);
    }

    // ------------------------------------------------------------------ printer

    private void use(Printer p) {
        printer = p;
        save();
        refresh();
        if (!p.probed) probe(p);
    }

    private void probe(final Printer p) {
        work.execute(() -> {
            Ipp.probe(p);
            runOnUiThread(() -> {
                if (printer == p) {
                    save();
                    refresh();
                }
            });
        });
    }

    private void findPrinters() {
        final List<Printer> shown = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Looking for printers…").setView(list)
                .setNegativeButton("Close", null).create();
        final Finder finder = new Finder(this, (printers, looking) -> {
            shown.clear();
            shown.addAll(printers);
            adapter.clear();
            for (Printer p : printers) adapter.add(p.title() + "\n" + p.host);
            adapter.notifyDataSetChanged();
            if (looking) dialog.setTitle("Looking for printers…");
            else dialog.setTitle(printers.isEmpty() ? "No printers found" : "Choose a printer");
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos < shown.size()) use(shown.get(pos));
            dialog.dismiss();
        });
        dialog.setOnDismissListener(d -> finder.stop());
        dialog.show();
        finder.start();
    }

    private void enterAddress() {
        final EditText e = new EditText(this);
        e.setHint("192.168.1.50");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine(true);
        if (printer != null) e.setText(printer.host);
        FrameLayout wrap = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(e);
        new AlertDialog.Builder(this).setTitle("Printer address")
                .setMessage("The IP address shown on the printer's network settings page.")
                .setView(wrap)
                .setPositiveButton("Use", (d, w) -> {
                    String host = e.getText().toString().trim();
                    if (host.isEmpty()) return;
                    Printer np = new Printer();
                    np.host = host;
                    use(np);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // --------------------------------------------------------------------- file

    private void pick(int what) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (what == PICK_PDF) {
            i.setType("application/pdf");
        } else {
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, what);
        } catch (Exception e) {
            try {
                i.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(i, what);
            } catch (Exception e2) {
                say("No file picker was found on this device.", true);
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        List<Uri> uris = uris(data);
        if (uris.isEmpty()) return;
        if (req == PICK_PDF) openPdf(uris.get(0));
        else openPhotos(uris);
    }

    private static List<Uri> uris(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        if (out.isEmpty() && data.getData() != null) out.add(data.getData());
        return out;
    }

    /** A file opened with, or shared to, PrintDrop from another app. */
    private void take(Intent i) {
        if (i == null || i.getAction() == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = i.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            if (i.getData() != null) uris.add(i.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (uris.isEmpty()) return;
        String type = i.getType();
        if (type == null) type = getContentResolver().getType(uris.get(0));
        if (type == null) type = "";
        String name = Ui.displayName(this, uris.get(0)).toLowerCase();
        if (type.equals("application/pdf") || name.endsWith(".pdf")) openPdf(uris.get(0));
        else openPhotos(uris);
    }

    private void openPdf(final Uri u) {
        final String name = Ui.displayName(this, u);
        say("Opening " + name + "…", false);
        work.execute(() -> {
            try {
                File f = Pages.copyIn(this, u, "document.pdf");
                int n;
                String sz;
                try (Pages.Doc d = new Pages.Doc(f)) {
                    n = d.count;
                    sz = d.sizes();
                }
                final int count = n;
                final String sizes = sz;
                runOnUiThread(() -> {
                    pdf = f;
                    docSizes = sizes;
                    photos = null;
                    docName = name;
                    pageCount = count;
                    pageSpec = "";
                    say("", false);
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> say(message(e), true));
            }
        });
    }

    private void openPhotos(List<Uri> uris) {
        photos = new ArrayList<>(uris);
        pdf = null;
        docSizes = "";
        pageCount = photos.size();
        docName = pageCount == 1 ? Ui.displayName(this, photos.get(0)) : pageCount + " photos";
        pageSpec = "";
        say("", false);
        refresh();
    }

    private void askPages() {
        if (pageCount == 0) {
            say("Choose something to print first.", false);
            return;
        }
        final EditText e = new EditText(this);
        e.setHint("All — or for example 1-3, 5");
        e.setSingleLine(true);
        e.setText(pageSpec);
        FrameLayout wrap = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(e);
        new AlertDialog.Builder(this).setTitle("Pages (of " + pageCount + ")").setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (s.equalsIgnoreCase("all")) s = "";
                    if (!s.isEmpty() && parsePages(s, pageCount).isEmpty()) {
                        say("Those pages are not in this document.", true);
                        return;
                    }
                    pageSpec = s;
                    refresh();
                })
                .setNeutralButton("All", (d, w) -> {
                    pageSpec = "";
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** "1-3, 5" becomes pages 0,1,2,4. Empty means every page. */
    static List<Integer> parsePages(String spec, int count) {
        TreeSet<Integer> set = new TreeSet<>();
        if (spec == null || spec.trim().isEmpty()) {
            for (int i = 0; i < count; i++) set.add(i);
            return new ArrayList<>(set);
        }
        for (String part : spec.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                String[] ends = part.split("-", -1);
                int from = Integer.parseInt(ends[0].trim());
                int to = ends.length > 1 ? (ends[1].trim().isEmpty() ? count
                        : Integer.parseInt(ends[1].trim())) : from;
                for (int i = Math.max(1, from); i <= Math.min(count, to); i++) set.add(i - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(set);
    }

    // -------------------------------------------------------------------- print

    private void print() {
        if (running != null) {
            running.cancelled = true;
            say("Cancelling…", false);
            return;
        }
        if (printer == null) {
            say("Choose a printer first.", true);
            return;
        }
        if (pageCount == 0) {
            say("Choose something to print first.", true);
            return;
        }
        final PrintJob job = new PrintJob();
        job.printer = printer;
        job.name = docName.isEmpty() ? "PrintDrop" : docName;
        job.paper = paper;
        job.autoPaper = autoPaper && pdf != null;
        job.copies = copies;
        job.colour = colour;
        job.sides = sides;
        job.sendAs = sendAs;
        job.pages = parsePages(pageSpec, pageCount);
        final File source = pdf;
        final List<Uri> pics = photos;
        running = job;
        refresh();
        say("Getting ready…", false);
        work.execute(() -> {
            String result;
            boolean bad = false;
            try {
                job.pdf = source != null ? source : Pages.photosToPdf(this, pics, job.paper);
                result = job.run(s -> runOnUiThread(() -> {
                    if (running == job) say(s, false);
                }));
            } catch (Exception | OutOfMemoryError e) {
                bad = true;
                result = message(e);
            }
            final String text = result;
            final boolean failed = bad;
            runOnUiThread(() -> {
                running = null;
                save();
                refresh();
                say(text, failed);
            });
        });
    }

    private static String message(Throwable e) {
        if (e instanceof OutOfMemoryError)
            return "Not enough memory for this job. Try fewer pages or a smaller paper size.";
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.UnknownHostException)
            return "The printer could not be reached. Check that this device is on the same "
                    + "Wi-Fi as the printer.";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Something went wrong: " + e.getClass().getSimpleName() : m;
    }
}
