package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// bedrock mobs are drawn as far as their geometry's visible bounds reach, not their hitbox. never less than java would
@Mixin(Entity.class)
public abstract class BrDrawDistanceMixin {

    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockDrawDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide()) return;
        double z = BrAktorzy.drawSize(self);
        if (z <= 0) return;
        double size = self.getBoundingBox().getSize();
        if (Double.isNaN(size)) size = 1;
        size = Math.max(size, z) * 64.0 * Entity.getViewScale();
        cir.setReturnValue(distance < size * size);
    }
}
