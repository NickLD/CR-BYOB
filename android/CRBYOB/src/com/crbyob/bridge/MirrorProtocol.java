package com.crbyob.bridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Labels and texts for Creality Scan's mirror protocol: assets/mirror-protocol.json (from models/), which holds
 * its enums with Creality Scan's own English text. Unknown values fall back to the enum name, then the number.
 */
final class MirrorProtocol {
    private static JSONObject doc;

    private MirrorProtocol() {}

    static synchronized JSONObject doc(Context c) {
        if (doc == null) {
            try (InputStream in = c.getAssets().open("mirror-protocol.json")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                doc = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                BLog.log("mirror_protocol_error", "err", String.valueOf(e));
                doc = new JSONObject();
            }
        }
        return doc;
    }

    /** Text of an entry in a {number: {name, text}} table (notification_content, dialog_title, ...). */
    static String text(Context c, String table, int value) {
        JSONObject t = doc(c).optJSONObject(table);
        JSONObject e = t != null ? t.optJSONObject(String.valueOf(value)) : null;
        if (e == null) return value == 0 ? "" : "#" + value;
        return e.optString("text", e.optString("name", "#" + value));
    }

    /** Label of a scan setting value, e.g. scanValue(c, 4, 3) = "Large". */
    static String scanValue(Context c, int key, int value) {
        JSONObject p = doc(c).optJSONObject("scan_params");
        JSONObject k = p != null ? p.optJSONObject(String.valueOf(key)) : null;
        JSONObject v = k != null ? k.optJSONObject("values") : null;
        return v != null ? v.optString(String.valueOf(value), "#" + value) : "#" + value;
    }

    /** Creality Scan's description of a scan setting value ("Medium: 250x250x250 ~ 500x500x500mm³"), or "". */
    static String scanTip(Context c, int key, int value) {
        JSONObject p = doc(c).optJSONObject("scan_params");
        JSONObject k = p != null ? p.optJSONObject(String.valueOf(key)) : null;
        JSONObject t = k != null ? k.optJSONObject("tips") : null;
        return t != null ? t.optString(String.valueOf(value), "").replace(" \n ", "\n") : "";
    }

    /** Fills "{0}", "{1}"... from args (dialog TitleArgs/ContentArgs) and turns the table's "<br>" into new lines. */
    static String format(String text, org.json.JSONArray args) {
        String s = text == null ? "" : text.replace("<br>", "\n");
        if (args != null) for (int i = 0; i < args.length(); i++) s = s.replace("{" + i + "}", args.optString(i));
        return s;
    }
}
