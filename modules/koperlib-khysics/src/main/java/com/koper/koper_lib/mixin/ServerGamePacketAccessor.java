package com.koper.koper_lib.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketAccessor {

    @Invoker("isEntityCollidingWithAnythingNew")
    boolean koper$collidingWithAnythingNew(LevelReader level, Entity entity, AABB oldAABB,
                                           double newX, double newY, double newZ);

    @Invoker("tryPickItem")
    void koper$tryPickItem(ItemStack stack);

    @Invoker("addBlockDataToItem")
    static void koper$addBlockDataToItem(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack) {
        throw new AssertionError();
    }
}
