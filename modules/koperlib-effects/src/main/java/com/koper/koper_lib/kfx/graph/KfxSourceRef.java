package com.koper.koper_lib.kfx.graph;

public record KfxSourceRef(KfxOrigin origin, String graphId, String source) {
    public KfxSourceRef {
        if (origin == null) throw new IllegalArgumentException("KFX origin cannot be null");
        graphId = graphId == null ? "" : graphId;
        source = source == null ? "" : source;
    }
}
