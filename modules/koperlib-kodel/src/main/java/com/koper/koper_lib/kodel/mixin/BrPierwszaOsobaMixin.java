package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// first person: an item with a bedrock attachable is drawn the way bedrock does it, on the arm.
// the arm is drawn like an empty hand and the attachable rides it (BrRekaMixin)
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class BrPierwszaOsobaMixin {

    @Shadow
    private void renderPlayerArm(PoseStack pose, SubmitNodeCollector tasks, int light, float equip, float swing, HumanoidArm arm,
                                 PlayerRenderState playerState) {
        throw new AssertionError();
    }

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockFirstPerson(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float frame,
                                            float xRot, InteractionHand hand, float swing, ItemStack stack, float equip,
                                            PoseStack pose, SubmitNodeCollector tasks, int light, CallbackInfo ci) {
        if (state.isScoping) return;
        AvatarRenderState avatar = playerState.avatarRenderState;
        // first person always draws the local player, the render state only carries what vanilla needs
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (avatar == null || player == null) return;
        boolean main = hand == InteractionHand.MAIN_HAND;
        BrKlatka att = stack.isEmpty() ? null : BrAktorzy.firstPersonAttachable(player, main, frame);
        HumanoidArm arm = main ? avatar.mainArm : avatar.mainArm.getOpposite();
        // a pack that animates the player: its own first person, arms and attachables from the model.
        // a held item without an attachable stays java's
        BrKlatka gracz = BrAktorzy.firstPersonPlayer(player, frame);
        if (gracz != null && gracz.onVanillaModel && (att != null || stack.isEmpty())) {
            var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(avatar);
            BrAktorzy.pierwszaOsoba(gracz, att, renderer.getModel(), avatar.skin.body().texturePath(),
                arm == HumanoidArm.RIGHT, player.getEyeHeight(), player.getXRot(frame),
                gracz.bodyYaw - player.getYRot(frame), main, pose, tasks, light);
            ci.cancel();
            return;
        }
        if (att == null) return;
        BrAktorzy.REKA.set(new BrAktorzy.Reka(att, arm == HumanoidArm.RIGHT));
        try {
            pose.pushPose();
            renderPlayerArm(pose, tasks, light, equip, swing, arm, playerState);
            pose.popPose();
        } finally {
            BrAktorzy.REKA.remove();
        }
        ci.cancel();
    }
}
