package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.network.KfxMotionBatchPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KfxMotionBatchPayloadTest {
    @Test
    void controllerMotionsRoundTripAsOneBoundedPacket() {
        var motion = new KfxMotionBatchPayload.Motion(7, 1, 2, 3, 4, 5, 6);
        var payload = new KfxMotionBatchPayload(List.of(motion));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);

        KfxMotionBatchPayload.CODEC.encode(buffer, payload);
        var decoded = KfxMotionBatchPayload.CODEC.decode(buffer);

        assertEquals(payload, decoded);
        buffer.release();
    }

    @Test
    void batchRejectsMoreThanTheControllerCap() {
        var values = java.util.stream.LongStream.range(0, KfxMotionBatchPayload.MAX_MOTIONS + 1L)
            .mapToObj(id -> new KfxMotionBatchPayload.Motion(id, 0, 0, 0, 0, 0, 0)).toList();
        assertThrows(IllegalArgumentException.class, () -> new KfxMotionBatchPayload(values));
    }
}
