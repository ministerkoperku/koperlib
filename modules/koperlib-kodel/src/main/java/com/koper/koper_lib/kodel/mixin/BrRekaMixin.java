package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the first person arm: bedrock first person animations move it, and an attachable in that
// hand is drawn bound to it, same binding rules as third person
@Mixin(AvatarRenderer.class)
public abstract class BrRekaMixin {

    @Inject(method = "renderHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/UvMapping;)V"))
    private void koperlib$bedrockArm(PoseStack pose, SubmitNodeCollector tasks, int light, Identifier skin, ModelPart arm,
                                     boolean sleeve, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var model = ((AvatarRenderer<?>) (Object) this).getModel();
        boolean right = arm == model.rightArm;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        BrKlatka player = BrAktorzy.firstPersonPlayer(mc.player, pt);
        if (player != null && player.onVanillaModel) BrAktorzy.onArm(player, arm, right);
        BrAktorzy.Reka reka = BrAktorzy.REKA.get();
        if (reka != null && reka.right() == right) BrAktorzy.renderBound(reka.attachable(), model, right, pose, tasks, light, arm);
    }
}
