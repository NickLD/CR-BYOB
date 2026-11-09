package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Issue #15, third round: Creality Scan's Infrared mode asks a Raptor behind a bridge for IR_L/IR_R MJPG
 * (1280x800 or 960x600), which cameraServer encodes from Y8; plus the 0x413 work mode and 0xFC3 parsing.
 */
public class RaptorInfraredTest {
    private static JSONObject doc() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File("../../models/scanners.json").toPath()),
                StandardCharsets.UTF_8));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static UsbDesc.Profile p(String fcc, int w, int h, int fps) {
        UsbDesc.Profile p = new UsbDesc.Profile();
        p.fourcc = fcc;
        p.width = w;
        p.height = h;
        p.fps = fps;
        return p;
    }

    /** The Raptor's MI_02 IR node: Y8 and native MJPG at the speckle sizes. */
    private static UsbDesc.Vs raptorIr() {
        UsbDesc.Vs vs = new UsbDesc.Vs();
        vs.profiles.add(p("GREY", 960, 600, 20));
        vs.profiles.add(p("GREY", 1280, 800, 20));
        vs.profiles.add(p("GREY", 1920, 1200, 40));
        vs.profiles.add(p("MJPG", 960, 600, 20));
        vs.profiles.add(p("MJPG", 1280, 800, 20));
        return vs;
    }

    @Test
    public void infraredMjpgIsEncodedFromY8() throws Exception {
        ScannerModel m = ScannerModel.forPid(doc(), 0x06A8);
        for (String path : new String[]{"/ir_l/1280_800_20_mjpg", "/ir_r/1280_800_20_mjpg", "/ir_l/960_600_20_mjpg"}) {
            Object[] r = RtspServer.parseSession(path, raptorIr(), m);
            assertEquals(path, 3, r.length);
            assertEquals(path, "GREY", ((UsbDesc.Profile) r[1]).fourcc);      // not the scanner's own MJPG
            assertEquals(path, "MJPG", r[2]);
        }
        // blue-line mode is unchanged: RLE from Y8, and plain Y8 stays native
        Object[] rle = RtspServer.parseSession("/ir_l/1920_1200_40_rle", raptorIr(), m);
        assertEquals("RLE ", rle[2]);
        Object[] y8 = RtspServer.parseSession("/ir_l/1280_800_20_y8", raptorIr(), m);
        assertEquals(2, y8.length);
        assertEquals("GREY", ((UsbDesc.Profile) y8[1]).fourcc);
    }

    @Test
    public void raptorsListMjpgCopiesOfTheirY8Modes() throws Exception {
        assertTrue(Bridge.IR_ENCODERS.contains("MJPG"));
        for (int pid : new int[]{0x06A8, 0x0C00, 0x0C03, 0x0C11}) {
            ScannerModel m = ScannerModel.forPid(doc(), pid);
            assertTrue(m.name, m.irSynth.contains("MJPG"));
            assertTrue(m.name, m.synthAllowed("MJPG", "1.3.8"));
            assertEquals(m.name, "MJPG", m.suffixToV4l2("mjpg"));
        }
    }

    @Test
    public void workModeNameFromTheOp28Payload() {
        // ravngr's log: Creality Scan's Infrared and blue-line preview
        byte[] speckle = hex("13040000000038e1e98963ed54bb3d48e6a18ed28dd243686970205363616c6520537065636b6c65205363616e0000000000000000000a000000");
        byte[] lines = hex("130400000000551cc6b785df988e243c449d3d38086c4c61736572204c696e6573205363616e000000000000000000000000000000002e000000");
        assertEquals("Chip Scale Speckle Scan", Bridge.workModeName(speckle));
        assertEquals("Laser Lines Scan", Bridge.workModeName(lines));
        assertNull(Bridge.workModeName(new byte[8]));
    }

    private static byte[] entries(boolean header, int[]... es) {
        int off = header ? 4 : 0;
        byte[] b = new byte[off + 20 * es.length];
        if (header) { Hp.putU16(b, 0, 20); Hp.putU16(b, 2, es.length); }
        for (int i = 0; i < es.length; i++) for (int k = 0; k < 5; k++) Hp.putU32(b, off + 20 * i + 4 * k, es[i][k]);
        return b;
    }

    @Test
    public void effectiveProfilesParseWithOrWithoutAHeader() {
        int[] irl = {6, 9, 1280, 800, 40}, col = {2, 5, 1280, 800, 30};
        for (boolean header : new boolean[]{false, true}) {
            List<int[]> l = Bridge.parseEffectiveProfiles(entries(header, irl, col));
            assertEquals(2, l.size());
            assertEquals("IR_L Y8 1280x800<=40, COLOR MJPG 1280x800<=30", Bridge.describeEffectiveProfiles(l));
        }
        assertNull(Bridge.parseEffectiveProfiles(new byte[7]));
        assertNull(Bridge.parseEffectiveProfiles(entries(false, new int[]{0, 9, 1280, 800, 40})));   // no sensor 0
        assertNull(Bridge.parseEffectiveProfiles(new byte[0]));
    }

    @Test
    public void otterEffectiveProfiles() {
        // the Otter's 0xFC3 reply (fw 1.2.2), first 4 of its 12 entries: {sensorType, OBFormat, w, h, maxFps}
        byte[] d = hex("070000000900000000050000200300001e000000070000000000000000050000200300001e000000"
                + "070000000900000080020000900100001e000000060000000500000000050000200300001e000000");
        assertEquals("IR_R Y8 1280x800<=30, IR_R YUYV 1280x800<=30, IR_R Y8 640x400<=30, IR_L MJPG 1280x800<=30",
                Bridge.describeEffectiveProfiles(Bridge.parseEffectiveProfiles(d)));
    }
}
