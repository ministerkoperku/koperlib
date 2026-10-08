package com.koper.koper_lib.api;

import com.koper.koper_lib.KoperLib;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class KoperItemTypes {
    private static final Map<String, KoperItemType> TYPES = new ConcurrentHashMap<>();

    private KoperItemTypes() {}

    public static void register(String type, KoperItemType factory) {
        if (type == null || type.isBlank() || factory == null) return;
        TYPES.put(type.toLowerCase(), factory);
        KoperLib.LOGGER.info("[KoperItemType] registered {}", type.toLowerCase());
    }

    public static Item create(String type, KoperItemBuildContext ctx) {
        if (type == null) return null;
        KoperItemType factory = TYPES.get(type.toLowerCase());
        if (factory == null) return null;
        return factory.create(ctx);
    }

    public static Set<String> ids() {
        return java.util.Collections.unmodifiableSet(TYPES.keySet());
    }
}
