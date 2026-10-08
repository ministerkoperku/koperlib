package com.koper.koper_lib.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class KoperBlockData implements KoperData {
    public String id;
    public String type;

    public Float hardness;
    public Float resistance;
    public Integer lightLevel;
    public String sound;
    public Float slipperiness;
    public Float speedFactor;
    public Float jumpFactor;
    public Float bounce;
    public Boolean dropsSelf;
    public Boolean transparent;
    public Boolean collidable;
    public Boolean randomTicks;
    public Boolean ignitedByLava;
    public Boolean noTerrainParticles;
    public Boolean replaceable;
    public String logic;
    public List<String> scripts = new ArrayList<>();
    public String creativeTab;
    public String texture;
    // Per-face textures: top, bottom, north, south, east, west, side, all
    public java.util.Map<String, String> textureFaces = new java.util.LinkedHashMap<>();

    public Float mass; // koper_mass_custom

    // Mining properties
    public String miningTool;      // "pickaxe", "axe", "shovel", "hoe"
    public Boolean requiresTool;   // whether tool is needed to drop items
    public Integer miningLevel;    // 0=wood, 1=stone, 2=iron, 3=diamond, 4=netherite

    // Advanced rendering
    public String renderType;      // "solid", "cutout", "translucent"
    public String shape;           // "cube", "slab", "stairs", "cross" (for plants)
    public com.google.gson.JsonElement hitbox;       // visual/selection shape, pixels 0..16
    public com.google.gson.JsonElement collisionBox; // collision shape; omitted = hitbox/default shape

    // Redstone
    public Integer redstonePower;  // 0-15, emits constant redstone signal
    public String gui;             // Kui page to open on use
    public String containerMode;   // "shared" or "block" (block position keyed)
    public Boolean dropContainer;  // drop stored Kui container contents when block breaks
    public Integer tickInterval;   // server ticks between on_tick scans near players
    public Integer tickRadius;     // player-near radius for on_tick blocks
    // real BlockEntity: per-position state, hopper/comparator visible inventory, own ticker.
    // null = decided from gui/on_tick, true/false = you said so
    public Boolean blockEntity;
    public com.google.gson.JsonObject events;
    public String connectGroup;     // same group = pipe/casing/boiler-ish neighbors
    public Boolean connectVertical; // false = only north/east/south/west
    public java.util.Map<Integer, String> connectedCubeGuis = new java.util.LinkedHashMap<>();
    public java.util.Map<String, String> connectedTextures = new java.util.LinkedHashMap<>();
    public java.util.List<String> stateProperties = new java.util.ArrayList<>();
    public java.util.Map<String, String> defaultStates = new java.util.LinkedHashMap<>();
    // bedrock permutations: "a=1,b=x" -> light, and the pack brings its own blockstate/models
    public java.util.Map<String, Integer> lightByState = new java.util.LinkedHashMap<>();
    public Boolean ownAssets;
    // where facing comes from when placed: null = front toward the player (old behaviour),
    // "player" = the way the player looks (horizontal), "look" = incl. up/down, "face" = clicked face
    public String facingFrom;
    public java.util.List<DropEntry> drops = new java.util.ArrayList<>();

    public static class DropEntry {
        public String item;
        public int count = 1;
        public float chance = 1.0f;
    }

    public void applyDefaults() {
        if (hardness == null) hardness = 1.5f;
        if (resistance == null) resistance = 6.0f;
        if (lightLevel == null) lightLevel = 0;
        if (sound == null) sound = "stone";
        if (slipperiness == null) slipperiness = 0.6f;
        if (dropsSelf == null) dropsSelf = true;
        if (transparent == null) transparent = false;
        if (collidable == null) collidable = true;
        if (requiresTool == null) requiresTool = false;
    }

    public void applyPreset(String presetName) {
        // Blocks don't have presets mapped currently, but architecture allows it
    }

    public void applyJson(JsonObject json) {
        if (json.has("id")) this.id = json.get("id").getAsString();
        if (json.has("type")) this.type = json.get("type").getAsString();

        if (json.has("hardness")) this.hardness = json.get("hardness").getAsFloat();
        if (json.has("destroy_time")) this.hardness = json.get("destroy_time").getAsFloat();
        if (json.has("break_time")) this.hardness = json.get("break_time").getAsFloat();
        if (json.has("resistance")) this.resistance = json.get("resistance").getAsFloat();
        if (json.has("explosion_resistance")) this.resistance = json.get("explosion_resistance").getAsFloat();
        if (json.has("strength") && json.get("strength").isJsonArray()) {
            var a = json.getAsJsonArray("strength");
            if (a.size() > 0) this.hardness = a.get(0).getAsFloat();
            if (a.size() > 1) this.resistance = a.get(1).getAsFloat();
        } else if (json.has("strength") && json.get("strength").isJsonPrimitive()) {
            this.hardness = json.get("strength").getAsFloat();
            this.resistance = json.get("strength").getAsFloat();
        }
        if (json.has("light_level")) this.lightLevel = json.get("light_level").getAsInt();
        if (json.has("own_assets")) this.ownAssets = json.get("own_assets").getAsBoolean();
        if (json.has("facing_from")) this.facingFrom = json.get("facing_from").getAsString();
        if (json.has("light_by_state") && json.get("light_by_state").isJsonObject()) {
            this.lightByState.clear();
            json.getAsJsonObject("light_by_state").entrySet().forEach(e -> this.lightByState.put(e.getKey(), e.getValue().getAsInt()));
        }
        if (json.has("luminance")) this.lightLevel = json.get("luminance").getAsInt();
        if (json.has("light")) this.lightLevel = json.get("light").getAsInt();
        if (json.has("sound")) this.sound = json.get("sound").getAsString();
        if (json.has("sound_group")) this.sound = json.get("sound_group").getAsString();
        if (json.has("slipperiness")) this.slipperiness = json.get("slipperiness").getAsFloat();
        if (json.has("friction")) this.slipperiness = json.get("friction").getAsFloat();
        if (json.has("speed_factor")) this.speedFactor = json.get("speed_factor").getAsFloat();
        if (json.has("movement_factor")) this.speedFactor = json.get("movement_factor").getAsFloat();
        if (json.has("velocity_multiplier")) this.speedFactor = json.get("velocity_multiplier").getAsFloat();
        if (json.has("jump_factor")) this.jumpFactor = json.get("jump_factor").getAsFloat();
        if (json.has("jump_velocity_multiplier")) this.jumpFactor = json.get("jump_velocity_multiplier").getAsFloat();
        if (json.has("bounce")) this.bounce = json.get("bounce").getAsFloat();
        if (json.has("bounce_restitution")) this.bounce = json.get("bounce_restitution").getAsFloat();
        if (json.has("drops_self")) this.dropsSelf = json.get("drops_self").getAsBoolean();
        if (json.has("drop_self")) this.dropsSelf = json.get("drop_self").getAsBoolean();
        if (json.has("drops_nothing")) this.dropsSelf = !json.get("drops_nothing").getAsBoolean();
        if (json.has("transparent")) this.transparent = json.get("transparent").getAsBoolean();
        if (json.has("no_occlusion")) this.transparent = json.get("no_occlusion").getAsBoolean();
        if (json.has("non_opaque")) this.transparent = json.get("non_opaque").getAsBoolean();
        if (json.has("collidable")) this.collidable = json.get("collidable").getAsBoolean();
        if (json.has("collision_enabled")) this.collidable = json.get("collision_enabled").getAsBoolean();
        if (json.has("no_collision")) this.collidable = !json.get("no_collision").getAsBoolean();
        if (json.has("random_ticks")) this.randomTicks = json.get("random_ticks").getAsBoolean();
        if (json.has("random_tick")) this.randomTicks = json.get("random_tick").getAsBoolean();
        if (json.has("ticks_randomly")) this.randomTicks = json.get("ticks_randomly").getAsBoolean();
        if (json.has("ignited_by_lava")) this.ignitedByLava = json.get("ignited_by_lava").getAsBoolean();
        if (json.has("flammable_from_lava")) this.ignitedByLava = json.get("flammable_from_lava").getAsBoolean();
        if (json.has("no_terrain_particles")) this.noTerrainParticles = json.get("no_terrain_particles").getAsBoolean();
        if (json.has("no_particles")) this.noTerrainParticles = json.get("no_particles").getAsBoolean();
        if (json.has("replaceable")) this.replaceable = json.get("replaceable").getAsBoolean();
        if (json.has("logic")) this.logic = json.get("logic").getAsString();
        if (json.has("scripts")) {
            this.scripts.clear();
            json.get("scripts").getAsJsonArray().forEach(s -> this.scripts.add(s.getAsString()));
        }
        if (json.has("script")) {
            this.scripts.clear();
            this.scripts.add(json.get("script").getAsString());
        }
        if (json.has("creative_tab")) this.creativeTab = json.get("creative_tab").getAsString();
        if (json.has("tab")) this.creativeTab = json.get("tab").getAsString();

        // Texture: string = all faces, object = per-face map
        if (json.has("texture")) {
            var texEl = json.get("texture");
            if (texEl.isJsonPrimitive()) {
                this.texture = texEl.getAsString();
            } else if (texEl.isJsonObject()) {
                texEl.getAsJsonObject().entrySet().forEach(e ->
                    this.textureFaces.put(e.getKey(), e.getValue().getAsString()));
                // "all" key = single texture for all faces
                if (this.textureFaces.containsKey("all")) {
                    this.texture = this.textureFaces.get("all");
                }
            }
        }

        // Support "stats" object (original docs format)
        if (json.has("stats")) {
            JsonObject stats = json.getAsJsonObject("stats");
            if (stats.has("hardness"))      this.hardness     = stats.get("hardness").getAsFloat();
            if (stats.has("destroy_time"))  this.hardness     = stats.get("destroy_time").getAsFloat();
            if (stats.has("resistance"))    this.resistance   = stats.get("resistance").getAsFloat();
            if (stats.has("explosion_resistance")) this.resistance = stats.get("explosion_resistance").getAsFloat();
            if (stats.has("luminance"))     this.lightLevel   = stats.get("luminance").getAsInt();
            if (stats.has("light"))         this.lightLevel   = stats.get("light").getAsInt();
            if (stats.has("friction"))      this.slipperiness = stats.get("friction").getAsFloat();
            if (stats.has("speed_factor"))  this.speedFactor  = stats.get("speed_factor").getAsFloat();
            if (stats.has("movement_factor")) this.speedFactor = stats.get("movement_factor").getAsFloat();
            if (stats.has("jump_factor"))   this.jumpFactor   = stats.get("jump_factor").getAsFloat();
            if (stats.has("bounce"))        this.bounce       = stats.get("bounce").getAsFloat();
            if (stats.has("harvest_tool"))  this.miningTool   = stats.get("harvest_tool").getAsString();
            if (stats.has("harvest_level")) this.miningLevel  = stats.get("harvest_level").getAsInt();
            if (stats.has("requires_tool")) this.requiresTool = stats.get("requires_tool").getAsBoolean();
        }

        if (json.has("mining_tool")) this.miningTool = json.get("mining_tool").getAsString();
        if (json.has("harvest_tool")) this.miningTool = json.get("harvest_tool").getAsString();
        if (json.has("tool")) this.miningTool = json.get("tool").getAsString();
        if (json.has("requires_tool")) this.requiresTool = json.get("requires_tool").getAsBoolean();
        if (json.has("requires_correct_tool")) this.requiresTool = json.get("requires_correct_tool").getAsBoolean();
        if (json.has("mining_level")) this.miningLevel = json.get("mining_level").getAsInt();
        if (json.has("harvest_level")) this.miningLevel = json.get("harvest_level").getAsInt();
        if (json.has("render_type")) this.renderType = json.get("render_type").getAsString();
        if (json.has("render_layer")) this.renderType = json.get("render_layer").getAsString();
        if (json.has("shape")) this.shape = json.get("shape").getAsString();
        if (json.has("hitbox")) this.hitbox = json.get("hitbox").deepCopy();
        if (json.has("shape_box")) this.hitbox = json.get("shape_box").deepCopy();
        if (json.has("collision_box")) this.collisionBox = json.get("collision_box").deepCopy();
        if (json.has("collision")) this.collisionBox = json.get("collision").deepCopy();
        if (json.has("redstone_power")) this.redstonePower = json.get("redstone_power").getAsInt();
        if (json.has("power")) this.redstonePower = json.get("power").getAsInt();
        if (json.has("redstone") && json.get("redstone").isJsonObject()) {
            JsonObject r = json.getAsJsonObject("redstone");
            if (r.has("power")) this.redstonePower = r.get("power").getAsInt();
            if (r.has("signal")) this.redstonePower = r.get("signal").getAsInt();
        }
        if (json.has("mass")) this.mass = json.get("mass").getAsFloat();
        if (json.has("weight")) this.mass = json.get("weight").getAsFloat();
        if (json.has("gui")) this.gui = json.get("gui").getAsString();
        if (json.has("kui")) this.gui = json.get("kui").getAsString();
        if (json.has("container")) {
            if (json.get("container").isJsonPrimitive()) this.containerMode = json.get("container").getAsString();
            else if (json.get("container").isJsonObject()) {
                JsonObject c = json.getAsJsonObject("container");
                if (c.has("mode")) this.containerMode = c.get("mode").getAsString();
                if (c.has("drop_on_break")) this.dropContainer = c.get("drop_on_break").getAsBoolean();
                if (c.has("drop_container")) this.dropContainer = c.get("drop_container").getAsBoolean();
                if (c.has("drop_contents")) this.dropContainer = c.get("drop_contents").getAsBoolean();
            }
        }
        if (json.has("container_mode")) this.containerMode = json.get("container_mode").getAsString();
        if (json.has("drop_container")) this.dropContainer = json.get("drop_container").getAsBoolean();
        if (json.has("tick_interval")) this.tickInterval = json.get("tick_interval").getAsInt();
        if (json.has("tick_radius")) this.tickRadius = json.get("tick_radius").getAsInt();
        if (json.has("tick") && json.get("tick").isJsonObject()) {
            JsonObject t = json.getAsJsonObject("tick");
            if (t.has("interval")) this.tickInterval = t.get("interval").getAsInt();
            if (t.has("radius")) this.tickRadius = t.get("radius").getAsInt();
        }
        if (json.has("block_entity")) this.blockEntity = json.get("block_entity").getAsBoolean();
        if (json.has("brain")) this.blockEntity = json.get("brain").getAsBoolean();
        if (json.has("events") && json.get("events").isJsonObject()) this.events = json.getAsJsonObject("events");
        if (json.has("connect_group")) this.connectGroup = json.get("connect_group").getAsString();
        if (json.has("connection_group")) this.connectGroup = json.get("connection_group").getAsString();
        if (json.has("connects_to")) this.connectGroup = json.get("connects_to").getAsString();
        if (json.has("connected")) {
            if (json.get("connected").isJsonPrimitive()) {
                this.connectGroup = json.get("connected").getAsString();
            } else if (json.get("connected").isJsonObject()) {
                JsonObject c = json.getAsJsonObject("connected");
                if (c.has("group")) this.connectGroup = c.get("group").getAsString();
                if (c.has("id")) this.connectGroup = c.get("id").getAsString();
                if (c.has("vertical")) this.connectVertical = c.get("vertical").getAsBoolean();
                if (c.has("y")) this.connectVertical = c.get("y").getAsBoolean();
            }
        }
        if (json.has("connect_vertical")) this.connectVertical = json.get("connect_vertical").getAsBoolean();
        if (json.has("connected_textures") && json.get("connected_textures").isJsonObject()) {
            json.getAsJsonObject("connected_textures").entrySet()
                .forEach(e -> this.connectedTextures.put(e.getKey().toLowerCase(), e.getValue().getAsString()));
        }
        if (json.has("ctm") && json.get("ctm").isJsonObject()) {
            json.getAsJsonObject("ctm").entrySet()
                .forEach(e -> this.connectedTextures.put(e.getKey().toLowerCase(), e.getValue().getAsString()));
        }
        readConnectedCubeGuis(json, "connected_cube_guis");
        readConnectedCubeGuis(json, "gui_by_connected_cube");
        readConnectedCubeGuis(json, "gui_by_cube_size");
        if (json.has("vault") && json.get("vault").isJsonObject()) {
            JsonObject v = json.getAsJsonObject("vault");
            readConnectedCubeGuis(v, "guis");
            readConnectedCubeGuis(v, "gui_by_size");
        }
        for (String ev : new String[]{"on_use", "on_place", "on_break", "on_tick", "on_step"}) {
            if (json.has(ev)) {
                if (this.events == null) this.events = new com.google.gson.JsonObject();
                this.events.add(ev, json.get(ev));
            }
        }
        if (json.has("drops") && json.get("drops").isJsonArray()) {
            this.drops.clear();
            json.getAsJsonArray("drops").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    DropEntry d = new DropEntry();
                    d.item = e.getAsString();
                    this.drops.add(d);
                } else if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    DropEntry d = new DropEntry();
                    d.item = o.has("item") ? o.get("item").getAsString() : o.has("id") ? o.get("id").getAsString() : null;
                    d.count = o.has("count") ? o.get("count").getAsInt() : 1;
                    d.chance = o.has("chance") ? o.get("chance").getAsFloat() : 1.0f;
                    if (d.item != null) this.drops.add(d);
                }
            });
        }
        if (json.has("states") && json.get("states").isJsonArray()) {
            this.stateProperties.clear();
            json.getAsJsonArray("states").forEach(e -> this.stateProperties.add(e.getAsString()));
        }
        if (json.has("block_states") && json.get("block_states").isJsonArray()) {
            this.stateProperties.clear();
            json.getAsJsonArray("block_states").forEach(e -> this.stateProperties.add(e.getAsString()));
        }
        if (json.has("default_states") && json.get("default_states").isJsonObject()) {
            this.defaultStates.clear();
            json.getAsJsonObject("default_states").entrySet()
                    .forEach(e -> this.defaultStates.put(e.getKey(), e.getValue().getAsString()));
        }
    }

    private void readConnectedCubeGuis(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) return;
        json.getAsJsonObject(key).entrySet().forEach(e -> {
            try {
                connectedCubeGuis.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
            } catch (NumberFormatException ignored) {
            }
        });
    }
}
// i am not stupid enough i used ai yes but not too much mainly to explin my own code to myself and remade it a bit ig
