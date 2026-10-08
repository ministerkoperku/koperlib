package com.koper.koper_lib.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// debug only — furnace/chest GUI on a kontra kicks the player out and spits items.
// the stack of the close call names the ejector (stillValid distance? BE swap? removed block?)
@Mixin(ServerPlayer.class)
public abstract class KontraMenuCloseDbgMixin {

    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void koperlib$whoClosedMe(CallbackInfo ci) {
        if (!com.koper.koper_lib.config.KoperLibConfig.get().debugMode) return;
        ServerPlayer self = (ServerPlayer)(Object)this;
        if (self.containerMenu == self.inventoryMenu) return;
        var trace = new Throwable().getStackTrace();
        StringBuilder via = new StringBuilder();
        for (int i = 1; i < Math.min(trace.length, 10); i++)
            via.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
               .append(':').append(trace[i].getLineNumber()).append(" <- ");
        com.koper.koper_lib.KoperLib.LOGGER.info("[GridDbg] closeContainer menu={} via {}",
            self.containerMenu.getClass().getSimpleName(), via);
    }
}
