package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class FpsFloorTest {

    private static UsbDesc.Profile p(String fcc, int w, int h, int fps) {
        UsbDesc.Profile p = new UsbDesc.Profile();
        p.fourcc = fcc;
        p.width = w;
        p.height = h;
        p.fps = fps;
        return p;
    }

    /** The profiles that stay in 0xFC1, as "fourcc wxh@fps". */
    private static List<String> kept(List<UsbDesc.Profile> all, int floor) {
        List<String> out = new ArrayList<>();
        for (UsbDesc.Profile p : all)
            if (!UsbDesc.belowFpsFloor(all, p, floor)) out.add(p.fourcc + " " + p.width + "x" + p.height + "@" + p.fps);
        return out;
    }

    private static final List<UsbDesc.Profile> OTTER_IR = List.of(
            p("GREY", 1280, 800, 5), p("GREY", 1280, 800, 15), p("GREY", 1280, 800, 30),
            p("GREY", 640, 400, 15));

    @Test public void offKeepsEverything() {
        assertEquals(4, kept(OTTER_IR, 0).size());
    }

    @Test public void floorHidesSlowerModesOfTheSameSize() {
        // Creality Scan wants 15 and takes the smallest advertised fps >= 15, so only @30 may remain
        assertEquals(List.of("GREY 1280x800@30", "GREY 640x400@15"), kept(OTTER_IR, 30));
    }

    @Test public void intermediateFloorKeepsFasterModes() {
        assertEquals(List.of("GREY 1280x800@30", "GREY 640x400@15"), kept(OTTER_IR, 20));
        assertEquals(List.of("GREY 1280x800@15", "GREY 1280x800@30", "GREY 640x400@15"), kept(OTTER_IR, 15));
    }

    @Test public void sizeWithNothingFastEnoughKeepsItsFastestMode() {
        // USB 2 color tops out at 20: it must still advertise something at that size
        List<UsbDesc.Profile> color = List.of(p("MJPG", 1280, 800, 10), p("MJPG", 1280, 800, 20));
        assertEquals(List.of("MJPG 1280x800@20"), kept(color, 30));
    }

    @Test public void captureQueueKeepsTheSameSlackAtAnyRate() {
        assertEquals(2, UvcCapture.queueDepth(5));
        assertEquals(4, UvcCapture.queueDepth(15));
        assertEquals(8, UvcCapture.queueDepth(30));
        assertEquals(13, UvcCapture.queueDepth(50));
    }

    /** The Otter's IR modes on USB 3 (from its descriptors): 5/10/15/20/30 at every size. */
    private static final List<UsbDesc.Profile> OTTER_USB3 = List.of(
            p("GREY", 640, 400, 15), p("GREY", 640, 400, 30),
            p("GREY", 1280, 800, 5), p("GREY", 1280, 800, 10), p("GREY", 1280, 800, 15),
            p("GREY", 1280, 800, 20), p("GREY", 1280, 800, 30), p("MJPG", 1280, 800, 60));

    @Test public void otterChoices() {
        FpsChoice c = FpsChoice.of(OTTER_USB3, 15);
        assertEquals(1280, c.width);
        assertEquals(20, c.boost);
        assertEquals(30, c.max);
        assertEquals(0, c.floorFor(FpsChoice.STOCK));
        assertEquals(20, c.floorFor(FpsChoice.BOOST));
        assertEquals(30, c.floorFor(FpsChoice.MAX));
        assertEquals(565, c.mbps(30));      // measured about 580 Mbit/s
    }

    @Test public void ferretBoostIsTheMiddleRate() {
        FpsChoice c = FpsChoice.of(OTTER_USB3, 10);     // Ferret: stock 10 behind a bridge
        assertEquals(15, c.boost);                      // closest to sqrt(10 * 30) = 17.3
        assertEquals(30, c.max);
    }

    @Test public void scannerAlreadyAtItsTopRateOffersNothing() {
        FpsChoice c = FpsChoice.of(OTTER_USB3, 30);
        assertEquals(0, c.boost);
        assertEquals(0, c.max);
        assertEquals(0, c.floorFor(FpsChoice.MAX));     // falls back to Stock
    }

    @Test public void unknownStockOffersMaxOnly() {
        FpsChoice c = FpsChoice.of(OTTER_USB3, 0);
        assertEquals(0, c.boost);
        assertEquals(30, c.max);
    }

    @Test public void usb2ModesGiveNoChoice() {
        // the Otter on USB 2 tops out at 15 at 1280x800
        List<UsbDesc.Profile> usb2 = List.of(p("GREY", 1280, 800, 10), p("GREY", 1280, 800, 15));
        assertEquals(0, FpsChoice.of(usb2, 15).max);
    }

    @Test public void formatsAreSeparate() {
        List<UsbDesc.Profile> mixed = List.of(p("GREY", 1280, 800, 15), p("GREY", 1280, 800, 30), p("MJPG", 1280, 800, 15));
        assertEquals(List.of("GREY 1280x800@30", "MJPG 1280x800@15"), kept(mixed, 30));
    }
}
