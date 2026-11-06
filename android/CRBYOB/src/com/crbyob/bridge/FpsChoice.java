package com.crbyob.bridge;

import java.util.List;
import java.util.TreeSet;

/**
 * The scanner frame-rate choice: Stock (what Creality Scan asks a bridge for), Boost and Max.
 *
 * Creality Scan takes its fps from a built-in table (15 for an Otter behind a bridge, 10 for a Ferret). For each
 * stream it then keeps the smallest advertised fps that is at least that value (lib_orbbec_scan.dll 0x1808E5DA0).
 * So hiding the scanner's slower modes from the 0xFC1 list (UsbDesc.belowFpsFloor) raises the rate it asks for.
 * The choice is saved as a mode, not a number, and resolved against whichever scanner is attached:
 * Max = its fastest IR mode, Boost = a mode in between.
 */
final class FpsChoice {
    static final String STOCK = "stock", BOOST = "boost", MAX = "max";

    final int width, height;   // the IR size the rates apply to (the scanner's largest Y8 mode)
    final int stock;           // 0 = unknown
    final int boost, max;      // 0 = not offered for this scanner

    private FpsChoice(int width, int height, int stock, int boost, int max) {
        this.width = width;
        this.height = height;
        this.stock = stock;
        this.boost = boost;
        this.max = max;
    }

    /**
     * irProfiles: the IR stream's UVC modes. stock: Creality Scan's own rate (0 = unknown). Max is offered when the
     * scanner has a faster mode than Stock (or Stock is unknown). Boost is offered only when Stock is known, and is the
     * scanner's in-between rate closest to halfway between them (the lower one on a tie): 20 for an Otter (15 -> 30).
     */
    static FpsChoice of(List<UsbDesc.Profile> irProfiles, int stock) {
        int w = 0, h = 0;
        for (UsbDesc.Profile p : irProfiles)
            if (p.fourcc.equals("GREY") && (long) p.width * p.height > (long) w * h) { w = p.width; h = p.height; }
        TreeSet<Integer> rates = new TreeSet<>();
        for (UsbDesc.Profile p : irProfiles)
            if (p.fourcc.equals("GREY") && p.width == w && p.height == h) rates.add(p.fps);
        if (rates.isEmpty()) return new FpsChoice(0, 0, stock, 0, 0);
        int top = rates.last();
        int max = stock <= 0 || top > stock ? top : 0;
        int boost = 0;
        if (stock > 0 && max > 0) {
            double target = (stock + max) / 2.0;
            for (int r : rates.subSet(stock, false, max, false))
                if (boost == 0 || Math.abs(r - target) < Math.abs(boost - target)) boost = r;
        }
        return new FpsChoice(w, h, stock, boost, max);
    }

    /** The fps floor for a mode: 0 (no floor) for Stock, or for a mode this scanner doesn't offer. */
    int floorFor(String mode) {
        if (BOOST.equals(mode)) return boost;
        if (MAX.equals(mode)) return max;
        return 0;
    }

    /** Rough bridge traffic at fps: two raw IR streams plus about 15% for color and packet overhead. */
    int mbps(int fps) {
        return (int) Math.round(2.0 * width * height * fps * 8 * 1.15 / 1e6);
    }

    static String normalize(String mode) {
        return BOOST.equals(mode) || MAX.equals(mode) ? mode : STOCK;
    }
}
