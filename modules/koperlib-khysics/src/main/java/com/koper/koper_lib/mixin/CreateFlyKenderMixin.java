package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KenderFlywheelCompat;
import com.zurrtum.create.client.flywheel.backend.Backends;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// one hook only; the adapter stays outside the mixin so Create updates don't turn this into injection soup
@Mixin(value = Backends.class, remap = false)
public class CreateFlyKenderMixin {
    @Inject(method = "<clinit>", at = @At("RETURN"), require = 1)
    private static void koperlib$registerKender(CallbackInfo ci) {
        KenderFlywheelCompat.register();
    }
}
