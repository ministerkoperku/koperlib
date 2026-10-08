package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// catches EVERY client-side block change (place/break/update) and refreshes the model block index
// without this a koperblock only rendered after a chunk reload (CHUNK_LOAD), not right after placing
@Mixin(LevelChunk.class)
public class KodelBlockUpdateMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void koperlib$kodelIndex(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel().isClientSide()) {
            KodelBlockRenderer.onBlockChanged(pos, state);
        }
    }
}
