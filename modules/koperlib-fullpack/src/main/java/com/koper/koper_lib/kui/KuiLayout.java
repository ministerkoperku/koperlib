package com.koper.koper_lib.kui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;

import java.util.ArrayList;
import java.util.List;

// parses the layout json (the "widgets" array) into KuiElements. forgiving — bad fields fall to defaults
// instead of throwing, so a pack author's typo doesn't nuke the whole screen.
public final class KuiLayout {
    private static final Gson GSON = new Gson();

    private KuiLayout() {}

    public static List<KuiElement> parse(String json) {
        List<KuiElement> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("widgets") || !root.get("widgets").isJsonArray()) return out;
            JsonArray arr = root.getAsJsonArray("widgets");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                KuiElement e = one(el.getAsJsonObject());
                if (e != null) out.add(e);
            }
        } catch (Exception ex) {
            KoperLib.LOGGER.warn("[Kui] layout parse failed: {}", ex.getMessage());
        }
        return out;
    }

    // regions.json -> elements. texture mode uses these for hitboxes + live overlays (knob/check/text)
    public static List<KuiElement> parseRegions(String json) {
        List<KuiElement> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("regions") || !root.get("regions").isJsonArray()) return out;
            for (JsonElement el : root.getAsJsonArray("regions")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                KuiElement e = new KuiElement();
                e.type = str(o, "type", "button").toLowerCase();
                e.id   = str(o, "id", "");
                e.x = intOf(o, "x", 0); e.y = intOf(o, "y", 0);
                e.w = intOf(o, "w", 0); e.h = intOf(o, "h", 0);
                e.tooltip = str(o, "tooltip", "");
                if (o.has("options") && o.get("options").isJsonArray()) {
                    java.util.List<String> opts = new ArrayList<>();
                    for (JsonElement f : o.getAsJsonArray("options")) opts.add(f.getAsString());
                    e.options = opts;
                }
                e.selected = str(o, "selected", "");
                out.add(e);
            }
        } catch (Exception ex) {
            KoperLib.LOGGER.warn("[Kui] regions parse failed: {}", ex.getMessage());
        }
        return out;
    }

    private static KuiElement one(JsonObject o) {
        KuiElement e = new KuiElement();
        e.type = str(o, "type", "label").toLowerCase();
        e.id   = str(o, "id", "");
        e.x = intOf(o, "x", 0);
        e.y = intOf(o, "y", 0);
        e.text = str(o, "text", "");
        if (o.has("item"))   e.text = str(o, "item", "");   // item widget keeps its id in text (so set() can swap it)
        if (o.has("entity")) e.text = str(o, "entity", ""); // entity preview: entity id in text
        if (o.has("image"))  e.text = str(o, "image", "");  // image widget: texture id in text
        e.tooltip = str(o, "tooltip", "");
        e.anim = str(o, "anim", "").toLowerCase();
        e.fps  = intOf(o, "fps", 0);
        if (o.has("frames") && o.get("frames").isJsonArray()) {
            java.util.List<String> fr = new ArrayList<>();
            for (JsonElement f : o.getAsJsonArray("frames")) fr.add(f.getAsString());
            e.frames = fr;
        }
        if (o.has("options") && o.get("options").isJsonArray()) {
            java.util.List<String> opts = new ArrayList<>();
            for (JsonElement f : o.getAsJsonArray("options")) opts.add(f.isJsonPrimitive() ? f.getAsString() : "");
            e.options = opts;
        }
        e.selected = str(o, "selected", str(o, "value_id", ""));
        if (o.has("text") && !o.get("text").isJsonPrimitive()) e.textJson = o.get("text").toString();
        if (o.has("options") && o.get("options").isJsonArray()) {
            java.util.List<String> js = new ArrayList<>();
            boolean any = false;
            for (JsonElement f : o.getAsJsonArray("options")) {
                any |= !f.isJsonPrimitive();
                js.add(f.isJsonPrimitive() ? GSON.toJson(f.getAsString()) : f.toString());
            }
            if (any) e.optionsJson = js;
        }
        e.selectedIndex = intOf(o, "selected_index", -1);
        e.flow = boolOf(o, "flow", false);
        if (o.has("color")) { e.color = color(o.get("color").getAsString(), e.color); e.tinted = true; }
        e.align = str(o, "align", "left").toLowerCase();
        e.cols = intOf(o, "cols", 1);
        e.rows = intOf(o, "rows", 1);
        e.gap  = intOf(o, "gap", 0);
        e.value = floatOf(o, "value", 0f);
        e.min = floatOf(o, "min", 0f);
        e.max = floatOf(o, "max", 0f);
        e.step = floatOf(o, "step", 0f);
		e.previewZoom = floatOf(o, "zoom", 1f);
        e.checked = boolOf(o, "checked", false);
        e.container = boolOf(o, "container", false);
        e.craft = boolOf(o, "craft", false);
        e.role = str(o, "role", "").toLowerCase();
        e.placeholder = str(o, "placeholder", "");
        e.live = boolOf(o, "live", false);
        e.inputText = str(o, "value_text", "");

        // sensible default sizes per type so authors can omit w/h
        e.w = intOf(o, "w", 0);
        e.h = intOf(o, "h", 0);
        switch (e.type) {
            case "button" -> { if (e.w == 0) e.w = 100; if (e.h == 0) e.h = 20; }
            case "input"  -> { if (e.w == 0) e.w = 100; if (e.h == 0) e.h = 16; }
            case "slider" -> { if (e.w == 0) e.w = 100; if (e.h == 0) e.h = 14; }
            case "selector", "segmented" -> { if (e.w == 0) e.w = 120; if (e.h == 0) e.h = 18; }
            case "radio", "list" -> { if (e.w == 0) e.w = 120; if (e.h == 0) e.h = Math.max(18, e.options.size() * 14); }
            case "progress" -> { if (e.w == 0) e.w = 100; if (e.h == 0) e.h = 6; }
            case "rule" -> { if (e.w == 0) e.w = 100; if (e.h == 0) e.h = 1; }
            case "toggle" -> { if (e.w == 0) e.w = e.text.isEmpty() ? 12 : 100; if (e.h == 0) e.h = 14; }
            case "slot"   -> { e.w = 18; e.h = 18; }
            case "ghost"  -> { e.w = 18; e.h = 18; }
            case "grid"   -> { e.w = e.cols * 18 + Math.max(0, e.cols - 1) * e.gap;
                               e.h = e.rows * 18 + Math.max(0, e.rows - 1) * e.gap; }
            case "panel"  -> { if (e.w == 0) e.w = 60; if (e.h == 0) e.h = 40; }
            case "item"   -> { if (e.w == 0) e.w = 16; if (e.h == 0) e.h = 16; }
            case "image"  -> { if (e.w == 0) e.w = 16; if (e.h == 0) e.h = 16; }
            case "entity" -> { if (e.w == 0) e.w = 50; if (e.h == 0) e.h = 60; }
            default -> {}
        }
        return e;
    }

    private static String str(JsonObject o, String k, String def) {
        try { return o.has(k) ? o.get(k).getAsString() : def; } catch (Exception e) { return def; }
    }
    private static int intOf(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }
    private static float floatOf(JsonObject o, String k, float def) {
        try { return o.has(k) ? o.get(k).getAsFloat() : def; } catch (Exception e) { return def; }
    }
    private static boolean boolOf(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (Exception e) { return def; }
    }

    // accepts 0xAARRGGBB, #RRGGBB, #AARRGGBB, or a plain int string
    static int color(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            String v = s.trim();
            if (v.startsWith("#")) v = v.substring(1);
            else if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2);
            long n = Long.parseLong(v, 16);
            if (v.length() <= 6) n |= 0xFF000000L; // no alpha given -> opaque
            return (int) n;
        } catch (Exception e) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e2) { return def; }
        }
    }
}
