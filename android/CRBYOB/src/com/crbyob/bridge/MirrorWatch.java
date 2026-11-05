package com.crbyob.bridge;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Listens on UDP 57301 while the bridge runs for Creality Scan's "OrbbecRTCServer" broadcast. The PC
 * only broadcasts while its "Mobile connection" dialog is open and no phone is attached, so a recent
 * broadcast means "ready for the mirror viewer". This is passive only: never probe TCP 57308, the PC
 * treats any accept as a client (stops broadcasting, logs connect/disconnect).
 */
final class MirrorWatch {
    private volatile boolean running;
    private DatagramSocket sock;
    volatile long lastHeardMs;
    volatile String lastHost;
    private volatile String loggedHost;
    /** true = the running bridge's watcher (its broadcasts may auto-connect), false = the dashboard's. */
    private final boolean bridgeWatch;

    MirrorWatch(boolean bridgeWatch) {
        this.bridgeWatch = bridgeWatch;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "mirror-watch");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
    }

    /** IP of the PC whose broadcast was heard within maxAgeMs, else null. */
    String recentHost(long maxAgeMs) {
        String h = lastHost;
        return h != null && System.currentTimeMillis() - lastHeardMs <= maxAgeMs ? h : null;
    }

    private void loop() {
        byte[] buf = new byte[256];
        while (running) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                sock = s;
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(MirrorActivity.BROADCAST_PORT));
                s.setSoTimeout(1000);
                BLog.log("mirror_watch_listen", "port", MirrorActivity.BROADCAST_PORT);
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!MirrorActivity.BROADCAST_MSG.equals(msg)) continue;
                    String ip = p.getAddress().getHostAddress();
                    lastHost = ip;
                    lastHeardMs = System.currentTimeMillis();
                    if (!ip.equals(loggedHost)) {
                        loggedHost = ip;
                        BLog.log("mirror_pc_ready", "host", ip);
                    }
                    MirrorSession ms = MirrorSession.peek();
                    if (ms != null) ms.onBroadcast(ip, bridgeWatch);   // auto-connect (if enabled and not suppressed)
                }
            } catch (Exception e) {
                if (!running) break;
                BLog.log("mirror_watch_error", "err", String.valueOf(e));
                Otter.sleep(3000);
            }
        }
        sock = null;
    }
}
