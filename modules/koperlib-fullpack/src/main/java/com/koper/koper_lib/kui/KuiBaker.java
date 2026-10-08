package com.koper.koper_lib.kui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.KoperLibDirectories;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// turns a json-mode layout into an editable png (same mc bevel as the live screen) + a regions.json
// of the clickable boxes. only re-bakes when the layout actually changed; if it has to overwrite a png
// you hand-edited, the old one is parked in gui/backup/ first. so editing the png and reloading keeps it.
public final class KuiBaker {
    private KuiBaker() {}

    private static final String STYLE = "kui-copper-dark-1";
    private static final Color BLACK = argb(0xFF090B10), GRAY = argb(0xFF202631), LIGHT = argb(0xFF465164),
        DARK = argb(0xFF11151C), SLOT = argb(0xFF111720), TITLE = argb(0xFFE7EBF2),
        MUTED = argb(0xFF8E98AA), TRACK = argb(0xFF0C1016), BAR = argb(0xFFE39A4A);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // walk every registered json-mode gui and bake the ones whose layout changed (or are missing a png)
    public static void syncAll(boolean force) {
        JsonObject cache = loadCache();
        int baked = 0, skipped = 0;
        for (KuiPage page : KuiBook.all()) {
            if (page.isTexture() || page.layoutFile == null) continue;
            try {
                if (syncOne(page, cache, force)) baked++; else skipped++;
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[Kui] bake failed for {}: {}", page.id, e.getMessage());
            }
        }
        saveCache(cache);
        if (baked > 0) KoperLib.LOGGER.info("[Kui] baked {} gui texture(s), {} unchanged", baked, skipped);
    }

    private static boolean syncOne(KuiPage page, JsonObject cache, boolean force) throws Exception {
        File layoutFile = new File(page.layoutFile);
        if (!layoutFile.exists()) return false;
        File guiDir = layoutFile.getParentFile();
        File packDir = guiDir.getParentFile();
        String base = layoutFile.getName().replace(".layout.json", "");
        String key = packDir.getName() + "/" + base;

        String layoutText = Files.readString(layoutFile.toPath());
        String layoutHash = sha1((STYLE + "\n" + layoutText).getBytes(StandardCharsets.UTF_8));

        File pngOut = new File(packDir, "textures/gui/" + base + ".png");
        JsonObject entry = cache.has(key) ? cache.getAsJsonObject(key) : null;
        String cachedLayout = entry != null && entry.has("layout") ? entry.get("layout").getAsString() : null;
        String cachedPng    = entry != null && entry.has("png")    ? entry.get("png").getAsString()    : null;

        boolean needBake = force || cachedLayout == null || !cachedLayout.equals(layoutHash) || !pngOut.exists();
        if (!needBake) return false;

        // png on disk differs from what we last baked => someone painted on it. don't nuke it, stash a copy
        if (pngOut.exists() && cachedPng != null) {
            String diskHash = sha1(Files.readAllBytes(pngOut.toPath()));
            if (!diskHash.equals(cachedPng)) {
                File backupDir = new File(guiDir, "backup");
                backupDir.mkdirs();
                File bak = new File(backupDir, base + "_" + LocalDateTime.now().format(STAMP) + ".png");
                Files.copy(pngOut.toPath(), bak.toPath());
                KoperLib.LOGGER.info("[Kui] {} was hand-edited — backed up to {}", base, bak.getName());
            }
        }

        List<KuiElement> els = KuiLayout.parse(layoutText);
        BufferedImage img = render(page, els);
        pngOut.getParentFile().mkdirs();
        ImageIO.write(img, "png", pngOut);
        writeRegions(new File(guiDir, base + ".regions.json"), els);

        String newPngHash = sha1(Files.readAllBytes(pngOut.toPath()));
        JsonObject ne = new JsonObject();
        ne.addProperty("layout", layoutHash);
        ne.addProperty("png", newPngHash);
        cache.add(key, ne);
        KoperLib.LOGGER.info("[Kui] baked {} ({}x{})", pngOut.getName(), page.w, page.h);
        return true;
    }

    // ── raster ───────────────────────────────────────────────────────────────

    private static BufferedImage render(KuiPage page, List<KuiElement> els) {
        BufferedImage img = new BufferedImage(page.w, page.h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(new Font("Monospaced", Font.PLAIN, 8));

        drawPanel(g, page.w, page.h);
        centered(g, page.title, page.w / 2, 6, TITLE);

        g.clipRect(2, 2, Math.max(0, page.w - 4), Math.max(0, page.h - 4));
        for (KuiElement e : els) draw(g, e);
        g.dispose();
        return img;
    }

    private static void draw(Graphics2D g, KuiElement e) {
        switch (e.type) {
            case "label" -> text(g, e.text, e.x, e.y, argb(e.color));
            case "panel" -> raised(g, e.x, e.y, e.w, e.h, GRAY);
            case "slot"  -> inset(g, e.x, e.y, 18, 18, SLOT);
            case "grid"  -> {
                int pitch = 18 + e.gap;
                for (int r = 0; r < e.rows; r++)
                    for (int c = 0; c < e.cols; c++)
                        inset(g, e.x + c * pitch, e.y + r * pitch, 18, 18, SLOT);
            }
            case "button" -> { raised(g, e.x, e.y, e.w, e.h, GRAY); centered(g, e.text, e.x + e.w / 2, e.y + (e.h - 8) / 2, TITLE); }
            case "progress" -> {
                inset(g, e.x, e.y, e.w, e.h, TRACK);
                int fill = Math.round(clamp(e.value) * (e.w - 2));
                if (fill > 0) { g.setColor(BAR); g.fillRect(e.x + 1, e.y + 1, fill, e.h - 2); }
            }
            case "toggle" -> {
                int box = Math.min(12, e.h);
                inset(g, e.x, e.y + (e.h - box) / 2, box, box, SLOT);
                if (!e.text.isEmpty()) text(g, e.text, e.x + box + 5, e.y + (e.h - 8) / 2, TITLE);
            }
            case "slider" -> {
                raised(g, e.x, e.y, e.w, e.h, GRAY);
                fill(g, e.x + 1, e.y + e.h - 3, e.w - 2, 2, TRACK);
            }
            case "selector", "segmented" -> {
                int n = Math.max(1, e.options.size());
                int segW = Math.max(1, e.w / n);
                for (int i = 0; i < n; i++) {
                    int x0 = e.x + i * segW;
                    int x1 = i == n - 1 ? e.x + e.w : x0 + segW;
                    raised(g, x0, e.y, x1 - x0, e.h, i == 0 ? argb(0xFF3A2C20) : GRAY);
                    if (i == 0) fill(g, x0 + 1, e.y + e.h - 2, x1 - x0 - 2, 1, BAR);
                    if (i < e.options.size()) centered(g, e.options.get(i), (x0 + x1) / 2, e.y + (e.h - 8) / 2, TITLE);
                }
            }
            case "radio", "list" -> {
                int n = Math.max(1, e.options.size());
                int rowH = Math.max(12, e.h / n);
                for (int i = 0; i < n; i++) {
                    int y = e.y + i * rowH;
                    if (e.type.equals("radio")) {
                        inset(g, e.x, y + 1, 10, 10, SLOT);
                        if (i < e.options.size()) text(g, e.options.get(i), e.x + 14, y + 2, TITLE);
                    } else {
                        raised(g, e.x, y, e.w, rowH, GRAY);
                        if (i < e.options.size()) text(g, e.options.get(i), e.x + 4, y + (rowH - 8) / 2, TITLE);
                    }
                }
            }
            case "input" -> {
                g.setColor(LIGHT); g.fillRect(e.x, e.y, e.w, e.h);
                g.setColor(TRACK); g.fillRect(e.x + 1, e.y + 1, e.w - 2, e.h - 2);
                String shown = e.inputText.isEmpty() ? e.placeholder : e.inputText;
                text(g, shown, e.x + 4, e.y + (e.h - 8) / 2, e.inputText.isEmpty() ? MUTED : TITLE);
            }
            case "player_inv" -> {
                for (int r = 0; r < 3; r++)
                    for (int c = 0; c < 9; c++) inset(g, e.x + c * 18, e.y + r * 18, 18, 18, SLOT);
                for (int c = 0; c < 9; c++) inset(g, e.x + c * 18, e.y + 58, 18, 18, SLOT);
            }
            default -> {}
        }
    }

    private static void drawPanel(Graphics2D g, int w, int h) {
        fill(g, 0, 0, w, h, BLACK);
        fill(g, 1, 1, w - 2, h - 2, argb(0xFF181D26));
        fill(g, 1, 1, w - 2, 1, LIGHT);
        fill(g, 2, 2, w - 4, 1, argb(0xFF252C38));
        fill(g, 1, h - 2, w - 2, 1, DARK);
        fill(g, w - 2, 1, 1, h - 2, DARK);
    }

    private static void raised(Graphics2D g, int x, int y, int w, int h, Color fill) {
        fill(g, x, y, w, h, fill);
        fill(g, x, y, w, 1, LIGHT);
        fill(g, x, y, 1, h, LIGHT);
        fill(g, x, y + h - 1, w, 1, DARK);
        fill(g, x + w - 1, y, 1, h, DARK);
    }

    private static void inset(Graphics2D g, int x, int y, int w, int h, Color fill) {
        fill(g, x, y, w, h, fill);
        fill(g, x, y, w, 1, DARK);
        fill(g, x, y, 1, h, DARK);
        fill(g, x, y + h - 1, w, 1, LIGHT);
        fill(g, x + w - 1, y, 1, h, LIGHT);
    }

    private static void fill(Graphics2D g, int x, int y, int w, int h, Color c) {
        if (w <= 0 || h <= 0) return;
        g.setColor(c);
        g.fillRect(x, y, w, h);
    }

    // mc text is top-anchored; awt drawString is baseline-anchored, so shift by ascent
    private static void text(Graphics2D g, String s, int x, int y, Color c) {
        if (s == null || s.isEmpty()) return;
        g.setColor(c);
        g.drawString(s, x, y + g.getFontMetrics().getAscent());
    }

    private static void centered(Graphics2D g, String s, int cx, int y, Color c) {
        if (s == null || s.isEmpty()) return;
        text(g, s, cx - g.getFontMetrics().stringWidth(s) / 2, y, c);
    }

    private static void writeRegions(File out, List<KuiElement> els) throws Exception {
        JsonArray arr = new JsonArray();
        for (KuiElement e : els) {
            if (!e.type.equals("button") && !e.type.equals("toggle")
                && !e.type.equals("slider") && !e.type.equals("input")
                && !e.type.equals("selector") && !e.type.equals("segmented")
                && !e.type.equals("radio") && !e.type.equals("list")) continue;
            JsonObject r = new JsonObject();
            r.addProperty("id", e.id);
            r.addProperty("type", e.type);
            r.addProperty("x", e.x); r.addProperty("y", e.y);
            r.addProperty("w", e.w); r.addProperty("h", e.h);
            if (!e.tooltip.isEmpty()) r.addProperty("tooltip", e.tooltip);
            if (!e.options.isEmpty()) {
                JsonArray opts = new JsonArray();
                for (String opt : e.options) opts.add(opt);
                r.add("options", opts);
            }
            if (!e.selected.isEmpty()) r.addProperty("selected", e.selected);
            arr.add(r);
        }
        JsonObject root = new JsonObject();
        root.add("regions", arr);
        Files.writeString(out.toPath(), GSON.toJson(root));
    }

    // ── cache ──────────────────────────────────────────────────────────────────

    private static File cacheFile() { return KoperLibDirectories.ROOT.resolve(".kui_cache.json").toFile(); }

    private static JsonObject loadCache() {
        File f = cacheFile();
        if (!f.exists()) return new JsonObject();
        try {
            JsonObject o = GSON.fromJson(Files.readString(f.toPath()), JsonObject.class);
            return o != null ? o : new JsonObject();
        } catch (Exception e) { return new JsonObject(); }
    }

    private static void saveCache(JsonObject cache) {
        try { Files.writeString(cacheFile().toPath(), GSON.toJson(cache)); }
        catch (Exception e) { KoperLib.LOGGER.warn("[Kui] cache save failed: {}", e.getMessage()); }
    }

    // ── util ───────────────────────────────────────────────────────────────────

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static Color argb(int c) {
        return new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF);
    }

    private static String sha1(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
