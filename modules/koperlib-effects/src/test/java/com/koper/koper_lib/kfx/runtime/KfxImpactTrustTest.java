package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.network.KfxImpactPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxImpactTrustTest {
    @Test
    void clientOnlyContactCannotInvokeServerGameplayListeners() {
        AtomicInteger gameplayCalls = new AtomicInteger();
        KfxEventListener listener = (level, impact) -> gameplayCalls.incrementAndGet();
        KfxControllerRuntime.addEventListener(listener);
        KfxImpact impact = new KfxImpact(77, 0, 1,
            UUID.fromString("11111111-1111-1111-1111-111111111111"), null, BlockPos.ZERO,
            Vec3.ZERO, new Vec3(0, 1, 0), new Vec3(0, -1, 0), Vec3.ZERO, 0, 99, Map.of());
        KfxImpactInbox clientVisuals = new KfxImpactInbox();

        assertTrue(clientVisuals.accept(KfxImpactPayload.from(impact)));
        assertEquals(0, gameplayCalls.get());

        KfxControllerRuntime.dispatch(null, impact);
        assertEquals(1, gameplayCalls.get());
        KfxControllerRuntime.removeEventListener(listener);
    }
}
