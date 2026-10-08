package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class BedrockSwiatMixin {

    // fresh spawn, not a load: tells the ENTITY_LOAD listener to stay out of it
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void koperlib$swiezak(Entity e, CallbackInfoReturnable<Boolean> cir) {
        BedrockUszy.SWIEZAK.set(true);
    }

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void koperlib$bedrockSpawn(Entity e, CallbackInfoReturnable<Boolean> cir) {
        BedrockUszy.SWIEZAK.set(false);
        if (cir.getReturnValueZ()) BedrockUszy.spawned(e);
    }
}
