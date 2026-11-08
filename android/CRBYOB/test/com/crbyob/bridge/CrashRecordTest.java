package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The crash record: what survives a crash, that nothing private does, and how the report tells it. */
public class CrashRecordTest {
    private static final long T0 = 1_762_000_000_000L;   // app start
    private static final long MIN = 60_000L;

    private static Dash.Snap streamingOnP2p() {
        Dash.Snap s = new Dash.Snap();
        s.running = true;
        s.hero = Dash.STREAMING;
        s.advertise = "p2p";
        s.fpsMode = "max";
        s.modelName = "CR-Scan Otter";
        s.scannerModel = "CR-Scan Otter";
        s.scannerFw = "1.2.2";
        s.scannerSn = "BCA000000X1";
        s.scanner = true;
        s.usbSuper = true;
        s.p2pFreq = 5180;
        s.p2pSsid = "DIRECT-SB-CRBYOB-6C79";
        s.p2pPass = "secretpass1";
        s.staConnected = true;
        s.staSsid = "MyHomeNetwork";
        s.soc = "Google Tensor G5";
        return s;
    }

    private static SessionStats streamed() {
        SessionStats ss = new SessionStats();
        ss.startMs = T0 + 2 * MIN;
        ss.streamMs = 8 * MIN;
        ss.streamingMsPer[0] = 8 * MIN;
        ss.sent[0] = 13_488;
        ss.captured[0] = 13_500;
        ss.reqFps[0] = 30;
        ss.poweredMs = 10 * MIN;
        ss.usbDetach.set(1);
        ss.detachStreaming.set(1);
        ss.charger = "USB-C PD or hub";
        for (int i = 0; i < 50; i++) ss.frameAge.add(20_000);
        return ss;
    }

    @Test
    public void scrubsAddressesAndSecrets() {
        List<String> secrets = Arrays.asList("MyHomeNetwork", "DIRECT-SB-CRBYOB-6C79", null, "ab");
        assertEquals("PC connected (<IP> via Wi-Fi Direct)",
                CrashRecord.scrub("PC connected (192.168.49.224 via Wi-Fi Direct)", secrets));
        assertEquals("Joined <hidden>", CrashRecord.scrub("Joined MyHomeNetwork", secrets));
        assertEquals("IR dropped to half rate", CrashRecord.scrub("IR dropped to half rate", secrets));
        assertEquals("fw 1.2.2", CrashRecord.scrub("fw 1.2.2", secrets));   // a version is not an address
    }

    @Test
    public void errorNamesTheAppsFramesNotTheMessage() {
        Exception cause = new java.net.SocketException("connect to 192.168.10.5 failed");
        IllegalStateException e = new IllegalStateException("stream for MyHomeNetwork", cause);
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 437),
                new StackTraceElement("com.crbyob.bridge.RtspServer", "run", "RtspServer.java", 512),
                new StackTraceElement("com.crbyob.bridge.Bridge", "start", "Bridge.java", 200)});
        String err = CrashRecord.error(e);
        assertEquals("IllegalStateException at RtspServer.run:512 ← Bridge.start:200 (cause: SocketException)", err);
        assertNull(CrashRecord.error(null));
    }

    @Test
    public void aRunSurvivesTheRoundTripWithoutSecrets() throws Exception {
        Dash.Snap s = streamingOnP2p();
        List<Object[]> ev = new ArrayList<>();
        ev.add(new Object[]{T0 + 10 * MIN - 40_000, "IR dropped to half rate (link saturated)"});
        ev.add(new Object[]{T0 + 2 * MIN, "Streaming started"});
        JSONObject j = CrashRecord.toJson(4242, T0, T0 + 10 * MIN, s, streamed(), ev, "IllegalStateException at RtspServer.run:512");
        String raw = j.toString();
        for (String secret : new String[]{"BCA000000X1", "MyHomeNetwork", "DIRECT-SB-CRBYOB-6C79", "secretpass1"})
            assertFalse(secret + " in " + raw, raw.contains(secret));

        CrashRecord.Run r = CrashRecord.fromJson(new JSONObject(raw));
        assertEquals(4242, r.pid);
        assertEquals(T0, r.appStartMs);
        assertEquals("p2p", r.snap.advertise);
        assertEquals("CR-Scan Otter", r.snap.modelName);
        assertEquals(8 * MIN, r.stats.streamMs);
        assertEquals(30, r.stats.reqFps[0]);
        assertEquals(1, r.stats.detachStreaming.get());
        assertEquals(50, r.stats.frameAge.count());
        assertEquals(20.0, r.stats.frameAge.avgMs(), 0.001);
        assertEquals(2, r.eventMs.length);
        assertEquals("Streaming started", r.eventText[1]);
        assertEquals("IllegalStateException at RtspServer.run:512", r.error);
    }

    @Test
    public void saysWhatTheRunWasDoing() {
        CrashRecord.Run r = new CrashRecord.Run();
        r.snap = streamingOnP2p();
        assertEquals("streaming to a PC over Wi-Fi Direct", CrashRecord.doing(r));
        r.snap.hero = Dash.READY;
        assertEquals("the bridge was on over Wi-Fi Direct, waiting for a PC", CrashRecord.doing(r));
        r.snap.advertise = "local";
        r.snap.hero = Dash.STREAMING;
        assertEquals("streaming to the CrealityScan app on the phone", CrashRecord.doing(r));
        r.snap.running = false;
        assertEquals("the bridge was off", CrashRecord.doing(r));
    }

    /** After a crash the new session is empty: the report tells the stopped run, with its own measurements. */
    @Test
    public void theReportTellsTheRunThatStopped() throws Exception {
        List<Object[]> ev = new ArrayList<>();
        ev.add(new Object[]{T0 + 10 * MIN - 40_000, "IR dropped to half rate (link saturated)"});
        CrashRecord.Run r = CrashRecord.fromJson(new JSONObject(CrashRecord.toJson(4242, T0, T0 + 10 * MIN - 2_000,
                streamingOnP2p(), streamed(), ev, "IllegalStateException at RtspServer.run:512").toString()));
        DeviceReport.AppHealth h = new DeviceReport.AppHealth();
        h.lastExit = "crashed, 3 min ago";
        h.crashed = r;
        h.crashTs = T0 + 10 * MIN;
        Dash.Snap now = new Dash.Snap();                        // reopened app, bridge off
        DeviceReport.Fields f = DeviceReport.collect(now, new SessionStats(), "x", "Phone", h);
        String d = f.details;
        assertTrue(d, d.contains("\nThe run that stopped (crashed, 3 min ago):\n"));
        assertTrue(d, d.contains("- 10m 00s after the app started, bridge on 8m 00s, streaming 8m 00s: streaming to a PC over Wi-Fi Direct\n"));
        assertTrue(d, d.contains("- Error: IllegalStateException at RtspServer.run:512\n"));
        assertTrue(d, d.contains("- Last saved 2 s before it stopped\n"));
        assertTrue(d, d.contains("- Connection: Wi-Fi Direct, 5 GHz\n"));
        assertTrue(d, d.contains("- IR L: 28.1 of 30 fps"));
        assertTrue(d, d.contains("- Scanner disconnects: 1 while streaming, 0 while not streaming\n"));
        assertTrue(d, d.contains("  - 40 s before: IR dropped to half rate (link saturated)\n"));
        assertTrue("the notes stay last", d.endsWith(DeviceReport.NOTES_MARKER + "\n\n"));
        // its measurements count: no "scan first" for a crash report, and the source line says whose they are
        f.result = DeviceReport.RESULTS[1];
        assertFalse(DeviceReport.needsScanFirst(f));
        assertTrue(f.fromStoppedRun);
        java.text.DateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        assertTrue(DeviceReport.statsSource(f, hm).startsWith("This session hasn't streamed yet: the report has the measurements of the run that stopped"));
    }
}
