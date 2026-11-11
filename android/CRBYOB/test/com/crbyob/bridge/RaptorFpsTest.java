package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #15: a Raptor's scan modes use different IR sizes and rates (blue-line 1920x1200 at 30, Infrared 1280x800
 * or 960x600 at 20), so the frame-rate choice is worked out per size, and one learned rate no longer moves Boost.
 */
public class RaptorFpsTest {
    private static JSONObject doc() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File("../../models/scanners.json").toPath()),
                StandardCharsets.UTF_8));
    }

    private static void add(List<UsbDesc.Profile> out, String fcc, int w, int h, int... rates) {
        for (int fps : rates) {
            UsbDesc.Profile p = new UsbDesc.Profile();
            p.fourcc = fcc;
            p.width = w;
            p.height = h;
            p.fps = fps;
            out.add(p);
        }
    }

    /** The Raptor's MI_02 IR modes (fw 1.3.8, from the issue #15 usb_vs_modes log). */
    private static List<UsbDesc.Profile> raptorIr() {
        List<UsbDesc.Profile> ir = new ArrayList<>();
        add(ir, "GREY", 960, 600, 10, 15, 20, 30, 40, 50, 60, 90);
        add(ir, "GREY", 1280, 800, 5, 10, 15, 20, 30, 40, 50, 60);
        add(ir, "GREY", 1520, 1200, 20, 30, 40, 50, 60);
        add(ir, "GREY", 1600, 1000, 15, 20, 30, 40, 60);
        add(ir, "GREY", 1920, 1200, 5, 10, 15, 20, 25, 30, 40, 50);
        add(ir, "MJPG", 1280, 800, 10, 15, 20, 30, 60);
        return ir;
    }

    private static final FpsChoice.Learned NONE = (w, h) -> 0;

    private static FpsChoice.Learned learned(Object... sizeFps) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < sizeFps.length; i += 2) m.put((String) sizeFps[i], (Integer) sizeFps[i + 1]);
        return (w, h) -> m.getOrDefault(w + "x" + h, 0);
    }

    /**
     * What Creality Scan asks for at w x h: the GREY modes 0xFC1 keeps (as Bridge.profileListBlob filters them),
     * then the wanted fps, else the nearest higher, else the nearest (lib_orbbec_scan.dll 0x1805068c0).
     */
    private static int asked(ScannerModel m, List<UsbDesc.Profile> ir, FpsChoice.Learned l, String mode,
                             int w, int h, int wanted) {
        int floor = FpsChoice.floor(ir, m, l, mode, w, h), cap = m.fpsCap(w, h);
        List<Integer> kept = new ArrayList<>();
        for (UsbDesc.Profile p : ir)
            if (p.fourcc.equals("GREY") && p.width == w && p.height == h
                    && !(cap > 0 && p.fps > cap) && !UsbDesc.belowFpsFloor(ir, p, floor)) kept.add(p.fps);
        if (kept.contains(wanted)) return wanted;
        int best = -1;
        for (int f : kept) if (f > wanted && (best < 0 || f < best)) best = f;
        if (best > 0) return best;
        for (int f : kept) if (best < 0 || Math.abs(f - wanted) < Math.abs(best - wanted)) best = f;
        return best;
    }

    @Test public void raptorModelHasRatesPerSize() throws Exception {
        for (int pid : new int[]{0x06A8, 0x0C00, 0x0C03}) {
            ScannerModel m = ScannerModel.forPid(doc(), pid);
            assertEquals(m.name, 0, m.bridgeFps);
            assertEquals(m.name, 30, m.stockFps(1920, 1200));
            assertEquals(m.name, 20, m.stockFps(1280, 800));
            assertEquals(m.name, 20, m.stockFps(960, 600));
            assertEquals(m.name, 50, m.fpsCap(1920, 1200));
            assertEquals(m.name, 30, m.fpsCap(1280, 800));
            assertEquals(m.name, 0, m.stockFps(1520, 1200));    // not used over a bridge: learned if ever asked
        }
        ScannerModel otter = ScannerModel.forPid(doc(), 0x06DA);
        assertEquals(15, otter.stockFps(1280, 800));
        assertEquals(15, otter.stockFps(640, 400));
        assertEquals(0, otter.fpsCap(1280, 800));
    }

    @Test public void dashboardShowsBlueLineRatesWhateverRanLast() throws Exception {
        ScannerModel m = ScannerModel.forPid(doc(), 0x06A8);
        // 0.2.8 learned 40, 30, 40 then 20 from one scanner and moved Boost with it; the model's rates win now
        for (FpsChoice.Learned l : new FpsChoice.Learned[]{NONE, learned("1920x1200", 40, "1280x800", 20),
                learned("1920x1200", 30)}) {
            FpsChoice c = FpsChoice.shown(raptorIr(), m, l);
            assertEquals(1920, c.width);
            assertEquals(30, c.stock);
            assertEquals(40, c.boost);
            assertEquals(50, c.max);
        }
    }

    @Test public void eachScanModeGetsItsOwnBoostAndMax() throws Exception {
        ScannerModel m = ScannerModel.forPid(doc(), 0x06A8);
        List<UsbDesc.Profile> ir = raptorIr();
        // blue-line: 1920x1200, Creality Scan asks 30 (its own setting can raise it to 40)
        assertEquals(30, asked(m, ir, NONE, FpsChoice.STOCK, 1920, 1200, 30));
        assertEquals(40, asked(m, ir, NONE, FpsChoice.BOOST, 1920, 1200, 30));
        assertEquals(50, asked(m, ir, NONE, FpsChoice.MAX, 1920, 1200, 30));
        assertEquals(50, asked(m, ir, NONE, FpsChoice.MAX, 1920, 1200, 40));
        // Infrared: 1280x800 (Chip Scale Speckle Scan, which tops out at 30) and 960x600, asked at 20
        for (int[] s : new int[][]{{1280, 800}, {960, 600}}) {
            assertEquals(20, asked(m, ir, NONE, FpsChoice.STOCK, s[0], s[1], 20));
            assertEquals(30, asked(m, ir, NONE, FpsChoice.BOOST, s[0], s[1], 20));   // its only rate above Stock
            assertEquals(30, asked(m, ir, NONE, FpsChoice.MAX, s[0], s[1], 20));     // no Max: runs as Boost (50 failed)
            assertEquals(30, asked(m, ir, NONE, FpsChoice.STOCK, s[0], s[1], 40));   // never above the cap
        }
    }

    @Test public void infraredHasNoMax() throws Exception {
        ScannerModel m = ScannerModel.forPid(doc(), 0x06A8);
        for (int[] s : new int[][]{{1280, 800}, {960, 600}}) {
            FpsChoice c = FpsChoice.at(raptorIr(), m, NONE, s[0], s[1]);
            assertEquals(20, c.stock);
            assertEquals(30, c.boost);
            assertEquals(0, c.max);
            assertEquals(30, c.floorFor(FpsChoice.MAX));
            assertEquals(FpsChoice.BOOST, c.effective(FpsChoice.MAX));
        }
        FpsChoice blue = FpsChoice.shown(raptorIr(), m, NONE);
        assertEquals(FpsChoice.MAX, blue.effective(FpsChoice.MAX));
        assertEquals(FpsChoice.STOCK, blue.effective("anything"));
    }

    @Test public void dashboardNotesTheOtherSizes() throws Exception {
        ScannerModel m = ScannerModel.forPid(doc(), 0x06A8);
        FpsChoice shown = FpsChoice.shown(raptorIr(), m, NONE);
        List<FpsChoice> others = FpsChoice.others(raptorIr(), m, NONE);
        assertEquals(2, others.size());
        assertEquals("Rates above are for 1920x1200. At 1280x800: Stock 20, Boost 30 fps (Max runs as Boost)."
                        + " At 960x600: Stock 20, Boost 30 fps (Max runs as Boost).",
                FpsChoice.othersNote(shown, others));
        ScannerModel otter = ScannerModel.forPid(doc(), 0x06DA);
        List<UsbDesc.Profile> otterIr = new ArrayList<>();
        add(otterIr, "GREY", 1280, 800, 5, 10, 15, 20, 30);
        add(otterIr, "GREY", 640, 400, 15, 30);
        assertTrue(FpsChoice.others(otterIr, otter, NONE).isEmpty());
    }

    @Test public void otterKeepsOneFloorForEverySize() throws Exception {
        ScannerModel otter = ScannerModel.forPid(doc(), 0x06DA);
        List<UsbDesc.Profile> ir = new ArrayList<>();
        add(ir, "GREY", 1280, 800, 5, 10, 15, 20, 30);
        add(ir, "GREY", 640, 400, 15, 30, 60);
        for (int[] s : new int[][]{{1280, 800}, {640, 400}, {1280, 720}}) {
            assertEquals(20, FpsChoice.floor(ir, otter, NONE, FpsChoice.BOOST, s[0], s[1]));
            assertEquals(30, FpsChoice.floor(ir, otter, NONE, FpsChoice.MAX, s[0], s[1]));
            assertEquals(0, FpsChoice.floor(ir, otter, NONE, FpsChoice.STOCK, s[0], s[1]));
        }
    }

    @Test public void learnedRatesArePerSize() throws Exception {
        ScannerModel unknown = ScannerModel.forPid(doc(), 0x7FFF);
        assertEquals(0, unknown.stockFps(1920, 1200));
        List<UsbDesc.Profile> ir = raptorIr();
        FpsChoice.Learned l = learned("1920x1200", 30, "1280x800", 20);
        FpsChoice c = FpsChoice.shown(ir, unknown, l);
        assertEquals(1920, c.width);
        assertEquals(40, c.boost);
        assertEquals(40, FpsChoice.floor(ir, unknown, l, FpsChoice.BOOST, 1920, 1200));
        assertEquals(40, FpsChoice.floor(ir, unknown, l, FpsChoice.BOOST, 1280, 800));   // halfway 20 -> 60
        assertEquals(60, FpsChoice.floor(ir, unknown, l, FpsChoice.MAX, 1280, 800));
        // a size nothing was learned at shares the shown choice
        assertEquals(40, FpsChoice.floor(ir, unknown, l, FpsChoice.BOOST, 960, 600));
        // only Infrared learned so far: the dashboard shows that size rather than an unknown Stock
        FpsChoice ir800 = FpsChoice.shown(ir, unknown, learned("1280x800", 20));
        assertEquals(1280, ir800.width);
        assertEquals(20, ir800.stock);
    }
}
