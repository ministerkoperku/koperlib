package com.koper.koper_lib.mixin;

import com.koper.koper_lib.KoperLib;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Pack.class)
public class ResourcePackProfileMixin {
    @Inject(method = "getCompatibility", at = @At("HEAD"), cancellable = true)
    private void koperlib$forceCompatible(CallbackInfoReturnable<PackCompatibility> cir) {
        Pack profile = (Pack) (Object) this;
        // Check if this pack is from KoperLib (by name or source)
        if (profile.getId().startsWith("koper_") || profile.getId().contains("fullpack")) {
            cir.setReturnValue(PackCompatibility.COMPATIBLE);
        }
    }
}
