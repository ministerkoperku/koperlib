package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class KontraBlockContactMixin {
    @WrapOperation(method = "lambda$checkInsideBlocks$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V"))
    private void koperlib$gridContact(BlockState state, Level level, BlockPos pos, Entity entity,
                                      InsideBlockEffectApplier effects, boolean inside,
                                      Operation<Void> original) {
        if (level instanceof ServerLevel server) {
            var contact = KoperPhys.projectedBlockContact(server, pos, state);
            if (contact != null) {
                // Keep the callback's state, queries and scheduled rechecks in one local frame.
                KontraGridContext.run(contact.grid(), () -> original.call(contact.state(), level,
                    contact.pos(), entity, effects, inside));
                return;
            }
        }
        original.call(state, level, pos, entity, effects, inside);
    }
}
