package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// makes the client see physics blocks as real blocks in the world
// CRITICAL: only expose the block at the CURRENTLY TARGETED position (set by KenderTargeting)
// if we expose ALL nearby floor(float_pos) positions: phantom blocks everywhere → double outline,
// placement broken (vanilla sees a "block" there), suffocation when walking near kontraktions
@Mixin(Level.class)
public abstract class ClientLevelAccessMixin {

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsBlockClientLookup(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        Level self = (Level)(Object)this;
        if (!self.isClientSide()) return;
        var grid = KenderClientState.activeGrid();
        if (grid != null) {
            cir.setReturnValue(KenderClientState.gridBlockState(grid, self, pos));
            return;
        }
        if (BYPASS.get() || KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get()) return;
        BlockState logical = KenderClientState.logicalBlockStateAt(pos);
        if (logical != null) { cir.setReturnValue(logical); return; }
        if (KoperPhys.ENTITY_COLLISION_ACTIVE.get() || KenderClientState.isEmpty()) return;

        // only overlay physics block state for the position KenderTargeting is actively aiming at
        // this prevents phantom blocks at rounded positions for ALL nearby blocks
        if (!KenderTargeting.isTargeting()) return;
        Minecraft mc = Minecraft.getInstance();
        // main thread ONLY. this overlay is for mining feedback (crack stage, dig speed), but the
        // chunk mesher runs on worker threads through the same getBlockState. break a block next to a
        // kontra, the cell turns air, the guard below stops guarding, and the mesher bakes the
        // projected block right into the chunk mesh — a client-only ghost that outlives everything
        if (!mc.isSameThread()) return;
        if (!(mc.hitResult instanceof BlockHitResult bhr)) return;
        if (!bhr.getBlockPos().equals(pos)) return;

        // return the EXACT block being aimed at — wbp rounding can re-derive a neighbour, making dirt
        // mine at stone speed or a cell act like air. fall back to the rounded lookup if unavailable.
        BlockState phys = KenderTargeting.targetedState();
        if (phys == null) phys = KenderClientState.getClientBlockStateAt(pos);
        if (phys == null) return;

        BYPASS.set(true);
        try {
            if (!self.getBlockState(pos).isAir()) return;
        } finally {
            BYPASS.set(false);
        }
        cir.setReturnValue(phys);
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsBlockEntityClientLookup(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        Level self = (Level)(Object)this;
        if (!self.isClientSide()) return;
        var grid = KenderClientState.activeGrid();
        if (grid != null) { cir.setReturnValue(KenderClientState.gridBlockEntity(grid, self, pos)); return; }
        BlockEntity logical = KenderClientState.logicalBlockEntityAt(pos);
        if (logical != null) { cir.setReturnValue(logical); return; }
        if (BYPASS.get() || KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get()) return;
        BYPASS.set(true);
        try {
            if (self.getBlockEntity(pos) != null) return;
        } finally {
            BYPASS.set(false);
        }
        var hit = KenderTargeting.getHit();
        Minecraft mc = Minecraft.getInstance();
        if (hit != null && mc.hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            var targetedGrid = KenderClientState.getById(hit.kontraId());
            if (targetedGrid != null) {
                BlockEntity targeted = targetedGrid.blockEntities.get(
                    new BlockPos(hit.hitLocalX(), hit.hitLocalY(), hit.hitLocalZ()));
                if (targeted != null) { cir.setReturnValue(targeted); return; }
            }
        }
        BlockEntity physical = KenderClientState.physicalBlockEntityAt(pos);
        if (physical != null) cir.setReturnValue(physical);
    }

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsGridLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (KenderClientState.activeGrid() != null || KenderClientState.logicalBlockStateAt(pos) != null)
            cir.setReturnValue(true);
    }

}
