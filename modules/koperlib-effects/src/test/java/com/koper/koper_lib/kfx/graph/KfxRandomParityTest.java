package com.koper.koper_lib.kfx.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxRandomParityTest {
    @Test
    void splitmix64MatchesTheNativeGoldenVector() {
        assertEquals(0L, KfxRandom.mix64(0L));
        assertEquals(0x5692161d100b05e5L, KfxRandom.mix64(1L));
    }
}
