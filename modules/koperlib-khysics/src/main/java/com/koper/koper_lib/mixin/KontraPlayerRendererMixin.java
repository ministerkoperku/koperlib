package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraPlayerVisualState;
import com.koper.koper_lib.kender.KontraRideClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(AvatarRenderer.class)
public abstract class KontraPlayerRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void koper$extractKontraRideRot(Avatar player, AvatarRenderState state,
                                            float tickDelta, CallbackInfo ci) {
        KontraPlayerVisualState kontra = (KontraPlayerVisualState)state;
        kontra.koperlib$setKontraRot(null);
        Vec3 off = KontraRideClient.renderRideOffset(player, tickDelta);
        if (off.lengthSqr() > 1.0e-8) {
            kontra.koperlib$setKontraOffset(new float[]{(float)off.x, (float)off.y, (float)off.z});
        } else {
            kontra.koperlib$setKontraOffset(null);
        }
    }

    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("HEAD"))
    private void koper$applyKontraRideRot(AvatarRenderState state, PoseStack poseStack,
                                          float bodyRot, float scale, CallbackInfo ci) {
        KontraPlayerVisualState kontra = (KontraPlayerVisualState)state;
        float[] off = kontra.koperlib$getKontraOffset();
        if (off != null && off.length >= 3) poseStack.translate(off[0], off[1], off[2]);

        float[] q = kontra.koperlib$getKontraRot();
        if (q == null || q.length < 4) return;
        if (Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]) < 1.0e-5f && Math.abs(q[3] - 1.0f) < 1.0e-5f) return;
        float pivotY = state.boundingBoxHeight * 0.5f / Math.max(scale, 1.0e-5f);
        poseStack.translate(0.0f, pivotY, 0.0f);
        poseStack.translate(0.0f, -pivotY, 0.0f);
    }
}
