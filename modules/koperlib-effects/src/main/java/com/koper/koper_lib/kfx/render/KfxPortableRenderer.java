package com.koper.koper_lib.kfx.render;

/** Collects portable batches from the same immutable plan used by native lowering. */
public final class KfxPortableRenderer {
    public KfxBatchBook lower(KfxRenderPlan plan) {
        KfxBatchBook batches = new KfxBatchBook();
        KfxPrimitiveRegistry registry = KfxPrimitiveRegistry.builtin();
        for (KfxRenderPlan.Node node : plan.nodes()) {
            KfxPrimitive primitive = registry.get(node.primitive());
            if (primitive != null) primitive.portableCompiler().lower(node, batches);
        }
        return batches;
    }
}
