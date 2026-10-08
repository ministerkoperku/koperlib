package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGlue;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tracked items need Entity.move each server tick to inherit their moving deck's pose. */
@Mixin(ItemEntity.class)
public abstract class KontraItemCarryMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/item/ItemEntity;onGround()Z", ordinal = 0))
    private boolean koperlib$keepDeckCarryTicking(boolean grounded) {
        ItemEntity self = (ItemEntity)(Object)this;
        // Only bypass the idle-move gate. Later ground checks still control vanilla friction/bounce.
        return grounded && (self.level().isClientSide() || !KontraGlue.tracked(self));
    }
}
