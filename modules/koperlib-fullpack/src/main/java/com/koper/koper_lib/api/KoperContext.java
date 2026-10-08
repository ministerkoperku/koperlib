package com.koper.koper_lib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// shoved into every @KoperHook call — one object beats 6 params
public record KoperContext(
    ServerPlayer player,
    LivingEntity entity,
    LivingEntity target,
    Level world,
    ItemStack stack,
    BlockPos pos
) {
    public static KoperContext ofUse(ServerPlayer player, ItemStack stack, BlockPos pos) {
        return new KoperContext(player, player, null, player.level(), stack, pos);
    }

    public static KoperContext ofHit(ServerPlayer player, LivingEntity target, ItemStack stack) {
        return new KoperContext(player, player, target, player.level(), stack, player.blockPosition());
    }

    public static KoperContext ofEquip(ServerPlayer player, ItemStack stack) {
        return new KoperContext(player, player, null, player.level(), stack, player.blockPosition());
    }

    public static KoperContext ofEntity(LivingEntity entity) {
        return new KoperContext(null, entity, null, entity.level(), ItemStack.EMPTY, entity.blockPosition());
    }

    public static KoperContext ofEntityInteract(net.minecraft.world.entity.player.Player player, LivingEntity entity) {
        return new KoperContext(
            player instanceof ServerPlayer sp ? sp : null,
            entity, null, entity.level(),
            player.getMainHandItem(), entity.blockPosition()
        );
    }

    public static KoperContext ofBlockUse(net.minecraft.world.entity.player.Player player, BlockPos pos) {
        return new KoperContext(
            player instanceof ServerPlayer sp ? sp : null,
            null, null, player.level(),
            player.getMainHandItem(), pos
        );
    }
}
