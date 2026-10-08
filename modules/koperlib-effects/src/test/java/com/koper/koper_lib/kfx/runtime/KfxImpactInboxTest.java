package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.network.KfxImpactPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxImpactInboxTest {
    @Test
    void impactPayloadRoundTripsTheAuthoritativeContact() {
        KfxImpact impact = new KfxImpact(44, 3, 9,
            UUID.fromString("11111111-1111-1111-1111-111111111111"), null, new BlockPos(5, 6, 7),
            new Vec3(5.25, 6.5, 7.75), new Vec3(0, 1, 0),
            new Vec3(2, -3, 1), new Vec3(2, 3, 1), 2, 7788, Map.of());
        KfxImpactPayload payload = KfxImpactPayload.from(impact);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);

        KfxImpactPayload.CODEC.encode(buffer, payload);
        KfxImpactPayload decoded = KfxImpactPayload.CODEC.decode(buffer);

        assertEquals(payload, decoded);
        buffer.release();
    }

    @Test
    void clientInboxIgnoresDuplicateAndLateSequences() {
        KfxImpactInbox inbox = new KfxImpactInbox();
        KfxImpactPayload nine = payload(9);
        KfxImpactPayload ten = payload(10);

        assertTrue(inbox.accept(nine));
        assertFalse(inbox.accept(nine));
        assertFalse(inbox.accept(payload(8)));
        assertTrue(inbox.accept(ten));
        assertEquals(ten, inbox.latest(44));
    }

    @Test
    void stoppingAnEffectForgetsItsSequenceEntry() {
        KfxImpactInbox inbox = new KfxImpactInbox();
        inbox.accept(payload(4));

        inbox.forget(44);

        assertNull(inbox.latest(44));
        assertTrue(inbox.accept(payload(1)));
    }

    private static KfxImpactPayload payload(long sequence) {
        return KfxImpactPayload.from(new KfxImpact(44, 3, sequence,
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"), null,
            new Vec3(1, 2, 3), new Vec3(-1, 0, 0), new Vec3(4, 0, 0), Vec3.ZERO,
            0, 77, Map.of()));
    }
}
