package com.koper.koper_lib.mixin;

import com.koper.koper_lib.scripting.ItemScriptRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ResultSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public abstract class CraftingEventMixin {

    @Inject(method = "onTake", at = @At("RETURN"))
    private void koperlib$onCraft(Player player, ItemStack stack, CallbackInfo ci) {
        if (!player.level().isClientSide()) {
            ItemScriptRegistry.fireEquipEvent(stack, ScriptEvent.ON_CRAFT, player);

            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) com.koper.koper_lib.scripting.KoperSnitch.snitch(
                    serverPlayer, com.koper.koper_lib.scripting.KoperSnitch.CRAFT,
                    "id", key.toString(), "count", String.valueOf(stack.getCount()));
            }
        }
    }
}
