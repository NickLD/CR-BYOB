package com.crbyob.bridge;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** HostProtocol control server on TCP 8090. */
public final class HostServer {
    private final Bridge bridge;
    private final int port;
    private ServerSocket srv;
    private volatile boolean running;
    private final Set<Socket> conns = Collections.synchronizedSet(new HashSet<>());
    final AtomicInteger clients = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    volatile long lastRequestMs;
    volatile String lastPeer;

    private static final int MAX_CONNS = 8;

    HostServer(Bridge b, int port) {
        this.bridge = b;
        this.port = port;
    }

    void start() throws Exception {
        srv = new ServerSocket();
        srv.setReuseAddress(true);
        srv.bind(new InetSocketAddress(port), 8);
        running = true;
        BLog.log("tcp_listen", "addr", "0.0.0.0:" + port, "reply", "error");
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket c = srv.accept();
                    if (conns.size() >= MAX_CONNS) {           // Creality Scan uses one; anyone on the LAN can connect
                        BLog.log("tcp_refused", "peer", c.getInetAddress().getHostAddress(), "why", "too many connections");
                        try { c.close(); } catch (Exception ignored) { }
                        continue;
                    }
                    if (!bridge.acceptsPeer(c.getInetAddress(), "control")) {
                        try { c.close(); } catch (Exception ignored) { }
                        continue;
                    }
                    c.setTcpNoDelay(true);
                    StallGuard.tune(c);
                    conns.add(c);
                    Thread h = new Thread(() -> client(c), "hp-" + c.getPort());
                    h.start();
                } catch (Exception e) {
                    if (running) BLog.log("tcp_accept_error", "err", String.valueOf(e));
                }
            }
        }, "hp-accept");
        t.start();
    }

    void stop() {
        running = false;
        try { srv.close(); } catch (Exception ignored) { }
        synchronized (conns) {
            for (Socket s : conns) try { s.close(); } catch (Exception ignored) { }
        }
    }

    private boolean hasConn(String ip) {
        synchronized (conns) {
            for (Socket s : conns) if (s.getInetAddress().getHostAddress().equals(ip)) return true;
        }
        return false;
    }

    private void client(Socket c) {
        String tag = c.getInetAddress().getHostAddress() + ":" + c.getPort();
        BLog.log("tcp_connect", "peer", tag);
        clients.incrementAndGet();
        Responder resp = new Responder(bridge);
        StallGuard guard = StallGuard.watch(c, "hp");
        long opened = System.currentTimeMillis();
        int handled = 0;
        boolean failed = false;
        try {
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            byte[] hdr = new byte[8];
            while (true) {
                try {
                    in.readFully(hdr);
                } catch (EOFException e) {
                    break;
                }
                int magic = Hp.u16(hdr, 0), half = Hp.u16(hdr, 2), op = Hp.u16(hdr, 4), reqId = Hp.u16(hdr, 6);
                if (magic != Hp.REQ_MAGIC) {
                    byte[] rest = new byte[4096];
                    int n = Math.max(0, in.read(rest));
                    byte[] all = new byte[8 + n];
                    System.arraycopy(hdr, 0, all, 0, 8);
                    System.arraycopy(rest, 0, all, 8, n);
                    BLog.log("hp_bad_magic", "peer", tag, "hex", Hp.hex(all, 64));
                    continue;
                }
                byte[] payload = new byte[half * 2];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    break;
                }
                byte[] frame = new byte[8 + payload.length];
                System.arraycopy(hdr, 0, frame, 0, 8);
                System.arraycopy(payload, 0, frame, 8, payload.length);
                long prop = payload.length >= 4 ? Hp.u32(payload, 0) & 0xFFFFFFFFL : -1;
                String[] note = new String[1];
                byte[] reply = resp.handle(op, reqId, payload, frame, prop, note);
                requests.incrementAndGet();
                lastRequestMs = System.currentTimeMillis();
                lastPeer = c.getInetAddress().getHostAddress();
                BLog.log("hp_req", "peer", tag, "opcode", op, "req_id", reqId,
                        "prop", prop >= 0 ? "0x" + Long.toHexString(prop) : null,
                        "plen", payload.length, "payload", Hp.hex(payload, 64), "answer", note[0]);
                handled++;
                if (reply != null) {
                    guard.write(out, reply, 0, reply.length);
                    out.flush();
                }
            }
        } catch (Exception e) {
            BLog.log("tcp_error", "peer", tag, "err", String.valueOf(e));
            failed = true;
        } finally {
            try { c.close(); } catch (Exception ignored) { }
            guard.release();
            conns.remove(c);
            clients.decrementAndGet();
            BLog.log("tcp_close", "peer", tag);
            // The PC's long-lived control connection is gone (closed or errored) and it has no other
            // 8090 connection open: its RTSP/IMU sessions are orphans, so free the scanner nodes now.
            // Short probe connections (discovery checks, < 5 s / no requests) never trigger this.
            String ip = c.getInetAddress().getHostAddress();
            if (running && System.currentTimeMillis() - opened > 5000 && handled > 0 && !hasConn(ip))
                bridge.dropPeer(x -> x.equals(ip), failed ? "control connection error" : "control connection closed");
        }
    }

    /** Answers HostProtocol requests: bridge-owned props, camera controls, the rest relayed to the scanner's XU. */
    static final class Responder {
        private final Bridge b;
        private final Map<Integer, byte[]> raw;

        Responder(Bridge b) {
            this.b = b;
            this.raw = b.rawBlobs();
        }

        byte[] handle(int op, int reqId, byte[] payload, byte[] frame, long prop, String[] note) {
            byte[] blob = prop >= 0 && prop <= Integer.MAX_VALUE ? raw.get((int) prop) : null;
            if (op == Hp.OP_INIT_READ_RAW && blob != null) {
                note[0] = "raw-init";
                byte[] d = new byte[4];
                Hp.putU32(d, 0, blob.length);
                return Hp.reply(op, reqId, d, 0);
            }
            if (op == Hp.OP_READ_RAW && blob != null && payload.length >= 12) {
                long off = Hp.u32(payload, 4) & 0xFFFFFFFFL, size = Hp.u32(payload, 8) & 0xFFFFFFFFL;
                int a = (int) Math.min(off, blob.length), e = (int) Math.min(off + size, blob.length);
                byte[] d = new byte[Math.max(0, e - a)];
                System.arraycopy(blob, a, d, 0, d.length);
                note[0] = "raw-read " + off + "+" + size;
                return Hp.reply(op, reqId, d, 0);
            }
            if (op == Hp.OP_FINISH_READ_RAW && blob != null) {
                note[0] = "raw-finish";
                return Hp.reply(op, reqId);
            }
            if (b.isLineScan(op, prop)) {            // Raptor IR frame light type (0xC5/0xC6)
                note[0] = "line-scan";
                return b.lineScan(op, reqId, prop, payload, frame);
            }
            byte[] r = b.bridgeProps(op, reqId, prop, payload);
            if (r == null) r = b.controls.handle(op, reqId, prop, payload);
            if (r != null) {
                note[0] = "local status=" + Hp.statusOf(r) + " len=" + r.length;
                return r;
            }
            try {
                r = b.forward(frame, op);
            } catch (Exception e) {
                BLog.log("forward_error", "opcode", op, "prop", "0x" + Long.toHexString(Math.max(prop, 0)), "err", String.valueOf(e));
                r = null;
            }
            if (r != null) {
                note[0] = "forwarded status=" + Hp.statusOf(r) + " len=" + r.length;
                if (op == Bridge.OP_SET_STRUCT_EXT && prop == Bridge.PROP_DEPTH_WORK_MODE && Hp.statusOf(r) == 0)
                    b.onWorkModeSet(payload);
                return r;
            }
            note[0] = "forward-failed";
            return Hp.error(op, reqId, 1);
        }
    }
}
