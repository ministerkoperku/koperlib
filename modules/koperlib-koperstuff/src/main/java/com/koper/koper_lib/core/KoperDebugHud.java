package com.koper.koper_lib.core;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public final class KoperDebugHud {

    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "debug_overlay");

    private KoperDebugHud() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, KoperDebugHud::render);
    }

    private static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker tick) {
        if (!KoperDebug.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int y = 4;
        int color = 0xFFE0E0E0;
        for (String line : KoperDebug.buildLines()) {
            if (y > mc.getWindow().getGuiScaledHeight() - 12) break;
            g.text(mc.font, line, 4, y, color);
            y += 10;
        }

        var p = mc.player;
        g.text(mc.font, String.format("pos %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()), 4, y, color);
        y += 10;
        g.text(mc.font, "dim=" + p.level().dimension().identifier(), 4, y, color);
    }
}
