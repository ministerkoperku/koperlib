package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.zurrtum.create.content.contraptions.ControlledContraptionEntity;
import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import com.zurrtum.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ControlledContraptionEntity.class)
public abstract class KontraCreateContraptionControlledMixin {
    @Shadow protected BlockPos controllerPos;

    @Inject(method = "tickContraption", at = @At("HEAD"))
    private void koperlib$bindControllerFrame(CallbackInfo ci) {
        if (controllerPos != null)
            KoperCreateContraptions.bindController((Entity)(Object)this, controllerPos);
    }

    @WrapMethod(method = "tickContraption")
    private void koperlib$tickInsideParentGrid(Operation<Void> original) {
        Entity self = (Entity)(Object)this;
        if (controllerPos != null) KoperCreateContraptions.bindController(self, controllerPos);
        KoperCreateContraptions.inParentGrid(self, () -> original.call());
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "tickContraption",
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lcom/zurrtum/create/content/contraptions/ControlledContraptionEntity;discard()V"))
    private void koperlib$restoreChildWhenControllerBreaks(
            ControlledContraptionEntity self, Operation<Void> original) {
        if (KoperCreateContraptions.hasLiveParent(self)) {
            ((AbstractContraptionEntity)self).disassemble();
            return;
        }
        original.call(self);
    }

    @ModifyReturnValue(method = "makeStructureTransform", at = @At("RETURN"))
    private StructureTransform koperlib$disassembleIntoParentGrid(StructureTransform original) {
        return KoperCreateContraptions.localizeTransform((Entity)(Object)this, original);
    }

    @ModifyReturnValue(method = "applyRotation", at = @At("RETURN"))
    private Vec3 koperlib$rotateWithParent(Vec3 original) {
        return KoperCreateContraptions.rotateToWorld((Entity)(Object)this, original);
    }

    @ModifyVariable(method = "reverseRotation", at = @At("HEAD"), argsOnly = true)
    private Vec3 koperlib$removeParentRotation(Vec3 world) {
        return KoperCreateContraptions.rotateToLocal((Entity)(Object)this, world);
    }
}
