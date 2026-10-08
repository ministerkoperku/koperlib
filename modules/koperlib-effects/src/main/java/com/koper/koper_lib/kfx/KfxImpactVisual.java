package com.koper.koper_lib.kfx;

import com.koper.koper_lib.network.KfxImpactPayload;
import net.minecraft.world.phys.Vec3;

public final class KfxImpactVisual {
    public static final int MAX_PER_TICK = 32;
    public static final int MAX_LIVE = 64;
    private KfxImpactVisual() {}

    public static Burst burst(KfxImpactPayload impact, int color, int coreColor, float thickness) {
        if (impact == null) throw new IllegalArgumentException("KFX impact visual needs an impact");
        Vec3 normal = impact.normal().lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : impact.normal().normalize();
        int burstColor = color != 0 ? color : coreColor;
        return new Burst(impact.position(), normal, burstColor,
            Math.max(0.35f, thickness * 3.0f), Math.max(0.05f, thickness * 0.8f), 12);
    }

    public record Burst(Vec3 position, Vec3 normal, int color, float radius, float thickness, int lifetime) {}

    public static final class Gate {
        private int tick = Integer.MIN_VALUE;
        private int made;

        public boolean allow(int nowTick, int live) {
            if (tick != nowTick) {
                tick = nowTick;
                made = 0;
            }
            if (live >= MAX_LIVE || made >= MAX_PER_TICK) return false;
            made++;
            return true;
        }
    }
}
