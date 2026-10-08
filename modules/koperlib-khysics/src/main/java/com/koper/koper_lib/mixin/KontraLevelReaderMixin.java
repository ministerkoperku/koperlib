package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelReader.class)
public interface KontraLevelReaderMixin {
    @Inject(method = "hasChunkAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridHasChunk(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof ServerLevel level
                && (KontraGridContext.active() != null || KoperPhys.gridAtLogical(level, pos) != null))
            cir.setReturnValue(true);
    }
}
