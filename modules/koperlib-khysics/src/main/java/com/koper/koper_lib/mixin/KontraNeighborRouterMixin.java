package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ServerLevel overrides ALL four neighbor-update methods and shoves them straight into its private
// CollectingNeighborUpdater — never calls the Level bodies. so the old router on @Mixin(Level) was
// stone dead on the server (javap-proven, the r8 dead-mixin family strikes AGAIN). grid updates
// landed in the WORLD collector where they could drain inside anybody's cascade with no grid ctx,
// and world-space pokes at projections ran wire logic against the real (empty/rotated) world.
// anchor-region traffic now goes to the kontra's own collector, always entered under grid ctx.
@Mixin(ServerLevel.class)
public abstract class KontraNeighborRouterMixin {

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void koperlib$gridFanout(BlockPos pos, Block block, Orientation orientation, CallbackInfo ci) {
        if (KoperPhys.REAL_WORLD_LOOKUP.get()) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGrid grid = KoperPhys.gridAtLogical(level, pos);
        if (grid == null) return;
        grid.routeNeighborUpdate(level, u -> u.updateNeighborsAtExceptFromFacing(pos, block, null, orientation));
        ci.cancel();
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridFanoutExcept(BlockPos pos, Block block, Direction skip, Orientation orientation,
                                           CallbackInfo ci) {
        if (KoperPhys.REAL_WORLD_LOOKUP.get()) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGrid grid = KoperPhys.gridAtLogical(level, pos);
        if (grid == null) return;
        grid.routeNeighborUpdate(level, u -> u.updateNeighborsAtExceptFromFacing(pos, block, skip, orientation));
        ci.cancel();
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void koperlib$gridPoke(BlockPos pos, Block block, Orientation orientation, CallbackInfo ci) {
        if (KoperPhys.REAL_WORLD_LOOKUP.get()) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGrid grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) {
            grid.routeNeighborUpdate(level, u -> u.neighborChanged(pos, block, orientation));
            ci.cancel();
            return;
        }
        koperlib$projection(level, pos, block, ci);
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void koperlib$gridPokeFull(BlockState state, BlockPos pos, Block block, Orientation orientation,
                                       boolean movedByPiston, CallbackInfo ci) {
        if (KoperPhys.REAL_WORLD_LOOKUP.get()) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGrid grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) {
            grid.routeNeighborUpdate(level, u -> u.neighborChanged(state, pos, block, orientation, movedByPiston));
            ci.cancel();
            return;
        }
        koperlib$projection(level, pos, block, ci);
    }

    // vanilla terrain sim must never see kontra projections — grass under a parked kontra was dying
    // to dirt en masse (SpreadingSnowyBlock.randomTick read the overlay as "covered by solid")
    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void koperlib$realTickChunkIn(net.minecraft.world.level.chunk.LevelChunk chunk, int randomTickSpeed,
                                          CallbackInfo ci) {
        KoperPhys.WORLD_RANDOM_TICK.set(true);
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void koperlib$realTickChunkOut(net.minecraft.world.level.chunk.LevelChunk chunk, int randomTickSpeed,
                                           CallbackInfo ci) {
        KoperPhys.WORLD_RANDOM_TICK.set(false);
    }

    // world-context poke landing on a kontra's projection cell: survival logic would run against the
    // real world (rotated deck's world-below = air → wire spits an item) — send it back to grid space
    @Unique
    private void koperlib$projection(ServerLevel level, BlockPos pos, Block source, CallbackInfo ci) {
        if (KontraGridContext.active() != null) return;
        if (KoperPhys.getBlockStateAt(level, pos) == null) return;
        if (!KoperPhys.realBlockState(level, pos).isAir()) return;
        if (KoperPhys.redirectProjectionNeighbor(level, pos, source)) ci.cancel();
    }
}
