package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The Raptor's per-frame IR light type: where it's read from, the model table, and the 0xC5/0xC6 "all" value. */
public class RaptorLightTypeTest {
    private static JSONObject doc() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File("../../models/scanners.json").toPath()),
                StandardCharsets.UTF_8));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Real Otter headers (fw 1.2.2): the per-frame tag is the first SCR byte, 0xA2 / 0xFF on IR, 0x3C on color. */
    @Test
    public void tagByteIsTheFirstScrByte() {
        byte[] irA = hex("0c8cbf870f77a2000000b803"), irB = hex("0c8d9eba1077ff0000ffb803"), color = hex("0c8c1f4e11773c000000b803");
        assertEquals(0xA2, UvcCapture.tagByte(irA, irA.length));
        assertEquals(0xFF, UvcCapture.tagByte(irB, irB.length));
        assertEquals(0x3C, UvcCapture.tagByte(color, color.length));
        assertEquals(-1, UvcCapture.vendorByte(irA, irA.length));            // nothing after the SCR
        // SCR without PTS: the tag is at 2
        assertEquals(0xB1, UvcCapture.tagByte(new byte[]{8, (byte) 0x88, (byte) 0xB1, 0, 0, 0, 0, 0}, 8));
        // no SCR: no tag
        assertEquals(-1, UvcCapture.tagByte(new byte[]{6, (byte) 0x84, 1, 2, 3, 4}, 6));
        assertEquals(-1, UvcCapture.tagByte(new byte[0], 0));
    }

    @Test
    public void vendorByteFollowsPtsAndScr() {
        // 14-byte header: length, info (EOH|SCR|PTS|FID), PTS(4), SCR(6), vendor 0xA2 0x00
        byte[] h = {14, (byte) 0x8D, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, (byte) 0xA2, 0};
        assertEquals(12, UvcCapture.vendorOffset(0x8D));
        assertEquals(0xA2, UvcCapture.vendorByte(h, h.length));
        // PTS only: vendor bytes start at 6
        byte[] p = {7, (byte) 0x84, 1, 2, 3, 4, (byte) 0xB1};
        assertEquals(0xB1, UvcCapture.vendorByte(p, p.length));
        // the standard 12-byte header carries none
        assertEquals(-1, UvcCapture.vendorByte(new byte[]{12, (byte) 0x8D, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 12));
        assertEquals(-1, UvcCapture.vendorByte(new byte[]{2, (byte) 0x80}, 2));
        assertEquals(-1, UvcCapture.vendorByte(new byte[0], 0));
    }

    @Test
    public void raptorsTagTheirIrFramesAndTheOtterDoesNot() throws Exception {
        JSONObject d = doc();
        for (int pid : new int[]{0x06A8, 0x0C00, 0x0C03, 0x0C11}) {          // Raptor, X, Pro, Pro Max (inherited)
            ScannerModel m = ScannerModel.forPid(d, pid);
            assertEquals(m.name, 5, m.irLightTypes.size());
            assertEquals(m.name, Integer.valueOf(0), m.irLightTypes.get(0xFF));
            assertEquals(m.name, Integer.valueOf(1), m.irLightTypes.get(0xA2));
            assertEquals(m.name, Integer.valueOf(4), m.irLightTypes.get(0xB4));
        }
        assertTrue(ScannerModel.forPid(d, 0x06DA).irLightTypes.isEmpty());   // Otter
    }

    @Test
    public void allLightTypesIsFfffOrMinusOne() {
        assertTrue(Bridge.allLightTypes(0xFFFF));
        assertTrue(Bridge.allLightTypes(-1));
        for (int v : new int[]{0, 1, 2, 3, 4, 0xFFFE, 0x1FFFF}) assertFalse(Integer.toHexString(v), Bridge.allLightTypes(v));
    }

    @Test
    public void histogramListsAndClears() {
        int[] c = new int[257];
        c[0xA2] = 15;
        c[0xB1] = 14;
        c[256] = 1;
        assertEquals("a2:15 b1:14 none:1", UvcCapture.histogram(c));
        assertEquals("", UvcCapture.histogram(c));
    }
}
