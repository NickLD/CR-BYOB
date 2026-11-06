package com.crbyob.bridge;

import java.util.ArrayDeque;

/**
 * "Adjust automatically" for the mirror: runs it at up to the chosen frame rate and 3D resolution, steps down
 * when the mirror stutters and back up when it's smooth.
 *
 * Creality Scan sends mirror frames unevenly while it processes a fast scan (an Otter at 30 fps: a 30 fps mirror
 * was frozen 6-9% of the time, a 15 fps one 2.5-3.4%), so the measure is how much of the last 20 s froze.
 * - More than 5% frozen: step down, at most every 10 s. The resolution goes first (100 -> 75 -> 50%), the frame
 *   rate only once the resolution is at 50% (30 -> 20 -> 15 -> 10): smooth motion matters more than detail for
 *   aiming the scanner. Each resolution change reconnects the mirror (Creality Scan reads the 3D texture size
 *   only at init).
 * - 2-5%: hold.
 * - 2% or less for 30 s: frame rate one step up, towards the ceiling. Resolution back up only once the frame rate
 *   is at its ceiling, after 2 smooth minutes (it reconnects).
 * Pure logic (no Android), driven by MirrorSession every stats interval (~5 s).
 */
final class MirrorAuto {
    static final int[] FPS = {30, 20, 15, 10};
    static final int[] RES = {100, 75, 50};
    static final int NONE = 0, FPS_CHANGED = 1, RES_CHANGED = 2;

    static final long WINDOW_MS = 20_000, DOWN_FROZEN_MS = 1000, HOLD_FROZEN_MS = 400;
    static final long DOWN_EVERY_MS = 10_000, FPS_UP_AFTER_MS = 30_000, RES_UP_AFTER_MS = 120_000;

    int fps, res;                  // in effect now; 0 until reset
    private int maxFps, maxRes;
    private long changedAt, goodSince;
    private final ArrayDeque<long[]> win = new ArrayDeque<>();   // {time, worst freeze ms of that interval}

    /** Start over at the ceiling. */
    void reset(int maxFps, int maxRes, long now) {
        this.maxFps = maxFps;
        this.maxRes = maxRes;
        fps = maxFps;
        res = maxRes;
        changed(now);
    }

    /** Not measuring (viewer hidden, not connected): forget the window, restart the smooth timer. */
    void pause(long now) {
        win.clear();
        goodSince = now;
    }

    /** One stats interval: worstFreezeMs is the longest any one view froze in it. Returns what changed. */
    int step(long worstFreezeMs, long now) {
        win.addLast(new long[]{now, worstFreezeMs});
        while (!win.isEmpty() && now - win.peekFirst()[0] > WINDOW_MS) win.pollFirst();
        long frozen = 0;
        for (long[] w : win) frozen += w[1];
        if (frozen > DOWN_FROZEN_MS && win.size() >= 3) {
            goodSince = now;
            if (now - changedAt < DOWN_EVERY_MS) return NONE;
            int r = next(RES, res, -1, 0);
            if (r > 0) { res = r; changed(now); return RES_CHANGED; }
            int f = next(FPS, fps, -1, 0);
            if (f > 0) { fps = f; changed(now); return FPS_CHANGED; }
            return NONE;
        }
        if (frozen > HOLD_FROZEN_MS) {
            goodSince = now;
            return NONE;
        }
        long smooth = now - goodSince, since = now - changedAt;
        if (fps < maxFps && smooth >= FPS_UP_AFTER_MS && since >= FPS_UP_AFTER_MS) {
            fps = next(FPS, fps, +1, maxFps);
            changed(now);
            return FPS_CHANGED;
        }
        if (fps >= maxFps && res < maxRes && smooth >= RES_UP_AFTER_MS && since >= RES_UP_AFTER_MS) {
            res = next(RES, res, +1, maxRes);
            changed(now);
            return RES_CHANGED;
        }
        return NONE;
    }

    private void changed(long now) {
        changedAt = now;
        goodSince = now;
        win.clear();
    }

    /**
     * The neighbouring step of cur in a descending ladder: dir -1 = next lower (0 if none), +1 = next higher but
     * not above max (cur if none).
     */
    private static int next(int[] ladder, int cur, int dir, int max) {
        if (dir < 0) {
            for (int v : ladder) if (v < cur) return v;
            return 0;
        }
        int best = cur;
        for (int v : ladder) if (v > cur && v <= max && (best == cur || v < best)) best = v;
        return best;
    }
}
