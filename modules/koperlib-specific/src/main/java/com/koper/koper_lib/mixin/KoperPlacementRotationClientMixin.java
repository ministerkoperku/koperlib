package com.koper.koper_lib.mixin;

import com.koper.koper_lib.api.attachment.KoperAttachments;
import com.koper.koper_lib.api.attachment.KoperPlacementClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Environment(EnvType.CLIENT)
@Mixin(BlockItem.class)
public abstract class KoperPlacementRotationClientMixin {

    @ModifyReturnValue(method = "getPlacementState", at = @At("RETURN"))
    private BlockState koperlib$rotateClientPlacement(BlockState state, BlockPlaceContext context) {
        BlockItem item = (BlockItem)(Object)this;
        if (state == null || !context.getLevel().isClientSide()
                || !KoperAttachments.rotatesPlacement(item)) return state;
        return KoperPlacementClient.apply(state);
    }
}
