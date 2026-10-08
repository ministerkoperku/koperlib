package com.koper.koper_lib.api;

import java.lang.annotation.*;

// slap this on a method to handle a koperlib item/entity event in Java
// value format: "namespace:item_path/event_name"  e.g. "koper_examples:flame_sword/on_use"
// method params: any combo of (KoperContext, ServerPlayer, LivingEntity, Level, ItemStack, BlockPos) — order doesn't matter
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KoperHook {
    String value();
}
