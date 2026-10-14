package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraRideClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// mobs and other players standing on a moving deck are drawn where the deck carries them this frame,
// not a few ticks behind it (see KontraRideClient.standingDeckOffset)
@Environment(EnvType.CLIENT)
@Mixin(EntityRenderer.class)
public abstract class KontraStandingRenderMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void koperlib$standOnDeck(Entity entity, EntityRenderState state, float tickDelta, CallbackInfo ci) {
        Vec3 off = KontraRideClient.standingDeckOffset(entity, tickDelta);
        if (off == Vec3.ZERO) return;
        state.x += off.x;
        state.y += off.y;
        state.z += off.z;
    }
}
