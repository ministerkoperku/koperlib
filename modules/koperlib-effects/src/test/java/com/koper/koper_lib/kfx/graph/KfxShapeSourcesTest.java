package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxProgram;
import com.koper.koper_lib.kfx.render.KfxNativeProgram;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KfxShapeSourcesTest {
    private static String graph(String source, String sourceFields, int count) {
        return """
            {"version":2,"id":"test:%s","nodes":{
              "shape":{"type":"koper_lib:source/%s"%s},
              "dots":{"type":"koper_lib:render/particles","source":"shape","count":%d,"color":"#ff44ddff"},
              "root":{"type":"koper_lib:group","children":["dots"]}},
             "outputs":["root"],"budget":{"max_particles":%d,"lifetime":40}}
            """.formatted(source, source, sourceFields, count, count);
    }

    private static JsonObject onlyStage(String programJson) {
        return JsonParser.parseString(programJson).getAsJsonObject()
            .getAsJsonArray("stages").get(0).getAsJsonObject();
    }

    private static String compile(String json) {
        return KfxGraphCompiler.compile(KfxGraphJson.parse(json), 77L, Map.of()).programJson();
    }

    @Test
    void sigilSourceReachesTheStarPolygonBackendWithItsAuthoredShape() {
        JsonObject stage = onlyStage(compile(graph("sigil", ",\"points\":7,\"skip\":3,\"radius\":2.5", 84)));

        assertEquals("pentagram_particles", stage.get("op").getAsString());
        assertEquals(7, stage.get("points").getAsInt());
        assertEquals(3, stage.get("skip").getAsInt());
    }

    @Test
    void streamAndOrbSourcesReachTheirOwnBackendOps() {
        assertEquals("stream", onlyStage(compile(graph("stream", ",\"wobble\":0.3,\"speed\":2.0", 40)))
            .get("op").getAsString());
        assertEquals("orb", onlyStage(compile(graph("orb", ",\"depth\":0.5", 1)))
            .get("op").getAsString());
    }

    @Test
    void theLegacyProgramParserCarriesTheSigilShapeToThePortablePath() {
        KfxProgram program = KfxProgram.parse(compile(graph("sigil", ",\"points\":9,\"skip\":4", 90)));

        assertEquals(1, program.ops.size());
        assertEquals(9, program.ops.get(0).points);
        assertEquals(4, program.ops.get(0).skip);
    }

    @Test
    void theNativeIrCarriesTheSigilShapeAndTheAuthoredColour() {
        var compiled = KfxGraphCompiler.compile(
            KfxGraphJson.parse(graph("sigil", ",\"points\":6,\"skip\":2", 60)), 77L, Map.of());
        float[] properties = KfxNativeProgram.from(compiled).nodes().get(0).properties();

        assertEquals(18, properties.length);
        assertEquals(6.0f, properties[16]);
        assertEquals(2.0f, properties[17]);
        assertEquals(0x44 / 255.0f, properties[12], 1.0e-4f, "red channel of #ff44ddff");
        assertEquals(0xdd / 255.0f, properties[13], 1.0e-4f, "green channel of #ff44ddff");
    }

    @Test
    void aSigilTooSmallToDrawItsLinesIsRejectedAtCompileTime() {
        assertThrows(KfxGraphException.class, () -> compile(graph("sigil", ",\"points\":5", 6)));
    }

    @Test
    void differentSourcesDoNotCollapseIntoTheSameBackendOp() {
        String sigil = onlyStage(compile(graph("sigil", "", 60))).get("op").getAsString();
        String ring = onlyStage(compile(graph("ring", ",\"radius\":2.0", 60))).get("op").getAsString();

        assertNotEquals(sigil, ring);
    }
}
