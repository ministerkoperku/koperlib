package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class ServerLevelBlockAccessMixin {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void koperlib$serverGridState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        // out at the grid anchors there is no real world, only grids. a read of a cell no grid owns
        // (a neighbour update, a stair looking at its neighbours, a random tick next to a body) used to
        // fall through to vanilla, which GENERATED the chunk: every machine grew the save for good.
        // an unloaded chunk out there is air, full stop
        if (KontraGrid.inGridRegion(pos) && !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            KontraGrid active = KontraGridContext.active();
            if (active != null && active.ownsGridPos(pos)) { cir.setReturnValue(active.getBlockState(level, pos)); return; }
            BlockState logical = KoperPhys.getLogicalBlockStateAt(level, pos);
            cir.setReturnValue(logical != null ? logical : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            return;
        }
        if (KoperPhys.REAL_WORLD_LOOKUP.get() || KoperPhys.worldWriting()) return;
        KontraGrid grid = KontraGridContext.active();
        if (grid != null) { cir.setReturnValue(grid.getBlockState(level, pos)); return; }
        BlockState logical = KoperPhys.getLogicalBlockStateAt(level, pos);
        if (logical != null) { cir.setReturnValue(logical); return; }
        if (BYPASS.get() || KoperPhys.ASSEMBLY_ACTIVE.get() || KoperPhys.TERRAIN_SCAN_ACTIVE.get()
                || KoperPhys.ENTITY_COLLISION_ACTIVE.get() || KoperPhys.WORLD_RANDOM_TICK.get()) return;
        BlockState physical = KoperPhys.getBlockStateAt(level, pos);
        if (physical == null) return;
        BYPASS.set(true);
        try {
            if (!level.getBlockState(pos).isAir()) return;
        } finally { BYPASS.set(false); }
        cir.setReturnValue(physical);
    }

    // the same for fluids: a fence, slab, trapdoor or leaves being placed on a body asks its neighbours
    // whether they are waterlogged, and that read generated the chunk out at the anchors just as well
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridRegionFluid(BlockPos pos, CallbackInfoReturnable<net.minecraft.world.level.material.FluidState> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        if (!KontraGrid.inGridRegion(pos) || level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        KontraGrid active = KontraGridContext.active();
        if (active != null && active.ownsGridPos(pos)) { cir.setReturnValue(active.getBlockState(level, pos).getFluidState()); return; }
        BlockState logical = KoperPhys.getLogicalBlockStateAt(level, pos);
        cir.setReturnValue(logical != null ? logical.getFluidState()
                : net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState());
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void koperlib$serverGridBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        if (KontraGrid.inGridRegion(pos) && !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            KontraGrid active = KontraGridContext.active();
            if (active != null && active.ownsGridPos(pos)) { cir.setReturnValue(active.getBlockEntity(level, pos)); return; }
            cir.setReturnValue(KoperPhys.getLogicalBlockEntityAt(level, pos));
            return;
        }
        if (KoperPhys.REAL_WORLD_LOOKUP.get() || KoperPhys.worldWriting()) return;
        KontraGrid grid = KontraGridContext.active();
        if (grid != null) { cir.setReturnValue(grid.getBlockEntity(level, pos)); return; }
        BlockEntity logical = KoperPhys.getLogicalBlockEntityAt(level, pos);
        if (logical != null) { cir.setReturnValue(logical); return; }
        BlockEntity physical = KoperPhys.getBlockEntityAt(level, pos);
        if (physical != null && KoperPhys.realBlockEntity(level, pos) == null)
            cir.setReturnValue(physical);
    }

    // lived on a ServerLevel mixin before — ServerLevel never declares playSound, so it silently
    // never applied and every grid-context sound (chest open, piston, furnace) played at the anchor
    @Inject(method = "playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridSound(net.minecraft.world.entity.Entity except, BlockPos pos,
                                    net.minecraft.sounds.SoundEvent sound,
                                    net.minecraft.sounds.SoundSource source, float volume, float pitch,
                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!((Object)this instanceof ServerLevel level)) return;
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        BlockPos worldPos = grid.toWorldPos(pos);
        if (worldPos != null) KontraGridContext.outside(() -> {
            level.playSound(except, worldPos, sound, source, volume, pitch);
            return null;
        });
        ci.cancel();
    }
}
