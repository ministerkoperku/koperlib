package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// a held item with a bedrock attachable draws the attachable, bound to the arm, not the item model
@Mixin(ItemInHandLayer.class)
public abstract class BrReceMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void koperlib$attachable(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
                                     PoseStack pose, SubmitNodeCollector tasks, int light, CallbackInfo ci) {
        BrKlatka[] att = ((BrNosiciel) state).koperlib$att();
        if (att == null) return;
        EquipmentSlot slot = arm == state.mainArm ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        BrKlatka k = BrAktorzy.forSlot(att, slot);
        if (k == null) return;
        Object model = ((RenderLayer<?, ?>) (Object) this).getParentModel();
        if (!(model instanceof HumanoidModel<?> hm)) return;
        BrAktorzy.renderBound(k, hm, arm == HumanoidArm.RIGHT, pose, tasks, light);
        ci.cancel();
    }
}
