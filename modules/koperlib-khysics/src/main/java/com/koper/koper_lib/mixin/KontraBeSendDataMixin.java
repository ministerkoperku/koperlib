package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// create sendData() = chunkSource.blockChanged(pos). for grid positions there is no tracked chunk
// so the BE tag never reached clients — speed changes stayed server-only until some block update
// resynced everything (that was the "place any block and it wakes up" mystery from the bugopis)
@Mixin(ServerChunkCache.class)
public abstract class KontraBeSendDataMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridBeSendData(BlockPos pos, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogicalExact(level, pos);
        if (grid == null || !grid.hasLocalBlock(pos)) return;
        grid.syncBlockEntity(level, pos);
        ci.cancel();
    }
}
