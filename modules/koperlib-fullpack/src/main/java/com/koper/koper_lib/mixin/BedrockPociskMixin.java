package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// projectileHitEntity / projectileHitBlock for bedrock addon scripts
@Mixin(Projectile.class)
public abstract class BedrockPociskMixin {

    @Inject(method = "onHit", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockHit(HitResult hit, CallbackInfo ci) {
        BedrockUszy.projectileHit((Projectile) (Object) this, hit);
    }
}
