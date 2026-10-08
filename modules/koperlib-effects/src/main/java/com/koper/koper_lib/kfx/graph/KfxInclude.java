package com.koper.koper_lib.kfx.graph;

import java.util.Map;

/**
 * One fragment reference. A binding is either a constant or a parent graph input, so a spell can hand
 * its own colour, power or radius down into a shared fragment instead of the fragment hard-coding one.
 */
public record KfxInclude(String alias, String graphId, Map<String, KfxValue> bindings) {
    public KfxInclude {
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("KFX include alias cannot be blank");
        if (graphId == null || graphId.isBlank()) throw new IllegalArgumentException("KFX include graph id cannot be blank");
        bindings = Map.copyOf(bindings);
    }
}
