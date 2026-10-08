package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

// plain (no container slots) kui screen. drawing -> KuiPaint, interaction -> KuiWidgets.
@Environment(EnvType.CLIENT)
public class KuiScreen extends Screen {

    private final KuiPage page;
    private final KuiWidgets widgets;
    private final boolean textureMode;
    private Identifier texId;
    private int left, top;
    private int scroll, contentH; // tall json pages (bedrock forms with 30 buttons) scroll instead of falling off

    public KuiScreen(KuiPage page, String layoutOrRegions, String stateJson, byte[] png) {
        super(Component.literal(page.title));
        this.page = page;
        this.textureMode = page.isTexture();
        var els = textureMode ? KuiLayout.parseRegions(layoutOrRegions) : KuiLayout.parse(layoutOrRegions);
        this.widgets = new KuiWidgets(page.id, els);
        this.widgets.applyState(stateJson);
        if (textureMode && png != null && png.length > 0) this.texId = uploadTexture(png);
    }

    private Identifier uploadTexture(byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "kui/" + page.id.replace(':', '_'));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "kui/" + page.id, img));
            return id;
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Kui] texture upload failed for {}: {}", page.id, e.getMessage());
            return null;
        }
    }

    @Override
    protected void init() {
        this.left = (this.width - page.w) / 2;
        this.top  = (this.height - page.h) / 2;
        contentH = 0;
        if (!textureMode) for (KuiElement e : widgets.elements()) contentH = Math.max(contentH, e.y + e.h + 6);
        scroll = Math.clamp(scroll, 0, Math.max(0, contentH - page.h));
        widgets.layout(left, top - scroll);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        KuiElement tip = null;
        KuiPaint.beginFrame(mouseX, mouseY);
        if (textureMode) {
            if (texId != null)
                g.blit(RenderPipelines.GUI_TEXTURED, texId, left, top, 0f, 0f, page.w, page.h, page.w, page.h);
            else
                KuiPaint.panel(g, left, top, page.w, page.h);
            g.enableScissor(left + 2, top + 2, left + page.w - 2, top + page.h - 2);
            for (KuiElement e : widgets.elements()) {
                KuiPaint.overlay(g, this.font, e, mouseX, mouseY);
                if (!e.tooltip.isEmpty() && e.hit(mouseX, mouseY)) tip = e;
            }
        } else {
            KuiPaint.panel(g, left, top, page.w, page.h);
            g.enableScissor(left + 2, top + 2, left + page.w - 2, top + page.h - 2);
            KuiPaint.centeredClippedText(g, this.font, this.title.getString(),
                left + 18, top + 6, page.w - 36, KuiPaint.TITLE);
            if (scrolls()) {
                g.disableScissor();
                g.enableScissor(left + 2, top + 18, left + page.w - 2, top + page.h - 2);
            }
            for (KuiElement e : widgets.elements()) {
                KuiPaint.widget(g, this.font, e, mouseX, mouseY);
                if (!e.tooltip.isEmpty() && e.hit(mouseX, mouseY)) tip = e;
            }
        }
        if (scrolls()) {
            g.disableScissor();
            int track = page.h - 22, bar = Math.max(10, track * page.h / contentH);
            int by = top + 20 + (track - bar) * scroll / Math.max(1, contentH - page.h);
            g.fill(left + page.w - 5, by, left + page.w - 3, by + bar, KuiPaint.TITLE);
            g.enableScissor(left + 2, top + 2, left + page.w - 2, top + page.h - 2);
        }
        boolean themeHover = themeHit(mouseX, mouseY);
        KuiPaint.themeChip(g, left + page.w - 15, top + 4, themeHover);
        g.disableScissor();
        if (themeHover)
            g.setTooltipForNextFrame(this.font,
                Component.literal("Theme: " + KuiThemes.displayName() + " (click to change)"), mouseX, mouseY);
        else if (tip != null)
            g.setTooltipForNextFrame(this.font.split(Component.literal(tip.tooltip), 240), mouseX, mouseY);
        else if (KuiPaint.clippedHover() != null)
            g.setTooltipForNextFrame(this.font.split(Component.literal(KuiPaint.clippedHover()), 240), mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override public boolean mouseClicked(MouseButtonEvent ev, boolean dbl) {
        if (themeHit(ev.x(), ev.y())) {
            KuiThemes.cycle();
            return true;
        }
        // stuff scrolled under the title bar is still in the list, dont let it eat clicks
        if (scrolls() && (ev.y() < top + 18 || ev.y() >= top + page.h - 2)) return super.mouseClicked(ev, dbl);
        return widgets.mouseClicked(ev.x(), ev.y()) || super.mouseClicked(ev, dbl);
    }
    @Override public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) { return widgets.mouseDragged(ev.x()) || super.mouseDragged(ev, dx, dy); }
    @Override public boolean mouseReleased(MouseButtonEvent ev) { return widgets.mouseReleased() || super.mouseReleased(ev); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (widgets.mouseScrolled(mx, my, sy)) return true;
        if (scrolls()) {
            scroll = Math.clamp(scroll - Math.round((float) sy * 14f), 0, contentH - page.h);
            widgets.layout(left, top - scroll);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent ev) { return widgets.keyPressed(ev.key()) || super.keyPressed(ev); }
    @Override public boolean charTyped(CharacterEvent ev) { return widgets.charTyped(ev.codepoint()) || super.charTyped(ev); }

    public void updateWidget(String widget, String value) { widgets.update(widget, value); }

    private boolean scrolls() { return contentH > page.h; }

    private boolean themeHit(double x, double y) {
        int tx = left + page.w - 15, ty = top + 4;
        return x >= tx && x < tx + 12 && y >= ty && y < ty + 10;
    }

    @Override
    public void removed() {
        widgets.onClosed();
        if (texId != null) Minecraft.getInstance().getTextureManager().release(texId);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
