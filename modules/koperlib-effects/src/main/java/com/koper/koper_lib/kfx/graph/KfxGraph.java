package com.koper.koper_lib.kfx.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record KfxGraph(
    String id,
    String source,
    Map<String, Input> inputs,
    List<KfxInclude> includes,
    Map<String, String> exports,
    Map<String, Node> nodes,
    List<String> outputs,
    Budget budget
) {
    public KfxGraph {
        inputs = Map.copyOf(inputs);
        includes = List.copyOf(includes);
        exports = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(exports));
        nodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        outputs = List.copyOf(outputs);
    }

    public record Input(KfxValueType type, KfxResolvedValue defaultValue) {}

    public record Node(String type, Map<String, KfxValue> parameters, Map<String, List<String>> links) {
        public Node {
            parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            LinkedHashMap<String, List<String>> copiedLinks = new LinkedHashMap<>();
            links.forEach((name, targets) -> copiedLinks.put(name, List.copyOf(targets)));
            links = java.util.Collections.unmodifiableMap(copiedLinks);
        }
    }

    public record Budget(int maxParticles, int lifetime) {}
}
