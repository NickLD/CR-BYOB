package com.crbyob.bridge;

import android.app.Activity;
import android.os.Build;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Send a device report": a review dialog with every field of the GitHub Device report form
 * (.github/ISSUE_TEMPLATE/device-report.yml), filled in with what the app already knows; the measured values
 * (firmware, app/phone/Android) are shown but not editable. Continue offers the apps that open GitHub links (the
 * GitHub app, browsers) and opens the form there with exactly those values, through its URL parameters (field
 * id = value), where the person submits it; nothing is sent from the app.
 *
 * Filled in: title, scanner, firmware, app/phone/Android line, the network the bridge ran on, and this session's
 * measurements (fps and delivery per stream, bitrate, frame-rate setting, USB speed, link). Never filled in,
 * because the issue is public: the scanner serial, Wi-Fi network names, IP addresses and the raw log.
 */
final class DeviceReport {
    private DeviceReport() {}

    static final String TEMPLATE = "device-report.yml";
    /** The template's "Other" scanner option, for a scanner whose name isn't in its list. */
    static final String OTHER = "Other (say which below)";

    /** The latest dashboard snapshot (running or idle), kept by MainActivity for reports opened from the docs. */
    static volatile Dash.Snap lastSnap;

    /** Form fields by template id, in form order. Values never null. */
    static final class Fields {
        String title = "", scanner = "", scannerFw = "", appInfo = "", network = "", client = "", result = "", details = "";
        /** The scanner was identified over USB (a model the app knows): shown, not picked. */
        boolean scannerDetected;
        /** Where the measurements come from: the bridge session (SessionStats), 0 = no session / not streamed. */
        long streamMs, sessionStartMs, sessionStopMs;
    }

    /** The template's Network and Result options (DeviceReportTest checks them against the form). */
    static final String[] NETWORKS = {"Wi-Fi Direct", "Wi-Fi", "Ethernet",
            "This phone only (CrealityScan mobile app)"};
    static final String[] RESULTS = {"Works (preview and a full scan)", "Partly works (say what doesn't)", "Doesn't work"};

    /** Template option for the network the bridge answers on (Snap.advertise). */
    static String networkOption(String advertise) {
        if (advertise == null) return "";
        switch (advertise) {
            case "p2p": return "Wi-Fi Direct";
            case "wifi": return "Wi-Fi";
            case "eth": return "Ethernet";
            case "local": return "This phone only (CrealityScan mobile app)";
            default: return "";
        }
    }

    /** The pre-filled "new issue" URL. Empty fields are left out so the form shows its own placeholder. */
    static String url(String repoUrl, Fields f) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("template", TEMPLATE);
        q.put("title", f.title);
        q.put("scanner", f.scanner);
        q.put("scanner_fw", f.scannerFw);
        q.put("app_info", f.appInfo);
        q.put("network", f.network);
        q.put("client", f.client);
        q.put("result", f.result);
        q.put("details", f.details);
        q.put("labels", "device report");
        // The GitHub app ignores the form's fields and only takes title/body: the same report as text, laid out
        // the way GitHub writes a submitted form. The web form fills its fields from the ids above instead.
        q.put("body", body(f));
        StringBuilder sb = new StringBuilder(repoUrl).append("/issues/new");
        char sep = '?';
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    /** The report as GitHub renders a submitted issue form: "### Label", a blank line, the value ("_No response_"). */
    static String body(Fields f) {
        String[][] rows = {
                {"Scanner", f.scanner}, {"Scanner firmware", f.scannerFw}, {"App, phone and Android version", f.appInfo},
                {"Network", f.network}, {"Creality Scan version", f.client}, {"Result", f.result},
                {"What happened", f.details}, {"Log", ""}};
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("### ").append(r[0]).append("\n\n").append(r[1] == null || r[1].isEmpty() ? "_No response_" : r[1]);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");     // %20: GitHub keeps "+" literally
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Is this link the Device report form (from the docs)? Then the app opens it pre-filled instead. */
    static boolean isFormLink(String url) {
        return url != null && url.contains("/issues/new") && url.contains("template=" + TEMPLATE);
    }

    /** Fills the fields from a dashboard snapshot (may be null), the current session's stats and the build. */
    static Fields collect(Dash.Snap s, SessionStats ss, String appLine, String phone) {
        return collect(s, ss, appLine, phone, null);
    }

    /** How Android treats the app on this phone: background limits and why the app last closed. */
    static final class AppHealth {
        Boolean unrestricted;   // exempt from battery optimization; null = unknown
        String lastExit;        // exitLine() of the previous process, null = none recorded
    }

    static Fields collect(Dash.Snap s, SessionStats ss, String appLine, String phone, AppHealth health) {
        Fields f = new Fields();
        String model = s == null ? null : s.modelName != null ? s.modelName : (s.scanner || s.plugged) ? s.scannerModel : null;
        boolean unlisted = model != null && model.startsWith("PID ");     // product ID not in scanners.json
        f.scanner = model == null ? "" : unlisted ? OTHER : model;
        f.scannerDetected = model != null && !unlisted;
        f.scannerFw = s != null && s.scannerFw != null ? s.scannerFw : "";
        f.appInfo = appLine;
        f.network = s == null ? "" : networkOption(s.advertise);
        f.title = "[Device] " + (model != null ? model : "<scanner>") + " on " + phone;
        if (ss != null) {
            f.streamMs = ss.streamMs;
            f.sessionStartMs = ss.startMs;
            f.sessionStopMs = ss.stopMs;
        }

        StringBuilder d = new StringBuilder();
        boolean streamed = ss != null && ss.streamMs > 0;
        d.append(streamed ? "Measured by CR-BYOB over " + SessionStats.dur(ss.streamMs) + " of streaming:\n"
                : "Measured by CR-BYOB (no streaming in this session yet):\n");
        if (model != null && s != null && (s.scanner || s.plugProbed)) d.append("- USB: ").append(s.usbSuper ? "USB 3" : "USB 2").append('\n');
        if (s != null && s.fpsMode != null) d.append("- Frame-rate setting: ").append(cap(s.fpsMode)).append('\n');
        if (s != null && !f.network.isEmpty()) {
            d.append("- Connection: ").append(f.network);
            if ("p2p".equals(s.advertise) && s.p2pFreq > 0) d.append(", ").append(Dash.band(s.p2pFreq));
            if ("wifi".equals(s.advertise) && s.staConnected) {                   // generation, band, signal: no SSID
                d.append(", ");
                if (s.staStd > 0) d.append("Wi-Fi ").append(s.staStd).append(", ");
                d.append(Dash.band(s.staFreq)).append(", ").append(s.staRssi).append(" dBm");
                int rate = s.staTx > 0 ? s.staTx : s.staLink;
                if (rate > 0) d.append(", ").append(rate).append(" Mbit/s link rate");
            }
            d.append('\n');
        }
        if (streamed) {
            String[] n = {"IR L", "IR R", "Color"};
            for (int i = 0; i < 3; i++) {
                if (ss.streamingMsPer[i] <= 0) continue;
                double fps = ss.sent[i] * 1000.0 / ss.streamingMsPer[i];
                d.append(String.format(Locale.US, "- %s: %.1f%s fps, %.0f%% of captured frames sent",
                        n[i], fps, ss.reqFps[i] > 0 ? " of " + ss.reqFps[i] : "", SessionStats.pct(ss.sent[i], ss.captured[i])));
                long dropped = ss.qdrops[i] + ss.skipped[i];
                if (dropped > 0) d.append(", ").append(dropped).append(" dropped");
                if (ss.reducedMs[i] > 0)                   // ½ or ⅓ rate because the link couldn't keep up
                    d.append(String.format(Locale.US, ", at reduced rate %.0f%% of the time",
                            SessionStats.pct(ss.reducedMs[i], ss.streamingMsPer[i])));
                d.append('\n');
            }
            d.append(String.format(Locale.US, "- Data: avg %.0f Mbit/s, peak %.0f Mbit/s%n", ss.avgMbps(), ss.peakMbps));
            // only when the bridge runs on Wi-Fi: with This phone only the phone's Wi-Fi plays no part
            if (s != null && "wifi".equals(s.advertise) && ss.rssiN > 0) {
                d.append(String.format(Locale.US, "- Wi-Fi signal during the session: avg %d dBm, worst %d dBm",
                        ss.rssiSum / ss.rssiN, ss.rssiMin));
                if (ss.linkMin > 0) d.append(", lowest link rate ").append(ss.linkMin).append(" Mbit/s");
                d.append('\n');
            }
            if (s != null && "p2p".equals(s.advertise))   // the two share the phone's radio: Wi-Fi Direct slows down
                d.append("- Phone also on a Wi-Fi network: ").append(ss.p2pDualMs <= 0 ? "no"
                        : String.format(Locale.US, "%.0f%% of the streaming time (%s), sharing the radio with Wi-Fi Direct",
                                SessionStats.pct(ss.p2pDualMs, ss.streamMs), Dash.band(ss.p2pDualFreq))).append('\n');
            if (ss.frameAge.count() > 0) {
                d.append(String.format(Locale.US, "- Latency: frame age avg %.1f ms, p95 %.0f ms",
                        ss.frameAge.avgMs(), ss.frameAge.pMs(0.95)));
                if (ss.xu.count() > 0) d.append(String.format(Locale.US, "; scanner replies p95 %.0f ms", ss.xu.pMs(0.95)));
                d.append('\n');
            }
            long onPower = ss.poweredMs, onBattery = ss.batteryMs;
            if (onPower + onBattery > 0) {
                d.append("- Phone power: ");
                if (onBattery == 0) d.append("external the whole session").append(ss.charger != null ? " (" + ss.charger + ")" : "");
                else if (onPower == 0) d.append("battery the whole session");
                else d.append(String.format(Locale.US, "on battery %.0f%% of the session", SessionStats.pct(onBattery, onPower + onBattery)))
                        .append(ss.charger != null ? ", otherwise " + ss.charger : "");
                d.append('\n');
            }
            if (ss.batteryStart >= 0 && ss.batteryEnd >= 0)
                d.append("- Phone battery: ").append(ss.batteryStart).append("% at the start, ").append(ss.batteryEnd).append("% at the end\n");
            // a deliberate unplug and a brown-out look the same to the app: when it happened tells them apart
            long det = ss.usbDetach.get(), detStreaming = ss.detachStreaming.get(), onBatt = ss.brownouts.get();
            d.append("- Scanner disconnects: ");
            if (det == 0) d.append("none");
            else {
                d.append(detStreaming).append(" while streaming, ").append(det - detStreaming).append(" while not streaming");
                if (onBatt > 0) d.append(" (").append(onBatt).append(" while the phone was on battery)");
            }
            d.append('\n');
            List<String> also = new ArrayList<>();
            count(also, ss.pcDrops.get(), "PC disconnect", "PC disconnects");
            count(also, ss.rtspErrors.get(), "stream error", "stream errors");
            count(also, ss.stalledClosed.get(), "stalled stream closed", "stalled streams closed");
            count(also, ss.uvcErrors.get(), "USB frame error", "USB frame errors");
            if (!also.isEmpty()) d.append("- Also counted: ").append(String.join(", ", also)).append('\n');
            long mirror = ss.mirrorConnects.get();
            d.append("- Mirror viewer: ").append(mirror == 0 ? "not used"
                    : "used (" + mirror + (mirror == 1 ? " connection)" : " connections)")).append('\n');
            if (ss.thermalMax >= 0)
                d.append("- Phone heat (worst Android thermal status): ").append(SessionStats.thermalName(ss.thermalMax)).append('\n');
        }
        if (s != null && s.soc != null && !s.soc.isEmpty()) d.append("- Phone chip: ").append(s.soc).append('\n');
        if (health != null && health.unrestricted != null)
            d.append("- Battery optimization: ").append(health.unrestricted ? "off for CR-BYOB (unrestricted)"
                    : "on for CR-BYOB (Android may stop it in the background)").append('\n');
        if (health != null && health.lastExit != null) d.append("- App crashes and kills: ").append(health.lastExit).append('\n');
        if (s != null && s.linkScore >= 0 && s.linkWord != null) d.append("- Link quality now: ").append(s.linkWord).append('\n');
        if (f.scanner.isEmpty()) d.append("- Scanner: not plugged in when this report was opened\n");
        if (unlisted) d.append("- Scanner USB ID: ").append(model.substring(4)).append(" (not in the app's model list: please say which model it is)\n");
        d.append("\nWhat I did, what worked, what didn't:\n\n");
        f.details = d.toString();
        return f;
    }

    /** The line above "What happened": which session the measurements are from, or that there are none. */
    static String statsSource(Fields f, java.text.DateFormat time) {
        if (f.streamMs <= 0)
            return "No streaming in this bridge session yet, so the report has no frame rates. Run a scan first for a "
                    + "more useful report.";
        String when = f.sessionStartMs > 0 ? ", started " + time.format(new java.util.Date(f.sessionStartMs))
                + (f.sessionStopMs > 0 ? ", stopped " + time.format(new java.util.Date(f.sessionStopMs)) : ", still running")
                : "";
        return "Measurements are from this bridge session: " + SessionStats.dur(f.streamMs) + " of streaming" + when + ".";
    }

    /**
     * Ask before sending: nothing was streamed in this session, so the report has no frame rates to back up
     * "Works" or "Partly works". "Doesn't work" is exempt: a setup that fails often can't stream at all.
     */
    static boolean needsScanFirst(Fields f) {
        return f.streamMs <= 0 && !f.result.isEmpty() && !RESULTS[2].equals(f.result);
    }

    /** The line that ends the measurements in "What happened"; what the person types goes after it. */
    static final String NOTES_MARKER = "What I did, what worked, what didn't:";

    /** What the person typed in "What happened" after the measurements (the part a draft keeps). */
    static String notesOf(String details) {
        int i = details.indexOf(NOTES_MARKER);
        return i < 0 ? details.trim() : details.substring(i + NOTES_MARKER.length()).trim();
    }

    /** The person's own entries, kept when they leave to scan first, so the next report starts with them. */
    static volatile Fields draft;

    /** Carries the person's entries from a draft into freshly collected fields (measurements stay fresh). */
    static void restore(Fields f, Fields d) {
        if (d == null) return;
        if (!d.client.isEmpty()) f.client = d.client;
        if (!d.result.isEmpty()) f.result = d.result;
        if (!f.scannerDetected && !d.scanner.isEmpty()) f.scanner = d.scanner;
        String notes = notesOf(d.details);
        if (!notes.isEmpty()) f.details = f.details.trim() + "\n" + notes + "\n";
    }

    /** Battery-optimization exemption and the previous process's exit (Android 11+), read when the report opens. */
    static AppHealth appHealth(android.content.Context c) {
        AppHealth h = new AppHealth();
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) h.unrestricted = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception ignored) {
        }
        Exits e = exits(c);
        if (e != null) h.lastExit = lastProblemExit(e.reasons, e.importance, e.ages());
        return h;
    }

    /** How the app's last processes ended, newest first (ApplicationExitInfo, Android 11+). */
    static final class Exits {
        int[] reasons = new int[0], importance = new int[0];
        long[] timestamps = new long[0];

        long[] ages() {
            long now = System.currentTimeMillis();
            long[] a = new long[timestamps.length];
            for (int i = 0; i < a.length; i++) a[i] = now - timestamps[i];
            return a;
        }
    }

    /** Android keeps these after the process dies, so the next run can see how the last ones ended. Null below 11. */
    static Exits exits(android.content.Context c) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 10);
            Exits e = new Exits();
            e.reasons = new int[l.size()];
            e.importance = new int[l.size()];
            e.timestamps = new long[l.size()];
            for (int i = 0; i < l.size(); i++) {
                e.reasons[i] = l.get(i).getReason();
                e.importance[i] = l.get(i).getImportance();
                e.timestamps[i] = l.get(i).getTimestamp();
            }
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Did this exit point at a problem? Crashes, ANRs and failed starts always do. Android killing the app counts
     * only while it was in use (importance VISIBLE = 200 or better: the dashboard open or the bridge's foreground
     * service running); clearing a cached background app is routine. Normal closes never count.
     */
    static boolean isProblem(int reason, int importance) {
        switch (reason) {
            case 4: case 5: case 6: case 7: return true;                          // crash, native crash, ANR, start
            case 1: case 8: case 10: case 11: case 15: case 16: return false;     // itself, permission, user, update
            default: return importance > 0 && importance <= 200;                  // killed by Android
        }
    }

    /** Index of the newest problem exit within the last 24 h (arrays newest first), or -1. */
    static int newestProblem(int[] reasons, int[] importance, long[] agesMs) {
        for (int i = 0; i < reasons.length; i++) {
            if (agesMs[i] > 24 * 3_600_000L) break;
            if (isProblem(reasons[i], importance[i])) return i;
        }
        return -1;
    }

    /**
     * The newest problem exit of the last 24 h in words; a note when there's none; null with no history. Normal
     * closes are skipped, so a crash doesn't hide behind a later swipe-away.
     */
    static String lastProblemExit(int[] reasons, int[] importance, long[] agesMs) {
        if (reasons.length == 0) return null;
        int i = newestProblem(reasons, importance, agesMs);
        return i < 0 ? "no crash or kill by Android in the last 24 h" : exitLine(reasons[i], agesMs[i]);
    }

    /**
     * ApplicationExitInfo.REASON_* in plain words, plus how long ago: "killed by Android (low memory), 12 min ago".
     * Numbers rather than the constants, so the table compiles and tests without the newer API levels' fields.
     */
    static String exitLine(int reason, long agoMs) {
        String why;
        switch (reason) {
            case 1: why = "closed normally"; break;
            case 2: why = "stopped by Android (signal)"; break;
            case 3: why = "killed by Android (low memory)"; break;
            case 4: why = "crashed"; break;
            case 5: why = "crashed (native code)"; break;
            case 6: why = "stopped responding (ANR)"; break;
            case 7: why = "failed to start"; break;
            case 8: why = "restarted after a permission change"; break;
            case 9: why = "killed by Android (too much battery or CPU use)"; break;
            case 10: why = "closed by the user (swiped away or force-stopped)"; break;
            case 11: why = "force-stopped by the user"; break;
            case 12: why = "stopped because a component it relies on stopped"; break;
            case 13: why = "killed by Android (other)"; break;
            case 14: why = "frozen by Android"; break;
            case 15: why = "closed after a change to the app's state"; break;
            case 16: why = "closed for an app update"; break;
            default: why = reason == 0 ? "unknown reason" : "other (reason " + reason + ")";
        }
        long min = Math.max(0, agoMs) / 60_000;
        String ago = min < 1 ? "just now" : min < 60 ? min + " min ago" : min < 48 * 60 ? min / 60 + " h ago" : min / (24 * 60) + " d ago";
        return why + ", " + ago;
    }

    /** "2 stream errors" / "1 PC disconnect", only when the count isn't zero. */
    private static void count(List<String> out, long n, String one, String many) {
        if (n > 0) out.add(n + " " + (n == 1 ? one : many));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Shows the review dialog, filled in from the bridge (running) or the dashboard's last snapshot. */
    static void open(Activity a) {
        Bridge b = BridgeService.current();
        Dash.Snap s = b != null ? b.dash.snap : lastSnap;
        Fields f = collect(s, SessionStats.cur, AppVersion.bugReportLine(), Build.MANUFACTURER + " " + Build.MODEL, appHealth(a));
        restore(f, draft);
        draft = null;
        BLog.log("device_report_review", "scanner", f.scanner, "network", f.network);
        new Review(a, f).show();
    }

    /** Scanner names for the picker: every model in scanners.json, in its order, then "Other". */
    static java.util.List<String> scannerOptions(android.content.Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONObject models = ScannerModel.loadDocument(c, c.getFilesDir()).getJSONObject("models");
            for (java.util.Iterator<String> it = models.keys(); it.hasNext(); ) {
                String n = models.getJSONObject(it.next()).optString("name", "");
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        } catch (Exception ignored) {
        }
        out.add(OTHER);
        return out;
    }

    /**
     * The review dialog: the form's fields, in its order, styled like the rest of the app. Lists open a picker;
     * the rest are text fields. Continue needs the form's required fields, then opens GitHub in the browser.
     */
    private static final class Review {
        private final Activity a;
        private final Fields f;
        private final android.widget.LinearLayout body;
        private android.widget.EditText title, client, details;
        private android.widget.TextView scanner, network, result, error;
        private android.widget.ScrollView scroll;
        private boolean tried;                       // Continue was tapped once: missing fields show in red
        private android.app.AlertDialog dialog;

        Review(Activity a, Fields f) {
            this.a = a;
            this.f = f;
            body = new android.widget.LinearLayout(a);
            body.setOrientation(android.widget.LinearLayout.VERTICAL);
            body.setPadding(dp(20), dp(4), dp(20), dp(8));
        }

        private int dp(float v) {
            return Widgets.dp(a, v);
        }

        private android.widget.TextView text(float sp, int color) {
            android.widget.TextView t = new android.widget.TextView(a);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            return t;
        }

        private void label(String s, boolean required) {
            android.widget.TextView t = text(13, Widgets.SUB);
            t.setText(required ? s + " *" : s);
            t.setPadding(0, dp(12), 0, dp(4));
            body.addView(t);
        }

        private android.widget.EditText field(String value, String hint, boolean multi) {
            android.widget.EditText e = new android.widget.EditText(a);
            e.setTextColor(Widgets.TEXT);
            e.setHintTextColor(Widgets.GREY);
            e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            mark(e, false);
            e.setPadding(dp(10), dp(8), dp(10), dp(8));
            e.setText(value);
            e.setHint(hint);
            e.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int a1, int b1, int c1) { }
                @Override public void onTextChanged(CharSequence x, int a1, int b1, int c1) { }
                @Override public void afterTextChanged(android.text.Editable x) { if (tried) check(); }
            });
            if (multi) {
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                e.setSingleLine(false);
                e.setMinLines(6);
                e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            } else {
                // one line of input (Enter doesn't add a line break) that still wraps on screen, so long values
                // such as the app/phone line show in full
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                e.setHorizontallyScrolling(false);
                e.setMaxLines(4);
            }
            body.addView(e, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return e;
        }

        /**
         * A value the app measured (firmware, app/phone/Android): shown as-is and not editable, so reports carry
         * exactly what the phone and scanner said. Outlined instead of filled, to read as fixed.
         */
        private void fixed(String value, boolean known) {
            android.widget.TextView t = text(15, known ? Widgets.TEXT : Widgets.GREY);
            android.graphics.drawable.GradientDrawable g = Widgets.roundRect(0x00000000, dp(8));
            g.setStroke(Math.max(1, dp(1)), Widgets.LINE);
            t.setBackground(g);
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            t.setText(value);
            t.setTextIsSelectable(true);                 // can still be copied
            body.addView(t, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /** A field that picks one of `options` in a styled list; "Choose…" until set. */
        private android.widget.TextView picker(String title, String value, java.util.List<String> options) {
            android.widget.TextView t = text(15, Widgets.TEXT);
            mark(t, false);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            setPick(t, value);
            t.setOnClickListener(v -> {
                String cur = (String) t.getTag();
                android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                        .setTitle(title)
                        .setSingleChoiceItems(options.toArray(new String[0]), options.indexOf(cur), (dd, which) -> {
                            setPick(t, options.get(which));
                            dd.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .create();
                Widgets.showStyled(d);
            });
            body.addView(t, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return t;
        }

        private void setPick(android.widget.TextView t, String value) {
            boolean set = value != null && !value.isEmpty();
            t.setTag(set ? value : "");
            t.setText(set ? value + "  \u25BE" : "Choose\u2026  \u25BE");
            t.setTextColor(set ? Widgets.TEXT : Widgets.GREY);
            if (tried) check();
        }

        /** A small note line under a field (hidden until set). */
        private android.widget.TextView note(int color) {
            android.widget.TextView t = text(13, color);
            t.setPadding(0, dp(4), 0, dp(2));
            t.setVisibility(android.view.View.GONE);
            body.addView(t);
            return t;
        }

        /** An editable field's box: filled, with a red outline while it's required and still empty. */
        private void mark(android.view.View v, boolean missing) {
            android.graphics.drawable.GradientDrawable g = Widgets.roundRect(Widgets.LINE, dp(8));
            if (missing) g.setStroke(Math.max(1, dp(2)), Widgets.RED);
            int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setBackground(g);
            v.setPadding(l, t, r, b);                // setBackground can reset padding
        }

        void show() {
            android.widget.TextView intro = text(14, Widgets.SUB);
            intro.setText("This is everything the report will contain. Add what you saw and fix anything that's off; "
                    + "the scanner, its firmware and the app details come straight from the scanner and this phone. Continue opens it "
                    + "on GitHub, where you submit it.");
            body.addView(intro);
            label("Title", true);
            title = field(f.title, "[Device] <scanner> on <phone>", false);
            if (f.scannerDetected) {                   // the plugged-in scanner, identified by its USB product ID
                label("Scanner \u00B7 detected over USB", false);
                fixed(f.scanner, true);
            } else {                                   // none plugged in, or a model the app doesn't know
                label("Scanner", true);
                scanner = picker("Scanner", f.scanner, scannerOptions(a));
            }
            label("Scanner firmware \u00B7 read from the scanner", false);
            fixed(f.scannerFw.isEmpty() ? "Unknown: plug the scanner in to read it" : f.scannerFw, !f.scannerFw.isEmpty());
            label("App, phone and Android version \u00B7 from this phone", false);
            fixed(f.appInfo, true);
            label("Network", true);
            network = picker("Network", f.network, java.util.Arrays.asList(NETWORKS));
            label("Creality Scan version", false);
            client = field(f.client, "Creality Scan 4.x (Windows) / CrealityScan 3.1.2 (Android)", false);
            label("Result", true);
            result = picker("Result", f.result, java.util.Arrays.asList(RESULTS));
            label("What happened", true);
            android.widget.TextView src = note(f.streamMs > 0 ? Widgets.SUB : Widgets.AMBER);
            src.setText(statsSource(f, android.text.format.DateFormat.getTimeFormat(a)));
            src.setVisibility(android.view.View.VISIBLE);
            details = field(f.details, "", true);
            android.widget.TextView privacy = text(13, Widgets.SUB);
            privacy.setText("Never included: the scanner's serial number, Wi-Fi network names, IP addresses and the "
                    + "log. The report is public on GitHub.");
            privacy.setPadding(0, dp(12), 0, 0);
            body.addView(privacy);
            error = text(14, Widgets.RED);
            error.setPadding(0, dp(8), 0, 0);
            error.setVisibility(android.view.View.GONE);
            body.addView(error);

            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(body);
            scroll = sv;
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                    .setTitle("Device report")
                    .setView(sv)
                    .setPositiveButton("Continue on GitHub", null)
                    .setNegativeButton("Cancel", null)
                    .create();
            if (d.getWindow() != null)
                d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (submit()) d.dismiss();
            }));
            dialog = d;
            Widgets.showStyled(d);
        }

        /**
         * No streaming stats in this session: ask before sending. "Scan first" closes the report and keeps the
         * person's entries for the next one; "Send anyway" goes on to GitHub.
         */
        private void confirmNoStats() {
            android.app.AlertDialog c = new android.app.AlertDialog.Builder(a)
                    .setTitle("No scan in this report")
                    .setMessage("No streaming stats were captured in this bridge session, so the report can't show "
                            + "frame rates or how the scan went. Can you do a quick scan first? Start the bridge, scan "
                            + "for a minute, then come back here: what you entered is kept.")
                    .setPositiveButton("Scan first", (x, w) -> {
                        draft = f;
                        BLog.log("device_report_scan_first");
                        if (dialog != null) dialog.dismiss();
                    })
                    .setNegativeButton("Send anyway", (x, w) -> {
                        if (dialog != null) dialog.dismiss();
                        openWith(url(BuildConfig.REPO_URL, f));
                    })
                    .create();
            Widgets.showStyled(c);
        }

        /**
         * Asks which app opens the report: the GitHub app and every browser, in a list styled like the app.
         * Android's own chooser can't do this: it goes straight to the GitHub app, which is the verified handler
         * for github.com links. With a single app it opens that one directly.
         */
        private void openWith(String url) {
            android.content.pm.PackageManager pm = a.getPackageManager();
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)).addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            java.util.List<android.content.pm.ResolveInfo> found = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (android.content.Intent probe : new android.content.Intent[]{view, new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)}) {   // the browsers, too
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_ALL)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!"android".equals(pkg) && seen.add(pkg)) found.add(ri);
                }
            }
            if (found.isEmpty()) {
                android.widget.Toast.makeText(a, "No app can open GitHub links", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            if (found.size() == 1) {
                launch(view, found.get(0).activityInfo.packageName);
                return;
            }
            String[] labels = new String[found.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(found.get(i).loadLabel(pm));
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                    .setTitle("Open the report in")
                    .setItems(labels, (dd, which) -> launch(view, found.get(which).activityInfo.packageName))
                    .setNegativeButton("Cancel", null)
                    .create();
            Widgets.showStyled(d);
        }

        private void launch(android.content.Intent view, String pkg) {
            BLog.log("device_report_opened", "app", pkg, "scanner", f.scanner, "result", f.result);
            try {
                a.startActivity(new android.content.Intent(view).setPackage(pkg));
            } catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(a, "Couldn't open " + pkg, android.widget.Toast.LENGTH_LONG).show();
            }
        }

        /** Copies the dialog into the fields; false (and says what's missing) while a required one is empty. */
        private boolean submit() {
            tried = true;
            android.view.View first = check();
            if (first != null) {                      // show the first one that still needs an answer
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, first.getTop() - dp(40))));
                return false;
            }
            if (needsScanFirst(f)) {                   // no stats to back the result: ask first
                confirmNoStats();
                return false;
            }
            openWith(url(BuildConfig.REPO_URL, f));
            return true;
        }

        /**
         * Copies the dialog into the fields and outlines every required one that's still empty in red; the line
         * at the bottom lists them. Returns the first missing field's view, or null when the report is complete.
         */
        private android.view.View check() {
            if (title == null || network == null || result == null || details == null || error == null) return null;
            f.title = title.getText().toString().trim();
            if (scanner != null) f.scanner = (String) scanner.getTag();
            f.network = (String) network.getTag();
            f.client = client.getText().toString().trim();
            f.result = (String) result.getTag();
            f.details = details.getText().toString().trim();
            java.util.List<String> missing = new java.util.ArrayList<>();
            android.view.View first = null;
            Object[][] req = {{"Title", title, f.title}, {"Scanner", scanner, f.scanner}, {"Network", network, f.network},
                    {"Result", result, f.result}, {"What happened", details, f.details}};
            for (Object[] r : req) {
                android.view.View v = (android.view.View) r[1];
                if (v == null) continue;                  // the scanner shown as detected, not picked
                boolean empty = ((String) r[2]).isEmpty();
                mark(v, empty);
                if (empty) {
                    missing.add((String) r[0]);
                    if (first == null) first = v;
                }
            }
            error.setText(missing.isEmpty() ? "" : "Still needed: " + String.join(", ", missing));
            error.setVisibility(missing.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            return first;
        }
    }
}
