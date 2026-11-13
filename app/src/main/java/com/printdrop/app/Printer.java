package com.printdrop.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A printer on the network and what it told us it can do. */
final class Printer {
    String name = "";
    String host = "";
    /** 0 when the printer does not answer IPP. */
    int ippPort;
    String ippPath = "/ipp/print";
    /** Raw (port 9100) printing; 0 when closed. */
    int rawPort;

    // Filled in by Ipp.probe()
    boolean probed;
    String model = "";
    final Set<String> formats = new HashSet<>();
    final List<Integer> pwgDpi = new ArrayList<>();
    final Set<String> pwgTypes = new HashSet<>();
    final Set<String> urf = new HashSet<>();
    final Set<String> media = new HashSet<>();
    boolean pageRanges, scaling;

    boolean ipp() {
        return ippPort > 0;
    }

    boolean has(String format) {
        for (String f : formats) if (f.equalsIgnoreCase(format)) return true;
        return false;
    }

    String title() {
        if (!model.isEmpty()) return model;
        if (!name.isEmpty()) return name;
        return host;
    }

    /** One line saying how the app will talk to this printer. */
    String summary() {
        if (!probed) return host + " · checking…";
        if (!ipp() && rawPort == 0) return host + " · not answering";
        StringBuilder s = new StringBuilder(host).append(" · ");
        if (!ipp()) return s.append("raw port ").append(rawPort).toString();
        s.append("IPP");
        List<String> f = new ArrayList<>();
        if (has("application/pdf")) f.add("PDF");
        if (has("image/pwg-raster")) f.add("PWG");
        if (has("image/urf")) f.add("AirPrint");
        if (has("image/jpeg")) f.add("JPEG");
        if (has("application/vnd.hp-PCL")) f.add("PCL");
        if (!f.isEmpty()) s.append(" · ").append(String.join(", ", f));
        return s.toString();
    }

    void clearCaps() {
        probed = false;
        model = "";
        formats.clear();
        pwgDpi.clear();
        pwgTypes.clear();
        urf.clear();
        media.clear();
        pageRanges = false;
        scaling = false;
    }

    String save() {
        return name.replace('|', ' ') + "|" + host + "|" + ippPort + "|" + ippPath + "|" + rawPort;
    }

    static Printer load(String s) {
        if (s == null) return null;
        String[] p = s.split("\\|", -1);
        if (p.length < 5 || p[1].isEmpty()) return null;
        try {
            Printer r = new Printer();
            r.name = p[0];
            r.host = p[1];
            r.ippPort = Integer.parseInt(p[2]);
            r.ippPath = p[3];
            r.rawPort = Integer.parseInt(p[4]);
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
