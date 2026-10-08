package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.zurrtum.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CarriageContraptionEntity.class)
public abstract class KontraCreateContraptionTrainMixin {
    @Inject(method = "tickContraption", at = @At("HEAD"))
    private void koperlib$bindMovingTrackFrame(CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity)(Object)this;
        KoperCreateContraptions.bindTrain(self, self.getCarriage());
    }
}
