package com.koper.koper_lib.api.workstation;

import com.koper.koper_lib.mixin.DisplayAccessor;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Depot-like in-world item placement backed by synced vanilla item displays, or by the block
 * entity's own renderer when it is a {@link KoperWorkstationRendered}.
 */
public final class KoperPhysicalWorkstation {
    private static final String DISPLAY_PREFIX = "koperlib_workstation_";

    private KoperPhysicalWorkstation() {}

    public static InteractionResult placeOne(Level level, BlockPos pos, Container inventory,
            KoperWorkstationLayout layout, Player player, ItemStack held, Vec3 hitLocation) {
        if (held.isEmpty()) return InteractionResult.PASS;
        int slot = nearestSlot(pos, layout, hitLocation, inventory, true);
        if (slot < 0) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        inventory.setItem(slot, held.copyWithCount(1));
        held.consume(1, player);
        changed(level, pos, inventory, layout);
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult takeOne(Level level, BlockPos pos, Container inventory,
            KoperWorkstationLayout layout, Player player, Vec3 hitLocation) {
        int slot = nearestSlot(pos, layout, hitLocation, inventory, false);
        if (slot < 0) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ItemStack removed = inventory.removeItemNoUpdate(slot);
        if (!removed.isEmpty() && !player.getInventory().add(removed)) com.koper.koper_lib.core.KoperWyrzucacz.drop(player, removed, false);
        changed(level, pos, inventory, layout);
        return InteractionResult.SUCCESS;
    }

    public static void changed(Level level, BlockPos pos, Container inventory, KoperWorkstationLayout layout) {
        if (level.getBlockEntity(pos) instanceof BlockEntity blockEntity) blockEntity.setChanged();
        if (level instanceof ServerLevel serverLevel) syncDisplays(serverLevel, pos, inventory, layout);
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }

    public static void syncDisplays(ServerLevel level, BlockPos pos, Container inventory,
            KoperWorkstationLayout layout) {
        if (inventory instanceof KoperWorkstationRendered) {
            // draws its own items; also clears displays left from before it did
            clearDisplays(level, pos);
            return;
        }
        String baseTag = baseTag(pos);
        List<Display.ItemDisplay> displays = new ArrayList<>(level.getEntitiesOfClass(Display.ItemDisplay.class,
                new AABB(pos).inflate(2), display -> display.entityTags().contains(baseTag)));
        for (int slot = 0; slot < Math.min(inventory.getContainerSize(), layout.size()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            Display.ItemDisplay display = find(displays, slotTag(pos, slot));
            if (stack.isEmpty()) {
                if (display != null) display.discard();
                continue;
            }
            if (display == null) {
                EntityType<?> displayType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("item_display"));
                var created = displayType == null ? null : displayType.create(level, EntitySpawnReason.TRIGGERED);
                if (!(created instanceof Display.ItemDisplay itemDisplay)) continue;
                display = itemDisplay;
                display.addTag(baseTag);
                display.addTag(slotTag(pos, slot));
                display.setNoGravity(true);
                level.addFreshEntity(display);
            }
            KoperWorkstationLayout.SlotPosition local = layout.slot(slot);
            display.getSlot(0).set(stack.copyWithCount(1));
            display.setPos(pos.getX() + local.x(), pos.getY() + local.y(), pos.getZ() + local.z());
            display.setYRot(local.yaw());
            display.setXRot(local.pitch());
            float scale = local.scale();
            ((DisplayAccessor) display).koperlib$setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        }
        for (Display.ItemDisplay duplicate : displays) duplicate.discard();
    }

    public static void clearDisplays(ServerLevel level, BlockPos pos) {
        String tag = baseTag(pos);
        for (Display.ItemDisplay display : level.getEntitiesOfClass(Display.ItemDisplay.class,
                new AABB(pos).inflate(2), entity -> entity.entityTags().contains(tag))) display.discard();
    }

    /** Removes displays whose backing workstation block entity no longer exists. */
    public static void clearOrphans(ServerLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Display.ItemDisplay display)) continue;
            BlockPos workstation = workstationPos(display);
            if (workstation == null || !level.isLoaded(workstation)) continue;
            if (!(level.getBlockEntity(workstation) instanceof Container)) display.discard();
        }
    }

    private static int nearestSlot(BlockPos pos, KoperWorkstationLayout layout, Vec3 hitLocation,
            Container inventory, boolean empty) {
        Vec3 hit = hitLocation.subtract(pos.getX(), pos.getY(), pos.getZ());
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        int count = Math.min(inventory.getContainerSize(), layout.size());
        for (int slot = 0; slot < count; slot++) {
            if (inventory.getItem(slot).isEmpty() != empty) continue;
            double distance = layout.slot(slot).vector().distanceToSqr(hit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        return best;
    }

    private static Display.ItemDisplay find(List<Display.ItemDisplay> displays, String tag) {
        java.util.Iterator<Display.ItemDisplay> iterator = displays.iterator();
        while (iterator.hasNext()) {
            Display.ItemDisplay display = iterator.next();
            if (!display.entityTags().contains(tag)) continue;
            iterator.remove();
            return display;
        }
        return null;
    }

    private static BlockPos workstationPos(Display.ItemDisplay display) {
        for (String tag : display.entityTags()) {
            if (!tag.startsWith(DISPLAY_PREFIX)) continue;
            try {
                return BlockPos.of(Long.parseLong(tag.substring(DISPLAY_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // Slot tags have an extra suffix; only the base tag is a packed long.
            }
        }
        return null;
    }

    private static String baseTag(BlockPos pos) { return DISPLAY_PREFIX + pos.asLong(); }
    private static String slotTag(BlockPos pos, int slot) { return baseTag(pos) + "_" + slot; }
}
