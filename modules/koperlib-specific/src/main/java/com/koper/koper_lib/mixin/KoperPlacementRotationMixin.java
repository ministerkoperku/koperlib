package com.koper.koper_lib.mixin;

import com.koper.koper_lib.api.attachment.KoperAttachments;
import com.koper.koper_lib.api.attachment.KoperPlacementRotation;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
public abstract class KoperPlacementRotationMixin {

    @ModifyReturnValue(method = "getPlacementState", at = @At("RETURN"))
    private BlockState koperlib$rotateServerPlacement(BlockState state, BlockPlaceContext context) {
        BlockItem item = (BlockItem)(Object)this;
        if (state == null || !KoperAttachments.rotatesPlacement(item)
                || !(context.getPlayer() instanceof ServerPlayer player)) return state;
        return KoperPlacementRotation.apply(state, KoperPlacementRotation.get(player));
    }
}
