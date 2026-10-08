package com.koper.koper_lib.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

// Sided overrides both methods and pokes state properties on its own before super — same guard again
@Mixin(targets = "com.zurrtum.create.client.foundation.blockEntity.behaviour.ValueBoxTransform$Sided", remap = false)
public abstract class KontraValueBoxSidedGuardMixin {

    @WrapMethod(method = "shouldRender")
    private boolean koperlib$renderGuard(BlockState state, Operation<Boolean> original) {
        try { return original.call(state); }
        catch (IllegalArgumentException e) { return false; }
    }

    @WrapMethod(method = "testHit")
    private boolean koperlib$hitGuard(LevelAccessor level, BlockPos pos, BlockState state, Vec3 hit,
                                      Operation<Boolean> original) {
        if (com.koper.koper_lib.kender.KenderTargeting.targetsLogical(pos, state)) {
            var physical = com.koper.koper_lib.kender.KenderTargeting.getHit();
            if (physical != null) hit = physical.blockPoint();
        }
        try { return original.call(level, pos, state, hit); }
        catch (IllegalArgumentException e) { return false; }
    }
}
