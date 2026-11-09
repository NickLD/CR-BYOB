package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Issue #15, second round: the Raptor's Y14 depth in 0xFC1, its real frame tags, and USB requests in flight. */
public class RaptorDepthTest {
    private static JSONObject doc() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File("../../models/scanners.json").toPath()),
                StandardCharsets.UTF_8));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** A UVC VS_FORMAT_UNCOMPRESSED descriptor (27 bytes) with the given GUID fourcc and the standard suffix. */
    private static byte[] formatUncompressed(String fourcc) {
        byte[] d = new byte[27];
        d[0] = 27; d[1] = 0x24; d[2] = 0x04; d[3] = 1; d[4] = 1;
        for (int i = 0; i < 4; i++) d[5 + i] = (byte) fourcc.charAt(i);
        byte[] suffix = hex("00001000800000aa00389b71");
        System.arraycopy(suffix, 0, d, 9, suffix.length);
        d[21] = 16;
        return d;
    }

    @Test
    public void y14GuidIsRecognized() {
        // the Raptor's MI_00 format 1 showed up as unknown=[fmt1:59313420] before
        assertEquals("Y14 ", UsbDesc.guidToFourcc(formatUncompressed("Y14 "), 5));
        assertEquals("Y10 ", UsbDesc.guidToFourcc(formatUncompressed("Y10 "), 5));
        assertEquals(null, UsbDesc.guidToFourcc(formatUncompressed("Z16 "), 5));
    }

    @Test
    public void raptorsAdvertiseY14DepthAndTheOtterDoesNot() throws Exception {
        JSONObject d = doc();
        for (int pid : new int[]{0x06A8, 0x0C00, 0x0C03, 0x0C11}) {          // Raptor, X, Pro, Pro Max (inherited)
            ScannerModel m = ScannerModel.forPid(d, pid);
            assertEquals(m.name, List.of("Y14 "), m.advertise("depth"));
            assertEquals(m.name, "depth", m.fc1Order.get(m.fc1Order.size() - 1));
            assertTrue(m.name, m.fmt("Y14 ").fc1);
            assertEquals(m.name, "Y14 ", m.suffixToV4l2("y14"));
        }
        ScannerModel otter = ScannerModel.forPid(d, 0x06DA);
        assertTrue(otter.advertise("depth").isEmpty());
        assertTrue(!otter.fc1Order.contains("depth"));
    }

    @Test
    public void raptorLineLaserFramesAreTaggedB1() throws Exception {
        // ravngr's Raptor (fw 1.3.8) in Laser Lines Scan: first SCR byte 0xB1 = light type 2
        byte[] h = hex("0c8c40d194c6b11d00fa3406");
        assertEquals(0xB1, UvcCapture.tagByte(h, h.length));
        assertEquals(-1, UvcCapture.vendorByte(h, h.length));
        assertEquals(Integer.valueOf(2), ScannerModel.forPid(doc(), 0x06A8).irLightTypes.get(0xB1));
    }

    @Test
    public void smallPayloadsGetMoreRequestsInFlight() {
        assertEquals(86, UvcCapture.urbCount(12288));          // the Raptor: about 1 MB instead of 192 KB
        assertEquals(16, UvcCapture.urbCount(64 * 1024));      // 64 KB and up: unchanged
        assertEquals(8, UvcCapture.urbCount(512 * 1024));      // capped by URB_BUDGET, as before
        assertEquals(2, UvcCapture.urbCount(2 << 20));
    }
}
