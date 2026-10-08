package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

// kui gui with real item slots. our panel is the background, vanilla draws + syncs the slots on top.
// non-slot widgets (buttons/sliders/inputs) still work through KuiWidgets.
@Environment(EnvType.CLIENT)
public class KuiMenuScreen extends AbstractContainerScreen<KuiMenu> {

    private final KuiMenuData data;
    private final KuiWidgets widgets;
    private final boolean textureMode;
    private Identifier texId;

    public KuiMenuScreen(KuiMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, menu.data.w(), menu.data.h());
        this.data = menu.data;
        this.textureMode = data.isTexture();
        var els = KuiLayout.parse(data.layout());
        this.widgets = new KuiWidgets(data.id(), els);
        this.widgets.applyState(data.state());
        if (textureMode && data.png() != null && data.png().length > 0) this.texId = uploadTexture(data.png());
    }

    private Identifier uploadTexture(byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "kui/" + data.id().replace(':', '_'));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "kui/" + data.id(), img));
            return id;
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Kui] menu texture upload failed for {}: {}", data.id(), e.getMessage());
            return null;
        }
    }

    @Override
    protected void init() {
        super.init();
        widgets.layout(leftPos, topPos);
    }

    // our panel + widgets are the background; vanilla paints slot contents in a later stratum
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        KuiElement tip = null;
        KuiPaint.beginFrame(mouseX, mouseY);
        if (textureMode) {
            if (texId != null)
                g.blit(RenderPipelines.GUI_TEXTURED, texId, leftPos, topPos, 0f, 0f, data.w(), data.h(), data.w(), data.h());
            else
                KuiPaint.panel(g, leftPos, topPos, data.w(), data.h());
            g.enableScissor(leftPos + 2, topPos + 2, leftPos + data.w() - 2, topPos + data.h() - 2);
            for (KuiElement e : widgets.elements()) {
                if (e.type.equals("player_inv")) KuiPaint.playerInv(g, e.absX, e.absY);
                else KuiPaint.overlay(g, this.font, e, mouseX, mouseY);
                if (!e.tooltip.isEmpty() && e.hit(mouseX, mouseY)) tip = e;
            }
        } else {
            KuiPaint.panel(g, leftPos, topPos, data.w(), data.h());
            g.enableScissor(leftPos + 2, topPos + 2, leftPos + data.w() - 2, topPos + data.h() - 2);
            KuiPaint.centeredClippedText(g, this.font, this.title.getString(),
                leftPos + 18, topPos + 6, data.w() - 36, KuiPaint.TITLE);
            for (KuiElement e : widgets.elements()) {
                KuiPaint.widget(g, this.font, e, mouseX, mouseY);
                if (!e.tooltip.isEmpty() && e.hit(mouseX, mouseY)) tip = e;
            }
        }
        boolean themeHover = themeHit(mouseX, mouseY);
        KuiPaint.themeChip(g, leftPos + data.w() - 15, topPos + 4, themeHover);
        g.disableScissor();
        if (themeHover)
            g.setTooltipForNextFrame(this.font,
                Component.literal("Theme: " + KuiThemes.displayName() + " (click to change)"), mouseX, mouseY);
        else if (tip != null)
            g.setTooltipForNextFrame(this.font.split(Component.literal(tip.tooltip), 240), mouseX, mouseY);
        else if (KuiPaint.clippedHover() != null)
            g.setTooltipForNextFrame(this.font.split(Component.literal(KuiPaint.clippedHover()), 240), mouseX, mouseY);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        // suppress vanilla's "Container"/"Inventory" text — our panel owns the title
    }

    @Override public boolean mouseClicked(MouseButtonEvent ev, boolean dbl) {
        if (themeHit(ev.x(), ev.y())) {
            KuiThemes.cycle();
            return true;
        }
        return widgets.mouseClicked(ev.x(), ev.y()) || super.mouseClicked(ev, dbl);
    }
    @Override public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) { return widgets.mouseDragged(ev.x()) || super.mouseDragged(ev, dx, dy); }
    @Override public boolean mouseReleased(MouseButtonEvent ev) { return widgets.mouseReleased() || super.mouseReleased(ev); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        return widgets.mouseScrolled(mx, my, sy) || super.mouseScrolled(mx, my, sx, sy);
    }
    @Override public boolean keyPressed(KeyEvent ev) { return widgets.keyPressed(ev.key()) || super.keyPressed(ev); }
    @Override public boolean charTyped(CharacterEvent ev) { return widgets.charTyped(ev.codepoint()) || super.charTyped(ev); }

    public void updateWidget(String widget, String value) { widgets.update(widget, value); }

    private boolean themeHit(double x, double y) {
        int tx = leftPos + data.w() - 15, ty = topPos + 4;
        return x >= tx && x < tx + 12 && y >= ty && y < ty + 10;
    }

    @Override
    public void removed() {
        widgets.onClosed();
        if (texId != null) Minecraft.getInstance().getTextureManager().release(texId);
        super.removed();
    }
}
