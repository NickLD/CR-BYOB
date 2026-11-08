package com.crbyob.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "First scan go well?": a one-time card on the dashboard after the first real scan, asking for a device report
 * when the phone or the scanner has none yet. Pure logic (no Android types), unit-tested; MainActivity shows it.
 *
 * Who has a report: scanners marked "verified" in scanners.json, and phones in the table of the bundled
 * docs/phones.md. Phone names are matched exactly ("Google Pixel 10 Pro XL" = manufacturer + model), so a phone
 * whose Android model is a code (Samsung "SM-S928B") isn't recognized and is asked once even if it's listed.
 */
final class ReportPrompt {
    private ReportPrompt() {}

    /** Shared pref: the card was shown and answered (tapped or dismissed). It never comes back after that. */
    static final String PREF_DONE = "report_prompt_done";
    /** A scan, not a quick test: streaming this long in the session before the card can appear. */
    static final long MIN_STREAM_MS = 60_000;
    /** ...and the stream has been stopped this long (Creality Scan closed or disconnected), not a preview flicker. */
    static final long ENDED_FOR_MS = 10_000;

    /** The phone names in phones.md's table: the link text in each row's first cell. */
    static List<String> listedPhones(String phonesMd) {
        List<String> out = new ArrayList<>();
        if (phonesMd == null) return out;
        Matcher m = Pattern.compile("(?m)^\\|\\s*\\[([^\\]]+)\\]").matcher(phonesMd);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Is this phone in the list? Exact match on "manufacturer model" or the model alone, ignoring case. */
    static boolean phoneListed(List<String> listed, String manufacturer, String model) {
        String full = norm(manufacturer + " " + model), bare = norm(model);
        for (String p : listed) {
            String n = norm(p);
            if (n.equals(full) || n.equals(bare)) return true;
        }
        return false;
    }

    /** The phone as people say it: "Google Pixel 9", "Samsung SM-S928B" (manufacturer capitalized). */
    static String phoneName(String manufacturer, String model) {
        String m = manufacturer == null ? "" : manufacturer.trim();
        if (!m.isEmpty()) m = Character.toUpperCase(m.charAt(0)) + m.substring(1);
        if (model != null && model.toLowerCase(Locale.ROOT).startsWith(m.toLowerCase(Locale.ROOT))) return model;
        return (m + " " + (model == null ? "" : model)).trim();
    }

    /**
     * What has no report yet, for the card's sentence ("your Google Pixel 9 with a CR-Scan Raptor", "your Google
     * Pixel 9", "the CR-Scan Raptor"), or null when both are covered and there's nothing to ask.
     */
    static String missing(boolean phoneListed, String phone, String scanner, boolean scannerVerified) {
        boolean scannerMissing = scanner != null && !scanner.isEmpty() && !scannerVerified;
        if (!phoneListed && scannerMissing) return "your " + phone + " with a " + scanner;
        if (!phoneListed) return "your " + phone;
        if (scannerMissing) return "the " + scanner;
        return null;
    }

    /** The first scan has ended: enough streaming in the session, and the stream stopped for a while (or long ago). */
    static boolean scanEnded(long sessionStreamMs, boolean streamingNow, long lastStreamingAt, long now) {
        if (sessionStreamMs < MIN_STREAM_MS || streamingNow) return false;
        return lastStreamingAt <= 0 || now - lastStreamingAt >= ENDED_FOR_MS;
    }

    /** Shared pref: exit timestamp of the newest crash the "stopped unexpectedly" card was shown for. */
    static final String PREF_CRASH_SEEN = "crash_prompt_seen";

    /** The crash card: a problem exit (DeviceReport.newestProblem) newer than the last one it was shown for. */
    static boolean crashDue(long exitTs, long seenTs) {
        return exitTs > 0 && exitTs > seenTs;
    }

    /**
     * exitLine: DeviceReport.exitLine ("crashed, 5 min ago"). summary: DeviceReport.stoppedRunSummary when the run's
     * crash record was kept, else null (then its measurements are gone, and the card says so).
     */
    static String crashMessage(String exitLine, String summary) {
        if (summary == null)
            return "Last run: " + exitLine + ". Tap here to send a device report about it: it includes how the "
                    + "app stopped, and you can add what you were doing. The measurements of that session are lost, so "
                    + "say what happened.";
        return "Last run: " + exitLine + " (" + summary + "). Tap here to send a device report about it: it includes "
                + "what the app was doing and its measurements up to then. Add what you were doing.";
    }

    static String message(String missing) {
        return "We don't have a compatibility report for " + missing + " yet. Tap here to help improve CR-BYOB with "
                + "a device report now, or scroll to the bottom of the app to send one another time. We won't show "
                + "this message again.";
    }
}
