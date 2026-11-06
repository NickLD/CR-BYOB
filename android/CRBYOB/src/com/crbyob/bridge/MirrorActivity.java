package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static com.crbyob.bridge.Widgets.*;

/**
 * Mirror viewer: a thin view over {@link MirrorSession}, which owns the signaling and the WebView and
 * lives on when this Activity closes (Back keeps the session, ✕ / Close ends it). Shows Creality Scan's
 * RGB / IR / 3D views, the bridge status, the PC's scan state and PC-driven scan buttons.
 * Portrait: one column. Landscape: model + RGB/IR column (in the page) + a native right column that can
 * collapse to a thin strip (status light, scan state, main scan button); the choice is remembered.
 */
public final class MirrorActivity extends Activity implements MirrorSession.Ui {
    static final int SIGNAL_PORT = MirrorSession.SIGNAL_PORT, BROADCAST_PORT = 57301;
    static final String BROADCAST_MSG = "OrbbecRTCServer";

    private final Handler h = new Handler(Looper.getMainLooper());
    private MirrorSession ses;
    private SharedPreferences prefs;
    private boolean landscape, collapsed;

    // top row
    private LinearLayout top;
    private TextView statusText, subText;
    // bridge panel (fixed grid)
    private LinearLayout panelCard;
    private OrbView pOrb;
    private TextView pHero, pTotals, pLink, pScanner, pWarn, pDecim;
    private final TextView[] chipVal = new TextView[3];
    private final LinearLayout[] chipBox = new LinearLayout[3];
    // native scan stats (landscape right column, under the buttons)
    private LinearLayout statsBox, statsGrid;
    private LinearLayout statsLink;
    private TextView statsTitle, statsDev;
    private org.json.JSONArray lastStats = new org.json.JSONArray();
    private String lastDev = "";
    // scan buttons: Start/Resume, Pause, Finish, Cancel, New scan
    private LinearLayout bar;
    private LinearLayout grid;                   // landscape: visible scan buttons, two per row
    private TextView btnHint;
    private final Button[] scanBtn = new Button[5];
    private static final String[] SCAN_RPC = {"scanStart", "pauseScan", "stopScan", "clearScan", "scanCreate"};
    private static final int[] SCAN_TYPE = {1, 2, 3, 4, 5};
    private JSONObject btnState;
    private boolean btnResume;
    private String scanLabel = "";
    // collapsed strip (landscape)
    private LinearLayout strip;
    private OrbView sOrb;
    private TextView sState;
    private Button sMain;
    private int sMainIdx = -1;
    private HandleView handle;

    private static final int SIDE_DP = 250, STRIP_DP = 92, TAB_CLEAR_DP = 14;

    /** Drawer edge tab: rounded tab with grip lines and a chevron; the whole 48 dp wide view is the touch target. */
    static final class HandleView extends View {
        private final android.graphics.Paint tab = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean collapsed;

        HandleView(android.content.Context c) {
            super(c);
            tab.setColor(0xFF2A313C);
            ink.setColor(0xFFC9D1D9);
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            setClickable(true);
        }

        void setCollapsed(boolean c) {
            collapsed = c;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), tw = 22 * d, cx = w / 2;
            cv.drawRoundRect(cx - tw / 2, 0, cx + tw / 2, h, 11 * d, 11 * d, tab);
            ink.setStrokeWidth(2 * d);
            for (int i = -1; i <= 1; i++)             // grip
                cv.drawLine(cx - 4 * d, h * 0.22f + i * 5 * d, cx + 4 * d, h * 0.22f + i * 5 * d, ink);
            for (int i = -1; i <= 1; i++)
                cv.drawLine(cx - 4 * d, h * 0.78f + i * 5 * d, cx + 4 * d, h * 0.78f + i * 5 * d, ink);
            ink.setStrokeWidth(3 * d);                // chevron: › hides the panel, ‹ shows it
            float s = collapsed ? -1 : 1, ch = 8 * d, cy = h / 2;
            cv.drawLine(cx - s * 3 * d, cy - ch, cx + s * 4 * d, cy, ink);
            cv.drawLine(cx + s * 4 * d, cy, cx - s * 3 * d, cy + ch, ink);
        }
    }

    private final Runnable panelTick = new Runnable() {
        @Override
        public void run() {
            refreshPanel();
            sampleLink();
            h.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(TEXT);
        b.setBackground(rounded(LINE, dp(10)));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setSingleLine(true);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BLog.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.applyLockScreenMode(this);           // over the lock screen only with the dev option on
        getWindow().setStatusBarColor(BG);          // Android 14 and older; 15+ draws edge-to-edge (fitSystemBars)
        getWindow().setNavigationBarColor(BG);
        Widgets.fitSystemBars(this);
        prefs =getSharedPreferences("bridge", MODE_PRIVATE);
        collapsed = prefs.getBoolean("mirror_collapsed", false);
        ses = MirrorSession.get(this);

        // ---- top row: state + IP · Mbit/s, ✕ close, ⋮ menu
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(4), dp(4), dp(4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        statusText = text(15, TEXT, true);
        col.addView(statusText);
        subText = text(12, SUB, false);
        col.addView(subText);
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = flat("✕", this::closeSession);
        top.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button more = flat("⋮", null);
        more.setOnClickListener(this::showMenu);
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));

        panelCard = buildPanel();

        btnHint = text(12, SUB, false);
        btnHint.setSingleLine(false);
        btnHint.setMaxLines(2);
        btnHint.setText("Scan buttons appear when Creality Scan reports its state");
        bar = new LinearLayout(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] labels = {"Start", "Pause", "Finish", "Cancel", "New scan"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Button bt = button(labels[i], () -> onScanButton(idx));
            bt.setVisibility(View.GONE);
            scanBtn[i] = bt;
        }

        // ---- collapsed strip (landscape)
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);   // clear of the edge tab in the middle
        strip.setPadding(dp(TAB_CLEAR_DP), dp(8), dp(4), dp(8));
        sOrb = new OrbView(this);
        sState = text(12, TEXT, true);
        sState.setGravity(Gravity.CENTER);
        sState.setSingleLine(false);
        sState.setMaxLines(2);
        sMain = button("", () -> { if (sMainIdx >= 0) onScanButton(sMainIdx); });
        sMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        handle = new HandleView(this);
        handle.setOnClickListener(v -> toggleCollapsed());
        handle.setContentDescription("Show or hide the side panel");

        statsBox = new LinearLayout(this);
        statsBox.setOrientation(LinearLayout.VERTICAL);
        statsBox.setPadding(dp(10), dp(4), dp(10), dp(4));
        statsTitle = text(16, TEXT, true);
        statsBox.addView(statsTitle);
        statsGrid = new LinearLayout(this);
        statsGrid.setOrientation(LinearLayout.VERTICAL);
        statsBox.addView(statsGrid);
        statsLink = new LinearLayout(this);        // the bridge's link, one full-width row per item
        statsLink.setOrientation(LinearLayout.VERTICAL);
        statsLink.setPadding(0, dp(6), 0, 0);
        statsBox.addView(statsLink);
        statsDev = text(12, SUB, false);
        statsDev.setPadding(0, dp(4), 0, 0);
        statsBox.addView(statsDev);

        applyLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        ses.setUi(this);
        h.post(panelTick);
        if (!ses.isActive() && !ses.isConnecting()) start();
    }

    private Button flat(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTextColor(SUB);
        b.setBackgroundColor(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        if (r != null) b.setOnClickListener(v -> r.run());
        return b;
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Manual PC address…");
        m.getMenu().add(0, 2, 1, "Reconnect");
        m.getMenu().add(0, 4, 2, "Session stats…");
        m.getMenu().add(0, 3, 3, "Close session");
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 4) {
                new AlertDialog.Builder(this).setTitle("Session stats").setMessage(SessionStats.cur.summary())
                        .setPositiveButton("OK", null).show();
                return true;
            }
            if (it.getItemId() == 1) askHost();
            else if (it.getItemId() == 2) {
                String hp = ses.hostPort();
                if (hp == null) hp = prefs.getString("mirror_host", null);
                if (hp != null) ses.connect(hp, "reconnect");
                else askHost();
            } else closeSession();
            return true;
        });
        m.show();
    }

    /**
     * The page's "Mirror … fps" pill: frame rate and 3D view resolution asked of Creality Scan, in the app's own
     * dialog style. The frame rate applies at once; a resolution change reconnects the mirror (sent only in init).
     */
    @Override
    public void onMirrorFpsMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        android.widget.Switch adapt = new android.widget.Switch(this);
        adapt.setText("Adjust automatically");
        adapt.setTextColor(TEXT);
        adapt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        adapt.setChecked(ses.adaptSetting());
        adapt.setPadding(0, dp(8), 0, dp(2));
        box.addView(adapt);
        TextView now = text(12, SUB, false);
        now.setSingleLine(false);
        now.setMaxLines(4);
        box.addView(now);
        int[] fpsOpts = MirrorAuto.FPS, resOpts = MirrorAuto.RES;
        String[] fpsLabels = new String[fpsOpts.length];
        for (int i = 0; i < fpsOpts.length; i++) fpsLabels[i] = fpsOpts[i] + " fps";
        String[] resLabels = new String[resOpts.length];
        for (int i = 0; i < resOpts.length; i++) {
            int[] m = ses.modelSize(resOpts[i]);
            resLabels[i] = (resOpts[i] == 100 ? "Full" : resOpts[i] + "%") + " · " + m[0] + "×" + m[1];
        }
        TextView[] titles = new TextView[2];
        RadioGroup fpsGroup = qualityGroup(box, "Frame rate", fpsLabels, indexOf(fpsOpts, ses.fpsSetting()), null, titles, 0);
        RadioGroup resGroup = qualityGroup(box, "3D view resolution", resLabels, indexOf(resOpts, ses.resSetting()),
                "Resolution changes reconnect the mirror for a second or two.", titles, 1);
        Runnable relabel = () -> {
            boolean on = adapt.isChecked();
            titles[0].setText(on ? "Frame rate: up to" : "Frame rate");
            titles[1].setText(on ? "3D view resolution: up to" : "3D view resolution");
            now.setText(on ? "Lowers the resolution, then the frame rate, when the mirror stutters, and raises them "
                    + "again up to your choices once it's smooth. Now " + ses.visibleFps() + " fps · " + ses.visibleRes() + "%."
                    : "Uses exactly your choices.");
        };
        adapt.setOnCheckedChangeListener((v, on) -> relabel.run());
        relabel.run();
        TextView note = text(13, SUB, false);
        note.setSingleLine(false);
        note.setMaxLines(3);
        note.setText("Only the mirror changes. The scanner streams to the PC are not affected.");
        note.setPadding(0, dp(12), 0, dp(4));
        box.addView(note);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Mirror quality").setView(sv)
                .setPositiveButton("Apply", (x, w) -> ses.setMirrorQuality(adapt.isChecked(),
                        fpsOpts[checkedIndex(fpsGroup)], resOpts[checkedIndex(resGroup)]))
                .setNegativeButton("Cancel", null).create();
        Widgets.showStyled(d);
    }

    /** A titled group of radio buttons in the dashboard's style (white text, blue buttons), with a hint line. */
    private RadioGroup qualityGroup(LinearLayout box, String title, String[] labels, int checked, String hint,
                                    TextView[] titles, int slot) {
        TextView t = text(14, SUB, true);
        t.setText(title);
        titles[slot] = t;
        t.setPadding(0, dp(12), 0, dp(2));
        box.addView(t);
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(i);
            rb.setText(labels[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            rb.setPadding(dp(4), dp(6), 0, dp(6));
            g.addView(rb);
            if (i == checked) rb.setChecked(true);
        }
        box.addView(g);
        if (hint != null) {
            TextView hn = text(12, SUB, false);
            hn.setSingleLine(false);
            hn.setMaxLines(3);
            hn.setText(hint);
            box.addView(hn);
        }
        return g;
    }

    private static int checkedIndex(RadioGroup g) {
        View v = g.findViewById(g.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof Integer ? (Integer) v.getTag() : 0;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private void closeSession() {
        ses.close();
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BLog.log("mirror_viewer", "event", "start");
        ses.setVisible(true);          // normal stream quality while the viewer is on screen
    }

    @Override
    protected void onStop() {
        super.onStop();
        BLog.log("mirror_viewer", "event", "stop", "finishing", isFinishing());
        ses.setVisible(false);         // low-rate streams while hidden: the Wi-Fi also carries the scanner data
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(panelTick);
        ses.setUi(null);
        ses.detachWeb();               // the session (and its WebView) live on
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land != landscape) applyLayout(land);      // same views re-parented: the WebRTC session keeps running
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String hh = intent.getStringExtra("host");
        if (hh != null && !hh.isEmpty() && !ses.isActive() && !ses.isConnecting()) ses.connect(hh, "dashboard");
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        prefs.edit().putBoolean("mirror_collapsed", collapsed).apply();
        applyLayout(landscape);
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    /** Arrange the same views for portrait / landscape / landscape-collapsed. */
    private void applyLayout(boolean land) {
        landscape = land;
        for (View v : new View[]{top, panelCard, btnHint, bar, grid, strip, sOrb, sState, sMain, handle, statsBox}) detach(v);
        for (Button b : scanBtn) detach(b);
        ses.detachWeb();
        int match = ViewGroup.LayoutParams.MATCH_PARENT, wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(match, wrap);
        pl.leftMargin = dp(8);
        pl.rightMargin = dp(8);
        pl.bottomMargin = dp(6);
        boolean compactPage = land && collapsed;
        if (!land) {
            subText.setSingleLine(true);
            root.setOrientation(LinearLayout.VERTICAL);
            subText.setVisibility(View.VISIBLE);
            root.addView(top);
            root.addView(panelCard, pl);
            ses.attach(root, new LinearLayout.LayoutParams(match, 0, 1));
            btnHint.setPadding(dp(12), dp(4), dp(12), 0);
            root.addView(btnHint);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setPadding(dp(8), dp(6), dp(8), dp(10));
            for (Button b : scanBtn) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, wrap, 1);
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                bar.addView(b, lp);
            }
            root.addView(bar);
        } else if (!collapsed) {
            subText.setSingleLine(false);            // two short lines rather than an ellipsized one in the narrow column
            subText.setMaxLines(2);
            root.setOrientation(LinearLayout.HORIZONTAL);
            ses.attach(root, new LinearLayout.LayoutParams(0, match, 1));
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(dp(TAB_CLEAR_DP), 0, 0, 0);        // the drawer tab never covers content
            side.addView(top);
            side.addView(panelCard, pl);
            side.addView(statsBox);                  // scan state + stats under the panel
            side.addView(new View(this), new LinearLayout.LayoutParams(match, 0, 1));   // flexible gap
            btnHint.setPadding(dp(10), 0, dp(10), 0);
            side.addView(btnHint);
            grid.setPadding(dp(5), 0, dp(5), dp(6));   // rows are built from the visible buttons in refreshButtons()
            side.addView(grid);                      // scan buttons anchored at the bottom
            // safety net only: at 2340/2404x1080 the column fits without scrolling
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setFillViewport(true);                // lets the gap push the buttons to the bottom
            sv.addView(side, new android.widget.FrameLayout.LayoutParams(match, match));
            root.addView(sv, new LinearLayout.LayoutParams(dp(SIDE_DP), match));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            ses.attach(root, new LinearLayout.LayoutParams(0, match, 1));
            strip.addView(sOrb, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(match, wrap);
            sl.topMargin = dp(8);
            strip.addView(sState, sl);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(match, wrap);
            ml.topMargin = dp(10);
            strip.addView(sMain, ml);
            root.addView(strip, new LinearLayout.LayoutParams(dp(STRIP_DP), match));
        }
        View content = root;
        if (land) {
            // edge tab over the column boundary: 48 dp wide touch target, visible grip + chevron
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.addView(root, new android.widget.FrameLayout.LayoutParams(match, match));
            android.widget.FrameLayout.LayoutParams hl = new android.widget.FrameLayout.LayoutParams(dp(48), dp(150),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            hl.rightMargin = dp(collapsed ? STRIP_DP : SIDE_DP) - dp(48 - TAB_CLEAR_DP);
            handle.setCollapsed(collapsed);
            frame.addView(handle, hl);
            content = frame;
        }
        // page layout: compact = model wide, RGB/IR as small thumbnails on it (still one tap to swap)
        h.post(() -> {
            WebViewEval.run(ses, "setCompact(" + compactPage + ")");
        });
        setContentView(content);
        refreshButtons();
        refreshStats();
        refreshPanel();
    }

    /** Tiny helper so applyLayout can poke the page without exposing MirrorSession internals. */
    static final class WebViewEval {
        static void run(MirrorSession s, String code) {
            try { s.web().evaluateJavascript("window.setCompact && " + code, null); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ MirrorSession.Ui
    @Override
    public void onStatus(String title, String sub) {
        if (ses.decoding) return;
        statusText.setText(title);
        if (sub != null) subText.setText(sub);
    }

    @Override
    public void onStats(String title, String line, boolean decoding) {
        String extra = "";
        if (title != null && title.startsWith("Connected · ")) {     // "Connected · 3 streams" -> scan state + sub
            extra = title.substring("Connected · ".length()) + " · ";
            title = scanLabel.isEmpty() ? "Connected" : scanLabel;
        }
        if (title != null) statusText.setText(title);
        if (line != null) {
            // landscape: just "IP · Mbit/s" (the codec detail is long); portrait: full detail
            String l = line;
            if (landscape) {                          // "IP" / "3 streams · 35.9 Mbit/s": two short lines, nothing cut
                int a = l.indexOf(" · "), z = l.lastIndexOf(" · ");
                String ip = a > 0 ? l.substring(0, a) : l, rate = z > 0 ? l.substring(z + 3) : "";
                String ex = extra.endsWith(" · ") ? extra.substring(0, extra.length() - 3) : extra;
                subText.setText(ip + "\n" + (ex.isEmpty() ? rate : ex + (rate.isEmpty() ? "" : " · " + rate)));
            } else subText.setText(extra + l);
        }
    }

    @Override
    public void onScanStats(org.json.JSONArray stats, String dev) {
        lastStats = stats != null ? stats : new org.json.JSONArray();
        lastDev = dev == null ? "" : dev;
        refreshStats();
    }

    /** Native stats block (landscape): balanced 2-column grid of label / value, then DeviceName_FW. */
    private java.util.List<String[]> linkCells = new java.util.ArrayList<>();
    private String linkKey = "";

    /**
     * Once a second: the link the bridge streams on (its dashboard snapshot), or this phone's own Wi-Fi when the
     * bridge isn't running here, as cells appended to the stats panel.
     */
    private void sampleLink() {
        Bridge b = BridgeService.current();
        Dash.Snap s = b != null ? b.dash.snap : null;
        String net = s != null ? s.advertise : "";
        if (s == null) {
            s = new Dash.Snap();
            Dash.sampleWifi(this, s);
        }
        java.util.List<String[]> cells = "local".equals(net) ? new java.util.ArrayList<>() : Dash.linkStats(s, net);
        StringBuilder k = new StringBuilder();
        for (String[] c : cells) k.append(c[0]).append('=').append(c[1]).append('|');
        if (k.toString().equals(linkKey)) return;
        linkKey = k.toString();
        linkCells = cells;
        ses.pageLink(cells);
        refreshStats();
    }

    private void refreshStats() {
        if (statsBox == null) return;
        statsTitle.setText(scanLabel.isEmpty() ? "Waiting for Creality Scan state…" : scanLabel);
        statsTitle.setVisibility(landscape ? View.GONE : View.VISIBLE);
        statsGrid.removeAllViews();
        org.json.JSONArray all = lastStats;
        statsLink.removeAllViews();
        for (String[] c : linkCells) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (c.length > 2) {                              // Wi-Fi signal: the dashboard's bars
                int lv = Integer.parseInt(c[2]);
                BarsView bars = new BarsView(this);
                bars.set(lv, Dash.barsColor(lv));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(18), dp(12));
                bl.rightMargin = dp(6);
                row.addView(bars, bl);
            }
            TextView t = text(13, SUB, false);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(c[0] + " ");
            int st = sb.length();
            sb.append(c[1]);
            sb.setSpan(new android.text.style.ForegroundColorSpan(TEXT), st, sb.length(), 0);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), 0);
            t.setText(sb);
            row.addView(t);
            statsLink.addView(row);
        }
        statsLink.setVisibility(linkCells.isEmpty() ? View.GONE : View.VISIBLE);
        int n = all.length();
        int cols = landscape ? 3 : 2;
        for (int i = 0; i < n; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(landscape ? 1 : 3), 0, 0);
            for (int k = i; k < i + cols; k++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                if (k < n) {
                    org.json.JSONArray it = all.optJSONArray(k);
                    TextView l = text(10, SUB, false);
                    l.setText(it != null ? it.optString(0) : "");
                    TextView v = text(14, TEXT, true);
                    v.setText(it != null ? it.optString(1) : "");
                    cell.addView(l);
                    cell.addView(v);
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            statsGrid.addView(row);
        }
        statsDev.setText(lastDev);
        statsDev.setVisibility(lastDev.isEmpty() || landscape ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onButtons(JSONObject b, boolean known, boolean resume, boolean pcDriven) {
        btnState = b;
        btnResume = resume;
        int shown = 0;
        for (int i = 0; i < 5; i++) if (state(i) != 2) shown++;
        btnHint.setText(!known ? "Scan buttons appear when Creality Scan reports its state"
                : shown == 0 ? "No scan actions available right now"
                : pcDriven ? "" : "Buttons estimated from the scan status");
        btnHint.setVisibility(btnHint.getText().length() == 0 ? View.GONE : View.VISIBLE);
        refreshButtons();
    }

    @Override
    public void onScanLabel(String s) {
        setScanLabel(s);
    }

    /** Readable scan state from the page (for the collapsed strip). */
    void setScanLabel(String s) {
        scanLabel = s == null ? "" : s;
        sState.setText(scanLabel);
        if (ses.decoding && !scanLabel.isEmpty()) statusText.setText(scanLabel);
        refreshStats();
    }

    private int state(int i) {
        return btnState != null ? btnState.optInt(String.valueOf(SCAN_TYPE[i]), 2) : 2;
    }

    private void refreshButtons() {
        for (int i = 0; i < 5; i++) {
            int st = state(i);
            Button bt = scanBtn[i];
            bt.setVisibility(st == 2 ? View.GONE : View.VISIBLE);
            bt.setEnabled(st == 1);
            bt.setAlpha(st == 3 ? 0.4f : 1f);
            if (i == 0) bt.setText(btnResume ? "Resume" : "Start");
        }
        if (landscape && !collapsed && grid != null) {     // two per row, visible buttons only
            grid.removeAllViews();
            for (Button b : scanBtn) detach(b);
            LinearLayout row = null;
            int inRow = 0;
            for (Button b : scanBtn) {
                if (b.getVisibility() != View.VISIBLE) continue;
                if (row == null || inRow == 2) {
                    row = new LinearLayout(this);
                    grid.addView(row);
                    inRow = 0;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
                lp.setMargins(dp(3), dp(3), dp(3), 0);
                row.addView(b, lp);
                inRow++;
            }
        }
        // collapsed strip: the one button that matters now (Pause, else Start/Resume, else New scan, else Finish)
        sMainIdx = -1;
        for (int i : new int[]{1, 0, 4, 2}) if (state(i) == 1) { sMainIdx = i; break; }
        if (sMainIdx >= 0) {
            sMain.setVisibility(View.VISIBLE);
            sMain.setText(sMainIdx == 0 ? (btnResume ? "Resume" : "Start") : sMainIdx == 1 ? "Pause" : sMainIdx == 4 ? "New" : "Finish");
        } else sMain.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ finding the PC
    /** Intent extra, the broadcast heard by the bridge, the bridge's PC, a 3 s listen, the saved IP, else ask. */
    private void start() {
        String extra = getIntent().getStringExtra("host");
        if (extra != null && !extra.isEmpty()) {
            ses.connect(extra, "intent");
            return;
        }
        new Thread(() -> {
            Bridge br = BridgeService.current();
            String found = br != null ? br.mirrorWatch.recentHost(6000) : null;
            String src = "broadcast (bridge)";
            if (found == null) { found = bridgePeer(); src = "bridge peer"; }
            if (found == null) {
                h.post(() -> onStatus("Looking for Creality Scan…", "Listening for its broadcast on UDP " + BROADCAST_PORT));
                found = listenBroadcast(3000);
                src = "broadcast";
            }
            if (found == null) { found = prefs.getString("mirror_host", null); src = "saved"; }
            final String f = found, s = src;
            h.post(() -> {
                if (f != null) ses.connect(f, s);
                else {
                    onStatus("PC not found", "Press \"Mobile connection\" in Creality Scan, or enter the PC's IP (⋮).");
                    askHost();
                }
            });
        }, "mirror-find").start();
    }

    private static String bridgePeer() {
        Bridge b = BridgeService.current();
        if (b == null || b.host == null) return null;
        String p = b.host.lastPeer;
        boolean live = b.host.clients.get() > 0 || System.currentTimeMillis() - b.host.lastRequestMs < 120000;
        return live ? p : null;
    }

    static String listenBroadcast(int ms) {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(BROADCAST_PORT));
            s.setSoTimeout(ms);
            byte[] buf = new byte[256];
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (BROADCAST_MSG.equals(msg)) return p.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            BLog.log("mirror_broadcast_error", "err", String.valueOf(e));
        }
        return null;
    }

    private void askHost() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setHint("PC IP, e.g. 192.168.1.20 (optionally :port)");
        String cur = ses.hostPort() != null ? ses.hostPort() : prefs.getString("mirror_host", "");
        e.setText(cur);
        new AlertDialog.Builder(this)
                .setTitle("Creality Scan PC")
                .setMessage("IP address of the PC running Creality Scan. Press \"Mobile connection\" in Creality Scan first.")
                .setView(e)
                .setPositiveButton("Connect", (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (!v.isEmpty()) {
                        prefs.edit().putString("mirror_host", v).apply();
                        ses.connect(v, "manual");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ scan buttons
    private void onScanButton(int i) {
        if (i == 3) {        // Cancel discards the scan: confirm
            new AlertDialog.Builder(this)
                    .setTitle("Cancel this scan?")
                    .setMessage("Creality Scan will discard the current scan data.")
                    .setPositiveButton("Discard", (d, w) -> ses.rpc(SCAN_RPC[3]))
                    .setNegativeButton("Keep", null)
                    .show();
            return;
        }
        ses.rpc(SCAN_RPC[i]);
    }

    // ------------------------------------------------------------------ bridge panel: fixed grid, nothing wraps
    private LinearLayout buildPanel() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, dp(12)));
        c.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        pOrb = new OrbView(this);
        r1.addView(pOrb, new LinearLayout.LayoutParams(dp(22), dp(22)));
        pHero = text(14, TEXT, true);
        pHero.setPadding(dp(8), 0, 0, 0);
        r1.addView(pHero, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setPadding(0, dp(4), 0, 0);
        String[] names = {"IR L", "IR R", "Color"};
        for (int i = 0; i < 3; i++) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(rounded(LINE, dp(8)));
            box.setPadding(dp(2), dp(1), dp(2), dp(2));
            TextView lab = text(10, SUB, false);
            lab.setGravity(Gravity.CENTER);
            lab.setText(names[i]);
            TextView val = text(15, TEXT, true);
            val.setGravity(Gravity.CENTER);
            val.setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, TypedValue.COMPLEX_UNIT_SP);
            val.setText("—");
            box.addView(lab);
            box.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(5);
            chipBox[i] = box;
            chipVal[i] = val;
            r2.addView(box, lp);
        }
        c.addView(r2);
        pDecim = text(12, AMBER, true);     // "IR at ½ rate · trying full in 4 s" while decimated
        pDecim.setMaxLines(1);
        pDecim.setPadding(0, dp(3), 0, 0);
        pDecim.setVisibility(View.GONE);
        c.addView(pDecim);
        LinearLayout r3 = new LinearLayout(this);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        r3.setPadding(0, dp(4), 0, 0);
        pTotals = text(12, SUB, false);
        pTotals.setMaxLines(1);
        r3.addView(pTotals, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(r3);
        LinearLayout r3b = new LinearLayout(this);      // link quality on its own line so the totals never truncate
        r3b.setGravity(Gravity.CENTER_VERTICAL);
        r3b.setPadding(0, dp(2), 0, 0);
        pLink = text(12, SUB, false);        // "Stream: Good (0.2 drops/s)", the word in the quality color
        pLink.setMaxLines(1);
        r3b.addView(pLink, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(r3b);
        pScanner = text(12, SUB, false);
        pScanner.setPadding(0, dp(2), 0, 0);
        c.addView(pScanner);
        pWarn = text(12, TEXT, true);
        pWarn.setBackground(rounded(0xFF5A1D1D, dp(6)));
        pWarn.setPadding(dp(8), dp(3), dp(8), dp(3));
        pWarn.setVisibility(View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(4);
        c.addView(pWarn, wl);
        return c;
    }

    static final int CHIP_GREEN = 0xFF1D4A2A, CHIP_AMBER = 0xFF4A3B12, CHIP_RED = 0xFF5A1D1D;

    /** Green: full rate (no decimation, sent >= 90 % of the requested fps); amber: decimated or slow; red: stalled. */
    static int chipColor(Dash.Stream st) {
        if (st == null || !st.active) return LINE;
        if (st.fpsSent < 0.5f) return CHIP_RED;
        float want = st.fpsReq > 0 ? st.fpsReq : 15;
        return st.k == 1 && st.fpsSent >= 0.9f * want ? CHIP_GREEN : CHIP_AMBER;
    }

    private static String chip(Dash.Stream st) {
        if (st == null || !st.active) return "idle";
        String r = String.format(Locale.US, "%.0f fps", st.fpsSent);
        if (st.k == 2) r += " ½";
        else if (st.k >= 3) r += " ⅓";
        return r;
    }

    private void refreshPanel() {
        Bridge b = BridgeService.current();
        Dash.Snap s = b != null ? b.dash.snap : null;
        if (s == null) {
            pOrb.set(heroColor(Dash.OFF), false);
            sOrb.set(heroColor(Dash.OFF), false);
            pHero.setText("Bridge off on this phone");
            for (int i = 0; i < 3; i++) chipVal[i].setText("—");
            pTotals.setText("Start it on the dashboard");
            pLink.setText("");
            pLink.setVisibility(View.GONE);
            pScanner.setText("");
            pScanner.setVisibility(View.GONE);       // empty lines take no height
            pWarn.setVisibility(View.GONE);
            return;
        }
        pOrb.set(heroColor(s.hero), s.hero == Dash.STREAMING);
        sOrb.set(heroColor(s.hero), s.hero == Dash.STREAMING);
        String hero = s.heroText;
        if (s.hero == Dash.STREAMING) {                  // "Streaming to <ip> via MyWiFi" -> "Streaming via MyWiFi"
            int v = hero.indexOf(" via ");
            hero = v > 0 ? "Streaming" + hero.substring(v) : "Streaming";
        }
        pHero.setText(hero);
        for (int i = 0; i < 3; i++) {
            Dash.Stream st = s.streams[i];
            chipVal[i].setText(chip(st));
            chipBox[i].setBackground(rounded(chipColor(st), dp(8)));
        }
        Dash.Stream ir = s.streams[0];
        boolean dec = ir != null && ir.active && ir.k > 1;
        pDecim.setVisibility(dec ? View.VISIBLE : View.GONE);
        if (dec) pDecim.setText("IR at " + (ir.k == 2 ? "½" : "⅓") + " rate" + Dash.recoverLabel(s.irRecoverMs, ir.k)
                .replace(" in ", " rate in ").replace("trying full", "trying full rate").replace("trying ½", "trying ½ rate"));
        pTotals.setText(String.format(Locale.US, "%.0f Mbit/s · %.1f GB", s.totalMbps, s.totalBytes / 1e9));
        android.text.SpannableStringBuilder link = new android.text.SpannableStringBuilder();
        if (s.linkScore >= 0) {
            link.append("Stream: ");
            int st = link.length();
            link.append(s.linkWord);
            link.setSpan(new android.text.style.ForegroundColorSpan(scoreColor(s.linkScore)), st, link.length(), 0);
            link.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, link.length(), 0);
            link.append(String.format(Locale.US, " (%.1f drops/s)", s.linkDropsPerSec));
        }
        if (s.p2pWanted && s.p2pFreq > 0)
            link.append(link.length() == 0 ? "" : " · ").append("ch ").append(String.valueOf(Dash.channel(s.p2pFreq)));
        pLink.setText(link);
        pLink.setVisibility(link.length() == 0 ? View.GONE : View.VISIBLE);   // no blank row before the first score
        String sc = s.scanner ? s.scannerModel + " · fw " + s.scannerFw + " · " + (s.usbSuper ? "USB 3" : "USB 2") : "Scanner: " + s.scannerState;
        if (s.battery >= 0) sc += " · " + s.battery + "%" + (s.powered ? "" : " battery");
        pScanner.setText(sc);
        pScanner.setVisibility(View.VISIBLE);
        String warn = s.brownOut ? "Scanner lost power: use a powered USB-C hub"
                : !s.powered && s.battery >= 0 ? "No external power: scanner may disconnect" : null;
        pWarn.setText(warn != null ? warn : "");
        pWarn.setVisibility(warn != null ? View.VISIBLE : View.GONE);
    }
}
