package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The log made safe to post: redaction, the link-sized digest and the archive. */
public class LogExportTest {
    private static String redact(String s) {
        return LogExport.redact(s, new LinkedHashMap<>());
    }

    @Test
    public void hidesSerialsNamesAndPasswordsInDashboardLines() {
        assertEquals("06:10:01 kind=scanner_ready pid=1704 fw=1.3.8 serial=(hidden)",
                redact("06:10:01 kind=scanner_ready pid=1704 fw=1.3.8 serial=BCA674105K2"));
        // a value with spaces runs to the next key
        assertEquals("kind=p2p_group why=start ssid=(hidden) passphrase=(hidden) freq=5180",
                redact("kind=p2p_group why=start ssid=DIRECT-SB-CRBYOB Nick's lab passphrase=(hidden) freq=5180"));
        assertEquals("kind=bridge_started pid=0xc01 version=1.3.1 sn=(hidden)",
                redact("kind=bridge_started pid=0xc01 version=1.3.1 sn=CRBYOB3F8B"));
        assertEquals("kind=gvcp_listen mac=(hidden) vs_sn=keep",
                redact("kind=gvcp_listen mac=02:00:00:aa:bb:cc vs_sn=keep"));
    }

    @Test
    public void hidesTheSameInJsonLines() {
        String j = "{\"t\":1730.123,\"kind\":\"usb_open\",\"serial\":\"BCA674105K2\",\"desc_bytes\":2453,"
                + "\"ssid\":\"My \\\"home\\\" net\",\"sn\":null}";
        assertEquals("{\"t\":1730.123,\"kind\":\"usb_open\",\"serial\":\"(hidden)\",\"desc_bytes\":2453,"
                + "\"ssid\":\"(hidden)\",\"sn\":\"(hidden)\"}", redact(j));
    }

    @Test
    public void namesAddressesConsistentlyAndLeavesTimesAlone() {
        Map<String, String> ips = new LinkedHashMap<>();
        assertEquals("06:12:36 kind=rtsp_connect port=8556 peer=ip1:50112",
                LogExport.redact("06:12:36 kind=rtsp_connect port=8556 peer=192.168.1.20:50112", ips));
        assertEquals("06:12:37 kind=rtsp_req url=rtsp://ip2:8556/ir from=ip1",
                LogExport.redact("06:12:37 kind=rtsp_req url=rtsp://192.168.1.7:8556/ir from=192.168.1.20", ips));
        assertEquals("kind=tcp_listen addr=0.0.0.0:8999 lo=127.0.0.1", redact("kind=tcp_listen addr=0.0.0.0:8999 lo=127.0.0.1"));
        assertEquals("peer=ip1 other=ip2 dev=(mac)", redact("peer=fe80::1c2b:3aff:fe4d%wlan0 other=2001:db8:0:0:1:0:0:1 dev=AA-BB-CC-DD-EE-FF"));
        assertEquals("fw=1.3.8 ver=0.2.4 code=20499 at 06:12:36", redact("fw=1.3.8 ver=0.2.4 code=20499 at 06:12:36"));
    }

    @Test
    public void compactsModesAndModelNotes() {
        assertEquals("06:10:00 usb_vs_modes iface=1 ep=81 modes=GREY 320x200@5/10, 640x400@30; MJPG 1280x800@15",
                LogExport.compact("06:10:00 kind=usb_vs_modes iface=1 ep=81 modes=GREY 320x200@5 (fmt 3 frm 1); "
                        + "GREY 320x200@10 (fmt 3 frm 1); GREY 640x400@30 (fmt 3 frm 2); MJPG 1280x800@15 (fmt 1 frm 4); "));
        assertEquals("06:10:01 scanner_model pid=0x06A8 name=CR-Scan Raptor status=unverified",
                LogExport.compact("06:10:01 kind=scanner_model pid=0x06A8 name=CR-Scan Raptor status=unverified "
                        + "registry=asset:scanners.json notes=long text"));
    }

    @Test
    public void theDigestKeepsSetupAndRecentLinesWithinItsBudget() {
        List<String> pinned = DeviceReportTest.pinnedLines(), recent = DeviceReportTest.recentLines();
        String d = LogExport.digest(pinned, recent, "x.zip", 3000);
        assertTrue(d.length() + "", LogExport.encodedLength(d) <= 3000);
        assertTrue(d, d.startsWith("Log digest. Full log: x.zip\n-- Scanner setup --\n"));
        assertTrue(d, d.contains("scanner_model pid=0x06A8"));
        assertTrue(d, d.contains("scanner_roles roles="));
        assertTrue(d, d.contains("-- Recent --\n"));
        assertTrue("newest line kept", d.endsWith("n=299"));
        assertFalse("noise skipped", d.contains("hp_req") || d.contains("uvc_stats"));
        assertFalse("already in the report", d.contains("scanner_ready") || d.contains("fw=1.3.8"));

        String small = LogExport.digest(pinned, recent, "x.zip", 700);
        assertTrue(small, small.contains("scanner_roles") && small.contains("n=299"));
        assertFalse("modes give way to model, roles and recent lines", small.contains("usb_vs_modes"));
        assertEquals("", LogExport.digest(pinned, recent, "x.zip", 10));
    }

    @Test
    public void theArchiveHoldsTheWholeLogRedacted() throws Exception {
        File dir = Files.createTempDirectory("crbyob-files").toFile();
        Files.write(new File(dir, "bridge.1.jsonl").toPath(),
                "{\"t\":1,\"kind\":\"usb_open\",\"serial\":\"BCA674105K2\"}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "bridge.jsonl").toPath(),
                "{\"t\":2,\"kind\":\"rtsp_connect\",\"peer\":\"192.168.1.20\"}\n".getBytes(StandardCharsets.UTF_8));
        File runs = new File(dir, CrashRecord.DIR);
        assertTrue(runs.mkdirs());
        Files.write(new File(runs, "1234.json").toPath(), "{\"pid\":1234}".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        LogExport.writeZip(bo, dir, DeviceReportTest.pinnedLines(), DeviceReportTest.recentLines(), "CR-BYOB 0.2.4");
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bo.toByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                ByteArrayOutputStream c = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = z.read(buf)) > 0; ) c.write(buf, 0, n);
                entries.put(e.getName(), c.toString("UTF-8"));
            }
        }
        assertEquals(java.util.Arrays.asList("summary.txt", "bridge.1.jsonl", "bridge.jsonl", "runs/1234.json"),
                new java.util.ArrayList<>(entries.keySet()));
        String all = String.join("\n", entries.values());
        assertFalse(all, all.contains("BCA674105K2") || all.contains("192.168."));
        assertTrue(entries.get("bridge.1.jsonl"), entries.get("bridge.1.jsonl").contains("\"serial\":\"(hidden)\""));
        assertTrue(entries.get("summary.txt").startsWith("CR-BYOB log (serial numbers"));
        assertTrue(entries.get("summary.txt").contains("CR-BYOB 0.2.4"));
    }

    @Test
    public void jsonLinesReadLikeTheDashboard() {
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            assertEquals("06:12:36 kind=rtsp_req port=8556 method=DESCRIBE url=rtsp://x/ir ok=true n=null arr=[1,2]",
                    LogExport.humanize("{\"t\":1794204756.123,\"kind\":\"rtsp_req\",\"port\":8556,\"method\":\"DESCRIBE\","
                            + "\"url\":\"rtsp://x/ir\",\"ok\":true,\"n\":null,\"arr\":[1,2]}"));
            assertEquals("06:12:36 kind=event text=PC \"lab\" connected\nok",
                    LogExport.humanize("{\"t\":1794204756.0,\"kind\":\"event\",\"text\":\"PC \\\"lab\\\" connected\\nok\"}"));
            assertEquals("not json", LogExport.humanize("not json"));
            assertEquals("{\"t\":1,\"kind\":", LogExport.humanize("{\"t\":1,\"kind\":"));   // cut off: left as is
        } finally {
            java.util.TimeZone.setDefault(tz);
        }
    }

    @Test
    public void recentLinesComeFromTheFilesAcrossARotation() throws Exception {
        File dir = Files.createTempDirectory("crbyob-tail").toFile();
        StringBuilder old = new StringBuilder(), cur = new StringBuilder();
        for (int i = 0; i < 50; i++) old.append("{\"t\":").append(i).append(",\"kind\":\"old\",\"n\":").append(i).append("}\n");
        for (int i = 0; i < 3; i++) cur.append("{\"t\":").append(100 + i).append(",\"kind\":\"cur\",\"n\":").append(i).append("}\n");
        Files.write(new File(dir, "bridge.1.jsonl").toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "bridge.jsonl").toPath(), cur.toString().getBytes(StandardCharsets.UTF_8));

        List<String> all = LogExport.recentFromFiles(dir, 1 << 20);
        assertEquals(53, all.size());
        assertTrue(all.get(0), all.get(0).endsWith("kind=old n=0"));
        assertTrue(all.get(52), all.get(52).endsWith("kind=cur n=2"));

        List<String> some = LogExport.recentFromFiles(dir, 200);     // the current file plus the end of the old one
        assertTrue(some.size() > 3 && some.size() < 53);
        assertTrue("no partial first line", some.get(0).contains("kind=old n="));
        assertTrue(some.get(some.size() - 1).endsWith("kind=cur n=2"));
        assertTrue(LogExport.recentFromFiles(Files.createTempDirectory("crbyob-none").toFile(), 1000).isEmpty());
    }

    @Test
    public void setupLinesSurviveARestartAndPreferThisRun() {
        List<String> fromFile = java.util.Arrays.asList(
                "01:00:00 kind=scanner_model pid=0x06A8 name=CR-Scan Raptor",
                "01:00:00 kind=usb_vs_modes iface=1 modes=GREY 640x400@30 (fmt 1 frm 1); ",
                "01:00:00 kind=usb_vs_modes iface=3 modes=GREY 640x400@30 (fmt 1 frm 1); ",
                "01:00:01 kind=rtsp_req port=8556");
        List<String> memory = java.util.Collections.singletonList("02:00:00 kind=scanner_model pid=0x06DA name=CR-Scan Otter");
        assertEquals(java.util.Arrays.asList(
                "02:00:00 kind=scanner_model pid=0x06DA name=CR-Scan Otter",
                "01:00:00 kind=usb_vs_modes iface=1 modes=GREY 640x400@30 (fmt 1 frm 1); ",
                "01:00:00 kind=usb_vs_modes iface=3 modes=GREY 640x400@30 (fmt 1 frm 1); "),
                LogExport.mergePinned(fromFile, memory));
    }

    @Test
    public void fileNamesSortByTime() {
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            assertEquals("crbyob-log-20261109-0612.zip", LogExport.fileName(1794204720000L));
        } finally {
            java.util.TimeZone.setDefault(tz);
        }
    }
}
