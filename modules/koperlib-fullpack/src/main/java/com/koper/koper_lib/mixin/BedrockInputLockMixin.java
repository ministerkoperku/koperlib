package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockKamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bedrock's camera input permission: the mouse stops turning the player
@Environment(EnvType.CLIENT)
@Mixin(MouseHandler.class)
public abstract class BedrockInputLockMixin {

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void koperlib$cameraLocked(double mousea, CallbackInfo ci) {
        if (BedrockKamera.cameraLocked()) ci.cancel();
    }
}
