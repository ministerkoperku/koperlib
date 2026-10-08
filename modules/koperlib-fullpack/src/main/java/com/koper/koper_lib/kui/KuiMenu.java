package com.koper.koper_lib.kui;

import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// container menu for kui guis with real item slots. input slots live in a persisted container,
// output slots in a transient result container (so recipe previews aren't saved). when inputs match a
// recipe the result auto-shows in the output; taking it consumes the inputs and fires gui:recipe.
public class KuiMenu extends AbstractContainerMenu {
    public final KuiMenuData data;
    private final Container input;
    private final SimpleContainer result;
    private final int inputSlots, outputSlots;
    private final List<KuiRecipe> recipes; // server only; null on client (KuiBook empty there)
    private boolean computing;

    public KuiMenu(int syncId, Inventory inv, KuiMenuData data, Container input) {
        super(KuiMenus.TYPE, syncId);
        this.data = data;
        this.input = input;

        List<KuiElement> els = KuiLayout.parse(data.layout());
        this.inputSlots = KuiMenus.inputCellCount(els);
        this.outputSlots = KuiMenus.outputCellCount(els);
        this.result = new SimpleContainer(Math.max(1, outputSlots));
        checkContainerSize(input, inputSlots);

        var page = KuiBook.get(data.id());
        this.recipes = page != null ? page.recipes : null;

        int in = 0, out = 0;
        for (KuiElement e : els) {
            if (!e.container) continue;
            for (int[] c : KuiMenus.cellsOf(e)) {
                if (KuiMenus.isOutput(e)) addSlot(new KuiResultSlot(result, out++, c[0], c[1], this));
                else                      addSlot(new Slot(input, in++, c[0], c[1]));
            }
        }
        int[] pinv = KuiMenus.playerInvPos(els);
        if (pinv != null) addStandardInventorySlots(inv, pinv[0], pinv[1]);

        recompute();
    }

    public Container getContainer() { return input; }

    @Override
    public void slotsChanged(Container container) {
        if (!computing) recompute();
    }

    // a plain SimpleContainer never calls slotsChanged, so recompute the preview here — runs every tick
    // for the open menu, right before the server syncs slots to the client. cheap (a small multiset match).
    @Override
    public void broadcastChanges() {
        recompute();
        super.broadcastChanges();
    }

    // refresh the result preview from the current inputs
    private void recompute() {
        if (recipes == null || recipes.isEmpty() || outputSlots == 0) return;
        computing = true;
        KuiRecipe r = KuiCraft.match(input, recipes);
        if (r != null) {
            Identifier id = Identifier.tryParse(r.result());
            var item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            result.setItem(0, item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, r.count())));
        } else {
            result.setItem(0, ItemStack.EMPTY);
        }
        computing = false;
    }

    // called when the player pulls the result out — burn the inputs + tell the script
    public void onResultTaken(Player player) {
        if (recipes == null) return;
        KuiRecipe r = KuiCraft.match(input, recipes);
        if (r != null) {
            KuiCraft.consume(input, r);
            var page = KuiBook.get(data.id());
            if (player instanceof ServerPlayer sp && page != null && page.script != null)
                UniversalScriptEngine.fireGuiEvent(page.script, sp, data.id(), r.id(), "recipe", 0f, r.result(), false, input);
        }
        recompute();
    }

    @Override
    public boolean stillValid(Player player) {
        return input.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int craftEnd = inputSlots + outputSlots; // player inv starts here
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            moved = stack.copy();
            if (index < inputSlots) {
                if (!moveItemStackTo(stack, craftEnd, this.slots.size(), false)) return ItemStack.EMPTY;
            } else if (index < craftEnd) {
                // shift-took the result -> push to inventory then consume the inputs
                if (!moveItemStackTo(stack, craftEnd, this.slots.size(), true)) return ItemStack.EMPTY;
                onResultTaken(player);
            } else {
                if (!moveItemStackTo(stack, 0, inputSlots, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return moved;
    }
}
