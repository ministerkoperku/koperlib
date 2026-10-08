package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// create renderers check supportsVisualization and skip the whole machine body ("flywheel draws it").
// grid BEs on a kontra have NO flywheel visual, so press heads/saw blades/arms went invisible and
// ArmRenderer NPE'd on its half-filled state. inside grid render ctx: lie "no visualization" ->
// renderers do the full vanilla extract and our submit path draws everything.
@Mixin(targets = "com.zurrtum.create.client.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class KontraFlywheelVisModeMixin {

    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, require = 0)
    private static void koperlib$noVisInGrid(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (KenderClientState.activeGrid() != null) cir.setReturnValue(false);
    }
}
