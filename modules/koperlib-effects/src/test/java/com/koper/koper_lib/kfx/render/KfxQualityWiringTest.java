package com.koper.koper_lib.kfx.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxProgram;
import com.koper.koper_lib.kfx.graph.KfxCompiledGraph;
import com.koper.koper_lib.kfx.graph.KfxGraphCompiler;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxQualityWiringTest {
    private static final String GRAPH = """
        {"version":2,"id":"test:tiers","nodes":{
          "shape":{"type":"koper_lib:source/ring","radius":2.0},
          "core_ring":{"type":"koper_lib:render/particles","source":"shape","count":100,"priority":"core"},
          "halo":{"type":"koper_lib:source/ring","radius":3.0},
          "halo_dust":{"type":"koper_lib:render/particles","source":"halo","count":80,"priority":"decorative"},
          "glow":{"type":"koper_lib:render/light","radius":4.0,"priority":"decorative"},
          "root":{"type":"koper_lib:group","children":["core_ring","halo_dust","glow"]}},
         "outputs":["root"],"budget":{"max_particles":180,"lifetime":40}}
        """;

    private static KfxCompiledGraph compiled() {
        return KfxGraphCompiler.compile(KfxGraphJson.parse(GRAPH), 4242L, Map.of());
    }

    private static int particleTotal(String programJson) {
        int total = 0;
        for (var raw : JsonParser.parseString(programJson).getAsJsonObject().getAsJsonArray("stages")) {
            JsonObject stage = raw.getAsJsonObject();
            if (stage.has("count")) total += stage.get("count").getAsInt();
        }
        return total;
    }

    private static boolean hasStage(String programJson, String node) {
        for (var raw : JsonParser.parseString(programJson).getAsJsonObject().getAsJsonArray("stages")) {
            if (raw.getAsJsonObject().get("node").getAsString().equals(node)) return true;
        }
        return false;
    }

    @Test
    void particleNodesCanDeclarePriorityAndItSurvivesCompilation() {
        String program = compiled().programJson();

        for (var raw : JsonParser.parseString(program).getAsJsonObject().getAsJsonArray("stages")) {
            JsonObject stage = raw.getAsJsonObject();
            String node = stage.get("node").getAsString();
            if (node.equals("core_ring")) assertEquals("core", stage.get("priority").getAsString());
            if (node.equals("halo_dust")) assertEquals("decorative", stage.get("priority").getAsString());
        }
    }

    @Test
    void nativeProgramMarksGraphDeclaredDecorativeNodesWithoutNameGuessing() {
        KfxNativeProgram program = KfxNativeProgram.from(compiled());

        assertEquals(3, program.nodes().size());
        assertEquals(1, program.nodes().stream().filter(node -> !node.decorative()).count());
        assertEquals(2, program.nodes().stream().filter(KfxNativeProgram.Node::decorative).count());
    }

    @Test
    void lowQualityDropsDecorativeStagesButKeepsTheCoreSilhouette() {
        String low = KfxQualityPlan.apply(compiled().programJson(), KfxQuality.LOW);

        assertTrue(hasStage(low, "core_ring"));
        assertFalse(hasStage(low, "halo_dust"));
        assertFalse(hasStage(low, "glow"));
    }

    @Test
    void everyTierRendersADifferentParticleLoadAndHighIsUnchanged() {
        String program = compiled().programJson();
        int high = particleTotal(KfxQualityPlan.apply(program, KfxQuality.HIGH));
        int medium = particleTotal(KfxQualityPlan.apply(program, KfxQuality.MEDIUM));
        int low = particleTotal(KfxQualityPlan.apply(program, KfxQuality.LOW));

        assertEquals(particleTotal(program), high);
        assertTrue(medium < high, "medium " + medium + " must be lighter than high " + high);
        assertTrue(low < medium, "low " + low + " must be lighter than medium " + medium);
        assertTrue(low >= 8, "low must keep a renderable core ring, got " + low);
    }

    @Test
    void tierPlansStayStableWhenAppliedTwice() {
        String once = KfxQualityPlan.apply(compiled().programJson(), KfxQuality.MEDIUM);

        assertEquals(once, KfxQualityPlan.apply(once, KfxQuality.MEDIUM));
    }

    @Test
    void thePortableProgramParserSeesADifferentSceneForEveryTier() {
        String program = compiled().programJson();
        KfxProgram high = KfxProgram.parse(KfxQualityPlan.apply(program, KfxQuality.HIGH));
        KfxProgram medium = KfxProgram.parse(KfxQualityPlan.apply(program, KfxQuality.MEDIUM));
        KfxProgram low = KfxProgram.parse(KfxQualityPlan.apply(program, KfxQuality.LOW));

        assertEquals(3, high.ops.size());
        assertEquals(3, medium.ops.size());
        assertEquals(1, low.ops.size(), "low keeps only the core silhouette");

        int highParticles = high.ops.stream().mapToInt(op -> op.count).sum();
        int mediumParticles = medium.ops.stream().mapToInt(op -> op.count).sum();
        assertTrue(mediumParticles < highParticles,
            "medium " + mediumParticles + " must be lighter than high " + highParticles);
    }
}
