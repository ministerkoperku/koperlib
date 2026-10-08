package com.koper.koper_lib.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class KoperEntityData implements KoperData {
    public String id;
    public String type;
    public String preset;

    public Float maxHealth;
    public Float movementSpeed;
    public Float attackDamage;
    public String aiType;

    public String model;
    // drawn by nothing at all: a bedrock pack's helper entity with no client entity (rlcraft's trinket storage)
    public Boolean invisible;
    public String texture;
    public int tint = com.koper.koper_lib.api.FullpackColors.NONE; // procedural color tint (color/colors)
    public Object animations; // String (AUTO path) or JsonObject (MANUAL) (fuck it means? idk it works tho )
    public Boolean presetAnimations;

    public List<DropData> drops = new ArrayList<>();
    public Boolean burnsInDaylight;
    public Boolean ranged;
    public String logic;
    public List<String> scripts = new ArrayList<>();
    public String creativeTab;
    public String spawnEggPrimary;
    public String spawnEggSecondary;

    public Float followRange;
    public Float armor;
    public java.util.List<AttributeEntry> attributes = new java.util.ArrayList<>();
    public com.google.gson.JsonObject events;

    // Entity dimensions
    public Float width;
    public Float height;

    // AI goals (eg swim attack_melee wander look_at_player)
    public List<String> entityAi = new ArrayList<>();

    // Animation fields
    public String deathAnimation;
    public String spawnAnimation;
    public String runAnimation;
    public String idleAnimation;
    public String attackAnimation;

    // Animation conditions: list of when value play objects
    public List<AnimationCondition> animationConditions = new ArrayList<>();

 
    public String spawnEggTexture;

    
    public Boolean persistent;          
    public Boolean noAi;                
    public Boolean baby;               
    public String tamingItem;          
    public Boolean rideable;            
    public Float rideSpeed;             

    // Bone hitboxes: bone_name → BoneHitboxDef
    public java.util.Map<String, BoneHitboxDef> hitboxBones = new java.util.HashMap<>();
    public Boolean obbHitboxes; // true = own OBB hitbox system from bones named *hitbox*

    public static class BoneHitboxDef {
        public float width = 0.5f;
        public float height = 0.5f;
        public float depth = 0.5f;
        public float offsetX = 0, offsetY = 0, offsetZ = 0;
        public float damageMultiplier = 1.0f;
    }

    public static class DropData {
        public String item;
        public int count;
        public float chance;
    }

    public static class AnimationCondition {
        public String when;     // eg health_below on_fire in_water attacking
        public double value;    // threshold value eg 5 for health_below
        public String play;     // animation name to play
    }

    public static class AttributeEntry {
        public String attribute;
        public String modifierId;
        public double amount;
        public String operation = "add_value";
    }

    public void applyDefaults() {
        if (maxHealth == null) maxHealth = 10.0f;
        if (movementSpeed == null) movementSpeed = 0.2f;
        if (attackDamage == null) attackDamage = 0.0f;
        if (aiType == null) aiType = "PASSIVE";
        if (presetAnimations == null) presetAnimations = false;
        if (burnsInDaylight == null) burnsInDaylight = false;
        if (ranged == null) ranged = false;
        if (width == null) width = 0.6f;
        if (height == null) height = 1.8f;
    }

    public void applyPreset(String presetName) {
        // Preset system replaced by inline JSON ai_preset field 
        // Built-in presets applied in EntityFactory based on "ai_preset" string.
    }

    public void applyJson(JsonObject json) {
        if (json.has("id")) this.id = json.get("id").getAsString();
        if (json.has("type")) this.type = json.get("type").getAsString();
        this.tint = com.koper.koper_lib.api.FullpackColors.fromJson(json);
        if (json.has("preset")) this.preset = json.get("preset").getAsString();

        if (json.has("health")) this.maxHealth = json.get("health").getAsFloat();
        if (json.has("movement_speed")) this.movementSpeed = json.get("movement_speed").getAsFloat();
        if (json.has("attack_damage")) this.attackDamage = json.get("attack_damage").getAsFloat();
        if (json.has("ai_type")) this.aiType = json.get("ai_type").getAsString();

        if (json.has("model")) this.model = json.get("model").getAsString();
        if (json.has("invisible")) this.invisible = json.get("invisible").getAsBoolean();
        if (json.has("texture")) this.texture = json.get("texture").getAsString();
        if (json.has("preset_animations")) this.presetAnimations = json.get("preset_animations").getAsBoolean();

        if (json.has("width")) this.width = json.get("width").getAsFloat();
        if (json.has("height")) this.height = json.get("height").getAsFloat();

        if (json.has("animations")) {
            if (json.get("animations").isJsonObject()) {
                com.google.gson.JsonObject a = json.getAsJsonObject("animations");
                this.animations = a;
                // consolidated schema: animations:{ path, idle, walk, attack, death } -> state->clip mappings
                // (flat *_animation keys below still override these if present)
                if (a.has("idle"))   this.idleAnimation   = a.get("idle").getAsString();
                if (a.has("walk"))   this.runAnimation    = a.get("walk").getAsString();
                if (a.has("run"))    this.runAnimation    = a.get("run").getAsString();
                if (a.has("attack")) this.attackAnimation = a.get("attack").getAsString();
                if (a.has("death"))  this.deathAnimation  = a.get("death").getAsString();
            } else {
                this.animations = json.get("animations").getAsString();
            }
        }

        if (json.has("drops") && json.get("drops").isJsonArray()) {
            this.drops.clear();
            json.getAsJsonArray("drops").forEach(e -> {
                JsonObject d = e.getAsJsonObject();
                DropData data = new DropData();
                data.item = d.get("item").getAsString();
                data.count = d.has("count") ? d.get("count").getAsInt() : 1;
                data.chance = d.has("chance") ? d.get("chance").getAsFloat() : 1.0f;
                this.drops.add(data);
            });
        }

        if (json.has("burns_in_daylight")) this.burnsInDaylight = json.get("burns_in_daylight").getAsBoolean();
        if (json.has("ranged")) this.ranged = json.get("ranged").getAsBoolean();
        if (json.has("logic")) this.logic = json.get("logic").getAsString();
        if (json.has("ai_script")) this.logic = json.get("ai_script").getAsString();
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

        // stats {} object (original docs format)
        if (json.has("stats")) {
            JsonObject stats = json.getAsJsonObject("stats");
            if (stats.has("health"))       this.maxHealth      = stats.get("health").getAsFloat();
            if (stats.has("damage"))       this.attackDamage   = stats.get("damage").getAsFloat();
            if (stats.has("speed"))        this.movementSpeed  = stats.get("speed").getAsFloat();
            if (stats.has("follow_range")) this.followRange    = stats.get("follow_range").getAsFloat();
            if (stats.has("armor"))        this.armor          = stats.get("armor").getAsFloat();
            if (stats.has("attributes"))   readAttributes(stats.get("attributes"));
        }
        if (json.has("follow_range")) this.followRange = json.get("follow_range").getAsFloat();
        if (json.has("armor"))        this.armor       = json.get("armor").getAsFloat();
        if (json.has("attributes")) readAttributes(json.get("attributes"));
        if (json.has("attribute_modifiers")) readAttributes(json.get("attribute_modifiers"));
        if (json.has("spawn_egg_primary")) this.spawnEggPrimary = json.get("spawn_egg_primary").getAsString();
        if (json.has("spawn_egg_secondary")) this.spawnEggSecondary = json.get("spawn_egg_secondary").getAsString();
        if (json.has("spawn_egg_texture")) this.spawnEggTexture = json.get("spawn_egg_texture").getAsString();
        if (json.has("persistent")) this.persistent = json.get("persistent").getAsBoolean();
        if (json.has("no_ai")) this.noAi = json.get("no_ai").getAsBoolean();
        if (json.has("baby")) this.baby = json.get("baby").getAsBoolean();
        if (json.has("taming_item")) this.tamingItem = json.get("taming_item").getAsString();
        if (json.has("rideable")) this.rideable = json.get("rideable").getAsBoolean();
        if (json.has("ride_speed")) this.rideSpeed = json.get("ride_speed").getAsFloat();

        // Entity AI goals
        if (json.has("entity_ai") && json.get("entity_ai").isJsonArray()) {
            this.entityAi.clear();
            json.getAsJsonArray("entity_ai").forEach(e -> this.entityAi.add(e.getAsString()));
        }

        // Animation fields
        if (json.has("death_animation")) this.deathAnimation = json.get("death_animation").getAsString();
        if (json.has("spawn_animation")) this.spawnAnimation = json.get("spawn_animation").getAsString();
        if (json.has("run_animation")) this.runAnimation = json.get("run_animation").getAsString();
        if (json.has("idle_animation")) this.idleAnimation = json.get("idle_animation").getAsString();
        if (json.has("attack_animation")) this.attackAnimation = json.get("attack_animation").getAsString();

        // OBB bone-hitboxes: own oriented-box system from bones named *hitbox* (instead of mc aabb)
        if (json.has("obb_hitboxes")) this.obbHitboxes = json.get("obb_hitboxes").getAsBoolean();

        // Bone hitboxes
        if (json.has("hitbox_bones") && json.get("hitbox_bones").isJsonObject()) {
            this.hitboxBones.clear();
            JsonObject bones = json.getAsJsonObject("hitbox_bones");
            for (String boneName : bones.keySet()) {
                JsonObject boneObj = bones.getAsJsonObject(boneName);
                BoneHitboxDef def = new BoneHitboxDef();
                if (boneObj.has("size") && boneObj.get("size").isJsonArray()) {
                    var arr = boneObj.getAsJsonArray("size");
                    if (arr.size() >= 1) def.width = arr.get(0).getAsFloat();
                    if (arr.size() >= 2) def.height = arr.get(1).getAsFloat();
                    if (arr.size() >= 3) def.depth = arr.get(2).getAsFloat();
                }
                if (boneObj.has("width")) def.width = boneObj.get("width").getAsFloat();
                if (boneObj.has("height")) def.height = boneObj.get("height").getAsFloat();
                if (boneObj.has("depth")) def.depth = boneObj.get("depth").getAsFloat();
                if (boneObj.has("offset") && boneObj.get("offset").isJsonArray()) {
                    var arr = boneObj.getAsJsonArray("offset");
                    if (arr.size() >= 1) def.offsetX = arr.get(0).getAsFloat();
                    if (arr.size() >= 2) def.offsetY = arr.get(1).getAsFloat();
                    if (arr.size() >= 3) def.offsetZ = arr.get(2).getAsFloat();
                }
                if (boneObj.has("damage_multiplier")) def.damageMultiplier = boneObj.get("damage_multiplier").getAsFloat();
                this.hitboxBones.put(boneName, def);
            }
        }

        // Animation conditions
        if (json.has("animation_conditions") && json.get("animation_conditions").isJsonArray()) {
            this.animationConditions.clear();
            json.getAsJsonArray("animation_conditions").forEach(e -> {
                JsonObject cond = e.getAsJsonObject();
                AnimationCondition ac = new AnimationCondition();
                ac.when = cond.has("when") ? cond.get("when").getAsString() : "always";
                ac.value = cond.has("value") ? cond.get("value").getAsDouble() : 0;
                ac.play = cond.has("play") ? cond.get("play").getAsString() : "idle";
                this.animationConditions.add(ac);
            });
        }

        if (json.has("events") && json.get("events").isJsonObject()) this.events = json.getAsJsonObject("events");
        for (String ev : new String[]{"on_spawn", "on_tick", "on_interact", "on_damage", "on_death", "on_target"}) {
            if (json.has(ev)) {
                if (this.events == null) this.events = new com.google.gson.JsonObject();
                this.events.add(ev, json.get(ev));
            }
        }
    }

    private void readAttributes(com.google.gson.JsonElement el) {
        this.attributes.clear();
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            el.getAsJsonArray().forEach(e -> {
                if (!e.isJsonObject()) return;
                AttributeEntry a = readAttributeEntry(e.getAsJsonObject(), null);
                if (a.attribute != null && !a.attribute.isBlank()) this.attributes.add(a);
            });
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        if (o.has("attribute") || o.has("id") || o.has("type")) {
            AttributeEntry a = readAttributeEntry(o, null);
            if (a.attribute != null && !a.attribute.isBlank()) this.attributes.add(a);
            return;
        }
        for (var ent : o.entrySet()) {
            AttributeEntry a;
            if (ent.getValue().isJsonObject()) {
                a = readAttributeEntry(ent.getValue().getAsJsonObject(), ent.getKey());
            } else if (ent.getValue().isJsonPrimitive()) {
                a = new AttributeEntry();
                a.attribute = ent.getKey();
                a.amount = ent.getValue().getAsDouble();
            } else {
                continue;
            }
            if (a.attribute != null && !a.attribute.isBlank()) this.attributes.add(a);
        }
    }

    private AttributeEntry readAttributeEntry(JsonObject o, String fallbackAttribute) {
        AttributeEntry a = new AttributeEntry();
        a.attribute = o.has("attribute") ? o.get("attribute").getAsString()
                : o.has("id") ? o.get("id").getAsString()
                : o.has("type") ? o.get("type").getAsString()
                : fallbackAttribute;
        if (o.has("modifier_id")) a.modifierId = o.get("modifier_id").getAsString();
        if (o.has("modifier")) a.modifierId = o.get("modifier").getAsString();
        if (o.has("amount")) a.amount = o.get("amount").getAsDouble();
        if (o.has("value")) a.amount = o.get("value").getAsDouble();
        if (o.has("operation")) a.operation = o.get("operation").getAsString();
        if (o.has("op")) a.operation = o.get("op").getAsString();
        return a;
    }
}
