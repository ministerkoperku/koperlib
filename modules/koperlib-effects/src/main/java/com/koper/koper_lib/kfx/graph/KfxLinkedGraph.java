package com.koper.koper_lib.kfx.graph;

import java.util.List;

public record KfxLinkedGraph(KfxGraph graph, List<KfxSourceRef> sourceChain) {
    public KfxLinkedGraph {
        sourceChain = List.copyOf(sourceChain);
    }
}
