package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// right clicking a mob, bedrock playerInteractWithEntity
@Mixin(Player.class)
public abstract class BedrockGadaMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockBeforeInteract(Entity target, InteractionHand hand, Vec3 at, CallbackInfoReturnable<InteractionResult> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide() || hand != InteractionHand.MAIN_HAND) return;
        if (BedrockUszy.beforeInteractEntity(self, target)) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "interactOn", at = @At("RETURN"))
    private void koperlib$bedrockAfterInteract(Entity target, InteractionHand hand, Vec3 at, CallbackInfoReturnable<InteractionResult> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide() || hand != InteractionHand.MAIN_HAND || cir.getReturnValue() == InteractionResult.FAIL) return;
        BedrockUszy.afterInteractEntity(self, target);
    }
}
