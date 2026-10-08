package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// ClientLevelAccessMixin makes kontraktion blocks visible as real blocks client-side
// so that mining/crack-stages work. side effect: MC's isInWall() sees physics blocks as walls
// → suffocation damage + soul-sand slowdown even when standing next to a kontraktion.
// fix: if the "wall" at entity head is a physics block, it's not a real wall → skip it.
@Environment(EnvType.CLIENT)
@Mixin(LivingEntity.class)
public abstract class KontraIsInWallMixin {

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true, require = 0)
    private void koper$skipPhysicsWall(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!self.level().isClientSide()) return;
        if (KenderClientState.isEmpty()) return;

        // match vanilla isInWall's width*0.8 eye box instead of just eye + eye.below
        AABB bb = self.getBoundingBox();
        if (KenderClientState.eyeBoxHasPhysicsBlock(self.getX(), self.getEyeY(), self.getZ(), bb.maxX - bb.minX))
            cir.setReturnValue(false);
    }
}
