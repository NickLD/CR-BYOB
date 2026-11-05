package com.crbyob.bridge;

import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP 8900 (as the official bridge's HighfrqServer). No handshake. While at least one
 * client is connected, every raw HID report from the Otter's interrupt endpoint (MI_06, EP 0x85)
 * is forwarded unchanged. Report 0x01 = IMU samples, 0x02 (magic 0x4321 at +8) = scanner button.
 */
final class ImuServer {
    private final Bridge bridge;
    final int port;
    private ServerSocket srv;
    private volatile boolean running;
    private final List<Socket> clients = new ArrayList<>();
    private final java.util.Map<Socket, StallGuard> guards = new java.util.concurrent.ConcurrentHashMap<>();
    private HidReader reader;
    private final ArrayBlockingQueue<byte[]> outq = new ArrayBlockingQueue<>(512);
    private volatile long reports, keyEvents, dropped;

    ImuServer(Bridge b, int port) {
        this.bridge = b;
        this.port = port;
    }

    void start() throws Exception {
        srv = new ServerSocket();
        srv.setReuseAddress(true);
        srv.bind(new InetSocketAddress(port), 4);
        running = true;
        BLog.log("imu_listen", "port", port);
        new Thread(this::acceptLoop, "imu-accept").start();
        new Thread(this::sendLoop, "imu-send").start();
    }

    void stop() {
        running = false;
        try { srv.close(); } catch (Exception ignored) { }
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (Exception ignored) { }
            clients.clear();
        }
        stopReader();
    }

    /** Closes the IMU connections from matching IPs (PC control connection dropped / left the group). */
    int killPeer(java.util.function.Predicate<String> ipMatch, String why) {
        int n = 0;
        synchronized (clients) {
            for (Socket c : clients) {
                if (ipMatch.test(c.getInetAddress().getHostAddress())) {
                    BLog.log("imu_kill", "peer", c.getInetAddress().getHostAddress() + ":" + c.getPort(), "why", why);
                    try { c.close(); } catch (Exception ignored) { }
                    n++;
                }
            }
        }
        return n;
    }

    int clientCount() {
        synchronized (clients) { return clients.size(); }
    }

    long reportCount() {
        return reports;
    }

    String statusLine() {
        int n;
        synchronized (clients) { n = clients.size(); }
        return n + " client(s), " + reports + " reports, " + keyEvents + " key events" + (reader != null ? ", reading" : "");
    }

    private static final int MAX_CLIENTS = 4;

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = srv.accept();
                synchronized (clients) {
                    if (clients.size() >= MAX_CLIENTS) {
                        BLog.log("imu_refused", "peer", c.getInetAddress().getHostAddress(), "why", "too many clients");
                        try { c.close(); } catch (Exception ignored) { }
                        continue;
                    }
                }
                if (!bridge.acceptsPeer(c.getInetAddress(), "imu")) {
                    try { c.close(); } catch (Exception ignored) { }
                    continue;
                }
                c.setTcpNoDelay(true);
                StallGuard.tune(c);
                guards.put(c, StallGuard.watch(c, "imu"));
                synchronized (clients) {
                    clients.add(c);
                }
                ensureReader();
                new Thread(() -> client(c), "imu-client").start();
            } catch (Exception e) {
                if (running) BLog.log("imu_accept_error", "err", String.valueOf(e));
            }
        }
    }

    private void client(Socket c) {
        String peer = c.getInetAddress().getHostAddress() + ":" + c.getPort();
        BLog.log("imu_connect", "peer", peer);
        SessionStats.cur.imuConnects.incrementAndGet();
        try {
            InputStream in = c.getInputStream();
            byte[] b = new byte[4096];
            while (in.read(b) >= 0) { /* only to detect close */ }
        } catch (Exception ignored) {
        } finally {
            boolean empty;
            synchronized (clients) {
                clients.remove(c);
                empty = clients.isEmpty();
            }
            try { c.close(); } catch (Exception ignored) { }
            StallGuard g = guards.remove(c);
            if (g != null) g.release();
            BLog.log("imu_close", "peer", c.getInetAddress().getHostAddress());
            if (empty) stopReader();
        }
    }

    /** Starts the HID reader if there are clients and a scanner; called on connect and on scanner attach. */
    synchronized void ensureReader() {
        synchronized (clients) {
            if (clients.isEmpty()) return;
        }
        if (reader != null && !reader.dead) return;
        if (reader != null) {                  // dead (scanner gone / transfer error): release its URBs first
            reader.close();
            reader = null;
        }
        Otter o = bridge.otter;
        if (o == null) {
            BLog.log("imu_error", "err", "scanner not attached");
            return;
        }
        // scanners.json imu.hid_interface picks the HID interface; -1 (unset) takes the first one
        ScannerModel m = o.model;
        UsbDesc.Hid hid = null;
        for (UsbDesc.Hid h : o.desc.hids)
            if (m == null || m.hidInterface < 0 || h.iface == m.hidInterface) { hid = h; break; }
        if (hid == null || hid.endpoint < 0) {
            BLog.log("imu_error", "err", "no HID interrupt endpoint on the scanner",
                    "hid_interface", m != null ? (Object) m.hidInterface : null);
            return;
        }
        HidReader r = null;
        try {
            r = new HidReader(o, hid);
            r.start();
            reader = r;
        } catch (Exception e) {
            BLog.log("imu_error", "err", String.valueOf(e));
            if (r != null) r.close();          // start() may have queued some URBs before failing
            reader = null;
        }
    }

    synchronized void stopReader() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    /** Called on the reaper thread: must never block. */
    void onReport(byte[] rep) {
        reports++;
        if (rep.length > 0 && rep[0] == 2) {
            keyEvents++;
            BLog.log("scanner_key_event", "hex", Hp.hex(rep, 24));
        }
        if (!outq.offer(rep)) dropped++;
    }

    private void sendLoop() {
        while (running) {
            byte[] rep;
            try {
                rep = outq.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (rep == null) continue;
            List<Socket> targets;
            synchronized (clients) {
                targets = new ArrayList<>(clients);
            }
            for (Socket c : targets) {
                StallGuard g = guards.get(c);
                if (g == null || g.fired) continue;
                try {
                    // one sender serves every client: a stalled one is closed after 3 s instead of
                    // holding up the others (and the HID queue) for good
                    g.write(c.getOutputStream(), rep, 0, rep.length);
                } catch (Exception e) {
                    try { c.close(); } catch (Exception ignored) { }   // the client thread logs imu_close
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    final class HidReader implements Otter.Sink {
        final Otter o;
        final UsbDesc.Hid hid;
        final List<Otter.Xfer> xfers = new ArrayList<>();
        int outstanding;
        boolean stopping;
        volatile boolean dead;
        final TreeMap<Integer, Integer> counts = new TreeMap<>();
        long lastLog = System.currentTimeMillis();
        int zeros;

        HidReader(Otter o, UsbDesc.Hid hid) {
            this.o = o;
            this.hid = hid;
        }

        void start() throws Exception {
            UsbEndpoint ep = o.endpoint(hid.iface, hid.endpoint);
            if (ep == null) throw new Exception("HID endpoint missing");
            int size = Math.max(ep.getMaxPacketSize(), 248);
            synchronized (this) {
                for (int i = 0; i < 4; i++) {
                    Otter.Xfer x = new Otter.Xfer();
                    x.req = new UsbRequest();
                    if (!x.req.initialize(o.conn, ep)) throw new Exception("UsbRequest.initialize failed");
                    x.buf = ByteBuffer.allocateDirect(size);
                    x.sink = this;
                    x.req.setClientData(x);
                    xfers.add(x);
                    if (x.req.queue(x.buf)) outstanding++;
                }
            }
            BLog.log("imu_reader_start", "iface", hid.iface, "ep", String.format("%02x", hid.endpoint), "mps", ep.getMaxPacketSize(),
                    "buf", size, "urbs", outstanding);
            if (outstanding == 0) { dead = true; throw new Exception("could not queue HID requests"); }
        }

        @Override
        public void onComplete(Otter.Xfer x, int len) {
            synchronized (this) {
                if (stopping) { outstanding--; notifyAll(); return; }
            }
            if (len > 0) {
                byte[] rep = new byte[len];
                x.buf.flip();
                x.buf.get(rep);
                int kind = rep[0] & 0xFF;
                Integer c = counts.get(kind);
                counts.put(kind, c == null ? 1 : c + 1);
                onReport(rep);
                zeros = 0;
            } else if (++zeros > 2000) {    // persistent zero-length completions (errors): give up
                BLog.log("imu_error", "err", "HID endpoint keeps failing");
                synchronized (this) { outstanding--; dead = true; }
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastLog > 10000) {
                lastLog = now;
                BLog.log("imu_stats", "reports", counts.toString(), "dropped", dropped);
            }
            x.buf.clear();
            synchronized (this) {
                if (stopping) { outstanding--; notifyAll(); return; }
                if (!x.req.queue(x.buf)) {
                    outstanding--;
                    if (outstanding == 0) dead = true;
                }
            }
        }

        void close() {
            synchronized (this) {
                if (stopping) return;
                stopping = true;
            }
            for (Otter.Xfer x : xfers) try { x.req.cancel(); } catch (Exception ignored) { }
            long end = System.currentTimeMillis() + 1500;
            synchronized (this) {
                while (outstanding > 0 && !o.isClosed() && System.currentTimeMillis() < end) {
                    try { wait(100); } catch (InterruptedException e) { break; }
                }
            }
            if (outstanding == 0 || o.isClosed()) for (Otter.Xfer x : xfers) try { x.req.close(); } catch (Exception ignored) { }
            dead = true;
            BLog.log("imu_reader_stop", "reports", counts.toString());
        }
    }
}
