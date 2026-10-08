package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/** Public Java authoring layer. It writes graph v2 JSON internally so Java and JSON share validation. */
public final class KfxGraphBuilder {
    private final String id;
    private String source;
    private final JsonObject inputs = new JsonObject();
    private final JsonArray includes = new JsonArray();
    private final JsonObject exports = new JsonObject();
    private final JsonObject nodes = new JsonObject();
    private final JsonArray outputs = new JsonArray();
    private final JsonObject budget = new JsonObject();

    private KfxGraphBuilder(String id) {
        this.id = id;
        this.source = "java:" + id;
    }

    public static KfxGraphBuilder graph(String id) {
        return new KfxGraphBuilder(id);
    }

    public KfxGraphBuilder source(String source) {
        this.source = source;
        return this;
    }

    public KfxGraphBuilder input(String name, KfxValueType type, KfxResolvedValue defaultValue) {
        if (type != defaultValue.type()) throw new IllegalArgumentException("KFX input default has the wrong type");
        JsonObject input = new JsonObject();
        input.addProperty("type", type.name().toLowerCase());
        input.add("default", defaultValue.toJson());
        inputs.add(name, input);
        return this;
    }

    public KfxGraphBuilder include(String alias, String graphId) {
        return include(alias, graphId, Map.of());
    }

    /** Includes a fragment and forwards this graph's own inputs into it by name. */
    public KfxGraphBuilder includeBound(String alias, String graphId, Map<String, String> inputBindings) {
        JsonObject include = new JsonObject();
        include.addProperty("graph", graphId);
        include.addProperty("as", alias);
        if (!inputBindings.isEmpty()) {
            JsonObject bind = new JsonObject();
            inputBindings.forEach((childInput, ownInput) -> {
                JsonObject typed = new JsonObject();
                typed.addProperty("input", ownInput);
                bind.add(childInput, typed);
            });
            include.add("bind", bind);
        }
        includes.add(include);
        return this;
    }

    public KfxGraphBuilder include(String alias, String graphId, Map<String, KfxResolvedValue> bindings) {
        JsonObject include = new JsonObject();
        include.addProperty("graph", graphId);
        include.addProperty("as", alias);
        if (!bindings.isEmpty()) {
            JsonObject bind = new JsonObject();
            bindings.forEach((name, value) -> {
                JsonObject typed = new JsonObject();
                typed.addProperty("type", value.type().name().toLowerCase());
                typed.add("value", value.toJson());
                bind.add(name, typed);
            });
            include.add("bind", bind);
        }
        includes.add(include);
        return this;
    }

    public NodeBuilder node(String nodeId, String type) {
        if (nodes.has(nodeId)) throw new IllegalArgumentException("duplicate KFX node " + nodeId);
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        nodes.add(nodeId, node);
        return new NodeBuilder(this, node);
    }

    public KfxGraphBuilder output(String nodeId) {
        outputs.add(nodeId);
        return this;
    }

    public KfxGraphBuilder exportNode(String name, String nodeId) {
        exports.addProperty(name, nodeId);
        return this;
    }

    public KfxGraphBuilder budget(int maxParticles, int lifetime) {
        budget.addProperty("max_particles", maxParticles);
        budget.addProperty("lifetime", lifetime);
        return this;
    }

    public KfxGraph build() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("id", id);
        if (!inputs.isEmpty()) root.add("inputs", inputs);
        if (!includes.isEmpty()) root.add("include", includes);
        root.add("nodes", nodes);
        if (!exports.isEmpty()) root.add("exports", exports);
        root.add("outputs", outputs);
        root.add("budget", budget);
        return KfxGraphJson.parse(root.toString(), source);
    }

    public static final class NodeBuilder {
        private final KfxGraphBuilder graph;
        private final JsonObject node;

        private NodeBuilder(KfxGraphBuilder graph, JsonObject node) {
            this.graph = graph;
            this.node = node;
        }

        public NodeBuilder number(String name, double value) {
            node.addProperty(name, value);
            return this;
        }

        public NodeBuilder integer(String name, int value) {
            node.addProperty(name, value);
            return this;
        }

        public NodeBuilder text(String name, String value) {
            node.addProperty(name, value);
            return this;
        }

        public NodeBuilder color(String name, int argb) {
            node.add(name, KfxResolvedValue.color(argb).toJson());
            return this;
        }

        public NodeBuilder input(String name, String input) {
            JsonObject value = new JsonObject();
            value.addProperty("input", input);
            node.add(name, value);
            return this;
        }

        public NodeBuilder randomUniform(String name, double min, double max) {
            return random(name, "uniform", min, max);
        }

        public NodeBuilder randomInteger(String name, int min, int max) {
            return random(name, "integer", min, max);
        }

        private NodeBuilder random(String name, String distribution, double min, double max) {
            JsonObject spec = new JsonObject();
            spec.addProperty("distribution", distribution);
            spec.addProperty("min", min);
            spec.addProperty("max", max);
            JsonObject value = new JsonObject();
            value.add("random", spec);
            node.add(name, value);
            return this;
        }

        public NodeBuilder link(String name, String target) {
            node.addProperty(name, target);
            return this;
        }

        public NodeBuilder links(String name, String... targets) {
            JsonArray values = new JsonArray();
            for (String target : targets) values.add(target);
            node.add(name, values);
            return this;
        }

        public KfxGraphBuilder end() {
            return graph;
        }
    }
}
