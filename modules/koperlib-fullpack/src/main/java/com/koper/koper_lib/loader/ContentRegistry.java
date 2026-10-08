package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperItemData;
import net.minecraft.core.MappedRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class ContentRegistry {
    private static final Map<Identifier, Item>          ITEMS     = new HashMap<>();
    private static final Map<Identifier, Block>         BLOCKS    = new HashMap<>();
    private static final Map<Identifier, EntityType<?>> ENTITIES  = new HashMap<>();
    // item id → original json data — used by KoperItemRef API
    private static final Map<Identifier, KoperItemData>  ITEM_DATA  = new HashMap<>();
    private static final Map<Identifier, com.koper.koper_lib.data.KoperBlockData> BLOCK_DATA = new HashMap<>();

    // clears tracking maps for reload; MC registries can't be unregistered at runtime
    public static void clearAll() {
        int items    = ITEMS.size();
        int blocks   = BLOCKS.size();
        int entities = ENTITIES.size();
        ITEMS.clear();
        BLOCKS.clear();
        ENTITIES.clear();
        ITEM_DATA.clear();
        BLOCK_DATA.clear();
        com.koper.koper_lib.core.KoperBlockRuntimeEvents.invalidate();
        com.koper.koper_lib.scripting.ItemScriptRegistry.clear();
        KoperLib.LOGGER.info("[ContentRegistry] Cleared {} items, {} blocks, {} entities for reload.",
            items, blocks, entities);
    }

    // called from ItemFactory right after registerItem — stores the raw data for API access
    public static void storeItemData(String idStr, KoperItemData data) {
        Identifier id = Identifier.tryParse(idStr);
        if (id != null && data != null) ITEM_DATA.put(id, data);
    }

    public static KoperItemData getItemData(Identifier id) {
        return ITEM_DATA.get(id);
    }

    public static void storeBlockData(String idStr, com.koper.koper_lib.data.KoperBlockData data) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null || data == null) return;
        BLOCK_DATA.put(id, data);
        com.koper.koper_lib.core.KoperBlockRuntimeEvents.invalidate(); // tick plan is derived from this map
    }

    public static com.koper.koper_lib.data.KoperBlockData getBlockData(Identifier id) {
        return BLOCK_DATA.get(id);
    }

    public static Map<Identifier, com.koper.koper_lib.data.KoperBlockData> getAllBlockData() {
        return Collections.unmodifiableMap(BLOCK_DATA);
    }

    public static Map<Identifier, KoperItemData> getAllItemData() {
        return Collections.unmodifiableMap(ITEM_DATA);
    }

    // after Registry.freeze() MC nulls intrusiveHolderCache; Block/Item constructors always call
    // createIntrusiveHolder(this) and crash if it's null — restore it with reflection before re-registering
    public static void prepareRegistriesForNewContent() {
        List<MappedRegistry<?>> registries = List.of(
            (MappedRegistry<?>) BuiltInRegistries.BLOCK,
            (MappedRegistry<?>) BuiltInRegistries.ITEM,
            (MappedRegistry<?>) BuiltInRegistries.ENTITY_TYPE
        );

        for (MappedRegistry<?> registry : registries) {
            restoreIntrusiveCache(registry);
        }
        KoperLib.LOGGER.debug("[ContentRegistry] Intrusive holder caches restored for new content registration.");
    }

    private static void restoreIntrusiveCache(MappedRegistry<?> registry) {
        // Find the null Map field by type. "Only intrusiveHolderCache is null after freeze()" was never
        // true: Fabric adds fabric_prevEntries (a BiMap) and fabric_prevIndexedEntries (an
        // Object2IntMap) to this class and both sit null in normal play. They matched the Map test,
        // took the assignment first and threw on the type mismatch — which is where the
        // "Could not restore intrusive cache field" warnings in every startup log come from.
        // Today that is only noise, because both throw and the loop moves on to the real field. It
        // stops being noise the moment anything adds a null field of plain Map type: that one would
        // be assigned instead, the method would return, and intrusiveHolderCache would stay null —
        // so every Block and Item constructor in the reload would NPE in createIntrusiveHolder.
        // Only consider fields that can actually hold what we are about to put in them.
        for (Field f : MappedRegistry.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            if (!f.getType().isAssignableFrom(IdentityHashMap.class)) continue;
            try {
                f.setAccessible(true);
                if (f.get(registry) == null) {
                    f.set(registry, new IdentityHashMap<>());
                    KoperLib.LOGGER.debug("[ContentRegistry] Restored intrusive cache field '{}' in {}",
                        f.getName(), registry.key().identifier());
                    return;
                }
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[ContentRegistry] Could not restore intrusive cache field '{}': {}",
                    f.getName(), e.getMessage());
            }
        }
    }

    public static Item.Properties createItemSettings(String idStr) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return new Item.Properties();
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return new Item.Properties().setId(key);
    }

    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties createBlockSettings(String idStr) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of();
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().setId(key);
    }

    public static void registerItem(String idStr, Item item, String tabName) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            Registry.register(BuiltInRegistries.ITEM, id, item);
            KoperLib.LOGGER.info("[ContentRegistry] Registered Item: {}", idStr);
        } else {
            KoperLib.LOGGER.debug("[ContentRegistry] Item already registered, refreshing assets: {}", idStr);
        }

        ITEMS.put(id, item);
        String resolvedTab = (tabName != null && !tabName.isEmpty()) ? tabName : id.getNamespace() + ":main";
        CreativeTabRegistry.addToTab(resolvedTab, item);
    }

    public static void registerBlock(String idStr, Block block, String tabName) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            Registry.register(BuiltInRegistries.BLOCK, id, block);
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                // a pack item with the block's own id (bedrock does that: the item stands in for the block).
                // a second BlockItem made here would never get registered, and an unregistered item kills
                // the game at startup ("intrusive holders were not registered"). the pack's item stays
                KoperLib.LOGGER.warn("[ContentRegistry] block {} shares its id with an item that is already there, that item stays the block's item", idStr);
                ITEMS.put(id, BuiltInRegistries.ITEM.getValue(id));
            } else {
                ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
                Item blockItem = new BlockItem(block, new Item.Properties().setId(itemKey));
                Registry.register(BuiltInRegistries.ITEM, id, blockItem);
                ITEMS.put(id, blockItem);
                KoperLib.LOGGER.info("[ContentRegistry] Registered Block & BlockItem: {}", idStr);
            }
        } else {
            KoperLib.LOGGER.debug("[ContentRegistry] Block already registered, refreshing assets: {}", idStr);
            ITEMS.put(id, BuiltInRegistries.ITEM.getValue(id));
        }

        BLOCKS.put(id, block);
        String resolvedTab = (tabName != null && !tabName.isEmpty()) ? tabName : id.getNamespace() + ":main";
        CreativeTabRegistry.addToTab(resolvedTab, BuiltInRegistries.ITEM.getValue(id));
    }

    public static void registerEntity(String idStr, EntityType<?> entityType) {
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            Registry.register(BuiltInRegistries.ENTITY_TYPE, id, entityType);
            KoperLib.LOGGER.info("[ContentRegistry] Registered Entity: {}", idStr);
        } else {
            KoperLib.LOGGER.debug("[ContentRegistry] Entity already registered: {}", idStr);
        }

        ENTITIES.put(id, entityType);
    }
}
