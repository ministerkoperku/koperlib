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

        String ns = id.getNamespace();
        String path = id.getPath();
        
        KoperDimensionData merged;
        DimensionTypeBuilder.Built built;
        try {
            merged = FactoryUtils.prepare(new KoperDimensionData(), json);
            built = DimensionTypeBuilder.build(json, ns, path, DimensionFactory::vanilla);
        } catch (RuntimeException e) {
            KoperLib.LOGGER.error("DimensionFactory: dimension {} does not work: {}", idStr, e.getMessage());
            return;
        }
        merged.name = json.has("name") ? json.get("name").getAsString() : id.getPath();
        JsonObject type = built.type();
        String biome = merged.biome != null ? merged.biome : "minecraft:the_void";
        String generator = merged.generator != null ? merged.generator : "void";

        merged.environment = json.has("environment") ? merged.environment
            : "nether".equalsIgnoreCase(merged.baseType) ? "nether" : "end".equalsIgnoreCase(merged.baseType) ? "end"
            : "space".equalsIgnoreCase(merged.baseType) ? "space" : "overworld";
        merged.hasSkylight = type.get("has_skylight").getAsBoolean();
        merged.hasCeiling = type.get("has_ceiling").getAsBoolean();
        merged.coordinateScale = type.get("coordinate_scale").getAsDouble();
        merged.ambientLight = type.get("ambient_light").getAsFloat();
        merged.logicalHeight = type.get("logical_height").getAsInt();
        merged.minY = type.get("min_y").getAsInt();
        merged.height = type.get("height").getAsInt();
        merged.monsterSpawnLight = type.get("monster_spawn_light_level").getAsInt();
        merged.hasEnderDragonFight = type.get("has_ender_dragon_fight").getAsBoolean();
        merged.infiniburn = type.get("infiniburn").getAsString();
        merged.skybox = type.has("skybox") ? type.get("skybox").getAsString() : "overworld";
        merged.fixedTime = built.fixedTime();
        merged.clock = built.clock();
        DIMENSION_DATA.put(id, merged);

        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(ns, "dimension_type/" + path + ".json"), GSON.toJson(type));
        built.files().forEach((file, content) -> KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(ns, file), GSON.toJson(content)));

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
    private static final Map<String, JsonObject> VANILLA = new java.util.concurrent.ConcurrentHashMap<>();

    // vanilla data files straight out of the game jar, e.g. "dimension_type/overworld"
    static JsonObject vanilla(String file) {
        return VANILLA.computeIfAbsent(file, f -> {
            String path = "/data/minecraft/" + f + ".json";
            try (var in = net.minecraft.server.MinecraftServer.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("vanilla " + path + " is not in the game jar");
                return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("vanilla " + path + " could not be read", e);
            }
        }).deepCopy();
    }

    // a dimension with "fixed_time" owns a clock; hold it at that tick. runs at every server start
    // because the clock state lives in the world save and "/time" can move it
    public static void holdClocks(net.minecraft.server.MinecraftServer server) {
        var clocks = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK);
        DIMENSION_DATA.forEach((id, data) -> {
            if (data.clock == null || data.fixedTime == null) return;
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.WORLD_CLOCK,
                Identifier.parse(data.clock));
            var clock = clocks.get(key);
            if (clock.isEmpty()) {
                KoperLib.LOGGER.error("DimensionFactory: fixed_time does not work for {}: clock {} is not registered", id, data.clock);
                return;
            }
            server.clockManager().setTotalTicks(clock.get(), data.fixedTime);
            server.clockManager().setPaused(clock.get(), true);
        });
    }
}
