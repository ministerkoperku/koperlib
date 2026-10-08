package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.VanillaEntityKender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// replaces only the main vanilla body; armor, held items, leashes, shadows and nameplates keep their normal layers.
@Mixin(LivingEntityRenderer.class)
public abstract class KenderLivingEntityMixin {
    @Shadow public abstract Identifier getTextureLocation(LivingEntityRenderState state);
    @Unique private boolean koperlib$gpuBody;

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void koperlib$beginEntity(CallbackInfo ci) {
        koperlib$gpuBody = false;
    }

    @Redirect(method = "submit", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"), require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void koperlib$entityBody(SubmitNodeCollector collector, Model<?> rawModel, Object rawState,
                                     PoseStack poseStack, RenderType renderType, int light, int overlay, int tint,
                                     UvMapping uvMapping, int outlineColor) {
        // 26.3 submits the crumbling overlay on its own (submitCrumblingOverlay), so the body never carries one
        if (uvMapping == null && rawModel instanceof EntityModel<?> entityModel
                && rawState instanceof LivingEntityRenderState state
                && VanillaEntityKender.submit(entityModel, state, poseStack, collector,
                    getTextureLocation(state), renderType, light, overlay, tint, outlineColor, null)) {
            koperlib$gpuBody = true;
            return;
        }
        collector.submitModel((Model)rawModel, rawState, poseStack, renderType, light, overlay, tint,
            uvMapping, outlineColor);
    }

    // Vanilla calls this again for layers. The GPU body capture already left the model in the same pose.
    // (the call is EntityModel.setupAnim; this used to name Model and never matched, require = 0 hid it)
    @Redirect(method = "submit", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V"), require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void koperlib$reuseBodyPose(EntityModel model, Object state) {
        if (!koperlib$gpuBody) model.setupAnim(state);
    }

    @Inject(method = "submit", at = @At("TAIL"), require = 0)
    private void koperlib$endEntity(CallbackInfo ci) {
        koperlib$gpuBody = false;
    }
}
