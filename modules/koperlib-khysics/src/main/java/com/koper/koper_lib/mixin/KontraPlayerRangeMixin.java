package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class KontraPlayerRangeMixin {
    @Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$logicalBlockRange(BlockPos pos, double extraRange, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player)(Object)this;
        if (!(self.level() instanceof ServerLevel level)) return;
        BlockPos physical = KoperPhys.logicalToWorld(level, pos);
        if (physical != null) cir.setReturnValue(self.isWithinBlockInteractionRange(physical, extraRange));
    }
}
