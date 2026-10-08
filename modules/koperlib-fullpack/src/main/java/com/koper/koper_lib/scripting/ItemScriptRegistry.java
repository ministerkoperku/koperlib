package com.koper.koper_lib.scripting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// item id → script list; lets mixin events fire scripts without touching the original data objects
public class ItemScriptRegistry {

    private static final Map<String, List<String>> SCRIPTS = new HashMap<>();

    public static void register(String itemId, List<String> scripts) {
        if (scripts == null || scripts.isEmpty()) return;
        SCRIPTS.put(itemId, List.copyOf(scripts));
    }

    public static void clear() {
        SCRIPTS.clear();
    }

    public static void fireEquipEvent(ItemStack stack, ScriptEvent event, Player player) {
        if (stack == null || stack.isEmpty()) return;
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return;
        List<String> scripts = SCRIPTS.get(itemId.toString());
        if (scripts == null) return;
        for (String script : scripts) {
            UniversalScriptEngine.call(script, event, player);
        }
    }

}
