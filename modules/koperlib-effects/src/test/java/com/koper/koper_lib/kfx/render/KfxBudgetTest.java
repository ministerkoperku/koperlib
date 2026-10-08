package com.koper.koper_lib.kfx.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KfxBudgetTest {
    @Test
    void nativeProgramHasVersionedLittleEndianHeaderAndStableRoundTrip() {
        KfxNativeProgram program = new KfxNativeProgram(0x1020304050607080L,
            List.of(new KfxNativeProgram.Node(17, 1, false, 40, new float[]{1.5f, 2.5f})),
            80, 45, KfxNativeProgram.Overflow.SKIP_DECORATIVE);

        byte[] encoded = program.encode();
        assertArrayEquals("KFX2".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(encoded, 4));
        assertEquals(2, ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getShort(4));
        assertEquals(program, KfxNativeProgram.decode(encoded));
    }

    @Test
    void qualityScalesParticleCreationBudgetBeforeNativeAllocation() {
        assertEquals(100, KfxQuality.HIGH.particleBudget(100));
        assertEquals(70, KfxQuality.MEDIUM.particleBudget(100));
        assertEquals(40, KfxQuality.LOW.particleBudget(100));
    }

    @Test
    void rejectPolicyRefusesAnOverBudgetProgram() {
        assertThrows(IllegalArgumentException.class, () -> new KfxNativeProgram(4L,
            List.of(new KfxNativeProgram.Node(1, 1, false, 81, new float[0])),
            80, 20, KfxNativeProgram.Overflow.REJECT));
    }
}
