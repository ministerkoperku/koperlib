package com.koper.koper_lib.mixin;

import com.koper.koper_lib.api.attachment.KoperAttachmentInputClient;
import com.koper.koper_lib.api.attachment.KoperAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Specific-owned attachment tool click; no Khysics classes are linked here. */
@Mixin(Minecraft.class)
public abstract class KoperAttachmentAttackMixin {
    @Shadow public LocalPlayer player;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void koperlib$specificAttachmentAttack(CallbackInfoReturnable<Boolean> cir) {
        if (player == null || !KoperAttachments.isTool(player.getMainHandItem().getItem())) return;
        KoperAttachmentInputClient.firePress((Minecraft)(Object)this);
        cir.setReturnValue(true);
    }

    // connecting is a DRAG and holding the button is vanilla's "keep mining" — without this the
    // connector chewed through the engine you were reaching around to wire up
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void koperlib$specificAttachmentContinue(boolean attackDown, CallbackInfo ci) {
        if (player == null || !KoperAttachments.isTool(player.getMainHandItem().getItem())) return;
        Minecraft minecraft = (Minecraft)(Object)this;
        if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock(); // kill progress from before the swap
        ci.cancel();
    }
}
