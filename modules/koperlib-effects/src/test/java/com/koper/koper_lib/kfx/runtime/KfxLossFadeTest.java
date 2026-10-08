package com.koper.koper_lib.kfx.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxLossFadeTest {
    @Test
    void anchorLossFadesOnceWithoutRestartingItsClock() {
        var fade = new KfxLossFade();

        fade.begin(10, 8);
        fade.begin(12, 8);

        assertEquals(1.0f, fade.alpha(10), 0.0001f);
        assertEquals(0.5f, fade.alpha(14), 0.0001f);
        assertFalse(fade.done(17.9f));
        assertTrue(fade.done(18));
    }

    @Test
    void explicitDetachCancelsAnAnchorLossFade() {
        var fade = new KfxLossFade();
        fade.begin(10, 8);

        fade.cancel();

        assertEquals(1.0f, fade.alpha(18), 0.0001f);
        assertFalse(fade.done(18));
    }
}
