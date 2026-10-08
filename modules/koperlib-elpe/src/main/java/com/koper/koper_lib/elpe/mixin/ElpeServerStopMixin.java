package com.koper.koper_lib.elpe.mixin;

import com.koper.koper_lib.elpe.ElpeLevelBoss;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// native worlds dont get gc'd, free them or singleplayer leaks one per world you open
@Mixin(MinecraftServer.class)
public abstract class ElpeServerStopMixin {
    // before the worlds save, so flying rubble lands as real blocks instead of vanishing
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void koperElpeLandEverything(CallbackInfo ci) {
        com.koper.koper_lib.elpe.ElpeRubble.settleAll();
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void koperElpeBye(CallbackInfo ci) {
        ElpeLevelBoss.dropAll();
    }
}
