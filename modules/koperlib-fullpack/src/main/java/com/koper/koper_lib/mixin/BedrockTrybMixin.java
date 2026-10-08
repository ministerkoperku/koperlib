package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// playerGameModeChange for bedrock addon scripts
@Mixin(ServerPlayer.class)
public abstract class BedrockTrybMixin {

    @Unique private GameType koperlib$byl;

    @Inject(method = "setGameMode", at = @At("HEAD"), require = 0)
    private void koperlib$bedrockModeHead(GameType to, CallbackInfoReturnable<Boolean> cir) {
        koperlib$byl = ((ServerPlayer) (Object) this).gameMode();
    }

    @Inject(method = "setGameMode", at = @At("RETURN"), require = 0)
    private void koperlib$bedrockModeReturn(GameType to, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && koperlib$byl != null) BedrockUszy.gameModeChanged((ServerPlayer) (Object) this, koperlib$byl.getName(), to.getName());
    }
}
