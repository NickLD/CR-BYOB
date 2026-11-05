package com.crbyob.bridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Camera controls, props 2000..2027, answered from the UVC
 * camera terminal / processing unit of the matching function instead of V4L2. Value semantics
 * follow uvcvideo's V4L2 value mappings, which is what Creality Scan expects.
 */
final class CameraControls {
    static final int CT = 0, PU = 1;
    static final int K_INT = 0, K_BOOL = 1, K_AEMODE = 2, K_MENU = 3;

    static final class Ctl {
        final String role; final int unit, selector, size, bit, kind; final boolean signed; final String name;
        Ctl(String role, String name, int unit, int selector, int size, boolean signed, int bit, int kind) {
            this.role = role; this.name = name; this.unit = unit; this.selector = selector; this.size = size;
            this.signed = signed; this.bit = bit; this.kind = kind;
        }
    }

    // (unit, selector, size, signed, bmControls bit, kind) per V4L2 control, as uvcvideo maps them
    private static Ctl c(String role, String n) {
        switch (n) {
            case "EXPOSURE_AUTO":          return new Ctl(role, n, CT, 0x02, 1, false, 1, K_AEMODE);
            case "EXPOSURE_AUTO_PRIORITY": return new Ctl(role, n, CT, 0x03, 1, false, 2, K_BOOL);
            case "EXPOSURE_ABSOLUTE":      return new Ctl(role, n, CT, 0x04, 4, false, 3, K_INT);
            case "BACKLIGHT_COMPENSATION": return new Ctl(role, n, PU, 0x01, 2, false, 8, K_INT);
            case "BRIGHTNESS":             return new Ctl(role, n, PU, 0x02, 2, true, 0, K_INT);
            case "CONTRAST":               return new Ctl(role, n, PU, 0x03, 2, false, 1, K_INT);
            case "GAIN":                   return new Ctl(role, n, PU, 0x04, 2, false, 9, K_INT);
            case "POWER_LINE_FREQUENCY":   return new Ctl(role, n, PU, 0x05, 1, false, 10, K_MENU);
            case "HUE":                    return new Ctl(role, n, PU, 0x06, 2, true, 2, K_INT);
            case "SATURATION":             return new Ctl(role, n, PU, 0x07, 2, false, 3, K_INT);
            case "SHARPNESS":              return new Ctl(role, n, PU, 0x08, 2, false, 4, K_INT);
            case "GAMMA":                  return new Ctl(role, n, PU, 0x09, 2, false, 5, K_INT);
            case "WHITE_BALANCE_TEMPERATURE": return new Ctl(role, n, PU, 0x0A, 2, false, 6, K_INT);
            case "AUTO_WHITE_BALANCE":     return new Ctl(role, n, PU, 0x0B, 1, false, 12, K_BOOL);
            default: throw new IllegalArgumentException(n);
        }
    }

    static final Map<Integer, Ctl> PROPS = new HashMap<>();
    static {
        PROPS.put(2000, c("color", "EXPOSURE_AUTO"));
        PROPS.put(2001, c("color", "EXPOSURE_ABSOLUTE"));
        PROPS.put(2002, c("color", "GAIN"));
        PROPS.put(2003, c("color", "AUTO_WHITE_BALANCE"));
        PROPS.put(2004, c("color", "WHITE_BALANCE_TEMPERATURE"));
        PROPS.put(2005, c("color", "BRIGHTNESS"));
        PROPS.put(2006, c("color", "SHARPNESS"));
        PROPS.put(2008, c("color", "SATURATION"));
        PROPS.put(2009, c("color", "CONTRAST"));
        PROPS.put(2010, c("color", "GAMMA"));
        PROPS.put(2012, c("color", "EXPOSURE_AUTO_PRIORITY"));
        PROPS.put(2013, c("color", "BACKLIGHT_COMPENSATION"));
        PROPS.put(2014, c("color", "HUE"));
        PROPS.put(2015, c("color", "POWER_LINE_FREQUENCY"));
        PROPS.put(2016, c("depth", "EXPOSURE_AUTO"));
        PROPS.put(2017, c("depth", "EXPOSURE_ABSOLUTE"));
        PROPS.put(2018, c("depth", "GAIN"));
        PROPS.put(2025, c("ir", "EXPOSURE_AUTO"));
        PROPS.put(2026, c("ir", "EXPOSURE_ABSOLUTE"));
        PROPS.put(2027, c("ir", "GAIN"));
    }

    private static final int UVC_AE_MANUAL = 0x01, UVC_AE_APERTURE_PRIORITY = 0x08;

    private final Bridge bridge;
    private final Object lock = new Object();

    CameraControls(Bridge b) {
        this.bridge = b;
    }

    private int roleVc(String role) {
        return bridge.roleVc(role);
    }

    /** Reply for props we own, else null (caller forwards to the scanner). */
    byte[] handle(int op, int reqId, long prop, byte[] payload) {
        Ctl ctl = prop >= 0 && prop < 100000 ? PROPS.get((int) prop) : null;
        if (ctl == null || (op != Hp.OP_GET_PROPERTY && op != Hp.OP_SET_PROPERTY)) return null;
        Otter o = bridge.otter;
        UsbDesc.Vc vc = null;
        if (o != null) {
            vc = o.desc.vcs.get(roleVc(ctl.role));
            if (vc == null) {   // FALLBACK depth <-> ir
                String fb = ctl.role.equals("depth") ? "ir" : ctl.role.equals("ir") ? "depth" : null;
                if (fb != null) vc = o.desc.vcs.get(roleVc(fb));
            }
        }
        if (vc == null) return Hp.error(op, reqId, 2);
        int entity = ctl.unit == CT ? vc.ctId : vc.puId;
        int bm = ctl.unit == CT ? vc.ctControls : vc.puControls;
        String node = "VC" + vc.iface;
        if (entity < 0 || (bm & (1 << ctl.bit)) == 0) {
            BLog.log("ctrl_unsupported", "prop", prop, "node", node, "err", ctl.name + " not in bmControls");
            return Hp.error(op, reqId, 2);
        }
        synchronized (lock) {
            try {
                int def = read(o, ctl, Otter.GET_DEF, entity, vc.iface);
                int min, max, step;
                if (ctl.kind == K_INT) {
                    min = read(o, ctl, Otter.GET_MIN, entity, vc.iface);
                    max = read(o, ctl, Otter.GET_MAX, entity, vc.iface);
                    step = read(o, ctl, Otter.GET_RES, entity, vc.iface);
                } else if (ctl.kind == K_MENU) {
                    min = 0; max = vc.bcdUvc >= 0x0150 ? 3 : 2; step = 1;
                } else {
                    min = 0; max = 1; step = 1;
                }
                if (op == Hp.OP_GET_PROPERTY) {
                    int cur = read(o, ctl, Otter.GET_CUR, entity, vc.iface);
                    int[] vals;
                    if (ctl.kind == K_AEMODE) {
                        vals = new int[]{cur != UVC_AE_MANUAL ? 1 : 0, 1, 0, def != UVC_AE_MANUAL ? 1 : 0, 1};
                    } else {
                        vals = new int[]{cur, max, min, def, Math.max(step, 1)};
                    }
                    BLog.log("ctrl_get", "prop", prop, "node", node, "vals", vals);
                    return Hp.reply(op, reqId, Hp.fiveInts(vals[0], vals[1], vals[2], vals[3], vals[4]), 0);
                }
                int value = payload.length >= 8 ? Hp.u32(payload, 4) : 0;
                int v;
                if (ctl.kind == K_AEMODE) {
                    int autoMode = def != UVC_AE_MANUAL ? def : UVC_AE_APERTURE_PRIORITY;
                    v = value != 0 ? autoMode : UVC_AE_MANUAL;
                } else {
                    v = Math.min(Math.max(value, min), max);
                }
                byte[] b = new byte[ctl.size];
                for (int i = 0; i < ctl.size; i++) b[i] = (byte) (v >> (8 * i));
                int r = o.ctrlSet(ctl.selector, entity, vc.iface, b, ctl.size);
                if (r < 0) throw new Exception("SET_CUR failed");
                BLog.log("ctrl_set", "prop", prop, "node", node, "value", value, "uvc", v);
                return Hp.reply(op, reqId);
            } catch (Exception e) {
                BLog.log("ctrl_unsupported", "prop", prop, "node", node, "err", String.valueOf(e.getMessage()));
                return Hp.error(op, reqId, 2);
            }
        }
    }

    private static int read(Otter o, Ctl ctl, int req, int entity, int vcIface) throws Exception {
        byte[] b = new byte[ctl.size];
        int r = o.ctrlGet(req, ctl.selector, entity, vcIface, b, ctl.size);
        if (r < ctl.size) throw new Exception(ctl.name + " request 0x" + Integer.toHexString(req) + " failed (" + r + ")");
        int v = 0;
        for (int i = 0; i < ctl.size; i++) v |= (b[i] & 0xFF) << (8 * i);
        if (ctl.signed && ctl.size < 4) {
            int shift = 32 - 8 * ctl.size;
            v = (v << shift) >> shift;
        }
        return v;
    }
}
