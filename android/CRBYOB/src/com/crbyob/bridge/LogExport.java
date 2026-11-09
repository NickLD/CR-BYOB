package com.crbyob.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The log in forms someone can post in a public GitHub issue, with private values removed:
 * - the digest: the scanner setup and the newest lines that matter, small enough to ride in the device report's
 *   pre-filled link (GitHub refuses links much over 6,000 characters);
 * - the archive: the whole log (bridge.jsonl and the one before it), crash records, a scanners.json override
 *   and a readable summary, zipped, for attaching to the issue.
 *
 * Removed: scanner and bridge serial numbers, Wi-Fi network names, passwords, MAC addresses and IP addresses.
 * IP addresses become ip1, ip2, ... (the same address gets the same name throughout one export), so the log
 * still shows which connections came from the same PC.
 */
final class LogExport {
    private LogExport() {}

    /** Keys whose whole value is private. */
    private static final String KEYS = "serial|sn|ssid|bssid|passphrase|password|mac";
    /** In the dashboard's "key=value" lines: the value runs to the next " key=" or the end of the line. */
    private static final Pattern PRIVATE_KV = Pattern.compile(
            "\\b(" + KEYS + ")=(.*?)(?=\\s[A-Za-z_][A-Za-z0-9_]*=|$)");
    /** In bridge.jsonl's JSON lines: a string or bare value. */
    private static final Pattern PRIVATE_JSON = Pattern.compile(
            "\"(" + KEYS + ")\":(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}]*)");
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b");
    /** Four or more colon-separated hex groups, or any "::" form. HH:mm:ss times have three groups. */
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:])(?:(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?)(?:%\\w+)?(?![\\w:])");

    /** Replaces private values in one line. `ips` keeps the names given so far (address -> ipN). */
    static String redact(String line, Map<String, String> ips) {
        String s = PRIVATE_KV.matcher(line).replaceAll("$1=(hidden)");
        s = PRIVATE_JSON.matcher(s).replaceAll("\"$1\":\"(hidden)\"");
        s = MAC.matcher(s).replaceAll("(mac)");
        s = replaceIps(IPV4, s, ips);
        s = replaceIps(IPV6, s, ips);
        return s;
    }

    private static String replaceIps(Pattern p, String s, Map<String, String> ips) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        StringBuffer out = new StringBuffer(s.length());
        do {
            String ip = m.group();
            String name;
            if (ip.equals("0.0.0.0") || ip.startsWith("127.") || ip.equals("::") || ip.equals("::1")) name = ip;
            else {
                name = ips.get(ip);
                if (name == null) {
                    name = "ip" + (ips.size() + 1);
                    ips.put(ip, name);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(name));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    // ---- digest

    /** Kinds logged every second or per request: too many to say much in a few lines, so the digest skips them. */
    private static final Set<String> NOISY = new HashSet<>(java.util.Arrays.asList(
            "hp_req", "gvcp_rx", "gvcp_ack", "uvc_stats", "stream_stats", "imu_stats", "ctrl_get", "ctrl_set",
            "tcp_connect", "tcp_close", "bridge_prop", "linktest", "fc1_fps_floor", "device_report_review",
            "log_exported", "mirror_video", "mirror_bitrate", "mirror_rpc_in", "mirror_rpc_out", "mirror_sig_in",
            "mirror_sig_out", "mirror_js", "mirror_js_console"));
    /** Setup kinds the digest shows in its own section (from the pinned lines), in this order. */
    private static final String[] SETUP_FIRST = {"scanner_model", "scanner_roles"};
    private static final Pattern MODE = Pattern.compile("(\\S+) (\\d+x\\d+)@(\\d+)");

    /** "06:12:01 kind=rtsp_req port=8556" -> "rtsp_req" (null when the line has no kind). */
    static String kindOf(String line) {
        int i = line.indexOf("kind=");
        if (i < 0) return null;
        int e = line.indexOf(' ', i);
        return line.substring(i + 5, e < 0 ? line.length() : e);
    }

    /**
     * One dashboard log line made shorter for the digest: no "kind=", scanner_model without its notes, and
     * usb_vs_modes grouped by format and size ("GREY 640x400@5/10/15/30, 1280x800@5/10") instead of one entry
     * per mode.
     */
    static String compact(String line) {
        String s = line.replaceFirst("kind=", "");
        String kind = kindOf(line);
        if ("scanner_model".equals(kind)) {
            int n = s.indexOf(" notes=");
            if (n >= 0) s = s.substring(0, n);
            s = s.replaceFirst(" registry=\\S+", "");
        } else if ("usb_vs_modes".equals(kind)) {
            int m = s.indexOf(" modes=");
            if (m >= 0) {
                Map<String, Map<String, List<String>>> byFormat = new LinkedHashMap<>();
                Matcher mm = MODE.matcher(s.substring(m + 7));
                while (mm.find()) {
                    byFormat.computeIfAbsent(mm.group(1), k -> new LinkedHashMap<>())
                            .computeIfAbsent(mm.group(2), k -> new ArrayList<>()).add(mm.group(3));
                }
                StringBuilder sb = new StringBuilder(s.substring(0, m)).append(" modes=");
                boolean firstFmt = true;
                for (Map.Entry<String, Map<String, List<String>>> f : byFormat.entrySet()) {
                    if (!firstFmt) sb.append("; ");
                    firstFmt = false;
                    sb.append(f.getKey()).append(' ');
                    boolean firstSize = true;
                    for (Map.Entry<String, List<String>> sz : f.getValue().entrySet()) {
                        if (!firstSize) sb.append(", ");
                        firstSize = false;
                        sb.append(sz.getKey()).append('@').append(String.join("/", sz.getValue()));
                    }
                }
                s = sb.toString();
            }
        }
        return s;
    }

    /** Length of s once URL-encoded the way DeviceReport encodes it. */
    static int encodedLength(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").length();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The digest: a header naming where the full log is (`fullLog`, e.g. the archive's file name), then the
     * scanner setup and the newest lines that aren't per-second noise, redacted, at most `maxEncoded` characters
     * once URL-encoded. When space runs out: scanner model and roles first, then up to 8 recent lines, the
     * usb_vs_modes lines, more recent lines, and last usb_open.
     */
    static String digest(List<String> pinned, List<String> recent, String fullLog, int maxEncoded) {
        Map<String, String> ips = new LinkedHashMap<>();
        String header = "Log digest. Full log: " + fullLog + "\n";
        int used = encodedLength(header);
        if (used > maxEncoded) return "";

        Map<String, String> setupByKind = new LinkedHashMap<>();   // pinned, per kind (usb_vs_modes per iface)
        for (String p : pinned) {
            String k = kindOf(p);
            if (k == null || k.equals("app_version") || k.equals("bridge_started") || k.equals("scanner_ready")) continue;
            String key = k.equals("usb_vs_modes") ? p.replaceFirst(".* iface=(\\S+).*", "usb_vs_modes $1") : k;
            setupByKind.put(key, compact(redact(p, ips)));
        }
        List<String> rec = new ArrayList<>();
        for (String r : recent) {
            String k = kindOf(r);
            if (k != null && (NOISY.contains(k) || setupByKind.containsKey(k) || k.equals("usb_vs_modes"))) continue;
            rec.add(compact(redact(r, ips)));
        }

        String setupTitle = "-- Scanner setup --\n", recentTitle = "-- Recent --\n";
        List<String> setup = new ArrayList<>();
        int[] budget = {maxEncoded - used - encodedLength(setupTitle) - encodedLength(recentTitle)};
        for (String k : SETUP_FIRST) take(setupByKind.remove(k), setup, budget);
        int newest = rec.size();                                  // rec[newest..] are taken
        newest = takeRecent(rec, newest, 8, budget);
        List<String> later = new ArrayList<>();
        for (Map.Entry<String, String> e : new ArrayList<>(setupByKind.entrySet()))
            if (e.getKey().startsWith("usb_vs_modes")) take(setupByKind.remove(e.getKey()), setup, budget);
        newest = takeRecent(rec, newest, Integer.MAX_VALUE, budget);
        for (String v : setupByKind.values()) take(v, later, budget);
        setup.addAll(later);

        StringBuilder sb = new StringBuilder(header);
        if (!setup.isEmpty()) {
            sb.append(setupTitle);
            for (String l : setup) sb.append(l).append('\n');
        }
        if (newest < rec.size()) {
            sb.append(recentTitle);
            for (int i = newest; i < rec.size(); i++) sb.append(rec.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    private static void take(String line, List<String> out, int[] budget) {
        if (line == null) return;
        int n = encodedLength(line + "\n");
        if (n > budget[0]) return;
        out.add(line);
        budget[0] -= n;
    }

    /** Takes up to `max` more lines from the end of `rec` (newest first) while they fit; returns the new start. */
    private static int takeRecent(List<String> rec, int start, int max, int[] budget) {
        for (int taken = 0; start > 0 && taken < max; taken++) {
            int n = encodedLength(rec.get(start - 1) + "\n");
            if (n > budget[0]) break;
            budget[0] -= n;
            start--;
        }
        return start;
    }

    // ---- readable text and archive

    /**
     * The readable log, redacted: `firstLine`, the scanner setup lines (usb_vs_modes grouped), then as many of
     * the newest recent lines as fit in `maxChars`. The GitHub app's report body, and summary.txt in the archive.
     */
    static String readable(List<String> pinned, List<String> recent, String firstLine, int maxChars,
                           Map<String, String> ips) {
        StringBuilder head = new StringBuilder(firstLine).append('\n');
        if (!pinned.isEmpty()) {
            head.append("\n-- Scanner setup (latest) --\n");
            for (String p : pinned) head.append(compact(redact(p, ips))).append('\n');
        }
        List<String> tail = new ArrayList<>();
        for (String r : recent) tail.add(redact(r, ips));
        int budget = maxChars - head.length() - 40, from = tail.size();
        while (from > 0 && budget - tail.get(from - 1).length() - 1 >= 0) budget -= tail.get(--from).length() + 1;
        StringBuilder sb = new StringBuilder(head);
        sb.append("\n-- Last ").append(tail.size() - from).append(" lines --\n");
        for (int i = from; i < tail.size(); i++) sb.append(tail.get(i)).append('\n');
        return sb.toString().trim();
    }

    /** summary.txt in the archive: the readable log with the app line, and where the rest is. */
    static String summary(List<String> pinned, List<String> recent, String appLine, Map<String, String> ips) {
        String first = "CR-BYOB log (serial numbers, Wi-Fi names, passwords, MAC and IP addresses removed)"
                + (appLine != null && !appLine.isEmpty() ? "\n" + appLine : "");
        return readable(pinned, recent, first, Integer.MAX_VALUE / 2, ips)
                + "\n\nThe whole log, oldest first, is in bridge.1.jsonl and bridge.jsonl (one JSON object per line).\n";
    }

    /**
     * Writes the archive: summary.txt, the redacted bridge.1.jsonl and bridge.jsonl, the crash records
     * (runs/*.json) and a scanners.json override, all from the app's files dir.
     */
    static void writeZip(OutputStream os, File filesDir, List<String> pinned, List<String> recent, String appLine)
            throws IOException {
        Map<String, String> ips = new LinkedHashMap<>();
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            Writer w = new OutputStreamWriter(z, StandardCharsets.UTF_8);
            z.putNextEntry(new ZipEntry("summary.txt"));
            w.write(summary(pinned, recent, appLine, ips));
            w.flush();
            z.closeEntry();
            for (String name : new String[]{"bridge.1.jsonl", "bridge.jsonl", "scanners.json"})
                addRedacted(z, w, new File(filesDir, name), name.equals("scanners.json") ? "scanners-override.json" : name, ips);
            File[] runs = new File(filesDir, CrashRecord.DIR).listFiles((d, n) -> n.endsWith(".json"));
            if (runs != null) {
                java.util.Arrays.sort(runs);
                for (File r : runs) addRedacted(z, w, r, "runs/" + r.getName(), ips);
            }
            z.finish();
        }
    }

    private static void addRedacted(ZipOutputStream z, Writer w, File f, String entry, Map<String, String> ips)
            throws IOException {
        if (!f.isFile()) return;
        z.putNextEntry(new ZipEntry(entry));
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(redact(line, ips));
                w.write('\n');
            }
        }
        w.flush();
        z.closeEntry();
    }

    // ---- recent lines from the log files

    /** How much of the log files to read for the recent lines: several times what a report body can hold. */
    static final int TAIL_BYTES = 256 * 1024;

    /**
     * The newest log lines from bridge.jsonl (and the end of bridge.1.jsonl when that's short), oldest first, in
     * the dashboard's "HH:mm:ss kind=... key=value" form. Unlike BLog's in-memory ring (300 lines, this run only),
     * the files survive restarts and crashes. Empty when there's no log file.
     */
    static List<String> recentFromFiles(File filesDir, int maxBytes) {
        File cur = new File(filesDir, "bridge.jsonl"), old = new File(filesDir, "bridge.1.jsonl");
        List<String> lines = new ArrayList<>();
        long curLen = cur.isFile() ? cur.length() : 0;
        if (curLen < maxBytes) lines.addAll(tail(old, (int) (maxBytes - curLen)));
        lines.addAll(tail(cur, maxBytes));
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(humanize(l));
        return out;
    }

    /** The complete lines in the last `maxBytes` of a file (a partial first line is dropped). */
    static List<String> tail(File f, int maxBytes) {
        List<String> out = new ArrayList<>();
        if (maxBytes <= 0 || !f.isFile()) return out;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length(), start = Math.max(0, len - maxBytes);
            byte[] buf = new byte[(int) (len - start)];
            r.seek(start);
            r.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1);
            }
            for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l);
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * One bridge.jsonl line ({"t":1730.123,"kind":"x","k":v,...}, flat, as BLog writes it) in the dashboard's form:
     * "HH:mm:ss kind=x k=v", keys in file order. Anything else comes back unchanged.
     */
    static String humanize(String json) {
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return json;
        try {
            int[] i = {1};
            String time = null, kind = null;
            StringBuilder rest = new StringBuilder();
            while (true) {
                skip(s, i);
                if (s.charAt(i[0]) == '}') break;
                String key = string(s, i);
                skip(s, i);
                if (s.charAt(i[0]++) != ':') return json;
                skip(s, i);
                String val;
                char c = s.charAt(i[0]);
                if (c == '"') val = string(s, i);
                else {
                    int end = c == '[' ? s.indexOf(']', i[0]) + 1 : i[0];
                    if (c != '[') while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '}') end++;
                    if (end <= i[0]) return json;
                    val = s.substring(i[0], end).trim();
                    i[0] = end;
                }
                if (key.equals("t")) {
                    long ms = Math.round(Double.parseDouble(val) * 1000);
                    time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(ms));
                } else if (key.equals("kind")) kind = val;
                else rest.append(' ').append(key).append('=').append(val);
            }
            if (kind == null) return json;
            return (time != null ? time + " " : "") + "kind=" + kind + rest;
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static void skip(String s, int[] i) {
        while (s.charAt(i[0]) == ' ' || s.charAt(i[0]) == ',') i[0]++;
    }

    /** A JSON string starting at i (the opening quote), unescaped; i ends after the closing quote. */
    private static String string(String s, int[] i) {
        if (s.charAt(i[0]) != '"') throw new IllegalArgumentException("not a string");
        StringBuilder sb = new StringBuilder();
        for (int j = i[0] + 1; ; j++) {
            char c = s.charAt(j);
            if (c == '"') {
                i[0] = j + 1;
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++j);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(j + 1, j + 5), 16)); j += 4; break;
                default: sb.append(e);
            }
        }
    }

    /** The scanner setup kinds kept for the report (BLog pins the same ones in memory). */
    static final Set<String> PINNED_KINDS = new HashSet<>(java.util.Arrays.asList(
            "app_version", "bridge_started", "scanner_ready", "usb_open", "usb_vs_modes", "scanner_model", "scanner_roles",
            "effective_profiles", "work_mode_set"));

    /** A setup line's slot: its kind, per interface for usb_vs_modes. Null for other lines. */
    static String pinKey(String line) {
        String k = kindOf(line);
        if (k == null || !PINNED_KINDS.contains(k)) return null;
        if (!k.equals("usb_vs_modes")) return k;
        Matcher m = Pattern.compile(" iface=(\\S+)").matcher(line);
        return m.find() ? k + " " + m.group(1) : k;
    }

    /**
     * The scanner setup lines: the newest per slot found in `recent` (from the files, so they survive a restart),
     * replaced by this run's own (`memory`, from BLog) where it has them.
     */
    static List<String> mergePinned(List<String> recent, List<String> memory) {
        Map<String, String> bySlot = new LinkedHashMap<>();
        for (String l : recent) {
            String k = pinKey(l);
            if (k != null) bySlot.put(k, l);
        }
        for (String l : memory) {
            String k = pinKey(l);
            if (k != null) bySlot.put(k, l);
        }
        return new ArrayList<>(bySlot.values());
    }

    /** "crbyob-log-20261109-0612.zip" for the given time. */
    static String fileName(long ms) {
        return "crbyob-log-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date(ms)) + ".zip";
    }
}
