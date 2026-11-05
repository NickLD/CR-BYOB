package com.crbyob.bridge;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps IR_LEFT/IR_RIGHT drops paired when the WiFi link cannot carry every frame.
 * Both IR senders send only frames whose capture-time slot (shared phase, 1/fps grid) is a multiple
 * of k. k = 1 (send everything) until a sender's capture queue starts
 * dropping frames because the socket is backlogged; then k grows (max 3). Recovery is adaptive: the
 * first attempt to go back up comes after 5 s without drops; if the link fails again within 6 s of a
 * step back up, the wait doubles (10, 20, 40, max 60 s); after 30 s stable at full rate it resets to 5 s. files/stream_map.json {"ir_decimation": 1|2|3|"auto"} overrides it.
 */
final class IrGate {
    private final File filesDir;
    private long t0 = Long.MIN_VALUE, lastSeenMs;
    private int k = 1;
    private long lastChangeMs;
    private static final long HOLD_MIN_MS = 5000, HOLD_MAX_MS = 60000;
    private long holdMs = HOLD_MIN_MS;   // current wait before trying a lower k
    private long stepDownMs;             // when k was last lowered (a recovery attempt)
    private final Map<Integer, long[]> last = new HashMap<>();   // port -> {frames, drops}
    private int fixed = 0;   // 0 = auto
    private long cfgReadMs;

    IrGate(File filesDir) {
        this.filesDir = filesDir;
    }

    private void readCfg(long now) {
        if (now - cfgReadMs < 5000) return;
        cfgReadMs = now;
        fixed = 0;
        File f = new File(filesDir, "stream_map.json");
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(b);
            Object v = new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8)).opt("ir_decimation");
            if (v instanceof Number) fixed = Math.max(1, Math.min(6, ((Number) v).intValue()));
        } catch (Exception ignored) {
        }
    }

    synchronized boolean admit(long tsUs, int fps) {
        long now = System.currentTimeMillis();
        readCfg(now);
        long period = 1_000_000L / Math.max(fps, 1);
        if (t0 == Long.MIN_VALUE || now - lastSeenMs > 2000) t0 = tsUs;
        lastSeenMs = now;
        int kk = fixed > 0 ? fixed : k;
        if (kk <= 1) return true;
        long slot = Math.floorDiv(tsUs - t0 + period / 2, period);
        return Math.floorMod(slot, kk) == 0;
    }

    /** Called by each IR sender about every 2 s with its cumulative capture counters. */
    synchronized void report(int port, long frames, long drops) {
        long now = System.currentTimeMillis();
        long[] p = last.get(port);
        last.put(port, new long[]{frames, drops});
        if (p == null || fixed > 0) return;
        long df = frames - p[0], dd = drops - p[1];
        if (df <= 0) return;
        double ratio = (double) dd / df;
        int old = k;
        if (ratio > 0.08 && k < 3 && now - lastChangeMs > 2500) {
            if (stepDownMs > 0 && now - stepDownMs < 6000) holdMs = Math.min(holdMs * 2, HOLD_MAX_MS);  // recovery failed fast
            k++;
            lastChangeMs = now;
        } else if (dd == 0 && k > 1 && now - lastChangeMs > holdMs) {
            k--;
            lastChangeMs = now;
            stepDownMs = now;
        } else if (k == 1 && holdMs != HOLD_MIN_MS && now - lastChangeMs > 30000) {
            holdMs = HOLD_MIN_MS;      // stable at full rate: next recovery can be quick again
        }
        if (k != old) BLog.log("ir_decimation", "k", k, "port", port, "drop_ratio", Math.round(ratio * 100) / 100.0,
                "next_recovery_s", holdMs / 1000);
    }

    /**
     * Milliseconds until the next attempt to step up one tier (lower k), 0 if due now (waiting for a
     * clean 2 s report), or -1 when at full rate or k is fixed by stream_map.json.
     */
    synchronized long recoverInMs() {
        if (fixed > 0 || k <= 1) return -1;
        return Math.max(0, lastChangeMs + holdMs - System.currentTimeMillis());
    }

    synchronized int k() {
        return fixed > 0 ? fixed : k;
    }
}
