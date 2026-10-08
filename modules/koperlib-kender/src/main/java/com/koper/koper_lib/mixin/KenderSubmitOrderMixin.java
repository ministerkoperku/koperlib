package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderEntityBatch;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SubmitNodeStorage.class)
public abstract class KenderSubmitOrderMixin {
    @Inject(method = "order", at = @At("RETURN"), require = 0)
    private void koperlib$rememberWorldOrder(int order, CallbackInfoReturnable<SubmitNodeCollection> cir) {
        KenderEntityBatch.registerWorldOrder(this, cir.getReturnValue(), order);
    }
}
