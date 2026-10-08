package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockHudCodes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// a bedrock pack's hud codes sent as titles never reach the screen, see BedrockHudCodes
@Environment(EnvType.CLIENT)
@Mixin(Hud.class)
public abstract class BedrockHudTitleMixin {

    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void koperlib$hudTitle(Component title, CallbackInfo ci) {
        if (BedrockHudCodes.swallowed(title.getString())) ci.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void koperlib$hudSubtitle(Component subtitle, CallbackInfo ci) {
        if (BedrockHudCodes.swallowed(subtitle.getString())) ci.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void koperlib$hudActionbar(Component message, boolean animate, CallbackInfo ci) {
        if (BedrockHudCodes.swallowed(message.getString())) ci.cancel();
    }
}
