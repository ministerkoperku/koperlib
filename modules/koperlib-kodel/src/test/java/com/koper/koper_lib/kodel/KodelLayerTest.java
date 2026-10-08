package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// an attack that only animates a jaw must not stop the legs, and the bones hanging
// off that jaw have to come with it
class KodelLayerTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry trex() throws Exception {
        try (InputStream in = KodelLayerTest.class.getResourceAsStream("/kopertrex.kodel")) {
            assertNotNull(in);
            return KodelBook.put("trex", KodelLoader.read(in));
        }
    }

    private static boolean owns(KodelBook.Entry entry, String clip, String bone) {
        KodelAnimation a = entry.clip(clip);
        if (a == null) return false;
        for (KodelAnimation.KodelTrack t : a.tracks) {
            if (t.bone.equals(bone) && t.hasChannels()) return true;
        }
        return false;
    }

    @Test
    void bonesTheOverlayDoesNotTouchKeepWalking() throws Exception {
        KodelBook.Entry entry = trex();
        KodelModel model = entry.model();

        float[] walkOnly = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] layered = KodelBook.poseLayered("trex", "walking_animation", 0.2f,
            "attack_animation_bite", 0.1f, null).clone();

        int untouchedChecked = 0;
        for (int i = 0; i < model.bones.size(); i++) {
            if (owns(entry, "attack_animation_bite", model.bones.get(i).name)) continue;
            // a bone the overlay ignores, whose parents it also ignores, must be
            // exactly where the base clip put it
            if (parentTouched(entry, model, i)) continue;
            int at = i * KodelSampler.MAT4_FLOATS;
            for (int k = 0; k < KodelSampler.MAT4_FLOATS; k++) {
                assertArrayEquals(new float[] {walkOnly[at + k]}, new float[] {layered[at + k]}, 1e-4f,
                    "bone " + model.bones.get(i).name + " moved and the overlay never asked it to");
            }
            untouchedChecked++;
        }
        assertTrue(untouchedChecked > 0, "no untouched bone to check, the test proves nothing");
    }

    private static boolean parentTouched(KodelBook.Entry entry, KodelModel model, int bone) {
        int at = model.bones.get(bone).parent;
        while (at >= 0) {
            if (owns(entry, "attack_animation_bite", model.bones.get(at).name)) return true;
            at = model.bones.get(at).parent;
        }
        return false;
    }

    // the bug this replaced: the old renderer copied the overlay's world matrix over
    // the base one for the bones the overlay owned, so their children stayed hung off
    // the pose the parent used to be in
    @Test
    void childrenOfAnOverlaidBoneFollowIt() throws Exception {
        KodelBook.Entry entry = trex();
        KodelModel model = entry.model();

        int parent = -1;
        for (int i = 0; i < model.bones.size() && parent < 0; i++) {
            if (!owns(entry, "attack_animation_bite", model.bones.get(i).name)) continue;
            for (KodelModel.KodelBone b : model.bones) {
                if (b.parent == i && !owns(entry, "attack_animation_bite", b.name)) parent = i;
            }
        }
        assertTrue(parent >= 0, "trex should have an overlaid bone with a plain child");

        float[] walkOnly = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] layered = KodelBook.poseLayered("trex", "walking_animation", 0.2f,
            "attack_animation_bite", 0.3f, null).clone();

        for (int i = 0; i < model.bones.size(); i++) {
            if (model.bones.get(i).parent != parent) continue;
            if (owns(entry, "attack_animation_bite", model.bones.get(i).name)) continue;
            int at = i * KodelSampler.MAT4_FLOATS;
            boolean moved = false;
            for (int k = 0; k < KodelSampler.MAT4_FLOATS; k++) {
                if (Math.abs(walkOnly[at + k] - layered[at + k]) > 1e-4f) moved = true;
            }
            assertTrue(moved, "child '" + model.bones.get(i).name
                + "' stayed put while its parent was overlaid");
            return;
        }
    }

    @Test
    void anAbsentOverlayChangesNothing() throws Exception {
        trex();
        float[] plain = KodelBook.pose("trex", "walking_animation", 0.2f, null).clone();
        float[] layered = KodelBook.poseLayered("trex", "walking_animation", 0.2f, null, 0f, null);
        assertArrayEquals(plain, layered, 1e-5f);
    }

    // world matrices carry the parent chain, so proving "the fade did not leak" on
    // the trex is impossible: a bone the overlay owns still inherits a parent the
    // fade moved, and that is correct. synthetic model, overlay bone at the root,
    // where world is local and the question has an exact answer
    @Test
    void theFadeNeverReachesAChannelTheOverlayOwns() {
        KodelModel model = new KodelModel();
        KodelModel.KodelBone root = new KodelModel.KodelBone();
        root.name = "jaw";
        model.bones.add(root);

        KodelAnimation base = clip("base", 1f, 0f);
        KodelAnimation fade = clip("fade", 1f, 90f);
        KodelAnimation over = clip("over", 1f, 45f);

        var tBase = KodelSampler.ResolvedTracks.of(model, base);
        var tFade = KodelSampler.ResolvedTracks.of(model, fade);
        var tOver = KodelSampler.ResolvedTracks.of(model, over);

        float[] reference = new float[KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePoseLayered(model, tBase, 0.5f, null, 0f, 0f, tOver, 0.5f, reference);

        for (float mix = 0f; mix <= 1f; mix += 0.25f) {
            float[] got = new float[KodelSampler.MAT4_FLOATS];
            KodelSampler.samplePoseLayered(model, tBase, 0.5f, tFade, 0.5f, mix, tOver, 0.5f, got);
            assertArrayEquals(reference, got, 1e-5f,
                "at mix " + mix + " the fade moved a bone the overlay owns");
        }
    }

    private static KodelAnimation clip(String name, float length, float degrees) {
        KodelAnimation a = new KodelAnimation();
        a.name = name;
        a.length = length;
        a.loop = true;
        var track = new KodelAnimation.KodelTrack();
        track.bone = "jaw";
        float r = (float) Math.toRadians(degrees);
        track.rotation = new KodelAnimation.KodelChannel().set(
            new float[] {0f, length},
            new float[] {r, 0f, 0f, r, 0f, 0f},
            new int[] {KodelFormat.EASE_LINEAR, KodelFormat.EASE_LINEAR},
            new float[6], new float[6]);
        a.tracks.add(track);
        return a;
    }
}
