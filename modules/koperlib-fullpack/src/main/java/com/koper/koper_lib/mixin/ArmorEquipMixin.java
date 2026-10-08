package com.koper.koper_lib.mixin;

import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// fires equip/unequip on actual slot change, not every tick like a dummy
@Mixin(LivingEntity.class)
public abstract class ArmorEquipMixin {

    @Inject(method = "setItemSlot", at = @At("HEAD"))
    private void koperlib$onSlotChange(EquipmentSlot slot, ItemStack newStack, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player))
            return;

        if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
                && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET)
            return;

        ItemStack previous = player.getItemBySlot(slot);
        boolean wasEmpty = previous.isEmpty();
        boolean isEmpty = newStack.isEmpty();

        if (wasEmpty && !isEmpty) {
            koperlib$fire(newStack, ScriptEvent.ON_EQUIP, player);
        } else if (!wasEmpty && isEmpty) {
            koperlib$fire(previous, ScriptEvent.ON_UNEQUIP, player);
        } else if (!wasEmpty && !isEmpty && !ItemStack.isSameItemSameComponents(newStack, previous)) {
            koperlib$fire(previous, ScriptEvent.ON_UNEQUIP, player);
            koperlib$fire(newStack, ScriptEvent.ON_EQUIP, player);
        }
    }

    @Unique
    private static void koperlib$fire(ItemStack stack, ScriptEvent event, Player player) {
        if (stack.isEmpty()) return;
        com.koper.koper_lib.scripting.ItemScriptRegistry.fireEquipEvent(stack, event, player);
    }
}
