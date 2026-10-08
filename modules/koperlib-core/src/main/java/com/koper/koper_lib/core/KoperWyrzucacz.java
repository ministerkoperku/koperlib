package com.koper.koper_lib.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// drops a stack at the players feet like 26.2 did. 26.3's drop() swings the arm too which is dumb
// for "inventory full, rest goes on the floor" so we spawn the item ourselves
public final class KoperWyrzucacz {
    private KoperWyrzucacz() {}

    public static ItemEntity drop(Player player, ItemStack stack, boolean thrownFromHand) {
        if (stack.isEmpty() || player.level().isClientSide()) return null;
        ItemEntity entity = player.createItemStackToDrop(stack, false, thrownFromHand);
        if (entity == null) return null;
        player.level().addFreshEntity(entity);
        if (thrownFromHand && player instanceof ServerPlayer sp && !entity.getItem().isEmpty()) {
            sp.awardStat(Stats.ITEM_DROPPED.get(entity.getItem().getItem()), stack.getCount());
            sp.awardStat(Stats.DROP);
        }
        return entity;
    }
}
