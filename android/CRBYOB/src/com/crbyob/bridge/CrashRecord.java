package com.crbyob.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the app was doing, saved to disk so the next run can report on one that crashed or was killed: the session
 * stats, the dashboard state, recent events and, for a Java crash, where it happened. Android records how a process
 * ended (ApplicationExitInfo) but nothing about the run itself, and everything in memory dies with the process.
 *
 * One file per process, files/runs/&lt;pid&gt;.json, the newest few kept: a reopened app writes its own file and can't
 * overwrite the record of the run that crashed before the report about it goes out. The exit is matched by pid.
 * Saved every few seconds by Dash while the bridge runs, and once more by the crash handler at a Java crash.
 *
 * Privacy as in the device report: no serial, network names or passwords (scrubbed from the events), no IP
 * addresses (replaced by &lt;IP&gt;), no exception messages (they can carry any of those), no log.
 */
final class CrashRecord {
    private CrashRecord() {}

    static final String DIR = "runs";
    static final int KEEP = 4;
    static final long SAVE_EVERY_MS = 5_000;
    static final int EVENTS = 10;

    /** A saved run, read back by a later process. */
    static final class Run {
        int pid;
        long appStartMs, savedMs;
        Dash.Snap snap;               // the dashboard state the report reads (no names, addresses or serial)
        SessionStats stats;           // the bridge session of that run; null when the bridge never ran
        long[] eventMs = new long[0];
        String[] eventText = new String[0];
        String error;                 // "IllegalStateException at RtspServer.run:512 ← ..." for a Java crash
    }

    // ------------------------------------------------------------------ pure parts (unit-tested)

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    /** IP addresses become &lt;IP&gt;, and each secret (network names, password, serial) is cut out. */
    static String scrub(String text, Collection<String> secrets) {
        String t = IPV4.matcher(text == null ? "" : text).replaceAll("<IP>");
        for (String s : secrets) if (s != null && s.length() >= 3) t = t.replace(s, "<hidden>");
        return t;
    }

    /**
     * Where a Java crash happened, without its message: "IllegalStateException at RtspServer.run:512 ← Bridge.start:200
     * (cause: SocketException)". The app's own frames first (up to 5); the top frames when none are the app's.
     */
    static String error(Throwable t) {
        if (t == null) return null;
        List<String> frames = new ArrayList<>();
        for (StackTraceElement e : t.getStackTrace())
            if (e.getClassName().startsWith("com.crbyob.") && frames.size() < 5) frames.add(frame(e));
        if (frames.isEmpty())
            for (StackTraceElement e : t.getStackTrace()) if (frames.size() < 3) frames.add(frame(e));
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (!frames.isEmpty()) sb.append(" at ").append(String.join(" ← ", frames));
        if (root != t) sb.append(" (cause: ").append(root.getClass().getSimpleName()).append(')');
        return sb.toString();
    }

    private static String frame(StackTraceElement e) {
        String c = e.getClassName();
        c = c.substring(c.lastIndexOf('.') + 1);
        return c + "." + e.getMethodName() + (e.getLineNumber() > 0 ? ":" + e.getLineNumber() : "");
    }

    /** The dashboard state worth keeping: what the report reads, without the names, addresses and serial. */
    static JSONObject snapJson(Dash.Snap s) throws JSONException {
        return new JSONObject().put("running", s.running).put("hero", s.hero).put("advertise", s.advertise)
                .put("fpsMode", s.fpsMode).put("modelName", s.modelName).put("scannerModel", s.scannerModel)
                .put("scannerFw", s.scannerFw).put("scanner", s.scanner).put("plugProbed", s.plugProbed)
                .put("usbSuper", s.usbSuper).put("powered", s.powered).put("p2pFreq", s.p2pFreq)
                .put("p2pClients", s.p2pClients).put("staConnected", s.staConnected).put("staStd", s.staStd)
                .put("staFreq", s.staFreq).put("staRssi", s.staRssi).put("staTx", s.staTx).put("staLink", s.staLink)
                .put("linkScore", s.linkScore).put("linkWord", s.linkWord).put("soc", s.soc);
    }

    static Dash.Snap snapFrom(JSONObject j) {
        Dash.Snap s = new Dash.Snap();
        if (j == null) return s;
        s.running = j.optBoolean("running");
        s.hero = j.optInt("hero");
        s.advertise = j.optString("advertise", "");
        s.fpsMode = j.optString("fpsMode", FpsChoice.STOCK);
        s.modelName = j.isNull("modelName") ? null : j.optString("modelName", null);
        s.scannerModel = j.isNull("scannerModel") ? null : j.optString("scannerModel", null);
        s.scannerFw = j.isNull("scannerFw") ? null : j.optString("scannerFw", null);
        s.scanner = j.optBoolean("scanner");
        s.plugProbed = j.optBoolean("plugProbed");
        s.usbSuper = j.optBoolean("usbSuper");
        s.powered = j.optBoolean("powered");
        s.p2pFreq = j.optInt("p2pFreq");
        s.p2pClients = j.optInt("p2pClients");
        s.staConnected = j.optBoolean("staConnected");
        s.staStd = j.optInt("staStd");
        s.staFreq = j.optInt("staFreq");
        s.staRssi = j.optInt("staRssi");
        s.staTx = j.optInt("staTx");
        s.staLink = j.optInt("staLink");
        s.linkScore = j.optInt("linkScore", -1);
        s.linkWord = j.isNull("linkWord") ? null : j.optString("linkWord", null);
        s.soc = j.optString("soc", "");
        return s;
    }

    /** The record for one moment of a run. events: newest first, {Long ms, String text}, already scrubbed. */
    static JSONObject toJson(int pid, long appStartMs, long nowMs, Dash.Snap s, SessionStats ss,
                             List<Object[]> events, String error) throws JSONException {
        JSONObject j = new JSONObject().put("v", 1).put("pid", pid).put("appStartMs", appStartMs).put("savedMs", nowMs);
        if (s != null) j.put("snap", snapJson(s));
        if (ss != null && (s == null || s.running || ss.streamMs > 0)) j.put("stats", ss.toJson());
        JSONArray ev = new JSONArray();
        for (Object[] e : events) ev.put(new JSONArray().put(e[0]).put(e[1]));
        j.put("events", ev);
        if (error != null) j.put("error", error);
        return j;
    }

    static Run fromJson(JSONObject j) {
        Run r = new Run();
        r.pid = j.optInt("pid");
        r.appStartMs = j.optLong("appStartMs");
        r.savedMs = j.optLong("savedMs");
        r.snap = snapFrom(j.optJSONObject("snap"));
        JSONObject st = j.optJSONObject("stats");
        r.stats = st == null ? null : SessionStats.fromJson(st);
        JSONArray ev = j.optJSONArray("events");
        int n = ev == null ? 0 : ev.length();
        r.eventMs = new long[n];
        r.eventText = new String[n];
        for (int i = 0; i < n; i++) {
            JSONArray e = ev.optJSONArray(i);
            r.eventMs[i] = e == null ? 0 : e.optLong(0);
            r.eventText[i] = e == null ? "" : e.optString(1, "");
        }
        r.error = j.isNull("error") ? null : j.optString("error", null);
        return r;
    }

    /** What the run was doing when it stopped, from its last saved state: "streaming to a PC over Wi-Fi Direct". */
    static String doing(Run r) {
        Dash.Snap s = r.snap;
        if (s == null || !s.running) return "the bridge was off";
        String net = DeviceReport.networkOption(s.advertise);
        boolean local = "local".equals(s.advertise);
        String over = net.isEmpty() || local ? "" : " over " + net;
        switch (s.hero) {
            case Dash.STREAMING: return local ? "streaming to the CrealityScan app on the phone" : "streaming to a PC" + over;
            case Dash.PC: return local ? "the CrealityScan app connected, not streaming" : "a PC connected" + over + ", not streaming";
            case Dash.ERROR: return "the bridge was on, showing a problem";
            default: return "the bridge was on" + over + ", waiting for " + (local ? "the CrealityScan app" : "a PC");
        }
    }

    // ------------------------------------------------------------------ Android parts

    private static volatile Context app;
    private static volatile long appStartMs;
    private static volatile String lastError;

    /** Idempotent: remembers the app context and adds the crash handler (each entry point calls it). */
    static synchronized void install(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        appStartMs = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime());
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                lastError = error(ex);
                saveNow();
            } catch (Throwable ignored) {
            } finally {
                if (prev != null) prev.uncaughtException(th, ex);
            }
        });
    }

    /** Saves this process's record now (from Dash's sampling thread or the crash handler). */
    static void saveNow() {
        Context c = app;
        if (c == null) return;
        try {
            Bridge b = BridgeService.current();
            Dash.Snap s = b != null ? b.dash.snap : DeviceReport.lastSnap;
            List<Object[]> ev = b != null ? b.dash.recentEvents() : new ArrayList<>();
            List<String> secrets = s == null ? new ArrayList<>()
                    : Arrays.asList(s.staSsid, s.p2pSsid, s.p2pPass, s.scannerSn);
            List<Object[]> clean = new ArrayList<>();
            for (Object[] e : ev) clean.add(new Object[]{e[0], scrub((String) e[1], secrets)});
            int pid = android.os.Process.myPid();
            JSONObject j = toJson(pid, appStartMs, System.currentTimeMillis(), s, SessionStats.cur, clean, lastError);
            File dir = new File(c.getFilesDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, pid + ".tmp"), out = new File(dir, pid + ".json");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(out)) return;
            prune(dir);
        } catch (Exception e) {
            BLog.log("crash_record_error", "err", String.valueOf(e));
        }
    }

    private static void prune(File dir) {
        File[] f = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (f == null || f.length <= KEEP) return;
        Arrays.sort(f, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < f.length; i++) //noinspection ResultOfMethodCallIgnored
            f[i].delete();
    }

    /** The saved record of the process with this pid, or null (none kept, or unreadable). */
    static Run load(Context c, int pid) {
        File f = new File(new File(c.getFilesDir(), DIR), pid + ".json");
        if (pid <= 0 || pid == android.os.Process.myPid() || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int n = in.read(b);
            return fromJson(new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
