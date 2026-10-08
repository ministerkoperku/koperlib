package com.koper.koper_lib.kui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.FullPackLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class KuiThemes {
    public record Palette(
        int black, int panel, int panelTop, int gray, int light, int dark, int slot,
        int title, int muted, int hover, int bar, int barHover, int track, int selected, int ghost
    ) {}

    private static final Map<String, Palette> THEMES = new LinkedHashMap<>();
    private static String current = "copper";
    private static boolean initialized;

    static {
        register("copper", new Palette(
            0xFF090B10, 0xFF181D26, 0xFF252C38, 0xFF202631, 0xFF465164, 0xFF11151C,
            0xFF111720, 0xFFE7EBF2, 0xFF8E98AA, 0xFF2C3543, 0xFFE39A4A, 0xFFFFB767,
            0xFF0C1016, 0xFF3A2C20, 0x60202020));
        register("dark", new Palette(
            0xFF070A10, 0xFF111824, 0xFF1D2A3A, 0xFF1A2635, 0xFF415A72, 0xFF0B1018,
            0xFF0C1420, 0xFFEAF3FF, 0xFF8FA2B7, 0xFF263A50, 0xFF4E9FE6, 0xFF75C5FF,
            0xFF080D14, 0xFF173753, 0x600C1620));
        register("light", new Palette(
            0xFF656B74, 0xFFE7E9ED, 0xFFFFFFFF, 0xFFD2D6DC, 0xFFF7F8FA, 0xFF9EA5AE,
            0xFFC6CBD2, 0xFF171A1F, 0xFF565E69, 0xFFB9D8EF, 0xFF2579B8, 0xFF075D9B,
            0xFFAEB4BC, 0xFFABD5F0, 0x40FFFFFF));
        register("classic", new Palette(
            0xFF252525, 0xFFC6C6C6, 0xFFFFFFFF, 0xFF8B8B8B, 0xFFFFFFFF, 0xFF555555,
            0xFF8B8B8B, 0xFF202020, 0xFF555555, 0xFFAAAAAA, 0xFF55AA55, 0xFFFFFF55,
            0xFF373737, 0xFF7EAA7E, 0x50000000));
        register("high_contrast", new Palette(
            0xFF000000, 0xFF050505, 0xFF333333, 0xFF111111, 0xFFFFFFFF, 0xFF000000,
            0xFF000000, 0xFFFFFFFF, 0xFFDDDDDD, 0xFF242424, 0xFFFFFF00, 0xFFFFFFFF,
            0xFF000000, 0xFF4A4A00, 0x80000000));
    }

    private KuiThemes() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        loadPackThemes();
        try {
            var path = configPath();
            if (Files.isRegularFile(path)) {
                String saved = Files.readString(path).trim().toLowerCase(Locale.ROOT);
                if (THEMES.containsKey(saved)) current = saved;
            }
        } catch (Exception error) {
            KoperLib.LOGGER.warn("[Kui] couldn't read theme setting: {}", error.getMessage());
        }
        KuiPaint.applyPalette(THEMES.get(current));
    }

    // Packs and addons can register palette-only themes without replacing the renderer.
    public static void register(String id, Palette palette) {
        if (id == null || id.isBlank() || palette == null) return;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        THEMES.put(normalized, palette);
        if (initialized && normalized.equals(current)) KuiPaint.applyPalette(palette);
    }

    public static List<String> names() {
        return List.copyOf(THEMES.keySet());
    }

    public static String current() {
        init();
        return current;
    }

    public static String displayName() {
        String id = current();
        StringBuilder out = new StringBuilder(id.length());
        boolean upper = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_') {
                out.append(' ');
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    public static void cycle() {
        init();
        List<String> names = names();
        int index = names.indexOf(current);
        set(names.get((index + 1) % names.size()));
    }

    public static boolean set(String id) {
        if (id == null) return false;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        Palette palette = THEMES.get(normalized);
        if (palette == null) return false;
        current = normalized;
        KuiPaint.applyPalette(palette);
        save();
        return true;
    }

    private static void save() {
        try {
            var path = configPath();
            Files.createDirectories(path.getParent());
            var temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, current + System.lineSeparator());
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            KoperLib.LOGGER.warn("[Kui] couldn't save theme setting: {}", error.getMessage());
        }
    }

    private static java.nio.file.Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("koperlib-kui-theme.txt");
    }

    private static void loadPackThemes() {
        for (var pack : FullPackLoader.getEnabledPackDirs()) {
            loadThemeDirectory(pack.toPath().resolve("gui/themes"), pack.getName());
            loadThemeDirectory(pack.toPath().resolve("koperlib/gui/themes"), pack.getName());
        }
    }

    private static void loadThemeDirectory(java.nio.file.Path directory, String packName) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (var path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    String fileName = path.getFileName().toString();
                    String fallbackId = packName + ":" + fileName.substring(0, fileName.length() - 5);
                    String id = json.has("id") ? json.get("id").getAsString() : fallbackId;
                    String parent = json.has("extends") ? json.get("extends").getAsString() : "copper";
                    Palette base = THEMES.getOrDefault(parent.toLowerCase(Locale.ROOT), THEMES.get("copper"));
                    register(id, palette(json, base));
                } catch (Exception error) {
                    KoperLib.LOGGER.warn("[Kui] couldn't load theme {}: {}", path, error.getMessage());
                }
            }
        } catch (Exception error) {
            KoperLib.LOGGER.warn("[Kui] couldn't scan theme directory {}: {}", directory, error.getMessage());
        }
    }

    private static Palette palette(JsonObject json, Palette base) {
        return new Palette(
            color(json, "black", base.black()),
            color(json, "panel", base.panel()),
            color(json, "panel_top", base.panelTop()),
            color(json, "gray", base.gray()),
            color(json, "light", base.light()),
            color(json, "dark", base.dark()),
            color(json, "slot", base.slot()),
            color(json, "title", base.title()),
            color(json, "muted", base.muted()),
            color(json, "hover", base.hover()),
            color(json, "bar", base.bar()),
            color(json, "bar_hover", base.barHover()),
            color(json, "track", base.track()),
            color(json, "selected", base.selected()),
            color(json, "ghost", base.ghost()));
    }

    private static int color(JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        var value = json.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsInt();
        String text = value.getAsString().strip();
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            if (hex.length() == 6) hex = "FF" + hex;
            return (int)Long.parseUnsignedLong(hex, 16);
        }
        return (int)Long.decode(text).longValue();
    }
}
