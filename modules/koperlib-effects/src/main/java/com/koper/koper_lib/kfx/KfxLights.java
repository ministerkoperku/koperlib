package com.koper.koper_lib.kfx;

import net.minecraft.world.phys.Vec3;

public final class KfxLights {
    private KfxLights() {}

    public static float extraLightAt(Vec3 pos) {
        float best = 0.0f;
        for (KfxInstance fx : KfxClient.live()) {
            if (fx.light.radius() <= 0.0f) continue;
            double dx = pos.x - fx.sx;
            double dy = pos.y - fx.sy;
            double dz = pos.z - fx.sz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float t = 1.0f - (float)(dist / fx.light.radius());
            if (t > 0.0f) best = Math.max(best, t * fx.light.intensity());
        }
        return best;
    }
}
