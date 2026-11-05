package com.crbyob.bridge;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * The scanner on USB (named after the Otter, the first model; any Orbbec VID 0x2BC5 device is opened,
 * and per-model differences live in {@link ScannerModel}): claims every interface, finds the Orbbec command XU, relays HostProtocol
 * frames through it, offers UVC class requests for the camera
 * controls and runs the single URB reaper thread shared by all bulk/interrupt streams.
 */
public final class Otter {
    public static final int VID = 0x2BC5;
    public static final int PID_OTTER = 0x06DA;

    // UVC request codes
    public static final int SET_CUR = 0x01, GET_CUR = 0x81, GET_MIN = 0x82, GET_MAX = 0x83,
            GET_RES = 0x84, GET_LEN = 0x85, GET_INFO = 0x86, GET_DEF = 0x87;

    // Reply size the SDK's server expects per opcode (ObServerManager::handleRecvDataAndResponse)
    private static int replySize(int opcode) {
        switch (opcode) {
            case 3: case 5: case 27: return 0x200;
            case 18: case 25: case 30: return 0x400;
            default: return 0x40;
        }
    }

    // HostProtocol opcodes the relay forwards (the safety allow-list).
    private static final Set<Integer> SAFE_OPCODES = new HashSet<>(Arrays.asList(1, 2, 3, 5, 17, 18, 19, 25, 26, 27, 29, 30, 31));
    private static final long[][] SAFE_WRITES = {{28, 0x413}, {4, 1004}};

    /** Completion callback, always called on the reaper thread. */
    public interface Sink {
        void onComplete(Xfer x, int len);
    }

    public static final class Xfer {
        public UsbRequest req;
        public ByteBuffer buf;
        public Sink sink;
    }

    public final UsbDevice dev;
    public final UsbDeviceConnection conn;
    public final UsbDesc desc;
    public final String usbSerial;
    private final Map<Integer, UsbInterface> ifaces = new HashMap<>();
    private final File filesDir;
    private UsbDesc.Xu xu;
    private final TreeMap<Integer, Integer> xuLens = new TreeMap<>();
    private final Object xuLock = new Object();
    private volatile boolean closed;
    private Thread reaper;
    public volatile String fwVersion, versionSn, versionName;
    /** The scanner's scanners.json entry (set by Bridge.applyModel): extra relay opcodes, HID interface. */
    volatile ScannerModel model;

    private Otter(UsbDevice dev, UsbDeviceConnection conn, UsbDesc desc, File filesDir) {
        this.dev = dev;
        this.conn = conn;
        this.desc = desc;
        this.filesDir = filesDir;
        String s = null;
        try { s = dev.getSerialNumber(); } catch (Exception ignored) { }
        if (s == null) s = conn.getSerial();
        this.usbSerial = s == null ? "" : s;
    }

    public static boolean isOrbbec(UsbDevice d) {
        return d != null && d.getVendorId() == VID;
    }

    public static Otter open(UsbManager mgr, UsbDevice dev, File filesDir) throws IOException {
        UsbDeviceConnection c = mgr.openDevice(dev);
        if (c == null) throw new IOException("openDevice failed");
        byte[] raw = c.getRawDescriptors();
        if (raw == null) { c.close(); throw new IOException("no raw descriptors"); }
        UsbDesc d = UsbDesc.parse(raw);
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(filesDir, "otter_descriptors.bin"))) {
            f.write(raw);
        } catch (IOException ignored) {
        }
        Otter o = new Otter(dev, c, d, filesDir);
        try {
            o.init(raw.length);
        } catch (IOException e) {
            o.close();
            throw e;
        }
        return o;
    }

    private void init(int rawLen) throws IOException {
        BLog.log("usb_open", "dev", dev.getDeviceName(), "vid", String.format("%04x", dev.getVendorId()),
                "pid", String.format("%04x", dev.getProductId()), "serial", usbSerial, "desc_bytes", rawLen,
                "summary", desc.summary());
        for (UsbDesc.Vs vs : desc.vss.values()) {
            StringBuilder sb = new StringBuilder();
            for (UsbDesc.Profile p : vs.profiles) sb.append(p).append("; ");
            BLog.log("usb_vs_modes", "iface", vs.iface, "ep", String.format("%02x", vs.endpoint), "modes", sb.toString());
        }
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface ui = dev.getInterface(i);
            if (ui.getAlternateSetting() != 0) continue;
            ifaces.put(ui.getId(), ui);
            boolean ok = conn.claimInterface(ui, true);
            BLog.log("usb_claim", "iface", ui.getId(), "class", ui.getInterfaceClass(), "sub", ui.getInterfaceSubclass(),
                    "name", ui.getName(), "ok", ok);
        }
        reaper = new Thread(this::reapLoop, "usb-reaper");
        reaper.setPriority(Thread.MAX_PRIORITY);
        reaper.start();
        findXu();
    }

    public UsbInterface iface(int id) {
        return ifaces.get(id);
    }

    public UsbEndpoint endpoint(int ifaceId, int addr) {
        UsbInterface ui = ifaces.get(ifaceId);
        if (ui == null) return null;
        for (int i = 0; i < ui.getEndpointCount(); i++) if (ui.getEndpoint(i).getAddress() == addr) return ui.getEndpoint(i);
        return null;
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ XU
    private void findXu() throws IOException {
        for (UsbDesc.Vc vc : desc.vcs.values()) {
            for (UsbDesc.Xu x : vc.xus) {
                TreeMap<Integer, Integer> lens = new TreeMap<>();
                byte[] b = new byte[2];
                for (int sel = 1; sel <= 8; sel++) {
                    int r = conn.controlTransfer(0xA1, GET_LEN, sel << 8, (x.unitId << 8) | x.vcIface, b, 2, 500);
                    if (r == 2) lens.put(sel, Hp.u16(b, 0));
                }
                BLog.log("xu_found", "vc_iface", x.vcIface, "unit", x.unitId, "guid", x.guidStr(),
                        "controls", x.numControls, "lens", lens.toString());
                if (lens.containsValue(64) && lens.containsValue(512) && lens.containsValue(1024) && xu == null) {
                    xu = x;
                    xuLens.putAll(lens);
                }
            }
        }
        if (xu == null) throw new IOException("Orbbec command XU (64/512/1024-byte controls) not found");
        BLog.log("xu_using", "vc_iface", xu.vcIface, "unit", xu.unitId);
    }

    /** Smallest XU control that fits size (the SDK uses 64->sel2, 512->sel1, 1024->sel3). */
    private int selectorFor(int size) throws IOException {
        int best = -1, bestLen = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : xuLens.entrySet()) {
            if (e.getValue() >= size && e.getValue() < bestLen) { best = e.getKey(); bestLen = e.getValue(); }
        }
        if (best < 0) throw new IOException("no XU control can hold " + size + " bytes");
        return best;
    }

    /** XU.transact: SET_CUR the padded frame, then GET_CUR until the matching RB reply shows up. */
    private byte[] xuTransact(byte[] frame, int opcode) throws IOException {
        int reqId = Hp.u16(frame, 6);
        int sendSel = selectorFor(frame.length);
        int recvSel = selectorFor(replySize(opcode));
        int sendLen = xuLens.get(sendSel), recvLen = xuLens.get(recvSel);
        int wIndex = (xu.unitId << 8) | xu.vcIface;
        byte[] out = Arrays.copyOf(frame, sendLen);
        byte[] in = new byte[recvLen];
        synchronized (xuLock) {
            if (closed) throw new IOException("device closed");
            long t0 = System.nanoTime();
            int r = conn.controlTransfer(0x21, SET_CUR, sendSel << 8, wIndex, out, sendLen, 1000);
            if (r < 0) throw new IOException("XU SET_CUR failed (" + r + ")");
            int fails = 0;
            for (int i = 0; i < 100; i++) {
                r = conn.controlTransfer(0xA1, GET_CUR, recvSel << 8, wIndex, in, recvLen, 1000);
                if (r < 0) {
                    if (++fails >= 3) throw new IOException("XU GET_CUR failed (" + r + ")");
                } else if (r >= 8) {
                    int magic = Hp.u16(in, 0), half = Hp.u16(in, 2), rid = Hp.u16(in, 6);
                    if (magic == Hp.RSP_MAGIC && rid == reqId) {
                        SessionStats.xuRoundTrip(System.nanoTime() - t0);     // HostProtocol -> scanner reply time
                        return Arrays.copyOf(in, Math.min(r, 8 + half * 2));
                    }
                }
                try { Thread.sleep(1); } catch (InterruptedException e) { throw new IOException("interrupted"); }
            }
            BLog.log("xu_timeout", "opcode", opcode, "req_id", reqId, "last", Hp.hex(in, 16));
            return null;
        }
    }

    /** OtterLink.transact: safety filter, then the XU relay. Returns null on timeout. */
    public byte[] transact(byte[] frame, int opcode) throws IOException {
        int prop = frame.length >= 12 ? Hp.u32(frame, 8) : -1;
        if (!opcodeAllowed(opcode, prop)) {
            // e.g. a *bridge* firmware upgrade (file transfer) must never reach the scanner
            BLog.log("blocked_opcode", "opcode", opcode, "prop", prop, "head", Hp.hex(frame, 24));
            return Hp.error(opcode, Hp.u16(frame, 6), 2);
        }
        return xuTransact(frame, opcode);
    }

    /**
     * SAFE_OPCODES/SAFE_WRITES, the model's extra_opcodes/extra_writes (scanners.json), plus files/allow.json
     * ({"opcodes":[..],"writes":[[op,prop],..]}), re-read every call.
     */
    private boolean opcodeAllowed(int opcode, int prop) {
        if (SAFE_OPCODES.contains(opcode)) return true;
        for (long[] w : SAFE_WRITES) if (w[0] == opcode && w[1] == (prop & 0xFFFFFFFFL)) return true;
        ScannerModel m = model;
        if (m != null && m.opcodeAllowed(opcode, prop)) return true;
        File f = new File(filesDir, "allow.json");
        if (!f.exists()) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(b);
            JSONObject j = new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            JSONArray ops = j.optJSONArray("opcodes");
            if (ops != null) for (int i = 0; i < ops.length(); i++) if (ops.getInt(i) == opcode) return true;
            JSONArray wr = j.optJSONArray("writes");
            if (wr != null) for (int i = 0; i < wr.length(); i++) {
                JSONArray p = wr.getJSONArray(i);
                if (p.getInt(0) == opcode && p.getLong(1) == (prop & 0xFFFFFFFFL)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** Read OBVersionInfo (struct prop 1000) straight from the scanner. */
    public String probeVersion() {
        byte[] frame = new byte[12];
        Hp.putU16(frame, 0, Hp.REQ_MAGIC);
        Hp.putU16(frame, 2, 2);
        Hp.putU16(frame, 4, Hp.OP_GET_STRUCT);
        Hp.putU16(frame, 6, 1);
        Hp.putU32(frame, 8, Hp.PROP_VERSION_INFO);
        try {
            byte[] r = xuTransact(frame, Hp.OP_GET_STRUCT);
            if (r == null) { BLog.log("probe_failed", "why", "no reply"); return null; }
            int status = Hp.u16(r, 8);
            int n = r.length - 10;
            if (status == 0 && n >= 164) {
                fwVersion = Hp.cstr(r, 10, 16);
                versionSn = Hp.cstr(r, 10 + 80, 16);
                versionName = Hp.cstr(r, 110, 16);
                BLog.log("probe", "firmware", fwVersion, "hardware", Hp.cstr(r, 26, 16), "sdk", Hp.cstr(r, 42, 16),
                        "depthChip", Hp.cstr(r, 58, 16), "systemChip", Hp.cstr(r, 74, 16), "sn", versionSn,
                        "type", Hp.u32(r, 106), "name", Hp.cstr(r, 110, 16));
                return fwVersion;
            }
            BLog.log("probe_failed", "status", status, "raw", Hp.hex(r, 64));
        } catch (IOException e) {
            BLog.log("probe_failed", "err", e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------ UVC class requests
    /** GET_* on an entity control. Returns bytes read or <0. */
    public int ctrlGet(int req, int selector, int entity, int vcIface, byte[] buf, int len) {
        if (closed) return -1;
        return conn.controlTransfer(0xA1, req, selector << 8, (entity << 8) | vcIface, buf, len, 1000);
    }

    public int ctrlSet(int selector, int entity, int vcIface, byte[] buf, int len) {
        if (closed) return -1;
        return conn.controlTransfer(0x21, SET_CUR, selector << 8, (entity << 8) | vcIface, buf, len, 1000);
    }

    // ------------------------------------------------------------------ reaper
    private void reapLoop() {
        int nulls = 0;
        while (!closed) {
            UsbRequest r;
            try {
                r = conn.requestWait(250);
            } catch (TimeoutException e) {
                continue;
            } catch (Exception e) {
                if (closed) break;
                BLog.log("usb_reap_error", "err", String.valueOf(e));
                sleep(5);
                continue;
            }
            if (r == null) {
                if (++nulls % 1000 == 1 && !closed) BLog.log("usb_reap_null", "count", nulls);
                sleep(2);
                continue;
            }
            Object cd = r.getClientData();
            if (!(cd instanceof Xfer)) continue;
            Xfer x = (Xfer) cd;
            try {
                x.sink.onComplete(x, x.buf.position());
            } catch (Throwable t) {
                BLog.log("usb_sink_error", "err", String.valueOf(t));
            }
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    public void close() {
        if (closed) return;
        synchronized (xuLock) {
            closed = true;
        }
        try {
            if (reaper != null) reaper.join(1000);
        } catch (InterruptedException ignored) {
        }
        for (UsbInterface ui : ifaces.values()) {
            try { conn.releaseInterface(ui); } catch (Exception ignored) { }
        }
        try { conn.close(); } catch (Exception ignored) { }
        BLog.log("usb_closed", "dev", dev.getDeviceName());
    }
}
