package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// entityItemPickup for players. HEAD can say no, the onItemPickup call is where it really happened
@Mixin(ItemEntity.class)
public abstract class BedrockPodnosiMixin {

    @Shadow public abstract ItemStack getItem();

    @Unique private ItemStack koperlib$ileBylo = ItemStack.EMPTY;

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void koperlib$zanimPodniesie(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide() || self.hasPickUpDelay()) return;
        if (BedrockUszy.beforePickup(player, self)) { ci.cancel(); return; }
        koperlib$ileBylo = getItem().copy();
    }

    @Inject(method = "playerTouch", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;onItemPickup(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void koperlib$podniosl(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        ItemStack got = koperlib$ileBylo.copy();
        // not all of it fit: what is still lying there was not picked up
        if (!self.isRemoved()) got.shrink(getItem().getCount());
        BedrockUszy.afterPickup(player, got);
    }
}
