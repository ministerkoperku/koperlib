package com.koper.koper_lib.kui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

// the five things every koper screen actually draws. screens call these instead of touching
// GuiGraphicsExtractor, so when mojang moves the drawing api again it moves here once and not in
// every screen of every koper mod. last port those calls were the bulk of the damage
@Environment(EnvType.CLIENT)
public final class KuiDraw {
    private KuiDraw() {}

    // x,y,w,h like everyone expects. vanilla fill wants two corners and that trips people up
    public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }

    public static void boxTo(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, argb);
    }

    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        g.outline(x, y, w, h, argb);
    }

    public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int argb) {
        g.text(font, Component.literal(s), x, y, argb);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component s, int x, int y, int argb) {
        g.text(font, s, x, y, argb);
    }

    public static void centered(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int argb) {
        g.centeredText(font, Component.literal(s), cx, y, argb);
    }

    public static void centered(GuiGraphicsExtractor g, Font font, Component s, int cx, int y, int argb) {
        g.centeredText(font, s, cx, y, argb);
    }

    // timer / charge / health bars. every screen was rolling this by hand out of two fills
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float progress, int back, int front) {
        box(g, x, y, w, h, back);
        int filled = (int) (w * Math.clamp(progress, 0f, 1f));
        if (filled > 0) box(g, x, y, filled, h, front);
    }
}
