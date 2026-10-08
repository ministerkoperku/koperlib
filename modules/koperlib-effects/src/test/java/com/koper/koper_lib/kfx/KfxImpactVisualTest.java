package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kfx.runtime.KfxImpact;
import com.koper.koper_lib.network.KfxImpactPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxImpactVisualTest {
    @Test
    void authoritativeImpactBecomesAnIndependentClientBurst() {
        var payload = KfxImpactPayload.from(new KfxImpact(4, 0, 2, UUID.randomUUID(), null,
            BlockPos.ZERO, new Vec3(1, 2, 3), new Vec3(0, 1, 0), Vec3.ZERO, Vec3.ZERO, 0, 9, Map.of()));

        var burst = KfxImpactVisual.burst(payload, 0xff112233, 0xffddeeff, 0.2f);

        assertEquals(new Vec3(1, 2, 3), burst.position());
        assertEquals(new Vec3(0, 1, 0), burst.normal());
        assertEquals(0xff112233, burst.color());
        assertEquals(12, burst.lifetime());
    }

    @Test
    void burstGateCapsOneTickAndLiveFallbacks() {
        var gate = new KfxImpactVisual.Gate();
        for (int i = 0; i < KfxImpactVisual.MAX_PER_TICK; i++) assertEquals(true, gate.allow(10, i));
        assertEquals(false, gate.allow(10, 0));
        assertEquals(true, gate.allow(11, 0));
        assertEquals(false, gate.allow(12, KfxImpactVisual.MAX_LIVE));
    }
}
