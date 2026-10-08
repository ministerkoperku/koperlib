package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// worn items with a bedrock attachable: the attachable replaces the armor piece
@Mixin(HumanoidArmorLayer.class)
public abstract class BrZbrojaMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void koperlib$attachable(PoseStack pose, SubmitNodeCollector tasks, ItemStack stack, EquipmentSlot slot, int light,
                                     HumanoidRenderState state, CallbackInfo ci) {
        BrKlatka k = BrAktorzy.forSlot(((BrNosiciel) state).koperlib$att(), slot);
        if (k == null) return;
        Object model = ((RenderLayer<?, ?>) (Object) this).getParentModel();
        if (!(model instanceof HumanoidModel<?> hm)) return;
        BrAktorzy.renderBound(k, hm, true, pose, tasks, light);
        ci.cancel();
    }
}
