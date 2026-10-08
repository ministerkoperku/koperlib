package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a mob with no clip at all is normal: kapoka only has a walk, so standing still it
// asks for nothing. every pose entry point has to hand back something drawable for
// that, and every caller has to USE what it hands back.
//
// getting this wrong drew every bone as a zero matrix, which collapses the model into
// a single point. on screen that reads as the mob blinking out whenever it stops
class KodelNoClipTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry trex() throws Exception {
        try (InputStream in = KodelNoClipTest.class.getResourceAsStream("/kapoka.kodel")) {
            assertNotNull(in);
            return KodelBook.put("kapoka", KodelLoader.read(in));
        }
    }

    private static void drawable(float[] pose, KodelModel model, String what) {
        assertNotNull(pose, what + ": no pose at all");
        assertTrue(pose.length >= model.bones.size() * KodelSampler.MAT4_FLOATS, what + ": pose too short");
        for (int b = 0; b < model.bones.size(); b++) {
            int at = b * KodelSampler.MAT4_FLOATS;
            boolean allZero = true;
            for (int k = 0; k < KodelSampler.MAT4_FLOATS; k++) {
                if (pose[at + k] != 0f) allZero = false;
                assertTrue(!Float.isNaN(pose[at + k]), what + ": bone " + b + " went NaN");
            }
            assertTrue(!allZero, what + ": bone " + model.bones.get(b).name
                + " is an all zero matrix, the model would collapse to a point");
        }
    }

    @Test
    void noClipAtAllStillGivesADrawablePose() throws Exception {
        KodelBook.Entry entry = trex();
        drawable(KodelBook.pose("kapoka", null, 0f, null), entry.model(), "pose(null)");
        drawable(KodelBook.poseLayered("kapoka", null, 0f, null, 0f, null),
            entry.model(), "poseLayered(null, null)");
        drawable(KodelBook.poseLayered("kapoka", null, 0f, null, 0f, 0f, null, 0f, 1f, null),
            entry.model(), "poseLayered with every name null");
        drawable(KodelBook.poseBlended("kapoka", null, 0f, null, 0f, 0.5f, null),
            entry.model(), "poseBlended(null, null)");
    }

    @Test
    void anUnknownClipNameIsTheSameCase() throws Exception {
        KodelBook.Entry entry = trex();
        drawable(KodelBook.pose("kapoka", "no_such_clip", 0.4f, null), entry.model(), "unknown clip");
        drawable(KodelBook.poseLayered("kapoka", "no_such_clip", 0.4f, "also_missing", 0.2f, null),
            entry.model(), "unknown clip and unknown overlay");
    }

    // the buffer form must actually fill the buffer, or a caller that trusts it draws
    // a model made entirely of zeros
    @Test
    void theBufferFormFillsTheBufferOrHandsBackSomethingElse() throws Exception {
        KodelBook.Entry entry = trex();
        int need = entry.model().bones.size() * KodelSampler.MAT4_FLOATS;

        float[] given = new float[need];
        float[] got = KodelBook.poseLayered("kapoka", null, 0f, null, 0f, 0f, null, 0f, 1f, given);
        drawable(got, entry.model(), "what poseLayered returned");

        float[] given2 = new float[need];
        float[] got2 = KodelBook.pose("kapoka", null, 0f, given2);
        drawable(got2, entry.model(), "what pose returned");
    }

    // and with a real clip the buffer IS the one that comes back
    @Test
    void aRealClipWritesIntoTheBufferYouGaveIt() throws Exception {
        KodelBook.Entry entry = trex();
        String clip = entry.clips().get(0).name;
        int need = entry.model().bones.size() * KodelSampler.MAT4_FLOATS;
        float[] given = new float[need];
        float[] got = KodelBook.pose("kapoka", clip, 0.1f, given);
        assertArrayEquals(given, got, 0f, "a real clip should have written into the buffer");
        drawable(got, entry.model(), "real clip");
    }

}
