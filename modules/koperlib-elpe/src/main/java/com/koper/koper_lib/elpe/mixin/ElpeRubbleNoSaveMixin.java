package com.koper.koper_lib.elpe.mixin;

import com.koper.koper_lib.elpe.ElpeRubble;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// rubble displays are just the look of an elpe point. saved without the point they'd be ghost blocks forever
@Mixin(Entity.class)
public abstract class ElpeRubbleNoSaveMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void koperElpeNoGhosts(CallbackInfoReturnable<Boolean> cir) {
        if (((Entity) (Object) this).entityTags().contains(ElpeRubble.TAG)) cir.setReturnValue(false);
    }
}
