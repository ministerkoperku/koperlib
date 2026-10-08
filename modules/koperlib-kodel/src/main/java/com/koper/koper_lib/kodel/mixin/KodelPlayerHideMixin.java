package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelPlayerModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// while a player model is set, hide the vanilla player parts (KodelPlayerModelLayer draws the model).
// runs every setupAnim so it resets correctly when the model is cleared. only touches AvatarRenderState (players);
// the parts stay POSED (rotations intact) so the model layer's delta-follow still reads them.
@Mixin(HumanoidModel.class)
public class KodelPlayerHideMixin {

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void koperlib$hidePlayerForModel(HumanoidRenderState state, CallbackInfo ci) {
        if (state instanceof AvatarRenderState) {
            ((Model<?>) (Object) this).root().visible = !KodelPlayerModel.active();
        }
    }
}
