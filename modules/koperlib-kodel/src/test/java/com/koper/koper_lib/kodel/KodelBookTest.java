package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the cache half of KodelBook, no pack roots and no minecraft involved
class KodelBookTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelLoader.Loaded trex() throws Exception {
        try (InputStream in = KodelBookTest.class.getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in);
            return KodelLoader.read(in);
        }
    }

    @Test
    void anEntryCachesTheRestPoseAndTheHitboxes() throws Exception {
        KodelBook.Entry entry = KodelBook.put("trex", trex());
        assertNotNull(entry);
        assertEquals(entry.model().bones.size() * KodelSampler.MAT4_FLOATS, entry.restPose().length);
        assertSame(entry, KodelBook.get("trex"), "second lookup must not reparse");
        assertTrue(entry.clips().size() > 0);
    }

    @Test
    void anUnknownNameAnswersNullAndStaysNull() {
        assertNull(KodelBook.get("nothing_by_that_name"));
        assertNull(KodelBook.model("nothing_by_that_name"));
        // second call goes through the miss cache rather than the disk again
        assertNull(KodelBook.get("nothing_by_that_name"));
    }

    @Test
    void poseFallsBackToTheRestPoseForAnUnknownClip() throws Exception {
        KodelBook.Entry entry = KodelBook.put("trex", trex());
        assertSame(entry.restPose(), KodelBook.pose("trex", null, 0f, null));
        assertSame(entry.restPose(), KodelBook.pose("trex", "clip_that_is_not_there", 0f, null));
    }

    @Test
    void poseActuallyAnimatesAndReusesTheBufferItIsGiven() throws Exception {
        KodelBook.Entry entry = KodelBook.put("trex", trex());
        String clip = entry.clips().get(0).name;
        float length = entry.clip(clip).length;

        float[] buffer = new float[entry.model().bones.size() * KodelSampler.MAT4_FLOATS];
        float[] out = KodelBook.pose("trex", clip, length * 0.5f, buffer);
        assertSame(buffer, out, "a big enough buffer must be written into, not replaced");

        float[] atZero = KodelBook.pose("trex", clip, 0f, new float[buffer.length]);
        boolean moved = false;
        for (int i = 0; i < buffer.length; i++) {
            if (Math.abs(buffer[i] - atZero[i]) > 1e-4f) moved = true;
        }
        assertTrue(moved, "clip '" + clip + "' should look different halfway through");
    }

    @Test
    void aTooSmallBufferIsReplacedInsteadOfOverrunning() throws Exception {
        KodelBook.put("trex", trex());
        String clip = KodelBook.get("trex").clips().get(0).name;
        float[] tiny = new float[4];
        float[] out = KodelBook.pose("trex", clip, 0f, tiny);
        assertTrue(out.length > tiny.length);
    }

    @Test
    void clearForgetsEverything() throws Exception {
        KodelBook.put("trex", trex());
        assertNotNull(KodelBook.get("trex"));
        KodelBook.clear();
        assertNull(KodelBook.get("trex"), "reload has to drop last session's models");
    }

    @Test
    void hitboxesAtRestComeStraightFromTheCache() throws Exception {
        KodelBook.Entry entry = KodelBook.put("trex", trex());
        assertEquals(entry.hitboxes(), KodelBook.hitboxes("trex", entry.restPose()));
        assertEquals(entry.hitboxes(), KodelBook.hitboxes("trex", null));
    }

    @Test
    void amodelWithoutHitboxBonesReportsNoBounds() throws Exception {
        KodelBook.Entry entry = KodelBook.put("trex", trex());
        if (entry.hitboxes().isEmpty()) {
            assertNull(entry.hitboxBounds(), "no hitbox bones means no bounds");
        } else {
            assertNotNull(entry.hitboxBounds());
            assertArrayEquals(KodelHitboxer.bounds(entry.hitboxes()), entry.hitboxBounds(), 1e-6f);
        }
    }
}
