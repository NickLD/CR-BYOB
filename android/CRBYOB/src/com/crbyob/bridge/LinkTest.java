package com.crbyob.bridge;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Debug link test on TCP 8999: on connect, sends zeros as fast as possible for up to 10 s from the
 * app's own socket path (same as RTSP: 4 MB SO_SNDBUF, 60 KB writes), then closes. Used to measure
 * phone->PC throughput on the LAN vs the Wi-Fi Direct group without the scanner.
 */
final class LinkTest {
    static final int PORT = 8999;
    private ServerSocket srv;
    private volatile boolean running;

    void start() {
        try {
            srv = new ServerSocket();
            srv.setReuseAddress(true);
            srv.bind(new InetSocketAddress(PORT), 2);
        } catch (Exception e) {
            BLog.log("linktest_error", "err", String.valueOf(e));
            return;
        }
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket c = srv.accept();
                    new Thread(() -> blast(c), "linktest").start();
                } catch (Exception e) {
                    if (running) BLog.log("linktest_error", "err", String.valueOf(e));
                }
            }
        }, "linktest-accept").start();
    }

    private void blast(Socket c) {
        String peer = c.getInetAddress().getHostAddress();
        long n = 0, t0 = System.nanoTime();
        try {
            c.setTcpNoDelay(true);
            c.setSendBufferSize(4 * 1024 * 1024);
            OutputStream out = c.getOutputStream();
            byte[] buf = new byte[60016];
            while (running && System.nanoTime() - t0 < 10_000_000_000L) {
                out.write(buf);
                n += buf.length;
            }
        } catch (Exception ignored) {
        } finally {
            double dt = (System.nanoTime() - t0) / 1e9;
            BLog.log("linktest", "peer", peer, "local", c.getLocalAddress().getHostAddress(), "bytes", n,
                    "mbps", Math.round(n * 8 / dt / 1e5) / 10.0);
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    void stop() {
        running = false;
        try { srv.close(); } catch (Exception ignored) { }
    }
}
