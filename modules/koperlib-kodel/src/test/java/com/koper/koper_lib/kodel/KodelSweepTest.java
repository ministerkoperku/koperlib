package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a model that blinks out mid animation is geometry that went NaN, so sweep every
// clip of every shipped model at a fine step and look at every float that comes out
class KodelSweepTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry load(String name) throws Exception {
        try (InputStream in = KodelSweepTest.class.getResourceAsStream("/" + name + ".kodel")) {
            assertNotNull(in, name + ".kodel missing from test resources");
            return KodelBook.put(name, KodelLoader.read(in));
        }
    }

    private static void sweep(String name) throws Exception {
        KodelBook.Entry entry = load(name);
        KodelModel model = entry.model();
        assertFalse(entry.clips().isEmpty(), name + " ships no clips");

        for (KodelAnimation clip : entry.clips()) {
            // two full laps, so wrapping is covered as well as the body of the clip
            float step = Math.max(clip.length / 60f, 1e-3f);
            for (float t = 0f; t <= clip.length * 2f; t += step) {
                float[] pose = KodelBook.pose(name, clip.name, t, null);
                assertNotNull(pose, name + " gave no pose for " + clip.name);
                for (int i = 0; i < pose.length; i++) {
                    assertTrue(finite(pose[i]),
                        name + " clip '" + clip.name + "' bone " + (i / 16)
                            + " element " + (i % 16) + " went " + pose[i] + " at t=" + t);
                }
                float[] mesh = KodelModelRender.bake(model, pose, null);
                assertTrue(mesh.length > 0, name + " baked nothing at t=" + t);
                for (float f : mesh) {
                    assertTrue(finite(f), name + " clip '" + clip.name + "' baked " + f + " at t=" + t);
                }
            }
        }
    }

    private static boolean finite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }

    @Test
    void kopertrexNeverGoesNaN() throws Exception {
        sweep("kopertrex");
    }

    @Test
    void kapokaNeverGoesNaN() throws Exception {
        sweep("kapoka");
    }

    // the fade is the newest code in the path and the likeliest place for a zero
    // length quaternion to sneak through
    @Test
    void noFadeBetweenAnyTwoClipsGoesNaN() throws Exception {
        for (String name : new String[] {"kopertrex", "kapoka"}) {
            KodelBook.clear();
            KodelBook.Entry entry = load(name);
            var clips = entry.clips();
            for (KodelAnimation from : clips) {
                for (KodelAnimation to : clips) {
                    for (float mix = 0f; mix <= 1f; mix += 0.1f) {
                        float[] pose = KodelBook.poseBlended(name, from.name, from.length * 0.37f,
                            to.name, to.length * 0.61f, mix, null);
                        assertNotNull(pose);
                        for (float f : pose) {
                            assertTrue(finite(f), name + " fading " + from.name + " -> " + to.name
                                + " at mix " + mix + " produced " + f);
                        }
                    }
                }
            }
        }
    }

    // and the model must not collapse to a point, which renders as nothing at all
    @Test
    void theModelKeepsItsSizeThroughEveryClip() throws Exception {
        for (String name : new String[] {"kopertrex", "kapoka"}) {
            KodelBook.clear();
            KodelBook.Entry entry = load(name);
            KodelModel model = entry.model();
            float rest = extent(KodelModelRender.bake(model, entry.restPose(), null));
            assertTrue(rest > 0.01f, name + " has no size even at rest");

            for (KodelAnimation clip : entry.clips()) {
                for (float t = 0f; t <= clip.length; t += clip.length / 24f) {
                    float got = extent(KodelModelRender.bake(model,
                        KodelBook.pose(name, clip.name, t, null), null));
                    assertTrue(got > rest * 0.1f,
                        name + " clip '" + clip.name + "' collapsed to " + got
                            + " against " + rest + " at rest, t=" + t);
                }
            }
        }
    }

    private static float extent(float[] mesh) {
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            for (int k = 0; k < 3; k++) {
                mn = Math.min(mn, mesh[i + k]);
                mx = Math.max(mx, mesh[i + k]);
            }
        }
        return mx - mn;
    }
}
