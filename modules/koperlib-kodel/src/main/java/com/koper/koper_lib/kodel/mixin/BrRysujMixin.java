package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// an entity with a bedrock actor draws its kodel model here instead of the vanilla one.
// shadow, fire and hitboxes stay the dispatcher's business, the name tag is put back by hand
@Mixin(EntityRenderDispatcher.class)
public abstract class BrRysujMixin {

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void koperlib$bedrockDraw(EntityRenderer<?, ?> renderer, EntityRenderState state, PoseStack pose,
                                      SubmitNodeCollector tasks, CameraRenderState camera, Operation<Void> original) {
        BrAktorzy.frame();
        BrKlatka k = ((BrNosiciel) state).koperlib$br();
        if (k == null || k.onVanillaModel || k.geo == null || k.geo.model == null) {
            original.call(renderer, state, pose, tasks, camera);
            // the java model is the base, the pack's extra render controllers still draw on top
            if (k != null) BrAktorzy.submitWarstwy(k, pose, tasks, state.lightCoords);
            return;
        }
        BrAktorzy.submit(k, pose, tasks, camera, state.lightCoords);
        if (state.nameTag != null) ((BrNazwaInvoker) renderer).koperlib$nameDisplay(state, pose, tasks, camera);
    }
}
