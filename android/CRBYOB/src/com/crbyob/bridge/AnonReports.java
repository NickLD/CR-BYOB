package com.crbyob.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Anonymous device reports: the app posts to the report Worker (which files the GitHub issue), keeps a random
 * device ID and the list of its own reports, and later looks for developer replies. Only the tag (a hash of the
 * ID) ever appears on GitHub. The ID and tag are never logged.
 */
final class AnonReports {
    private AnonReports() {}

    static final class Sent { int number; String url; }

    /** code 0 = no connection or timeout, otherwise the HTTP status; serverError = the Worker's {error}, or null. */
    static final class HttpError extends IOException {
        final int code;
        final String serverError;
        HttpError(int code, String msg) { this(code, msg, null); }
        HttpError(int code, String msg, String serverError) { super(msg); this.code = code; this.serverError = serverError; }
    }

    private static final String K_ID = "anon_id", K_BOT = "anon_bot", K_REPORTS = "anon_reports",
            K_CHECK = "anon_check_replies", K_LAST = "anon_last_check";
    /** Held around every read-modify-write of anon_reports (the background check merges under it too). */
    static final Object LOCK = new Object();
    private static final long CHECK_INTERVAL_MS = 15L * 60 * 1000;

    static boolean enabled() { return !BuildConfig.REPORT_WORKER_URL.isEmpty(); }

    static String newId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b, 32);
    }

    private static String hex(byte[] b, int chars) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.substring(0, chars);
    }

    static String deviceId(SharedPreferences p) {
        String id = p.getString(K_ID, null);
        if (id == null) {
            id = newId();
            p.edit().putString(K_ID, id).apply();
        }
        return id;
    }

    static String tagOf(String id) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)), 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is guaranteed on every Android
        }
    }

    static boolean isMine(JSONObject comment, String bot, String tag) {
        JSONObject u = comment.optJSONObject("user");
        return u != null && bot != null && bot.equals(u.optString("login"))
                && comment.optString("body").startsWith("**Reply from the reporter** (tag `" + tag + "`)");
    }

    static boolean isDeveloper(JSONObject comment, String bot, String tag) {
        String a = comment.optString("author_association");
        JSONObject u = comment.optJSONObject("user");
        boolean isBot = u != null && "Bot".equals(u.optString("type"));
        // a reporter's reply (this phone's or, with any tag, posted by the bot) is never the developer's
        if (isMine(comment, bot, tag) || (isBot && comment.optString("body").startsWith("**Reply from the reporter**")))
            return false;
        return a.equals("OWNER") || a.equals("MEMBER") || a.equals("COLLABORATOR") || isBot;
    }

    static String errorMessage(IOException e) {
        return e instanceof HttpError ? errorMessage(((HttpError) e).code, ((HttpError) e).serverError) : errorMessage(0, null);
    }

    static String errorMessage(int httpCode, String serverError) {
        if (httpCode == 0) return "Couldn't reach the report server. Check the connection and try again.";
        if (httpCode == 429) return "Too many reports from this network just now. Try again in a minute.";
        if (httpCode == 400 || httpCode == 403)          // permanent: the same request gets the same answer
            return "The report server turned this down (" + httpCode + (serverError != null ? ": " + serverError : "")
                    + "). Sending the same again won't help.";
        return "The report server answered " + httpCode + ". Try again later.";
    }

    static JSONArray reports(SharedPreferences p) {
        try {
            return new JSONArray(p.getString(K_REPORTS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void saveReports(SharedPreferences p, JSONArray a) {
        p.edit().putString(K_REPORTS, a.toString()).apply();
    }

    static boolean anyUnread(SharedPreferences p) {
        JSONArray a = reports(p);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && a.optJSONObject(i).optBoolean("unread")) return true;
        return false;
    }

    /** The issue number when exactly one report is unread, else 0. */
    static int onlyUnread(JSONArray a) {
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null || !r.optBoolean("unread")) continue;
            if (n != 0) return 0;
            n = r.optInt("n");
        }
        return n;
    }

    private static boolean anyOpen(SharedPreferences p) {
        JSONArray a = reports(p);
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && !a.optJSONObject(i).optBoolean("closed")) return true;
        return false;
    }

    static boolean applyIssue(JSONObject report, int httpCode, JSONObject issue) {
        try {
            if (httpCode == 404 || httpCode == 410 // 403 is GitHub's rate limit, not a deleted issue
                    || (issue != null && "closed".equals(issue.optString("state")))) report.put("closed", true);
            return issue != null && issue.optInt("comments") > report.optInt("seen");
        } catch (JSONException e) {
            return false;
        }
    }

    static void applyComments(JSONObject report, JSONArray comments, String bot, String tag) {
        long newest = 0;
        for (int i = 0; i < comments.length(); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c != null && isDeveloper(c, bot, tag)) newest = Math.max(newest, c.optLong("id"));
        }
        try {
            report.put("seen", comments.length()).put("newest", newest)
                    .put("unread", newest > report.optLong("dev"));
        } catch (JSONException ignored) { }
    }

    static void markRead(JSONObject report) {
        try {
            report.put("unread", false).put("dev", report.optLong("newest"));
        } catch (JSONException ignored) { }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    static Sent send(Context c, String title, String body) throws IOException {
        SharedPreferences p = prefs(c);
        try {
            JSONObject r = new JSONObject(http(BuildConfig.REPORT_WORKER_URL + "/report",
                    new JSONObject().put("id", deviceId(p)).put("title", title).put("body", body).toString(), false));
            Sent s = new Sent();
            s.number = r.getInt("number");
            s.url = r.getString("url");
            synchronized (LOCK) {
                JSONArray all = reports(p);
                all.put(new JSONObject().put("n", s.number).put("seen", 0).put("dev", 0).put("newest", 0)
                        .put("closed", false).put("unread", false));
                p.edit().putString(K_BOT, r.optString("bot")).putString(K_REPORTS, all.toString()).apply();
            }
            BLog.log("anon_report_sent");   // no issue number: a later log on a GitHub-account report would link the two
            return s;
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from the report server");
        }
    }

    static void reply(Context c, int issue, String text) throws IOException {
        try {
            http(BuildConfig.REPORT_WORKER_URL + "/reply",
                    new JSONObject().put("id", deviceId(prefs(c))).put("issue", issue).put("body", text).toString(), false);
            BLog.log("anon_reply_sent");
        } catch (JSONException e) {
            throw new HttpError(502, "bad request body");
        }
    }

    // shortcut: first 100 comments only, page if a report thread ever gets that long
    static JSONArray comments(int issue) throws IOException {
        try {
            return new JSONArray(http(issueUrl(issue) + "/comments?per_page=100", null, true));
        } catch (JSONException e) {
            throw new HttpError(502, "bad answer from GitHub");
        }
    }

    private static String issueUrl(int n) {
        String u = BuildConfig.REPO_URL;
        return "https://api.github.com/repos/" + u.substring(u.indexOf("github.com/") + 11) + "/issues/" + n;
    }

    /** POSTs json (or GETs when null); returns the body of a 2xx answer, else throws HttpError. */
    private static String http(String url, String json, boolean github) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "CR-BYOB/" + AppVersion.NAME);
            if (github) {
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new HttpError(code, "HTTP " + code, serverError(c));
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (HttpError e) {
            throw e;
        } catch (IOException e) {
            throw new HttpError(0, String.valueOf(e));
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The {error} text of the Worker's JSON error answer, or null. */
    private static String serverError(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            return in == null ? null : new JSONObject(readAll(in)).optString("error", null);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** At most every 15 minutes, if the setting is on; calls onChanged on the UI thread when a report changed. */
    static void maybeCheck(Activity a, Runnable onChanged) {
        SharedPreferences p = prefs(a);
        if (!enabled() || !p.getBoolean(K_CHECK, true) || !anyOpen(p)    // none open: don't create the ID
                || System.currentTimeMillis() - p.getLong(K_LAST, 0) < CHECK_INTERVAL_MS) return;
        p.edit().putLong(K_LAST, System.currentTimeMillis()).apply();
        new Thread(() -> {
            JSONArray all = reports(p);
            java.util.List<JSONObject> changed = new java.util.ArrayList<>();
            String bot = p.getString(K_BOT, null), tag = tagOf(deviceId(p));
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.optJSONObject(i);
                if (r == null || r.optBoolean("closed")) continue;
                int n = r.optInt("n");
                String was = r.toString();
                try {
                    int code = 200;
                    JSONObject issue = null;
                    try {
                        issue = new JSONObject(http(issueUrl(n), null, true));
                    } catch (HttpError e) {
                        if (e.code != 404 && e.code != 410) throw e; // transient (incl. 403 rate limit): retry next time
                        code = e.code;
                    }
                    if (applyIssue(r, code, issue)) applyComments(r, comments(n), bot, tag);
                } catch (IOException | JSONException e) {
                    BLog.log("anon_check_failed", "err", String.valueOf(e));
                }
                if (!r.toString().equals(was)) changed.add(r);
            }
            if (changed.isEmpty()) return;
            // merge into a fresh read: the UI may have sent or marked a report read during the network calls
            synchronized (LOCK) {
                JSONArray fresh = reports(p);
                boolean any = false;
                for (int i = 0; i < fresh.length(); i++) {
                    JSONObject f = fresh.optJSONObject(i);
                    for (JSONObject r : changed) {
                        if (f == null || f.optInt("n") != r.optInt("n")) continue;
                        try {
                            f.put("seen", r.optInt("seen")).put("newest", r.optLong("newest"))
                                    .put("closed", f.optBoolean("closed") || r.optBoolean("closed"))
                                    .put("unread", r.optLong("newest") > f.optLong("dev"));
                            any = true;
                        } catch (JSONException ignored) { }
                    }
                }
                if (!any) return;
                saveReports(p, fresh);
            }
            a.runOnUiThread(onChanged);
        }, "anon-check").start();
    }
}
