package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraWorldData;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// persist kontraktions on every vanilla world save (autosave / pause / Save-and-Quit). closing the MC
// window kills the JVM mid-shutdown before SERVER_STOPPING can finish saving — so tying our save to the
// vanilla one means kontras are already on disk by the time you quit, however you quit.
@Mixin(MinecraftServer.class)
public abstract class KontraSaveMixin {

    @Inject(method = "saveEverything", at = @At("RETURN"))
    private void koper_saveKontras(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (KoperPhys.all().isEmpty() && !KontraWorldData.hasSave((MinecraftServer)(Object)this)) return;
        KontraWorldData.save((MinecraftServer)(Object)this);
    }
}
