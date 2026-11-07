package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** The one-time "First scan go well?" card: who gets asked, and when. */
public class ReportPromptTest {
    private static List<String> listed() throws Exception {
        return ReportPrompt.listedPhones(new String(Files.readAllBytes(new File("../../docs/phones.md").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void readsThePhonesFromTheRealList() throws Exception {
        List<String> l = listed();
        assertTrue(l.toString(), l.contains("Google Pixel 10 Pro XL"));
        assertTrue(l.toString(), l.contains("Google Pixel 6"));
    }

    @Test
    public void matchesPhonesExactly() throws Exception {
        List<String> l = listed();
        assertTrue(ReportPrompt.phoneListed(l, "Google", "Pixel 10 Pro XL"));
        assertTrue(ReportPrompt.phoneListed(l, "google", "pixel 6"));
        assertFalse("a Pixel 10 is not the Pro XL", ReportPrompt.phoneListed(l, "Google", "Pixel 10"));
        assertFalse(ReportPrompt.phoneListed(l, "samsung", "SM-S928B"));
    }

    @Test
    public void asksOnlyAboutWhatHasNoReport() {
        assertNull("both covered: no card", ReportPrompt.missing(true, "Google Pixel 10 Pro XL", "CR-Scan Otter", true));
        assertEquals("your Google Pixel 9", ReportPrompt.missing(false, "Google Pixel 9", "CR-Scan Otter", true));
        assertEquals("the CR-Scan Raptor", ReportPrompt.missing(true, "Google Pixel 6", "CR-Scan Raptor", false));
        assertEquals("your Google Pixel 9 with a CR-Scan Raptor",
                ReportPrompt.missing(false, "Google Pixel 9", "CR-Scan Raptor", false));
        assertEquals("Samsung SM-S928B", ReportPrompt.phoneName("samsung", "SM-S928B"));
        assertEquals("Google Pixel 9", ReportPrompt.phoneName("Google", "Pixel 9"));
    }

    @Test
    public void waitsForARealScanToEnd() {
        long now = 1_000_000;
        assertFalse("too short to be a scan", ReportPrompt.scanEnded(30_000, false, now - 60_000, now));
        assertFalse("still streaming", ReportPrompt.scanEnded(120_000, true, now, now));
        assertFalse("a preview flicker", ReportPrompt.scanEnded(120_000, false, now - 3_000, now));
        assertTrue(ReportPrompt.scanEnded(120_000, false, now - 15_000, now));
        assertTrue("streamed while the app was in the background", ReportPrompt.scanEnded(120_000, false, 0, now));
    }

    @Test
    public void keepsTheScrollHint() {
        String m = ReportPrompt.message("your Google Pixel 9");
        assertTrue(m, m.startsWith("We don't have a compatibility report for your Google Pixel 9 yet."));
        assertTrue(m, m.contains("scroll to the bottom of the app"));
        assertTrue(m, m.endsWith("We won't show this message again."));
    }
}
