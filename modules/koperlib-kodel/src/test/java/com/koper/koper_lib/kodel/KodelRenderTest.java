package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the box bake against hand-computed Bedrock corners. The face layout and
 * cube matrix are a line-for-line port of the Python reference ({@code bake_model}
 * in {@code kodel/python/kodel.py}); this pins the parts that are easy to invert
 * silently — column-major matrix loading, face winding and atlas UV.
 */
class KodelRenderTest {
    private static KodelModel cubeModel() {
        KodelModel model = new KodelModel();
        model.texWidth = 16;
        model.texHeight = 16;
        KodelModel.KodelBone bone = new KodelModel.KodelBone();
        bone.name = "root";
        bone.parent = -1;
        bone.rotation[3] = 1f; // identity quat
        KodelModel.KodelCube cube = new KodelModel.KodelCube();
        cube.origin[0] = 0;
        cube.origin[1] = 0;
        cube.origin[2] = 0;
        cube.size[0] = 2;
        cube.size[1] = 4;
        cube.size[2] = 6;
        for (int f = 0; f < 6; f++) {
            KodelModel.KodelFace face = new KodelModel.KodelFace();
            face.u = 1;
            face.v = 2;
            face.uw = 4;
            face.vh = 8;
            cube.faces[f] = face;
        }
        // rot on nx (index 1) exercises the +90 step, mirror the flip
        cube.faces[1].u = 0;
        cube.faces[1].v = 0;
        cube.faces[1].rot = 1;
        cube.faces[1].mirror = true;
        bone.cubes.add(cube);
        model.bones.add(bone);
        return model;
    }

    private static float[] restPose(KodelModel model) {
        float[] world = new float[model.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(model, null, 0f, new float[3], new float[3], new float[3], world);
        return world;
    }

    @Test
    void bakesSixFacesOfFourVertices() {
        KodelModel model = cubeModel();
        float[] mesh = KodelModelRender.bake(model, restPose(model), null);
        assertEquals(6 * 4 * KodelModelRender.STRIDE, mesh.length);
    }

    @Test
    void pxFaceCornersMatchBedrockLayout() {
        KodelModel model = cubeModel();
        float[] mesh = KodelModelRender.bake(model, restPose(model), null);
        float[][] expected = {
            {0.125f, 0f, 0.375f},
            {0.125f, 0f, 0f},
            {0.125f, 0.25f, 0f},
            {0.125f, 0.25f, 0.375f},
        };
        for (int k = 0; k < 4; k++) {
            int at = k * KodelModelRender.STRIDE;
            assertEquals(expected[k][0], mesh[at], 1e-5f, "px x " + k);
            assertEquals(expected[k][1], mesh[at + 1], 1e-5f, "px y " + k);
            assertEquals(expected[k][2], mesh[at + 2], 1e-5f, "px z " + k);
        }
    }

    @Test
    void atlasUvHonoursRotationAndMirror() {
        KodelModel model = cubeModel();
        float[] mesh = KodelModelRender.bake(model, restPose(model), null);
        // px, no rot: the atlas V is top-down, so the top corner samples v+vh
        assertEquals(1f / 16f, mesh[3], 1e-5f);
        assertEquals(10f / 16f, mesh[4], 1e-5f);
        assertEquals(5f / 16f, mesh[KodelModelRender.STRIDE + 3], 1e-5f);
        // nx, rot=1 mirror: (0,0) wraps to the far v corner
        int nx = 4 * KodelModelRender.STRIDE;
        assertEquals(0f / 16f, mesh[nx + 3], 1e-5f);
        assertEquals(8f / 16f, mesh[nx + 4], 1e-5f);
    }

    @Test
    void bonePositionShiftsGeometry() {
        KodelModel model = cubeModel();
        model.bones.get(0).position[1] = 16f; // one block up
        float[] mesh = KodelModelRender.bake(model, restPose(model), null);
        assertEquals(1f, mesh[1], 1e-5f);
    }
}
