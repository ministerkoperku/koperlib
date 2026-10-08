package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SignalGetter.class)
public interface KontraSignalMixin {
    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        var grid = KontraGridContext.active();
        if (!((Object)this instanceof ServerLevel level)) return;
        if (grid != null) {
            if (!grid.hasLocalBlock(pos)) cir.setReturnValue(grid.externalSignal(level, pos, direction, false));
        }
    }

    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void koperlib$physicalSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        if (KontraGridContext.active() != null || !((Object)this instanceof ServerLevel level)) return;
        Integer signal = KoperPhys.physicalSignal(level, pos, direction, false);
        if (signal != null && signal > cir.getReturnValue()) cir.setReturnValue(signal);
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridDirectSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        var grid = KontraGridContext.active();
        if (!((Object)this instanceof ServerLevel level)) return;
        if (grid != null) {
            if (!grid.hasLocalBlock(pos)) cir.setReturnValue(grid.externalSignal(level, pos, direction, true));
        }
    }

    @Inject(method = "getDirectSignal", at = @At("RETURN"), cancellable = true)
    private void koperlib$physicalDirectSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        if (KontraGridContext.active() != null || !((Object)this instanceof ServerLevel level)) return;
        Integer signal = KoperPhys.physicalSignal(level, pos, direction, true);
        if (signal != null && signal > cir.getReturnValue()) cir.setReturnValue(signal);
    }

    @Inject(method = "getBestNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void koperlib$overlappingBestSignal(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (KontraGridContext.active() != null || !((Object)this instanceof ServerLevel level)) return;
        int signal = KoperPhys.physicalSignalAt(level, pos, false);
        if (signal > cir.getReturnValue()) cir.setReturnValue(signal);
    }

    @Inject(method = "hasNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void koperlib$overlappingHasSignal(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || KontraGridContext.active() != null
                || !((Object)this instanceof ServerLevel level)) return;
        if (KoperPhys.physicalSignalAt(level, pos, false) > 0) cir.setReturnValue(true);
    }

    @Inject(method = "getDirectSignalTo", at = @At("RETURN"), cancellable = true)
    private void koperlib$overlappingDirectSignal(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (KontraGridContext.active() != null || !((Object)this instanceof ServerLevel level)) return;
        int signal = KoperPhys.physicalSignalAt(level, pos, true);
        if (signal > cir.getReturnValue()) cir.setReturnValue(signal);
    }

    @Inject(method = "getControlInputSignal", at = @At("RETURN"), cancellable = true)
    private void koperlib$overlappingControlSignal(BlockPos pos, Direction direction, boolean diodeOnly,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (diodeOnly || KontraGridContext.active() != null || !((Object)this instanceof ServerLevel level)) return;
        Integer signal = KoperPhys.physicalSignal(level, pos, direction, true);
        if (signal != null && signal > cir.getReturnValue()) cir.setReturnValue(signal);
    }
}
