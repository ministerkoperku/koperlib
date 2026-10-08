package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zurrtum.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(OrientedContraptionEntity.class)
public abstract class KontraCreateContraptionOrientedMixin {
    @ModifyReturnValue(method = "applyRotation", at = @At("RETURN"))
    private Vec3 koperlib$rotateWithParent(Vec3 original) {
        return KoperCreateContraptions.rotateToWorld((Entity)(Object)this, original);
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = "reverseRotation", at = @At("HEAD"), argsOnly = true)
    private Vec3 koperlib$removeParentRotation(Vec3 world) {
        return KoperCreateContraptions.rotateToLocal((Entity)(Object)this, world);
    }
}
