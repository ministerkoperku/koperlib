package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderEntityBatch;
import com.koper.koper_lib.kender.VanillaEntityKender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Once the body is on Kender, ordinary model layers can join the same ordered GPU batches.
@Mixin(SubmitNodeCollection.class)
public abstract class KenderModelLayerMixin {
    @Inject(method = "submitModel", at = @At("HEAD"), cancellable = true, require = 1)
    private <S> void koperlib$entityLayer(Model<? super S> model, S state, PoseStack poseStack,
                                          RenderType renderType, int light, int overlay, int tint,
                                          UvMapping uvMapping, int outlineColor,
                                          CallbackInfo ci) {
        // crumbling goes through submitCrumblingOverlay in 26.3, it never reaches submitModel
        if (uvMapping != null || overlay != OverlayTexture.NO_OVERLAY || outlineColor != 0
                || !KenderEntityBatch.supportedLayerType(renderType)) return;
        var collector = (SubmitNodeCollection)(Object)this;
        int requestedOrder = KenderEntityBatch.worldOrder(collector);
        if (requestedOrder < 0 || requestedOrder > 15) return;

        Object binding = ((RenderSetupAccessor)(Object)((RenderTypeAccessor)(Object)renderType)
            .koperlib$state()).koperlib$textures().get("Sampler0");
        if (!(binding instanceof RenderTextureBindingAccessor textureBinding)) return;
        Identifier texture = textureBinding.koperlib$location();
        Matrix4f uv = ((RenderSetupAccessor)(Object)((RenderTypeAccessor)(Object)renderType)
            .koperlib$state()).koperlib$textureTransform().createMatrix();
        if (!KenderEntityBatch.hasGpuState(state)) {
            if (requestedOrder == 0 && VanillaEntityKender.submitModel((Model)model, state, poseStack,
                    collector, texture, renderType, light, overlay, tint, outlineColor, null)) ci.cancel();
            return;
        }

        int order = KenderEntityBatch.claimLayerOrder(state, requestedOrder);
        if (order <= 15 && VanillaEntityKender.submitLayer((Model)model, state, poseStack,
                collector, texture, renderType, light, tint, order, uv.m30(), uv.m31())) ci.cancel();
    }
}
