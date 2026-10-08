package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import java.util.ArrayList;
import java.util.List;

// one custom menu type for every kui container gui. fabric-screen-handler-api isn't published for 26.2,
// so we build the vanilla MenuType ourselves (constructor opened via classtweaker) and ferry the render
// data to the client over our own packet, stashed in KuiClientPending right before the open packet lands.
public final class KuiMenus {
    private KuiMenus() {}

    public static final MenuType<KuiMenu> TYPE = new MenuType<>(
        (containerId, inv) -> {
            KuiMenuData d = KuiClientPending.take();
            if (d == null) d = new KuiMenuData("", "", 176, 166, "json", "{}", "", new byte[0]);
            return new KuiMenu(containerId, inv, d, new SimpleContainer(Math.max(1, slotCount(d))));
        },
        FeatureFlags.VANILLA_SET);

    public static void register() {
        Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "kui_menu"), TYPE);
    }

    // item-area top-lefts (inside the 1px bevel) for one container element (slot = 1, grid = cols*rows)
    public static List<int[]> cellsOf(KuiElement e) {
        List<int[]> out = new ArrayList<>();
        if (!e.container) return out;
        if (e.type.equals("slot")) {
            out.add(new int[]{ e.x + 1, e.y + 1 });
        } else if (e.type.equals("grid")) {
            int pitch = 18 + e.gap;
            for (int r = 0; r < e.rows; r++)
                for (int c = 0; c < e.cols; c++)
                    out.add(new int[]{ e.x + c * pitch + 1, e.y + r * pitch + 1 });
        }
        return out;
    }

    public static boolean isOutput(KuiElement e) { return "output".equals(e.role); }

    public static int inputCellCount(List<KuiElement> els) {
        int n = 0;
        for (KuiElement e : els) if (e.container && !isOutput(e)) n += cellsOf(e).size();
        return n;
    }

    public static int outputCellCount(List<KuiElement> els) {
        int n = 0;
        for (KuiElement e : els) if (e.container && isOutput(e)) n += cellsOf(e).size();
        return n;
    }

    // top-left item area for addStandardInventorySlots, or null = no player inventory in this gui
    public static int[] playerInvPos(List<KuiElement> els) {
        for (KuiElement e : els)
            if (e.type.equals("player_inv")) return new int[]{ e.x + 1, e.y + 1 };
        return null;
    }

    // size of the persisted (input) container — output slots live in a transient result container
    public static int slotCount(KuiMenuData data) {
        return inputCellCount(KuiLayout.parse(data.layout()));
    }

    public static boolean hasContainer(List<KuiElement> els) {
        for (KuiElement e : els) if (e.container) return true;
        return false;
    }
}
