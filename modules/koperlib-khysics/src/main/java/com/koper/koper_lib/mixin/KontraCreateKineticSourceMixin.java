package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateKinetics;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KontraCreateKineticSourceMixin {

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true)
    private void koperlib$externalGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        KoperCreateKinetics.Drive drive =
            KoperCreateKinetics.driveAt((KineticBlockEntity)(Object)this);
        if (drive != null) cir.setReturnValue(drive.rpm());
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true)
    private void koperlib$externalStressCapacity(CallbackInfoReturnable<Float> cir) {
        KoperCreateKinetics.Drive drive =
            KoperCreateKinetics.driveAt((KineticBlockEntity)(Object)this);
        if (drive != null) cir.setReturnValue(drive.capacityPerRpm());
    }
}
