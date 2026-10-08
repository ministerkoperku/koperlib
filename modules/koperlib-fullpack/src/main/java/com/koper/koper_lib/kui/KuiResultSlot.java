package com.koper.koper_lib.kui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

// take-only output slot. you can't put things in; taking the result consumes the inputs (crafting-table style).
public class KuiResultSlot extends Slot {
    private final KuiMenu menu;

    public KuiResultSlot(Container container, int index, int x, int y, KuiMenu menu) {
        super(container, index, x, y);
        this.menu = menu;
    }

    @Override
    public boolean mayPlace(ItemStack stack) { return false; }

    @Override
    public void onTake(Player player, ItemStack taken) {
        menu.onResultTaken(player);
        super.onTake(player, taken);
    }
}
