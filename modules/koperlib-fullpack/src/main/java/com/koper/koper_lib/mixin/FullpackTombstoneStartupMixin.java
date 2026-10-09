package com.koper.koper_lib.mixin;

import com.koper.koper_lib.loader.FullpackTombstones;
import net.minecraft.server.ReloadableServerResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Item components are bound while a world's data packs load, which happens before any server
// lifecycle event. A tombstone item registered later has no components and every stack of it fails
// to decode, so the startup tombstones go in right here, after every mod has initialised.
@Mixin(ReloadableServerResources.class)
public abstract class FullpackTombstoneStartupMixin {

    @Inject(method = "loadResources", at = @At("HEAD"))
    private static void koperlib$tombstonesBeforeComponents(CallbackInfoReturnable<?> cir) {
        FullpackTombstones.registerStartupTombstones();
    }
}
