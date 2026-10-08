package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelTicks.class)
public abstract class KontraLevelTicksMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void koperlib$scheduleGridTick(ScheduledTick<T> tick, CallbackInfo ci) {
        var level = KoperPhys.levelForTicks(this);
        if (level == null) return;
        var grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogical(level, tick.pos());
        if (grid == null) return;
        grid.scheduleFromWorldTick(tick.pos(), tick.type(), tick.triggerTick(), tick.priority(), level.getGameTime());
        ci.cancel();
    }

    @Inject(method = "hasScheduledTick", at = @At("HEAD"), cancellable = true)
    private void koperlib$hasGridTick(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        var level = KoperPhys.levelForTicks(this);
        if (level == null) return;
        var grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) cir.setReturnValue(grid.hasScheduledTick(pos, type));
    }

    @Inject(method = "willTickThisTick", at = @At("HEAD"), cancellable = true)
    private void koperlib$willGridTick(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        var level = KoperPhys.levelForTicks(this);
        if (level == null) return;
        var grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogical(level, pos);
        if (grid != null) cir.setReturnValue(grid.willTickThisTick(pos, type));
    }
}
