package com.koper.koper_lib.mixin;

import com.koper.koper_lib.scripting.KoperSnitch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// "the player now has one of these". HEAD because add() eats the stack as it fills slots.
// BROKEN for menus: shift-click and any quick-move go through moveItemStackTo and write slots
// directly, never touching add(), so those arrivals are silent. mouse pickup only works because
// the carried stack goes back through add() on close. fix = diff the inventory around doClick.
// PLEASE HELP A SILLY LITTLE KOPERDEV if you know a cheaper hook
@Mixin(Inventory.class)
public abstract class KoperSnitchGrabMixin {

    @Shadow @Final public Player player;

    @Inject(method = "add(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"))
    private void koperlib$snitchGrab(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty() || !(player instanceof ServerPlayer serverPlayer)) return;

        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) return;

        KoperSnitch.snitch(serverPlayer, KoperSnitch.GOT,
            "id", key.toString(),
            "count", String.valueOf(stack.getCount()));
    }
}
