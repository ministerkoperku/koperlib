package com.koper.koper_lib.factory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperEnchantmentData;
import net.minecraft.resources.Identifier;
import java.util.Map;

public class EnchantmentFactory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void createAndRegister(JsonObject json) {
        KoperEnchantmentData data = new KoperEnchantmentData();
        data.applyJson(json);

        if (data.id == null) return;
        Identifier id = Identifier.tryParse(data.id);
        if (id == null) return;

        String ns = id.getNamespace();
        String path = id.getPath();

        
        String supportedItems;
        String slotsJson;
        switch (data.slot.toLowerCase()) {
            case "weapon" -> { supportedItems = "#minecraft:enchantable/weapon"; slotsJson = "[\"mainhand\"]"; }
            case "armor"  -> { supportedItems = "#minecraft:enchantable/armor";  slotsJson = "[\"head\",\"chest\",\"legs\",\"feet\"]"; }
            case "tool", "mining" -> { supportedItems = "#minecraft:enchantable/mining"; slotsJson = "[\"mainhand\"]"; }
            default       -> { supportedItems = "#minecraft:enchantable/vanishing"; slotsJson = "[\"any\"]"; }
        }

        JsonObject root = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("translate", "enchantment." + ns + "." + path);
        root.add("description", description);
        root.addProperty("supported_items", supportedItems);
        root.addProperty("weight", data.weight);
        root.addProperty("max_level", data.maxLevel);
        // vanilla has no min_level, the closest real knob is where the cost curve starts.
        // min_level 3 means "shows up about as late as a level 3 enchant would"
        int floor = Math.max(1, data.minLevel);
        root.add("min_cost", cost(1 + (floor - 1) * 10, 10));
        root.add("max_cost", cost(51 + (floor - 1) * 10, 10));
        root.addProperty("anvil_cost", data.anvilCost);
        root.add("slots", GSON.fromJson(slotsJson, JsonArray.class));
        root.add("effects", buildEffects(data));

        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(ns, "enchantment/" + path + ".json"),
            GSON.toJson(root)
        );

        // curses are a tag in modern mc, not a flag on the enchantment. vanilla curses sit in
        // #curse and stay out of #non_treasure, which is what makes them treasure only
        if (data.isCurse) {
            KoperLib.VIRTUAL_PACK.addServerTagValue(
                Identifier.fromNamespaceAndPath("minecraft", "enchantment/curse"), ns + ":" + path);
        }

        
        String translationKey = "enchantment." + ns + "." + path;
        if (data.name != null && !data.name.isEmpty()) {
            KoperLib.VIRTUAL_PACK.addTranslation(translationKey, data.name);
        } else {
            KoperLib.VIRTUAL_PACK.addTranslation(translationKey, FactoryUtils.capitalizeWords(path));
        }

        KoperLib.LOGGER.info("EnchantmentFactory: Registered enchantment data: " + data.id);
    }

    private static JsonObject cost(int base, int perLevel) {
        JsonObject obj = new JsonObject();
        obj.addProperty("base", base);
        obj.addProperty("per_level_above_first", perLevel);
        return obj;
    }

    private static JsonObject buildEffects(KoperEnchantmentData data) {
        JsonObject effects = new JsonObject();
        if (data.script != null && !data.script.isEmpty()) {
            addScriptEffect(effects, "post_attack", data.script, "on_hit", "attacker", "victim");
        }
        for (String script : data.scripts) {
            if (script != null && !script.isEmpty()) {
                addScriptEffect(effects, "post_attack", script, "on_hit", "attacker", "victim");
            }
        }
        if (data.effects == null) return effects;

        for (Map.Entry<String, JsonElement> entry : data.effects.entrySet()) {
            String key = normalizeComponent(entry.getKey());
            JsonElement value = entry.getValue();
            if (isEntityEffectComponent(key)) {
                JsonArray out = effects.has(key) && effects.get(key).isJsonArray()
                    ? effects.getAsJsonArray(key) : new JsonArray();
                for (JsonElement e : asArray(value)) {
                    out.add(componentEntry(key, e));
                }
                effects.add(key, out);
            } else {
                effects.add(key, value.deepCopy());
            }
        }
        return effects;
    }

    private static String normalizeComponent(String key) {
        if (key == null) return "";
        String k = key.toLowerCase();
        return k.startsWith("minecraft:") ? k.substring("minecraft:".length()) : k;
    }

    private static boolean isEntityEffectComponent(String key) {
        return switch (key) {
            case "post_attack", "post_piercing_attack", "hit_block", "tick", "projectile_spawned" -> true;
            default -> false;
        };
    }

    private static JsonArray asArray(JsonElement value) {
        JsonArray arr = new JsonArray();
        if (value == null || value.isJsonNull()) return arr;
        if (value.isJsonArray()) return value.getAsJsonArray();
        arr.add(value);
        return arr;
    }

    private static JsonObject componentEntry(String component, JsonElement value) {
        JsonObject src = value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        if (value != null && value.isJsonPrimitive()) src.addProperty("script", value.getAsString());

        JsonObject out = new JsonObject();
        if ("post_attack".equals(component)) {
            out.addProperty("enchanted", string(src, "enchanted", "attacker"));
            out.addProperty("affected", string(src, "affected", "victim"));
        }

        JsonObject effect;
        if (src.has("effect") && src.get("effect").isJsonObject()) {
            effect = src.getAsJsonObject("effect").deepCopy();
        } else {
            effect = new JsonObject();
            if (src.has("script")) effect.addProperty("script", src.get("script").getAsString());
            effect.addProperty("event", string(src, "event", defaultEvent(component)));
        }
        if (effect.has("script") && !effect.has("type")) {
            effect.addProperty("type", "koper_lib:script_effect");
        }
        if (effect.has("script") && !effect.has("event")) {
            effect.addProperty("event", string(src, "event", defaultEvent(component)));
        }
        out.add("effect", effect);

        if (src.has("requirements")) out.add("requirements", src.get("requirements").deepCopy());
        return out;
    }

    private static void addScriptEffect(JsonObject effects, String component, String script, String event, String enchanted, String affected) {
        JsonArray arr = effects.has(component) && effects.get(component).isJsonArray()
            ? effects.getAsJsonArray(component) : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("script", script);
        entry.addProperty("event", event);
        entry.addProperty("enchanted", enchanted);
        entry.addProperty("affected", affected);
        arr.add(componentEntry(component, entry));
        effects.add(component, arr);
    }

    private static String defaultEvent(String component) {
        return switch (component) {
            case "tick" -> "on_tick";
            case "hit_block" -> "on_break";
            case "projectile_spawned" -> "on_spawn";
            default -> "on_hit";
        };
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : fallback;
    }
}
