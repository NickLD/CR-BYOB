package com.crbyob.bridge;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-stall watchdog for one accepted TCP socket. Every write to a peer goes through {@link #write}:
 * if a single write makes no progress for {@link #STALL_MS} (the peer stopped reading, or vanished
 * without a FIN/RST, e.g. a Wi-Fi Direct client walking out of range), one shared timer thread closes
 * the socket. That unblocks the writer with "Socket closed", so the stream thread runs its cleanup and
 * releases the UVC node instead of sitting in write() forever.
 *
 * {@link #tune} also turns on TCP keepalive (idle peers) and TCP_USER_TIMEOUT (unacked data) so the
 * kernel gives up on a dead peer even where no write is in flight.
 */
final class StallGuard {
    static final long STALL_MS = 3000;

    final Socket sock;
    final String what, peer;
    private volatile long writeStartMs;           // 0 = no write in flight
    volatile long lastWriteDoneMs = System.currentTimeMillis();
    volatile boolean fired;

    private static final Set<StallGuard> ALL = ConcurrentHashMap.newKeySet();
    private static Thread timer;

    private StallGuard(Socket s, String what) {
        this.sock = s;
        this.what = what;
        this.peer = s.getInetAddress().getHostAddress() + ":" + s.getPort();
    }

    /** Starts watching a socket; call {@link #release} when its connection ends. */
    static StallGuard watch(Socket s, String what) {
        StallGuard g = new StallGuard(s, what);
        ALL.add(g);
        synchronized (StallGuard.class) {
            if (timer == null) {
                timer = new Thread(StallGuard::loop, "stall-guard");
                timer.setDaemon(true);
                timer.start();
            }
        }
        return g;
    }

    void release() {
        ALL.remove(this);
    }

    void write(OutputStream out, byte[] b, int off, int len) throws IOException {
        writeStartMs = System.currentTimeMillis();
        try {
            out.write(b, off, len);
        } finally {
            writeStartMs = 0;
        }
        lastWriteDoneMs = System.currentTimeMillis();
    }

    /** ms since the current write started, or 0 if none is in flight. */
    long stalledFor() {
        long ws = writeStartMs;
        return ws == 0 ? 0 : System.currentTimeMillis() - ws;
    }

    private static void loop() {
        while (true) {
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            for (StallGuard g : ALL) {
                long st = g.stalledFor();
                if (st > STALL_MS && !g.fired) {
                    g.fired = true;
                    BLog.log("write_stall", "what", g.what, "peer", g.peer, "stalled_ms", st, "action", "close");
                    SessionStats.cur.stalledClosed.incrementAndGet();
                    try { g.sock.close(); } catch (Exception ignored) { }
                    ALL.remove(g);
                }
            }
        }
    }

    // Linux values; android.system.OsConstants does not expose all of them on every API level
    private static final int TCP_KEEPIDLE = 4, TCP_KEEPINTVL = 5, TCP_KEEPCNT = 6, TCP_USER_TIMEOUT = 18;

    /** Keepalive (idle 10 s, then 3 probes 3 s apart) and a 10 s limit on unacknowledged data. */
    static void tune(Socket s) {
        try { s.setKeepAlive(true); } catch (Exception ignored) { }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(s)) {   // a dup of the same socket
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 10);
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 3);
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3);
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT, 10000);
        } catch (Throwable e) {
            BLog.log("tcp_tune_error", "err", String.valueOf(e));
        }
    }
}
