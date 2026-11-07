package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * GitHub build only: downloads the release APK inside the app, checks it, and hands it to Android's
 * PackageInstaller, so updating never goes through the browser (Chrome holds .apk downloads behind a
 * "harmful file" warning, then wants its own install-from-unknown-sources permission).
 *
 * Checks before installing: the SHA-256 GitHub lists for the asset (when the API gives one), and that the APK
 * is this app (same package name and signing certificate) at a higher version. Android would refuse a wrong
 * key anyway; checking first gives a clear message instead of a bare "App not installed".
 *
 * Android asks once to allow installs from CR-BYOB ("Install unknown apps"), as part of its own confirm screen.
 * On Android 12+ the session asks for no user action: Android allows that only for an app updating itself
 * that already installed the current copy, so the first in-app update shows the confirm screen and later ones
 * may not. Installing replaces the running app, which stops the bridge; {@link Updated} posts a
 * "tap to open" notification from the new version.
 */
final class SelfInstall {
    private SelfInstall() {}

    private static final String K_PENDING = "update_installing_version";
    private static final String NOTIF_CHANNEL = "updates";
    private static final int NOTIF_ID = 2;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    // the update being installed and the screen that started it, for the "not installed" dialog
    private static java.lang.ref.WeakReference<Activity> owner = new java.lang.ref.WeakReference<>(null);
    private static Updates.Release installing;
    private static int lastFinishedSession = -1;    // Android can report the same outcome twice

    /** Thrown with a message meant for the user. */
    static final class Failure extends Exception {
        Failure(String msg) { super(msg); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    /** True while a scan is streaming to the PC: installing would cut it off. */
    static boolean streaming() {
        Bridge b = BridgeService.current();
        if (b == null) return false;
        for (RtspServer s : b.rtsp) if (s.stat.active > 0) return true;
        return false;
    }

    /** Asks first if a scan is streaming, then downloads and installs. */
    static void start(Activity a, Updates.Release r) {
        if (!streaming()) {
            download(a, r);
            return;
        }
        Widgets.showStyled(new AlertDialog.Builder(a)
                .setTitle("A scan is running")
                .setMessage("Installing the update closes CR-BYOB, which stops the stream to Creality Scan. "
                        + "Update now, or finish the scan first?")
                .setPositiveButton("Update now", (x, w) -> download(a, r))
                .setNegativeButton("Later", null)
                .create());
    }

    private static void download(Activity a, Updates.Release r) {
        if (busy) {
            Toast.makeText(a, "The update is already downloading", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        owner = new java.lang.ref.WeakReference<>(a);
        installing = r;
        Context app = a.getApplicationContext();
        boolean[] cancel = {false};

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Widgets.dp(a, 22);
        col.setPadding(pad, Widgets.dp(a, 8), pad, Widgets.dp(a, 8));
        TextView label = new TextView(a);
        label.setTextColor(Widgets.SUB);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setText("Starting download…");
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
        col.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Widgets.dp(a, 24)));
        col.addView(label);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Downloading CR-BYOB " + r.version)
                .setView(col)
                .setCancelable(false)
                .setNegativeButton("Cancel", (x, w) -> cancel[0] = true)
                .create();
        Widgets.showStyled(d);

        new Thread(() -> {
            File apk = null;
            String err = null;
            try {
                apk = fetch(app, r, cancel, (done, total) -> UI.post(() -> {
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setProgress((int) (done * 1000 / total));
                        label.setText(mb(done) + " of " + mb(total));
                    } else {
                        label.setText(mb(done));
                    }
                }));
                if (apk != null) {
                    verify(app, apk, r);
                    UI.post(() -> label.setText("Installing…"));
                    install(app, apk, r);
                }
            } catch (Failure e) {
                err = e.getMessage();
            } catch (Exception e) {
                err = "The download failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                busy = false;
                if (apk != null) apk.delete();   // the session holds its own copy once written
            }
            if (err != null) BLog.log("update_install_failed", "version", r.version, "err", err);
            String er = err;
            UI.post(() -> {
                if (d.isShowing() && !a.isDestroyed()) d.dismiss();
                if (er != null) notInstalled(a, r, er);
            });
        }, "update-download").start();
    }

    /**
     * The update didn't install (download or check failed, the user said no, or Android refused): says so in the
     * app's dialog style and offers the release page, to download and install it by hand, or another try.
     */
    private static void notInstalled(Activity a, Updates.Release r, String why) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        Widgets.showStyled(new AlertDialog.Builder(a)
                .setTitle("Update not installed")
                .setMessage(why + "\n\nYou can download CR-BYOB " + r.version + " from the release page and install it "
                        + "yourself, or tap Try again.")
                .setPositiveButton("Release page", (x, w) -> Updates.open(a, r.htmlUrl))
                .setNeutralButton("Try again", (x, w) -> start(a, r))
                .setNegativeButton("Close", null)
                .create());
    }

    /** Words for an install that Android ended without installing. */
    static String whyNotInstalled(int status, String msg) {
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            if (msg != null && msg.toLowerCase(Locale.US).contains("permission"))
                return "That's fine, nothing was changed. To update itself, CR-BYOB needs Android's permission to "
                        + "install apps (\"Allow from this source\"), and it was left off.";
            return "That's fine, nothing was changed.";
        }
        if (status == PackageInstaller.STATUS_FAILURE_BLOCKED)
            return "Android or Google Play Protect blocked the install. Nothing was changed.";
        if (status == PackageInstaller.STATUS_FAILURE_STORAGE)
            return "There isn't enough free storage for the update. Nothing was changed.";
        return "Android didn't install it" + (msg != null ? " (" + msg + ")" : "") + ". Nothing was changed.";
    }

    interface Progress { void at(long done, long total); }

    /** Downloads the APK to the cache dir; null if canceled. */
    private static File fetch(Context c, Updates.Release r, boolean[] cancel, Progress p) throws IOException, Failure {
        File dir = new File(c.getCacheDir(), "update");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File out = new File(dir, "CR-BYOB-" + r.version + ".apk");

        // browser_download_url redirects to GitHub's asset host (https -> https, which HttpURLConnection follows)
        HttpURLConnection conn = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "CR-BYOB/" + AppVersion.NAME);
        BLog.log("update_download", "version", r.version, "url", r.apkUrl);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("GitHub answered HTTP " + status);
            long total = conn.getContentLengthLong();
            if (total <= 0) total = r.apkSize;
            long done = 0, lastReport = 0;
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancel[0]) {
                        out.delete();
                        BLog.log("update_download_canceled", "version", r.version);
                        return null;
                    }
                    os.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 100) {
                        lastReport = now;
                        p.at(done, total);
                    }
                }
            }
            p.at(done, total);
            if (r.apkSize > 0 && done != r.apkSize)
                throw new Failure("The download was cut short (" + mb(done) + " of " + mb(r.apkSize) + ").");
        } finally {
            conn.disconnect();
        }
        String want = r.apkSha256;
        if (want != null && !want.equalsIgnoreCase(hex(sha.digest()))) {
            out.delete();
            throw new Failure("The download doesn't match the checksum GitHub lists for it.");
        }
        return out;
    }

    /** Same package, same signing key, newer version. */
    @SuppressWarnings("deprecation")
    private static void verify(Context c, File apk, Updates.Release r) throws Failure {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (got == null) throw new Failure("The downloaded file isn't a valid app package.");
        if (!c.getPackageName().equals(got.packageName))
            throw new Failure("The downloaded app is " + got.packageName + ", not CR-BYOB.");
        long code = Build.VERSION.SDK_INT >= 28 ? got.getLongVersionCode() : got.versionCode;
        if (code <= AppVersion.CODE)
            throw new Failure("The download (" + got.versionName + ") isn't newer than this copy (" + AppVersion.NAME + ").");
        PackageInfo mine;
        try {
            mine = pm.getPackageInfo(c.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Failure("Couldn't read this app's own signature.");
        }
        Set<String> have = signers(mine), dl = signers(got);
        // some Android versions don't read an archive's signature; Android still enforces the key on install
        if (have != null && dl != null && !have.equals(dl))
            throw new Failure("The download is signed with a different key than this copy of CR-BYOB"
                    + (BuildConfig.DEBUG ? " (this is a debug build)" : "")
                    + ", so Android can't install it over this one. Uninstall this copy first to switch.");
    }

    /** SHA-256 of each current signing certificate, or null if this Android version didn't report them. */
    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) sigs = p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : null;
        else sigs = p.signatures;
        if (sigs == null || sigs.length == 0) return null;
        Set<String> out = new HashSet<>();
        for (Signature s : sigs) out.add(sha256(s.toByteArray()));
        return out;
    }

    private static void install(Context c, File apk, Updates.Release r) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream os = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
                s.fsync(os);
            }
            prefs(c).edit().putString(K_PENDING, r.version).commit();   // read by Updated in the new version
            // MUTABLE: the installer fills in the status extras. The intent is explicit, as Android 14 requires.
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent status = PendingIntent.getBroadcast(c, id, new Intent(c, Status.class), piFlags);
            BLog.log("update_install_commit", "version", r.version, "session", id);
            s.commit(status.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    /** Result of the install session: shows Android's confirm screen when it needs one, reports failures. */
    public static final class Status extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            BLog.log("update_install_status", "status", st, "msg", msg);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation")
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        BLog.log("update_confirm_failed", "err", String.valueOf(e));
                    }
                }
                return;
            }
            if (st == PackageInstaller.STATUS_SUCCESS) return;    // the new version takes over (see Updated)
            int session = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (session != -1 && session == lastFinishedSession) return;
            lastFinishedSession = session;
            prefs(c).edit().remove(K_PENDING).apply();
            String why = whyNotInstalled(st, msg);
            Activity a = owner.get();
            Updates.Release r = installing;
            if (a != null && r != null && !a.isFinishing() && !a.isDestroyed()) notInstalled(a, r, why);
            else Toast.makeText(c, "Update not installed. " + why, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * MY_PACKAGE_REPLACED, received by the new version: installing stopped the app, so offer a tap to reopen it.
     * Only after an in-app update (adb or browser installs set no marker).
     */
    public static final class Updated extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
            SharedPreferences p = prefs(c);
            String v = p.getString(K_PENDING, null);
            if (v == null) return;
            p.edit().remove(K_PENDING).apply();
            BLog.log("update_installed", "version", AppVersion.NAME);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null)
                nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, NOTIF_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_bridge)
                    .setContentTitle("CR-BYOB updated to " + AppVersion.NAME)
                    .setContentText("Tap to open")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            try {
                nm.notify(NOTIF_ID, n);
            } catch (SecurityException e) {
                // notifications not allowed: the user reopens it themselves
            }
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static String sha256(byte[] b) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "sha256:<64 hex>" (the GitHub API's asset digest) -> the hex, lowercased; anything else -> null. */
    static String parseDigest(String d) {
        if (d == null || !d.startsWith("sha256:")) return null;
        String h = d.substring(7).toLowerCase(Locale.US);
        return h.matches("[0-9a-f]{64}") ? h : null;
    }
}
