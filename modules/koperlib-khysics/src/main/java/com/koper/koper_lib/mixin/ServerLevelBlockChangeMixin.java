package com.koper.koper_lib.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;

// the ONE working setBlock hook. ServerLevel declares no setBlock at all — the old @Mixin(ServerLevel)
// guard silently never applied (require=0) and every world-space write at a kontra projection
// materialized a real block: static plates when stepped on, static targets when shot. all of it.
// MUST target 4-arg — destroyBlock calls setBlock(pos,state,flags,recursionLeft) directly,
// never goes through the 3-arg wrapper.
@Mixin(Level.class)
public abstract class ServerLevelBlockChangeMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void koperlib$gridChanged(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        var grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) {
            cir.setReturnValue(grid.setBlock(level, pos, state, flags, recursionLeft));
            return;
        }
        // no grid owns this cell out at the anchors and its chunk is not loaded: nothing real lives
        // there, and letting vanilla write would generate the chunk. returned before worldWriteIn,
        // so the RETURN hook's worldWriteOut is not owed either
        if (com.koper.koper_lib.physics.KontraGrid.inGridRegion(pos)
                && !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            cir.setReturnValue(false);
            return;
        }
        if (KoperPhys.ASSEMBLY_ACTIVE.get()) { KoperPhys.worldWriteIn(); return; }
        // plate press / arrow-lit target / anything writing at a projection cell → into the grid,
        // never a real world block
        Boolean routed = KoperPhys.routeProjectionWrite(level, pos, state, flags, recursionLeft);
        if (routed != null) { cir.setReturnValue(routed); return; }
        // vanilla is about to write and then RE-READ this cell: newState == state decides whether
        // clients ever hear about it. that read has to see the WORLD, not our projection, or the
        // compare fails and markAndNotifyBlock never runs — the block goes on the server, stays on
        // the client, collision and all. a counter because setBlock nests through neighbour updates
        KoperPhys.worldWriteIn();
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void koperlib$worldWriteDone(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof ServerLevel) KoperPhys.worldWriteOut();
    }

    // fires during ASSEMBLY too — the blocks a kontra eats become air and their section bits must
    // clear BEFORE the spawn cmd (same queue) or the body spawns inside its own ghost terrain.
    // no bookkeeping stack here: a HEAD cancel never reaches RETURN, so the old push/pop pair leaked
    // one entry per grid write and later REAL block changes got skipped by the terrain sync.
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void koperlib$terrainChanged(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (!((Object)this instanceof ServerLevel)) return;
        if (KontraGridContext.active() != null) return;
        ServerLevel level = (ServerLevel)(Object)this;
        // ghost hunter: a real-world write landing INSIDE a kontra's projection = static clone being born
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode
                && KoperPhys.getBlockStateAt(level, pos) != null) {
            var trace = new Throwable().getStackTrace();
            StringBuilder via = new StringBuilder();
            for (int i = 1; i < Math.min(trace.length, 12); i++)
                via.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
                   .append(':').append(trace[i].getLineNumber()).append(" <- ");
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] WORLD-WRITE-AT-PROJECTION pos={} state={} via {}",
                pos, state.getBlock(), via);
        }
        com.koper.koper_lib.physics.terrain.TerrainSlurper.blockChanged(level, pos, state);
        KoperPhys.staticBlockChanged(level, pos, state);
    }

    // Level.neighborShapeChanged pushes straight into the WORLD collector — grid-cell shape updates
    // must ride the kontra's own collector like everything else (Level declares this one, javap ✓)
    @Inject(method = "neighborShapeChanged", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridShape(net.minecraft.core.Direction direction, BlockPos pos, BlockPos neighborPos,
                                    BlockState neighborState, int updateFlags, int updateLimit,
                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!((Object)this instanceof ServerLevel level)) return;
        if (KoperPhys.REAL_WORLD_LOOKUP.get()) return;
        com.koper.koper_lib.physics.KontraGrid grid = KoperPhys.gridAtLogical(level, pos);
        if (grid == null) return;
        grid.routeNeighborUpdate(level, u -> u.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit));
        ci.cancel();
    }
}
