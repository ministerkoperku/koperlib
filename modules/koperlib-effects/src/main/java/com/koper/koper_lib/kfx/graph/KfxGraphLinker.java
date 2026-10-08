package com.koper.koper_lib.kfx.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class KfxGraphLinker {
    private KfxGraphLinker() {}

    static Map<String, KfxLinkedGraph> link(Map<String, KfxGraphRegistry.Declaration> declarations) {
        Map<String, KfxLinkedGraph> result = new LinkedHashMap<>();
        for (String id : new java.util.TreeSet<>(declarations.keySet())) {
            linkOne(id, declarations, result, new ArrayList<>());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static KfxLinkedGraph linkOne(
        String id,
        Map<String, KfxGraphRegistry.Declaration> declarations,
        Map<String, KfxLinkedGraph> memo,
        List<String> stack
    ) {
        KfxLinkedGraph cached = memo.get(id);
        if (cached != null) return cached;
        int cycleAt = stack.indexOf(id);
        if (cycleAt >= 0) {
            List<String> cycle = new ArrayList<>(stack.subList(cycleAt, stack.size()));
            cycle.add(id);
            throw new KfxGraphException("registry", "$.include", "include cycle: " + String.join(" -> ", cycle));
        }
        KfxGraphRegistry.Declaration declaration = declarations.get(id);
        if (declaration == null) {
            throw new KfxGraphException("registry", "$.include", "unknown included graph '" + id + "'");
        }

        stack.add(id);
        KfxGraph own = declaration.graph();
        Map<String, KfxGraph.Node> nodes = new LinkedHashMap<>();
        List<String> outputs = new ArrayList<>(own.outputs());
        Map<String, String> includedExports = new LinkedHashMap<>();
        List<KfxSourceRef> chain = new ArrayList<>();
        chain.add(new KfxSourceRef(declaration.origin(), own.id(), own.source()));

        for (KfxInclude include : own.includes()) {
            KfxLinkedGraph child = linkOne(include.graphId(), declarations, memo, stack);
            Map<String, KfxValue> boundInputs = bindInputs(include, child.graph(), own);
            String prefix = include.alias() + "/";

            for (Map.Entry<String, KfxGraph.Node> childNode : child.graph().nodes().entrySet()) {
                String prefixedId = prefix + childNode.getKey();
                if (nodes.containsKey(prefixedId)) {
                    throw new KfxGraphException(own.source(), "$.include",
                        "include alias '" + include.alias() + "' collides at node " + prefixedId);
                }
                nodes.put(prefixedId, copyNode(childNode.getValue(), prefix, boundInputs));
            }
            child.graph().exports().forEach((name, target) ->
                includedExports.put(include.alias() + "/" + name, prefix + target)
            );
            for (String childOutput : child.graph().outputs()) outputs.add(prefix + childOutput);
            appendUnique(chain, child.sourceChain());
        }
        stack.removeLast();

        for (Map.Entry<String, KfxGraph.Node> ownNode : own.nodes().entrySet()) {
            nodes.put(ownNode.getKey(), resolveOwnNode(
                ownNode.getKey(), ownNode.getValue(), own.nodes(), includedExports, own
            ));
        }
        Map<String, String> exports = new LinkedHashMap<>();
        own.exports().forEach((name, target) -> exports.put(name,
            resolveOwnTarget(target, own.nodes(), includedExports, own, "$.exports." + name)
        ));

        KfxGraph merged = new KfxGraph(
            own.id(), own.source(), own.inputs(), List.of(), exports, nodes, outputs, own.budget()
        );
        KfxGraphJson.validateLinkedGraph(merged);
        KfxLinkedGraph linked = new KfxLinkedGraph(merged, chain);
        memo.put(id, linked);
        return linked;
    }

    private static Map<String, KfxValue> bindInputs(KfxInclude include, KfxGraph child, KfxGraph parent) {
        for (String binding : include.bindings().keySet()) {
            if (!child.inputs().containsKey(binding)) {
                throw new KfxGraphException(parent.source(), "$.include." + include.alias() + ".bind." + binding,
                    "included graph has no input '" + binding + "'");
            }
        }
        Map<String, KfxValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, KfxGraph.Input> input : child.inputs().entrySet()) {
            String path = "$.include." + include.alias() + ".bind." + input.getKey();
            KfxValueType expected = input.getValue().type();
            KfxValue bound = include.bindings().get(input.getKey());
            if (bound == null) {
                values.put(input.getKey(), new KfxValue.Constant(input.getValue().defaultValue()));
                continue;
            }
            values.put(input.getKey(), checkedBinding(bound, expected, parent, path));
        }
        return values;
    }

    private static KfxValue checkedBinding(KfxValue bound, KfxValueType expected, KfxGraph parent, String path) {
        if (bound instanceof KfxValue.Constant constant) {
            if (constant.value().type() != expected) {
                throw new KfxGraphException(parent.source(), path, "expected "
                    + expected.name().toLowerCase() + ", got " + constant.value().type().name().toLowerCase());
            }
            return constant;
        }
        if (bound instanceof KfxValue.Input parentInput) {
            KfxGraph.Input declared = parent.inputs().get(parentInput.name());
            if (declared == null) {
                throw new KfxGraphException(parent.source(), path,
                    "including graph has no input '" + parentInput.name() + "' to bind");
            }
            if (declared.type() != expected) {
                throw new KfxGraphException(parent.source(), path, "expected "
                    + expected.name().toLowerCase() + ", got " + declared.type().name().toLowerCase());
            }
            return parentInput;
        }
        throw new KfxGraphException(parent.source(), path, "a binding must be a constant or a graph input");
    }

    private static KfxGraph.Node copyNode(
        KfxGraph.Node node,
        String prefix,
        Map<String, KfxValue> boundInputs
    ) {
        Map<String, KfxValue> parameters = new LinkedHashMap<>();
        node.parameters().forEach((name, value) -> {
            if (value instanceof KfxValue.Input input) {
                KfxValue bound = boundInputs.get(input.name());
                if (bound == null) throw new KfxGraphException("registry", name, "missing bound input '" + input.name() + "'");
                parameters.put(name, bound);
            } else {
                parameters.put(name, value);
            }
        });
        Map<String, List<String>> links = new LinkedHashMap<>();
        node.links().forEach((name, targets) ->
            links.put(name, targets.stream().map(target -> prefix + target).toList())
        );
        return new KfxGraph.Node(node.type(), parameters, links);
    }

    private static KfxGraph.Node resolveOwnNode(
        String nodeId,
        KfxGraph.Node node,
        Map<String, KfxGraph.Node> ownNodes,
        Map<String, String> includedExports,
        KfxGraph owner
    ) {
        Map<String, List<String>> links = new LinkedHashMap<>();
        node.links().forEach((name, targets) -> links.put(name, targets.stream()
            .map(target -> resolveOwnTarget(target, ownNodes, includedExports, owner, "$.nodes." + nodeId + "." + name))
            .toList()));
        return new KfxGraph.Node(node.type(), node.parameters(), links);
    }

    private static String resolveOwnTarget(
        String target,
        Map<String, KfxGraph.Node> ownNodes,
        Map<String, String> includedExports,
        KfxGraph owner,
        String path
    ) {
        if (ownNodes.containsKey(target)) return target;
        String resolved = includedExports.get(target);
        if (resolved != null) return resolved;
        int slash = target.indexOf('/');
        String export = slash >= 0 && slash < target.length() - 1 ? target.substring(slash + 1) : target;
        throw new KfxGraphException(owner.source(), path, "included graph has no export '" + export + "'");
    }

    private static void appendUnique(List<KfxSourceRef> target, List<KfxSourceRef> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (KfxSourceRef source : target) seen.add(source.origin() + ":" + source.graphId());
        for (KfxSourceRef source : values) {
            if (seen.add(source.origin() + ":" + source.graphId())) target.add(source);
        }
    }
}
