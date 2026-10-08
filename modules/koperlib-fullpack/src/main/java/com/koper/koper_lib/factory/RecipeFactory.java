package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.resources.Identifier;

import static com.koper.koper_lib.factory.FactoryUtils.normalize;

// converts koperlib recipe JSON to vanilla MC recipe JSON and injects via VirtualResourcePack
public class RecipeFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id") && !json.has("result")) {
            KoperLib.LOGGER.warn("RecipeFactory: missing 'id' or 'result', skipping.");
            return;
        }

        String recipeId;
        if (json.has("id")) {
            recipeId = json.get("id").getAsString();
        } else {
            String result = resultId(json);
            recipeId = result.replace('/', '_').replace(':', '/');
        }
        Identifier id = Identifier.tryParse(recipeId);
        if (id == null) {
            KoperLib.LOGGER.warn("RecipeFactory: invalid id '{}', skipping.", recipeId);
            return;
        }

        String rawType = json.has("type") ? json.get("type").getAsString().toLowerCase() : "recipe";
        String subtype = json.has("subtype") ? json.get("subtype").getAsString().toLowerCase()
                       : (rawType.equals("recipe") ? "shaped" : rawType);

        try {
            String mcRecipeJson = switch (subtype) {
                case "shaped"       -> buildShaped(json);
                case "shapeless"    -> buildShapeless(json);
                case "smelting"     -> buildCooking(json, "minecraft:smelting",    200, 0.7f);
                case "smoking"      -> buildCooking(json, "minecraft:smoking",     100, 0.35f);
                case "blasting"     -> buildCooking(json, "minecraft:blasting",    100, 0.7f);
                case "campfire"     -> buildCooking(json, "minecraft:campfire_cooking", 600, 0.35f);
                case "stonecutting"    -> buildStonecutting(json);
                case "smithing"        -> buildSmithing(json);
                case "custom_station"  -> buildShapeless(json); // custom station → shapeless fallback
                default                -> buildShaped(json);
            };

            Identifier assetId = Identifier.fromNamespaceAndPath(id.getNamespace(), "recipe/" + id.getPath() + ".json");
            KoperLib.VIRTUAL_PACK.addServerAsset(assetId, mcRecipeJson);
            KoperLib.LOGGER.info("RecipeFactory: Registered recipe: {} ({})", recipeId, subtype);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("RecipeFactory: Failed to build recipe '{}': {}", recipeId, e.getMessage());
        }
    }

    // ── Shaped ────────────────────────────────────────────────────────────────

    private static String buildShaped(JsonObject json) {
        JsonArray pattern = json.has("pattern") ? json.getAsJsonArray("pattern") : defaultPattern();
        JsonObject key = json.has("key") ? json.getAsJsonObject("key") : json.getAsJsonObject("ingredients");

        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:crafting_shaped");
        out.addProperty("category", "misc");
        out.add("pattern", pattern);

        JsonObject mcKey = new JsonObject();
        key.entrySet().forEach(e -> {
            String itemId = e.getValue().isJsonPrimitive()
                ? normalize(e.getValue().getAsString())
                : normalize(e.getValue().getAsJsonObject().get("item").getAsString());
            // plain id string, not {"item": id}: 26.3's Ingredient codec no longer reads a map
            // here and would answer "No key fabric:type"
            mcKey.addProperty(e.getKey(), itemId);
        });
        out.add("key", mcKey);
        out.add("result", buildResult(json));
        return out.toString();
    }

    // ── Shapeless ─────────────────────────────────────────────────────────────

    private static String buildShapeless(JsonObject json) {
        JsonArray ingredients = json.getAsJsonArray("ingredients");
        JsonArray mcIngredients = new JsonArray();
        ingredients.forEach(el -> {
            String item = el.isJsonPrimitive() ? el.getAsString() : el.getAsJsonObject().get("item").getAsString();
            mcIngredients.add(normalize(item));
        });

        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:crafting_shapeless");
        out.addProperty("category", "misc");
        out.add("ingredients", mcIngredients);
        out.add("result", buildResult(json));
        return out.toString();
    }

    // ── Cooking (smelting / smoking / blasting / campfire) ───────────────────

    private static String buildCooking(JsonObject json, String mcType, int defaultTime, float defaultXp) {
        String ingredient = json.has("ingredient")
            ? ingredientStr(json.get("ingredient"))
            : ingredientStr(json.get("input"));

        float xp = json.has("experience") ? json.get("experience").getAsFloat() : defaultXp;
        int time = json.has("cookingtime") ? json.get("cookingtime").getAsInt()
                 : json.has("cook_time")   ? json.get("cook_time").getAsInt()
                 : defaultTime;
        // 26.3 moved the blast furnace / smoker speedup into their fuel component: recipes store the
        // furnace-speed time, so the real cooking time a pack wrote is doubled here
        if (mcType.equals("minecraft:blasting") || mcType.equals("minecraft:smoking")) time *= 2;

        JsonObject out = new JsonObject();
        out.addProperty("type", mcType);
        out.addProperty("category", "misc");
        out.addProperty("ingredient", normalize(ingredient));
        out.add("result", buildResult(json));
        out.addProperty("experience", xp);
        out.addProperty("cookingtime", time);
        return out.toString();
    }

    // ── Stonecutting ──────────────────────────────────────────────────────────

    private static String buildStonecutting(JsonObject json) {
        String ingredient = ingredientStr(json.get("ingredient"));

        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:stonecutting");
        out.addProperty("ingredient", normalize(ingredient));
        out.add("result", buildResult(json));
        return out.toString();
    }

    // ── Smithing ──────────────────────────────────────────────────────────────

    private static String buildSmithing(JsonObject json) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:smithing_transform");
        if (json.has("template")) {
            out.addProperty("template", normalize(ingredientStr(json.get("template"))));
        }
        out.addProperty("base", normalize(ingredientStr(json.get("base"))));
        out.addProperty("addition", normalize(ingredientStr(json.get("addition"))));
        out.add("result", buildResult(json));
        return out.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonObject buildResult(JsonObject json) {
        JsonObject result = new JsonObject();
        if (json.has("result")) {
            var r = json.get("result");
            if (r.isJsonPrimitive()) {
                result.addProperty("id", normalize(r.getAsString()));
                result.addProperty("count", 1);
            } else {
                JsonObject ro = r.getAsJsonObject();
                result.addProperty("id", normalize(
                    ro.has("item") ? ro.get("item").getAsString() : ro.get("id").getAsString()));
                result.addProperty("count", ro.has("count") ? ro.get("count").getAsInt() : 1);
            }
        }
        return result;
    }

    private static String resultId(JsonObject json) {
        if (!json.has("result")) return "koper_lib:unknown";
        var r = json.get("result");
        if (r.isJsonPrimitive()) return r.getAsString();
        JsonObject ro = r.getAsJsonObject();
        return ro.has("item") ? ro.get("item").getAsString()
             : ro.has("id")   ? ro.get("id").getAsString()
             : "koper_lib:unknown";
    }

    private static String ingredientStr(com.google.gson.JsonElement el) {
        if (el == null) return "minecraft:air";
        if (el.isJsonPrimitive()) return el.getAsString();
        JsonObject o = el.getAsJsonObject();
        return o.has("item") ? o.get("item").getAsString() : "minecraft:air";
    }

    private static JsonArray defaultPattern() {
        JsonArray p = new JsonArray();
        p.add("AAA"); p.add("AAA"); p.add("AAA");
        return p;
    }
}

// mostly ai made it is boooringg