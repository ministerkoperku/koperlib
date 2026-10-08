package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperDimensionData;
import net.minecraft.resources.Identifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.Map;

public class DimensionFactory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<Identifier, KoperDimensionData> DIMENSION_DATA = new HashMap<>();

    public static Map<Identifier, KoperDimensionData> getDimensionData() {
        return DIMENSION_DATA;
    }

    public static void clearReloadData() {
        DIMENSION_DATA.clear();
    }

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        KoperDimensionData merged = FactoryUtils.prepare(new KoperDimensionData(), json);
        merged.name = json.has("name") ? json.get("name").getAsString() : id.getPath();

        String ns = id.getNamespace();
        String path = id.getPath();

        
        String baseType = merged.baseType != null ? merged.baseType : "custom";

        
        boolean hasSkylight, hasCeiling, ultrawarm, natural, bedWorks, piglinSafe, respawnAnchorWorks, hasRaids;
        double coordScale;
        float ambientLight;
        String infiniburn;

        switch (baseType.toLowerCase()) {
            case "nether" -> {
                hasSkylight = false; hasCeiling = true; ultrawarm = true; natural = false;
                bedWorks = false; piglinSafe = true; respawnAnchorWorks = true; hasRaids = false;
                coordScale = 8.0; ambientLight = 0.1f;
                infiniburn = "#minecraft:infiniburn_nether";
            }
            case "end" -> {
                hasSkylight = false; hasCeiling = false; ultrawarm = false; natural = false;
                bedWorks = false; piglinSafe = false; respawnAnchorWorks = false; hasRaids = true;
                coordScale = 1.0; ambientLight = 0.0f;
                infiniburn = "#minecraft:infiniburn_end";
            }
            default -> { 
                hasSkylight = true; hasCeiling = false; ultrawarm = false; natural = true;
                bedWorks = true; piglinSafe = false; respawnAnchorWorks = false; hasRaids = true;
                coordScale = 1.0; ambientLight = 0.0f;
                infiniburn = "#minecraft:infiniburn_overworld";
            }
        }

        
        String environment = json.has("environment") ? merged.environment :
                (baseType.equals("nether") ? "nether" : baseType.equals("end") ? "end" : "overworld");
        hasSkylight = json.has("has_skylight") ? getBool(json, "has_skylight", hasSkylight) : hasSkylight;
        hasCeiling  = json.has("has_ceiling") ? getBool(json, "has_ceiling", hasCeiling) : hasCeiling;
        ultrawarm   = json.has("ultrawarm") ? getBool(json, "ultrawarm", ultrawarm) : ultrawarm;
        natural     = json.has("natural") ? getBool(json, "natural", natural) : natural;
        bedWorks    = json.has("bed_works") ? getBool(json, "bed_works", bedWorks) : bedWorks;
        piglinSafe  = json.has("piglin_safe") ? getBool(json, "piglin_safe", piglinSafe) : piglinSafe;
        respawnAnchorWorks = json.has("respawn_anchor_works") ? getBool(json, "respawn_anchor_works", respawnAnchorWorks) : respawnAnchorWorks;
        hasRaids    = json.has("has_raids") ? getBool(json, "has_raids", hasRaids) : hasRaids;
        coordScale  = json.has("coordinate_scale") ? getDouble(json, "coordinate_scale", coordScale) : coordScale;
        ambientLight = json.has("ambient_light") ? (float) getDouble(json, "ambient_light", ambientLight) : ambientLight;

        int logicalHeight   = json.has("logical_height") ? merged.logicalHeight : 256;
        int minY            = json.has("min_y") ? merged.minY : -64;
        int height          = json.has("height") ? merged.height : 384;
        int monsterSpawnLight = json.has("monster_spawn_light_level")
            ? getInt(json, "monster_spawn_light_level", 7)
            : merged.monsterSpawnLight != null ? merged.monsterSpawnLight : 7;
        String biome        = merged.biome != null ? merged.biome : "minecraft:the_void";
        String generator    = merged.generator != null ? merged.generator : "void";

        
        String effects = json.has("effects") && merged.effects != null ? merged.effects : switch (environment.toLowerCase()) {
            case "nether" -> "minecraft:the_nether";
            case "end"    -> "minecraft:the_end";
            default       -> "minecraft:overworld";
        };

       
        boolean hasEnderDragonFight = json.has("has_ender_dragon_fight")
            ? getBool(json, "has_ender_dragon_fight", false)
            : baseType.equals("end");

        merged.environment = environment;
        merged.hasSkylight = hasSkylight;
        merged.hasCeiling = hasCeiling;
        merged.ultrawarm = ultrawarm;
        merged.natural = natural;
        merged.bedWorks = bedWorks;
        merged.piglinSafe = piglinSafe;
        merged.respawnAnchorWorks = respawnAnchorWorks;
        merged.hasRaids = hasRaids;
        merged.coordinateScale = coordScale;
        merged.ambientLight = ambientLight;
        merged.logicalHeight = logicalHeight;
        merged.minY = minY;
        merged.height = height;
        merged.monsterSpawnLight = monsterSpawnLight;
        merged.effects = effects;
        merged.hasEnderDragonFight = hasEnderDragonFight;
        merged.infiniburn = infiniburn;
        if (json.has("fixed_time")) merged.fixedTime = getInt(json, "fixed_time", 6000);
        DIMENSION_DATA.put(id, merged);

        
        String dimTypeJson = """
            {
              "ultrawarm": %b,
              "natural": %b,
              "piglin_safe": %b,
              "respawn_anchor_works": %b,
              "bed_works": %b,
              "coordinate_scale": %s,
              "has_skylight": %b,
              "has_ceiling": %b,
              "ambient_light": %s,
              "fixed_time": %s,
              "monster_spawn_light_level": %d,
              "monster_spawn_block_light_limit": 0,
              "has_raids": %b,
              "has_ender_dragon_fight": %b,
              "logical_height": %d,
              "min_y": %d,
              "height": %d,
              "infiniburn": "%s",
              "effects": "%s"
            }
            """.formatted(ultrawarm, natural, piglinSafe, respawnAnchorWorks, bedWorks,
                          coordScale, hasSkylight, hasCeiling, ambientLight,
                          json.has("fixed_time") ? String.valueOf(getInt(json, "fixed_time", 6000)) : "false",
                          monsterSpawnLight, hasRaids, hasEnderDragonFight,
                          logicalHeight, minY, height, infiniburn, effects);

        
        dimTypeJson = dimTypeJson.replace("\"fixed_time\": false,\n", "");

        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(ns, "dimension_type/" + path + ".json"), dimTypeJson);

        
        String generatorJson = merged.generatorJson != null ? GSON.toJson(merged.generatorJson) : switch (generator.toLowerCase()) {
            case "flat" -> """
                {
                  "type": "minecraft:flat",
                  "settings": {
                    "layers": [{"block": "minecraft:bedrock", "height": 1}],
                    "biome": "%s",
                    "features": false,
                    "lakes": false
                  }
                }""".formatted(biome);
            case "noise" -> """
                {
                  "type": "minecraft:noise",
                  "biome_source": {
                    "type": "minecraft:fixed",
                    "biome": "%s"
                  },
                  "settings": "minecraft:overworld"
                }""".formatted(biome);
            default -> """
                {
                  "type": "minecraft:flat",
                  "settings": {
                    "layers": [],
                    "biome": "%s",
                    "features": false,
                    "lakes": false
                  }
                }""".formatted(biome);
        };

        String dimJson = """
            {
              "type": "%s:%s",
              "generator": %s
            }
            """.formatted(ns, path, generatorJson);

        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(ns, "dimension/" + path + ".json"), dimJson);

        // Portal auto-creation: if "portal" block is present in JSON, create igniter + portal block
        if (json.has("portal")) {
            JsonObject portal = json.getAsJsonObject("portal");
            String frameBlock = portal.has("frame_block") ? portal.get("frame_block").getAsString() : "minecraft:obsidian";
            boolean autoIgniter = portal.has("auto_igniter") && portal.get("auto_igniter").getAsBoolean();

            if (autoIgniter) {
                JsonObject igniterJson = new JsonObject();
                igniterJson.addProperty("id", ns + ":" + path + "_igniter");
                igniterJson.addProperty("type", "portal_igniter");
                igniterJson.addProperty("name", getString(json, "name", path) + " Igniter");
                igniterJson.addProperty("dimension", idStr);
                igniterJson.addProperty("frame_block", frameBlock);
                igniterJson.addProperty("creative_tab", ns + ":main");
                igniterJson.addProperty("max_stack", 1);
                igniterJson.addProperty("durability", 64);

                ItemFactory.createAndRegister(igniterJson);
            }
        }

        KoperLib.LOGGER.info("DimensionFactory: Registered dimension data: " + idStr);
    }

    private static String getString(JsonObject j, String key, String def) {
        return j.has(key) ? j.get(key).getAsString() : def;
    }
    private static boolean getBool(JsonObject j, String key, boolean def) {
        return j.has(key) ? j.get(key).getAsBoolean() : def;
    }
    private static int getInt(JsonObject j, String key, int def) {
        return j.has(key) ? j.get(key).getAsInt() : def;
    }
    private static double getDouble(JsonObject j, String key, double def) {
        return j.has(key) ? j.get(key).getAsDouble() : def;
    }
}
