package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.network.KenderBreakPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// survival mining fallback — KoperAttackMixer handles creative (instant), this handles survival
// fires when client-side mining finishes (destroyBlock on MultiPlayerGameMode)
// sends KenderBreakPayload directly so server doesn't need the fragile position-lookup path
// ServerBreakMixin still runs as backup but double-break is safe (second call finds no block)
@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class KoperSurvivalBreakMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$survivalPhysicsBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.isCreative()) return; // creative handled by KoperAttackMixer already

        KenderTargeting.PhysHit hit = KenderTargeting.getHit();
        if (hit == null) return;

        ClientPlayNetworking.send(new KenderBreakPayload(hit.networkRef()));
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);
        // return true so vanilla clears mining progress — block is already AIR in real world
        cir.setReturnValue(true);
    }
}
