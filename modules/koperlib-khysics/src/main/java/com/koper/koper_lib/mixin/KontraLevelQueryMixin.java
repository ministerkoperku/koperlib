package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import com.koper.koper_lib.physics.KoperPhys;

@Mixin(Level.class)
public abstract class KontraLevelQueryMixin {
    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (KontraGridContext.active() != null
                // NOT the exact one: this only answers "that area exists", and narrowing it was
                // never part of the ghost block fix. the three below are the ones that cancel
                // vanilla bookkeeping and therefore decide whether the client hears about a change
                || (Object)this instanceof ServerLevel level && KoperPhys.gridAtLogical(level, pos) != null)
            cir.setReturnValue(true);
    }

    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridBlockEntityChanged(BlockPos pos, CallbackInfo ci) {
        // The BE object itself is persisted with the kontra. There is no real chunk at the logical anchor.
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogicalExact(level, pos);
        if (grid != null && (Object)this instanceof ServerLevel level) {
            KoperPhys.gridBlockEntityChanged(level, grid, pos);
            ci.cancel();
        } else if ((Object)this instanceof ServerLevel level) {
            // a block entity on a body whose cell is not indexed (just built, being rebuilt, or left
            // over from a body that is gone) used to fall through to vanilla, which looks the chunk up
            // and so GENERATES it at the grid anchor. every machine ever built left real chunks out
            // there, saved forever. with the chunk not loaded there is nothing to mark dirty anyway
            if (com.koper.koper_lib.physics.KontraGrid.inGridRegion(pos)
                    && !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                ci.cancel();
                return;
            }
            KoperPhys.staticBlockChanged(level, pos, KoperPhys.realBlockState(level, pos));
        }
    }

    // BlockEntity.setChanged also tells comparators around it, which reads the four neighbours. for a
    // block entity on a body those neighbours are grid-anchor cells in no real chunk, and reading them
    // generated the chunk: the second half of the leak above. nothing real sits there to be told
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridOutputSignal(BlockPos pos, net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        if ((Object)this instanceof ServerLevel level
                && com.koper.koper_lib.physics.KontraGrid.inGridRegion(pos)
                && !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
            ci.cancel();
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void koperlib$setGridBlockEntity(BlockEntity be, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogicalExact(level, be.getBlockPos());
        if (grid != null && (Object)this instanceof ServerLevel level) {
            grid.setBlockEntity(level, be);
            ci.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void koperlib$removeGridBlockEntity(BlockPos pos, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogicalExact(level, pos);
        if (grid != null) {
            grid.removeBlockEntity(pos);
            ci.cancel();
        }
    }
}
