package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

// procedural geo tint: a grayscale-authored model gets multiplied by a color so shading survives
// (dark stays a darker shade of the tint, light a lighter shade). multiple colors get averaged into one.
// "color": "#ff0000" | "0xff0000" | [255,0,0] | [1.0,0,0]   |   "colors": [ ... mixed ... ]
public final class KoperTint {

    private KoperTint() {}

    public static final int NONE = 0xFFFFFFFF; // white = no tint (texture passes through unchanged)

    // reads "color"/"colors" off a JSON object -> ARGB int with full alpha, or NONE if absent
    public static int fromJson(JsonObject o) {
        if (o == null) return NONE;
        if (o.has("colors") && o.get("colors").isJsonArray()) {
            List<Integer> cols = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("colors")) cols.add(parseOne(e));
            return cols.isEmpty() ? NONE : argb(mix(cols));
        }
        if (o.has("color")) return argb(parseOne(o.get("color")));
        return NONE;
    }

    public static int argb(int rgb) { return 0xFF000000 | (rgb & 0xFFFFFF); }

    // average a list of rgb ints (simple linear mix — good enough, the user can pre-mix if they want fancier)
    public static int mix(List<Integer> cols) {
        long r = 0, g = 0, b = 0;
        for (int c : cols) { r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; }
        int n = cols.size();
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    // "#rrggbb" / "0xrrggbb" / "rrggbb" / [r,g,b] (0-255 or 0.0-1.0) -> rgb int
    private static int parseOne(JsonElement e) {
        try {
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                if (a.size() < 3) return 0xFFFFFF;
                float r = a.get(0).getAsFloat(), g = a.get(1).getAsFloat(), b = a.get(2).getAsFloat();
                boolean norm = r <= 1f && g <= 1f && b <= 1f; // 0..1 floats vs 0..255 ints
                int ri = clamp(norm ? r * 255f : r), gi = clamp(norm ? g * 255f : g), bi = clamp(norm ? b * 255f : b);
                return ri << 16 | gi << 8 | bi;
            }
            String s = e.getAsString().trim().replace("#", "").replace("0x", "").replace("0X", "");
            return (int) (Long.parseLong(s, 16) & 0xFFFFFF);
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
