package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxResolvedValue;

import java.util.Map;
import java.util.Objects;

public record KfxPlayRequest(
    String graph,
    Map<String, KfxResolvedValue> parameters,
    KfxAnchor start,
    KfxAnchor end,
    long seed
) {
    public KfxPlayRequest {
        if (graph == null || graph.isBlank()) throw new IllegalArgumentException("KFX play request needs a graph id");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
