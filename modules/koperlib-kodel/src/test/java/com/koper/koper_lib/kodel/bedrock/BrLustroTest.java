package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kodel.KodelBedrock;
import com.koper.koper_lib.kodel.KodelConverters;
import com.koper.koper_lib.kodel.KodelModel;
import com.koper.koper_lib.kodel.KodelSampler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// the native actor poses in bedrock space, BrAktorzy mirrors that into kodel's render space.
// kodel's own sampler on the render space model is what kopermod draws in game, so both have to
// land on the same matrices or bedrock mobs come out bent (udders on the back, see the cow)
class BrLustroTest {

    private static final String GEO = """
        {"description": {"identifier": "geometry.lustro", "texture_width": 64, "texture_height": 64},
         "bones": [
           {"name": "body", "pivot": [0, 19, 2], "rotation": [90, 0, 0],
            "cubes": [{"origin": [-6, 11, -5], "size": [12, 18, 10], "uv": [0, 0]}]},
           {"name": "head", "parent": "body", "pivot": [0, 20, -8], "rotation": [10, 25, -15],
            "cubes": [{"origin": [-4, 16, -14], "size": [8, 8, 6], "uv": [0, 0]}]},
           {"name": "horn", "parent": "head", "pivot": [3, 24, -10], "rotation": [-30, -40, 60],
            "cubes": [{"origin": [3, 24, -11], "size": [1, 3, 1], "uv": [0, 0]}]}
         ]}
        """;

    @Test
    void mirroredNativeMatchesKodelSampler() {
        Assumptions.assumeTrue(BrNatywka.ready());
        KodelModel model = KodelConverters.geometry(JsonParser.parseString(GEO).getAsJsonObject());
        KodelModel render = KodelBedrock.toRenderSpace(model);

        float[] ref = new float[render.bones.size() * 16];
        KodelSampler.samplePoseBlended(render, null, 0f, null, 0f, 0f, ref);

        JsonObject raw = JsonParser.parseString(GEO).getAsJsonObject();
        JsonArray bones = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {
            KodelModel.KodelBone kb = model.bones.get(i);
            JsonObject bj = new JsonObject();
            bj.addProperty("name", kb.name);
            bj.addProperty("parent", kb.parent);
            bj.add("pivot", arr(kb.pivot));
            bj.add("pos", arr(kb.position));
            bj.add("rot", raw.getAsJsonArray("bones").get(i).getAsJsonObject().get("rotation"));
            bones.add(bj);
        }
        JsonObject def = new JsonObject();
        def.add("bones", bones);
        def.add("short", new JsonObject());
        def.add("scripts", new JsonObject());
        long d = BrNatywka.define(def.toString());
        long inst = BrNatywka.spawn(d, 1);
        BrKlatka k = new BrKlatka(bones.size());
        BrNatywka.tick(inst, new float[0], new int[0], 0f, k, false);
        for (int b = 0; b < bones.size(); b++) BrAktorzy.mirrorForTest(k.mats, b * 16);
        BrNatywka.free(inst);
        BrNatywka.undefine(d);

        for (int i = 0; i < ref.length; i++)
            assertEquals(ref[i], k.mats[i], 1e-3f, "bone " + (i / 16) + " element " + (i % 16));
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) a.add(f);
        return a;
    }
}
