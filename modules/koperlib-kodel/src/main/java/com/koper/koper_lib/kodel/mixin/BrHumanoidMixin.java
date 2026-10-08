package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// packs that animate bedrock's own humanoid (players mostly): the java model gets the offsets
@Mixin(HumanoidModel.class)
public abstract class BrHumanoidMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void koperlib$bedrockOffsets(HumanoidRenderState state, CallbackInfo ci) {
        BrKlatka k = ((BrNosiciel) state).koperlib$br();
        if (k != null && k.onVanillaModel) BrAktorzy.onHumanoid(k, (HumanoidModel<?>) (Object) this);
    }
}
