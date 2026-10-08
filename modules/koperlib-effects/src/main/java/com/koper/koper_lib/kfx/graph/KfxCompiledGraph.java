package com.koper.koper_lib.kfx.graph;

import java.util.LinkedHashMap;
import java.util.Map;

public record KfxCompiledGraph(
    String id,
    String programJson,
    int lifetime,
    int maxParticles,
    int totalParticles,
    Map<String, KfxResolvedValue> sampledValues
) {
    public KfxCompiledGraph {
        sampledValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sampledValues));
    }

    public double number(String propertyPath) {
        KfxResolvedValue value = sampledValues.get(propertyPath);
        if (value == null) throw new KfxGraphException(propertyPath, "compiled value does not exist");
        return value.asNumber(propertyPath);
    }

    public int color(String propertyPath) {
        KfxResolvedValue value = sampledValues.get(propertyPath);
        if (value == null) throw new KfxGraphException(propertyPath, "compiled value does not exist");
        return value.asColor(propertyPath);
    }
}
