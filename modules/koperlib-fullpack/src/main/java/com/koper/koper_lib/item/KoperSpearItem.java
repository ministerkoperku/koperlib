package com.koper.koper_lib.item;

import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

import java.util.List;

public class KoperSpearItem extends Item {

    private final List<String> scripts;

    public KoperSpearItem(Item.Properties settings, List<String> scripts) {
        super(settings);
        this.scripts = scripts;
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        if (!attacker.level().isClientSide() && attacker instanceof Player player) {
            for (String script : scripts) {
                UniversalScriptEngine.call(script, ScriptEvent.ON_HIT, player, target);
            }
        }
    }
}

