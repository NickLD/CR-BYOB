package com.crbyob.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** JSONL-ish log: logcat (tag CRBYOB), files/bridge.jsonl and an in-memory ring for the UI. */
public final class BLog {
    public static final String TAG = "CRBYOB";
    private static final long MAX_FILE = 8L << 20;
    private static final int RING = 300;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    /**
     * Scanner setup lines, kept after they leave the ring because they matter most in a report: the newest line
     * per kind (per interface for usb_vs_modes, see LogExport.PINNED_KINDS), in the order they were first logged.
     */
    private static final Map<String, String> pinned = new LinkedHashMap<>();
    private static File file;
    private static Writer writer;
    private static long written;
    private static final SimpleDateFormat HMS = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private BLog() {}

    public static synchronized void init(Context ctx) {
        if (writer != null) return;
        try {
            file = new File(ctx.getFilesDir(), "bridge.jsonl");
            if (file.length() > MAX_FILE) {
                File old = new File(ctx.getFilesDir(), "bridge.1.jsonl");
                old.delete();
                file.renameTo(old);
                file = new File(ctx.getFilesDir(), "bridge.jsonl");
            }
            written = file.length();
            writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "log file", e);
        }
    }

    /** log("kind", "key1", value1, "key2", value2, ...) */
    public static void log(String kind, Object... kv) {
        long now = System.currentTimeMillis();
        StringBuilder js = new StringBuilder(128);
        StringBuilder human = new StringBuilder(128);
        js.append("{\"t\":").append(now / 1000).append('.').append(String.format(Locale.US, "%03d", now % 1000))
          .append(",\"kind\":\"").append(kind).append('"');
        human.append("kind=").append(kind);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            // logs end up in bug reports: never write secrets (the dashboard still shows the Wi-Fi Direct password)
            if (v != null && ("passphrase".equals(k) || "password".equals(k))) v = "(hidden)";
            js.append(",\"").append(k).append("\":");
            appendJson(js, v);
            human.append(' ').append(k).append('=').append(v);
        }
        js.append('}');
        String line = js.toString();
        Log.i(TAG, line);
        synchronized (BLog.class) {
            String ui = HMS.format(new Date(now)) + " " + human;
            ring.addLast(ui);
            while (ring.size() > RING) ring.removeFirst();
            if (LogExport.PINNED_KINDS.contains(kind))
                pinned.put("usb_vs_modes".equals(kind) && kv.length > 1 ? kind + " " + kv[1] : kind, ui);
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                    written += line.length() + 1;
                    if (written > MAX_FILE) rotate();     // a phone left as a bridge runs for days
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** bridge.jsonl -> bridge.1.jsonl (replacing the older one), then a fresh bridge.jsonl. Holds BLog.class. */
    private static void rotate() {
        try {
            writer.close();
        } catch (Exception ignored) {
        }
        writer = null;
        try {
            File old = new File(file.getParentFile(), "bridge.1.jsonl");
            old.delete();
            file.renameTo(old);
            file = new File(file.getParentFile(), "bridge.jsonl");
            writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
            written = 0;
        } catch (Exception e) {
            Log.e(TAG, "log rotate", e);
        }
    }

    public static synchronized List<String> recent(int n) {
        List<String> out = new ArrayList<>(ring);
        return out.size() > n ? out.subList(out.size() - n, out.size()) : out;
    }

    /** The scanner setup lines (see LogExport.PINNED_KINDS), as shown in the raw log. */
    public static synchronized List<String> pinned() {
        return new ArrayList<>(pinned.values());
    }

    private static void appendJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof int[]) {
            int[] a = (int[]) v;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else {
            sb.append(jsonString(String.valueOf(v)));
        }
    }

    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
