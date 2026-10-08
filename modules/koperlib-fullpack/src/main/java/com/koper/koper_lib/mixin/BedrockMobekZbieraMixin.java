package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// entityItemPickup for mobs. wrapped at the call in aiStep because villagers and raiders override
// pickUpItem without always calling super, the call site catches all of them
@Mixin(Mob.class)
public abstract class BedrockMobekZbieraMixin {

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Mob;pickUpItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void koperlib$mobekZbiera(Mob self, ServerLevel level, ItemEntity item, Operation<Void> original) {
        if (BedrockUszy.beforePickup(self, item)) return;
        ItemStack bylo = item.getItem().copy();
        original.call(self, level, item);
        if (!item.isRemoved()) bylo.shrink(item.getItem().getCount());
        BedrockUszy.afterPickup(self, bylo);
    }
}
