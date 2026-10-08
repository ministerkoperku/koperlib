package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// callers hand over seconds since the clip started and that number only grows. the
// sampler clamps past the last key, so a walk cycle used to play once and then
// freeze on its last frame while the mob carried on walking
class KodelLoopTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry trex() throws Exception {
        try (InputStream in = KodelLoopTest.class.getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in);
            return KodelBook.put("trex", KodelLoader.read(in));
        }
    }

    @Test
    void aLoopingClipComesBackAroundInsteadOfFreezing() throws Exception {
        KodelBook.Entry entry = trex();
        KodelAnimation walk = entry.clip("walking_animation");
        assertNotNull(walk);
        assertTrue(walk.loop, "the walk cycle is a looping clip");

        float[] atQuarter = KodelBook.pose("trex", "walking_animation", walk.length * 0.25f, null).clone();
        float[] end = KodelBook.pose("trex", "walking_animation", walk.length, null).clone();

        // a full lap later the pose has to be the same again
        float[] lapLater = KodelBook.pose("trex", "walking_animation",
            walk.length * 5.25f, null).clone();
        assertArrayEquals(atQuarter, lapLater, 1e-4f,
            "five laps on it should look exactly like a quarter of the way in");

        // and it must not be stuck on the closing frame
        boolean differs = false;
        for (int i = 0; i < end.length; i++) {
            if (Math.abs(end[i] - lapLater[i]) > 1e-3f) differs = true;
        }
        assertTrue(differs, "the clip is frozen on its last frame, which is the bug this test exists for");
    }

    @Test
    void aClipThatDoesNotLoopStillHoldsItsLastFrame() throws Exception {
        KodelBook.Entry entry = trex();
        KodelAnimation once = entry.clip("attack_poison_animation");
        assertNotNull(once);
        assertFalse(once.loop, "the poison attack is a one shot");

        float[] atEnd = KodelBook.pose("trex", "attack_poison_animation", once.length, null).clone();
        float[] wayPast = KodelBook.pose("trex", "attack_poison_animation", once.length * 4f, null).clone();
        assertArrayEquals(atEnd, wayPast, 1e-4f, "a one shot holds, it does not restart");
    }

    @Test
    void foldHandlesTheAwkwardInputs() throws Exception {
        KodelBook.Entry entry = trex();
        KodelAnimation walk = entry.clip("walking_animation");

        assertEquals(0f, KodelBook.fold(walk, 0f), 1e-6f);
        assertEquals(0f, KodelBook.fold(walk, walk.length), 1e-6f, "a whole lap lands back on zero");
        assertEquals(walk.length * 0.5f, KodelBook.fold(walk, walk.length * 3.5f), 1e-5f);
        assertTrue(KodelBook.fold(walk, -0.3f) >= 0f, "negative time must not sample off the front");
        assertEquals(0f, KodelBook.fold(null, 5f), 1e-6f);
    }

    @Test
    void blendingFoldsBothSidesToo() throws Exception {
        KodelBook.Entry entry = trex();
        KodelAnimation walk = entry.clip("walking_animation");
        KodelAnimation idle = entry.clip("idle_animation");

        float[] near = KodelBook.poseBlended("trex", "walking_animation", walk.length * 0.25f,
            "idle_animation", idle.length * 0.25f, 0.5f, null).clone();
        float[] far = KodelBook.poseBlended("trex", "walking_animation", walk.length * 6.25f,
            "idle_animation", idle.length * 4.25f, 0.5f, null).clone();
        assertArrayEquals(near, far, 1e-4f, "a fade must wrap both clips, not just the one it fades to");
    }
}
