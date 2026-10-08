package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// riding a bobbing kontra must NEVER deal fall damage (the up-down dmg thing drives people insane).
// landing on a vertically moving deck is also free — only a genuinely parked kontra hurts like ground.
@Mixin(LivingEntity.class)
public abstract class KontraFallDamageMixin {

    @Inject(method = "causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void koper$kontraFall(double fallDistance, float damageModifier, DamageSource source,
                                  CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (KoperPhys.shouldEatFallDamage(self)) cir.setReturnValue(false);
    }
}
