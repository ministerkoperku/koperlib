package com.koper.koper_lib.elpe.mixin;

import com.koper.koper_lib.elpe.ElpeLevelBoss;
import com.koper.koper_lib.elpe.ElpeSelfTest;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

// elpe steps right after the level ticked, so it sees this tick's block changes
@Mixin(ServerLevel.class)
public abstract class ElpeLevelTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void koperElpeTick(BooleanSupplier haveTime, CallbackInfo ci) {
        if (ElpeSelfTest.ON) ElpeSelfTest.tick((ServerLevel) (Object) this);
        ElpeLevelBoss.tick((ServerLevel) (Object) this);
    }
}
