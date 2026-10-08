package com.koper.koper_lib.kender;


import java.util.Locale;

// where the fuck does the kontra frame actually go. off by default, plain static bool because
// everything here runs on the client/render thread and nothing else ever touches it.
public final class KontraLagSniffer {

    public static boolean on;

    private KontraLagSniffer() {}

    // timings
    private static long loopNs, beNs, flushNs;
    private static int frames;

    // per-frame counters, summed across frames then divided at drain
    private static long kontras, seen, cullMask, cullDist, cullFrust, drawGpu, drawInst, drawCpu;
    private static long beDrawn, beCulled, upFloats;

    public static long mark() { return on ? System.nanoTime() : 0L; }

    public static void loopDone(long start) { if (on && start != 0L) loopNs += System.nanoTime() - start; }
    public static void beDone(long start)   { if (on && start != 0L) beNs   += System.nanoTime() - start; }
    public static void flushDone(long start){ if (on && start != 0L) flushNs += System.nanoTime() - start; }

    public static void frame()      { if (on) frames++; }
    public static void kontra()     { if (on) kontras++; }
    public static void seen(int n)  { if (on) seen += n; }
    public static void maskCull()   { if (on) cullMask++; }
    public static void distCull()   { if (on) cullDist++; }
    public static void frustCull(int n) { if (on) cullFrust += n; }
    public static void gpu()        { if (on) drawGpu++; }
    public static void inst()       { if (on) drawInst++; }
    public static void cpu()        { if (on) drawCpu++; }
    public static void be(boolean drawn) { if (on) { if (drawn) beDrawn++; else beCulled++; } }
    public static void uploaded(int floats) { if (on) upFloats += floats; }

    public static String drain() {
        if (frames == 0) return "prof: no frames sampled (is a kontra in view?)";
        double f = frames;
        String out = String.format(Locale.ROOT,
            "prof %d frames | %.1f kontra %.0f blocks/f | cull mask %.0f dist %.0f frustum %.0f | "
            + "draw gpu %.0f inst %.0f cpu %.0f | be %.0f drawn %.0f culled | "
            + "loop %.2fms be %.2fms flush %.2fms | up %.0fk floats/f",
            frames, kontras / f, seen / f, cullMask / f, cullDist / f, cullFrust / f,
            drawGpu / f, drawInst / f, drawCpu / f, beDrawn / f, beCulled / f,
            loopNs / 1e6 / f, beNs / 1e6 / f, flushNs / 1e6 / f, upFloats / f / 1000.0);
        reset();
        return out;
    }

    public static void reset() {
        loopNs = beNs = flushNs = 0;
        frames = 0;
        kontras = seen = cullMask = cullDist = cullFrust = drawGpu = drawInst = drawCpu = 0;
        beDrawn = beCulled = upFloats = 0;
    }

    public static String toggle() {
        on = !on;
        reset();
        String msg = on ? "prof ON — fly around, then /kender prof again for the numbers" : "prof OFF";
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-prof] {}", msg);
        return msg;
    }
}
