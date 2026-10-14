package com.koper.koper_lib.kender;

// counts what can make a flying build stutter on screen: a client tick with no fresh server pose (the
// render predicts), a pose that replaced one nobody rendered yet (two packets in one tick), and a step
// of more than one server tick at once. every 10 s with anything to report it writes one line, so a
// player's log says whether the network timing or something else is behind the jitter
final class KontraStutterMeter {
    private KontraStutterMeter() {}

    private static int ticks, predicted, overwritten, jumps, maxJump;
    private static long lastReport = System.nanoTime();

    static void arrived(boolean replacedUnrendered) {
        if (replacedUnrendered) overwritten++;
    }

    static void latched(boolean fresh, long fromTick, long toTick) {
        ticks++;
        if (!fresh) predicted++;
        else if (toTick - fromTick > 1) {
            jumps++;
            maxJump = (int) Math.max(maxJump, toTick - fromTick);
        }
        long now = System.nanoTime();
        if (now - lastReport < 10_000_000_000L) return;
        lastReport = now;
        if (predicted + overwritten + jumps > 0)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                "[KontraStutter] last 10 s over {} build ticks: {} without a fresh pose, {} poses dropped, {} multi tick steps (largest {})",
                ticks, predicted, overwritten, jumps, maxJump);
        ticks = predicted = overwritten = jumps = maxJump = 0;
    }
}
