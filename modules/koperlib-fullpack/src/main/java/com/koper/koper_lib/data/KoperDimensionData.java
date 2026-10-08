package com.koper.koper_lib.data;

import com.google.gson.JsonObject;

public class KoperDimensionData implements KoperData {
    public String id;
    public String type;
    public String name;

    public String baseType;
    public String generator;
    public com.google.gson.JsonObject generatorJson;
    public String biome;
    public String environment;
    public String effects;

    public Integer minY;
    public Integer height;
    public Integer logicalHeight;
    public Double coordinateScale;
    public Float ambientLight;
    public Boolean hasSkylight;
    public Boolean hasCeiling;
    public Boolean ultrawarm;
    public Boolean natural;
    public Boolean bedWorks;
    public Boolean piglinSafe;
    public Boolean respawnAnchorWorks;
    public Boolean hasRaids;
    public Boolean hasEnderDragonFight;
    public Integer monsterSpawnLight;
    public String infiniburn;
    public Integer fixedTime;

    @Override
    public void applyDefaults() {
        if (baseType == null) baseType = "custom";
        if (generator == null) generator = "void";
        if (biome == null) biome = "minecraft:the_void";
        if (environment == null) environment = "overworld";
        if (effects == null) effects = "minecraft:overworld";
        if (minY == null) minY = -64;
        if (height == null) height = 384;
        if (logicalHeight == null) logicalHeight = 384;
        if (coordinateScale == null) coordinateScale = 1.0;
        if (ambientLight == null) ambientLight = 0.0f;
        if (hasSkylight == null) hasSkylight = true;
        if (hasCeiling == null) hasCeiling = false;
        if (ultrawarm == null) ultrawarm = false;
        if (natural == null) natural = true;
        if (bedWorks == null) bedWorks = true;
        if (piglinSafe == null) piglinSafe = false;
        if (respawnAnchorWorks == null) respawnAnchorWorks = false;
        if (hasRaids == null) hasRaids = true;
        if (hasEnderDragonFight == null) hasEnderDragonFight = false;
        if (monsterSpawnLight == null) monsterSpawnLight = 7;
        if (infiniburn == null) infiniburn = "#minecraft:infiniburn_overworld";
    }

    @Override
    public void applyPreset(String presetName) {
    }

    @Override
    public void applyJson(JsonObject json) {
        if (json.has("id")) this.id = json.get("id").getAsString();
        if (json.has("type")) this.type = json.get("type").getAsString();
        if (json.has("name")) this.name = json.get("name").getAsString();

        if (json.has("base_type")) this.baseType = json.get("base_type").getAsString();
        if (json.has("generator")) {
            if (json.get("generator").isJsonObject()) this.generatorJson = json.getAsJsonObject("generator").deepCopy();
            else this.generator = json.get("generator").getAsString();
        }
        if (json.has("generator_json") && json.get("generator_json").isJsonObject())
            this.generatorJson = json.getAsJsonObject("generator_json").deepCopy();
        if (json.has("biome")) this.biome = json.get("biome").getAsString();
        if (json.has("environment")) this.environment = json.get("environment").getAsString();
        if (json.has("effects")) this.effects = json.get("effects").getAsString();

        if (json.has("min_y")) this.minY = json.get("min_y").getAsInt();
        if (json.has("height")) this.height = json.get("height").getAsInt();
        if (json.has("logical_height")) this.logicalHeight = json.get("logical_height").getAsInt();
        if (json.has("coordinate_scale")) this.coordinateScale = json.get("coordinate_scale").getAsDouble();
        if (json.has("ambient_light")) this.ambientLight = json.get("ambient_light").getAsFloat();
        if (json.has("has_skylight")) this.hasSkylight = json.get("has_skylight").getAsBoolean();
        if (json.has("has_ceiling")) this.hasCeiling = json.get("has_ceiling").getAsBoolean();
        if (json.has("ultrawarm")) this.ultrawarm = json.get("ultrawarm").getAsBoolean();
        if (json.has("natural")) this.natural = json.get("natural").getAsBoolean();
        if (json.has("bed_works")) this.bedWorks = json.get("bed_works").getAsBoolean();
        if (json.has("piglin_safe")) this.piglinSafe = json.get("piglin_safe").getAsBoolean();
        if (json.has("respawn_anchor_works")) this.respawnAnchorWorks = json.get("respawn_anchor_works").getAsBoolean();
        if (json.has("has_raids")) this.hasRaids = json.get("has_raids").getAsBoolean();
        if (json.has("has_ender_dragon_fight")) this.hasEnderDragonFight = json.get("has_ender_dragon_fight").getAsBoolean();
        if (json.has("monster_spawn_light")) this.monsterSpawnLight = json.get("monster_spawn_light").getAsInt();
        if (json.has("infiniburn")) this.infiniburn = json.get("infiniburn").getAsString();
        if (json.has("fixed_time")) this.fixedTime = json.get("fixed_time").getAsInt();
    }
}
