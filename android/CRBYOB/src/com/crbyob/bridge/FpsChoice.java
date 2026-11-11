package com.crbyob.bridge;

import java.util.ArrayList;
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
 *
 * A scanner with several scan modes (the Raptor: blue-line at 1920x1200, Infrared at 1280x800 or 960x600) has a
 * Stock rate and a top rate per IR size, so each size it has rates for (scanners.json bridge_fps/fps_cap by size,
 * or a rate learned at that size) gets its own Boost and Max. Other sizes share the choice the dashboard shows.
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

    /** Rates the bridge learned per IR size (Creality Scan's ask at Stock), 0 = none. */
    interface Learned {
        int fps(int width, int height);
    }

    /** At the scanner's largest Y8 IR size, with no cap. */
    static FpsChoice of(List<UsbDesc.Profile> irProfiles, int stock) {
        int[] s = largestY8(irProfiles);
        return of(irProfiles, s[0], s[1], stock, 0);
    }

    /**
     * irProfiles: the IR stream's UVC modes. stock: Creality Scan's own rate at w x h (0 = unknown). cap: the
     * scanner's top rate at that size in the scan mode that uses it (0 = none); faster UVC modes don't count. Max is
     * offered when the scanner has a faster mode than Stock (or Stock is unknown). Boost is offered only when Stock is
     * known, and is the scanner's in-between rate closest to halfway between them (the lower one on a tie): 20 for an
     * Otter (15 -> 30). With only one rate above Stock, that rate is Boost and there is no Max (choosing Max runs as
     * Boost): the Raptor's Infrared, 20 -> 30, where the scanner tops out at 30.
     */
    static FpsChoice of(List<UsbDesc.Profile> irProfiles, int w, int h, int stock, int cap) {
        TreeSet<Integer> rates = new TreeSet<>();
        for (UsbDesc.Profile p : irProfiles)
            if (p.fourcc.equals("GREY") && p.width == w && p.height == h && (cap <= 0 || p.fps <= cap)) rates.add(p.fps);
        if (rates.isEmpty()) return new FpsChoice(0, 0, stock, 0, 0);
        int top = rates.last();
        int max = stock <= 0 || top > stock ? top : 0;
        int boost = 0;
        if (stock > 0 && max > 0) {
            double target = (stock + max) / 2.0;
            for (int r : rates.subSet(stock, false, max, false))
                if (boost == 0 || Math.abs(r - target) < Math.abs(boost - target)) boost = r;
            if (boost == 0) {
                boost = max;
                max = 0;
            }
        }
        return new FpsChoice(w, h, stock, boost, max);
    }

    /** Creality Scan's Stock rate at w x h: the model's, else the learned one, else 0. */
    static int stockAt(ScannerModel m, Learned learned, int w, int h) {
        int s = m.stockFps(w, h);
        return s > 0 ? s : learned.fps(w, h);
    }

    /** Whether w x h has rates of its own: the model lists that size, or (no model rate) one was learned there. */
    static boolean ownRates(ScannerModel m, Learned learned, int w, int h) {
        return m.hasSizeRates(w, h) || m.stockFps(w, h) <= 0 && learned.fps(w, h) > 0;
    }

    static FpsChoice at(List<UsbDesc.Profile> ir, ScannerModel m, Learned learned, int w, int h) {
        return of(ir, w, h, stockAt(m, learned, w, h), m.fpsCap(w, h));
    }

    /** The choice the dashboard shows: the largest Y8 IR size whose Stock is known, else the largest. */
    static FpsChoice shown(List<UsbDesc.Profile> ir, ScannerModel m, Learned learned) {
        int[] best = null;
        for (int[] s : y8Sizes(ir))
            if (stockAt(m, learned, s[0], s[1]) > 0 && (best == null || area(s) > area(best))) best = s;
        if (best == null) best = largestY8(ir);
        return at(ir, m, learned, best[0], best[1]);
    }

    /** The other sizes with rates of their own, largest first (shown as a note under the choice). */
    static List<FpsChoice> others(List<UsbDesc.Profile> ir, ScannerModel m, Learned learned) {
        FpsChoice main = shown(ir, m, learned);
        List<FpsChoice> out = new ArrayList<>();
        for (int[] s : y8Sizes(ir))
            if ((s[0] != main.width || s[1] != main.height) && ownRates(m, learned, s[0], s[1]))
                out.add(at(ir, m, learned, s[0], s[1]));
        out.sort((a, b) -> Long.compare((long) b.width * b.height, (long) a.width * a.height));
        return out;
    }

    /**
     * The 0xFC1 fps floor for modes of size w x h (any stream): that size's own choice if it has rates of its own,
     * else the shown one's (so a model with one bridge_fps, like the Otter, has one floor for every stream).
     */
    static int floor(List<UsbDesc.Profile> ir, ScannerModel m, Learned learned, String mode, int w, int h) {
        FpsChoice c = ownRates(m, learned, w, h) ? at(ir, m, learned, w, h) : shown(ir, m, learned);
        return c.floorFor(normalize(mode));
    }

    private static long area(int[] s) {
        return (long) s[0] * s[1];
    }

    private static List<int[]> y8Sizes(List<UsbDesc.Profile> ir) {
        List<int[]> out = new ArrayList<>();
        for (UsbDesc.Profile p : ir) {
            if (!p.fourcc.equals("GREY")) continue;
            boolean seen = false;
            for (int[] s : out) seen |= s[0] == p.width && s[1] == p.height;
            if (!seen) out.add(new int[]{p.width, p.height});
        }
        return out;
    }

    private static int[] largestY8(List<UsbDesc.Profile> ir) {
        int[] best = {0, 0};
        for (int[] s : y8Sizes(ir)) if (area(s) > area(best)) best = s;
        return best;
    }

    /**
     * The fps floor for a mode: 0 (no floor) for Stock, or for a mode this scanner doesn't offer. Max with only a Boost
     * (one rate above Stock) runs as Boost.
     */
    int floorFor(String mode) {
        if (BOOST.equals(mode)) return boost;
        if (MAX.equals(mode)) return max > 0 ? max : boost;
        return 0;
    }

    /** The mode a saved choice runs as here: Max with only a Boost runs as Boost, an unoffered one as Stock. */
    String effective(String mode) {
        if (MAX.equals(mode)) return max > 0 ? MAX : boost > 0 ? BOOST : STOCK;
        if (BOOST.equals(mode)) return boost > 0 ? BOOST : STOCK;
        return STOCK;
    }

    /**
     * "Rates above are for 1920x1200. At 1280x800: Stock 20, Boost 30 fps (Max runs as Boost)." for a scanner whose scan modes use
     * different IR sizes (the Raptor's Infrared runs at 1280x800 or 960x600).
     */
    static String othersNote(FpsChoice shown, List<FpsChoice> others) {
        StringBuilder b = new StringBuilder("Rates above are for " + shown.width + "x" + shown.height + ".");
        for (FpsChoice o : others) {
            if (o.width == 0) continue;
            List<String> parts = new ArrayList<>();
            if (o.stock > 0) parts.add("Stock " + o.stock);
            if (o.boost > 0) parts.add("Boost " + o.boost);
            if (o.max > 0) parts.add("Max " + o.max);
            if (parts.isEmpty()) continue;
            b.append(" At ").append(o.width).append('x').append(o.height).append(": ").append(String.join(", ", parts))
                    .append(" fps").append(shown.max > 0 && o.max == 0 && o.boost > 0 ? " (Max runs as Boost)." : ".");
        }
        return b.toString();
    }

    /** Rough bridge traffic at fps: two raw IR streams plus about 15% for color and packet overhead. */
    int mbps(int fps) {
        return (int) Math.round(2.0 * width * height * fps * 8 * 1.15 / 1e6);
    }

    static String normalize(String mode) {
        return BOOST.equals(mode) || MAX.equals(mode) ? mode : STOCK;
    }
}
