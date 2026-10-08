package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KfxBatchBook {
    public record Key(String backend, Identifier primitive, Identifier material) {}
    private final Map<Key, List<KfxRenderPlan.Node>> batches = new LinkedHashMap<>();

    public void add(String backend, KfxRenderPlan.Node node) {
        batches.computeIfAbsent(new Key(backend, node.primitive(), node.material()), ignored -> new ArrayList<>())
            .add(node);
    }

    public Map<Key, List<KfxRenderPlan.Node>> snapshot() {
        Map<Key, List<KfxRenderPlan.Node>> copy = new LinkedHashMap<>();
        batches.forEach((key, nodes) -> copy.put(key, List.copyOf(nodes)));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
