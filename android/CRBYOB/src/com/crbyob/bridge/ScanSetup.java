package com.crbyob.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * New-scan settings for the mirror viewer, from what Creality Scan sends: SupportedScanConfig (what this PC and
 * scanner allow) and AppliedScanParams (the last settings, used as defaults). Produces the scan.setParams payload.
 *
 * SupportedScanConfig (Creality Scan 1.4.3, observed with an Otter):
 * {"infrared": {"face": {"fuse_modes": {"geo": {"accuracy": [fast, high], "fov": [normal, wide], "support": b}, ...},
 *               "support": b}, ..., "texture": [with, without], "scan_type": [...], "support": b},
 *  "blue_light": {...}, "pid": 1754}
 * Values on the wire are the Orbbec enums (models/mirror-protocol.json "scan_params"): projection 0 infrared /
 * 1 blue laser; scan mode 0 face, 1 small, 2 medium, 3 big, 4 human; fuse 0 geometry, 1 texture (RGBD),
 * 2 marker, 3 markerless; accuracy 0 fast, 1 high; FOV 0 normal, 1 wide; texture 0 with, 1 without.
 */
final class ScanSetup {
    static final int K_TEXTURE = 1, K_PROJECTION = 3, K_MODE = 4, K_ACCURACY = 5, K_FUSE = 6, K_FOV = 7, K_MATERIAL = 12;
    static final int FUSE_MARKER = 2;
    static final String[] PROJECTIONS = {"infrared", "blue_light"};
    static final String[] MODES = {"face", "obj_small", "obj_medium", "obj_big", "human"};
    static final String[] FUSES = {"geo", "rgbd", "marker", "markerless"};

    private final JSONObject cfg;
    int projection, mode, fuse, accuracy, fov, texture, material;   // the current choice

    /** supported: SupportedScanConfig (object or its JSON string); applied: AppliedScanParams, may be null. */
    ScanSetup(Object supported, JSONObject applied) throws Exception {
        cfg = supported instanceof JSONObject ? (JSONObject) supported : new JSONObject(String.valueOf(supported));
        JSONObject a = applied != null ? applied : new JSONObject();
        projection = a.optInt(String.valueOf(K_PROJECTION), 0);
        mode = a.optInt(String.valueOf(K_MODE), 1);
        fuse = a.optInt(String.valueOf(K_FUSE), 0);
        accuracy = a.optInt(String.valueOf(K_ACCURACY), 1);
        fov = a.optInt(String.valueOf(K_FOV), 0);
        texture = a.optInt(String.valueOf(K_TEXTURE), 0);
        material = a.optInt(String.valueOf(K_MATERIAL), 0);
        fix();
    }

    private JSONObject proj(int p) {
        JSONObject o = p >= 0 && p < PROJECTIONS.length ? cfg.optJSONObject(PROJECTIONS[p]) : null;
        return o != null && o.optBoolean("support", false) ? o : null;
    }

    private JSONObject modeObj(int p, int m) {
        JSONObject pr = proj(p);
        JSONObject o = pr != null && m >= 0 && m < MODES.length ? pr.optJSONObject(MODES[m]) : null;
        return o != null && o.optBoolean("support", false) ? o : null;
    }

    private JSONObject fuseObj(int p, int m, int f) {
        JSONObject mo = modeObj(p, m);
        JSONObject fm = mo != null ? mo.optJSONObject("fuse_modes") : null;
        JSONObject o = fm != null && f >= 0 && f < FUSES.length ? fm.optJSONObject(FUSES[f]) : null;
        return o != null && o.optBoolean("support", false) ? o : null;
    }

    private static List<Integer> flags(JSONArray a) {
        List<Integer> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) if (a.optBoolean(i, false)) out.add(i);
        return out;
    }

    List<Integer> projections() {
        List<Integer> out = new ArrayList<>();
        for (int p = 0; p < PROJECTIONS.length; p++) if (proj(p) != null && !modes(p).isEmpty()) out.add(p);
        return out;
    }

    private List<Integer> modes(int p) {
        List<Integer> out = new ArrayList<>();
        for (int m = 0; m < MODES.length; m++) if (modeObj(p, m) != null && !fuses(p, m).isEmpty()) out.add(m);
        return out;
    }

    private List<Integer> fuses(int p, int m) {
        List<Integer> out = new ArrayList<>();
        for (int f = 0; f < FUSES.length; f++) if (fuseObj(p, m, f) != null) out.add(f);
        return out;
    }

    List<Integer> modes() { return modes(projection); }
    List<Integer> fuses() { return fuses(projection, mode); }

    List<Integer> accuracies() {
        JSONObject f = fuseObj(projection, mode, fuse);
        return f != null ? flags(f.optJSONArray("accuracy")) : new ArrayList<>();
    }

    List<Integer> fovs() {
        JSONObject f = fuseObj(projection, mode, fuse);
        return f != null ? flags(f.optJSONArray("fov")) : new ArrayList<>();
    }

    /** Texture choices (0 with, 1 without) for the projection; only offered when both are allowed. */
    List<Integer> textures() {
        JSONObject pr = proj(projection);
        return pr != null ? flags(pr.optJSONArray("texture")) : new ArrayList<>();
    }

    /**
     * Object color (0 standard, 1 black, 2 white, 3 highly reflective): Creality Scan's new-scan page asks for it
     * in Marker mode. SupportedScanConfig doesn't list it; the four values are its OBScanObjectMaterial enum.
     */
    List<Integer> materials() {
        List<Integer> out = new ArrayList<>();
        if (fuse == FUSE_MARKER) for (int i = 0; i < 4; i++) out.add(i);
        return out;
    }

    /** After any change: move every later choice to a valid value, keeping it where it is still allowed. */
    void fix() {
        projection = keep(projections(), projection);
        mode = keep(modes(), mode);
        fuse = keep(fuses(), fuse);
        accuracy = keep(accuracies(), accuracy);
        fov = keep(fovs(), fov);
        texture = keep(textures(), texture);
        material = keep(materials(), material);
    }

    /** Prefer the last value (High for accuracy is the higher index, so a lost choice falls to the last one). */
    private static int keep(List<Integer> allowed, int v) {
        if (allowed.isEmpty() || allowed.contains(v)) return v;
        return allowed.get(allowed.size() - 1);
    }

    boolean valid() {
        return fuseObj(projection, mode, fuse) != null;
    }

    /** scan.setParams params: {"DicDatas": {key: value}}, the settings this sheet chooses. */
    JSONObject params() throws Exception {
        JSONObject d = new JSONObject();
        d.put(String.valueOf(K_PROJECTION), projection);
        d.put(String.valueOf(K_MODE), mode);
        d.put(String.valueOf(K_FUSE), fuse);
        if (!accuracies().isEmpty()) d.put(String.valueOf(K_ACCURACY), accuracy);
        if (!fovs().isEmpty()) d.put(String.valueOf(K_FOV), fov);
        if (!textures().isEmpty()) d.put(String.valueOf(K_TEXTURE), texture);
        if (!materials().isEmpty()) d.put(String.valueOf(K_MATERIAL), material);
        return new JSONObject().put("DicDatas", d);
    }
}
