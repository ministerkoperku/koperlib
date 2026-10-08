package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;

public interface KfxPrimitive {
    Identifier id();
    Compiler nativeCompiler();
    Compiler portableCompiler();

    @FunctionalInterface
    interface Compiler {
        void lower(KfxRenderPlan.Node node, KfxBatchBook batches);
    }
}
