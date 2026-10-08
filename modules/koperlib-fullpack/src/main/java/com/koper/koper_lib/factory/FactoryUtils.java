package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.data.KoperData;

// shared helpers used by every factory — so the same 5-line stream thing isn't copy-pasted 8 times
public final class FactoryUtils {

    // "lang": { "en_us": { "name": "Essence Core", "lore": ["...", "..."] }, "pl_pl": {...} }
    // every language a pack cares to write. the plain "name"/"lore" stay the en_us fallback,
    // and minecraft falls back to en_us on its own for anything a language leaves out
    public static void emitLang(com.google.gson.JsonObject json, String nameKey, String loreKeyPrefix,
                                String fallbackName, java.util.List<String> fallbackLore) {
        if (fallbackName != null)
            com.koper.koper_lib.KoperLib.VIRTUAL_PACK.addTranslation("en_us", nameKey, fallbackName);
        if (fallbackLore != null && loreKeyPrefix != null)
            for (int i = 0; i < fallbackLore.size(); i++)
                com.koper.koper_lib.KoperLib.VIRTUAL_PACK.addTranslation("en_us",
                    loreKeyPrefix + "." + i, fallbackLore.get(i));

        if (json == null || !json.has("lang") || !json.get("lang").isJsonObject()) return;
        for (var entry : json.getAsJsonObject("lang").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            String lang = entry.getKey();
            var body = entry.getValue().getAsJsonObject();
            if (body.has("name") && body.get("name").isJsonPrimitive())
                com.koper.koper_lib.KoperLib.VIRTUAL_PACK.addTranslation(lang, nameKey,
                    body.get("name").getAsString());
            if (loreKeyPrefix != null && body.has("lore") && body.get("lore").isJsonArray()) {
                var lines = body.getAsJsonArray("lore");
                for (int i = 0; i < lines.size(); i++)
                    com.koper.koper_lib.KoperLib.VIRTUAL_PACK.addTranslation(lang,
                        loreKeyPrefix + "." + i, lines.get(i).getAsString());
            }
        }
    }
    private FactoryUtils() {}

    // standard boot for any JSON-defined data: defaults → optional preset → json overrides
    public static <T extends KoperData> T prepare(T data, JsonObject json) {
        data.applyDefaults();
        if (json.has("preset")) data.applyPreset(json.get("preset").getAsString());
        data.applyJson(json);
        return data;
    }

    // creative tab from config, or the pack's default "<namespace>:main"
    public static String tabOr(String creativeTab, String namespace) {
        return (creativeTab != null && !creativeTab.isEmpty()) ? creativeTab : namespace + ":main";
    }

    // slash-style "mod/item" → namespaced "mod:item"; bare "item" → "minecraft:item"
    public static String normalize(String id) {
        if (id == null) return "minecraft:air";
        if (id.contains(":")) return id;
        if (id.contains("/")) { int i = id.indexOf('/'); return id.substring(0, i) + ":" + id.substring(i + 1); }
        return "minecraft:" + id;
    }

    // "flame_sword" → "Flame Sword", also works on already-spaced strings
    public static String capitalizeWords(String input) {
        String spaced = input.replace("_", " ");
        String[] words = spaced.split(" ");
        StringBuilder sb = new StringBuilder(spaced.length());
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
