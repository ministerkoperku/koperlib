package com.koper.koper_lib.kui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// shapeless recipe matching over a container's contents. exact multiset: what's in the slots must be
// precisely the recipe ingredients — no leftovers — same as throwing items in a crafting grid.
public final class KuiCraft {
    private KuiCraft() {}

    // first recipe whose ingredient multiset equals the container contents, or null
    public static KuiRecipe match(Container c, List<KuiRecipe> recipes) {
        Map<String, Integer> have = contents(c);
        if (have.isEmpty()) return null;
        for (KuiRecipe r : recipes) {
            Map<String, Integer> need = new HashMap<>();
            for (String ing : r.ingredients()) need.merge(ing, 1, Integer::sum);
            if (have.equals(need)) return r;
        }
        return null;
    }

    // remove the recipe's ingredients from the container
    public static void consume(Container c, KuiRecipe r) {
        Map<String, Integer> need = new HashMap<>();
        for (String ing : r.ingredients()) need.merge(ing, 1, Integer::sum);
        for (var e : need.entrySet()) {
            int rem = e.getValue();
            for (int i = 0; i < c.getContainerSize() && rem > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !key(s).equals(e.getKey())) continue;
                int take = Math.min(rem, s.getCount());
                s.shrink(take);
                rem -= take;
            }
        }
        c.setChanged();
    }

    // hand the result to the player (drops if the inventory is full)
    public static void giveResult(Player player, KuiRecipe r) {
        Identifier id = Identifier.tryParse(r.result());
        if (id == null) return;
        var item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null) return;
        ItemStack out = new ItemStack(item, Math.max(1, r.count()));
        player.getInventory().add(out);
        if (!out.isEmpty()) com.koper.koper_lib.core.KoperWyrzucacz.drop(player, out, false);
    }

    private static Map<String, Integer> contents(Container c) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) m.merge(key(s), s.getCount(), Integer::sum);
        }
        return m;
    }

    private static String key(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }
}
