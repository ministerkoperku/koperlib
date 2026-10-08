package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.graph.KfxCompiledGraph;

import java.util.ArrayList;
import java.util.List;

public final class KfxRenderCompiler {
    private final KfxPrimitiveRegistry primitives;

    public KfxRenderCompiler(KfxPrimitiveRegistry primitives) {
        this.primitives = java.util.Objects.requireNonNull(primitives, "primitives");
    }

    public KfxRenderPlan lower(List<NodeSpec> source, KfxQuality quality) {
        List<KfxRenderPlan.Node> nodes = new ArrayList<>();
        int particles = 0, geometry = 0;
        for (NodeSpec spec : source) {
            if (quality == KfxQuality.LOW && spec.decorative()) continue;
            Identifier primitiveId = namespaced(spec.primitive());
            if (primitives.get(primitiveId) == null) {
                throw new IllegalArgumentException("unknown KFX primitive " + primitiveId);
            }
            int cost = quality == KfxQuality.MEDIUM && spec.decorative()
                ? Math.max(1, spec.cost() * 7 / 10) : spec.cost();
            KfxRenderPlan.Node node = new KfxRenderPlan.Node(spec.id(), primitiveId,
                namespaced(spec.material()), spec.decorative(), cost);
            nodes.add(node);
            if (primitiveId.getPath().equals("particles")) particles += cost;
            else if (!primitiveId.getPath().equals("group") && !primitiveId.getPath().equals("light")) geometry += cost;
        }
        KfxBatchBook batches = new KfxBatchBook();
        for (KfxRenderPlan.Node node : nodes) {
            KfxPrimitive primitive = primitives.get(node.primitive());
            primitive.nativeCompiler().lower(node, batches);
        }
        return new KfxRenderPlan(nodes, quality,
            new KfxRenderPlan.Cost(particles, geometry, batches.snapshot().size()));
    }

    public KfxRenderPlan lower(KfxCompiledGraph graph, KfxQuality quality) {
        JsonObject root = JsonParser.parseString(graph.programJson()).getAsJsonObject();
        List<NodeSpec> nodes = new ArrayList<>();
        root.getAsJsonArray("stages").forEach(raw -> {
            JsonObject stage = raw.getAsJsonObject();
            String primitive = text(stage, "primitive", "particles");
            String id = text(stage, "node", "stage_" + nodes.size());
            String material = text(stage, "material", "koper_lib:additive");
            boolean decorative = text(stage, "priority", "core").equalsIgnoreCase("decorative")
                || id.contains("spark") || id.contains("decor");
            int cost = stage.has("count") ? stage.get("count").getAsInt()
                : stage.has("points") ? stage.get("points").getAsInt() : 1;
            nodes.add(new NodeSpec(id, primitive, material, decorative, Math.max(0, cost)));
        });
        return lower(nodes, quality);
    }

    private static Identifier namespaced(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank KFX render id");
        return Identifier.parse(value.indexOf(':') >= 0 ? value : "koper_lib:" + value);
    }

    private static String text(JsonObject object, String name, String fallback) {
        return object.has(name) ? object.get(name).getAsString() : fallback;
    }

    public record NodeSpec(String id, String primitive, String material, boolean decorative, int cost) {
        public NodeSpec {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("KFX render node needs id");
            if (cost < 0) throw new IllegalArgumentException("KFX render cost cannot be negative");
        }
    }
}
