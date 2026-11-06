package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MirrorAutoTest {

    /** Feed one 5 s interval per call starting at t; returns the time after the last one. */
    private static long feed(MirrorAuto a, long t, int intervals, long freezeMs, int[] lastResult) {
        for (int i = 0; i < intervals; i++) {
            t += 5000;
            lastResult[0] = a.step(freezeMs, t);
        }
        return t;
    }

    @Test public void startsAtTheCeiling() {
        MirrorAuto a = new MirrorAuto();
        a.reset(20, 75, 0);
        assertEquals(20, a.fps);
        assertEquals(75, a.res);
    }

    @Test public void stuttersStepResolutionFirstThenFrameRate() {
        MirrorAuto a = new MirrorAuto();
        a.reset(30, 100, 0);
        int[] r = new int[1];
        long t = 0;
        // 500 ms frozen per 5 s (10%): resolution 75, 50, then frame rate 20, 15, 10
        int[][] expect = {{30, 75}, {30, 50}, {20, 50}, {15, 50}, {10, 50}};
        for (int[] e : expect) {
            t = feed(a, t, 3, 500, r);           // 3 intervals: window full enough, and >= 10 s since the last change
            assertEquals(e[0], a.fps);
            assertEquals(e[1], a.res);
        }
        assertEquals(MirrorAuto.FPS_CHANGED, r[0]);
        t = feed(a, t, 3, 500, r);               // nothing lower left
        assertEquals(10, a.fps);
        assertEquals(50, a.res);
    }

    @Test public void holdsInTheMiddleBand() {
        MirrorAuto a = new MirrorAuto();
        a.reset(30, 100, 0);
        int[] r = new int[1];
        long t = feed(a, 0, 3, 500, r);          // down to 75%
        assertEquals(75, a.res);
        feed(a, t, 30, 150, r);                  // 600 ms per 20 s = 3%: neither up nor down for 150 s
        assertEquals(75, a.res);
        assertEquals(30, a.fps);
    }

    @Test public void recoversFrameRateAfter30sAndResolutionAfter2Minutes() {
        MirrorAuto a = new MirrorAuto();
        a.reset(20, 100, 0);
        int[] r = new int[1];
        long t = 0;
        for (int i = 0; i < 4; i++) t = feed(a, t, 3, 500, r);   // res 75 -> 50, then 20 -> 15 -> 10 fps
        assertEquals(10, a.fps);
        assertEquals(50, a.res);
        t = feed(a, t, 6, 0, r);                 // 30 s smooth: frame rate recovers first
        assertEquals(15, a.fps);
        t = feed(a, t, 6, 0, r);
        assertEquals(20, a.fps);                 // the ceiling, not 30
        t = feed(a, t, 6, 0, r);
        assertEquals(20, a.fps);
        assertEquals(50, a.res);                 // resolution waits for 2 minutes
        t = feed(a, t, 18, 0, r);
        assertEquals(75, a.res);
        assertEquals(MirrorAuto.RES_CHANGED, r[0]);
        t = feed(a, t, 24, 0, r);
        assertEquals(100, a.res);
    }

    @Test public void pauseRestartsTheSmoothTimer() {
        MirrorAuto a = new MirrorAuto();
        a.reset(30, 100, 0);
        int[] r = new int[1];
        long t = 0;
        for (int i = 0; i < 3; i++) t = feed(a, t, 3, 500, r);   // res 75 -> 50, then 20 fps
        assertEquals(20, a.fps);
        t = feed(a, t, 5, 0, r);                 // 25 s smooth
        a.pause(t);
        t = feed(a, t, 5, 0, r);                 // 25 s more, but counted from the pause
        assertEquals(20, a.fps);
        t = feed(a, t, 1, 0, r);
        assertEquals(30, a.fps);
    }
}
