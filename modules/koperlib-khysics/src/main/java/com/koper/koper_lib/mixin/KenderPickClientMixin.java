package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.network.KenderPickPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class KenderPickClientMixin {

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$pickMovingBlock(CallbackInfo ci) {
        KenderTargeting.PhysHit hit = KenderTargeting.getHit();
        if (hit == null) return;
        ClientPlayNetworking.send(new KenderPickPayload(
            hit.networkRef(), ((Minecraft) (Object) this).hasControlDown()));
        ci.cancel();
    }
}
