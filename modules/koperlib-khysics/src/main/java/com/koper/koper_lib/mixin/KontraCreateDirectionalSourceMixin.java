package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateKinetics;
import com.zurrtum.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DirectionalShaftHalvesBlockEntity.class, remap = false)
public abstract class KontraCreateDirectionalSourceMixin {

    @Inject(method = "getSourceFacing", at = @At("HEAD"), cancellable = true)
    private void koperlib$externalSourceFacing(CallbackInfoReturnable<Direction> cir) {
        KineticBlockEntity kinetic = (KineticBlockEntity)(Object)this;
        if (kinetic.source != null) return;
        KoperCreateKinetics.Drive drive = KoperCreateKinetics.driveAt(kinetic);
        if (drive != null && drive.sourceSide() != null) cir.setReturnValue(drive.sourceSide());
    }
}
