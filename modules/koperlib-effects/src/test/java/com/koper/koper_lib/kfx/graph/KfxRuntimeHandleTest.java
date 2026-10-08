package com.koper.koper_lib.kfx.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class KfxRuntimeHandleTest {
    @Test
    void effectHandlesAreMonotonicAndNeverZero() {
        long first = KfxRuntime.nextInstanceId();
        long second = KfxRuntime.nextInstanceId();

        assertNotEquals(0, first);
        assertEquals(first + 1, second);
    }
}
