package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScheduledTickAccess.class)
public interface KontraScheduledTickMixin {
    @Inject(method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$scheduleBlock(BlockPos pos, Block block, int delay, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) { grid.schedule(pos, block, delay); ci.cancel(); }
    }

    @Inject(method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;ILnet/minecraft/world/ticks/TickPriority;)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$scheduleBlockPriority(BlockPos pos, Block block, int delay, TickPriority priority, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) { grid.schedule(pos, block, delay, priority); ci.cancel(); }
    }

    @Inject(method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;I)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$scheduleFluid(BlockPos pos, Fluid fluid, int delay, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) { grid.schedule(pos, fluid, delay); ci.cancel(); }
    }

    @Inject(method = "scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;ILnet/minecraft/world/ticks/TickPriority;)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$scheduleFluidPriority(BlockPos pos, Fluid fluid, int delay, TickPriority priority, CallbackInfo ci) {
        var grid = KontraGridContext.active();
        if (grid == null && (Object)this instanceof ServerLevel level)
            grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) { grid.schedule(pos, fluid, delay, priority); ci.cancel(); }
    }
}
