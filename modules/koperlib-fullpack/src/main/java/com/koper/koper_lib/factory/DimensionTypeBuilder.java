package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

// builds a 26.3 dimension_type from a fullpack dimension json. 26.3 moved sky, beds, raids, piglins and
// time into "attributes", "skybox", "timelines" and clocks, and its codec silently drops the old keys,
// so the old keys are translated here. the vanilla dimension of the chosen environment is the template.
// no minecraft classes in here: the caller hands in the vanilla files, the tests hand in their own
public final class DimensionTypeBuilder {
    private DimensionTypeBuilder() {}

    /** dimension_type json, extra server files (path → json) and the tick a frozen clock is held at, or null */
    public record Built(JsonObject type, Map<String, JsonObject> files, Integer fixedTime, String clock) {}

    public static final int SPACE_TIME = 18000;

    public static Built build(JsonObject json, String ns, String path, Function<String, JsonObject> vanilla) {
        String baseType = lower(json, "base_type", "custom");
        String environment = json.has("environment") ? lower(json, "environment", "overworld") : switch (baseType) {
            case "nether" -> "nether";
            case "end" -> "end";
            case "space" -> "space";
            default -> "overworld";
        };
        JsonObject type = template(vanilla, environment).deepCopy();
        JsonObject attributes = type.has("attributes") ? type.getAsJsonObject("attributes") : new JsonObject();
        type.add("attributes", attributes);
        if (environment.equals("space")) space(type, attributes);

        // geometry and light keep the pre-26.3 fullpack defaults, so existing worlds keep their chunks
        boolean nether = baseType.equals("nether"), end = baseType.equals("end");
        type.addProperty("has_skylight", bool(json, "has_skylight", !nether && !end));
        type.addProperty("has_ceiling", bool(json, "has_ceiling", nether));
        type.addProperty("coordinate_scale", num(json, "coordinate_scale", nether ? 8.0 : 1.0));
        type.addProperty("ambient_light", num(json, "ambient_light", nether ? 0.1 : 0.0));
        type.addProperty("min_y", integer(json, "min_y", -64));
        type.addProperty("height", integer(json, "height", 384));
        type.addProperty("logical_height", integer(json, "logical_height", 256));
        type.addProperty("infiniburn", json.has("infiniburn") ? json.get("infiniburn").getAsString()
            : nether ? "#minecraft:infiniburn_nether" : end ? "#minecraft:infiniburn_end" : "#minecraft:infiniburn_overworld");
        type.addProperty("has_ender_dragon_fight", bool(json, "has_ender_dragon_fight", end));
        type.addProperty("monster_spawn_light_level", integer(json, "monster_spawn_light_level",
            integer(json, "monster_spawn_light", 7)));
        type.addProperty("monster_spawn_block_light_limit", integer(json, "monster_spawn_block_light_limit", 0));

        // the look of another vanilla dimension, the old "effects" key
        if (json.has("effects")) {
            String effects = json.get("effects").getAsString();
            String from = switch (effects) {
                case "minecraft:the_nether" -> "nether";
                case "minecraft:the_end" -> "end";
                case "minecraft:overworld" -> "overworld";
                default -> throw new IllegalArgumentException("\"effects\" must be minecraft:overworld, minecraft:the_nether or minecraft:the_end, got " + effects + "; use \"skybox\" and \"attributes\" for anything else");
            };
            JsonObject look = template(vanilla, from);
            copyOrRemove(look, type, "skybox");
            copyOrRemove(look, type, "cardinal_light");
            attributes.keySet().removeIf(DimensionTypeBuilder::isLook);
            if (look.has("attributes")) look.getAsJsonObject("attributes").entrySet().stream()
                .filter(e -> isLook(e.getKey())).forEach(e -> attributes.add(e.getKey(), e.getValue().deepCopy()));
        }

        // pre-26.3 gameplay switches
        if (json.has("bed_works")) {
            JsonObject rule = bedRule(json.get("bed_works").getAsBoolean());
            attributes.add("minecraft:gameplay/bed_rule", rule);
            attributes.add("minecraft:gameplay/straw_bed_rule", rule.deepCopy());
        }
        if (json.has("respawn_anchor_works"))
            attributes.addProperty("minecraft:gameplay/respawn_anchor_works", json.get("respawn_anchor_works").getAsBoolean());
        if (json.has("has_raids"))
            attributes.addProperty("minecraft:gameplay/can_start_raid", json.get("has_raids").getAsBoolean());
        if (json.has("piglin_safe"))
            attributes.addProperty("minecraft:gameplay/piglins_zombify", !json.get("piglin_safe").getAsBoolean());
        if (json.has("natural"))
            attributes.addProperty("minecraft:gameplay/nether_portal_spawns_piglin", json.get("natural").getAsBoolean());
        if (json.has("ultrawarm")) {
            boolean warm = json.get("ultrawarm").getAsBoolean();
            attributes.addProperty("minecraft:gameplay/water_evaporates", warm);
            attributes.addProperty("minecraft:gameplay/fast_lava", warm);
        }

        // 26.3 keys straight through, the pack has the last word
        if (json.has("skybox")) {
            String skybox = json.get("skybox").getAsString();
            if (!skybox.equals("none") && !skybox.equals("overworld") && !skybox.equals("end"))
                throw new IllegalArgumentException("\"skybox\" must be none, overworld or end, got " + skybox);
            type.addProperty("skybox", skybox);
        }
        if (json.has("cardinal_light")) type.add("cardinal_light", json.get("cardinal_light").deepCopy());
        if (json.has("timelines")) type.add("timelines", json.get("timelines").deepCopy());
        if (json.has("default_clock")) type.add("default_clock", json.get("default_clock").deepCopy());
        if (json.has("has_fixed_time")) type.addProperty("has_fixed_time", json.get("has_fixed_time").getAsBoolean());
        if (json.has("attributes")) {
            if (!json.get("attributes").isJsonObject()) throw new IllegalArgumentException("\"attributes\" must be an object");
            for (var e : json.getAsJsonObject("attributes").entrySet()) {
                String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
                attributes.add(key, e.getValue().deepCopy());
            }
        }

        // a frozen time of day: an own clock that never runs, driving copies of the vanilla day and moon
        Map<String, JsonObject> files = new LinkedHashMap<>();
        Integer fixed = json.has("fixed_time") ? Integer.valueOf(json.get("fixed_time").getAsInt())
            : environment.equals("space") ? Integer.valueOf(SPACE_TIME) : null;
        String clock = null;
        if (fixed != null) {
            if (fixed < 0) throw new IllegalArgumentException("\"fixed_time\" must not be negative");
            clock = ns + ":" + path;
            files.put("world_clock/" + path + ".json", new JsonObject());
            JsonArray timelines = new JsonArray();
            for (String name : new String[]{"day", "moon"}) {
                JsonObject timeline = vanilla.apply("timeline/" + name).deepCopy();
                timeline.addProperty("clock", clock);
                timeline.remove("time_markers");
                files.put("timeline/" + path + "_" + name + ".json", timeline);
                timelines.add(ns + ":" + path + "_" + name);
            }
            type.addProperty("default_clock", clock);
            type.add("timelines", timelines);
            type.addProperty("has_fixed_time", true);
        }
        return new Built(type, files, fixed, clock);
    }

    // black sky, stars at full, no clouds, nobody sleeps, nothing raids
    private static void space(JsonObject type, JsonObject attributes) {
        type.addProperty("skybox", "overworld");
        attributes.addProperty("minecraft:visual/sky_color", "#000000");
        attributes.addProperty("minecraft:visual/fog_color", "#000000");
        attributes.addProperty("minecraft:visual/cloud_color", "#00ffffff");
        attributes.addProperty("minecraft:visual/star_brightness", 1.0);
        attributes.add("minecraft:gameplay/bed_rule", bedRule(false));
        attributes.add("minecraft:gameplay/straw_bed_rule", bedRule(false));
        attributes.addProperty("minecraft:gameplay/can_start_raid", false);
        attributes.addProperty("minecraft:gameplay/nether_portal_spawns_piglin", false);
    }

    private static JsonObject template(Function<String, JsonObject> vanilla, String environment) {
        String file = switch (environment) {
            case "nether", "the_nether" -> "the_nether";
            case "end", "the_end" -> "the_end";
            case "overworld", "space" -> "overworld";
            default -> throw new IllegalArgumentException("\"environment\" must be overworld, nether, end or space, got " + environment);
        };
        JsonObject template = vanilla.apply("dimension_type/" + file);
        if (template == null) throw new IllegalStateException("vanilla dimension_type/" + file + " is missing from the game jar");
        return template;
    }

    private static JsonObject bedRule(boolean works) {
        JsonObject rule = new JsonObject();
        if (works) {
            rule.addProperty("can_set_spawn", "always");
            rule.addProperty("can_sleep", "when_dark");
            JsonObject message = new JsonObject();
            message.addProperty("translate", "block.minecraft.bed.no_sleep");
            rule.add("error_message", message);
        } else {
            rule.addProperty("can_set_spawn", "never");
            rule.addProperty("can_sleep", "never");
            rule.addProperty("destroy_on_use", true);
        }
        return rule;
    }

    private static boolean isLook(String attribute) {
        return attribute.startsWith("minecraft:visual/") || attribute.startsWith("minecraft:audio/");
    }

    private static void copyOrRemove(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) to.add(key, from.get(key).deepCopy());
        else to.remove(key);
    }

    private static String lower(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString().toLowerCase(Locale.ROOT) : fallback;
    }
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }
    private static double num(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }
    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
    }
}
