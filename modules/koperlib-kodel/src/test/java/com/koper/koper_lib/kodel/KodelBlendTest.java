package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// cross-fading two clips. hard switching is what makes a mob snap between walk and
// idle, and blending finished matrices instead of the bone's own trs shears anything
// mid-rotation, so both ends of that are pinned here
class KodelBlendTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry trex() throws Exception {
        try (InputStream in = KodelBlendTest.class.getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in);
            return KodelBook.put("trex", KodelLoader.read(in));
        }
    }

    @Test
    void theEndsOfTheFadeAreTheClipsThemselves() throws Exception {
        trex();
        float[] walk = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] idle = KodelBook.pose("trex", "idle_animation", 0.4f, null).clone();

        float[] atStart = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            "idle_animation", 0.4f, 0f, null).clone();
        float[] atEnd = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            "idle_animation", 0.4f, 1f, null).clone();

        assertArrayEquals(walk, atStart, 1e-4f, "mix 0 has to be the clip it fades from");
        assertArrayEquals(idle, atEnd, 1e-4f, "mix 1 has to be the clip it fades to");
    }

    @Test
    void halfwayActuallySitsBetweenTheTwo() throws Exception {
        KodelBook.Entry entry = trex();
        float[] walk = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] idle = KodelBook.pose("trex", "idle_animation", 0.4f, null).clone();
        float[] mid = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            "idle_animation", 0.4f, 0.5f, null).clone();

        int moved = 0;
        for (int b = 0; b < entry.model().bones.size(); b++) {
            int at = b * KodelSampler.MAT4_FLOATS;
            // translation column, the part that is easy to reason about
            for (int k = 12; k < 15; k++) {
                float lo = Math.min(walk[at + k], idle[at + k]);
                float hi = Math.max(walk[at + k], idle[at + k]);
                assertTrue(mid[at + k] >= lo - 1e-3f && mid[at + k] <= hi + 1e-3f,
                    "bone " + b + " element " + k + " left the span: " + mid[at + k]
                        + " not in [" + lo + ", " + hi + "]");
                if (hi - lo > 1e-3f) moved++;
            }
        }
        assertTrue(moved > 0, "these two clips should disagree somewhere, otherwise the test proves nothing");
    }

    // a bone rotating through a blend must keep its length. lerping the matrices
    // instead of the rotation is what breaks this
    @Test
    void blendingDoesNotShrinkTheModel() throws Exception {
        KodelBook.Entry entry = trex();
        KodelModel model = entry.model();
        float restSpan = span(KodelModelRender.bake(model, entry.restPose(), null));

        for (float mix = 0f; mix <= 1f; mix += 0.125f) {
            float[] pose = KodelBook.poseBlended("trex", "walking_animation", 0.1f,
                "attack_animation_bite", 0.3f, mix, null);
            float got = span(KodelModelRender.bake(model, pose, null));
            assertTrue(got > restSpan * 0.5f && got < restSpan * 2f,
                "at mix " + mix + " the model spans " + got + " against " + restSpan + " at rest");
        }
    }

    private static float span(float[] mesh) {
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            mn = Math.min(mn, mesh[i + 1]);
            mx = Math.max(mx, mesh[i + 1]);
        }
        return mx - mn;
    }

    // a mob that stops walking is fading TO nothing. refusing the fade there is what
    // made kapoka hold its walk for the whole transition and then snap to standing
    @Test
    void fadingIntoNothingLandsOnTheBindPose() throws Exception {
        KodelBook.Entry entry = trex();
        float[] walk = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();

        float[] atStart = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            null, 0f, 0f, null).clone();
        assertArrayEquals(walk, atStart, 1e-4f, "mix 0 is still the clip it leaves");

        float[] atEnd = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            null, 0f, 1f, null).clone();
        assertArrayEquals(entry.restPose(), atEnd, 1e-4f, "mix 1 has to land on the bind pose");

        float[] half = KodelBook.poseBlended("trex", "walking_animation", 0.2f,
            null, 0f, 0.5f, null).clone();
        boolean between = false;
        for (int i = 0; i < half.length; i++) {
            float lo = Math.min(walk[i], entry.restPose()[i]);
            float hi = Math.max(walk[i], entry.restPose()[i]);
            assertTrue(half[i] >= lo - 1e-3f && half[i] <= hi + 1e-3f, "element " + i + " left the span");
            if (hi - lo > 1e-3f) between = true;
        }
        assertTrue(between, "the two poses should differ somewhere");
    }

    // and the other way round: standing, then starting to walk
    @Test
    void fadingOutOfNothingStartsFromTheBindPose() throws Exception {
        KodelBook.Entry entry = trex();
        float[] walk = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();

        assertArrayEquals(entry.restPose(),
            KodelBook.poseBlended("trex", null, 0f, "walking_animation", 0.2f, 0f, null).clone(),
            1e-4f, "mix 0 is the bind pose it leaves");
        assertArrayEquals(walk,
            KodelBook.poseBlended("trex", null, 0f, "walking_animation", 0.2f, 1f, null).clone(),
            1e-4f, "mix 1 is the clip it arrives at");
    }

    // an attack should arrive and leave, not appear
    @Test
    void theOverlayWeightRampsInsteadOfSnapping() throws Exception {
        trex();
        float[] plain = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] full = KodelBook.poseLayered("trex", "walking_animation", 0.2f,
            null, 0f, 0f, "attack_animation_bite", 0.15f, 1f, null).clone();

        assertArrayEquals(plain, KodelBook.poseLayered("trex", "walking_animation", 0.2f,
            null, 0f, 0f, "attack_animation_bite", 0.15f, 0f, null).clone(),
            1e-4f, "weight 0 means the overlay is not there yet");

        float[] half = KodelBook.poseLayered("trex", "walking_animation", 0.2f,
            null, 0f, 0f, "attack_animation_bite", 0.15f, 0.5f, null).clone();

        // world matrices compose down the bone chain, so a half blend is NOT the
        // component-wise midpoint and asserting that is wrong. what has to hold is
        // that it sits nearer each end than the ends sit to each other
        float span = distance(plain, full);
        assertTrue(span > 1e-3f, "these two poses should differ somewhere");
        assertTrue(distance(half, plain) < span, "halfway is no closer to the base than the overlay is");
        assertTrue(distance(half, full) < span, "halfway is no closer to the overlay than the base is");
    }

    private static float distance(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }
}
