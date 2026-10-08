package com.koper.koper_lib.kodel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// java and rust hold two separate copies of the same sampler math. they WILL drift
// if nobody checks. this is the check. needs the native, so it skips without one --
// KodelGeoImportTest is the suite that has to pass everywhere
class KodelNativeParityTest {

    @BeforeEach
    void needsNative() {
        Assumptions.assumeTrue(KodelBridge.available(),
            "native kodel engine not loadable, parity unchecked on this machine");
    }

    private static KodelModel geo(String json) {
        return KodelConverters.geometry(JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject());
    }

    private static KodelAnimation.KodelChannel channel(float[] times, float[] values, int easing) {
        int n = times.length;
        int[] ease = new int[n];
        java.util.Arrays.fill(ease, easing);
        return new KodelAnimation.KodelChannel()
            .set(times, values, ease, new float[n * 3], new float[n * 3]);
    }

    private void assertSamplesMatch(KodelModel model, KodelAnimation anim, String what) {
        KodelSampler.ResolvedTracks resolved = KodelSampler.ResolvedTracks.of(model, anim);
        long handle = KodelBridge.loadModel(model.write());
        assertTrue(handle != 0, what + ": rust refused the model");
        try {
            assertEquals(0, KodelBridge.loadAnimations(handle, KodelAnimation.write(List.of(anim))),
                what + ": rust refused the clip");
            float[] js = new float[16 * model.bones.size()];
            float[] rs = new float[16 * model.bones.size()];
            for (float t = 0f; t <= anim.length + 0.5f; t += 0.1f) {
                KodelSampler.samplePose(model, resolved, t, new float[3], new float[3], new float[3], js);
                assertEquals(0, KodelBridge.sample(handle, 0, t, rs));
                assertArrayEquals(js, rs, 2e-3f, what + ": drift at t=" + t);
            }
        } finally {
            KodelBridge.free(handle);
        }
    }

    private static final String RIG = """
        {"minecraft:geometry":[{
          "description":{"identifier":"geometry.rig","texture_width":32,"texture_height":32},
          "bones":[
            {"name":"body","pivot":[0,8,0],"cubes":[{"origin":[-2,4,-1],"size":[4,8,2],"uv":[0,0]}]},
            {"name":"arm","parent":"body","pivot":[2,11,0],"rotation":[10,-20,30],
             "cubes":[{"origin":[2,7,-1],"size":[2,6,2],"uv":[12,0]}]},
            {"name":"hand","parent":"arm","pivot":[3,7,0],
             "cubes":[{"origin":[2,5,-1],"size":[2,2,2],"uv":[20,0],"inflate":0.25}]}]}]}
        """;

    @Test
    void restPose() {
        KodelModel model = geo(RIG);
        KodelAnimation empty = new KodelAnimation();
        empty.name = "rest";
        empty.length = 1f;
        assertSamplesMatch(model, empty, "rest pose");
    }

    @Test
    void everyEasingModeAgrees() {
        for (int easing : new int[] {KodelFormat.EASE_LINEAR, KodelFormat.EASE_STEP, KodelFormat.EASE_BEZIER}) {
            KodelModel model = geo(RIG);
            KodelAnimation anim = new KodelAnimation();
            anim.name = "ease" + easing;
            anim.length = 3f;
            var track = new KodelAnimation.KodelTrack();
            track.bone = "arm";
            track.rotation = channel(
                new float[] {0f, 1f, 2f, 3f},
                new float[] {0, 0, 0, 1.2f, -0.4f, 0.3f, -0.8f, 0.9f, 0.1f, 0, 0, 0},
                easing);
            anim.tracks.add(track);
            assertSamplesMatch(model, anim, "easing " + easing);
        }
    }

    @Test
    void allThreeChannelsOnNestedBonesAgree() {
        KodelModel model = geo(RIG);
        KodelAnimation anim = new KodelAnimation();
        anim.name = "full";
        anim.length = 2f;

        var arm = new KodelAnimation.KodelTrack();
        arm.bone = "arm";
        arm.rotation = channel(new float[] {0f, 2f}, new float[] {0, 0, 0, 0.7f, 0.5f, -0.3f},
            KodelFormat.EASE_LINEAR);
        arm.position = channel(new float[] {0f, 1f, 2f},
            new float[] {0, 0, 0, 0, 3, 0, 0, 0, 0}, KodelFormat.EASE_BEZIER);
        anim.tracks.add(arm);

        var hand = new KodelAnimation.KodelTrack();
        hand.bone = "hand";
        hand.scale = channel(new float[] {0f, 2f}, new float[] {1, 1, 1, 1.5f, 0.5f, 2f},
            KodelFormat.EASE_LINEAR);
        anim.tracks.add(hand);

        assertSamplesMatch(model, anim, "nested channels");
    }

    // rust used to skip straight to the next track when a bone name was unknown,
    // leaving the reader parked mid channel. every track after it came out as noise
    @Test
    void aTrackForAMissingBoneDoesNotShredTheRest() {
        KodelModel model = geo(RIG);
        KodelAnimation anim = new KodelAnimation();
        anim.name = "ghost";
        anim.length = 2f;

        var ghost = new KodelAnimation.KodelTrack();
        ghost.bone = "tail_that_does_not_exist";
        ghost.rotation = channel(new float[] {0f, 1f, 2f},
            new float[] {0, 0, 0, 9, 9, 9, 0, 0, 0}, KodelFormat.EASE_BEZIER);
        ghost.position = channel(new float[] {0f, 2f}, new float[] {0, 0, 0, 5, 5, 5},
            KodelFormat.EASE_LINEAR);
        anim.tracks.add(ghost);

        var real = new KodelAnimation.KodelTrack();
        real.bone = "arm";
        real.rotation = channel(new float[] {0f, 2f}, new float[] {0, 0, 0, 1f, 0, 0},
            KodelFormat.EASE_LINEAR);
        anim.tracks.add(real);

        assertSamplesMatch(model, anim, "ghost track first");
    }

    @Test
    void theRealTrexAgreesOnEveryClip() throws Exception {
        KodelLoader.Loaded loaded;
        try (InputStream in = getClass().getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in);
            loaded = KodelLoader.read(in);
        }
        KodelModel model = loaded.model();
        assertNotNull(loaded.animations());

        long handle = KodelBridge.loadModel(model.write());
        assertTrue(handle != 0);
        try {
            assertEquals(0, KodelBridge.loadAnimations(handle, KodelAnimation.write(loaded.animations())));
            for (int clip = 0; clip < loaded.animations().size(); clip++) {
                KodelAnimation anim = loaded.animations().get(clip);
                KodelSampler.ResolvedTracks resolved = KodelSampler.ResolvedTracks.of(model, anim);
                float[] js = new float[16 * model.bones.size()];
                float[] rs = new float[16 * model.bones.size()];
                for (float t = 0f; t <= anim.length; t += anim.length / 12f) {
                    KodelSampler.samplePose(model, resolved, t, new float[3], new float[3], new float[3], js);
                    assertEquals(0, KodelBridge.sample(handle, clip, t, rs));
                    assertArrayEquals(js, rs, 2e-3f, "clip '" + anim.name + "' drifts at t=" + t);
                }
            }
        } finally {
            KodelBridge.free(handle);
        }
    }

    // a forward parent index has to be refused on both sides, not sampled
    @Test
    void bothSidesRefuseAForwardParent() {
        KodelModel m = new KodelModel();
        KodelModel.KodelBone child = new KodelModel.KodelBone();
        child.name = "child";
        child.parent = 1;
        KodelModel.KodelBone parent = new KodelModel.KodelBone();
        parent.name = "parent";
        m.bones.add(child);
        m.bones.add(parent);
        byte[] bytes = m.write();

        assertEquals(0, KodelBridge.loadModel(bytes), "rust must refuse it too");
    }
}
