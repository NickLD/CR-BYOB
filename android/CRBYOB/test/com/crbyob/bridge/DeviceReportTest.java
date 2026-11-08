package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pre-filled Device report: its URL, its privacy rules, and that it matches the GitHub form. */
public class DeviceReportTest {
    private static final String REPO = "https://github.com/NickLD/CR-BYOB";

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File("../..", rel).toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String url) throws Exception {
        Map<String, String> q = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            int e = kv.indexOf('=');
            q.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
        }
        return q;
    }

    private static Dash.Snap otterOnWifi() {
        Dash.Snap s = new Dash.Snap();
        s.scanner = true;
        s.modelName = "CR-Scan Otter";
        s.scannerModel = "CR-Scan Otter";
        s.scannerSn = "BCA000000X1";
        s.scannerFw = "1.2.2";
        s.usbSuper = true;
        s.fpsMode = "max";
        s.advertise = "wifi";
        s.staConnected = true;
        s.staSsid = "MyHomeNetwork";
        s.staStd = 7;
        s.staFreq = 5260;
        s.staRssi = -44;
        s.staTx = 1921;
        s.linkScore = 4;
        s.linkWord = "Excellent";
        return s;
    }

    @Test
    public void fillsTheFormFromTheSession() throws Exception {
        SessionStats ss = new SessionStats();
        ss.streamMs = 711_000;
        ss.streamingMsPer[0] = 711_000;
        ss.sent[0] = 21_330;
        ss.captured[0] = 21_500;
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), ss, "CR-BYOB 0.2.1 (1, github, abc) · Google Pixel 6 · Android 17 (API 37)", "Google Pixel 6");
        Map<String, String> q = query(DeviceReport.url(REPO, f));
        assertEquals("device-report.yml", q.get("template"));
        assertEquals("[Device] CR-Scan Otter on Google Pixel 6", q.get("title"));
        assertEquals("CR-Scan Otter", q.get("scanner"));
        assertEquals("1.2.2", q.get("scanner_fw"));
        assertEquals("Wi-Fi", q.get("network"));
        String d = q.get("details");
        assertTrue(d, d.contains("IR L: 30.0 fps, 99% of captured frames sent"));
        assertTrue(d, d.contains("Frame-rate setting: Max"));
        assertTrue(d, d.contains("Wi-Fi 7, 5 GHz, -44 dBm, 1921 Mbit/s link rate"));
        assertFalse("spaces must be %20, not +", DeviceReport.url(REPO, f).contains("+"));
        // the GitHub app ignores form fields: the same report as body text, in GitHub's submitted-form layout
        String body = q.get("body");
        assertTrue(body, body.startsWith("### Scanner\n\nCR-Scan Otter\n\n### Scanner firmware\n\n1.2.2"));
        assertTrue(body, body.contains("### Result\n\n_No response_"));
        assertTrue(body, body.contains("### What happened\n\nMeasured by CR-BYOB"));
    }

    /** A session with every diagnostic set: power, disconnects by phase, reduced rate, errors, latency, heat, chip. */
    private static SessionStats troubledSession() {
        SessionStats ss = new SessionStats();
        ss.streamMs = 100_000;
        ss.streamingMsPer[0] = 100_000;
        ss.sent[0] = 2_500;
        ss.captured[0] = 2_600;
        ss.qdrops[0] = 60;
        ss.skipped[0] = 40;
        ss.reducedMs[0] = 12_000;
        ss.poweredMs = 65_000;
        ss.batteryMs = 35_000;
        ss.usbDetach.set(7);
        ss.usbAttach.set(7);
        ss.detachStreaming.set(2);
        ss.brownouts.set(7);
        ss.pcDrops.set(1);
        ss.rtspErrors.set(2);
        ss.stalledClosed.set(1);
        ss.uvcErrors.set(3);
        ss.mirrorConnects.set(2);
        for (int i = 0; i < 100; i++) ss.frameAge.add(10_000 + i * 200);   // 10..29.8 ms
        for (int i = 0; i < 100; i++) ss.xu.add(3_000);
        ss.thermalMax = 2;
        return ss;
    }

    @Test
    public void reportsTheDiagnostics() {
        Dash.Snap s = otterOnWifi();
        s.soc = "Qualcomm SM8550";
        String d = DeviceReport.collect(s, troubledSession(), "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 25.0 fps, 96% of captured frames sent, 100 dropped, at reduced rate 12% of the time\n"));
        assertTrue(d, d.contains("- Phone power: on battery 35% of the session\n"));   // no charger seen: nothing to name
        assertTrue(d, d.contains("- Scanner disconnects: 2 while streaming, 5 while not streaming (7 while the phone was on battery)\n"));
        assertTrue(d, d.contains("- Also counted: 1 PC disconnect, 2 stream errors, 1 stalled stream closed, 3 USB frame errors\n"));
        assertTrue(d, d.contains("- Latency: frame age avg 19.9 ms, p95 29 ms; scanner replies p95 4 ms\n"));
        assertTrue(d, d.contains("- Mirror viewer: used (2 connections)\n"));
        assertTrue(d, d.contains("- Phone heat (worst Android thermal status): Moderate\n"));
        assertTrue(d, d.contains("- Phone chip: Qualcomm SM8550\n"));
    }

    @Test
    public void aQuietSessionStaysShort() {
        SessionStats ss = new SessionStats();
        ss.streamMs = 60_000;
        ss.streamingMsPer[0] = 60_000;
        ss.sent[0] = 1800;
        ss.captured[0] = 1800;
        ss.poweredMs = 60_000;
        String d = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 30.0 fps, 100% of captured frames sent\n"));
        assertTrue(d, d.contains("- Phone power: external the whole session\n"));
        assertTrue(d, d.contains("- Scanner disconnects: none\n"));
        assertTrue(d, d.contains("- Mirror viewer: not used\n"));
        for (String absent : new String[]{"Also counted", "Latency", "Phone heat", "Phone chip", "dropped", "reduced rate"})
            assertFalse(absent + " in\n" + d, d.contains(absent));

        ss.poweredMs = 0;
        ss.batteryMs = 60_000;
        assertTrue(DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details.contains("- Phone power: battery the whole session\n"));
    }

    @Test
    public void reportsTargetRateBatteryChargerAndWorstLink() {
        SessionStats ss = troubledSession();
        ss.reqFps[0] = 30;
        ss.batteryStart = 80;
        ss.batteryEnd = 72;
        ss.charger = "USB-C PD or hub";
        ss.rssiSum = -50 * 10;
        ss.rssiN = 10;
        ss.rssiMin = -61;
        ss.linkMin = 433;
        String d = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details;
        assertTrue(d, d.contains("- IR L: 25.0 of 30 fps, 96% of captured frames sent"));
        assertTrue(d, d.contains("- Phone power: on battery 35% of the session, otherwise USB-C PD or hub\n"));
        assertTrue(d, d.contains("- Phone battery: 80% at the start, 72% at the end\n"));
        assertTrue(d, d.contains("- Wi-Fi signal during the session: avg -50 dBm, worst -61 dBm, lowest link rate 433 Mbit/s\n"));
        ss.batteryMs = 0;
        assertTrue(DeviceReport.collect(otterOnWifi(), ss, "x", "Phone").details
                .contains("- Phone power: external the whole session (USB-C PD or hub)\n"));
    }

    @Test
    public void wifiDirectSaysWhetherThePhoneSharedTheRadio() {
        Dash.Snap s = otterOnWifi();
        s.advertise = "p2p";
        s.p2pFreq = 5180;
        SessionStats ss = troubledSession();
        ss.p2pDualMs = 40_000;
        ss.p2pDualFreq = 2437;
        String d = DeviceReport.collect(s, ss, "x", "Phone").details;
        assertTrue(d, d.contains("- Phone also on a Wi-Fi network: 40% of the streaming time (2.4 GHz), sharing the radio with Wi-Fi Direct\n"));
        assertFalse("no network name", d.contains("MyHomeNetwork"));
        ss.p2pDualMs = 0;
        assertTrue(DeviceReport.collect(s, ss, "x", "Phone").details.contains("- Phone also on a Wi-Fi network: no\n"));
        s.advertise = "wifi";                                     // only asked about Wi-Fi Direct
        assertFalse(DeviceReport.collect(s, ss, "x", "Phone").details.contains("Phone also on a Wi-Fi network"));
    }

    @Test
    public void appHealthShowsBatteryOptimizationAndTheLastExit() {
        DeviceReport.AppHealth h = new DeviceReport.AppHealth();
        h.unrestricted = false;
        h.lastExit = DeviceReport.exitLine(3, 12 * 60_000L);
        String d = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone", h).details;
        assertTrue(d, d.contains("- Battery optimization: on for CR-BYOB (Android may stop it in the background)\n"));
        assertTrue(d, d.contains("- Last time the app closed: killed by Android (low memory), 12 min ago\n"));
        h.unrestricted = true;
        h.lastExit = null;
        d = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone", h).details;
        assertTrue(d, d.contains("- Battery optimization: off for CR-BYOB (unrestricted)\n"));
        assertFalse(d, d.contains("Last time the app closed"));
        assertEquals("crashed, 3 h ago", DeviceReport.exitLine(4, 3 * 3_600_000L));
        assertEquals("stopped responding (ANR), just now", DeviceReport.exitLine(6, 20_000L));
        assertEquals("closed for an app update, 2 d ago", DeviceReport.exitLine(16, 50 * 3_600_000L));
        assertEquals("other (reason 99), 1 min ago", DeviceReport.exitLine(99, 60_000L));
    }

    /** This phone only: the phone's own Wi-Fi and any Ethernet adapter play no part, so neither shows up. */
    @Test
    public void thisPhoneOnlyLeavesOutNetworkLinks() {
        Dash.Snap s = otterOnWifi();                  // still connected to home Wi-Fi
        s.advertise = "local";
        s.ethMbps = 1000;
        SessionStats ss = troubledSession();
        ss.rssiSum = -50 * 10;
        ss.rssiN = 10;
        ss.rssiMin = -61;
        ss.linkMin = 433;
        String d = DeviceReport.collect(s, ss, "x", "Phone").details;
        assertTrue(d, d.contains("- Connection: This phone only (CrealityScan mobile app)\n"));
        for (String absent : new String[]{"dBm", "link rate", "Mbit/s link", "Phone also on a Wi-Fi network"})
            assertFalse(absent + " in\n" + d, d.contains(absent));
    }

    @Test
    public void ethernetShowsItsLinkSpeed() {
        Dash.Snap s = otterOnWifi();
        s.advertise = "eth";
        s.staConnected = false;
        s.ethMbps = 100;
        String d = DeviceReport.collect(s, new SessionStats(), "x", "Phone").details;
        assertTrue(d, d.contains("- Connection: Ethernet, 100 Mbit/s link\n"));
        s.ethMbps = -1;                                // unreadable: no speed, no guess
        d = DeviceReport.collect(s, new SessionStats(), "x", "Phone").details;
        assertTrue(d, d.contains("- Connection: Ethernet\n"));
    }

    @Test
    public void neverLeaksSerialSsidOrAddresses() {
        Dash.Snap s = otterOnWifi();
        s.lanIp = "192.168.10.116";
        DeviceReport.Fields f = DeviceReport.collect(s, new SessionStats(), "x", "Phone");
        String all = f.title + f.scanner + f.scannerFw + f.appInfo + f.network + f.details;
        assertFalse(all.contains("BCA000000X1"));
        assertFalse(all.contains("MyHomeNetwork"));
        assertFalse(all.contains("192.168."));
    }

    @Test
    public void noScannerLeavesTheDropdownForThePerson() throws Exception {
        DeviceReport.Fields f = DeviceReport.collect(new Dash.Snap(), null, "x", "Phone");
        assertEquals("", f.scanner);
        assertFalse(f.scannerDetected);
        assertFalse(query(DeviceReport.url(REPO, f)).containsKey("scanner"));
        assertTrue(f.title.contains("<scanner>"));
    }

    @Test
    public void asksForAScanWhenThereAreNoStats() {
        java.text.DateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        hm.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        DeviceReport.Fields none = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone");
        assertFalse("no result picked yet", DeviceReport.needsScanFirst(none));
        none.result = DeviceReport.RESULTS[0];
        assertTrue(DeviceReport.needsScanFirst(none));
        none.result = DeviceReport.RESULTS[1];
        assertTrue(DeviceReport.needsScanFirst(none));
        assertTrue(DeviceReport.statsSource(none, hm).startsWith("No streaming in this bridge session yet"));
        none.result = DeviceReport.RESULTS[2];                     // "Doesn't work": a failed setup may not stream
        assertFalse(DeviceReport.needsScanFirst(none));

        SessionStats ss = new SessionStats();
        ss.streamMs = 711_000;
        ss.stopMs = ss.startMs + 900_000;
        DeviceReport.Fields streamed = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone");
        streamed.result = DeviceReport.RESULTS[0];
        assertFalse(DeviceReport.needsScanFirst(streamed));
        String src = DeviceReport.statsSource(streamed, hm);
        assertTrue(src, src.startsWith("Measurements are from this bridge session: 11m 51s of streaming, started "));
        assertTrue(src, src.contains(", stopped "));
    }

    /** "Scan first" keeps what the person entered; the next report has fresh measurements plus their entries. */
    @Test
    public void aDraftCarriesTheirEntriesIntoTheNextReport() {
        DeviceReport.Fields before = DeviceReport.collect(otterOnWifi(), new SessionStats(), "x", "Phone");
        before.client = "Creality Scan 4.3.3 (Windows)";
        before.result = DeviceReport.RESULTS[0];
        before.details = before.details + "Scanned a mug, tracking held.";

        SessionStats ss = new SessionStats();
        ss.streamMs = 60_000;
        ss.streamingMsPer[0] = 60_000;
        ss.sent[0] = 1800;
        ss.captured[0] = 1800;
        DeviceReport.Fields after = DeviceReport.collect(otterOnWifi(), ss, "x", "Phone");
        DeviceReport.restore(after, before);
        assertEquals("Creality Scan 4.3.3 (Windows)", after.client);
        assertEquals(DeviceReport.RESULTS[0], after.result);
        assertTrue(after.details, after.details.contains("IR L: 30.0 fps"));                 // fresh measurements
        assertFalse(after.details, after.details.contains("no streaming in this session"));  // not the old ones
        assertTrue(after.details, after.details.trim().endsWith("Scanned a mug, tracking held."));
        assertEquals("Scanned a mug, tracking held.", DeviceReport.notesOf(after.details));
        assertFalse(DeviceReport.needsScanFirst(after));
    }

    @Test
    public void aDetectedScannerIsFixed() {
        DeviceReport.Fields f = DeviceReport.collect(otterOnWifi(), null, "x", "Phone");
        assertEquals("CR-Scan Otter", f.scanner);
        assertTrue(f.scannerDetected);
    }

    @Test
    public void anUnlistedScannerIsOtherWithItsUsbId() {
        Dash.Snap s = otterOnWifi();
        s.modelName = "PID 0x0C42";                   // ScannerModel's name for a product ID not in scanners.json
        DeviceReport.Fields f = DeviceReport.collect(s, null, "x", "Phone");
        assertEquals(DeviceReport.OTHER, f.scanner);
        assertFalse("pick it, or say which in the details", f.scannerDetected);
        assertTrue(f.details, f.details.contains("Scanner USB ID: 0x0C42"));
    }

    /** The ids and options the app fills in exist in the form, or GitHub silently drops them. */
    @Test
    public void matchesTheGitHubForm() throws Exception {
        String yml = read(".github/ISSUE_TEMPLATE/" + DeviceReport.TEMPLATE);
        for (String id : new String[]{"scanner", "scanner_fw", "app_info", "network", "client", "result", "details"})
            assertTrue("field id " + id, yml.contains("id: " + id + "\n") || yml.contains("id: " + id + "\r\n"));
        List<String> scannerOpts = options(yml, "scanner"), networkOpts = options(yml, "network");
        assertTrue(scannerOpts.contains(DeviceReport.OTHER));
        assertEquals(java.util.Arrays.asList(DeviceReport.NETWORKS), networkOpts);
        assertEquals(java.util.Arrays.asList(DeviceReport.RESULTS), options(yml, "result"));
        for (String a : new String[]{"p2p", "wifi", "eth", "local"})
            assertTrue(a, networkOpts.contains(DeviceReport.networkOption(a)));
        JSONObject models = new JSONObject(read("models/scanners.json")).getJSONObject("models");
        for (Iterator<String> it = models.keys(); it.hasNext(); ) {
            String name = models.getJSONObject(it.next()).optString("name");
            assertTrue("scanners.json name not in the form's Scanner list: " + name, scannerOpts.contains(name));
        }
        assertTrue(DeviceReport.isFormLink(REPO + "/issues/new?template=device-report.yml"));
    }

    private static List<String> options(String yml, String id) {
        Matcher m = Pattern.compile("id: " + id + "\\s*\\n(?:.*\\n)*?\\s*options:\\s*\\n((?:\\s*- .*\\n)+)").matcher(yml.replace("\r", ""));
        assertTrue("options for " + id, m.find());
        List<String> out = new ArrayList<>();
        for (String l : m.group(1).split("\n")) if (l.trim().startsWith("- ")) out.add(l.trim().substring(2).trim());
        return out;
    }
}
