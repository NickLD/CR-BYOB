package com.crbyob.bridge;

import java.util.Random;

/**
 * Debug-only stand-in for a scanner node (pref "test_pattern", set with the launch extra of the same
 * name). Lets the RTSP/node-lock/takeover path be exercised on a phone with no Otter attached: frames
 * have the requested size and rate, IR as a moving gradient, color as filler bytes the size of a
 * typical MJPEG frame. While test_pattern is on, GVCP is not started, so no PC discovers this bridge.
 */
final class TestPattern implements FrameSource {
    /** Synthetic stream descriptors for a role's VC (Otter: IR L = 2, IR R / depth = 0, color = 4). */
    static UsbDesc.Vs vsFor(int vc, boolean color) {
        UsbDesc.Vs vs = new UsbDesc.Vs();
        vs.iface = vc + 1;
        if (color) {
            vs.profiles.add(profile("MJPG", 1280, 800, 15));
            vs.profiles.add(profile("MJPG", 1280, 800, 30));
        } else {
            vs.profiles.add(profile("GREY", 1280, 800, 15));
            vs.profiles.add(profile("GREY", 1280, 800, 5));
        }
        return vs;
    }

    private static UsbDesc.Profile profile(String f, int w, int h, int fps) {
        UsbDesc.Profile p = new UsbDesc.Profile();
        p.fourcc = f;
        p.width = w;
        p.height = h;
        p.fps = fps;
        return p;
    }

    private final UsbDesc.Profile p;
    private final UvcCapture.Frame frame;
    private final long periodUs;
    private long next, frames;
    private volatile boolean closed;

    TestPattern(UsbDesc.Profile p) {
        this.p = p;
        boolean mjpg = p.fourcc.equals("MJPG");
        int size = mjpg ? 120_000 : p.width * p.height;
        frame = new UvcCapture.Frame(size);
        frame.len = size;
        if (mjpg) {
            new Random(1).nextBytes(frame.data);
            frame.data[0] = (byte) 0xFF; frame.data[1] = (byte) 0xD8;
            frame.data[size - 2] = (byte) 0xFF; frame.data[size - 1] = (byte) 0xD9;
        }
        periodUs = 1_000_000L / Math.max(1, p.fps);
    }

    @Override public void start() {
        next = System.nanoTime() / 1000;
        BLog.log("test_pattern_start", "profile", p.key());
    }

    @Override public UvcCapture.Frame take(long timeoutMs) throws InterruptedException {
        if (closed) return null;
        long now = System.nanoTime() / 1000, wait = next - now;
        if (wait > timeoutMs * 1000) { Thread.sleep(timeoutMs); return null; }
        if (wait > 0) Thread.sleep(wait / 1000, (int) (wait % 1000) * 1000);
        if (closed) return null;
        next = Math.max(next + periodUs, System.nanoTime() / 1000 - periodUs);
        frames++;
        if (!p.fourcc.equals("MJPG")) {            // cheap moving pattern: one byte per 64 px
            byte v = (byte) (frames * 4);
            for (int i = 0; i < frame.len; i += 64) frame.data[i] = (byte) (v + (i >> 14));
        }
        frame.tsUs = System.nanoTime() / 1000;
        return frame;
    }

    @Override public void release(UvcCapture.Frame f) { }
    @Override public long framesCaptured() { return frames; }
    @Override public long framesDroppedQueue() { return 0; }
    @Override public long uvcErrors() { return 0; }
    @Override public void close() { closed = true; }
}
