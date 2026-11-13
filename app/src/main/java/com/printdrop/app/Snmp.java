package com.printdrop.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Asks a printer for its model name over SNMP, which nearly every office printer answers. */
final class Snmp {
    private Snmp() {}

    /** hrDeviceDescr.1 - the device's own description of itself. */
    private static final byte[] DEVICE = {0x2B, 6, 1, 2, 1, 25, 3, 2, 1, 3, 1};
    /** sysDescr.0 */
    private static final byte[] SYSTEM = {0x2B, 6, 1, 2, 1, 1, 1, 0};

    /** The model name, or "" when the printer does not say. Blocks for up to about two seconds. */
    static String model(String host) {
        String s = get(host, DEVICE);
        if (s.isEmpty()) s = get(host, SYSTEM);
        return s;
    }

    static byte[] request(byte[] oid) {
        byte[] community = "public".getBytes(StandardCharsets.US_ASCII);
        int bind = 2 + oid.length + 2;
        int binds = 2 + bind;
        int pdu = 6 + 3 + 3 + 2 + binds;
        int all = 3 + 2 + community.length + 2 + pdu;
        byte[] b = new byte[2 + all];
        int n = 0;
        b[n++] = 0x30;
        b[n++] = (byte) all;
        b[n++] = 2;
        b[n++] = 1;
        b[n++] = 0;
        b[n++] = 4;
        b[n++] = (byte) community.length;
        System.arraycopy(community, 0, b, n, community.length);
        n += community.length;
        b[n++] = (byte) 0xA0;
        b[n++] = (byte) pdu;
        b[n++] = 2;
        b[n++] = 4;
        b[n++] = 0x50;
        b[n++] = 0x44;
        b[n++] = 0x52;
        b[n++] = 0x50;
        b[n++] = 2;
        b[n++] = 1;
        b[n++] = 0;
        b[n++] = 2;
        b[n++] = 1;
        b[n++] = 0;
        b[n++] = 0x30;
        b[n++] = (byte) binds;
        b[n++] = 0x30;
        b[n++] = (byte) bind;
        b[n++] = 6;
        b[n++] = (byte) oid.length;
        System.arraycopy(oid, 0, b, n, oid.length);
        n += oid.length;
        b[n++] = 5;
        b[n] = 0;
        return b;
    }

    private static String get(String host, byte[] oid) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1000);
            byte[] q = request(oid);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(host), 161));
            byte[] in = new byte[1500];
            DatagramPacket r = new DatagramPacket(in, in.length);
            s.receive(r);
            return parse(in, r.getLength());
        } catch (Exception e) {
            return "";
        }
    }

    private static int at;

    /** Pulls the text value out of a reply. */
    static synchronized String parse(byte[] b, int len) {
        try {
            at = 0;
            open(b, 0x30);
            skip(b);                      // version
            skip(b);                      // community
            open(b, 0xA2);
            skip(b);                      // request id
            if (b[at] != 2) return "";
            int l = length(b, at + 1);    // error status
            int err = 0;
            for (int i = 0; i < l; i++) err = (err << 8) | (b[at + i] & 255);
            at += l;
            if (err != 0) return "";
            skip(b);                      // error index
            open(b, 0x30);
            open(b, 0x30);
            skip(b);                      // the name we asked for
            if ((b[at] & 255) != 4) return "";
            int n = length(b, at + 1);
            if (at + n > len) return "";
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int c = b[at + i] & 255;
                if (c >= 32 && c < 127) s.append((char) c);
            }
            return s.toString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Reads a length starting at b[from] and leaves 'at' on the first byte of the content. */
    private static int length(byte[] b, int from) {
        int l = b[from] & 255;
        at = from + 1;
        if (l < 128) return l;
        int count = l & 127, v = 0;
        for (int i = 0; i < count; i++) v = (v << 8) | (b[at++] & 255);
        return v;
    }

    private static void open(byte[] b, int tag) {
        if ((b[at] & 255) != tag) throw new IllegalStateException();
        length(b, at + 1);
    }

    private static void skip(byte[] b) {
        int l = length(b, at + 1);
        at += l;
    }
}
