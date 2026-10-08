package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelReader.class)
public interface ClientKontraLevelReaderMixin {
    @Inject(method = "hasChunkAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridHasChunk(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof Level level && level.isClientSide()
                && (KenderClientState.activeGrid() != null || KenderClientState.logicalBlockStateAt(pos) != null))
            cir.setReturnValue(true);
    }
}
