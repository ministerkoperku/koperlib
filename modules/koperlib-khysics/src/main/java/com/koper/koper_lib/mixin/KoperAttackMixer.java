package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.kender.KenderMiningClient;
import com.koper.koper_lib.network.KenderBreakPayload;
import com.koper.koper_lib.network.KenderSelfRightPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// client attacks a physics block
// sneak+attack = toss it (self-right), normal attack = break block
@Mixin(Minecraft.class)
public abstract class KoperAttackMixer {

    @Shadow public LocalPlayer player;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void koperlib$physicsAttack(CallbackInfoReturnable<Boolean> cir) {
        if (player == null) return;
        // a connector-style tool owns the attack button for its drag gesture. break here and the
        // engine you were reaching around is destroyed instead of wired. plain return, not cancel:
        // the attachment mixin still needs its turn at this same injection point.
        if (com.koper.koper_lib.api.core.KoperToolHogger.blocksMining(player.getMainHandItem())) return;
        KenderTargeting.PhysHit hit = KenderTargeting.getHit();
        if (hit == null) return;

        if (player.isShiftKeyDown()) {
            // sneak+attack always tosses regardless of gamemode
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            ClientPlayNetworking.send(new KenderSelfRightPayload(hit.kontraId()));
            cir.setReturnValue(true);
            return;
        }

        if (player.isCreative()) {
            // creative: instant break, no animation needed
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            ClientPlayNetworking.send(new KenderBreakPayload(hit.networkRef()));
            cir.setReturnValue(true);
            return;
        }
        KenderMiningClient.begin((Minecraft)(Object)this, hit);
        cir.setReturnValue(true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void koperlib$continuePhysicsAttack(boolean attackDown, CallbackInfo ci) {
        if (player != null
                && com.koper.koper_lib.api.core.KoperToolHogger.blocksMining(player.getMainHandItem()))
            return;
        if (KenderMiningClient.continueMining((Minecraft)(Object)this, attackDown))
            ci.cancel();
    }
}
