package com.koper.koper_lib.api.attachment;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class KoperAttachments {

    private static final Map<Block, Function<BlockState, List<KoperAttachmentPoint>>> POINTS =
        new ConcurrentHashMap<>();
    private static final Map<Identifier, Function<BlockState, List<KoperAttachmentPoint>>> POINT_IDS =
        new ConcurrentHashMap<>();
    private static final Set<Item> TOOLS = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> TOOL_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<Item> PLACEMENT_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<Item> ROTATABLE_PLACEMENT_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> PLACEMENT_ITEM_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> ROTATABLE_PLACEMENT_ITEM_IDS = ConcurrentHashMap.newKeySet();

    private KoperAttachments() {}

    public static void register(Block block, Function<BlockState, List<KoperAttachmentPoint>> points) {
        POINTS.put(block, points);
    }

    public static void register(Identifier blockId, Function<BlockState, List<KoperAttachmentPoint>> points) {
        POINT_IDS.put(blockId, points);
    }

    public static void registerTool(Item item) {
        TOOLS.add(item);
    }

    public static void registerTool(Identifier itemId) {
        TOOL_IDS.add(itemId);
    }

    public static boolean isTool(Item item) {
        return TOOLS.contains(item) || TOOL_IDS.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    public static void registerPlacementItem(Item item) {
        registerPlacementItem(item, true);
    }

    public static void registerPlacementItem(Item item, boolean rotatable) {
        PLACEMENT_ITEMS.add(item);
        if (rotatable) ROTATABLE_PLACEMENT_ITEMS.add(item);
        else ROTATABLE_PLACEMENT_ITEMS.remove(item);
    }

    public static void registerPlacementItem(Identifier itemId) {
        registerPlacementItem(itemId, true);
    }

    public static void registerPlacementItem(Identifier itemId, boolean rotatable) {
        PLACEMENT_ITEM_IDS.add(itemId);
        if (rotatable) ROTATABLE_PLACEMENT_ITEM_IDS.add(itemId);
        else ROTATABLE_PLACEMENT_ITEM_IDS.remove(itemId);
    }

    public static boolean hasPlacementPreview(Item item) {
        return PLACEMENT_ITEMS.contains(item)
            || PLACEMENT_ITEM_IDS.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    public static boolean rotatesPlacement(Item item) {
        return ROTATABLE_PLACEMENT_ITEMS.contains(item)
            || ROTATABLE_PLACEMENT_ITEM_IDS.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    public static List<KoperAttachmentPoint> points(BlockState state) {
        Function<BlockState, List<KoperAttachmentPoint>> provider = POINTS.get(state.getBlock());
        if (provider == null) provider = POINT_IDS.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        return provider == null ? List.of() : List.copyOf(provider.apply(state));
    }

    public static KoperAttachmentPoint closest(BlockState state, Vec3 localHit, double radius) {
        KoperAttachmentPoint best = null;
        double bestDistance = radius * radius;
        for (KoperAttachmentPoint point : points(state)) {
            double distance = point.position().distanceToSqr(localHit);
            if (distance <= bestDistance) {
                best = point;
                bestDistance = distance;
            }
        }
        return best;
    }
}
