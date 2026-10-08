package com.koper.koper_lib.kodel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// poly_mesh is bedrock's escape hatch from cubes: arbitrary triangles. they imported
// fine and then rendered as nothing, because the vertex consumer upstream is quad
// based and a triangle cannot be handed to it
class KodelPolyMeshTest {

    private static final String QUAD_MESH = """
        {"minecraft:geometry":[{
          "description":{"identifier":"geometry.poly","texture_width":16,"texture_height":16},
          "bones":[{"name":"root","pivot":[0,0,0],"poly_mesh":{
            "normalized_uvs":false,
            "positions":[[0,0,0],[8,0,0],[8,8,0],[0,8,0]],
            "normals":[[0,0,1]],
            "uvs":[[0,0],[8,0],[8,8],[0,8]],
            "polys":[[[0,0,0],[1,0,1],[2,0,2],[3,0,3]]]}}]}]}
        """;

    private static KodelModel model(String json) {
        return KodelBedrock.toRenderSpace(KodelConverters.geometry(
            JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()));
    }

    private static float[] bake(KodelModel m) {
        float[] world = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], world);
        return KodelModelRender.bake(m, world, null);
    }

    @Test
    void trianglesActuallyReachTheBuffer() {
        KodelModel m = model(QUAD_MESH);
        assertEquals(1, m.meshes.size(), "the mesh should have survived the import");
        float[] mesh = bake(m);
        // one quad triangulates to two tris, each going out as a four corner quad
        assertEquals(2 * 4 * KodelModelRender.STRIDE, mesh.length,
            "two triangles should be eight vertices, got " + mesh.length / KodelModelRender.STRIDE);
    }

    @Test
    void theTrianglesLandWhereTheGeometrySaidTheyWould() {
        float[] mesh = bake(model(QUAD_MESH));
        float mnx = Float.MAX_VALUE, mxx = -Float.MAX_VALUE, mny = Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            mnx = Math.min(mnx, mesh[i]);   mxx = Math.max(mxx, mesh[i]);
            mny = Math.min(mny, mesh[i + 1]); mxy = Math.max(mxy, mesh[i + 1]);
        }
        // 8 pixels is half a block, mirrored on x into render space
        assertEquals(-0.5f, mnx, 1e-4f);
        assertEquals(0f, mxx, 1e-4f);
        assertEquals(0f, mny, 1e-4f);
        assertEquals(0.5f, mxy, 1e-4f);
    }

    @Test
    void uvsAreNormalisedAgainstTheAtlas() {
        float[] mesh = bake(model(QUAD_MESH));
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            assertTrue(mesh[i + 3] >= -1e-4f && mesh[i + 3] <= 1.0001f, "u off the atlas: " + mesh[i + 3]);
            assertTrue(mesh[i + 4] >= -1e-4f && mesh[i + 4] <= 1.0001f, "v off the atlas: " + mesh[i + 4]);
        }
    }

    // the skinned path has to carry them too or kender would drop the mesh half
    @Test
    void theSkinnedBakeCarriesThemWithABoneIndex() {
        KodelModel m = model(QUAD_MESH);
        float[] skinned = KodelModelRender.bakeSkinned(m);
        assertEquals(0, skinned.length % KodelModelRender.SKIN_STRIDE, "ragged buffer");
        assertEquals(2 * 4, skinned.length / KodelModelRender.SKIN_STRIDE, "two triangles as quads");
        for (int v = 0; v < skinned.length / KodelModelRender.SKIN_STRIDE; v++) {
            int bone = (int) skinned[v * KodelModelRender.SKIN_STRIDE + 8];
            assertTrue(bone >= 0 && bone < m.bones.size(), "bone index " + bone + " outside the model");
        }
    }

    @Test
    void aModelWithNoMeshesIsUnchanged() {
        KodelModel m = model("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.cubes"},
              "bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[2,2,2],"uv":[0,0]}]}]}]}
            """);
        assertTrue(m.meshes.isEmpty());
        assertEquals(6 * 4 * KodelModelRender.STRIDE, bake(m).length, "a plain cube is still six quads");
    }
}
