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

    private void applyQuality() {
        if (!rpcReady) return;
        boolean wantLow = !visible;
        if (wantLow == lowQuality) return;
        lowQuality = wantLow;
        // StreamQualityMessage {TextureType, ScaleFactor, FrameRate, QualityFactor};
        // normal = the PC prefab's VideoStreamSender defaults (scale 1.0, 30 fps).
        double scale = wantLow ? 0.25 : 1.0, q = wantLow ? 0.5 : 1.0;
        int fps = wantLow ? 2 : 30;
        for (int t : new int[]{1, 2, 4}) {
            js(String.format(Locale.US, "rpcNotify('stream.quality',{TextureType:%d,ScaleFactor:%.2f,FrameRate:%d,QualityFactor:%.2f})",
                    t, scale, fps, q));
        }
        BLog.log("mirror_quality", "mode", wantLow ? "low (hidden)" : "normal (visible)", "scale", scale, "fps", fps);
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
            if ("init".equals(state)) h.post(() -> { rpcReady = true; applyQuality(); });
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
                    String n = t == 1 ? "RGB" : t == 2 ? "IR" : t == 4 ? "Model" : "track" + (i + 1);
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(n).append(' ').append(o.optString("codec")).append(' ')
                            .append(String.format(Locale.US, "%.0f fps", o.optDouble("fps"))).append(' ')
                            .append(o.optInt("w")).append('x').append(o.optInt("h"));
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
                }
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
