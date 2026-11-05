package com.crbyob.bridge;

import java.nio.charset.StandardCharsets;

/** HostProtocol framing + little byte helpers. */
public final class Hp {
    public static final int REQ_MAGIC = 0x4D47;   // "GM"
    public static final int RSP_MAGIC = 0x4252;   // "RB"

    public static final int OP_GET_PROPERTY = 1;
    public static final int OP_SET_PROPERTY = 2;
    public static final int OP_GET_STRUCT = 3;
    public static final int OP_INIT_READ_RAW = 0x11;
    public static final int OP_READ_RAW = 0x12;
    public static final int OP_FINISH_READ_RAW = 0x13;

    public static final int PROP_VERSION_INFO = 1000;
    public static final int PROP_EXTENSION_INFO = 0xFC9;

    private Hp() {}

    /** RB reply: <H magic, H halfWords, H opcode, H reqId> + <H status> + data, padded to even length. */
    public static byte[] reply(int opcode, int reqId, byte[] data, int status) {
        int body = 2 + (data == null ? 0 : data.length);
        if ((body & 1) != 0) body++;
        byte[] r = new byte[8 + body];
        putU16(r, 0, RSP_MAGIC);
        putU16(r, 2, body / 2);
        putU16(r, 4, opcode);
        putU16(r, 6, reqId);
        putU16(r, 8, status);
        if (data != null) System.arraycopy(data, 0, r, 10, data.length);
        return r;
    }

    public static byte[] reply(int opcode, int reqId) {
        return reply(opcode, reqId, null, 0);
    }

    public static byte[] error(int opcode, int reqId, int status) {
        return reply(opcode, reqId, null, status);
    }

    public static byte[] fiveInts(int a, int b, int c, int d, int e) {
        byte[] o = new byte[20];
        putU32(o, 0, a); putU32(o, 4, b); putU32(o, 8, c); putU32(o, 12, d); putU32(o, 16, e);
        return o;
    }

    public static int statusOf(byte[] reply) {
        return reply != null && reply.length >= 10 ? u16(reply, 8) : -1;
    }

    // ---- little-endian
    public static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    public static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }
    public static void putU16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    public static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }
    public static void putU64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >> (8 * i));
    }
    // ---- big-endian
    public static int be16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }
    public static void putBe16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
    public static void putBe32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v;
    }

    /** Fixed-size ASCII field: at most n-1 chars, zero padded to n. */
    public static byte[] fixedStr(String s, int n) {
        byte[] out = new byte[n];
        if (s == null) return out;
        int len = Math.min(s.length(), n - 1);
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            out[i] = (byte) (c < 0x80 ? c : '?');
        }
        return out;
    }

    /** UTF-8 fixed field (the Wi-Fi security struct's string fields). */
    public static byte[] fixedUtf8(String s, int n) {
        byte[] out = new byte[n];
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, out, 0, Math.min(b.length, n - 1));
        return out;
    }

    public static String cstr(byte[] b, int off, int max) {
        int end = off;
        int lim = Math.min(b.length, off + max);
        while (end < lim && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b, int off, int len) {
        if (b == null) return "";
        len = Math.max(0, Math.min(len, b.length - off));
        char[] c = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            c[2 * i] = HEX[v >> 4];
            c[2 * i + 1] = HEX[v & 15];
        }
        return new String(c);
    }

    public static String hex(byte[] b, int max) {
        return b == null ? "" : hex(b, 0, Math.min(max, b.length));
    }
}
