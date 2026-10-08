package com.koper.koper_lib.elpe.mixin;

import com.koper.koper_lib.elpe.ElpeLevelBoss;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// non null return = the block really changed. elpe flips one bit and wakes whatever sat there
@Mixin(LevelChunk.class)
public abstract class ElpeChunkBlockMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void koperElpeBlockFlip(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        if (((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            ElpeLevelBoss.blockChanged(level, pos, state);
        }
    }
}
