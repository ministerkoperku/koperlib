package com.koper.koper_lib.api;

import net.minecraft.world.item.Item;

@FunctionalInterface
public interface KoperItemType {
    Item create(KoperItemBuildContext ctx);
}
