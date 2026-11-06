package com.crbyob.bridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Per-scanner-model settings from scanners.json (repo models/scanners.json, bundled as an asset by
 * the Gradle build; files/scanners.json in the app's private dir is merged over it).
 * See docs/adding-a-scanner-model.md.
 */
final class ScannerModel {
    static final String ASSET = "scanners.json", LOCAL = "scanners.json";

    /** One V4L2-style fourcc: Orbbec fourcc, RTP encoding name, RTSP URL suffix, allowed in 0xFC1. */
    static final class Fmt {
        final String ob, rtp, suffix;
        final boolean fc1;
        Fmt(String ob, String rtp, String suffix, boolean fc1) { this.ob = ob; this.rtp = rtp; this.suffix = suffix; this.fc1 = fc1; }
    }

    final int pid;
    final String key, name, status, productModel, notes, source;
    final boolean known;
    /** stream -> {VC interface number, streaming index}, or null for "auto" (guess from interface names). */
    final Map<String, int[]> interfaces;
    final List<String> fc1Order = new ArrayList<>();
    final Map<String, List<String>> advertise = new LinkedHashMap<>();
    final List<String> irSynth = new ArrayList<>();
    final Map<String, Integer> irSynthMinFw = new LinkedHashMap<>();
    final int[] rleParams = {100, 60, 6, 15, 15};
    final int rlePadTo;
    final List<int[]> irPreStreamProps = new ArrayList<>();
    final int irJpegQuality;
    final int bridgeFps;      // Creality Scan's own IR fps behind a bridge ("Stock"), 0 = unknown (learned at runtime)
    final boolean imuPresent;
    final int hidInterface;                 // -1: first HID interface
    final List<int[]> accelProfiles = new ArrayList<>(), gyroProfiles = new ArrayList<>();
    final Set<Integer> extraOpcodes = new HashSet<>();
    final List<long[]> extraWrites = new ArrayList<>();
    final Map<String, Integer> streamPorts = new LinkedHashMap<>();
    final int imuPort;
    final Map<String, Fmt> formats = new LinkedHashMap<>();
    private final List<Object[]> autoRules = new ArrayList<>();   // {Pattern, List<String>}

    // ------------------------------------------------------------------ loading
    static String normPid(String k) {
        String s = k.trim().toLowerCase(Locale.US);
        int v = s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
        return normPid(v);
    }

    static String normPid(int pid) {
        return String.format(Locale.US, "0x%04X", pid & 0xFFFF);
    }

    /** Objects merge key by key; anything else in over replaces. */
    static JSONObject merge(JSONObject base, JSONObject over) throws Exception {
        JSONObject out = new JSONObject(base.toString());
        for (Iterator<String> it = over.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = over.get(k);
            Object b = out.opt(k);
            if (b instanceof JSONObject && v instanceof JSONObject) out.put(k, merge((JSONObject) b, (JSONObject) v));
            else out.put(k, v);
        }
        return out;
    }

    private static JSONObject normModels(JSONObject doc) throws Exception {
        JSONObject m = doc.optJSONObject("models");
        JSONObject n = new JSONObject();
        if (m != null) for (Iterator<String> it = m.keys(); it.hasNext(); ) {
            String k = it.next();
            n.put(normPid(k), m.get(k));
        }
        doc.put("models", n);
        return doc;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Bundled asset merged with files/scanners.json. Never throws: a broken override is logged and skipped. */
    static JSONObject loadDocument(Context ctx, File filesDir) {
        JSONObject doc;
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            doc = normModels(new JSONObject(readAll(in)));
            doc.put("_source", "asset:" + ASSET);
        } catch (Exception e) {
            BLog.log("scanner_models_error", "what", "asset", "err", String.valueOf(e));
            doc = new JSONObject();
            try { doc.put("models", new JSONObject()); } catch (Exception ignored) { }
        }
        File f = new File(filesDir, LOCAL);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                doc = merge(doc, normModels(new JSONObject(readAll(in))));
                doc.put("_local", f.getPath());
            } catch (Exception e) {
                BLog.log("scanner_models_error", "what", f.getPath(), "err", String.valueOf(e));
            }
        }
        return doc;
    }

    private static JSONObject modelDict(JSONObject doc, String key, Set<String> seen) throws Exception {
        JSONObject e = doc.getJSONObject("models").optJSONObject(key);
        if (e == null) return new JSONObject();
        String parent = e.optString("inherit", "");
        if (parent.isEmpty()) return e;
        parent = normPid(parent);
        if (!seen.add(key) || parent.equals(key) || seen.contains(parent)) throw new IllegalArgumentException("inherit cycle at " + key);
        JSONObject own = new JSONObject(e.toString());
        own.remove("inherit");
        return merge(modelDict(doc, parent, seen), own);
    }

    /** The model for this PID (defaults if it isn't listed). Falls back to built-in defaults on any error. */
    static ScannerModel forPid(JSONObject doc, int pid) {
        try {
            return new ScannerModel(doc, pid);
        } catch (Exception e) {
            BLog.log("scanner_models_error", "what", normPid(pid), "err", String.valueOf(e));
            try {
                return new ScannerModel(new JSONObject("{\"models\":{}}"), pid);
            } catch (Exception e2) {
                throw new IllegalStateException(e2);
            }
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static void pairs(JSONArray a, List<int[]> out) {
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.optJSONArray(i);
            if (p != null && p.length() >= 2) out.add(new int[]{p.optInt(0), p.optInt(1)});
        }
    }

    /** 'Y8' -> 'GREY', 'RLE' -> 'RLE ' (Orbbec format name -> V4L2 fourcc). */
    static String obNameToV4l2(String n) {
        String s = n.trim().toUpperCase(Locale.US);
        return s.equals("Y8") || s.equals("GREY") ? "GREY" : (s + "    ").substring(0, 4);
    }

    private ScannerModel(JSONObject doc, int pid) throws Exception {
        this.pid = pid;
        this.key = normPid(pid);
        JSONObject models = doc.optJSONObject("models");
        this.known = models != null && models.has(key);
        JSONObject defaults = doc.optJSONObject("defaults");
        JSONObject m = merge(defaults != null ? defaults : builtinDefaults(), known ? modelDict(doc, key, new HashSet<>()) : new JSONObject());
        this.source = doc.optString("_source", "built-in") + (doc.has("_local") ? " + " + doc.optString("_local") : "");
        this.name = m.isNull("name") ? "PID " + key : m.optString("name", "PID " + key);
        this.status = m.optString("status", "unknown");
        this.productModel = m.isNull("product_model") ? null : m.optString("product_model", null);
        this.notes = m.optString("notes", "");
        JSONObject ifs = m.optJSONObject("interfaces");
        if (ifs != null) {
            Map<String, int[]> map = new LinkedHashMap<>();
            for (Iterator<String> it = ifs.keys(); it.hasNext(); ) {
                String s = it.next();
                if (ifs.isNull(s)) continue;
                JSONArray a = ifs.optJSONArray(s);      // [vc, n]: n-th VideoStreaming interface of that VC
                map.put(s, a != null ? new int[]{a.getInt(0), a.length() > 1 ? a.getInt(1) : 0} : new int[]{ifs.getInt(s), 0});
            }
            this.interfaces = Collections.unmodifiableMap(map);
        } else {
            this.interfaces = null;
        }
        fc1Order.addAll(strings(m.optJSONArray("fc1_order")));
        JSONObject adv = m.optJSONObject("advertise");
        if (adv != null) for (Iterator<String> it = adv.keys(); it.hasNext(); ) {
            String k = it.next();
            advertise.put(k, strings(adv.optJSONArray(k)));
        }
        for (String s : strings(m.optJSONArray("ir_synth"))) irSynth.add(obNameToV4l2(s));
        JSONObject minFw = m.optJSONObject("ir_synth_min_fw");
        if (minFw != null) for (Iterator<String> it = minFw.keys(); it.hasNext(); ) {
            String k = it.next();
            irSynthMinFw.put(obNameToV4l2(k), minFw.getInt(k));
        }
        JSONObject rle = m.optJSONObject("rle");
        JSONArray rp = rle != null ? rle.optJSONArray("params") : null;
        if (rp != null) for (int i = 0; i < Math.min(5, rp.length()); i++) rleParams[i] = rp.getInt(i);
        this.rlePadTo = rle != null ? rle.optInt("pad_to", 1500) : 1500;
        pairs(m.optJSONArray("ir_pre_stream_props"), irPreStreamProps);
        this.irJpegQuality = m.optInt("ir_jpeg_quality", 98);
        this.bridgeFps = m.optInt("bridge_fps", 0);
        JSONObject imu = m.optJSONObject("imu");
        this.imuPresent = imu != null && imu.optBoolean("present", false);
        this.hidInterface = imu == null || imu.isNull("hid_interface") ? -1 : imu.optInt("hid_interface", -1);
        if (imu != null) {
            pairs(imu.optJSONArray("accel_profiles"), accelProfiles);
            pairs(imu.optJSONArray("gyro_profiles"), gyroProfiles);
        }
        JSONArray ops = m.optJSONArray("extra_opcodes");
        if (ops != null) for (int i = 0; i < ops.length(); i++) extraOpcodes.add(ops.getInt(i));
        JSONArray wr = m.optJSONArray("extra_writes");
        if (wr != null) for (int i = 0; i < wr.length(); i++) {
            JSONArray p = wr.getJSONArray(i);
            extraWrites.add(new long[]{p.getLong(0), p.getLong(1)});
        }
        JSONObject ports = doc.optJSONObject("stream_ports");
        if (ports == null) ports = new JSONObject("{\"ir\":8556,\"ir_l\":8556,\"ir_r\":8557,\"depth\":8554,\"color\":8888}");
        for (Iterator<String> it = ports.keys(); it.hasNext(); ) {
            String s = it.next();
            streamPorts.put(s, ports.getInt(s));
        }
        this.imuPort = doc.optInt("imu_port", 8900);
        JSONObject fm = doc.optJSONObject("formats");
        if (fm == null) fm = new JSONObject("{\"GREY\":{\"ob\":\"Y8  \",\"rtp\":\"OB_FMT_Y8\",\"suffix\":\"y8\"},"
                + "\"MJPG\":{\"ob\":\"MJPG\",\"rtp\":\"OB_FMT_MJPEG\",\"suffix\":\"mjpg\"}}");
        for (Iterator<String> it = fm.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject f = fm.getJSONObject(k);
            formats.put(k, new Fmt(f.getString("ob"), f.getString("rtp"), f.getString("suffix"), f.optBoolean("fc1", true)));
        }
        JSONArray rules = doc.optJSONArray("auto_interfaces");
        if (rules != null) for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            autoRules.add(new Object[]{Pattern.compile(r.getString("match"), Pattern.CASE_INSENSITIVE),
                    strings(r.optJSONArray("streams"))});
        }
    }

    /** Used only if the asset is missing: the Otter-compatible defaults of scanners.json. */
    private static JSONObject builtinDefaults() throws Exception {
        return new JSONObject("{\"fc1_order\":[\"ir_l\",\"ir_r\",\"color\",\"ir\",\"depth\"],"
                + "\"advertise\":{\"ir\":[\"GREY\"],\"color\":[\"MJPG\"],\"depth\":[]},"
                + "\"ir_pre_stream_props\":[[112,1],[18,1]],\"ir_synth\":[\"MJPG\"],"
                + "\"imu\":{\"present\":true,\"accel_profiles\":[[2,8],[1,8],[3,8],[4,8],[2,7],[1,7],[3,7],[4,7]],"
                + "\"gyro_profiles\":[[8,8],[7,8],[6,8],[8,7],[7,7],[6,7]]}}");
    }

    // ------------------------------------------------------------------ queries
    static boolean isIrStream(String s) {
        return s.equals("ir") || s.equals("ir_l") || s.equals("ir_r");
    }

    /** 'ir_l' -> 'ir' (the key used in advertise). */
    static String kind(String stream) {
        return isIrStream(stream) ? "ir" : stream;
    }

    List<String> advertise(String stream) {
        List<String> l = advertise.get(kind(stream));
        return l != null ? l : Collections.<String>emptyList();
    }

    Fmt fmt(String v4l2) {
        return formats.get(v4l2);
    }

    /** RTSP suffix -> V4L2 fourcc ("mjpeg" is accepted for "mjpg"). */
    String suffixToV4l2(String suffix) {
        String s = suffix.toLowerCase(Locale.US);
        if (s.equals("mjpeg")) s = "mjpg";
        for (Map.Entry<String, Fmt> e : formats.entrySet()) if (e.getValue().suffix.equals(s)) return e.getKey();
        return null;
    }

    String rtpName(String v4l2) {
        Fmt f = fmt(v4l2);
        return f != null ? f.rtp : "OB_FMT_" + v4l2.trim();
    }

    /** '1.3.8' -> 10308 (major*10000 + minor*100 + patch, as the SDK's Pioneer RLE gate 10107) or -1. */
    static int fwInt(String version) {
        if (version == null) return -1;
        java.util.regex.Matcher mm = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!mm.find()) return -1;
        return Integer.parseInt(mm.group(1)) * 10000 + Integer.parseInt(mm.group(2)) * 100 + Integer.parseInt(mm.group(3));
    }

    /** ir_synth_min_fw gate; unknown firmware with a gate set -> false. */
    boolean synthAllowed(String v4l2, String fwVersion) {
        Integer need = irSynthMinFw.get(v4l2);
        if (need == null) return true;
        int have = fwInt(fwVersion);
        return have >= 0 && have >= need;
    }

    boolean opcodeAllowed(int opcode, int prop) {
        if (extraOpcodes.contains(opcode)) return true;
        for (long[] w : extraWrites) if (w[0] == opcode && w[1] == (prop & 0xFFFFFFFFL)) return true;
        return false;
    }

    /**
     * Roles for the interfaces present. ifaceNames: VC interface number -> interface string.
     * vsCount: VC interface -> number of VideoStreaming interfaces it has (null: 1 each).
     * how (optional) receives stream -> "table" | "auto:<name>" for the log.
     */
    List<Bridge.Role> resolveRoles(Map<Integer, String> ifaceNames, Map<Integer, Integer> vsCount, Map<String, String> how) {
        List<Bridge.Role> out = new ArrayList<>();
        if (interfaces != null) {
            for (Map.Entry<String, int[]> e : interfaces.entrySet()) {
                Integer port = streamPorts.get(e.getKey());
                int vc = e.getValue()[0], idx = e.getValue()[1];
                if (port == null || !ifaceNames.containsKey(vc)) continue;
                int have = vsCount == null || !vsCount.containsKey(vc) ? 1 : vsCount.get(vc);
                if (idx >= have) continue;
                out.add(new Bridge.Role(e.getKey(), Bridge.typeOf(e.getKey()), vc, idx, port));
                if (how != null) how.put(e.getKey(), "table");
            }
            return out;
        }
        Set<String> taken = new HashSet<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(ifaceNames).entrySet()) {
            String n = e.getValue() == null ? "" : e.getValue();
            for (Object[] r : autoRules) {
                if (!((Pattern) r[0]).matcher(n).find()) continue;
                @SuppressWarnings("unchecked") List<String> streams = (List<String>) r[1];
                for (String s : streams) {
                    Integer port = streamPorts.get(s);
                    if (port == null || !taken.add(s)) continue;
                    out.add(new Bridge.Role(s, Bridge.typeOf(s), e.getKey(), 0, port));
                    if (how != null) how.put(s, "auto:" + n);
                }
                break;
            }
        }
        return out;
    }

    String summary() {
        return name + " [" + status + "] PID " + key + (known ? "" : " (not in scanners.json)");
    }
}
