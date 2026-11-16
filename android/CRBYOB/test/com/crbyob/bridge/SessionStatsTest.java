package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionStatsTest {
    @Test public void bytesSurviveTheTotalFallingOnAReconnect() {
        SessionStats ss = new SessionStats();
        // #42: 200 bytes streamed, the scanner reopens (the RTSP servers' counts restart, IMU's 10 remain), 150 more
        long[] totals = {0, 100, 200, 10, 60, 160};
        long prev = 0;
        for (long t : totals) {
            ss.addSent(prev, t);
            prev = t;
        }
        assertEquals(350, ss.bytesNow - ss.bytesAtStart);
        assertEquals(350e-9, ss.gb(), 1e-15);
    }

    @Test public void aNewSessionStartsFromZero_notFromTheRunningTotal() {
        SessionStats ss = new SessionStats();
        ss.addSent(5_000, 6_000);    // first sample of a session that began mid-run
        ss.addSent(6_000, 6_500);
        assertEquals(500, ss.bytesNow - ss.bytesAtStart);
    }

    @Test public void detachesCountStreamingAndBatterySeparately() {
        SessionStats ss = new SessionStats();
        ss.countDetach(true, true);
        ss.countDetach(false, false);
        assertEquals(2, ss.usbDetach.get());
        assertEquals(1, ss.detachStreaming.get());
        assertEquals(1, ss.brownouts.get());
    }
}
