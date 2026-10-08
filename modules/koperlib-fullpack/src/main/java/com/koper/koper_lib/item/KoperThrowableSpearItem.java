package com.koper.koper_lib.item;

import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.Item;

import java.util.List;

public class KoperThrowableSpearItem extends TridentItem {

    private final List<String> scripts;

    public KoperThrowableSpearItem(Item.Properties settings, List<String> scripts) {
        super(settings);
        this.scripts = scripts;
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        if (!attacker.level().isClientSide() && attacker instanceof Player player) {
            for (String script : scripts) {
                UniversalScriptEngine.call(script, ScriptEvent.ON_HIT, player, target);
            }
        }
    }
}
