package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.kfx.KfxLimits;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class KfxGraphCompiler {
    private KfxGraphCompiler() {}

    public static KfxCompiledGraph compile(KfxGraph graph, long castSeed, Map<String, KfxResolvedValue> arguments) {
        Map<String, KfxResolvedValue> inputs = resolveInputs(graph, arguments);
        Map<String, KfxResolvedValue> sampled = new LinkedHashMap<>();
        JsonArray stages = new JsonArray();
        Set<String> emitted = new HashSet<>();
        int[] totalParticles = {0};

        for (String output : graph.outputs()) {
            emit(output, graph, inputs, castSeed, sampled, stages, emitted, totalParticles);
        }
        if (totalParticles[0] > graph.budget().maxParticles()
            || totalParticles[0] > KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH) {
            throw new KfxGraphException(graph.source(), "$.budget.max_particles",
                "resolved particle count " + totalParticles[0] + " exceeds the hard budget");
        }

        JsonObject program = new JsonObject();
        program.addProperty("version", 2);
        program.addProperty("cast_seed", castSeed);
        program.add("stages", stages);
        return new KfxCompiledGraph(
            graph.id(), program.toString(), graph.budget().lifetime(), graph.budget().maxParticles(),
            totalParticles[0], sampled
        );
    }

    private static void emit(
        String nodeId,
        KfxGraph graph,
        Map<String, KfxResolvedValue> inputs,
        long castSeed,
        Map<String, KfxResolvedValue> sampled,
        JsonArray stages,
        Set<String> emitted,
        int[] totalParticles
    ) {
        if (!emitted.add(nodeId)) return;
        KfxGraph.Node node = graph.nodes().get(nodeId);
        if (node.type().equals(KfxNodeSchemas.GROUP)) {
            for (String child : node.links().get("children")) {
                emit(child, graph, inputs, castSeed, sampled, stages, emitted, totalParticles);
            }
            return;
        }
        if (KfxNodeSchemas.isRender(node.type()) && !node.type().equals(KfxNodeSchemas.RENDER_PARTICLES)) {
            Map<String, KfxResolvedValue> values = resolveNode(nodeId, node, inputs, castSeed, sampled);
            JsonObject stage = new JsonObject();
            stage.addProperty("node", nodeId);
            stage.addProperty("primitive", KfxNodeSchemas.primitiveName(node.type()));
            stage.addProperty("op", KfxNodeSchemas.primitiveName(node.type()));
            values.forEach((name, value) -> add(stage, name, value));
            stages.add(stage);
            return;
        }
        if (!node.type().equals(KfxNodeSchemas.RENDER_PARTICLES)) {
            throw new KfxGraphException(graph.source(), "$.nodes." + nodeId,
                "only render and group nodes can be emitted");
        }

        String sourceId = node.links().get("source").getFirst();
        KfxGraph.Node source = graph.nodes().get(sourceId);
        Map<String, KfxResolvedValue> sourceValues = resolveNode(sourceId, source, inputs, castSeed, sampled);
        Map<String, KfxResolvedValue> renderValues = resolveNode(nodeId, node, inputs, castSeed, sampled);
        String op = backendOp(source.type(), sourceValues, graph.source(), sourceId);
        int count = integer(renderValues, "count", nodeId);
        int minimum = backendMinimum(op);
        if (count < minimum) {
            throw new KfxGraphException(graph.source(), "$.nodes." + nodeId + ".count",
                "backend minimum " + minimum + " must fit the resolved count");
        }
        totalParticles[0] = Math.addExact(totalParticles[0], count);
        if (totalParticles[0] > graph.budget().maxParticles()
            || totalParticles[0] > KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH) {
            throw new KfxGraphException(graph.source(), "$.nodes." + nodeId + ".count",
                "resolved total " + totalParticles[0] + " exceeds max_particles " + graph.budget().maxParticles());
        }

        JsonObject stage = new JsonObject();
        stage.addProperty("node", nodeId);
        stage.addProperty("primitive", "particles");
        stage.addProperty("op", op);
        add(stage, "from", renderValues.get("from"));
        add(stage, "to", renderValues.get("to"));
        add(stage, "count", renderValues.get("count"));
        add(stage, "radius", sourceValues.get("radius"));
        KfxResolvedValue radiusTo = sourceValues.getOrDefault("radius_to", sourceValues.get("radius"));
        add(stage, "radius_to", radiusTo);
        add(stage, "thickness", renderValues.get("thickness"));
        add(stage, "size", renderValues.get("size"));
        add(stage, "alpha", renderValues.get("alpha"));
        add(stage, "spin", sourceValues.get("spin"));
        add(stage, "wobble", sourceValues.get("wobble"));
        add(stage, "depth", sourceValues.get("depth"));
        stage.addProperty("x", 0.0);
        stage.addProperty("y", 0.0);
        stage.addProperty("z", 0.0);
        stage.addProperty("seed", (KfxRandom.derivedSeed(castSeed, nodeId) >>> 40) & 0xFFFFFFL);
        add(stage, "speed", sourceValues.get("speed"));
        add(stage, "style", renderValues.get("style"));
        add(stage, "ease", renderValues.get("ease"));
        add(stage, "build", renderValues.get("build"));
        add(stage, "dim", renderValues.get("dim"));
        add(stage, "priority", renderValues.get("priority"));
        if (sourceValues.containsKey("points")) add(stage, "points", sourceValues.get("points"));
        if (sourceValues.containsKey("skip")) add(stage, "skip", sourceValues.get("skip"));
        stage.addProperty("hold", true);
        add(stage, "color", renderValues.get("color"));
        stages.add(stage);
    }

    private static int backendMinimum(String op) {
        return switch (op) {
            case "pentagram_particles" -> 15;
            case "burst_ring", "spiral" -> 8;
            case "stream" -> 2;
            default -> 1;
        };
    }

    private static Map<String, KfxResolvedValue> resolveNode(
        String nodeId,
        KfxGraph.Node node,
        Map<String, KfxResolvedValue> inputs,
        long castSeed,
        Map<String, KfxResolvedValue> sampled
    ) {
        Map<String, KfxResolvedValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, KfxValue> parameter : node.parameters().entrySet()) {
            String propertyPath = nodeId + "." + parameter.getKey();
            KfxResolvedValue value = resolve(parameter.getValue(), inputs, castSeed, propertyPath);
            values.put(parameter.getKey(), value);
            sampled.put(propertyPath, value);
        }
        return values;
    }

    private static String backendOp(String sourceType, Map<String, KfxResolvedValue> source, String sourceRef, String nodeId) {
        if (sourceType.equals(KfxNodeSchemas.SOURCE_SPIRAL)) return "spiral";
        if (sourceType.equals(KfxNodeSchemas.SOURCE_SIGIL)) return "pentagram_particles";
        if (sourceType.equals(KfxNodeSchemas.SOURCE_STREAM)) return "stream";
        if (sourceType.equals(KfxNodeSchemas.SOURCE_ORB)) return "orb";
        if (sourceType.equals(KfxNodeSchemas.SOURCE_RING)) {
            String mode = text(source, "mode", nodeId);
            return mode.equals("burst") ? "burst_ring" : "ring_particles";
        }
        throw new KfxGraphException(sourceRef, "$.nodes." + nodeId + ".type", "source cannot feed particles in Part 1");
    }

    private static Map<String, KfxResolvedValue> resolveInputs(
        KfxGraph graph,
        Map<String, KfxResolvedValue> arguments
    ) {
        for (String supplied : arguments.keySet()) {
            if (!graph.inputs().containsKey(supplied)) {
                throw new KfxGraphException(graph.source(), "$.arguments." + supplied, "graph has no such input");
            }
        }
        Map<String, KfxResolvedValue> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, KfxGraph.Input> input : graph.inputs().entrySet()) {
            KfxResolvedValue value = arguments.getOrDefault(input.getKey(), input.getValue().defaultValue());
            if (value.type() != input.getValue().type()) {
                throw new KfxGraphException(graph.source(), "$.arguments." + input.getKey(),
                    "expected " + input.getValue().type().name().toLowerCase() + ", got " + value.type().name().toLowerCase());
            }
            resolved.put(input.getKey(), value);
        }
        return resolved;
    }

    private static KfxResolvedValue resolve(
        KfxValue value,
        Map<String, KfxResolvedValue> inputs,
        long castSeed,
        String propertyPath
    ) {
        if (value instanceof KfxValue.Constant constant) return constant.value();
        if (value instanceof KfxValue.Input input) return inputs.get(input.name());
        if (value instanceof KfxValue.Random random) return random.distribution().sample(castSeed, propertyPath);
        throw new KfxGraphException(propertyPath, "unsupported value source");
    }

    private static int integer(Map<String, KfxResolvedValue> values, String name, String nodeId) {
        KfxResolvedValue value = values.get(name);
        if (value.type() != KfxValueType.INTEGER) throw new KfxGraphException(nodeId + "." + name, "expected integer");
        return (int)value.value();
    }

    private static String text(Map<String, KfxResolvedValue> values, String name, String nodeId) {
        KfxResolvedValue value = values.get(name);
        if (value.type() != KfxValueType.TEXT) throw new KfxGraphException(nodeId + "." + name, "expected text");
        return (String)value.value();
    }

    private static void add(JsonObject json, String name, KfxResolvedValue value) {
        if (value == null) throw new IllegalStateException("canonical KFX field is missing: " + name);
        json.add(name, value.toJson());
    }
}
