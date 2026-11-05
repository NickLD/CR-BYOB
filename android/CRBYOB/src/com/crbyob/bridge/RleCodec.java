package com.crbyob.bridge;

/**
 * Y8 -> "RLE" (OB_FMT_RLE, cameraServer's BlockStdDevCompression V3) for Raptor / Sermoon IR streams.
 *   header 256 B LE: u32 0xAABBCCEE, u32 0xFF020000, u16 1, u16 W, u16 H, u16 1, zeros
 *   body: 8x8 blocks in raster order; a run of N flat blocks = count(N) + N mean bytes,
 *         each detailed block = 0x00 + 64 raw bytes; count = N/255 x 0xFF then N%255.
 *   flat: (Q - (S*S >> 6)) / 63 <= threshold (S = sum, Q = sum of squares; mean = S >> 6).
 * Frames shorter than padTo are zero-padded (cameraServer pads to 1500). Pure Java: no Android imports.
 */
final class RleCodec implements IrEncoder {
    static final int MAGIC = 0xAABBCCEE, VERSION = 0xFF020000, HEADER = 256;

    final int w, h, padTo;
    volatile int threshold;
    private final int bw, bh;
    private final byte[] out, means;
    long encodes, encodeNs, bytes;

    RleCodec(int w, int h, int threshold, int padTo) {
        this.w = w;
        this.h = h;
        this.threshold = threshold;
        this.padTo = padTo;
        bw = w / 8;
        bh = h / 8;
        int blocks = bw * bh;
        out = new byte[Math.max(padTo, HEADER + blocks * 65 + blocks / 255 + 16)];
        means = new byte[blocks];
        putU32(0, MAGIC);
        putU32(4, VERSION);
        putU16(8, 1);
        putU16(10, w);
        putU16(12, h);
        putU16(14, 1);
    }

    private void putU32(int o, int v) { out[o] = (byte) v; out[o + 1] = (byte) (v >> 8); out[o + 2] = (byte) (v >> 16); out[o + 3] = (byte) (v >>> 24); }
    private void putU16(int o, int v) { out[o] = (byte) v; out[o + 1] = (byte) (v >> 8); }

    @Override public byte[] buf() { return out; }
    @Override public long[] stats() { return new long[]{encodes, bytes, encodeNs}; }

    private int flushRun(int p, int n) {
        for (int k = n / 255; k > 0; k--) out[p++] = (byte) 0xFF;
        out[p++] = (byte) (n % 255);
        System.arraycopy(means, 0, out, p, n);
        return p + n;
    }

    /** Encodes the Y8 frame (len >= w*h); the result is buf()[0..return value). */
    @Override public int encode(byte[] y) {
        long t0 = System.nanoTime();
        int thr = threshold, p = HEADER, run = 0;
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int base = by * 8 * w + bx * 8, s = 0, q = 0;
                for (int r = 0; r < 8; r++) {
                    int o = base + r * w;
                    for (int c = 0; c < 8; c++) {
                        int v = y[o + c] & 0xFF;
                        s += v;
                        q += v * v;
                    }
                }
                if ((q - ((s * s) >> 6)) / 63 <= thr) {     // flat: one mean byte in the current run
                    means[run++] = (byte) (s >> 6);
                } else {                                     // detailed: end the run, then 00 + 64 raw bytes
                    if (run > 0) { p = flushRun(p, run); run = 0; }
                    out[p++] = 0;
                    for (int r = 0; r < 8; r++) {
                        System.arraycopy(y, base + r * w, out, p, 8);
                        p += 8;
                    }
                }
            }
        }
        if (run > 0) p = flushRun(p, run);
        if (p < padTo) {
            java.util.Arrays.fill(out, p, padTo, (byte) 0);
            p = padTo;
        }
        encodeNs += System.nanoTime() - t0;
        encodes++;
        bytes += p;
        return p;
    }
}
