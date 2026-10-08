package com.koper.koper_lib.api;

import com.google.gson.JsonObject;
import com.koper.koper_lib.data.KoperItemData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

public record KoperItemBuildContext(
    Identifier id,
    KoperItemData data,
    JsonObject json,
    Item.Properties properties
) {
}
