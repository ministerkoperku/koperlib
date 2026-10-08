package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// entityItemDrop. players land here too, ServerPlayer.drop goes through super
@Mixin(LivingEntity.class)
public abstract class BedrockWyrzutMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("RETURN"))
    private void koperlib$wyrzucil(ItemStack stack, boolean thrownFromHand, net.minecraft.util.Prediction prediction,
                                   CallbackInfoReturnable<ItemEntity> cir) {
        BedrockUszy.dropped((LivingEntity) (Object) this, cir.getReturnValue());
    }
}
