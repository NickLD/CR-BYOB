package com.crbyob.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sticky counters for the current bridge session: reset when the bridge starts (or by "Reset"), kept
 * after Stop until the next Start. Hot paths only touch atomics and fixed histograms (no allocation).
 */
final class SessionStats {
    /** Latency histogram: 1 ms buckets up to 2 s plus overflow; avg / p95 / max without allocation. */
    static final class Hist {
        private final int[] b = new int[2001];
        private long n, sumUs, maxUs;

        synchronized void add(long us) {
            if (us < 0) return;
            int i = (int) Math.min(2000, us / 1000);
            b[i]++;
            n++;
            sumUs += us;
            if (us > maxUs) maxUs = us;
        }

        synchronized long count() { return n; }
        synchronized double avgMs() { return n == 0 ? 0 : sumUs / 1000.0 / n; }
        synchronized double maxMs() { return maxUs / 1000.0; }

        synchronized double pMs(double q) {
            if (n == 0) return 0;
            long want = (long) Math.ceil(n * q), acc = 0;
            for (int i = 0; i < b.length; i++) {
                acc += b[i];
                if (acc >= want) return i + 0.5;
            }
            return 2000;
        }

        String fmt() {
            return count() == 0 ? "—" : String.format(Locale.US, "%.1f / %.0f / %.0f ms", avgMs(), pMs(0.95), maxMs());
        }

        /** {n, sum, max, b: {bucket: count}} with only the non-empty buckets (a few dozen in practice). */
        synchronized JSONObject toJson() throws JSONException {
            JSONObject j = new JSONObject().put("n", n).put("sum", sumUs).put("max", maxUs);
            JSONObject bj = new JSONObject();
            for (int i = 0; i < b.length; i++) if (b[i] != 0) bj.put(String.valueOf(i), b[i]);
            return j.put("b", bj);
        }

        synchronized void fromJson(JSONObject j) {
            if (j == null) return;
            n = j.optLong("n");
            sumUs = j.optLong("sum");
            maxUs = j.optLong("max");
            JSONObject bj = j.optJSONObject("b");
            if (bj == null) return;
            for (java.util.Iterator<String> it = bj.keys(); it.hasNext(); ) {
                String k = it.next();
                int i = Integer.parseInt(k);
                if (i >= 0 && i < b.length) b[i] = bj.optInt(k);
            }
        }
    }

    static volatile SessionStats cur = new SessionStats();

    static void reset() {
        cur = new SessionStats();
        BLog.log("session_stats_reset");
    }

    volatile long startMs = System.currentTimeMillis();   // set again by fromJson for a saved session
    volatile long stopMs;                    // 0 while the bridge runs
    // time (accumulated by Dash once a second)
    volatile long pcMs, streamMs;
    // data
    volatile double peakMbps;
    volatile long bytesAtStart = -1, bytesNow;
    // frames per stream (IR L, IR R, Color): from the per-port counters; reduced-rate time and decimation onsets
    final long[] captured = new long[3], sent = new long[3], qdrops = new long[3], skipped = new long[3];
    final long[] reducedMs = new long[3], streamingMsPer = new long[3], decimations = new long[3];
    final long[] base = new long[12];            // per-port counter values at session start
    boolean baseSet;
    // reliability
    final AtomicLong pcDrops = new AtomicLong(), rtspCloses = new AtomicLong(), rtspErrors = new AtomicLong(),
            imuConnects = new AtomicLong(), p2pJoins = new AtomicLong(), p2pLeaves = new AtomicLong(),
            usbDetach = new AtomicLong(), usbAttach = new AtomicLong(), brownouts = new AtomicLong(),
            mirrorConnects = new AtomicLong(), uvcErrors = new AtomicLong(),
            stalledClosed = new AtomicLong(), takeovers = new AtomicLong(), peerCleanups = new AtomicLong(),
            detachStreaming = new AtomicLong();  // of usbDetach: the scanner went while video was streaming
    // phone: time on external power vs battery (sampled once a second), worst thermal status (-1 unknown)
    volatile long poweredMs, batteryMs;
    volatile int thermalMax = -1;
    volatile int batteryStart = -1, batteryEnd = -1;   // battery % at the first and latest sample
    volatile String charger;                           // the last external power seen ("USB-C PD or hub", ...)
    // Wi-Fi Direct while the phone is also on a Wi-Fi network (they share the radio): streaming time, band
    volatile long p2pDualMs;
    volatile int p2pDualFreq;
    final int[] reqFps = new int[3];                   // fps Creality Scan asked for, per stream (0 unknown)
    // latency
    final Hist xu = new Hist(), frameAge = new Hist();
    // link (whichever network carries the PC: Wi-Fi STA sampled; a Wi-Fi Direct GO has no per-client RSSI)
    volatile int rssiMin = 0, linkMin = 0;
    volatile long rssiSum, rssiN;
    volatile String linkNet = "";

    // ---- saved with the crash record (CrashRecord), so the next run can report on a session that died.
    // Everything the device report reads, nothing more: linkNet (a network name) and base stay out.

    private static final String[] COUNTERS = {"pcDrops", "rtspCloses", "rtspErrors", "imuConnects", "p2pJoins",
            "p2pLeaves", "usbDetach", "usbAttach", "brownouts", "mirrorConnects", "uvcErrors", "stalledClosed",
            "takeovers", "peerCleanups", "detachStreaming"};

    private AtomicLong[] counters() {
        return new AtomicLong[]{pcDrops, rtspCloses, rtspErrors, imuConnects, p2pJoins, p2pLeaves, usbDetach,
                usbAttach, brownouts, mirrorConnects, uvcErrors, stalledClosed, takeovers, peerCleanups, detachStreaming};
    }

    private static JSONArray arr(long[] a) {
        JSONArray j = new JSONArray();
        for (long v : a) j.put(v);
        return j;
    }

    private static void arr(JSONArray j, long[] into) {
        if (j != null) for (int i = 0; i < into.length && i < j.length(); i++) into[i] = j.optLong(i);
    }

    JSONObject toJson() throws JSONException {
        JSONObject j = new JSONObject()
                .put("startMs", startMs).put("stopMs", stopMs).put("pcMs", pcMs).put("streamMs", streamMs)
                .put("peakMbps", peakMbps).put("bytesAtStart", bytesAtStart).put("bytesNow", bytesNow)
                .put("captured", arr(captured)).put("sent", arr(sent)).put("qdrops", arr(qdrops))
                .put("skipped", arr(skipped)).put("reducedMs", arr(reducedMs))
                .put("streamingMsPer", arr(streamingMsPer)).put("decimations", arr(decimations))
                .put("reqFps", arr(new long[]{reqFps[0], reqFps[1], reqFps[2]}))
                .put("poweredMs", poweredMs).put("batteryMs", batteryMs).put("thermalMax", thermalMax)
                .put("batteryStart", batteryStart).put("batteryEnd", batteryEnd).put("charger", charger)
                .put("p2pDualMs", p2pDualMs).put("p2pDualFreq", p2pDualFreq)
                .put("rssiMin", rssiMin).put("linkMin", linkMin).put("rssiSum", rssiSum).put("rssiN", rssiN)
                .put("xu", xu.toJson()).put("frameAge", frameAge.toJson());
        AtomicLong[] c = counters();
        for (int i = 0; i < c.length; i++) j.put(COUNTERS[i], c[i].get());
        return j;
    }

    static SessionStats fromJson(JSONObject j) {
        SessionStats s = new SessionStats();
        s.startMs = j.optLong("startMs", s.startMs);
        s.stopMs = j.optLong("stopMs");
        s.pcMs = j.optLong("pcMs");
        s.streamMs = j.optLong("streamMs");
        s.peakMbps = j.optDouble("peakMbps", 0);
        s.bytesAtStart = j.optLong("bytesAtStart", -1);
        s.bytesNow = j.optLong("bytesNow");
        arr(j.optJSONArray("captured"), s.captured);
        arr(j.optJSONArray("sent"), s.sent);
        arr(j.optJSONArray("qdrops"), s.qdrops);
        arr(j.optJSONArray("skipped"), s.skipped);
        arr(j.optJSONArray("reducedMs"), s.reducedMs);
        arr(j.optJSONArray("streamingMsPer"), s.streamingMsPer);
        arr(j.optJSONArray("decimations"), s.decimations);
        long[] req = new long[3];
        arr(j.optJSONArray("reqFps"), req);
        for (int i = 0; i < 3; i++) s.reqFps[i] = (int) req[i];
        s.poweredMs = j.optLong("poweredMs");
        s.batteryMs = j.optLong("batteryMs");
        s.thermalMax = j.optInt("thermalMax", -1);
        s.batteryStart = j.optInt("batteryStart", -1);
        s.batteryEnd = j.optInt("batteryEnd", -1);
        s.charger = j.isNull("charger") ? null : j.optString("charger", null);
        s.p2pDualMs = j.optLong("p2pDualMs");
        s.p2pDualFreq = j.optInt("p2pDualFreq");
        s.rssiMin = j.optInt("rssiMin");
        s.linkMin = j.optInt("linkMin");
        s.rssiSum = j.optLong("rssiSum");
        s.rssiN = j.optLong("rssiN");
        s.xu.fromJson(j.optJSONObject("xu"));
        s.frameAge.fromJson(j.optJSONObject("frameAge"));
        AtomicLong[] c = s.counters();
        for (int i = 0; i < c.length; i++) c[i].set(j.optLong(COUNTERS[i]));
        return s;
    }

    // ---- hot-path hooks (cheap)
    static void xuRoundTrip(long ns) { cur.xu.add(ns / 1000); }
    static void frameAge(long us) { cur.frameAge.add(us); }

    long uptimeMs() {
        return (stopMs > 0 ? stopMs : System.currentTimeMillis()) - startMs;
    }

    static String dur(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%dh %02dm", s / 3600, (s / 60) % 60)
                : s >= 60 ? String.format(Locale.US, "%dm %02ds", s / 60, s % 60) : s + " s";
    }

    double gb() {
        return bytesAtStart < 0 ? 0 : (bytesNow - bytesAtStart) / 1e9;
    }

    double avgMbps() {
        return streamMs <= 0 || bytesAtStart < 0 ? 0 : (bytesNow - bytesAtStart) * 8.0 / (streamMs / 1000.0) / 1e6;
    }

    /** Plain-text summary (viewer ⋮ menu, logs). */
    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Uptime %s · PC %s · streaming %s%n", dur(uptimeMs()), dur(pcMs), dur(streamMs)));
        sb.append(String.format(Locale.US, "Sent %.2f GB · avg %.0f Mbit/s · peak %.0f Mbit/s%n", gb(), avgMbps(), peakMbps));
        String[] n = {"IR L", "IR R", "Color"};
        for (int i = 0; i < 3; i++)
            sb.append(String.format(Locale.US, "%s: %d sent / %d captured · %d dropped · %.0f%% reduced · %d× decimated%n",
                    n[i], sent[i], captured[i], qdrops[i] + skipped[i], pct(reducedMs[i], streamingMsPer[i]), decimations[i]));
        sb.append(String.format(Locale.US, "PC drops %d · RTSP closes %d (errors %d) · IMU connects %d%n",
                pcDrops.get(), rtspCloses.get(), rtspErrors.get(), imuConnects.get()));
        sb.append(String.format(Locale.US, "Stalled sessions closed %d · taken over %d · closed on PC/peer loss %d%n",
                stalledClosed.get(), takeovers.get(), peerCleanups.get()));
        sb.append(String.format(Locale.US, "USB detach %d (%d while streaming) / attach %d (brown-outs %d) · Wi-Fi Direct joins %d / leaves %d · mirror connects %d%n",
                usbDetach.get(), detachStreaming.get(), usbAttach.get(), brownouts.get(), p2pJoins.get(), p2pLeaves.get(), mirrorConnects.get()));
        sb.append(String.format(Locale.US, "On battery %s of %s · worst thermal status %s%n",
                dur(batteryMs), dur(poweredMs + batteryMs), thermalName(thermalMax)));
        sb.append("Scanner reply (XU) ").append(xu.fmt()).append(" · frame age ").append(frameAge.fmt());
        return sb.toString();
    }

    /** PowerManager.THERMAL_STATUS_* (0 none … 6 shutdown) in words; "unknown" below Android 10. */
    static String thermalName(int status) {
        String[] n = {"None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown"};
        return status >= 0 && status < n.length ? n[status] : "unknown";
    }

    static double pct(long part, long whole) {
        return whole <= 0 ? 0 : part * 100.0 / whole;
    }
}
