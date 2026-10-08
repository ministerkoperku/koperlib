package com.koper.koper_lib.kui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

// shared procedural skin for plain screens and container screens.
@Environment(EnvType.CLIENT)
public final class KuiPaint {
    private KuiPaint() {}

    public static int BLACK = 0xFF090B10, PANEL = 0xFF181D26, PANEL_TOP = 0xFF252C38,
        GRAY = 0xFF202631, LIGHT = 0xFF465164, DARK = 0xFF11151C,
        SLOT = 0xFF111720, TITLE = 0xFFE7EBF2, MUTED = 0xFF8E98AA, HOVER = 0xFF2C3543,
        BAR = 0xFFE39A4A, BAR_HOVER = 0xFFFFB767, TRACK = 0xFF0C1016, SELECTED = 0xFF3A2C20,
        GHOST = 0x60202020;

    static void applyPalette(KuiThemes.Palette palette) {
        BLACK = palette.black();
        PANEL = palette.panel();
        PANEL_TOP = palette.panelTop();
        GRAY = palette.gray();
        LIGHT = palette.light();
        DARK = palette.dark();
        SLOT = palette.slot();
        TITLE = palette.title();
        MUTED = palette.muted();
        HOVER = palette.hover();
        BAR = palette.bar();
        BAR_HOVER = palette.barHover();
        TRACK = palette.track();
        SELECTED = palette.selected();
        GHOST = palette.ghost();
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, BLACK);
        if (w <= 2 || h <= 2) return;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, LIGHT);
        g.fill(x + 2, y + 2, x + w - 2, y + 3, PANEL_TOP);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, DARK);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, DARK);
    }

    // json-mode: draw the full widget at its absolute box
    public static void widget(GuiGraphicsExtractor g, Font font, KuiElement e, int mx, int my) {
        switch (e.type) {
            case "label"    -> {
                if (e.absW <= 0) { g.text(font, e.text, e.absX, e.absY, e.color); break; }
                switch (e.align) {
                    case "center" -> centeredClippedText(g, font, e.text, e.absX, e.absY, e.absW, e.color);
                    case "right" -> {
                        int slack = Math.max(0, e.absW - font.width(Component.literal(e.text)));
                        clippedText(g, font, e.text, e.absX + slack, e.absY, e.absW - slack, e.color);
                    }
                    default -> clippedText(g, font, e.text, e.absX, e.absY, e.absW, e.color);
                }
            }
            case "panel"    -> raised(g, e.absX, e.absY, e.absW, e.absH, e.tinted ? e.color : GRAY);
            // a plain line. the thing every layout wants and nobody had, so people faked it with panels
            case "rule"     -> {
                int thickness = Math.max(1, e.absH > e.absW ? e.absW : e.absH);
                g.fill(e.absX, e.absY, e.absX + Math.max(thickness, e.absW), e.absY + Math.max(thickness, e.absH),
                    e.tinted ? e.color : LIGHT);
            }
            case "slot"     -> inset(g, e.absX, e.absY, 18, 18, SLOT);
            case "grid"     -> {
                int pitch = 18 + e.gap;
                for (int r = 0; r < e.rows; r++)
                    for (int c = 0; c < e.cols; c++)
                        inset(g, e.absX + c * pitch, e.absY + r * pitch, 18, 18, SLOT);
            }
            case "button"   -> {
                boolean hover = e.hit(mx, my);
                int face = e.tinted ? (hover ? lift(e.color) : e.color) : (hover ? HOVER : GRAY);
                raised(g, e.absX, e.absY, e.absW, e.absH, face);
                if (hover) g.outline(e.absX, e.absY, e.absW, e.absH, BAR_HOVER);
                centeredClippedText(g, font, e.text, e.absX, e.absY + (e.absH - 8) / 2,
                    e.absW, hover ? BAR_HOVER : TITLE);
            }
            case "progress" -> {
                inset(g, e.absX, e.absY, e.absW, e.absH, TRACK);
                float v = e.anim.equals("auto") ? autoCycle(e) : e.value;
                int fill = Math.round(Mth.clamp(v, 0f, 1f) * (e.absW - 2));
                if (fill > 0) g.fill(e.absX + 1, e.absY + 1, e.absX + 1 + fill, e.absY + e.absH - 1, BAR);
            }
            case "toggle"   -> {
                int box = Math.min(12, e.absH);
                inset(g, e.absX, e.absY + (e.absH - box) / 2, box, box, SLOT);
                if (e.checked) {
                    int by = e.absY + (e.absH - box) / 2;
                    g.fill(e.absX + 3, by + 3, e.absX + box - 2, by + box - 2, BAR);
                }
                if (!e.text.isEmpty())
                    clippedText(g, font, e.text, e.absX + box + 5, e.absY + (e.absH - 8) / 2,
                        e.absW - box - 5, TITLE);
            }
            case "slider"   -> {
                raised(g, e.absX, e.absY, e.absW, e.absH, GRAY);
                int trackY = e.absY + e.absH - 3;
                g.fill(e.absX + 1, trackY, e.absX + e.absW - 1, trackY + 2, TRACK);
                int progress = Math.round(Mth.clamp(e.value, 0f, 1f) * Math.max(0, e.absW - 2));
                if (progress > 0) g.fill(e.absX + 1, trackY, e.absX + 1 + progress, trackY + 2, BAR);
                int knobX = e.absX + Math.round(Mth.clamp(e.value, 0f, 1f) * (e.absW - 8));
                g.fill(knobX + 3, e.absY + 1, knobX + 5, e.absY + e.absH - 1, BAR_HOVER);
                String lbl = (e.text.isEmpty() ? "" : e.text + ": ") + sliderText(e);
                centeredClippedText(g, font, lbl, e.absX, e.absY + Math.max(1, (e.absH - 10) / 2),
                    e.absW, TITLE);
            }
            case "selector", "segmented" -> segmented(g, font, e, mx, my);
            case "radio", "list" -> optionList(g, font, e, mx, my);
            case "input"    -> {
                g.fill(e.absX, e.absY, e.absX + e.absW, e.absY + e.absH, e.focused ? BAR_HOVER : LIGHT);
                g.fill(e.absX + 1, e.absY + 1, e.absX + e.absW - 1, e.absY + e.absH - 1, TRACK);
                boolean empty = e.inputText.isEmpty();
                int ty = e.absY + (e.absH - 8) / 2;
                int textW = Math.max(0, e.absW - 8);
                String shown = empty ? fitHead(font, e.placeholder, textW)
                    : e.focused ? fitTail(font, e.inputText, textW) : fitHead(font, e.inputText, textW);
                g.text(font, shown, e.absX + 4, ty, empty ? MUTED : TITLE);
                caret(g, font, e, shown, ty);
            }
            case "image" -> {
                Identifier tex = resolveTex(currentFrame(e));
                if (tex != null) g.blit(tex, e.absX, e.absY, e.absX + e.absW, e.absY + e.absH, 0f, 1f, 0f, 1f);
            }
            case "entity" -> KuiPreview.render(g, e, mx, my);
            case "player_inv" -> playerInv(g, e.absX, e.absY);
            case "item" -> {
                var id = net.minecraft.resources.Identifier.tryParse(e.text);
                var it = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                if (it != null) g.item(new net.minecraft.world.item.ItemStack(it), e.absX, e.absY);
            }
            case "ghost" -> {
                // "put this here" hint: slot frame + a dimmed example item, can't be interacted with
                inset(g, e.absX, e.absY, 18, 18, SLOT);
                var id = net.minecraft.resources.Identifier.tryParse(e.text);
                var it = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
                if (it != null) {
                    g.item(new net.minecraft.world.item.ItemStack(it), e.absX + 1, e.absY + 1);
                    g.fill(e.absX + 1, e.absY + 1, e.absX + 17, e.absY + 17, GHOST); // dim overlay = ghost
                }
            }
            default -> g.text(font, "?" + e.type, e.absX, e.absY, 0xFFFF5555);
        }
    }

    // texture-mode: only the live bits, drawn on top of the baked/custom png
    public static void overlay(GuiGraphicsExtractor g, Font font, KuiElement e, int mx, int my) {
        switch (e.type) {
            case "button" -> { if (e.hit(mx, my)) g.outline(e.absX, e.absY, e.absW, e.absH, BAR_HOVER); }
            case "toggle" -> {
                if (e.checked) {
                    int box = Math.min(12, e.absH);
                    int by = e.absY + (e.absH - box) / 2;
                    g.fill(e.absX + 3, by + 3, e.absX + box - 2, by + box - 2, BAR);
                }
            }
            case "slider" -> {
                int knobX = e.absX + Math.round(Mth.clamp(e.value, 0f, 1f) * (e.absW - 8));
                g.fill(knobX + 3, e.absY + 1, knobX + 5, e.absY + e.absH - 1, BAR_HOVER);
                centeredClippedText(g, font, sliderText(e),
                    e.absX, e.absY + Math.max(1, (e.absH - 10) / 2), e.absW, TITLE);
            }
            case "selector", "segmented" -> segmented(g, font, e, mx, my);
            case "radio", "list" -> optionList(g, font, e, mx, my);
            case "input" -> {
                if (e.focused || !e.inputText.isEmpty()) {
                    g.fill(e.absX + 1, e.absY + 1, e.absX + e.absW - 1, e.absY + e.absH - 1, BLACK);
                    int ty = e.absY + (e.absH - 8) / 2;
                    String shown = e.focused ? fitTail(font, e.inputText, Math.max(0, e.absW - 8))
                        : fitHead(font, e.inputText, Math.max(0, e.absW - 8));
                    g.text(font, shown, e.absX + 4, ty, TITLE);
                    caret(g, font, e, shown, ty);
                }
            }
            default -> {}
        }
    }

    private static void caret(GuiGraphicsExtractor g, Font font, KuiElement e, String shown, int ty) {
        if (e.focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = Math.min(e.absX + e.absW - 3, e.absX + 4 + font.width(shown));
            g.fill(cx, ty - 1, cx + 1, ty + 9, BAR_HOVER);
        }
    }

    // under this many pixels a segment shows "..." and nothing else, which is useless
    public static final int SEG_MIN = 34;

    public static boolean cramped(KuiElement e) {
        return !e.options.isEmpty() && e.absW / e.options.size() < SEG_MIN;
    }

    private static void segmented(GuiGraphicsExtractor g, Font font, KuiElement e, int mx, int my) {
        if (e.options.isEmpty()) return;
        if (cramped(e)) { cycler(g, font, e, mx, my); return; }
        int n = e.options.size();
        int segW = Math.max(1, e.absW / n);
        for (int i = 0; i < n; i++) {
            int x0 = e.absX + i * segW;
            int x1 = i == n - 1 ? e.absX + e.absW : x0 + segW;
            String opt = e.options.get(i);
            boolean sel = opt.equals(e.selected) || (e.selected.isEmpty() && i == 0);
            boolean hover = mx >= x0 && mx < x1 && my >= e.absY && my < e.absY + e.absH;
            raised(g, x0, e.absY, x1 - x0, e.absH, sel ? SELECTED : hover ? HOVER : GRAY);
            if (sel) g.fill(x0 + 1, e.absY + e.absH - 2, x1 - 1, e.absY + e.absH - 1, BAR);
            centeredClippedText(g, font, opt, x0, e.absY + (e.absH - 8) / 2,
                x1 - x0, sel ? BAR_HOVER : TITLE);
        }
    }

    // too many options for the width: show one at a time with arrows, so the label stays readable
    private static void cycler(GuiGraphicsExtractor g, Font font, KuiElement e, int mx, int my) {
        boolean hover = e.hit(mx, my);
        raised(g, e.absX, e.absY, e.absW, e.absH, hover ? HOVER : GRAY);

        int arrow = 10;
        boolean onLeft = hover && mx < e.absX + arrow;
        boolean onRight = hover && mx >= e.absX + e.absW - arrow;
        int ty = e.absY + (e.absH - 8) / 2;

        g.text(font, "<", e.absX + 3, ty, onLeft ? BAR_HOVER : MUTED);
        g.text(font, ">", e.absX + e.absW - 7, ty, onRight ? BAR_HOVER : MUTED);

        String shown = e.selected.isEmpty() ? e.options.getFirst() : e.selected;
        int index = Math.max(0, e.options.indexOf(shown));
        centeredClippedText(g, font, shown, e.absX + arrow, ty, e.absW - arrow * 2, BAR_HOVER);
        g.fill(e.absX + 1, e.absY + e.absH - 2, e.absX + e.absW - 1, e.absY + e.absH - 1, BAR);

        String counter = (index + 1) + "/" + e.options.size();
        g.text(font, counter, e.absX + e.absW - 7 - font.width(Component.literal(counter)) - 2,
            e.absY + e.absH - 9, MUTED);
    }

    private static void optionList(GuiGraphicsExtractor g, Font font, KuiElement e, int mx, int my) {
        if (e.options.isEmpty()) return;
        boolean scrolling = e.type.equals("list");
        int rowH = scrolling ? 14 : Math.max(12, e.absH / e.options.size());
        int visible = scrolling ? Math.max(1, e.absH / rowH) : e.options.size();
        int start = scrolling ? Mth.clamp(e.scroll, 0, Math.max(0, e.options.size() - visible)) : 0;
        int end = Math.min(e.options.size(), start + visible);
        if (scrolling) g.enableScissor(e.absX, e.absY, e.absX + e.absW, e.absY + e.absH);
        for (int i = start; i < end; i++) {
            int y = e.absY + (i - start) * rowH;
            String opt = e.options.get(i);
            boolean sel = opt.equals(e.selected) || (e.selected.isEmpty() && i == 0);
            boolean hover = mx >= e.absX && mx < e.absX + e.absW && my >= y && my < y + rowH;
            if (e.type.equals("radio")) {
                inset(g, e.absX, y + 1, 10, 10, SLOT);
                if (sel) g.fill(e.absX + 3, y + 4, e.absX + 7, y + 8, BAR);
                clippedText(g, font, opt, e.absX + 14, y + 2, e.absW - 14,
                    hover ? BAR_HOVER : TITLE);
            } else {
                raised(g, e.absX, y, e.absW, rowH, sel ? SELECTED : hover ? HOVER : GRAY);
                if (sel) g.fill(e.absX + 1, y + 1, e.absX + 3, y + rowH - 1, BAR);
                clippedText(g, font, opt, e.absX + 6, y + (rowH - 8) / 2,
                    e.absW - 10, sel ? BAR_HOVER : TITLE);
            }
        }
        if (scrolling) {
            if (e.options.size() > visible) {
                int thumbH = Math.max(8, e.absH * visible / e.options.size());
                int travel = Math.max(1, e.absH - thumbH);
                int maxStart = Math.max(1, e.options.size() - visible);
                int thumbY = e.absY + travel * start / maxStart;
                g.fill(e.absX + e.absW - 2, thumbY, e.absX + e.absW - 1, thumbY + thumbH, BAR);
            }
            g.disableScissor();
        }
    }

    // whatever got shortened under the mouse this frame, so the screen can show it whole on hover.
    // reset by beginFrame; a screen that never calls it simply never reads it
    private static int frameMouseX = Integer.MIN_VALUE, frameMouseY = Integer.MIN_VALUE;
    private static String clippedHover;

    public static void beginFrame(int mouseX, int mouseY) {
        frameMouseX = mouseX;
        frameMouseY = mouseY;
        clippedHover = null;
    }

    /** The full text of whatever was drawn shortened under the mouse this frame, or null. */
    public static String clippedHover() {
        return clippedHover;
    }

    private static void noteClipped(String text, String shown, int x, int y, int w) {
        if (shown.equals(text)) return;
        if (frameMouseX >= x && frameMouseX < x + w && frameMouseY >= y - 1 && frameMouseY < y + 9)
            clippedHover = text;
    }

    public static void clippedText(GuiGraphicsExtractor g, Font font, String text,
                                   int x, int y, int maxWidth, int color) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        String shown = fitHead(font, text, maxWidth);
        g.text(font, shown, x, y, color);
        noteClipped(text, shown, x, y, maxWidth);
    }

    public static void centeredClippedText(GuiGraphicsExtractor g, Font font, String text,
                                           int x, int y, int width, int color) {
        if (text == null || text.isEmpty() || width <= 4) return;
        String shown = fitHead(font, text, width - 8);
        g.centeredText(font, Component.literal(shown), x + width / 2, y, color);
        noteClipped(text, shown, x, y, width);
    }

    public static void themeChip(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
        inset(g, x, y, 12, 10, TRACK);
        g.fill(x + 3, y + 3, x + 9, y + 7, BAR);
        if (hovered) g.outline(x, y, 12, 10, BAR_HOVER);
    }

    private static String fitHead(Font font, String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String dots = maxWidth >= font.width("...") ? "..." : "";
        int room = Math.max(0, maxWidth - font.width(dots));
        // longest prefix that fits, measured on the raw string so colour codes survive the cut
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (font.width(text.substring(0, mid)) <= room) lo = mid;
            else hi = mid - 1;
        }
        if (lo > 0 && lo < text.length() && Character.isHighSurrogate(text.charAt(lo - 1))) lo--;
        if (lo > 0 && text.charAt(lo - 1) == '\u00a7') lo--;
        String head = text.substring(0, lo);
        // stop at the last whole word, unless that throws away more than half of what fits
        int space = head.lastIndexOf(' ');
        if (space > head.length() / 2 && lo < text.length() && text.charAt(lo) != ' ')
            head = head.substring(0, space);
        return head.stripTrailing() + dots;
    }

    private static String fitTail(Font font, String text, int maxWidth) {
        if (text == null || maxWidth <= 0 || font.width(text) <= maxWidth) return text == null ? "" : text;
        int start = text.length();
        while (start > 0) {
            int next = text.offsetByCodePoints(start, -1);
            if (font.width(text.substring(next)) > maxWidth) break;
            start = next;
        }
        return text.substring(start);
    }

    // standard player inventory frame: 3 rows of 9 + a hotbar row 4px lower (matches addStandardInventorySlots)
    public static void playerInv(GuiGraphicsExtractor g, int x, int y) {
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                inset(g, x + c * 18, y + r * 18, 18, 18, SLOT);
        for (int c = 0; c < 9; c++)
            inset(g, x + c * 18, y + 58, 18, 18, SLOT);
    }

    // 0..1 looping fill, period = fps seconds (default 3) — furnace-style auto progress
    private static float autoCycle(KuiElement e) {
        long per = (e.fps > 0 ? e.fps : 3) * 1000L;
        return (System.currentTimeMillis() % per) / (float) per;
    }

    // current frame texture id: cycle e.frames at fps, else the single image in e.text
    private static String currentFrame(KuiElement e) {
        if (e.frames.isEmpty()) return e.text;
        int fps = e.fps > 0 ? e.fps : 8;
        int i = (int) ((System.currentTimeMillis() * fps / 1000L) % e.frames.size());
        return e.frames.get(i);
    }

    // "ns:gui/orb" -> ns:textures/gui/orb.png ; pass-through if already a textures/...png path
    private static Identifier resolveTex(String s) {
        if (s == null || s.isBlank()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        int c = s.indexOf(':');
        String ns = s.substring(0, c), p = s.substring(c + 1);
        if (!p.startsWith("textures/")) p = "textures/" + p;
        if (!p.endsWith(".png")) p = p + ".png";
        return Identifier.fromNamespaceAndPath(ns, p);
    }

    // no range = the old percent readout, with a range it shows the number the author asked for
    static String sliderText(KuiElement e) {
        if (!(e.max > e.min)) return Math.round(e.value * 100) + "%";
        float v = e.min + Mth.clamp(e.value, 0f, 1f) * (e.max - e.min);
        if (e.step > 0f && e.step == Math.floor(e.step)) return Integer.toString(Math.round(v));
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // hover shade for a tinted button — just walk each channel toward white a bit
    private static int lift(int argb) {
        int a = argb >>> 24;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28);
        int gr = Math.min(255, ((argb >> 8) & 0xFF) + 28);
        int b = Math.min(255, (argb & 0xFF) + 28);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    public static void raised(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, fill);
        if (w <= 1 || h <= 1) return;
        g.fill(x, y, x + w, y + 1, LIGHT);
        g.fill(x, y, x + 1, y + h, LIGHT);
        g.fill(x, y + h - 1, x + w, y + h, DARK);
        g.fill(x + w - 1, y, x + w, y + h, DARK);
    }

    public static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, fill);
        if (w <= 1 || h <= 1) return;
        g.fill(x, y, x + w, y + 1, DARK);
        g.fill(x, y, x + 1, y + h, DARK);
        g.fill(x, y + h - 1, x + w, y + h, LIGHT);
        g.fill(x + w - 1, y, x + w, y + h, LIGHT);
    }
}
