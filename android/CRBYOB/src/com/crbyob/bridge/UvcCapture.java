package com.crbyob.bridge;

import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One UVC bulk capture (replaces v4l2.Capture): VS_PROBE/VS_COMMIT, several queued UsbRequests,
 * payload-header stripping and frame assembly following uvcvideo's bulk decoder
 * (uvc_video_decode_bulk/start/data/end).
 */
public final class UvcCapture implements Otter.Sink, FrameSource {
    public static final class Frame {
        public final byte[] data;
        public int len;
        public long tsUs;
        boolean err;
        /**
         * The frame's tag byte: the first byte of the SCR field of its first UVC payload header, -1 without SCR.
         * Orbbec scanners put per-frame data there instead of a clock (Otter IR: 0xA2 / 0xFF, color 0x3C), and the
         * Raptor marks each IR frame's light type with it [I] (ScannerModel.irLightTypes).
         */
        public int meta = -1;
        final byte[] firstHdr = new byte[HDR_KEEP], lastHdr = new byte[HDR_KEEP];
        int firstHdrLen, lastHdrLen;
        Frame(int cap) { data = new byte[cap]; }
    }

    /** Payload header bytes kept per frame, for the light type and the uvc_hdr log lines. */
    static final int HDR_KEEP = 64;

    /** Offset of the vendor bytes in a UVC payload header: after length, info, PTS (4) and SCR (6) when present. */
    static int vendorOffset(int info) {
        return 2 + ((info & 0x04) != 0 ? 4 : 0) + ((info & 0x08) != 0 ? 6 : 0);
    }

    /** First vendor byte of a payload header (h[0] = length, h[1] = info), or -1 if it has none. */
    static int vendorByte(byte[] h, int n) {
        if (n < 2) return -1;
        int hl = Math.min(h[0] & 0xFF, n), vo = vendorOffset(h[1] & 0xFF);
        return hl > vo ? h[vo] & 0xFF : -1;
    }

    /** First byte of a payload header's SCR field (after PTS when present), or -1 if it has no SCR. */
    static int tagByte(byte[] h, int n) {
        if (n < 2) return -1;
        int info = h[1] & 0xFF, hl = Math.min(h[0] & 0xFF, n), at = 2 + ((info & 0x04) != 0 ? 4 : 0);
        return (info & 0x08) != 0 && hl > at ? h[at] & 0xFF : -1;
    }

    private final int maxQueued;                 // finished frames waiting for the sender; pool = this + assembling + consumer

    /**
     * Frames the sender may fall behind before the oldest is dropped: about 250 ms of video (4 frames at 15 fps,
     * 8 at 30). A phone's USB Ethernet transmit path can pause for 50-250 ms. Measured on a Pixel 10 at 30 fps,
     * 133 ms of slack still dropped frames and set off decimation; 266 ms dropped none.
     */
    static int queueDepth(int fps) {
        return Math.max(2, (fps + 3) / 4);
    }
    private static final int URB_BUDGET = 4 << 20; // usbfs_memory_mb is 16 MB on stock kernels; 3 streams share it
    /**
     * Bytes kept queued per stream. A scanner's FIFO overflows when no request is waiting, and the lost chunk
     * breaks the whole frame. 16 x 12 KB (the Raptor's payload size) is under 2 ms at 1920x1200@50: on a
     * Pixel 8 Pro that arrived at 94% of the bytes but only 55% of the frames (issue #15). 1 MB is about 9 ms.
     */
    static final int IN_FLIGHT = 1 << 20;

    /** Requests to keep queued for transfers of xferSize: IN_FLIGHT worth, at least 16, within URB_BUDGET. */
    static int urbCount(int xferSize) {
        int n = Math.max(16, (IN_FLIGHT + xferSize - 1) / xferSize);
        return Math.max(2, Math.min(n, URB_BUDGET / xferSize));
    }

    private final Otter o;
    private final UsbDesc.Vs vs;
    public final UsbDesc.Profile p;
    private final String tag;
    private final int expected;          // w*h for GREY, 0 = any size
    private int maxPayload, xferSize, frameCap;

    private final ArrayDeque<Frame> free = new ArrayDeque<>();
    private final ArrayDeque<Frame> filled = new ArrayDeque<>();
    private final Object q = new Object();
    private Frame cur;
    private int lastFid = -1;
    private int hdrState;                // 0 = expecting a payload header, 1 = in payload, 2 = skipping payload
    private int savedInfo, payloadBytes;

    private final List<Otter.Xfer> xfers = new ArrayList<>();
    private int outstanding;
    private boolean stopping;
    private UsbEndpoint ep;

    // stats (reaper thread)
    private long urbs, bytes, frames, dropErr, dropSize, dropQueue, dropSync, badHdr, queueFail;
    private long statT0, lastStat;

    /** queued: frames the sender may fall behind (0 = {@link #queueDepth} for the profile's fps). */
    public UvcCapture(Otter o, UsbDesc.Vs vs, UsbDesc.Profile p, int queued) {
        this.o = o;
        this.vs = vs;
        this.p = p;
        this.maxQueued = queued > 0 ? queued : queueDepth(p.fps);
        this.tag = "VS" + vs.iface + " " + p.fourcc.trim() + p.width + "x" + p.height + "@" + p.fps;
        this.expected = p.fourcc.equals("GREY") ? p.width * p.height : 0;
    }

    public void start() throws IOException {
        UsbInterface ui = o.iface(vs.iface);
        if (ui == null) throw new IOException("VS interface " + vs.iface + " missing");
        ep = o.endpoint(vs.iface, vs.endpoint);
        if (ep == null) throw new IOException("VS endpoint " + Integer.toHexString(vs.endpoint) + " missing");
        commit();

        int mps = Math.max(ep.getMaxPacketSize(), 512);
        if (maxPayload <= 0) maxPayload = 512 * 1024;
        xferSize = ((maxPayload + mps - 1) / mps) * mps;
        if (xferSize > (2 << 20)) xferSize = 64 * 1024;   // huge payloads: spanning logic below handles it
        int n = urbCount(xferSize);

        if (expected > 0) frameCap = expected + 64 * 1024;   // a bit of slack so oversize frames are detectable
        else frameCap = Math.max(Math.max(p.maxFrameBuf, maxFrameSize), p.width * p.height * 2);
        for (int i = 0; i < maxQueued + 2; i++) free.add(new Frame(frameCap));

        statT0 = lastStat = System.nanoTime();
        synchronized (this) {
            for (int i = 0; i < n; i++) {
                Otter.Xfer x = new Otter.Xfer();
                x.req = new UsbRequest();
                if (!x.req.initialize(o.conn, ep)) throw new IOException("UsbRequest.initialize failed");
                x.buf = ByteBuffer.allocateDirect(xferSize);
                x.sink = this;
                x.req.setClientData(x);
                xfers.add(x);
                if (x.req.queue(x.buf)) outstanding++;
                else BLog.log("uvc_queue_failed", "stream", tag, "i", i);
            }
        }
        if (outstanding == 0) throw new IOException("could not queue any bulk request");
        BLog.log("uvc_start", "stream", tag, "fmt", p.formatIndex, "frm", p.frameIndex, "interval", p.interval,
                "max_payload", maxPayload, "max_frame", maxFrameSize, "xfer", xferSize, "urbs", outstanding,
                "frame_cap", frameCap, "queue", maxQueued);
    }

    private int maxFrameSize;

    private void commit() throws IOException {
        UsbDesc.Vc vc = o.desc.vcForVs(vs.iface);
        int bcd = vc != null ? vc.bcdUvc : 0x0110;
        int len = bcd >= 0x0150 ? 48 : bcd >= 0x0110 ? 34 : 26;
        byte[] probe = new byte[len];
        Hp.putU16(probe, 0, 1);                 // bmHint: dwFrameInterval fixed
        probe[2] = (byte) p.formatIndex;
        probe[3] = (byte) p.frameIndex;
        Hp.putU32(probe, 4, p.interval);
        int r = o.conn.controlTransfer(0x21, Otter.SET_CUR, 0x0100, vs.iface, probe, len, 1000);
        if (r < 0 && len != 26) {
            len = 26;
            r = o.conn.controlTransfer(0x21, Otter.SET_CUR, 0x0100, vs.iface, probe, len, 1000);
        }
        if (r < 0) throw new IOException("VS_PROBE SET_CUR failed");
        byte[] got = new byte[len];
        r = o.conn.controlTransfer(0xA1, Otter.GET_CUR, 0x0100, vs.iface, got, len, 1000);
        if (r < 26) throw new IOException("VS_PROBE GET_CUR failed (" + r + ")");
        int gl = r;
        r = o.conn.controlTransfer(0x21, Otter.SET_CUR, 0x0200, vs.iface, got, gl, 1000);
        if (r < 0) throw new IOException("VS_COMMIT SET_CUR failed");
        maxFrameSize = Hp.u32(got, 18);
        maxPayload = Hp.u32(got, 22);
        BLog.log("uvc_commit", "stream", tag, "probe_len", gl, "fmt", got[2] & 0xFF, "frm", got[3] & 0xFF,
                "interval", Hp.u32(got, 4), "max_frame", maxFrameSize, "max_payload", maxPayload, "hex", Hp.hex(got, gl));
    }

    // ------------------------------------------------------------------ reaper side
    @Override
    public void onComplete(Otter.Xfer x, int len) {
        synchronized (this) {
            if (stopping) {
                outstanding--;
                notifyAll();
                return;
            }
        }
        urbs++;
        bytes += len;
        process(x.buf, len);
        x.buf.clear();
        synchronized (this) {
            if (stopping) {
                outstanding--;
                notifyAll();
                return;
            }
            if (!x.req.queue(x.buf)) {
                outstanding--;
                queueFail++;
                BLog.log("uvc_requeue_failed", "stream", tag, "outstanding", outstanding);
            }
        }
        long now = System.nanoTime();
        if (now - lastStat > 5_000_000_000L) {
            lastStat = now;
            double dt = (now - statT0) / 1e9;
            BLog.log("uvc_stats", "stream", tag, "frames", frames, "fps", Math.round(frames / dt * 10) / 10.0,
                    "mbps", Math.round(bytes * 8 / dt / 1e5) / 10.0, "urbs", urbs, "drop_err", dropErr,
                    "drop_size", dropSize, "drop_queue", dropQueue, "drop_sync", dropSync, "bad_hdr", badHdr,
                    "queue_fail", queueFail, "tag", histogram(tagCounts), "vendor", histogram(vendorCounts));
        }
    }

    /** uvc_video_decode_bulk */
    private void process(ByteBuffer b, int n) {
        int off = 0;
        if (hdrState == 0) {
            if (n == 0) return;
            int hl = n >= 2 ? b.get(0) & 0xFF : 0;
            int info = n >= 2 ? b.get(1) & 0xFF : 0;
            if (hl < 2 || hl > n) {
                badHdr++;
                hdrState = 2;
            } else if (decodeStart(info)) {
                hdrState = 1;
                savedInfo = info;
                off = hl;
                if (cur != null) {                  // keep the frame's first and latest payload headers
                    int k = Math.min(hl, HDR_KEEP);
                    b.limit(n);
                    b.position(0);
                    if (cur.len == 0 && cur.firstHdrLen == 0) {
                        b.get(cur.firstHdr, 0, k);
                        cur.firstHdrLen = k;
                        cur.meta = tagByte(cur.firstHdr, k);
                        b.position(0);
                    }
                    b.get(cur.lastHdr, 0, k);
                    cur.lastHdrLen = k;
                }
            } else {
                hdrState = 2;
            }
        }
        payloadBytes += n;
        if (hdrState == 1 && cur != null && n > off) {
            int dl = n - off;
            if (cur.len + dl > cur.data.length) {
                cur.err = true;                     // "Frame complete (overflow)"
                dl = cur.data.length - cur.len;
            }
            if (dl > 0) {
                b.limit(n);
                b.position(off);
                b.get(cur.data, cur.len, dl);
                cur.len += dl;
            }
        }
        // payload ends with a short transfer or when it reaches dwMaxPayloadTransferSize
        if (n < xferSize || payloadBytes >= maxPayload) {
            if (hdrState == 1) decodeEnd(savedInfo);
            hdrState = 0;
            payloadBytes = 0;
        }
    }

    /** uvc_video_decode_start; false = drop this payload. */
    private boolean decodeStart(int info) {
        int fid = info & 0x01;
        if (cur == null) {
            if (fid == lastFid) {           // out of sync: wait for the FID to toggle
                dropSync++;
                return false;
            }
            cur = obtain();
        }
        if (fid != lastFid && cur.len != 0) {   // FID toggled: previous frame complete
            finish();
            cur = obtain();
        }
        lastFid = fid;
        if ((info & 0x40) != 0) cur.err = true;     // error bit
        return true;
    }

    private void decodeEnd(int info) {
        if ((info & 0x02) != 0 && cur != null && cur.len != 0) finish();   // EOF
    }

    private Frame obtain() {
        Frame f;
        synchronized (q) {
            f = free.pollFirst();
            if (f == null) {
                f = filled.pollFirst();       // consumer too slow: drop the oldest queued frame
                if (f != null) dropQueue++;
            }
        }
        if (f == null) f = new Frame(frameCap);
        f.len = 0;
        f.err = false;
        f.meta = -1;
        f.firstHdrLen = f.lastHdrLen = 0;
        f.tsUs = System.nanoTime() / 1000;
        return f;
    }

    private void finish() {
        Frame f = cur;
        cur = null;
        if (f.err) {
            dropErr++;
            recycle(f);
            return;
        }
        if (expected > 0 ? f.len != expected : f.len == 0) {
            dropSize++;
            recycle(f);
            return;
        }
        frames++;
        noteHeaders(f);
        synchronized (q) {
            while (filled.size() >= maxQueued) {
                free.addLast(filled.pollFirst());
                dropQueue++;
            }
            filled.addLast(f);
            q.notifyAll();
        }
    }

    // per uvc_stats window: tag bytes (SCR byte 0) and bytes after the SCR, of each frame's first payload header
    // (index 256 = none)
    private final int[] tagCounts = new int[257], vendorCounts = new int[257];
    private int hdrLogged;

    /**
     * Logs the payload headers of the first frames (uvc_hdr) and counts their tag and vendor bytes for uvc_stats,
     * so a log shows where a scanner puts per-frame data such as the Raptor's light type.
     */
    private void noteHeaders(Frame f) {
        int tag = f.meta, vend = vendorByte(f.firstHdr, f.firstHdrLen);
        tagCounts[tag < 0 ? 256 : tag]++;
        vendorCounts[vend < 0 ? 256 : vend]++;
        if (hdrLogged < 6) {
            hdrLogged++;
            BLog.log("uvc_hdr", "stream", tag, "frame", frames, "len", f.len, "first", Hp.hex(f.firstHdr, f.firstHdrLen),
                    "last", Hp.hex(f.lastHdr, f.lastHdrLen), "tag", tag, "vendor", vend);
        }
    }

    /** "a2:15 b1:15" for a counts array (index 256 = "none"), then cleared. */
    static String histogram(int[] c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(i == 256 ? "none" : String.format(java.util.Locale.US, "%02x", i)).append(':').append(c[i]);
            c[i] = 0;
        }
        return sb.toString();
    }

    private void recycle(Frame f) {
        synchronized (q) {
            free.addLast(f);
        }
    }

    // ------------------------------------------------------------------ consumer side
    /** Next complete frame or null after timeoutMs. Give it back with release(). */
    public long framesCaptured() { return frames; }
    public long framesDroppedQueue() { return dropQueue; }
    /** UVC-level problems: frames with the error bit, bad payload headers, wrong-size frames. */
    public long uvcErrors() { return dropErr + badHdr + dropSize; }

    public Frame take(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (q) {
            while (filled.isEmpty()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0 || stopping || o.isClosed()) return null;
                q.wait(left);
            }
            return filled.pollFirst();
        }
    }

    public void release(Frame f) {
        if (f != null) recycle(f);
    }

    public void close() {
        synchronized (this) {
            if (stopping) return;
            stopping = true;
        }
        synchronized (q) { q.notifyAll(); }
        for (Otter.Xfer x : xfers) {
            try { x.req.cancel(); } catch (Exception ignored) { }
        }
        long end = System.currentTimeMillis() + 2000;
        synchronized (this) {
            while (outstanding > 0 && !o.isClosed()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { wait(Math.min(left, 100)); } catch (InterruptedException e) { break; }
            }
        }
        int left = outstanding;
        if (left == 0 || o.isClosed()) {
            for (Otter.Xfer x : xfers) {
                try { x.req.close(); } catch (Exception ignored) { }
            }
        }
        if (!o.isClosed()) {
            // uvcvideo stops a bulk stream with CLEAR_FEATURE(ENDPOINT_HALT) on the streaming endpoint;
            // SET_INTERFACE(alt 0) afterwards resets the data toggle/sequence on both sides.
            int r1 = -1;
            boolean r2 = false;
            try {
                r1 = o.conn.controlTransfer(0x02, 0x01, 0, vs.endpoint, null, 0, 500);
                UsbInterface ui = o.iface(vs.iface);
                if (ui != null) r2 = o.conn.setInterface(ui);
            } catch (Exception e) {
                BLog.log("uvc_stop_error", "stream", tag, "err", String.valueOf(e));
            }
            BLog.log("uvc_stop", "stream", tag, "frames", frames, "urbs_left", left, "clear_halt", r1, "set_iface", r2);
        } else {
            BLog.log("uvc_stop", "stream", tag, "frames", frames, "device", "closed");
        }
    }
}
