package com.koper.koper_lib.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// MovingBlockFeatureRenderer hardcodes cull=false when creating ModelBlockRenderer
// that means shouldRenderFace() never fires → all 6 faces rendered per block always
// flipping to true makes MC use BlockAndTintGetter.getBlockState() for neighbor queries
// vanilla piston blocks: getBlockState() returns AIR for all neighbors → no faces culled (same as before)
// KontraMovingBlockState: getBlockState() returns actual adjacent kontraktion blocks → shared faces culled
@Mixin(targets = "net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer")
public abstract class MovingBlockCullMixin {

    @ModifyArg(
        method = "buildGroup",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;<init>(ZZLnet/minecraft/client/color/block/BlockColors;)V"),
        index = 1,
        require = 0
    )
    private boolean koper$enableCull(boolean cull) {
        return true;
    }
}
