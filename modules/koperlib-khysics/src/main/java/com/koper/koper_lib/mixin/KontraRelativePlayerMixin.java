package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraMotionClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class KontraRelativePlayerMixin {
    @Inject(method="sendPosition",at=@At("HEAD"),cancellable=true)
    private void koper$relativePosition(CallbackInfo ci) {
        if (KontraMotionClient.send((LocalPlayer)(Object)this)) ci.cancel();
    }
}
