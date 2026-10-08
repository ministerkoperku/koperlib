package com.koper.koper_lib.api;

import net.minecraft.world.InteractionResult;

@FunctionalInterface
public interface KoperCallHandler {
    InteractionResult run(KoperCallContext ctx);
}
