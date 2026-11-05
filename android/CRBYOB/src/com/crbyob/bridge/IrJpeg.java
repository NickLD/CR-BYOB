package com.crbyob.bridge;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Y8 -> JPEG for the optional "ir_jpeg_over_y8" mode. The Y plane is wrapped as NV21 with neutral
 * chroma (U = V = 128) and compressed with YuvImage.compressToJpeg (the platform's libjpeg-turbo).
 * Buffers are reused per session.
 */
final class IrJpeg implements IrEncoder {
    static final class Out extends ByteArrayOutputStream {
        Out(int n) { super(n); }
        byte[] buf() { return buf; }
        int len() { return count; }
    }

    final int w, h, quality;
    private final byte[] nv21;
    private final Rect rect;
    final Out out = new Out(512 * 1024);
    long encodes, encodeNs, bytes;

    IrJpeg(int w, int h, int quality) {
        this.w = w;
        this.h = h;
        this.quality = Math.max(1, Math.min(100, quality));
        nv21 = new byte[w * h * 3 / 2];
        Arrays.fill(nv21, w * h, nv21.length, (byte) 128);
        rect = new Rect(0, 0, w, h);
    }

    @Override public byte[] buf() { return out.buf(); }
    @Override public long[] stats() { return new long[]{encodes, bytes, encodeNs}; }

    /** Encodes the Y8 frame; the JPEG is in out.buf()[0..return value). */
    @Override public int encode(byte[] y) {
        long t = System.nanoTime();
        System.arraycopy(y, 0, nv21, 0, w * h);
        out.reset();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(rect, quality, out);
        encodeNs += System.nanoTime() - t;
        encodes++;
        bytes += out.len();
        return out.len();
    }
}
