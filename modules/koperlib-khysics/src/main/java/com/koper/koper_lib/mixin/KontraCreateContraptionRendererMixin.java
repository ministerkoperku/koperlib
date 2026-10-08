package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.client.content.contraptions.render.ContraptionEntityRenderer;
import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ContraptionEntityRenderer.class)
public abstract class KontraCreateContraptionRendererMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lcom/zurrtum/create/client/content/contraptions/render/ContraptionEntityRenderer;createTransform(Lcom/zurrtum/create/content/contraptions/AbstractContraptionEntity;F)Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"))
    private PoseStack.Pose koperlib$renderInsideParentFrame(ContraptionEntityRenderer<?, ?> renderer,
            AbstractContraptionEntity entity, float partialTick, Operation<PoseStack.Pose> original) {
        PoseStack.Pose pose = original.call(renderer, entity, partialTick);
        float[] q = KoperCreateContraptions.parentRotation(entity);
        if (q == null) return pose;
        Quaternionf parent = new Quaternionf(q[0], q[1], q[2], q[3]);
        pose.pose().rotateLocal(parent);
        pose.normal().rotateLocal(parent);
        return pose;
    }
}
