package com.crbyob.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The mirror session, independent of any Activity: TCP 57308 signaling + a WebView (application
 * context, kept alive off-screen) running WebRTC and JSON-RPC (assets/mirror.html). MirrorActivity only
 * attaches the WebView while it is visible. Auto-connects when the bridge hears Creality Scan's
 * "OrbbecRTCServer" broadcast (its "Mobile connection" dialog then closes by itself), and asks the PC for
 * low-rate streams while no viewer is visible, because the phone's Wi-Fi also carries the scanner data.
 */
final class MirrorSession {
    static final int SIGNAL_PORT = 57308;
    static final String CONN_ID = "OrbbecRTC";

    /** UI of an attached MirrorActivity; all calls on the main thread. */
    interface Ui {
        void onStatus(String title, String sub);
        void onStats(String title, String line, boolean decoding);
        void onButtons(JSONObject buttons, boolean known, boolean resume, boolean pcDriven);
        void onScanLabel(String label);
        void onScanStats(JSONArray stats, String dev);
        /** The mirror frame-rate pill on the page was tapped. */
        void onMirrorFpsMenu();
    }

    private static MirrorSession instance;

    static synchronized MirrorSession get(Context c) {
        if (instance == null) instance = new MirrorSession(c.getApplicationContext());
        return instance;
    }

    static synchronized MirrorSession peek() {
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean pageReady;
    private String pendingConnect;
    private Ui ui;
    private final String uid;

    // state (written on the main thread or the signal thread, read anywhere)
    private volatile Signal sig;
    private volatile String host;
    private volatile int port = SIGNAL_PORT;
    private volatile boolean connected, connecting, rpcReady, visible, lowQuality;
    volatile int requestedFps;      // the frame rate last asked of Creality Scan
    volatile boolean decoding;
    private String lastTitle = "Not connected", lastSub = "", lastStatsTitle, lastStatsLine;
    private JSONObject lastButtons;
    private boolean lastKnown, lastResume, lastPcDriven;
    private String lastLabel = "", lastDev = "";
    private JSONArray lastStats = new JSONArray();
    // auto-connect
    private volatile boolean suppressed;
    private volatile long lastBroadcastMs, lastAutoTry;
    // bitrate measurement
    private long statBytes = -1, statT;
    private volatile double kbps;
    private long kbpsLogAt;

    private MirrorSession(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        String u = prefs.getString("mirror_uid", null);
        if (u == null) {
            u = "diy-bridge-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("mirror_uid", u).apply();
        }
        uid = u;
    }

    // ------------------------------------------------------------------ public state
    boolean isActive() { return connected; }
    boolean isConnecting() { return connecting && !connected; }
    String hostPort() { return host == null ? null : port == SIGNAL_PORT ? host : host + ":" + port; }
    double kbps() { return kbps; }

    boolean autoEnabled() { return prefs.getBoolean("mirror_auto", true); }
    void setAutoEnabled(boolean on) { prefs.edit().putBoolean("mirror_auto", on).apply(); }

    // ------------------------------------------------------------------ WebView
    /** The session's WebView (created on first use, main thread only). */
    WebView web() {
        if (web == null) {
            web = new WebView(app);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);   // chrome://inspect in debug builds only
            web.setBackgroundColor(Widgets.BG);
            web.addJavascriptInterface(new Js(), "Android");
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage m) {
                    BLog.log("mirror_js_console", "msg", m.message(), "line", m.lineNumber());
                    return true;
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    pageReady = true;
                    v.evaluateJavascript("mirrorStart(" + JSONObject.quote(uid) + ")", null);
                    // the quality pill from the start, so it can be set before Creality Scan connects
                    v.evaluateJavascript(pillJs(visibleFps()), null);
                    if (pendingConnect != null) {
                        String p = pendingConnect;
                        pendingConnect = null;
                        startSignal(p);
                    }
                }
            });
            // https base URL: a secure context for RTCPeerConnection; the page makes no network requests
            web.loadDataWithBaseURL("https://mirror.crbyob.local/", readAsset("mirror.html"), "text/html", "utf-8", null);
        }
        return web;
    }

    /** Put the WebView into an Activity's layout (removing it from wherever it was). */
    void attach(ViewGroup parent, ViewGroup.LayoutParams lp) {
        WebView w = web();
        detachWeb();
        parent.addView(w, lp);
    }

    void detachWeb() {
        if (web != null && web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    }

    void setUi(Ui u) {
        ui = u;
        if (u != null) {
            u.onStatus(lastTitle, lastSub);
            if (decoding && lastStatsTitle != null) u.onStats(lastStatsTitle, lastStatsLine, true);
            if (lastButtons != null || lastKnown) u.onButtons(lastButtons, lastKnown, lastResume, lastPcDriven);
            u.onScanLabel(lastLabel);
            u.onScanStats(lastStats, lastDev);
        }
    }

    private String readAsset(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "<html><body style='color:#fff;background:#000'>mirror.html missing: " + e + "</body></html>";
        }
    }

    /** The bridge's link cells, {label, value}, for the page's portrait info block. */
    void pageLink(java.util.List<String[]> cells) {
        JSONArray a = new JSONArray();
        for (String[] c : cells) {
            JSONArray x = new JSONArray().put(c[0]).put(c[1]);
            if (c.length > 2) {                                  // signal bars: level and CSS color
                int lv = Integer.parseInt(c[2]);
                x.put(lv).put(String.format("#%06X", Dash.barsColor(lv) & 0xFFFFFF));
            }
            a.put(x);
        }
        js("setLink(" + a + ")");
    }

    private void js(String code) {
        h.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private void status(String title, String sub) {
        h.post(() -> {
            lastTitle = title;
            if (sub != null) lastSub = sub;
            if (ui != null) ui.onStatus(title, sub);
        });
    }

    // ------------------------------------------------------------------ connect / close
    /** Connect to "ip" or "ip:port" (replacing any current session). */
    void connect(String hostPort, String source) {
        h.post(() -> {
            BLog.log("mirror_connect", "host", hostPort, "source", source);
            suppressed = false;
            web();
            if (!pageReady) pendingConnect = hostPort;
            else startSignal(hostPort);
        });
    }

    private void startSignal(String hostPort) {
        int pt = SIGNAL_PORT;
        String hh = hostPort.trim();
        int c = hh.lastIndexOf(':');
        if (c > 0 && hh.indexOf(':') == c) {            // "ip:port" (IPv4 / hostname only)
            try { pt = Integer.parseInt(hh.substring(c + 1)); hh = hh.substring(0, c); } catch (NumberFormatException ignored) { }
        }
        Signal old = sig;
        sig = null;
        if (old != null) old.close();
        web.evaluateJavascript("mirrorClose()", null);
        resetSessionState();
        host = hh;
        port = pt;
        connecting = true;
        Signal s = new Signal(hh, pt);
        SessionStats.cur.mirrorConnects.incrementAndGet();
        sig = s;
        new Thread(s, "mirror-signal").start();
    }

    private void resetSessionState() {
        connected = false;
        rpcReady = false;
        lowQuality = false;
        decoding = false;
        lastStatsTitle = null;
        lastButtons = null;
        lastKnown = false;
        statBytes = -1;
        kbps = 0;
    }

    /** User pressed Close: disconnect for real and don't auto-reconnect until the broadcast stops and restarts. */
    void close() {
        h.post(() -> {
            suppressed = true;
            BLog.log("mirror_close", "host", host);
            Signal s = sig;
            sig = null;
            if (s != null) s.close();
            if (web != null) web.evaluateJavascript("mirrorClose()", null);
            connecting = false;
            resetSessionState();
            status("Closed", "Auto-connect waits until Creality Scan's Mobile connection is reopened.");
        });
    }

    /** Called by MirrorWatch for every "OrbbecRTCServer" datagram. bridgeWatch = heard by the running bridge. */
    void onBroadcast(String ip, boolean bridgeWatch) {
        long now = System.currentTimeMillis();
        boolean gap = now - lastBroadcastMs > 4000;     // the broadcast had stopped: a fresh "Mobile connection"
        lastBroadcastMs = now;
        if (suppressed && gap && lastBroadcastMs > 0) {
            suppressed = false;
            BLog.log("mirror_auto_rearmed", "host", ip);
        }
        if (suppressed || !autoEnabled() || connected || connecting) return;
        if (!bridgeWatch && !prefs.getBoolean("mirror_auto_ui", false)) return;   // only the bridge phone mirrors
        if (now - lastAutoTry < 3000) return;                                   // no tight retry loop
        lastAutoTry = now;
        int ap = prefs.getInt("mirror_auto_port", SIGNAL_PORT);   // test hook: point auto-connect at a fake PC port
        connect(ap == SIGNAL_PORT ? ip : ip + ":" + ap, "auto (broadcast)");
    }

    void rpc(String method) {
        if (!connected) {
            status("Not connected", "Connect to Creality Scan first");
            return;
        }
        js("rpcNotify(" + JSONObject.quote(method) + ")");
        BLog.log("mirror_cmd", "method", method);
    }

    // ------------------------------------------------------------------ bandwidth while hidden
    /** A viewer became visible / hidden. Hidden -> low-rate streams; visible again -> the normal (prefab) values. */
    void setVisible(boolean v) {
        h.post(() -> {
            visible = v;
            applyQuality();
        });
    }

    /** "Adjust automatically" (prefs "mirror_adapt", on by default): see MirrorAuto. */
    boolean adaptSetting() { return prefs.getBoolean("mirror_adapt", true); }

    /** Frame rate: exactly this, or the most Auto uses (prefs "mirror_fps"; an old 0 = Auto reads as 30). */
    int fpsSetting() {
        int f = prefs.getInt("mirror_fps", 30);
        for (int v : MirrorAuto.FPS) if (v == f) return f;
        return 30;
    }

    /** 3D view resolution in percent: exactly this, or the most Auto uses (prefs "mirror_res"). */
    int resSetting() {
        int r = prefs.getInt("mirror_res", 100);
        for (int v : MirrorAuto.RES) if (v == r) return r;
        return 100;
    }

    /**
     * The 3D view's texture size asked of Creality Scan in init: the screen's long side (at most 1280) at 4:3, times
     * the resolution in percent. It's the one size Creality Scan honors, and only at init: it ignores
     * StreamQualityMessage.ScaleFactor (at 0.5 and 0.25 every track kept its size), so a change reconnects.
     */
    int[] modelSize(int res) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int full = Math.min(1280, Math.max(dm.widthPixels, dm.heightPixels));
        int w = Math.max(160, full * res / 100 / 8 * 8);
        return new int[]{w, w * 3 / 4 / 8 * 8};
    }

    private final MirrorAuto auto = new MirrorAuto();
    private int initRes;                 // the resolution sent in this connection's init

    private void ensureAuto() {
        if (auto.fps == 0) auto.reset(fpsSetting(), resSetting(), System.currentTimeMillis());
    }

    /** The frame rate asked of Creality Scan while the viewer is visible. */
    int visibleFps() {
        if (!adaptSetting()) return fpsSetting();
        ensureAuto();
        return auto.fps;
    }

    /** The 3D resolution in effect (percent). */
    int visibleRes() {
        if (!adaptSetting()) return resSetting();
        ensureAuto();
        return auto.res;
    }

    /**
     * The viewer's quality pill: the frame rate and 3D size in effect, "auto" when adjusting, and degraded (amber)
     * when Auto has stepped either below the chosen ceiling.
     */
    private String pillJs(int fps) {
        int res = visibleRes();
        int[] m = modelSize(res);
        boolean degraded = adaptSetting() && (fps < fpsSetting() || res < resSetting());
        return "setMirrorFps(" + adaptSetting() + "," + fps + "," + m[0] + "," + m[1] + "," + degraded + ")";
    }

    /** Every stats interval (~5 s): worstFreezeMs is the longest any one view froze in it. */
    private void autoAdjust(long worstFreezeMs, long now) {
        if (!adaptSetting() || !visible || lowQuality || !rpcReady) {
            auto.pause(now);
            return;
        }
        ensureAuto();
        int f0 = auto.fps, r0 = auto.res;
        int what = auto.step(worstFreezeMs, now);
        if (what == MirrorAuto.NONE) return;
        BLog.log("mirror_auto", "fps", f0 + "->" + auto.fps, "res", r0 + "->" + auto.res);
        String hp = hostPort();
        if (what == MirrorAuto.RES_CHANGED && hp != null) connect(hp, "auto resolution");   // init carries it
        else applyQuality(true);
    }

    /** Save the viewer's settings and apply them now; Auto starts over at the new ceiling. */
    void setMirrorQuality(boolean adapt, int fps, int res) {
        prefs.edit().putBoolean("mirror_adapt", adapt).putInt("mirror_fps", fps).putInt("mirror_res", res).apply();
        h.post(() -> {
            auto.reset(fpsSetting(), resSetting(), System.currentTimeMillis());
            String hp = hostPort();
            if (visibleRes() != initRes && hp != null && (connected || connecting)) connect(hp, "resolution");
            else applyQuality(true);
        });
    }

    private void applyQuality() {
        applyQuality(false);
    }

    private void applyQuality(boolean force) {
        if (!rpcReady) return;
        boolean wantLow = !visible;
        if (wantLow == lowQuality && !force) return;
        lowQuality = wantLow;
        // StreamQualityMessage {TextureType, ScaleFactor, FrameRate, QualityFactor};
        // the PC prefab's VideoStreamSender defaults are scale 1.0, 30 fps.
        double scale = wantLow ? 0.25 : 1.0, q = wantLow ? 0.5 : 1.0;
        int fps = wantLow ? 2 : visibleFps();
        for (int t : new int[]{1, 2, 4}) {
            js(String.format(Locale.US, "rpcNotify('stream.quality',{TextureType:%d,ScaleFactor:%.2f,FrameRate:%d,QualityFactor:%.2f})",
                    t, scale, fps, q));
        }
        requestedFps = fps;
        if (!wantLow) js(pillJs(fps));       // the viewer's pill
        BLog.log("mirror_quality", "mode", wantLow ? "low (hidden)" : "normal (visible)", "fps", fps,
                "res", wantLow ? null : (Object) visibleRes(),
                "setting", wantLow ? null : (adaptSetting() ? "auto up to " : "fixed ") + fpsSetting() + " fps " + resSetting() + "%");
    }

    // ------------------------------------------------------------------ TCP signaling
    final class Signal implements Runnable {
        final String host;
        final int port;
        private Socket sock;
        private OutputStream out;
        private volatile boolean closed;
        private String lastOffer;
        private boolean answered;

        Signal(String host, int port) {
            this.host = host;
            this.port = port;
        }

        void close() {
            closed = true;
            new Thread(() -> {
                try {
                    send(new JSONObject().put("type", "disconnect").put("connectionId", CONN_ID).toString());
                } catch (Exception ignored) {
                }
                try { if (sock != null) sock.close(); } catch (Exception ignored) { }
            }, "mirror-close").start();
        }

        synchronized void send(String json) throws Exception {
            if (out == null) return;
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            byte[] f = new byte[4 + b.length];
            Hp.putU32(f, 0, b.length);                     // int32 little-endian length
            System.arraycopy(b, 0, f, 4, b.length);
            out.write(f);
            out.flush();
            BLog.log("mirror_sig_out", "len", b.length, "msg", json.length() > 200 ? json.substring(0, 200) : json);
        }

        /** RoutedMessage<SignalingMessage>, all fields present as Unity's JsonUtility writes them. */
        String routed(String type, String sdp, String cand, String mid, int idx) throws Exception {
            JSONObject d = new JSONObject();
            d.put("status", "").put("message", "").put("sessionId", "").put("connectionId", CONN_ID).put("polite", false)
                    .put("sdp", sdp == null ? "" : sdp).put("type", type).put("candidate", cand == null ? "" : cand)
                    .put("sdpMid", mid == null ? "" : mid).put("sdpMLineIndex", idx);
            return new JSONObject().put("from", CONN_ID).put("to", "").put("type", type).put("data", d).toString();
        }

        @Override
        public void run() {
            status("Connecting to " + host + "…", "TCP " + port);
            String endTitle = null, endSub = null;
            try {
                sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(host, port), 4000);
                out = sock.getOutputStream();
                DataInputStream in = new DataInputStream(sock.getInputStream());
                prefs.edit().putString("mirror_host", port == SIGNAL_PORT ? host : host + ":" + port).apply();
                // literal from TCPSignaling::OpenConnection (note the space after the comma)
                send("{\"type\":\"connect\", \"connectionId\":\"" + CONN_ID + "\"}");
                connected = true;
                connecting = false;
                status("Waiting for Creality Scan's offer…", host + ":" + port);
                byte[] lenb = new byte[4];
                while (!closed) {
                    in.readFully(lenb);
                    int len = Hp.u32(lenb, 0);
                    if (len <= 0 || len >= (1 << 20)) throw new Exception("invalid message length " + len);
                    byte[] body = new byte[len];
                    in.readFully(body);
                    handle(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                }
                if (!closed || sig == this) { endTitle = "Creality Scan closed the session"; endSub = "Waiting for its Mobile connection again"; }
            } catch (ConnectException e) {
                endTitle = "Creality Scan is not accepting phones";
                endSub = "Connection to " + host + ":" + port + " was refused. In Creality Scan on the PC, press "
                        + "\"Mobile connection\"; the phone connects as soon as it opens.";
            } catch (SocketTimeoutException e) {
                endTitle = "PC not reachable";
                endSub = host + ":" + port + " did not answer. Check the IP and that the PC is on this network.";
            } catch (Exception e) {
                endTitle = "Disconnected";
                endSub = String.valueOf(e.getMessage() != null ? e.getMessage() : e) + ". Waiting for Creality Scan's Mobile connection.";
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) { }
                if (sig == this) {
                    // back to listening: no reconnect loop; the next broadcast (or Retry) starts a new session
                    sig = null;
                    connected = false;
                    connecting = false;
                    h.post(() -> {
                        if (web != null) web.evaluateJavascript("mirrorClose()", null);
                        resetSessionState();
                    });
                    if (!closed && endTitle != null) status(endTitle, endSub);
                    BLog.log("mirror_session_end", "host", host, "why", endTitle);
                }
            }
        }

        private void handle(JSONObject m) throws Exception {
            String type = m.optString("type");
            JSONObject d = m.optJSONObject("data");
            if (!"candidate".equals(type))
                BLog.log("mirror_sig_in", "type", type, "len", m.toString().length());
            switch (type) {
                case "connect":
                    status("Paired, waiting for the offer…", host + ":" + port);
                    break;
                case "offer": {
                    String sdp = d != null ? d.optString("sdp") : m.optString("sdp");
                    if (sdp.isEmpty()) break;
                    if (answered && sdp.equals(lastOffer)) break;      // re-sent until answered
                    lastOffer = sdp;
                    js("onOffer(" + JSONObject.quote(sdp) + ")");
                    break;
                }
                case "candidate":
                    if (d != null && !d.optString("candidate").isEmpty())
                        js("onCandidate(" + JSONObject.quote(d.optString("candidate")) + "," + JSONObject.quote(d.optString("sdpMid", "0"))
                                + "," + d.optInt("sdpMLineIndex", 0) + ")");
                    break;
                case "disconnect":
                    closed = true;
                    sock.close();
                    break;
                case "error":
                    status("Creality Scan reported an error", d != null ? d.optString("message") : m.toString());
                    break;
                default:
                    break;
            }
        }

        void answer(String sdp) {
            answered = true;
            try {
                send(routed("answer", fixMdns(sdp), null, null, 0));
            } catch (Exception e) {
                BLog.log("mirror_sig_error", "err", String.valueOf(e));
            }
        }

        void candidate(String c, String mid, int idx) {
            try {
                send(routed("candidate", null, fixMdns(c), mid, idx));
            } catch (Exception e) {
                BLog.log("mirror_sig_error", "err", String.valueOf(e));
            }
        }

        /** WebView hides host candidates behind "xxxx.local" mDNS names; substitute the phone's real IP facing the PC. */
        String fixMdns(String s) {
            if (s == null || !s.contains(".local")) return s;
            InetAddress a;
            try { a = InetAddress.getByName(host); } catch (Exception e) { return s; }
            String ip = Gvcp.localIpTowards(a);
            if (ip == null || ip.startsWith("0.")) return s;
            return MDNS.matcher(s).replaceAll(ip);
        }
    }

    private static final Pattern MDNS = Pattern.compile("[0-9a-fA-F-]{8,}\\.local");

    // ------------------------------------------------------------------ JS -> Java
    final class Js {
        @JavascriptInterface
        public void log(String s) {
            BLog.log("mirror_js", "msg", s);
        }

        @JavascriptInterface
        public void status(String state, String text) {
            BLog.log("mirror_state", "state", state, "text", text);
            if ("init".equals(state)) h.post(() -> { rpcReady = true; applyQuality(true); });   // always state our settings
            if ("closed".equals(state) || "error".equals(state)) decoding = false;
            String title;
            switch (state) {
                case "offer": title = "Answering Creality Scan…"; break;
                case "media": title = "Connected, waiting for video…"; break;
                case "rpc": title = "Connected, initializing…"; break;
                case "init": title = "Mirroring Creality Scan"; break;
                case "closed": title = "Session closed"; break;
                default: title = "Error";
            }
            if (decoding && !"error".equals(state) && !"closed".equals(state)) return;   // stats() owns the title then
            MirrorSession.this.status(title, text);
        }

        @JavascriptInterface
        public void pcState(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONObject b = o.optJSONObject("buttons");
                boolean known = o.optBoolean("known"), resume = o.optBoolean("resume"), pcDriven = o.optBoolean("pcDriven");
                String label = o.optString("label"), dev = o.optString("dev");
                JSONArray stats = o.optJSONArray("stats") != null ? o.optJSONArray("stats") : new JSONArray();
                h.post(() -> {
                    lastLabel = label;
                    lastStats = stats;
                    lastDev = dev;
                    if (ui != null) {
                        ui.onScanLabel(label);
                        ui.onScanStats(stats, dev);
                    }
                    lastButtons = b;
                    lastKnown = known;
                    lastResume = resume;
                    lastPcDriven = pcDriven;
                    if (ui != null) ui.onButtons(b, known, resume, pcDriven);
                });
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void sendAnswer(String sdp) {
            Signal s = sig;
            if (s != null) new Thread(() -> s.answer(sdp), "mirror-answer").start();
        }

        @JavascriptInterface
        public void sendCandidate(String c, String mid, int idx) {
            Signal s = sig;
            if (s != null) new Thread(() -> s.candidate(c, mid, idx), "mirror-cand").start();
        }

        @JavascriptInterface
        public void rpcOut(String text) {
            if (!text.contains("\"heartbeat\"")) BLog.log("mirror_rpc_out", "msg", text);
        }

        @JavascriptInterface
        public void rpcIn(String text) {
            BLog.log("mirror_rpc_in", "msg", text.length() > 300 ? text.substring(0, 300) : text);
        }

        private final java.util.Map<String, JSONObject> prevVideo = new java.util.HashMap<>();
        private final java.util.Map<String, Long> prevVideoAt = new java.util.HashMap<>();

        /**
         * Per-track mirror health over the last log interval. Frames received but not decoded point at the phone,
         * packets lost / NACKs / jitter at the link, and a low received rate with neither at the PC's encoder.
         */
        private long logVideo(JSONObject o, long now) {
            String id = o.optString("track");
            JSONObject p = prevVideo.put(id, o);
            Long pt = prevVideoAt.put(id, now);
            if (p == null || pt == null || now <= pt) return 0;
            long freezeMs = Math.round((o.optDouble("freezeS") - p.optDouble("freezeS")) * 1000);
            double dt = (now - pt) / 1000.0;
            long jbOut = o.optLong("jbOut") - p.optLong("jbOut");
            long dec = o.optLong("decoded") - p.optLong("decoded");
            int t = o.optInt("type");
            BLog.log("mirror_video", "track", t == 1 ? "RGB" : t == 2 ? "IR" : t == 4 ? "Model" : id,
                    "size", o.optInt("w") + "x" + o.optInt("h"), "decoder", o.optString("decoder"),
                    "recv_fps", Math.round((o.optLong("recv") - p.optLong("recv")) / dt * 10) / 10.0,
                    "dec_fps", Math.round(dec / dt * 10) / 10.0,
                    "dropped", o.optLong("dropped") - p.optLong("dropped"),
                    "pkts", o.optLong("pkts") - p.optLong("pkts"), "lost", o.optLong("lost") - p.optLong("lost"),
                    "nack", o.optLong("nack") - p.optLong("nack"), "pli", o.optLong("pli") - p.optLong("pli"),
                    "freezes", o.optLong("freezes") - p.optLong("freezes"),
                    "freeze_ms", freezeMs,
                    "jitter_ms", Math.round(o.optDouble("jitter") * 1000),
                    "jb_ms", jbOut > 0 ? Math.round((o.optDouble("jbDelay") - p.optDouble("jbDelay")) / jbOut * 1000) : null,
                    "decode_ms", dec > 0 ? Math.round((o.optDouble("decodeS") - p.optDouble("decodeS")) / dec * 10000) / 10.0 : null);
            return freezeMs;
        }

        /** "w,h" for init's CustomModelTextureSize. */
        @JavascriptInterface
        public String modelSize() {
            initRes = visibleRes();
            int[] m = MirrorSession.this.modelSize(initRes);
            return m[0] + "," + m[1];
        }

        @JavascriptInterface
        public void mirrorFpsMenu() {
            h.post(() -> { if (ui != null) ui.onMirrorFpsMenu(); });
        }

        @JavascriptInterface
        public void stats(String json) {
            try {
                JSONArray a = new JSONArray(json);
                StringBuilder sb = new StringBuilder();
                long bytes = 0;
                int live = 0;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    int t = o.optInt("type");
                    String n = t == 1 ? "RGB" : t == 2 ? "IR" : t == 4 ? "3D" : "track" + (i + 1);
                    sb.append(sb.length() > 0 ? " · " : "Mirror: ").append(n).append(' ')
                            .append(String.format(Locale.US, "%.0f", o.optDouble("fps")));
                    bytes += o.optLong("bytes");
                    if (o.optDouble("fps") > 0.5) live++;
                }
                long now = System.currentTimeMillis();
                if (statBytes >= 0 && now > statT && bytes >= statBytes) kbps = (bytes - statBytes) * 8.0 / (now - statT);
                statBytes = bytes;
                statT = now;
                if (now - kbpsLogAt > 5000) {
                    kbpsLogAt = now;
                    BLog.log("mirror_bitrate", "kbps", Math.round(kbps), "visible", visible, "low_quality", lowQuality, "tracks", a.length());
                    long worst = 0;
                    for (int i = 0; i < a.length(); i++) worst = Math.max(worst, logVideo(a.getJSONObject(i), now));
                    final long worstMs = worst, at = now;
                    h.post(() -> autoAdjust(worstMs, at));
                }
                if (sb.length() > 0) sb.append(" fps");      // frames arriving per second, per view
                final String line = (host != null ? host + " · " : "") + (sb.length() > 0 ? sb : "no video yet")
                        + String.format(Locale.US, " · %.1f Mbit/s", kbps / 1000);
                final int n = live;
                h.post(() -> {
                    String title;
                    if (n > 0) {
                        decoding = true;
                        title = "Connected · " + n + (n == 1 ? " stream" : " streams");
                    } else if (decoding) title = "Connected · no video (Creality Scan idle?)";
                    else title = null;
                    if (title != null) {
                        lastStatsTitle = title;
                        lastStatsLine = line;
                        lastTitle = title;
                        lastSub = line;
                    }
                    if (ui != null) ui.onStats(title, line, decoding);
                });
            } catch (Exception ignored) {
            }
        }
    }
}
