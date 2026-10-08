package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

// layouts shown as a screen overlay (not a Screen). toggled per-player by koper.gui.hud(player, id, show).
// not clickable — meant for bars/labels/icons that the script updates via koper.gui.set.
@Environment(EnvType.CLIENT)
public final class KuiHud {
    private record Entry(KuiWidgets widgets, int x, int y) {}

    private static final Map<String, Entry> ACTIVE = new LinkedHashMap<>();

    private KuiHud() {}

    public static void set(String id, boolean show, String layout, int x, int y) {
        if (!show) { ACTIVE.remove(id); return; }
        ACTIVE.put(id, new Entry(new KuiWidgets(id, KuiLayout.parse(layout)), x, y));
    }

    public static void update(String widget, String value) {
        for (Entry e : ACTIVE.values()) e.widgets.update(widget, value);
    }

    public static void clear() { ACTIVE.clear(); }

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "kui_hud"), KuiHud::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        if (ACTIVE.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        for (Entry e : ACTIVE.values()) {
            e.widgets.layout(e.x, e.y);
            for (KuiElement el : e.widgets.elements()) KuiPaint.widget(g, font, el, -1, -1);
        }
    }
}
