package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class BedrockOdrodzenieMixin {

    @Inject(method = "respawn", at = @At("RETURN"))
    private void koperlib$bedrockRespawn(ServerPlayer old, boolean keepAll, Entity.RemovalReason why, CallbackInfoReturnable<ServerPlayer> cir) {
        // leaving the end also goes through respawn, bedrock doesn't call that a spawn
        if (why == Entity.RemovalReason.KILLED && cir.getReturnValue() != null) BedrockUszy.respawned(cir.getReturnValue());
    }
}
