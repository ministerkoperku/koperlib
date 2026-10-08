package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// kender uploads the bind pose once and pushes bone matrices per frame, so the gpu
// does the skinning instead of KodelModelRender rebuilding every vertex every frame.
// the two have to agree or a model looks possessed on one path and fine on the other
class KodelSkinnedTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry load(String name) throws Exception {
        try (InputStream in = KodelSkinnedTest.class.getResourceAsStream("/" + name + ".kodel")) {
            assertNotNull(in, name + ".kodel missing");
            return KodelBook.put(name, KodelLoader.read(in));
        }
    }

    /// skin the mesh on the cpu the way the shader would, so the two paths can be compared
    private static float[] skinOnCpu(float[] skinned, float[] boneMats) {
        int verts = skinned.length / KodelModelRender.SKIN_STRIDE;
        float[] out = new float[verts * 3];
        for (int v = 0; v < verts; v++) {
            int at = v * KodelModelRender.SKIN_STRIDE;
            float x = skinned[at], y = skinned[at + 1], z = skinned[at + 2];
            int bone = (int) skinned[at + 8];
            int m = bone * KodelSampler.MAT4_FLOATS;
            out[v * 3]     = boneMats[m] * x + boneMats[m + 4] * y + boneMats[m + 8] * z + boneMats[m + 12];
            out[v * 3 + 1] = boneMats[m + 1] * x + boneMats[m + 5] * y + boneMats[m + 9] * z + boneMats[m + 13];
            out[v * 3 + 2] = boneMats[m + 2] * x + boneMats[m + 6] * y + boneMats[m + 10] * z + boneMats[m + 14];
        }
        return out;
    }

    private static void agreeOn(String name, String clip, float t) throws Exception {
        KodelBook.Entry entry = KodelBook.get(name) != null ? KodelBook.get(name) : load(name);
        KodelModel model = entry.model();
        float[] world = clip == null ? entry.restPose() : KodelBook.pose(name, clip, t, null);

        float[] cpu = KodelModelRender.bake(model, world, null);
        float[] skinned = KodelModelRender.bakeSkinned(model);
        float[] mats = KodelModelRender.boneMatricesForKender(world, null);
        float[] gpu = skinOnCpu(skinned, mats);

        int cpuVerts = cpu.length / KodelModelRender.STRIDE;
        assertEquals(cpuVerts, gpu.length / 3,
            name + "/" + clip + ": the two bakes disagree on how many vertices there are");
        assertTrue(cpuVerts > 0, name + " baked nothing");

        for (int v = 0; v < cpuVerts; v++) {
            for (int k = 0; k < 3; k++) {
                float a = cpu[v * KodelModelRender.STRIDE + k];
                float b = gpu[v * 3 + k];
                assertEquals(a, b, 1e-4f,
                    name + "/" + clip + " vertex " + v + " axis " + k + " drifted between the paths");
            }
        }
    }

    @Test
    void restPosesMatchOnBothModels() throws Exception {
        agreeOn("kopertrex", null, 0f);
        KodelBook.clear();
        agreeOn("kapoka", null, 0f);
    }

    @Test
    void everyClipMatchesThroughItsWholeLength() throws Exception {
        for (String name : new String[] {"kopertrex", "kapoka"}) {
            KodelBook.clear();
            KodelBook.Entry entry = load(name);
            for (KodelAnimation clip : entry.clips()) {
                for (float t = 0f; t <= clip.length; t += clip.length / 8f) {
                    agreeOn(name, clip.name, t);
                }
            }
        }
    }

    // uv and normals ride along unchanged, they must survive the layout change too
    @Test
    void theSkinnedLayoutCarriesUvAndNormals() throws Exception {
        KodelBook.Entry entry = load("kopertrex");
        float[] skinned = KodelModelRender.bakeSkinned(entry.model());
        assertEquals(0, skinned.length % KodelModelRender.SKIN_STRIDE, "ragged skinned buffer");

        int verts = skinned.length / KodelModelRender.SKIN_STRIDE;
        for (int v = 0; v < verts; v++) {
            int at = v * KodelModelRender.SKIN_STRIDE;
            float u = skinned[at + 3], tv = skinned[at + 4];
            assertTrue(u >= -0.001f && u <= 1.001f, "u off the atlas: " + u);
            assertTrue(tv >= -0.001f && tv <= 1.001f, "v off the atlas: " + tv);
            float nx = skinned[at + 5], ny = skinned[at + 6], nz = skinned[at + 7];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertEquals(1f, len, 1e-3f, "normal is not unit length, lighting will go black");
            int bone = (int) skinned[at + 8];
            assertTrue(bone >= 0 && bone < KodelModelRender.boneCount(entry.model()),
                "bone index " + bone + " outside the model");
        }
    }
}
