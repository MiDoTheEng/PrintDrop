package com.printdrop.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Looks for printers on the Wi-Fi two ways at once: by listening for printers that announce
 * themselves, and by knocking on the printing ports of every address on the local network
 * (office networks often block the announcements).
 */
final class Finder {
    interface Listener {
        /** Called on the main thread whenever the list changes. */
        void onChanged(List<Printer> printers, boolean stillLooking);
    }

    private static final String[] TYPES = {"_ipp._tcp", "_pdl-datastream._tcp", "_printer._tcp"};

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Printer> found = new LinkedHashMap<>();
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private NsdManager nsd;
    private WifiManager.MulticastLock lock;
    private ExecutorService pool;
    private boolean resolving, scanning, stopped;

    Finder(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("printdrop");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd != null) {
                for (String type : TYPES) {
                    NsdManager.DiscoveryListener l = discovery();
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
                    listeners.add(l);
                }
            }
        } catch (Exception ignored) {
        }
        scan();
        publish();
    }

    void stop() {
        stopped = true;
        if (nsd != null) {
            for (NsdManager.DiscoveryListener l : listeners) {
                try {
                    nsd.stopServiceDiscovery(l);
                } catch (Exception ignored) {
                }
            }
        }
        listeners.clear();
        if (pool != null) pool.shutdownNow();
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------- announced printers

    private NsdManager.DiscoveryListener discovery() {
        return new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo i) {}

            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    toResolve.add(info);
                    resolveNext();
                });
            }
        };
    }

    private void resolveNext() {
        if (resolving || stopped || toResolve.isEmpty()) return;
        NsdServiceInfo info = toResolve.poll();
        resolving = true;
        final String type = info.getServiceType() == null ? "" : info.getServiceType();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override public void onServiceResolved(NsdServiceInfo i) {
                    main.post(() -> {
                        resolving = false;
                        announced(i, type);
                        resolveNext();
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    private void announced(NsdServiceInfo i, String type) {
        InetAddress a = i.getHost();
        if (!(a instanceof Inet4Address)) return;
        String host = a.getHostAddress();
        Printer p = found.get(host);
        if (p == null) {
            p = new Printer();
            p.host = host;
            found.put(host, p);
        }
        if (i.getServiceName() != null) p.name = i.getServiceName();
        if (type.contains("_ipp")) {
            p.ippPort = i.getPort();
            String rp = txt(i, "rp");
            if (!rp.isEmpty()) p.ippPath = rp.startsWith("/") ? rp : "/" + rp;
        } else if (type.contains("_pdl-datastream")) {
            p.rawPort = i.getPort();
        }
        String ty = txt(i, "ty");
        if (!ty.isEmpty()) p.model = ty;
        publish();
    }

    private static String txt(NsdServiceInfo i, String key) {
        try {
            byte[] b = i.getAttributes().get(key);
            return b == null ? "" : new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ network sweep

    private void scan() {
        final String prefix = localPrefix();
        if (prefix == null) return;
        scanning = true;
        pool = Executors.newFixedThreadPool(40);
        for (int n = 1; n < 255; n++) {
            final String host = prefix + n;
            pool.execute(() -> {
                boolean ipp = Ipp.open(host, 631, 400);
                boolean raw = !ipp && Ipp.open(host, 9100, 400);
                if (!ipp && !raw) return;
                final Printer p = new Printer();
                p.host = host;
                Ipp.probe(p);
                if (!p.ipp() && p.rawPort == 0) return;
                main.post(() -> {
                    Printer old = found.get(host);
                    if (old != null) {
                        if (p.name.isEmpty()) p.name = old.name;
                        if (p.model.isEmpty()) p.model = old.model;
                    }
                    found.put(host, p);
                    publish();
                });
            });
        }
        pool.shutdown();
        final ExecutorService waiting = pool;
        new Thread(() -> {
            try {
                waiting.awaitTermination(40, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            main.post(() -> {
                scanning = false;
                publish();
            });
        }).start();
    }

    /** "192.168.1." for the Wi-Fi this device is on, or null. */
    private static String localPrefix() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String s = a.getHostAddress();
                    String pre = s.substring(0, s.lastIndexOf('.') + 1);
                    String name = ni.getName() == null ? "" : ni.getName();
                    if (name.startsWith("wlan")) return pre;
                    if (fallback == null) fallback = pre;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void publish() {
        if (stopped) return;
        listener.onChanged(new ArrayList<>(found.values()), scanning);
    }
}
