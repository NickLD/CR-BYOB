package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GitHub build only: checks GitHub Releases for a newer version and offers to install it; the download and
 * install happen inside the app ({@link SelfInstall}), with the release page as the fallback.
 * Automatic check at most once a day on app start; manual check from the "What's new" dialog.
 * Pre-releases are offered only to people already running one. The Play build has a no-op version of this
 * class (flavors/play): Play apps must update through Play.
 */
final class Updates {
    private Updates() {}

    static final boolean SUPPORTED = true;

    private static final String K_LAST = "update_last_check", K_SKIP = "update_skipped_code",
            K_REPO = "update_repo_override";
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;

    static final class Release {
        String version, date, body, htmlUrl, apkUrl;
        String apkSha256;   // from the asset's "digest" (GitHub lists one for assets uploaded since mid-2025), or null
        long apkSize;       // bytes, 0 if unknown
        int code;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    /**
     * "owner/name"; debug builds can point at another repo for testing (--es update_repo owner/name), or at a
     * fake GitHub API with a full URL (--es update_repo http://127.0.0.1:8765 serves /releases; debug builds
     * allow cleartext http for this).
     */
    static String repo(Context c) {
        String o = BuildConfig.DEBUG ? prefs(c).getString(K_REPO, null) : null;
        return o != null && !o.isEmpty() ? o : BuildConfig.GITHUB_REPO;
    }

    static void setRepoOverride(Context c, String repo) {
        if (!BuildConfig.DEBUG) return;
        SharedPreferences.Editor e = prefs(c).edit();
        if (repo == null || repo.isEmpty()) e.remove(K_REPO); else e.putString(K_REPO, repo);
        e.remove(K_LAST).apply();
    }

    static void maybeAutoCheck(Activity a) {
        if (System.currentTimeMillis() - prefs(a).getLong(K_LAST, 0) < AUTO_INTERVAL_MS) return;
        check(a, false);
    }

    static void checkNow(Activity a) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        check(a, true);
    }

    private static void check(Activity a, boolean manual) {
        Context app = a.getApplicationContext();
        String repo = repo(app);
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<Release> releases = null;
            String err = null;
            try {
                releases = fetch(repo);
                prefs(app).edit().putLong(K_LAST, System.currentTimeMillis()).apply();
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                BLog.log("update_check_failed", "repo", repo, "err", String.valueOf(e));
            }
            List<Release> r = releases;
            String er = err;
            ui.post(() -> onResult(a, manual, r, er));
        }, "update-check").start();
    }

    /** Non-draft releases with a vX.Y.Z tag, newest first; pre-releases only if this build is one. */
    static List<Release> fetch(String repo) throws IOException, org.json.JSONException {
        String base = repo.startsWith("http://") || repo.startsWith("https://") ? repo.replaceAll("/+$", "")
                : "https://api.github.com/repos/" + repo;
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/releases?per_page=30").openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "CR-BYOB/" + AppVersion.NAME);
        String json;
        try {
            int status = c.getResponseCode();
            if (status == 404) throw new IOException("no releases found for " + repo);
            if (status == 403 || status == 429) throw new IOException("GitHub rate limit, try again later");
            if (status != 200) throw new IOException("GitHub answered HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }

        boolean includePre = AppVersion.isPrerelease();
        List<Release> out = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.optBoolean("draft") || (o.optBoolean("prerelease") && !includePre)) continue;
            Release r = new Release();
            String tag = o.optString("tag_name", "");
            r.version = tag.startsWith("v") ? tag.substring(1) : tag;
            r.code = AppVersion.codeOf(r.version);
            if (r.code <= 0) continue;
            String published = o.optString("published_at", "");
            r.date = published.length() >= 10 ? published.substring(0, 10) : null;
            r.body = o.optString("body", "");
            r.htmlUrl = o.optString("html_url", "https://github.com/" + repo + "/releases");
            JSONArray assets = o.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject as = assets.getJSONObject(j);
                String name = as.optString("name", "");
                if (!name.endsWith(".apk")) continue;
                // prefer the github-flavor APK if a release ever carries more than one
                if (r.apkUrl == null || name.contains("github")) {
                    r.apkUrl = as.optString("browser_download_url", null);
                    r.apkSha256 = SelfInstall.parseDigest(as.optString("digest", null));
                    r.apkSize = Math.max(0, as.optLong("size", 0));
                }
            }
            out.add(r);
        }
        Collections.sort(out, (x, y) -> Integer.compare(y.code, x.code));
        return out;
    }

    private static void onResult(Activity a, boolean manual, List<Release> releases, String err) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (releases == null) {
            if (manual) Toast.makeText(a, "Couldn't check for updates: " + err, Toast.LENGTH_LONG).show();
            return;
        }
        Release newest = releases.isEmpty() ? null : releases.get(0);
        if (newest == null || newest.code <= AppVersion.CODE) {
            if (manual) Toast.makeText(a, "You have the latest version (" + AppVersion.NAME + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences p = prefs(a);
        if (!manual && p.getInt(K_SKIP, 0) == newest.code) return;
        BLog.log("update_available", "current", AppVersion.NAME, "latest", newest.version);

        // notes of every release between this build and the newest, so skipped versions are covered too
        List<Changelog.Entry> notes = new ArrayList<>();
        for (Release r : releases)
            if (r.code > AppVersion.CODE && !r.body.trim().isEmpty()) notes.add(new Changelog.Entry(r.version, r.date, r.body));

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setView(WhatsNew.notesView(a, "CR-BYOB " + newest.version + " is available. You have "
                        + AppVersion.NAME + ".", notes))
                .setPositiveButton(newest.apkUrl != null ? "Update" : "Download", (x, w) -> {
                    if (newest.apkUrl != null) SelfInstall.start(a, newest); else open(a, newest.htmlUrl);
                })
                .setNegativeButton("Later", null);
        if (manual) b.setNeutralButton("Release page", (x, w) -> open(a, newest.htmlUrl));
        else b.setNeutralButton("Skip version", (x, w) -> p.edit().putInt(K_SKIP, newest.code).apply());
        AlertDialog d = b.create();
        Widgets.showStyled(d);
    }

    /** Opens the release page in the browser (no APK on the release, or the in-app install failed). */
    static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, "No browser to open " + url, Toast.LENGTH_LONG).show();
        }
    }
}
