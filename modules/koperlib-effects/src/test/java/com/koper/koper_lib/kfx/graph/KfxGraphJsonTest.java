package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxLimits;
import com.koper.koper_lib.kfx.KfxProgram;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxGraphJsonTest {
    private static final String VALID_GRAPH = """
        {
          "version": 2,
          "id": "aq:test_spell",
          "inputs": {
            "accent": { "type": "color", "default": "#55ccff" }
          },
          "nodes": {
            "shape": {
              "type": "koper_lib:source/ring",
              "radius": { "random": { "distribution": "uniform", "min": 1.5, "max": 3.0 } },
              "spin": { "random": { "distribution": "uniform", "min": -1.2, "max": 1.2 } }
            },
            "ring": {
              "type": "koper_lib:render/particles",
              "source": "shape",
              "from": 0,
              "to": 36,
              "count": { "random": { "distribution": "integer", "min": 80, "max": 120 } },
              "color": { "input": "accent" },
              "style": "orb3d"
            },
            "root": {
              "type": "koper_lib:group",
              "children": ["ring"]
            }
          },
          "outputs": ["root"],
          "budget": { "max_particles": 120, "lifetime": 40 }
        }
        """;

    @Test
    void parsesTypedNodesAndRealLinks() {
        KfxGraph graph = KfxGraphJson.parse(VALID_GRAPH);

        assertEquals("aq:test_spell", graph.id());
        assertEquals(KfxValueType.COLOR, graph.inputs().get("accent").type());
        assertEquals("koper_lib:source/ring", graph.nodes().get("shape").type());
        assertEquals(java.util.List.of("shape"), graph.nodes().get("ring").links().get("source"));
        assertEquals(java.util.List.of("ring"), graph.nodes().get("root").links().get("children"));
        assertEquals(120, graph.budget().maxParticles());
    }

    @Test
    void castRandomnessIsRepeatableAndUnrelatedNodesDoNotMoveItsStream() {
        KfxGraph graph = KfxGraphJson.parse(VALID_GRAPH);
        String withOrphan = VALID_GRAPH.replace("\"root\": {", """
            "orphan": {
              "type": "koper_lib:source/ring",
              "radius": { "random": { "distribution": "uniform", "min": 40, "max": 50 } }
            },
            "root": {
            """);

        KfxCompiledGraph first = KfxGraphCompiler.compile(graph, 8844L, Map.of());
        KfxCompiledGraph replay = KfxGraphCompiler.compile(graph, 8844L, Map.of());
        KfxCompiledGraph withUnrelatedNode = KfxGraphCompiler.compile(KfxGraphJson.parse(withOrphan), 8844L, Map.of());
        KfxCompiledGraph anotherCast = KfxGraphCompiler.compile(graph, 8845L, Map.of());

        assertEquals(first.programJson(), replay.programJson());
        assertEquals(first.programJson(), withUnrelatedNode.programJson());
        assertNotEquals(first.number("shape.radius"), anotherCast.number("shape.radius"));
        assertTrue(first.number("ring.count") >= 80 && first.number("ring.count") <= 120);
    }

    @Test
    void compilerMaterializesCanonicalBackendFieldsAndInputOverride() {
        KfxGraph graph = KfxGraphJson.parse(VALID_GRAPH);
        KfxCompiledGraph compiled = KfxGraphCompiler.compile(
            graph, 12L, Map.of("accent", KfxResolvedValue.color(0xEEDD44FF))
        );
        JsonObject stage = JsonParser.parseString(compiled.programJson())
            .getAsJsonObject().getAsJsonArray("stages").get(0).getAsJsonObject();

        assertEquals(0xEEDD44FF, compiled.color("ring.color"));
        assertEquals("ring_particles", stage.get("op").getAsString());
        assertEquals("#eedd44ff", stage.get("color").getAsString());
        for (String field : java.util.List.of(
            "from", "to", "count", "radius", "radius_to", "thickness", "size", "alpha",
            "spin", "wobble", "depth", "x", "y", "z", "seed", "speed",
            "style", "ease", "build", "dim", "hold", "color"
        )) assertTrue(stage.has(field), "missing canonical field " + field);
    }

    @Test
    void rejectsWorstCaseAboveBudgetAndAnUnsafeAbsoluteBudget() {
        String overflowing = VALID_GRAPH.replace("\"max_particles\": 120", "\"max_particles\": 119");
        KfxGraphException overflow = assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(overflowing));
        assertEquals("$.nodes.ring.count", overflow.path());
        assertTrue(overflow.getMessage().contains("119"));

        String unsafe = VALID_GRAPH.replace("\"max_particles\": 120", "\"max_particles\": 4097");
        KfxGraphException hardCap = assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(unsafe));
        assertEquals("$.budget.max_particles", hardCap.path());
    }

    @Test
    void countCannotUseAnUnboundedInputOrTheWrongType() {
        String unsafe = VALID_GRAPH.replace(
            "{ \"random\": { \"distribution\": \"integer\", \"min\": 80, \"max\": 120 } }",
            "{ \"input\": \"accent\" }"
        );

        KfxGraphException error = assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(unsafe));

        assertEquals("$.nodes.ring.count", error.path());
        assertTrue(error.getMessage().contains("input"));
    }

    @Test
    void rejectsUnknownPropertiesAndBrokenOrCyclicLinks() {
        String unknown = VALID_GRAPH.replace("\"style\": \"orb3d\"", "\"style\": \"orb3d\", \"potato\": 7");
        assertEquals("$.nodes.ring.potato",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(unknown)).path());

        String missing = VALID_GRAPH.replace("\"source\": \"shape\"", "\"source\": \"absent\"");
        assertEquals("$.nodes.ring.source",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(missing)).path());

        String cycle = VALID_GRAPH.replace("\"children\": [\"ring\"]", "\"children\": [\"root\"]");
        assertEquals("$.nodes.root.children",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(cycle)).path());
    }

    @Test
    void malformedValuesAlwaysReportTheirExactPath() {
        String missingMax = VALID_GRAPH.replace("\"max\": 3.0", "\"maximum\": 3.0");
        assertEquals("$.nodes.shape.radius.random.max",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(missingMax)).path());

        String wrongVersionType = VALID_GRAPH.replace("\"version\": 2", "\"version\": \"two\"");
        assertEquals("$.version",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(wrongVersionType)).path());

        String invalidId = VALID_GRAPH.replace("aq:test_spell", "not an id:");
        assertEquals("$.id",
            assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(invalidId)).path());
    }

    @Test
    void oldBackendProgramsAreAlsoClampedBeforeCpuOrRustCanAllocate() {
        KfxProgram program = KfxProgram.parse("""
            {"stages":[
              {"op":"ring_band","count":2147483647},
              {"op":"ring_particles","count":4000},
              {"op":"spiral","count":4000}
            ]}
            """);

        int total = program.ops.stream().mapToInt(op -> op.count).sum();
        assertTrue(total <= KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH);
        assertTrue(program.ops.stream().noneMatch(op -> op.count > KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH));
    }

    @Test
    void declaredBudgetUsesTheRealBackendMinimumForBurstAndSpiral() {
        String burstWithOne = VALID_GRAPH
            .replace("\"spin\": { \"random\": { \"distribution\": \"uniform\", \"min\": -1.2, \"max\": 1.2 } }",
                "\"spin\": 1, \"mode\": \"burst\"")
            .replace("{ \"random\": { \"distribution\": \"integer\", \"min\": 80, \"max\": 120 } }", "1")
            .replace("\"max_particles\": 120", "\"max_particles\": 1");

        KfxGraphException error = assertThrows(KfxGraphException.class, () -> KfxGraphJson.parse(burstWithOne));

        assertEquals("$.nodes.ring.count", error.path());
        assertTrue(error.getMessage().contains("minimum 8"));
    }
}
