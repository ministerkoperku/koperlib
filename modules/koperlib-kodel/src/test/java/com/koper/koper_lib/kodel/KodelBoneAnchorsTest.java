package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperBoneAnchors;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KodelBoneAnchorsTest {
    @AfterEach
    void forget() {
        KoperBoneAnchors.clear();
        KodelBoneAnchors.clear();
    }

    private static KodelModel oneBone(String name, float px, float py, float pz) {
        KodelModel model = new KodelModel();
        KodelModel.KodelBone bone = new KodelModel.KodelBone();
        bone.name = name;
        bone.pivot[0] = px;
        bone.pivot[1] = py;
        bone.pivot[2] = pz;
        model.bones.add(bone);
        return model;
    }

    @Test
    void requestedDrawnBoneIsPublishedAtItsPivot() {
        KoperBoneAnchors.install(KodelBoneAnchors.provider());
        KoperBoneAnchors.beginFrame();
        assertTrue(KoperBoneAnchors.resolve(7, "staff_tip", 0.25f).isEmpty());
        KoperBoneAnchors.endFrame();

        KodelModel model = oneBone("staff_tip", 16, 32, 48);
        float[] pose = new float[KodelSampler.MAT4_FLOATS];
        new Matrix4f().get(pose);
        KodelBoneAnchors.capture(7, 2, 2, 2, new Matrix4f().scale(1f / 16f), model, pose);

        KoperBoneAnchors.beginFrame();
        var bone = KoperBoneAnchors.resolve(7, "staff_tip", 0.25f).orElseThrow();
        KoperBoneAnchors.endFrame();
        assertEquals(3.0f, bone.x(), 1e-5f);
        assertEquals(4.0f, bone.y(), 1e-5f);
        assertEquals(5.0f, bone.z(), 1e-5f);
        assertEquals(1.0f, bone.forwardZ(), 1e-5f);
        assertEquals(1.0f, bone.normalY(), 1e-5f);
    }

    @Test
    void clearingDropsPositionsFromThePreviousLevel() {
        KoperBoneAnchors.install(KodelBoneAnchors.provider());
        KoperBoneAnchors.beginFrame();
        KoperBoneAnchors.resolve(7, "staff_tip", 0.5f);
        KoperBoneAnchors.endFrame();
        KodelBoneAnchors.publish(7, "staff_tip", new KoperBoneAnchors.TransformData(3, 4, 5, 0, 0, 1, 0, 1, 0));

        KoperBoneAnchors.clear();

        assertTrue(KoperBoneAnchors.resolve(7, "staff_tip", 0.5f).isEmpty());
    }

    @Test
    void poseExpiresWhenTheMobStopsRendering() {
        KoperBoneAnchors.install(KodelBoneAnchors.provider());
        KoperBoneAnchors.beginFrame();
        KoperBoneAnchors.resolve(7, "staff_tip", 0.5f);
        KoperBoneAnchors.endFrame();
        KodelBoneAnchors.publish(7, "staff_tip", new KoperBoneAnchors.TransformData(3, 4, 5, 0, 0, 1, 0, 1, 0));

        KoperBoneAnchors.beginFrame();
        assertTrue(KoperBoneAnchors.resolve(7, "staff_tip", 0.5f).isPresent());
        KoperBoneAnchors.endFrame();
        KoperBoneAnchors.beginFrame();
        assertTrue(KoperBoneAnchors.resolve(7, "staff_tip", 0.5f).isEmpty());
        KoperBoneAnchors.endFrame();
    }
}
