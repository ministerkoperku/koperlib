package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptionAccess;
import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContraptionEntity.class)
public abstract class KontraCreateContraptionEntityMixin implements KoperCreateContraptionAccess {
    @Unique private long koperlib$parentBody = -1L;
    @Unique private Vec3 koperlib$logicalAnchor;

    @Override public long koperlib$parentBody() { return koperlib$parentBody; }
    @Override public void koperlib$parentBody(long body) { koperlib$parentBody = body; }
    @Override public Vec3 koperlib$logicalAnchor() { return koperlib$logicalAnchor; }
    @Override public void koperlib$logicalAnchor(Vec3 anchor) { koperlib$logicalAnchor = anchor; }

    @WrapOperation(method = "setPos(DDD)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V"))
    private void koperlib$keepPositionInParentFrame(AbstractContraptionEntity self,
            double x, double y, double z,
            Operation<Void> original) {
        Vec3 mapped = KoperCreateContraptions.mapSetPos(self, x, y, z);
        original.call(self, mapped.x, mapped.y, mapped.z);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void koperlib$followParentFrame(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        Vec3 world = KoperCreateContraptions.follow(self);
        if (world != null && self.position().distanceToSqr(world) > 1.0e-12)
            self.setPos(world.x, world.y, world.z);
    }

    @WrapMethod(method = "disassemble")
    private void koperlib$disassembleInsideParentGrid(Operation<Void> original) {
        KoperCreateContraptions.inParentGrid((Entity)(Object)this, () -> original.call());
    }

    @Inject(method = "writeAdditional", at = @At("TAIL"))
    private void koperlib$saveParentFrame(ValueOutput output, boolean spawnPacket, CallbackInfo ci) {
        if (koperlib$parentBody < 0 || koperlib$logicalAnchor == null) return;
        output.putLong("KoperParentBody", koperlib$parentBody);
        output.putDouble("KoperLocalX", koperlib$logicalAnchor.x);
        output.putDouble("KoperLocalY", koperlib$logicalAnchor.y);
        output.putDouble("KoperLocalZ", koperlib$logicalAnchor.z);
    }

    @Inject(method = "readAdditional", at = @At("TAIL"))
    private void koperlib$loadParentFrame(ValueInput input, boolean spawnPacket, CallbackInfo ci) {
        long body = input.getLongOr("KoperParentBody", -1L);
        if (body < 0) return;
        koperlib$parentBody = body;
        koperlib$logicalAnchor = new Vec3(
            input.getDoubleOr("KoperLocalX", 0),
            input.getDoubleOr("KoperLocalY", 0),
            input.getDoubleOr("KoperLocalZ", 0));
    }
}
