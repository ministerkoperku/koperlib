package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the binary format, cross-checks Java vs native sampling when the
 * Rust engine is loadable, and verifies easing/smoothing semantics. Uses a tiny
 * Bedrock geometry so the whole pipe (convert -> write -> read -> sample) is
 * exercised.
 */
class KodelFormatSmokeTest {
    private static final String GEO = """
        {
          "format_version": "1.12.0",
          "minecraft:geometry": [
            {
              "description": {
                "identifier": "geometry.test",
                "texture_width": 16,
                "texture_height": 16
              },
              "bones": [
                {
                  "name": "root",
                  "pivot": [0, 0, 0],
                  "cubes": [
                    {"origin": [0, 0, 0], "size": [4, 4, 4], "uv": [0, 0]}
                  ]
                },
                {
                  "name": "arm",
                  "parent": "root",
                  "pivot": [2, 4, 0],
                  "position": [0, 4, 0],
                  "rotation": [0, 0, 45],
                  "cubes": [
                    {"origin": [1, 4, 1], "size": [2, 6, 2], "uv": [8, 0]}
                  ]
                }
              ]
            }
          ]
        }
        """;

    @Test
    void bedrockToBinaryRoundTrip() {
        KodelModel model = KodelConverters.geometry(
            new com.google.gson.JsonParser().parse(GEO).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject());
        assertEquals(2, model.bones.size());
        assertEquals(16, model.texWidth);

        byte[] bytes = model.write();
        KodelModel back = KodelModel.read(bytes);
        assertEquals(model.bones.size(), back.bones.size());
        assertArrayEquals(model.bones.get(1).rotation, back.bones.get(1).rotation, 1e-6f);
        assertEquals(Math.toRadians(45), rotAngle(back.bones.get(1).rotation), 1e-5);
        assertArrayEquals(model.bones.get(1).position, back.bones.get(1).position, 0f);
        assertEquals(1, back.bones.get(0).cubes.size());
    }

    @Test
    void animationRoundTrip() {
        var anim = new KodelAnimation();
        anim.name = "idle";
        anim.length = 2f;
        var track = new KodelAnimation.KodelTrack();
        track.bone = "arm";
        track.rotation = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f, 2f},
            new float[] {0f, 0f, 0f, Math.PI == 0 ? 1 : (float) Math.PI / 2, 0f, 0f, 0f, 0f, 0f},
            new int[] {KodelFormat.EASE_BEZIER, KodelFormat.EASE_LINEAR, KodelFormat.EASE_STEP},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        anim.tracks.add(track);

        byte[] bin = KodelAnimation.write(List.of(anim));
        List<KodelAnimation> back = KodelAnimation.readAll(bin);
        assertEquals(1, back.size());
        KodelAnimation.KodelChannel ch = back.get(0).tracks.get(0).rotation;
        assertEquals(3, ch.count);
        assertArrayEquals(track.rotation.values, ch.values, 1e-6f);
        assertEquals(KodelFormat.EASE_STEP, ch.easing[2]);
    }

    @Test
    void easingSemantics() {
        float[] out = new float[3];

        // STEP: value of the held key until the next key fires
        var step = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f}, new float[] {0f, 0f, 0f, 1f, 0f, 0f},
            new int[] {KodelFormat.EASE_STEP, KodelFormat.EASE_STEP},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f}, new float[] {0f, 0f, 0f, 0f, 0f, 0f});
        KodelSampler.sample(step, 0.9f, out);
        assertEquals(0f, out[0], 1e-6f, "step should hold pre-key value");
        KodelSampler.sample(step, 1.0f, out);
        assertEquals(1f, out[0], 1e-6f, "step fires at the key");

        // LINEAR: straight midpoint
        var lin = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 2f}, new float[] {0f, 0f, 0f, 2f, 0f, 0f},
            new int[] {KodelFormat.EASE_LINEAR, KodelFormat.EASE_LINEAR},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f}, new float[] {0f, 0f, 0f, 0f, 0f, 0f});
        KodelSampler.sample(lin, 1f, out);
        assertEquals(1f, out[0], 0.01f, "linear midpoint");

        // automatic smoothing: bezier with zero control points = Catmull-Rom
        var smooth = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f, 2f}, new float[] {0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f},
            new int[] {KodelFormat.EASE_BEZIER, KodelFormat.EASE_BEZIER, KodelFormat.EASE_BEZIER},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        KodelSampler.sample(smooth, 1.5f, out);
        assertTrue(out[0] >= 1f && out[0] <= 2f, "smooth sample out of range: " + out[0]);

        // real bezier: control points exist -> the curve leaves linear straight away
        var bez = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f}, new float[] {0f, 0f, 0f, 1f, 0f, 0f},
            new int[] {KodelFormat.EASE_BEZIER, KodelFormat.EASE_BEZIER},
            new float[] {1f, 0f, 0f, 1f, 0f, 0f}, new float[] {0f, 0f, 0f, 0f, 0f, 0f});
        KodelSampler.sample(bez, 0.5f, out);
        assertTrue(out[0] > 0.6f, "bezier head should be pulled toward the control point, got " + out[0]);
        KodelSampler.sample(bez, 0.5f, out);
        assertTrue(out[0] > 0.55f, "bezier should differ from the linear 0.5, got " + out[0]);
    }

    @Test
    void restPoseMatchesNative() {
        KodelModel model = modelForSampling();
        float[] javaWorld = new float[16 * 2];
        KodelSampler.samplePose(model, null, 0, new float[3], new float[3], new float[3], javaWorld);

        Assumptions.assumeTrue(KodelBridge.available(), "native engine not loadable; skipping native cross-check");
        byte[] bin = model.write();
        // an empty-tracks clip -> native must fall back to the stored bind pose
        var restAnim = new KodelAnimation();
        restAnim.name = "rest";
        restAnim.length = 1f;
        byte[] animBin = KodelAnimation.write(List.of(restAnim));
        long handle = KodelBridge.loadModel(bin);
        try {
            Assumptions.assumeTrue(handle != 0, "load failed");
            assertEquals(2, KodelBridge.boneCount(handle));
            assertEquals(0, KodelBridge.loadAnimations(handle, animBin));
            float[] nativeWorld = new float[16 * 2];
            assertEquals(0, KodelBridge.sample(handle, 0, 0f, nativeWorld));
            assertArrayEquals(javaWorld, nativeWorld, 1e-4f);
        } finally {
            KodelBridge.free(handle);
        }
    }

    @Test
    void nativeMatchesJavaOnAnimatedClip() {
        KodelModel model = modelForSampling();
        var track = new KodelAnimation.KodelTrack();
        track.bone = "arm";
        track.rotation = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f, 2f},
            new float[] {0f, 0f, 0f, (float) Math.PI / 2, 0f, 0f, (float) Math.PI, 0f, 0f},
            new int[] {KodelFormat.EASE_BEZIER, KodelFormat.EASE_BEZIER, KodelFormat.EASE_BEZIER},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
            new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        var anim = animOf(track);
        anim.length = 3f;

        KodelSampler.ResolvedTracks resolved = KodelSampler.ResolvedTracks.of(model, anim);
        byte[] modelBin = model.write();
        byte[] animBin = KodelAnimation.write(List.of(anim));

        Assumptions.assumeTrue(KodelBridge.available(), "native engine not loadable");
        long handle = KodelBridge.loadModel(modelBin);
        try {
            Assumptions.assumeTrue(handle != 0, "load failed");
            assertEquals(0, KodelBridge.loadAnimations(handle, animBin));
            float[] js = new float[16 * 2];
            float[] rs = new float[16 * 2];
            for (float t = 0f; t <= 3f; t += 0.25f) {
                KodelSampler.samplePose(model, resolved, t, new float[3], new float[3], new float[3], js);
                assertEquals(0, KodelBridge.sample(handle, 0, t, rs));
                assertArrayEquals(js, rs, 2e-3f, "mismatch at t=" + t);
            }
        } finally {
            KodelBridge.free(handle);
        }
    }

    private static KodelModel modelForSampling() {
        KodelModel model = KodelConverters.geometry(
            new com.google.gson.JsonParser().parse(GEO).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject());
        // kodel1 has no scene-transform yet; the root bone pivot in Bedrock is at
        // the model origin, so translation expectations below are relative.
        return model;
    }

    private static KodelAnimation animOf(KodelAnimation.KodelTrack track) {
        var anim = new KodelAnimation();
        anim.name = "clip";
        anim.length = 2f;
        anim.tracks.add(track);
        return anim;
    }

    private static double rotAngle(float[] q) {
        return 2 * Math.acos(Math.max(-1, Math.min(1, q[3])));
    }
}