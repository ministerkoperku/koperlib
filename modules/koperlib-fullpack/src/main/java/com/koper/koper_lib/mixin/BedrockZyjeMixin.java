package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import com.koper.koper_lib.bedrock.BedrockZachowanie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// entityHurt / entityHitEntity / entityDie for bedrock addon scripts, and the behavior pack hooks for
// java's own mobs a pack redefines (villager_v2...), which KoperMobEntity calls itself for addon mobs
@Mixin(LivingEntity.class)
public abstract class BedrockZyjeMixin {

    @Shadow protected boolean dead;

    // before: an addon may cancel the hit or change how much it hurts
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "hurtServer")
    private boolean koperlib$bedrockHurtBefore(ServerLevel level, DamageSource src, float amount,
                                              com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        float now = BedrockUszy.beforeHurt((LivingEntity) (Object) this, src, amount);
        if (Float.isNaN(now)) return false;
        return original.call(level, src, now);
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean koperlib$bedrockEffectBefore(MobEffectInstance fx, Entity by,
                                                com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
        int dur = BedrockUszy.beforeEffect((LivingEntity) (Object) this, fx);
        if (dur < 0) return false;
        if (dur != fx.getDuration())
            fx = new MobEffectInstance(fx.getEffect(), dur, fx.getAmplifier(), fx.isAmbient(), fx.isVisible(), fx.showIcon());
        return original.call(fx, by);
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void koperlib$bedrockHurt(ServerLevel level, DamageSource src, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        LivingEntity self = (LivingEntity) (Object) this;
        BedrockUszy.hurt(self, src, amount);
        if (BedrockZachowanie.nakladka(self)) BedrockZachowanie.hurt((Mob) self, src, amount);
    }

    @Unique private float koperlib$hpBefore;
    @Unique private float koperlib$healBefore;

    @Inject(method = "setHealth", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockHpHead(float hp, CallbackInfo ci) {
        koperlib$hpBefore = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "setHealth", at = @At("RETURN"), require = 0)
    private void koperlib$bedrockHpReturn(float hp, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        BedrockUszy.healthChanged(self, koperlib$hpBefore, self.getHealth());
    }

    @Inject(method = "heal", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockHealHead(float amount, CallbackInfo ci) {
        koperlib$healBefore = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "heal", at = @At("RETURN"), require = 0)
    private void koperlib$bedrockHealReturn(float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        BedrockUszy.healed(self, self.getHealth() - koperlib$healBefore);
    }

    @Inject(method = "startUsingItem", at = @At("RETURN"), require = 0)
    private void koperlib$bedrockUseStart(InteractionHand hand, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isUsingItem()) BedrockUszy.itemUseStage("itemStartUse", self, self.getUseItem(), self.getUseItemRemainingTicks());
    }

    @Inject(method = "completeUsingItem", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockUseComplete(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isUsingItem()) BedrockUszy.itemUseStage("itemCompleteUse", self, self.getUseItem().copy(), used(self));
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockUseRelease(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isUsingItem()) BedrockUszy.itemUseStage("itemReleaseUse", self, self.getUseItem().copy(), used(self));
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockUseStop(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isUsingItem()) BedrockUszy.itemUseStage("itemStopUse", self, self.getUseItem().copy(), used(self));
    }

    @Unique
    private static int used(LivingEntity self) {
        return Math.max(0, self.getUseItem().getUseDuration(self) - self.getUseItemRemainingTicks());
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"), require = 0)
    private void koperlib$bedrockEffect(MobEffectInstance fx, Entity by, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) BedrockUszy.effectAdded((LivingEntity) (Object) this, fx);
    }

    // 26.3: every swing goes through swing(hand, animation, sendToSelf)
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z", at = @At("HEAD"))
    private void koperlib$bedrockSwing(InteractionHand hand, net.minecraft.world.item.component.SwingAnimation animation, boolean updateSelf,
                                       org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide()) BedrockUszy.swung(self);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void koperlib$bedrockNakladka(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isRemoved() && BedrockZachowanie.nakladka(self)) BedrockZachowanie.tick((Mob) self);
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void koperlib$bedrockDie(DamageSource src, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // same guard vanilla die() opens with, otherwise a double die() fires the event twice
        if (self.level().isClientSide() || self.isRemoved() || dead) return;
        BedrockUszy.died(self, src);
        if (BedrockZachowanie.nakladka(self)) BedrockZachowanie.died((Mob) self, src);
    }
}
