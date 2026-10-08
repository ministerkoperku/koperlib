package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraSeat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// a kontra seat decides when sneak means "get off" — see KontraSeat.sneakToLeave
@Mixin(Player.class)
public abstract class KontraSeatSneakMixin {

    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true, require = 0)
    private void koper$seatSaysSo(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player)(Object)this;
        if (self.getVehicle() instanceof KontraSeat seat) cir.setReturnValue(seat.letsGoOf(self));
    }
}
