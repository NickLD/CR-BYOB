package com.crbyob.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Everything that is not Android-shell: network servers, scanner link, tables. */
public final class Bridge {
    // stream name -> OBStreamType
    static final int ST_IR = 1, ST_COLOR = 2, ST_DEPTH = 3, ST_ACCEL = 4, ST_GYRO = 5, ST_IR_L = 6, ST_IR_R = 7;

    /**
     * A stream the bridge serves: (stream name, stream type, VC interface number, RTSP port). Built per
     * attached scanner from its {@link ScannerModel} (scanners.json "interfaces").
     */
    static final class Role {
        final String name; final int type, vc, vsIndex, port;
        Role(String n, int t, int v, int idx, int p) { name = n; type = t; vc = v; vsIndex = idx; port = p; }
        /** Key for the per-node lock/holder maps: one capture node = one VideoStreaming interface. */
        int nodeKey() { return vc * 256 + vsIndex; }
        @Override public String toString() { return name + "@VC" + vc + (vsIndex > 0 ? "#" + vsIndex : "") + ":" + port; }
    }

    /**
     * IR encodings this app can synthesize from Y8 and so may advertise in 0xFC1:
     * RLE ({@link RleCodec}) for models whose ir_synth lists it. No MJPG: the Otter's IR stays Y8 as before.
     */
    static final Set<String> IR_ENCODERS = Collections.singleton("RLE ");

    /** Struct 0x42A (OB_STRUCT_IR_SENSOR_RLE_COMPRESSION_INFOMATION, 5 x i32); field 0 = RLE threshold. */
    static final int PROP_RLE_PARAMS = 0x42A;
    final int[] rleParams = {100, 60, 6, 15, 15};

    static int typeOf(String name) {
        switch (name) {
            case "ir": return ST_IR;
            case "color": return ST_COLOR;
            case "depth": return ST_DEPTH;
            case "accel": return ST_ACCEL;
            case "gyro": return ST_GYRO;
            case "ir_l": return ST_IR_L;
            case "ir_r": return ST_IR_R;
            default: return 0;
        }
    }

    static boolean isIr(int t) { return t == ST_IR || t == ST_IR_L || t == ST_IR_R; }

    static int obFourcc(String s) {
        s = (s + "    ").substring(0, 4);
        return s.charAt(0) << 24 | s.charAt(1) << 16 | s.charAt(2) << 8 | s.charAt(3);
    }

    // V4L2 fourcc -> Orbbec fourcc / RTP name / URL suffix: ScannerModel.formats (scanners.json "formats").

    // ---------------------------------------------------------------------------------------------
    public final Context ctx;
    private final UsbManager usb;
    private final SharedPreferences prefs;
    public final File filesDir;
    public volatile Otter otter;
    private final Object usbLock = new Object();
    private final Map<Integer, Semaphore> nodeLocks = new ConcurrentHashMap<>();
    /** The RTSP session streaming from each VC node (for takeover of stale holders). */
    final Map<Integer, RtspServer.Session> nodeHolders = new ConcurrentHashMap<>();
    private final Random rnd = new Random();

    Gvcp gvcp;
    HostServer host;
    /** Copy-on-write: stop() replaces it while closeOtter (USB detach thread) iterates it. */
    final List<RtspServer> rtsp = new java.util.concurrent.CopyOnWriteArrayList<>();
    ImuServer imu;
    final CameraControls controls;
    final IrGate irGate;
    volatile boolean running;
    final P2pGroup p2p;
    final LinkTest linkTest = new LinkTest();
    final MirrorWatch mirrorWatch = new MirrorWatch(true);
    final Dash dash = new Dash(this);
    volatile long permissionPending;

    // ExtensionInfo / GVCP config
    final String bridgeSn;      // "CRBYOB" + 4 hex of the per-install MAC (was a fixed "CRBYOB001")
    final String bridgeVersion = "1.3.1";
    final int bridgePid = 0x0C01;
    final String wifiSsid = "Creality_Scan_Pocket_D1Y001";

    // Scanner model (scanners.json). Without a scanner: the Otter, so ExtensionInfo / test pattern behave as before.
    volatile ScannerModel model;
    volatile List<Role> roles = Collections.emptyList();
    volatile Map<String, String> roleSource = Collections.emptyMap();

    public Bridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        this.prefs = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        this.filesDir = ctx.getFilesDir();
        this.bridgeSn = "CRBYOB" + idSuffix(ctx);
        this.controls = new CameraControls(this);
        this.irGate = new IrGate(filesDir);
        useDefaultModel();
        this.p2p = new P2pGroup(ctx);
        // A Wi-Fi Direct client list has MACs, not IPs; when the last client leaves, every session
        // from the group subnet is an orphan (its writes would only block until the stall guard fires).
        this.p2p.onLeave = (remaining, goIp) -> {
            if (remaining > 0) return;          // can't tell which one left: the stall guard handles it
            String gw = goIp != null ? goIp : "192.168.49.1";
            String prefix = gw.substring(0, gw.lastIndexOf('.') + 1);
            new Thread(() -> dropPeer(ip -> ip.startsWith(prefix) && !ip.equals(gw), "left Wi-Fi Direct"), "p2p-cleanup").start();
        };
    }

    /** Closes the RTSP and IMU sessions of matching peers; returns how many were closed. */
    int dropPeer(java.util.function.Predicate<String> ipMatch, String why) {
        if (!running) return 0;
        int n = 0;
        for (RtspServer s : new ArrayList<>(rtsp)) n += s.killPeer(ipMatch, why);
        ImuServer i = imu;
        if (i != null) n += i.killPeer(ipMatch, why);
        if (n > 0) {
            BLog.log("peer_cleanup", "why", why, "sessions", n);
            SessionStats.cur.peerCleanups.addAndGet(n);
            dash.event("Closed " + n + " orphaned stream session" + (n == 1 ? "" : "s") + " (" + why + ")");
        }
        return n;
    }

    // ------------------------------------------------------------------ lifecycle
    /** Network chosen at Start; fixed until Stop (the UI greys the choice out while running). */
    public volatile String lockedAdvertise;

    public synchronized void start() throws Exception {
        if (running) return;
        running = true;
        try {
            startServers();
        } catch (Exception e) {
            // e.g. a port already taken by another bridge app: don't leave discovery advertising a half-started bridge
            BLog.log("bridge_start_failed", "err", String.valueOf(e));
            dash.event("Couldn't start the bridge: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            stop();                              // its final dashboard snapshot carries the event above
            throw e;
        }
    }

    private void startServers() throws Exception {
        lockedAdvertise = effectiveAdvertise();
        BLog.log("network_locked", "advertise", lockedAdvertise);
        Gvcp.Cfg cfg = new Gvcp.Cfg();
        cfg.mac = mac();
        cfg.sn = bridgeSn;
        cfg.version = bridgeVersion;
        cfg.pid = bridgePid;
        // Discovery is answered on exactly one network: a PC on two of them would see one MAC at two IPs,
        // and the SDK then never builds the sensor list.
        cfg.advertise = () -> lockedAdvertise != null ? lockedAdvertise : effectiveAdvertise();
        gvcp = new Gvcp(cfg);
        testPattern = prefs.getBoolean("test_pattern", false);
        if (testPattern) {
            BLog.log("test_pattern_mode", "gvcp", "off", "note", "synthetic frames when no scanner is attached; not discoverable");
            dash.event("TEST PATTERN mode: not discoverable, synthetic frames");
        } else {
            gvcp.start();
        }
        host = new HostServer(this, 8090);
        host.start();
        useDefaultModel();                  // re-reads scanners.json, so an edited override applies on Start
        // One RTSP server per port; which node it serves is looked up per session (roleForPort).
        for (int port : new java.util.LinkedHashSet<>(model.streamPorts.values())) {
            RtspServer s = new RtspServer(this, port);
            s.start();
            rtsp.add(s);
        }
        imu = new ImuServer(this, model.imuPort);
        imu.start();
        if (BuildConfig.DEBUG) linkTest.start();     // TCP 8999 blasts zeros at anyone who connects: test builds only
        mirrorWatch.start();
        SessionStats.reset();               // new session: sticky counters start from zero
        dash.start();
        dash.event("Bridge started");
        BLog.log("bridge_started", "pid", "0x" + Integer.toHexString(bridgePid), "version", bridgeVersion, "sn", bridgeSn);
        p2p.capabilities();
        if (prefs.getBoolean("p2p", false)) p2p.start();
        requestEthernet();
        scanUsb();
    }

    private android.net.ConnectivityManager.NetworkCallback ethCallback;

    /**
     * Android only runs DHCP on a USB-C Ethernet adapter while some app asks for an Ethernet network: with Wi-Fi up
     * the system default is satisfied, so eth0 sits with a link but no IP. Holding an Ethernet-only request
     * makes the system bring it up next to Wi-Fi. Servers listen on 0.0.0.0, and Android replies to accepted
     * TCP connections on the interface they came in on, so nothing needs binding.
     */
    private void requestEthernet() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            ethCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) { BLog.log("eth_available", "net", String.valueOf(n)); }
                @Override public void onLost(android.net.Network n) { BLog.log("eth_lost", "net", String.valueOf(n)); }
            };
            cm.requestNetwork(req, ethCallback);
            BLog.log("eth_requested");
        } catch (Exception e) {
            BLog.log("eth_request_error", "err", String.valueOf(e));
        }
    }

    private void releaseEthernet() {
        if (ethCallback == null) return;
        try {
            ((android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(ethCallback);
        } catch (Exception ignored) {
        }
        ethCallback = null;
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (gvcp != null) gvcp.stop();
        if (host != null) host.stop();
        for (RtspServer s : rtsp) s.stop();
        rtsp.clear();
        if (imu != null) imu.stop();
        p2p.stop();
        releaseEthernet();
        linkTest.stop();
        mirrorWatch.stop();
        dash.event("Bridge stopped");
        SessionStats.cur.stopMs = System.currentTimeMillis();   // keep the last session's values until the next Start
        dash.stop();
        lockedAdvertise = null;
        synchronized (usbLock) {             // not mid-open/close on the USB attach/detach threads
            closeOtter("service stop");
        }
        BLog.log("bridge_stopped");
    }

    private byte[] mac() {
        return persistentMac(prefs);
    }

    /** Stable per-install random MAC (locally administered), persisted in prefs "mac". */
    static synchronized byte[] persistentMac(SharedPreferences prefs) {
        String m = prefs.getString("mac", null);
        if (m == null || m.length() != 12) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] | 0x02) & 0xFE);    // locally administered unicast
            m = Hp.hex(b, 0, 6);
            prefs.edit().putString("mac", m).commit();
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(m.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** 4 hex chars that tell two phones apart (last two MAC bytes): SN "CRBYOB" + id, SSID "DIRECT-SB-CRBYOB-" + id. */
    static String idSuffix(Context ctx) {
        byte[] mac = persistentMac(ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE));
        return Hp.hex(mac, 4, 2).toUpperCase(java.util.Locale.US);
    }

    // ------------------------------------------------------------------ USB attach/detach
    public UsbDevice findDevice() {
        for (UsbDevice d : usb.getDeviceList().values()) if (Otter.isOrbbec(d)) return d;
        return null;
    }

    /** Opens the scanner if it is attached and permitted; requests permission otherwise. */
    public void scanUsb() {
        UsbDevice d = findDevice();
        if (d == null) {
            BLog.log("usb_absent");
            return;
        }
        onAttached(d);
    }

    public void onAttached(UsbDevice d) {
        if (!running || !Otter.isOrbbec(d)) return;
        synchronized (usbLock) {
            if (otter != null && !otter.isClosed()) {
                if (otter.dev.getDeviceName().equals(d.getDeviceName())) return;
                closeOtter("replaced");
            }
            if (!usb.hasPermission(d)) {
                long now = System.currentTimeMillis();
                if (now - permissionPending < 60000) return;     // a dialog is already up
                permissionPending = now;
                BLog.log("usb_permission_request", "dev", d.getDeviceName());
                BridgeService.requestPermission(ctx, usb, d);
                return;
            }
            try {
                Otter o = Otter.open(usb, d, filesDir);
                if (!running) {                  // Stop arrived during the (slow) open
                    o.close();
                    return;
                }
                applyModel(o);
                otter = o;
                String fw = o.probeVersion();
                if (fw != null) prefs.edit().putString("sub_fw", fw).putString("sub_sn", o.usbSerial.isEmpty() ? o.versionSn : o.usbSerial).apply();
                BLog.log("scanner_ready", "pid", d.getProductId(), "fw", fw, "serial", o.usbSerial);
                if (imu != null) imu.ensureReader();
            } catch (Exception e) {
                BLog.log("usb_open_failed", "dev", d.getDeviceName(), "err", String.valueOf(e));
                otter = null;
            }
        }
    }

    public void onDetached(UsbDevice d) {
        synchronized (usbLock) {
            Otter o = otter;
            if (o != null && (d == null || o.dev.getDeviceName().equals(d.getDeviceName()))) closeOtter("detached");
        }
    }

    private void closeOtter(String why) {
        Otter o = otter;
        otter = null;
        if (o == null) return;
        BLog.log("scanner_closing", "why", why);
        for (RtspServer s : rtsp) s.stopAllStreams();
        if (imu != null) imu.stopReader();
        // let capture threads notice, cancel their URBs and release their nodes before closing the fd
        for (int vc : new java.util.TreeSet<>(nodeLocks.keySet())) {
            Semaphore s = nodeLock(vc);
            try {
                if (s.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) s.release();
            } catch (InterruptedException ignored) {
            }
        }
        o.close();
        useDefaultModel();
    }

    // ------------------------------------------------------------------ scanner model
    /** stream_map.json "model_pid": treat the scanner as this PID's scanners.json entry (e.g. "0x06DA"). */
    private int modelPidOverride() {
        String v = streamMap().optString("model_pid", "");
        if (v.isEmpty()) return -1;
        try {
            return Integer.decode(v.trim());
        } catch (NumberFormatException e) {
            BLog.log("stream_map_error", "key", "model_pid", "value", v);
            return -1;
        }
    }

    /** No scanner attached: the Otter model with its table interfaces (what the test pattern serves). */
    private void useDefaultModel() {
        ScannerModel m = ScannerModel.forPid(ScannerModel.loadDocument(ctx, filesDir), Otter.PID_OTTER);
        Map<Integer, String> names = new java.util.TreeMap<>();
        Map<Integer, Integer> vsCount = new java.util.TreeMap<>();
        if (m.interfaces != null) for (int[] v : m.interfaces.values()) {
            names.put(v[0], "");
            vsCount.put(v[0], Math.max(v[1] + 1, vsCount.containsKey(v[0]) ? vsCount.get(v[0]) : 1));
        }
        model = m;
        roles = m.resolveRoles(names, vsCount, null);
        roleSource = Collections.emptyMap();
    }

    /** Picks the scanner's model by USB PID and maps its UVC interfaces to stream roles. */
    private void applyModel(Otter o) {
        JSONObject doc = ScannerModel.loadDocument(ctx, filesDir);
        int forced = modelPidOverride();
        ScannerModel m = ScannerModel.forPid(doc, forced >= 0 ? forced : o.dev.getProductId());
        Map<Integer, String> names = new java.util.TreeMap<>();
        for (int i = 0; i < o.dev.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface ui = o.dev.getInterface(i);
            if (ui.getAlternateSetting() == 0 && ui.getInterfaceClass() == 14 && ui.getInterfaceSubclass() == 1)
                names.put(ui.getId(), ui.getName() == null ? "" : ui.getName());   // UVC VideoControl
        }
        Map<Integer, Integer> vsCount = new java.util.TreeMap<>();
        for (int vc : names.keySet()) {
            UsbDesc.Vc d = o.desc.vcs.get(vc);
            int n = 0;
            if (d != null) for (int vs : d.vsIfaces) if (o.desc.vss.containsKey(vs)) n++;
            vsCount.put(vc, Math.max(n, 1));
        }
        Map<String, String> how = new java.util.LinkedHashMap<>();
        List<Role> r = m.resolveRoles(names, vsCount, how);
        o.model = m;
        synchronized (rleParams) { System.arraycopy(m.rleParams, 0, rleParams, 0, 5); }
        model = m;
        roles = r;
        roleSource = how;
        BLog.log("scanner_model", "pid", ScannerModel.normPid(o.dev.getProductId()), "model_pid", m.key, "name", m.name,
                "status", m.status, "registry", m.source, "notes", m.notes.isEmpty() ? null : m.notes);
        BLog.log("scanner_roles", "roles", r.toString(), "source", how.toString(), "interfaces", names.toString(),
                "vs_per_vc", vsCount.toString());
        if (!"verified".equals(m.status)) dash.event("Scanner model " + m.summary() + ": not verified, streams may be wrong");
        if (r.isEmpty()) dash.event("No stream roles for this scanner: set \"interfaces\" in files/scanners.json");
    }

    /** First role served on this RTSP port (8556 is shared by ir and ir_l, which use the same node). */
    Role roleForPort(int port) {
        for (Role r : roles) if (r.port == port) return r;
        return null;
    }

    boolean isColorPort(int port) {
        Integer p = model.streamPorts.get("color");
        return p != null && p == port;
    }

    int rleThreshold() {
        synchronized (rleParams) { return rleParams[0]; }
    }

    int roleVc(String name) {
        for (Role r : roles) if (r.name.equals(name)) return r.vc;
        return -1;
    }

    public Semaphore nodeLock(int vc) {
        Semaphore s = nodeLocks.get(vc);
        if (s == null) {
            nodeLocks.putIfAbsent(vc, new Semaphore(1));
            s = nodeLocks.get(vc);
        }
        return s;
    }

    // ------------------------------------------------------------------ relay
    /** fb.FORWARDER: relay a HostProtocol frame to the scanner. */
    public byte[] forward(byte[] frame, int opcode) throws IOException {
        Otter o = otter;
        if (o == null || o.isClosed()) throw new IOException("scanner not attached");
        return o.transact(frame, opcode);
    }

    /** cameraServer sets 0x70 and 0x12 before IR streams; so do we. */
    public void scannerSetBool(int prop, int value) {
        byte[] frame = new byte[16];
        Hp.putU16(frame, 0, Hp.REQ_MAGIC);
        Hp.putU16(frame, 2, 4);
        Hp.putU16(frame, 4, 2);
        Hp.putU16(frame, 6, rnd.nextInt(1 << 15));
        Hp.putU32(frame, 8, prop);
        Hp.putU32(frame, 12, value);
        Object st;
        try {
            byte[] r = forward(frame, 2);
            st = r != null && r.length >= 10 ? (Object) Hp.u16(r, 8) : null;
        } catch (Exception e) {
            st = String.valueOf(e);
        }
        BLog.log("pre_stream_set", "prop", "0x" + Integer.toHexString(prop), "value", value, "status", st);
    }

    // ------------------------------------------------------------------ nodes / tables
    /** VS interface for a role, from the attached scanner's descriptors. */
    /** Debug: serve {@link TestPattern} frames while no scanner is attached (pref test_pattern). */
    volatile boolean testPattern;

    public UsbDesc.Vs vsFor(String name) {
        Otter o = otter;
        List<Role> rs = roles;
        if (o == null && testPattern) {
            for (Role r : rs) if (r.name.equals(name)) return TestPattern.vsFor(r.vc + r.vsIndex, r.type == ST_COLOR);
            return null;
        }
        if (o == null) return null;
        for (Role r : rs) if (r.name.equals(name)) {
            if (!o.desc.vcs.containsKey(r.vc)) return null;
            return o.desc.vsForVc(r.vc, r.vsIndex);
        }
        return null;
    }

    private List<Role> presentRoles() {
        List<Role> out = new ArrayList<>();
        for (Role r : roles) if (vsFor(r.name) != null) out.add(r);
        return out;
    }

    /** stream_map.json "imu" wins over the model. */
    boolean imuOn() {
        return streamMap().optBoolean("imu", model.imuPresent);
    }

    private static byte[] u16le(int a, int b) {
        byte[] o = new byte[4];
        Hp.putU16(o, 0, a);
        Hp.putU16(o, 2, b);
        return o;
    }

    private static void videoEntry(ByteArrayOutputStream body, int st, UsbDesc.Profile p, String obFcc) {
        byte[] e = new byte[18];
        Hp.putU16(e, 0, st);
        Hp.putU32(e, 2, p.width);
        Hp.putU32(e, 6, p.height);
        Hp.putU32(e, 10, p.fps);
        Hp.putU32(e, 14, obFourcc(obFcc));
        body.write(e, 0, 18);
    }

    /** 0xFC1 profile list: model fc1_order, advertise, ir_synth (if we can encode it), IMU. */
    byte[] profileListBlob() {
        ScannerModel m = model;
        Otter o = otter;
        String fw = o != null ? o.fwVersion : null;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        for (String stream : m.fc1Order) {
            UsbDesc.Vs vs = vsFor(stream);
            if (vs == null) continue;
            int st = typeOf(stream);
            List<String> allowed = m.advertise(stream);
            for (UsbDesc.Profile p : vs.profiles) {
                if (!allowed.contains(p.fourcc)) continue;
                ScannerModel.Fmt f = m.fmt(p.fourcc);
                if (f == null || !f.fc1) continue;
                videoEntry(body, st, p, f.ob);
                count++;
                if (isIr(st) && p.fourcc.equals("GREY")) {
                    for (String s : m.irSynth) {
                        ScannerModel.Fmt sf = m.fmt(s);
                        if (sf == null || !IR_ENCODERS.contains(s) || !m.synthAllowed(s, fw)) continue;
                        videoEntry(body, st, p, sf.ob);
                        count++;
                    }
                }
            }
        }
        if (imuOn()) {
            for (int[] a : m.accelProfiles) { byte[] e = new byte[18]; Hp.putU16(e, 0, ST_ACCEL); Hp.putU16(e, 2, a[0]); Hp.putU16(e, 4, a[1]); body.write(e, 0, 18); count++; }
            for (int[] g : m.gyroProfiles) { byte[] e = new byte[18]; Hp.putU16(e, 0, ST_GYRO); Hp.putU16(e, 2, g[0]); Hp.putU16(e, 4, g[1]); body.write(e, 0, 18); count++; }
        }
        byte[] b = body.toByteArray();
        byte[] out = Arrays.copyOf(u16le(18, count), 4 + b.length);
        System.arraycopy(b, 0, out, 4, b.length);
        return out;
    }

    byte[] portListBlob() {
        List<int[]> rows = new ArrayList<>();
        for (Role r : presentRoles()) rows.add(new int[]{0x12, r.type, r.port});
        rows.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
        if (imuOn()) rows.add(Math.min(3, rows.size()), new int[]{0x11, ST_ACCEL, model.imuPort});
        byte[] out = new byte[4 + 10 * rows.size()];
        Hp.putU16(out, 0, 10);
        Hp.putU16(out, 2, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Hp.putU32(out, 4 + 10 * i, rows.get(i)[0]);
            Hp.putU32(out, 8 + 10 * i, rows.get(i)[1]);
            Hp.putU16(out, 12 + 10 * i, rows.get(i)[2]);
        }
        return out;
    }

    /** ExtensionInfo JSON, in the official bridge's exact format (", " and ": " separators). */
    byte[] extensionInfo() {
        Otter o = otter;
        String subPid = String.valueOf(o != null ? o.dev.getProductId() : Otter.PID_OTTER);
        String subFw = o != null && o.fwVersion != null ? o.fwVersion : prefs.getString("sub_fw", "1.0.0");
        String subSn = o != null ? (o.usbSerial.isEmpty() && o.versionSn != null ? o.versionSn : o.usbSerial) : prefs.getString("sub_sn", "");
        // cameraServer sends DeviceInfo::name: the model's product_model, else the USB product string
        ScannerModel m = model;
        String productModel = m.productModel;
        if (productModel == null && o != null) productModel = o.dev.getProductName();
        if (productModel == null) productModel = m.name;
        String[][] kv = {{"SubDevicePid", subPid}, {"SubFwVer", subFw}, {"ProductModel", productModel},
                {"WifiHandleDevicePid", String.valueOf(bridgePid)}, {"WifiHandleFwVer", bridgeVersion},
                {"WifiHandleSSID", wifiSsid}, {"SubDeviceSN", subSn == null ? "" : subSn},
                {"SubDeviceConnectionType", "USB3.0"}, {"WifiSecurityVersion", "1.0.0"}};
        StringBuilder sb = new StringBuilder("{\"ExtensionInfo\": {");
        for (int i = 0; i < kv.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(BLog.jsonString(kv[i][0])).append(": ").append(BLog.jsonString(kv[i][1]));
        }
        sb.append("}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Raw blobs served via ops 0x11/0x12/0x13, rebuilt per control connection. */
    Map<Integer, byte[]> rawBlobs() {
        Map<Integer, byte[]> m = new HashMap<>();
        m.put(Hp.PROP_EXTENSION_INFO, extensionInfo());
        m.put(0xFC1, profileListBlob());
        m.put(0xFCB, portListBlob());
        return m;
    }

    // ------------------------------------------------------------------ bridge-owned props
    static final int PROP_SCANNER_ATTACHED = 111, PROP_DEVICE_STATUS = 0x91, PROP_WIFI_SECURITY = 0x42E;

    byte[] bridgeProps(int op, int reqId, long prop, byte[] payload) {
        if (op == Hp.OP_GET_PROPERTY && prop == PROP_SCANNER_ATTACHED) {
            // cameraServer answers the bridge PID 0x0C01; 0 means "device is disconnected"
            int cur = otter != null ? 0x0C01 : 0;
            BLog.log("bridge_prop", "prop", prop, "cur", cur);
            return Hp.reply(op, reqId, Hp.fiveInts(cur, 0x0C01, 0, 0x0C01, 1), 0);
        }
        if (op == Hp.OP_GET_PROPERTY && prop == PROP_DEVICE_STATUS) {
            BLog.log("bridge_prop", "prop", prop, "cur", 1);
            return Hp.reply(op, reqId, Hp.fiveInts(1, 1, 0, 1, 1), 0);
        }
        if ((op == Hp.OP_GET_STRUCT || op == 27) && prop == PROP_WIFI_SECURITY) {
            BLog.log("bridge_prop", "prop", prop, "struct", "wifi_security");
            return Hp.reply(op, reqId, wifiSecurityStruct(), 0);
        }
        if ((op == 4 || op == 28) && prop == PROP_WIFI_SECURITY) {
            byte[] data = Arrays.copyOfRange(payload, Math.min(4, payload.length), payload.length);
            if (op == 28 && data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0)
                data = Arrays.copyOfRange(data, 4, data.length);   // v1.1 carries an extra u32 first
            setWifiSecurity(data);
            return Hp.reply(op, reqId);
        }
        if (prop == PROP_WIFI_SECURITY) {
            BLog.log("bridge_prop_unhandled", "prop", prop, "opcode", op, "payload", Hp.hex(payload, 48));
            return Hp.reply(op, reqId);
        }
        if (prop == PROP_RLE_PARAMS && model.irSynth.contains("RLE ")) {   // the official bridge answers this struct itselfc
            synchronized (rleParams) {
                if (op == Hp.OP_GET_STRUCT || op == 27) {
                    BLog.log("bridge_prop", "prop", prop, "struct", "rle_params", "vals", Arrays.toString(rleParams));
                    byte[] d = new byte[20];
                    for (int i = 0; i < 5; i++) Hp.putU32(d, 4 * i, rleParams[i]);
                    return Hp.reply(op, reqId, d, 0);
                }
                if (op == 4 || op == 28) {
                    int off = 4 + (op == 28 && payload.length >= 28 ? 4 : 0);   // v1.1 carries an extra u32 first
                    if (payload.length >= off + 20) for (int i = 0; i < 5; i++) rleParams[i] = Hp.u32(payload, off + 4 * i);
                    BLog.log("bridge_prop_set", "prop", prop, "struct", "rle_params", "vals", Arrays.toString(rleParams));
                    return Hp.reply(op, reqId);
                }
            }
        }
        return null;
    }

    private JSONObject wifiSettings() {
        JSONObject d = new JSONObject();
        try {
            // Default of the Scan Bridge Wi-Fi settings struct (prop 0x42E) that Creality Scan reads and writes.
            // Stored in wifi.json only; never used for a real network.
            d.put("password", "diybridge01");
            d.put("authMode", "WPA2PSK");
            d.put("encrypType", "AES");
            File f = new File(filesDir, "wifi.json");
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = in.read(b);
                    JSONObject j = new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
                    for (String k : new String[]{"password", "authMode", "encrypType"}) if (j.has(k)) d.put(k, j.getString(k));
                }
            }
        } catch (Exception ignored) {
        }
        return d;
    }

    /** password[63] @0, authMode[32] @0x3f, encrypType[32] @0x5f, ssid[128] @0x7f, 32 zero bytes. */
    byte[] wifiSecurityStruct() {
        JSONObject w = wifiSettings();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Hp.fixedUtf8(w.optString("password"), 63), 0, 63);
        o.write(Hp.fixedUtf8(w.optString("authMode"), 32), 0, 32);
        o.write(Hp.fixedUtf8(w.optString("encrypType"), 32), 0, 32);
        o.write(Hp.fixedUtf8(wifiSsid, 128), 0, 128);
        o.write(new byte[32], 0, 32);
        return o.toByteArray();
    }

    private void setWifiSecurity(byte[] data) {
        JSONObject w = wifiSettings();
        try {
            String pw = Hp.cstr(data, 0, 63);
            w.put("password", pw);
            String auth = data.length >= 0x5f ? Hp.cstr(data, 0x3f, 32) : "";
            if (!auth.isEmpty()) w.put("authMode", auth);
            String enc = data.length >= 0x7f ? Hp.cstr(data, 0x5f, 32) : "";
            if (!enc.isEmpty()) w.put("encrypType", enc);
            try (FileOutputStream out = new FileOutputStream(new File(filesDir, "wifi.json"))) {
                out.write(w.toString().getBytes(StandardCharsets.UTF_8));
            }
            BLog.log("wifi_password_set", "length", pw.length(), "auth", auth.isEmpty() ? null : auth);
        } catch (Exception e) {
            BLog.log("wifi_password_set_error", "err", String.valueOf(e));
        }
    }

    /** The saved "Run streaming server on" choice: p2p | wifi | eth | local, or "" (automatic). */
    public String savedAdvertise() {
        String v = streamMap().optString("advertise", "");
        return isChoice(v) ? v : "";
    }

    static boolean isChoice(String v) {
        return v.equals("p2p") || v.equals("wifi") || v.equals("eth") || v.equals("local");
    }

    /**
     * "local" ("This phone only"): only apps on this phone (the CrealityScan app) can discover and use the bridge.
     * Discovery answers only requests from the phone's own addresses and every server refuses other peers.
     */
    boolean localOnly() {
        return "local".equals(lockedAdvertise);
    }

    /** True if a is loopback or one of this phone's own addresses (so the peer is an app on this phone). */
    static boolean isLocalAddress(java.net.InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;
        try {
            return java.net.NetworkInterface.getByInetAddress(a) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Servers call this on accept: in "This phone only" mode, peers from the network are refused. */
    boolean acceptsPeer(java.net.InetAddress a, String server) {
        if (!localOnly() || isLocalAddress(a)) return true;
        BLog.log("peer_refused", "server", server, "peer", a != null ? a.getHostAddress() : null, "why", "this phone only");
        return false;
    }

    /** Which networks are up right now (IPv4 address present): {p2p, eth, wifi}. */
    public static boolean[] networksUp() {
        boolean p2p = false, eth = false, wifi = false;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                boolean v4 = false;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getAddress() instanceof java.net.Inet4Address) v4 = true;
                if (!v4) continue;
                String k = Gvcp.kind(ni.getName());
                if ("p2p".equals(k)) p2p = true;
                if ("eth".equals(k)) eth = true;
                if ("wifi".equals(k)) wifi = true;
            }
        } catch (Exception ignored) {
        }
        return new boolean[]{p2p, eth, wifi};
    }

    /**
     * The one network discovery is answered on (a PC that saw the bridge on two would hang at Preview).
     * The saved choice wins while it is up; otherwise fall back Wi-Fi Direct, Ethernet, Wi-Fi, and return to the
     * saved one as soon as it comes back. A starting Wi-Fi Direct group counts as up so it isn't flapped away.
     */
    public static String resolveAdvertise(String saved, boolean p2pWanted, boolean[] up) {
        if ("local".equals(saved)) return "local";          // needs no network at all
        boolean p2p = up[0] || p2pWanted, eth = up[1], wifi = up[2];
        if ("p2p".equals(saved) && p2p) return "p2p";
        if ("eth".equals(saved) && eth) return "eth";
        if ("wifi".equals(saved) && wifi) return "wifi";
        return p2p ? "p2p" : eth ? "eth" : wifi ? "wifi" : (saved.isEmpty() ? "wifi" : saved);
    }

    public String effectiveAdvertise() {
        return resolveAdvertise(savedAdvertise(), prefs.getBoolean("p2p", false), networksUp());
    }

    /** files/stream_map.json (local overrides), re-read on every call. */
    JSONObject streamMap() {
        File f = new File(filesDir, "stream_map.json");
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(b);
            return new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (Exception e) {
            BLog.log("stream_map_error", "err", String.valueOf(e));
            return new JSONObject();
        }
    }

    /** Wi-Fi Direct group on/off (persisted). */
    public void setP2p(boolean on) {
        prefs.edit().putBoolean("p2p", on).apply();
        BLog.log("p2p_toggle", "on", on);
        if (!running) return;
        if (on) p2p.start(); else p2p.stop();
    }

    public boolean p2pWanted() {
        return prefs.getBoolean("p2p", false);
    }

    /** Set one key in files/stream_map.json, keeping the others. */
    synchronized void putStreamMap(String key, Object value) {
        JSONObject j = streamMap();
        try {
            if (value == null) j.remove(key); else j.put(key, value);
            try (FileOutputStream out = new FileOutputStream(new File(filesDir, "stream_map.json"))) {
                out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
            BLog.log("stream_map_set", "key", key, "value", String.valueOf(value));
        } catch (Exception e) {
            BLog.log("stream_map_error", "err", String.valueOf(e));
        }
    }

    // ------------------------------------------------------------------ status for the UI
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(running ? "RUNNING" : "STOPPED");
        Otter o = otter;
        if (o != null) sb.append("\nScanner: ").append(String.format("%04x:%04x", o.dev.getVendorId(), o.dev.getProductId()))
                .append(" fw ").append(o.fwVersion).append(" sn ").append(o.usbSerial).append(" (").append(o.dev.getDeviceName()).append(')')
                .append("\nModel: ").append(model.summary()).append("\nRoles: ").append(roles);
        else {
            UsbDevice d = findDevice();
            sb.append("\nScanner: ").append(d == null ? "not attached" : (usb.hasPermission(d) ? "attached, not open" : "attached, no permission"));
        }
        sb.append("\nWi-Fi Direct: ").append(p2p.statusLine());
        if (gvcp != null) sb.append("\nGVCP: ").append(gvcp.acks).append(" acks, ").append(gvcp.clientCount()).append(" client(s)");
        if (host != null) sb.append("\nControl 8090: ").append(host.clients.get()).append(" conn, ").append(host.requests.get()).append(" req");
        for (RtspServer s : rtsp) sb.append("\nRTSP ").append(s.port).append(": ").append(s.statusLine());
        if (imu != null) sb.append("\nIMU 8900: ").append(imu.statusLine());
        return sb.toString();
    }
}
