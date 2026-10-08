package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// server-side counterpart to KontraIsInWallMixin
// ServerLevelAccessMixin makes physics blocks visible via getBlockState()
// so isInWall() on server sees physics blocks as solid walls → deals suffocation damage
// fix: cancel isInWall() when eye pos is a physics block (same logic as client mixin)
@Mixin(LivingEntity.class)
public abstract class KontraIsInWallServerMixin {

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true, require = 0)
    private void koper$skipPhysicsWallServer(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (self.level().isClientSide()) return; // client has KontraIsInWallMixin
        if (!(self.level() instanceof ServerLevel sl)) return;
        if (KoperPhys.all().isEmpty()) return;

        // old check only looked at eye + eye.below — vanilla samples a whole width*0.8 box, so a
        // rounded-cell miss let suffocation through when standing next to a rotated/offset kontra
        AABB bb = self.getBoundingBox();
        if (KoperPhys.eyeBoxHasPhysicsBlock(sl, self.getX(), self.getEyeY(), self.getZ(), bb.maxX - bb.minX))
            cir.setReturnValue(false);
    }
}
