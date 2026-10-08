package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a real .kodel off disk, not a hand written toy. replaces two scratch tests that
// dumped floats into a temp dir and asserted absolutely nothing
class KodelKopertrexTest {

    private static KodelLoader.Loaded trex() throws Exception {
        try (InputStream in = KodelKopertrexTest.class.getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in, "kopertrex.kodel missing from test resources");
            return KodelLoader.read(in);
        }
    }

    @Test
    void loadsAndTheHierarchyIsWalkable() throws Exception {
        KodelLoader.Loaded loaded = trex();
        KodelModel model = loaded.model();
        assertTrue(model.bones.size() > 1, "trex should have a skeleton");
        model.validate();
        assertTrue(model.texWidth > 0 && model.texHeight > 0, "atlas size must survive the manifest");
    }

    @Test
    void bakingTheRestPoseProducesFiniteGeometry() throws Exception {
        KodelModel model = trex().model();
        float[] world = new float[model.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(model, null, 0f, new float[3], new float[3], new float[3], world);
        float[] mesh = KodelModelRender.bake(model, world, null);

        assertTrue(mesh.length >= KodelModelRender.STRIDE, "nothing got baked");
        for (float f : mesh) {
            assertFalse(Float.isNaN(f) || Float.isInfinite(f), "bake produced " + f);
        }
        // uvs are normalised into 0..1 against the atlas, anything outside means the
        // unwrap table drifted again
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            float u = mesh[i + 3];
            float v = mesh[i + 4];
            assertTrue(u >= -0.001f && u <= 1.001f, "u off the atlas: " + u);
            assertTrue(v >= -0.001f && v <= 1.001f, "v off the atlas: " + v);
        }
    }

    @Test
    void everyClipActuallyMovesSomething() throws Exception {
        KodelLoader.Loaded loaded = trex();
        KodelModel model = loaded.model();
        assertNotNull(loaded.animations(), "trex ships clips");
        assertFalse(loaded.animations().isEmpty());

        for (KodelAnimation anim : loaded.animations()) {
            KodelSampler.ResolvedTracks tracks = KodelSampler.ResolvedTracks.of(model, anim);
            int bound = 0;
            for (int i = 0; i < model.bones.size(); i++) {
                if (tracks.rotation[i] != null || tracks.position[i] != null || tracks.scale[i] != null) bound++;
            }
            assertTrue(bound > 0, "clip '" + anim.name + "' binds to no bone at all");

            float[] p = new float[3], r = new float[3], s = new float[3];
            float[] a = new float[model.bones.size() * 16];
            float[] b = new float[model.bones.size() * 16];
            KodelSampler.samplePose(model, tracks, 0f, p, r, s, a);
            KodelSampler.samplePose(model, tracks, anim.length * 0.5f, p, r, s, b);
            int moved = 0;
            for (int i = 0; i < a.length; i++) {
                if (Math.abs(a[i] - b[i]) > 1e-4f) moved++;
            }
            assertTrue(moved > 0, "clip '" + anim.name + "' is frozen between t=0 and halfway");
        }
    }
}
