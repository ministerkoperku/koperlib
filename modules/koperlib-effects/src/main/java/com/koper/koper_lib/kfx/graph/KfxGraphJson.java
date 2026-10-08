package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxLimits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KfxGraphJson {
    private static final String INLINE = "<inline>";
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile(
        "[a-z0-9_.-]+:[a-z0-9/._-]+"
    );

    private KfxGraphJson() {}

    public static KfxGraph parse(String raw) {
        return parse(raw, INLINE);
    }

    public static KfxGraph parse(String raw, String source) {
        try {
            return parseInternal(raw, source == null || source.isBlank() ? INLINE : source);
        } catch (KfxGraphException error) {
            throw error.atSource(source == null || source.isBlank() ? INLINE : source);
        }
    }

    private static KfxGraph parseInternal(String raw, String source) {
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) throw new KfxGraphException("$", "expected a JSON object");
            root = parsed.getAsJsonObject();
        } catch (KfxGraphException error) {
            throw error;
        } catch (Exception error) {
            throw new KfxGraphException("$", "invalid JSON: " + error.getMessage());
        }

        int version = integer(required(root, "version", "$.version"), "$.version");
        if (version != 2) throw new KfxGraphException("$.version", "expected KFX graph version 2");
        String id = string(required(root, "id", "$.id"), "$.id");
        if (!ID.matcher(id).matches()) {
            throw new KfxGraphException("$.id", "expected a valid namespaced id such as aq:fireball");
        }

        Map<String, KfxGraph.Input> inputs = parseInputs(object(root, "inputs", "$.inputs", true));
        List<KfxInclude> includes = parseIncludes(root);
        Map<String, KfxGraph.Node> nodes = parseNodes(object(root, "nodes", "$.nodes", false), inputs);
        Set<String> includeAliases = includes.stream().map(KfxInclude::alias).collect(java.util.stream.Collectors.toSet());
        validateLinks(nodes, includeAliases);
        Map<String, String> exports = parseExports(object(root, "exports", "$.exports", true), nodes, includeAliases);
        List<String> outputs = parseOutputs(array(root, "outputs", "$.outputs"), nodes, !exports.isEmpty());
        KfxGraph.Budget budget = parseBudget(object(root, "budget", "$.budget", false));
        validateBudget(outputs, nodes, budget);
        return new KfxGraph(id, source, inputs, includes, exports, nodes, outputs, budget);
    }

    static void validateLinkedGraph(KfxGraph graph) {
        try {
            validateLinks(graph.nodes(), Set.of());
            validateResolvedExports(graph.exports(), graph.nodes());
            validateBudget(graph.outputs(), graph.nodes(), graph.budget());
        } catch (KfxGraphException error) {
            throw error.atSource(graph.source());
        }
    }

    private static Map<String, KfxGraph.Input> parseInputs(JsonObject json) {
        Map<String, KfxGraph.Input> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String path = "$.inputs." + entry.getKey();
            JsonObject input = asObject(entry.getValue(), path);
            String rawType = string(required(input, "type", path + ".type"), path + ".type");
            KfxValueType type = KfxValueType.parse(rawType, path + ".type");
            KfxResolvedValue defaultValue = parseConstant(
                required(input, "default", path + ".default"), type, path + ".default"
            );
            inputs.put(entry.getKey(), new KfxGraph.Input(type, defaultValue));
        }
        return inputs;
    }

    private static List<KfxInclude> parseIncludes(JsonObject root) {
        if (!root.has("include")) return List.of();
        JsonElement raw = root.get("include");
        if (!raw.isJsonArray()) throw new KfxGraphException("$.include", "expected an array");
        JsonArray array = raw.getAsJsonArray();
        List<KfxInclude> includes = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String path = "$.include[" + i + "]";
            JsonObject include = asObject(array.get(i), path);
            String graph = string(required(include, "graph", path + ".graph"), path + ".graph");
            if (!ID.matcher(graph).matches()) throw new KfxGraphException(path + ".graph", "expected a valid namespaced graph id");
            String alias = string(required(include, "as", path + ".as"), path + ".as");
            if (!alias.matches("[a-z][a-z0-9_-]*")) {
                throw new KfxGraphException(path + ".as", "alias must match [a-z][a-z0-9_-]*");
            }
            if (!aliases.add(alias)) throw new KfxGraphException(path + ".as", "duplicate include alias '" + alias + "'");

            Map<String, KfxValue> bindings = new LinkedHashMap<>();
            if (include.has("bind")) {
                JsonObject bind = asObject(include.get("bind"), path + ".bind");
                for (Map.Entry<String, JsonElement> binding : bind.entrySet()) {
                    String bindingPath = path + ".bind." + binding.getKey();
                    JsonObject typed = asObject(binding.getValue(), bindingPath);
                    if (typed.has("input")) {
                        bindings.put(binding.getKey(), new KfxValue.Input(
                            string(typed.get("input"), bindingPath + ".input")));
                        continue;
                    }
                    KfxValueType type = KfxValueType.parse(
                        string(required(typed, "type", bindingPath + ".type"), bindingPath + ".type"),
                        bindingPath + ".type"
                    );
                    bindings.put(binding.getKey(), new KfxValue.Constant(parseConstant(
                        required(typed, "value", bindingPath + ".value"), type, bindingPath + ".value"
                    )));
                }
            }
            includes.add(new KfxInclude(alias, graph, bindings));
        }
        return List.copyOf(includes);
    }

    private static Map<String, KfxGraph.Node> parseNodes(JsonObject json, Map<String, KfxGraph.Input> inputs) {
        Map<String, KfxGraph.Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String nodeId = entry.getKey();
            String path = "$.nodes." + nodeId;
            if (!nodeId.matches("[a-z][a-z0-9_-]*")) {
                throw new KfxGraphException(path, "node id must match [a-z][a-z0-9_-]*");
            }
            JsonObject rawNode = asObject(entry.getValue(), path);
            String type = string(required(rawNode, "type", path + ".type"), path + ".type");
            KfxNodeSchemas.Schema schema = KfxNodeSchemas.get(type, path + ".type");

            for (String field : rawNode.keySet()) {
                if (!field.equals("type")
                    && !schema.properties().containsKey(field)
                    && !schema.links().containsKey(field)) {
                    throw new KfxGraphException(path + "." + field, "unknown property for " + type);
                }
            }

            Map<String, KfxValue> parameters = new LinkedHashMap<>();
            for (KfxNodeSchemas.Property property : schema.properties().values()) {
                String propertyPath = path + "." + property.name();
                if (!rawNode.has(property.name())) {
                    if (property.required()) throw new KfxGraphException(propertyPath, "missing required value");
                    parameters.put(property.name(), new KfxValue.Constant(property.defaultValue()));
                    continue;
                }
                parameters.put(property.name(), parseValue(
                    rawNode.get(property.name()), property, inputs, propertyPath
                ));
            }

            Map<String, List<String>> links = new LinkedHashMap<>();
            for (Map.Entry<String, KfxNodeSchemas.Link> linkEntry : schema.links().entrySet()) {
                String name = linkEntry.getKey();
                String linkPath = path + "." + name;
                if (!rawNode.has(name)) {
                    if (linkEntry.getValue().required()) throw new KfxGraphException(linkPath, "missing required link");
                    continue;
                }
                List<String> targets = parseLink(rawNode.get(name), linkEntry.getValue().many(), linkPath);
                if (targets.isEmpty()) throw new KfxGraphException(linkPath, "link needs at least one target");
                links.put(name, targets);
            }
            validateLocalValues(type, parameters, path);
            nodes.put(nodeId, new KfxGraph.Node(type, parameters, links));
        }
        if (nodes.isEmpty()) throw new KfxGraphException("$.nodes", "graph needs at least one node");
        return nodes;
    }

    private static Map<String, String> parseExports(
        JsonObject json,
        Map<String, KfxGraph.Node> nodes,
        Set<String> includeAliases
    ) {
        Map<String, String> exports = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String path = "$.exports." + entry.getKey();
            if (!entry.getKey().matches("[a-z][a-z0-9_-]*")) {
                throw new KfxGraphException(path, "export name must match [a-z][a-z0-9_-]*");
            }
            String target = string(entry.getValue(), path);
            if (!nodes.containsKey(target) && !isIncludeReference(target, includeAliases)) {
                throw new KfxGraphException(path, "unknown exported node '" + target + "'");
            }
            exports.put(entry.getKey(), target);
        }
        return java.util.Collections.unmodifiableMap(exports);
    }

    private static KfxValue parseValue(
        JsonElement json,
        KfxNodeSchemas.Property property,
        Map<String, KfxGraph.Input> inputs,
        String path
    ) {
        if (json.isJsonPrimitive()) return new KfxValue.Constant(parseConstant(json, property.type(), path));
        JsonObject object = asObject(json, path);
        if (object.has("input")) {
            if (!property.allowInput()) throw new KfxGraphException(path, "input values are not bounded here in Part 1");
            String name = string(object.get("input"), path + ".input");
            KfxGraph.Input input = inputs.get(name);
            if (input == null) throw new KfxGraphException(path + ".input", "unknown input '" + name + "'");
            if (input.type() != property.type()) {
                throw new KfxGraphException(path,
                    "expected " + label(property.type()) + ", input '" + name + "' is " + label(input.type()));
            }
            return new KfxValue.Input(name);
        }
        if (object.has("random")) {
            if (!property.allowRandom()) throw new KfxGraphException(path, "random values are not allowed here");
            return new KfxValue.Random(parseDistribution(
                asObject(object.get("random"), path + ".random"), property.type(), path + ".random"
            ));
        }
        throw new KfxGraphException(path, "expected a constant, input, or random distribution");
    }

    private static KfxDistribution parseDistribution(JsonObject json, KfxValueType expected, String path) {
        String kind = string(required(json, "distribution", path + ".distribution"), path + ".distribution");
        return switch (kind.toLowerCase()) {
            case "uniform", "integer" -> {
                boolean integer = kind.equalsIgnoreCase("integer");
                KfxValueType actual = integer ? KfxValueType.INTEGER : KfxValueType.NUMBER;
                if (actual != expected) {
                    throw new KfxGraphException(path, "distribution produces " + label(actual) + ", expected " + label(expected));
                }
                double min = number(required(json, "min", path + ".min"), path + ".min");
                double max = number(required(json, "max", path + ".max"), path + ".max");
                if (max < min) throw new KfxGraphException(path, "random max must be at least min");
                if (integer && Math.ceil(min) > Math.floor(max)) {
                    throw new KfxGraphException(path, "integer distribution contains no whole number");
                }
                yield new KfxDistribution.Uniform(min, max, integer);
            }
            case "palette" -> {
                JsonArray values = array(json, "values", path + ".values");
                if (values.isEmpty()) throw new KfxGraphException(path + ".values", "palette cannot be empty");
                List<KfxResolvedValue> parsed = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    parsed.add(parseConstant(values.get(i), expected, path + ".values[" + i + "]"));
                }
                yield new KfxDistribution.Palette(parsed);
            }
            default -> throw new KfxGraphException(path + ".distribution", "unknown distribution '" + kind + "'");
        };
    }

    private static List<String> parseLink(JsonElement json, boolean many, String path) {
        if (!many) return List.of(string(json, path));
        if (!json.isJsonArray()) throw new KfxGraphException(path, "expected an array of node ids");
        JsonArray array = json.getAsJsonArray();
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) targets.add(string(array.get(i), path + "[" + i + "]"));
        return List.copyOf(targets);
    }

    private static void validateLocalValues(String type, Map<String, KfxValue> parameters, String path) {
        if (type.equals(KfxNodeSchemas.SOURCE_RING)) {
            KfxValue mode = parameters.get("mode");
            String value = (String)((KfxValue.Constant)mode).value().value();
            if (!value.equals("ring") && !value.equals("burst")) {
                throw new KfxGraphException(path + ".mode", "expected 'ring' or 'burst'");
            }
        }
        if (type.equals(KfxNodeSchemas.RENDER_PARTICLES)) {
            KfxValue count = parameters.get("count");
            double min = numericLowerBound(count, path + ".count");
            double max = numericUpperBound(count, path + ".count");
            if (min < 1) throw new KfxGraphException(path + ".count", "particle count must be at least 1");
            if (max > KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH) {
                throw new KfxGraphException(path + ".count", "particle count exceeds engine hard cap "
                    + KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH);
            }
        }
    }

    private static void validateLinks(Map<String, KfxGraph.Node> nodes, Set<String> includeAliases) {
        for (Map.Entry<String, KfxGraph.Node> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            KfxNodeSchemas.Schema schema = KfxNodeSchemas.get(entry.getValue().type(), "$.nodes." + nodeId + ".type");
            for (Map.Entry<String, List<String>> link : entry.getValue().links().entrySet()) {
                KfxNodeSchemas.Link contract = schema.links().get(link.getKey());
                for (String targetId : link.getValue()) {
                    KfxGraph.Node target = nodes.get(targetId);
                    if (target == null && isIncludeReference(targetId, includeAliases)) continue;
                    if (target == null) throw new KfxGraphException(
                        "$.nodes." + nodeId + "." + link.getKey(), "unknown node '" + targetId + "'"
                    );
                    if (!contract.targetTypes().contains(target.type())) {
                        throw new KfxGraphException("$.nodes." + nodeId + "." + link.getKey(),
                            "node '" + targetId + "' has incompatible type " + target.type());
                    }
                }
            }
        }
        Map<String, Integer> state = new HashMap<>();
        for (String nodeId : nodes.keySet()) detectCycle(nodeId, nodes, state);
    }

    private static boolean isIncludeReference(String target, Set<String> includeAliases) {
        int slash = target.indexOf('/');
        return slash > 0 && slash == target.lastIndexOf('/') && slash < target.length() - 1
            && includeAliases.contains(target.substring(0, slash));
    }

    private static void validateResolvedExports(Map<String, String> exports, Map<String, KfxGraph.Node> nodes) {
        exports.forEach((name, target) -> {
            if (!nodes.containsKey(target)) {
                throw new KfxGraphException("$.exports." + name, "unknown exported node '" + target + "'");
            }
        });
    }

    private static void detectCycle(String nodeId, Map<String, KfxGraph.Node> nodes, Map<String, Integer> state) {
        if (state.getOrDefault(nodeId, 0) == 2) return;
        state.put(nodeId, 1);
        KfxGraph.Node node = nodes.get(nodeId);
        for (Map.Entry<String, List<String>> link : node.links().entrySet()) {
            for (String target : link.getValue()) {
                if (!nodes.containsKey(target)) continue;
                if (state.getOrDefault(target, 0) == 1) {
                    throw new KfxGraphException("$.nodes." + nodeId + "." + link.getKey(), "graph link cycle through '" + target + "'");
                }
                detectCycle(target, nodes, state);
            }
        }
        state.put(nodeId, 2);
    }

    private static List<String> parseOutputs(JsonArray json, Map<String, KfxGraph.Node> nodes, boolean hasExports) {
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < json.size(); i++) {
            String id = string(json.get(i), "$.outputs[" + i + "]");
            KfxGraph.Node node = nodes.get(id);
            if (node == null) throw new KfxGraphException("$.outputs[" + i + "]", "unknown node '" + id + "'");
            if (!node.type().equals(KfxNodeSchemas.GROUP) && !KfxNodeSchemas.isRender(node.type())) {
                throw new KfxGraphException("$.outputs[" + i + "]", "output must be a render or group node");
            }
            if (!outputs.contains(id)) outputs.add(id);
        }
        if (outputs.isEmpty() && !hasExports) {
            throw new KfxGraphException("$.outputs", "graph needs an output or an exported node");
        }
        return outputs;
    }

    private static KfxGraph.Budget parseBudget(JsonObject json) {
        int maxParticles = integer(required(json, "max_particles", "$.budget.max_particles"), "$.budget.max_particles");
        int lifetime = integer(required(json, "lifetime", "$.budget.lifetime"), "$.budget.lifetime");
        if (maxParticles < 1) throw new KfxGraphException("$.budget.max_particles", "must be at least 1");
        if (maxParticles > KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH) {
            throw new KfxGraphException("$.budget.max_particles", "exceeds engine hard cap " + KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH);
        }
        if (lifetime < 1) throw new KfxGraphException("$.budget.lifetime", "must be at least 1 tick");
        return new KfxGraph.Budget(maxParticles, lifetime);
    }

    private static void validateBudget(List<String> outputs, Map<String, KfxGraph.Node> nodes, KfxGraph.Budget budget) {
        Set<String> visited = new HashSet<>();
        double[] total = {0};
        for (String output : outputs) accumulateBudget(output, nodes, visited, total, budget);
    }

    private static void accumulateBudget(
        String nodeId,
        Map<String, KfxGraph.Node> nodes,
        Set<String> visited,
        double[] total,
        KfxGraph.Budget budget
    ) {
        if (!visited.add(nodeId)) return;
        KfxGraph.Node node = nodes.get(nodeId);
        if (node.type().equals(KfxNodeSchemas.RENDER_PARTICLES)) {
            String path = "$.nodes." + nodeId + ".count";
            int minimum = backendMinimum(node, nodes);
            double lower = numericLowerBound(node.parameters().get("count"), path);
            if (lower < minimum) {
                throw new KfxGraphException(path, "backend minimum " + minimum + " must fit the declared count");
            }
            total[0] += numericUpperBound(node.parameters().get("count"), path);
            if (total[0] > budget.maxParticles()) {
                throw new KfxGraphException(path, "declared worst case exceeds max_particles budget " + budget.maxParticles());
            }
        }
        for (List<String> targets : node.links().values()) {
            for (String target : targets) {
                if (nodes.containsKey(target)) accumulateBudget(target, nodes, visited, total, budget);
            }
        }
    }

    private static int backendMinimum(KfxGraph.Node render, Map<String, KfxGraph.Node> nodes) {
        KfxGraph.Node source = nodes.get(render.links().get("source").getFirst());
        if (source == null) return 1; // included export; exact backend minimum is checked after linking
        if (source.type().equals(KfxNodeSchemas.SOURCE_SPIRAL)) return 8;
        if (source.type().equals(KfxNodeSchemas.SOURCE_RING)) {
            KfxResolvedValue mode = ((KfxValue.Constant)source.parameters().get("mode")).value();
            return "burst".equals(mode.value()) ? 8 : 1;
        }
        return 1;
    }

    private static double numericUpperBound(KfxValue value, String path) {
        if (value instanceof KfxValue.Constant constant) return constant.value().asNumber(path);
        if (value instanceof KfxValue.Random random) return random.distribution().numericUpperBound(path);
        throw new KfxGraphException(path, "input-driven cost needs a declared bound");
    }

    private static double numericLowerBound(KfxValue value, String path) {
        if (value instanceof KfxValue.Constant constant) return constant.value().asNumber(path);
        if (value instanceof KfxValue.Random random) return random.distribution().numericLowerBound(path);
        throw new KfxGraphException(path, "input-driven cost needs a declared bound");
    }

    private static KfxResolvedValue parseConstant(JsonElement json, KfxValueType type, String path) {
        try {
            return switch (type) {
                case NUMBER -> KfxResolvedValue.number(number(json, path));
                case INTEGER -> KfxResolvedValue.integer(integer(json, path));
                case COLOR -> KfxResolvedValue.color(parseColor(string(json, path), path));
                case TEXT -> KfxResolvedValue.text(string(json, path));
            };
        } catch (KfxGraphException error) {
            throw error;
        } catch (Exception error) {
            throw new KfxGraphException(path, "value does not match " + label(type));
        }
    }

    private static int parseColor(String raw, String path) {
        String value = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            if (value.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(value, 16);
            if (value.length() == 8) return (int)Long.parseLong(value, 16);
        } catch (NumberFormatException ignored) {
        }
        throw new KfxGraphException(path, "expected #RRGGBB or #AARRGGBB");
    }

    private static String label(KfxValueType type) {
        return type.name().toLowerCase();
    }

    private static JsonElement required(JsonObject json, String name, String path) {
        if (!json.has(name) || json.get(name).isJsonNull()) throw new KfxGraphException(path, "missing required value");
        return json.get(name);
    }

    private static JsonObject object(JsonObject json, String name, String path, boolean optional) {
        if (optional && !json.has(name)) return new JsonObject();
        return asObject(required(json, name, path), path);
    }

    private static JsonObject asObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw new KfxGraphException(path, "expected an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String name, String path) {
        JsonElement value = required(json, name, path);
        if (!value.isJsonArray()) throw new KfxGraphException(path, "expected an array");
        return value.getAsJsonArray();
    }

    private static String string(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new KfxGraphException(path, "expected a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement value, String path) {
        double number = number(value, path);
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new KfxGraphException(path, "expected a 32-bit integer");
        }
        return (int)number;
    }

    private static double number(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new KfxGraphException(path, "expected a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new KfxGraphException(path, "number must be finite");
        return number;
    }
}
