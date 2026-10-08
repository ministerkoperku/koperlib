package com.koper.koper_lib.data;

import com.google.gson.JsonObject;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// sorted by ai SOrry
public class KoperItemData implements KoperData {
    public String id;
    public String type;
    public String preset;
    public String texture;
    public String toolTier;
    public ToolTierDef customToolTier;
    public String defaultState = "idle";
    public java.util.Map<String, ItemStateDef> itemStates = new java.util.LinkedHashMap<>();
    public java.util.Map<String, ItemAnimationDef> itemAnimations = new java.util.LinkedHashMap<>();

    // Combat
    public Integer damage;
    public Integer durability;
    public Double attackSpeed;
    public Double miningSpeed;
    public Rarity rarity;

    // Food
    public Integer foodHunger;
    public Float foodSaturation;
    public Boolean alwaysEdible;
    public List<String> effects = new ArrayList<>();
    public List<EffectEntry> consumeEffects = new ArrayList<>();

    // General
    public Boolean fireproof;
    public Integer maxStack;
    public String creativeTab;
    public String logic;
    public List<String> scripts = new ArrayList<>();

    // Armor & Weapons
    public Integer defense;
    public Float toughness;
    public Float knockbackResistance;
    public String slot;
    public Integer enchantability;
    public String armorTextureLayer1; // explicit armor texture path
    public String armorTextureLayer2;

    // geo armor: a 3D model rendered on the wearer (skeletal), following the body bones
    public String model;            // model name: a .kodel, or a .geo.json kodel converts
    public String armorTexture;     // texture for the geo armor model
    public String armorAnimation;   // optional .animation.json clip to play on the worn model
    public float armorScale = 1f;             // uniform size multiplier
    public float[] armorOffset;               // x,y,z block offset (model placement)
    public float[] armorRotate;               // x,y,z degrees (model orientation)
    public java.util.Map<String, String> armorBones; // bone -> body part (head/body/right_arm/left_arm/right_leg/left_leg)
    public java.util.Map<String, float[]> bonePlacement; // per-bone fine offset [x,y,z] on top of the base placement
    public String itemDisplay;                // "texture" (default 2D icon) or "model" (3D geo model as the item icon)
    public int tint = com.koper.koper_lib.api.FullpackColors.NONE; // procedural color tint (color/colors)
    public boolean dyeable;                    // accept dyes in-game; render follows the dyed color
    public java.util.List<String> renderBones; // per-slot: only these bones draw (helmet -> ["Head"]); null = whole model
    public Float itemScale;                    // 3D-model item icon size (GUI/ground/hand)
    public float[] itemOffset;                 // 3D-model item icon x,y,z placement

    // Bow/Crossbow
    public Float projectileSpeed;
    public Float drawTime;

    // Spear
    public Boolean throwable;
    public String textureInHand; // narrow in-hand sprite for spears (optional, most people dont need this)

    // JSON-only scripting alternatives (A6)
    public List<EffectEntry> onUseEffects = new ArrayList<>();
    public List<EffectEntry> onHitEffects = new ArrayList<>();
    public String onHitCommand; // eg say player hit target
    public Integer cooldownTicks; // applied after use
    public Boolean consumeOnUse; // decrement stack on right-click
    public Integer rightClickDropXp; // spawn XP orbs on right-click
    public Double attackAoeRadius; // AoE damage radius on hit (0.5× base damage)

    /** Effect entry for on_use_effects / on_hit_effects arrays. */
    public record EffectEntry(String id, int ticks, int amplifier, float chance) {
    }

    // Glow/special
    public Boolean glint; // enchantment glint without enchantment

    // Reach attribute
    public Double reach;
    public java.util.List<AttributeEntry> attributes = new java.util.ArrayList<>();

    // Item component extras
    public List<String> lore = new ArrayList<>();
    public Map<String, Integer> enchantments = new HashMap<>();
    public Boolean unbreakable;
    public Integer customModelData;
    public Float useSeconds;
    public String useAnimation;
    public String useSound;
    public Boolean useParticles;
    public Float useCooldownSeconds;
    public String cooldownGroup;
    public String useRemainder;
    public java.util.List<String> repairItems = new java.util.ArrayList<>();
    public com.google.gson.JsonObject events;

    public static class AttributeEntry {
        public String attribute;
        public String modifierId;
        public double amount;
        public String operation = "add_value";
        public String slot;
        public boolean hidden;
    }

    public static class ToolTierDef {
        public String name;
        public int durability = 250;
        public float speed = 6.0f;
        public float attackDamageBonus = 2.0f;
        public int enchantability = 14;
        public int miningLevel = 2;
        public java.util.List<String> repairItems = new java.util.ArrayList<>();
        public java.util.List<String> incorrectFor = new java.util.ArrayList<>();
        public String incorrectForTag;
        public String repairTag;
    }

    public static class ItemStateDef {
        public String name;
        public String texture;
        public String modelType;
        public int customModelData;
        public Integer frametime;
        public java.util.List<Integer> frames = new java.util.ArrayList<>();
        public Boolean interpolate;
    }

    public static class ItemAnimationDef {
        public String name;
        public java.util.List<ItemAnimationFrame> frames = new java.util.ArrayList<>();
        public String resetTo;
        public boolean interrupt = true;
    }

    public static class ItemAnimationFrame {
        public String state;
        public int ticks = 1;
    }

    public void applyDefaults() {
        if (damage == null)
            damage = 1;
        // durability: no global default — food/material/gem would get forced to stacksTo(1) with it
        // weapons get durability from ToolMaterial, ranged/shield need explicit JSON or ItemFactory defaults
        if (attackSpeed == null)
            attackSpeed = -2.4;
        if (miningSpeed == null)
            miningSpeed = 1.0;
        if (rarity == null)
            rarity = Rarity.COMMON;
        if (fireproof == null)
            fireproof = false;
        if (maxStack == null)
            maxStack = 64;
        if (alwaysEdible == null)
            alwaysEdible = false;
    }

    public void applyPreset(String presetName) {

    }

    private static float[] readVec3(com.google.gson.JsonArray a) {
        if (a == null || a.size() < 3) return null;
        return new float[]{ a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat() };
    }

    private static ItemStateDef readItemState(String name, JsonObject json, int index) {
        ItemStateDef def = new ItemStateDef();
        def.name = name;
        def.texture = json.has("texture") ? json.get("texture").getAsString() : null;
        def.modelType = json.has("model_type") ? json.get("model_type").getAsString() : null;
        def.customModelData = json.has("custom_model_data") ? json.get("custom_model_data").getAsInt() : index;
        if (json.has("frametime")) def.frametime = json.get("frametime").getAsInt();
        if (json.has("animation") && json.get("animation").isJsonObject()) {
            JsonObject a = json.getAsJsonObject("animation");
            if (a.has("frametime")) def.frametime = a.get("frametime").getAsInt();
            if (a.has("interpolate")) def.interpolate = a.get("interpolate").getAsBoolean();
            if (a.has("frames") && a.get("frames").isJsonArray()) {
                a.getAsJsonArray("frames").forEach(e -> {
                    if (e.isJsonPrimitive()) def.frames.add(e.getAsInt());
                });
            }
        }
        return def;
    }

    private int nextCustomModelData() {
        return itemStates.values().stream().mapToInt(s -> s.customModelData).max().orElse(0) + 1;
    }

    private void readItemAnimations(JsonObject json) {
        if (!json.has("item_animations") || !json.get("item_animations").isJsonObject()) return;
        this.itemAnimations.clear();
        for (var ent : json.getAsJsonObject("item_animations").entrySet()) {
            ItemAnimationDef anim = new ItemAnimationDef();
            anim.name = ent.getKey();
            if (ent.getValue().isJsonArray()) {
                readAnimationFrames(anim, ent.getValue().getAsJsonArray());
            } else if (ent.getValue().isJsonObject()) {
                JsonObject o = ent.getValue().getAsJsonObject();
                if (o.has("reset_to")) anim.resetTo = o.get("reset_to").getAsString();
                if (o.has("interrupt")) anim.interrupt = o.get("interrupt").getAsBoolean();
                if (o.has("frames") && o.get("frames").isJsonArray()) {
                    readAnimationFrames(anim, o.getAsJsonArray("frames"));
                }
            }
            if (!anim.frames.isEmpty()) this.itemAnimations.put(anim.name, anim);
        }
    }

    private void readAnimationFrames(ItemAnimationDef anim, com.google.gson.JsonArray frames) {
        int i = 0;
        for (var el : frames) {
            ItemAnimationFrame frame = new ItemAnimationFrame();
            if (el.isJsonPrimitive()) {
                frame.state = el.getAsString();
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                frame.state = o.has("state") ? o.get("state").getAsString()
                        : o.has("name") ? o.get("name").getAsString()
                        : anim.name + "_" + i;
                frame.ticks = o.has("ticks") ? Math.max(1, o.get("ticks").getAsInt())
                        : o.has("duration") ? Math.max(1, o.get("duration").getAsInt()) : 1;
                if (o.has("texture") && !itemStates.containsKey(frame.state)) {
                    ItemStateDef state = readItemState(frame.state, o, nextCustomModelData());
                    itemStates.put(state.name, state);
                }
            }
            if (frame.state != null && !frame.state.isBlank()) anim.frames.add(frame);
            i++;
        }
    }

    public void applyJson(JsonObject json) {
        if (json.has("id"))
            this.id = json.get("id").getAsString();
        if (json.has("type"))
            this.type = json.get("type").getAsString();
        if (json.has("preset"))
            this.preset = json.get("preset").getAsString();
        if (json.has("texture"))
            this.texture = json.get("texture").getAsString();
        if (json.has("tool_tier")) readToolTier(json.get("tool_tier"));
        if (json.has("tier")) readToolTier(json.get("tier"));
        if (json.has("tool_material")) readToolTier(json.get("tool_material"));
        if (json.has("default_state"))
            this.defaultState = json.get("default_state").getAsString();

        if (json.has("damage"))
            this.damage = json.get("damage").getAsInt();
        if (json.has("durability"))
            this.durability = json.get("durability").getAsInt();
        if (json.has("max_damage"))
            this.durability = json.get("max_damage").getAsInt();
        if (json.has("max_durability"))
            this.durability = json.get("max_durability").getAsInt();
        if (json.has("attack_speed"))
            this.attackSpeed = json.get("attack_speed").getAsDouble();
        if (json.has("mining_speed"))
            this.miningSpeed = json.get("mining_speed").getAsDouble();

        if (json.has("rarity")) {
            String r = json.get("rarity").getAsString().toUpperCase();
            try {
                this.rarity = Rarity.valueOf(r);
            } catch (Exception ignored) {
            }
        }

        // accept both "food_hunger" and shorthand "hunger"
        if (json.has("food_hunger"))     this.foodHunger = json.get("food_hunger").getAsInt();
        if (json.has("hunger"))          this.foodHunger = json.get("hunger").getAsInt();
        if (json.has("food_saturation")) this.foodSaturation = json.get("food_saturation").getAsFloat();
        if (json.has("saturation"))      this.foodSaturation = json.get("saturation").getAsFloat();
        if (json.has("always_edible"))   this.alwaysEdible = json.get("always_edible").getAsBoolean();
        if (json.has("always_eat"))      this.alwaysEdible = json.get("always_eat").getAsBoolean();
        if (json.has("food") && json.get("food").isJsonObject()) readFoodObject(json.getAsJsonObject("food"));
        if (json.has("consumable") && json.get("consumable").isJsonObject()) readConsumableObject(json.getAsJsonObject("consumable"));

        if (json.has("effects")) {
            this.effects.clear();
            this.consumeEffects.clear();
            json.get("effects").getAsJsonArray().forEach(e -> {
                if (e.isJsonPrimitive()) {
                    this.effects.add(e.getAsString());
                    this.consumeEffects.add(new EffectEntry(e.getAsString(), 200, 0, 1.0f));
                } else if (e.isJsonObject()) {
                    EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                    if (entry != null) this.consumeEffects.add(entry);
                }
            });
        }
        if (json.has("consume_effects") && json.get("consume_effects").isJsonArray()) {
            this.consumeEffects.clear();
            json.getAsJsonArray("consume_effects").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    this.consumeEffects.add(new EffectEntry(e.getAsString(), 200, 0, 1.0f));
                } else if (e.isJsonObject()) {
                    EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                    if (entry != null) this.consumeEffects.add(entry);
                }
            });
        }
        if (json.has("food_effects") && json.get("food_effects").isJsonArray()) {
            this.consumeEffects.clear();
            json.getAsJsonArray("food_effects").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    this.consumeEffects.add(new EffectEntry(e.getAsString(), 200, 0, 1.0f));
                } else if (e.isJsonObject()) {
                    EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                    if (entry != null) this.consumeEffects.add(entry);
                }
            });
        }

        if (json.has("fireproof"))
            this.fireproof = json.get("fireproof").getAsBoolean();
        if (json.has("fire_resistant"))
            this.fireproof = json.get("fire_resistant").getAsBoolean();
        if (json.has("fireproof_item"))
            this.fireproof = json.get("fireproof_item").getAsBoolean();
        if (json.has("max_stack"))
            this.maxStack = json.get("max_stack").getAsInt();
        if (json.has("stack_size"))
            this.maxStack = json.get("stack_size").getAsInt();
        if (json.has("max_count"))
            this.maxStack = json.get("max_count").getAsInt();
        if (json.has("logic"))
            this.logic = json.get("logic").getAsString();

        if (json.has("scripts")) {
            this.scripts.clear();
            json.get("scripts").getAsJsonArray().forEach(s -> this.scripts.add(s.getAsString()));
        }

        if (json.has("creative_tab"))
            this.creativeTab = json.get("creative_tab").getAsString();
        if (json.has("tab"))
            this.creativeTab = json.get("tab").getAsString();
        if (json.has("subtype"))
            this.type = json.get("subtype").getAsString();

        if (json.has("stats")) {
            JsonObject stats = json.getAsJsonObject("stats");
            if (stats.has("damage"))
                this.damage = stats.get("damage").getAsInt();
            if (stats.has("durability"))
                this.durability = stats.get("durability").getAsInt();
            if (stats.has("max_damage"))
                this.durability = stats.get("max_damage").getAsInt();
            if (stats.has("max_stack"))
                this.maxStack = stats.get("max_stack").getAsInt();
            if (stats.has("stack_size"))
                this.maxStack = stats.get("stack_size").getAsInt();
            if (stats.has("attack_speed"))
                this.attackSpeed = stats.get("attack_speed").getAsDouble();
            if (stats.has("mining_speed"))
                this.miningSpeed = stats.get("mining_speed").getAsDouble();
            if (stats.has("defense"))
                this.defense = stats.get("defense").getAsInt();
            if (stats.has("toughness"))
                this.toughness = stats.get("toughness").getAsFloat();
            if (stats.has("knockback_resistance"))
                this.knockbackResistance = stats.get("knockback_resistance").getAsFloat();
            if (stats.has("reach"))
                this.reach = stats.get("reach").getAsDouble();
            if (stats.has("attributes"))
                readAttributes(stats.get("attributes"));
        }

        if (json.has("reach"))
            this.reach = json.get("reach").getAsDouble();
        if (json.has("attributes"))
            readAttributes(json.get("attributes"));
        if (json.has("attribute_modifiers"))
            readAttributes(json.get("attribute_modifiers"));

        if (json.has("defense"))
            this.defense = json.get("defense").getAsInt();
        if (json.has("toughness"))
            this.toughness = json.get("toughness").getAsFloat();
        if (json.has("knockback_resistance"))
            this.knockbackResistance = json.get("knockback_resistance").getAsFloat();
        if (json.has("slot"))
            this.slot = json.get("slot").getAsString();
        if (json.has("armor_type"))
            this.slot = json.get("armor_type").getAsString();
        if (json.has("part"))
            this.slot = json.get("part").getAsString();
        if (json.has("enchantability"))
            this.enchantability = json.get("enchantability").getAsInt();
        if (json.has("enchantment_value"))
            this.enchantability = json.get("enchantment_value").getAsInt();
        if (json.has("enchanting") && json.get("enchanting").isJsonObject()) {
            JsonObject enchanting = json.getAsJsonObject("enchanting");
            if (enchanting.has("value")) this.enchantability = enchanting.get("value").getAsInt();
            if (enchanting.has("enchantability")) this.enchantability = enchanting.get("enchantability").getAsInt();
            if (enchanting.has("enchantment_value")) this.enchantability = enchanting.get("enchantment_value").getAsInt();
        }
        if (json.has("texture_layer_1"))
            this.armorTextureLayer1 = json.get("texture_layer_1").getAsString();
        if (json.has("texture_layer_2"))
            this.armorTextureLayer2 = json.get("texture_layer_2").getAsString();

        // geo armor (3D model on the wearer). "model" + optional "armor_texture"/"armor_animation"
        if (json.has("model")) this.model = json.get("model").getAsString();
        if (json.has("armor_texture")) this.armorTexture = json.get("armor_texture").getAsString();
        if (json.has("armor_animation")) this.armorAnimation = json.get("armor_animation").getAsString();
        if (json.has("armor_scale")) this.armorScale = json.get("armor_scale").getAsFloat();
        if (json.has("armor_offset")) this.armorOffset = readVec3(json.getAsJsonArray("armor_offset"));
        if (json.has("armor_rotate")) this.armorRotate = readVec3(json.getAsJsonArray("armor_rotate"));
        if (json.has("armor_bones")) {
            this.armorBones = new java.util.HashMap<>();
            for (var ent : json.getAsJsonObject("armor_bones").entrySet())
                this.armorBones.put(ent.getKey(), ent.getValue().getAsString());
        }
        if (json.has("item_display")) this.itemDisplay = json.get("item_display").getAsString();
        this.tint = com.koper.koper_lib.api.FullpackColors.fromJson(json);
        if (json.has("dyeable")) this.dyeable = json.get("dyeable").getAsBoolean();
        if (json.has("render_bones") && json.get("render_bones").isJsonArray()) {
            this.renderBones = new java.util.ArrayList<>();
            json.getAsJsonArray("render_bones").forEach(e -> this.renderBones.add(e.getAsString()));
        }
        if (json.has("item_scale")) this.itemScale = json.get("item_scale").getAsFloat();
        if (json.has("item_offset")) this.itemOffset = readVec3(json.getAsJsonArray("item_offset"));
        if (json.has("bone_placement") && json.get("bone_placement").isJsonObject()) {
            this.bonePlacement = new java.util.HashMap<>();
            for (var ent : json.getAsJsonObject("bone_placement").entrySet())
                if (ent.getValue().isJsonArray()) this.bonePlacement.put(ent.getKey(), readVec3(ent.getValue().getAsJsonArray()));
        }

        if (json.has("script")) {
            this.scripts.clear();
            this.scripts.add(json.get("script").getAsString());
        }

        if (json.has("projectile_speed"))
            this.projectileSpeed = json.get("projectile_speed").getAsFloat();
        if (json.has("draw_time"))
            this.drawTime = json.get("draw_time").getAsFloat();
        if (json.has("throwable"))
            this.throwable = json.get("throwable").getAsBoolean();
        if (json.has("texture_in_hand"))
            this.textureInHand = json.get("texture_in_hand").getAsString();
        if (json.has("glint"))
            this.glint = json.get("glint").getAsBoolean();

        if (json.has("lore") && json.get("lore").isJsonArray()) {
            this.lore.clear();
            json.getAsJsonArray("lore").forEach(e -> this.lore.add(e.getAsString()));
        }
        if (json.has("enchantments") && json.get("enchantments").isJsonObject()) {
            this.enchantments.clear();
            json.getAsJsonObject("enchantments").entrySet()
                    .forEach(e -> this.enchantments.put(e.getKey(), e.getValue().getAsInt()));
        }
        if (json.has("unbreakable"))
            this.unbreakable = json.get("unbreakable").getAsBoolean();
        if (json.has("custom_model_data"))
            this.customModelData = json.get("custom_model_data").getAsInt();
        if (json.has("use_seconds")) this.useSeconds = json.get("use_seconds").getAsFloat();
        if (json.has("consume_seconds")) this.useSeconds = json.get("consume_seconds").getAsFloat();
        if (json.has("use_animation")) this.useAnimation = json.get("use_animation").getAsString();
        if (json.has("consume_animation")) this.useAnimation = json.get("consume_animation").getAsString();
        if (json.has("use_sound")) this.useSound = json.get("use_sound").getAsString();
        if (json.has("consume_sound")) this.useSound = json.get("consume_sound").getAsString();
        if (json.has("use_particles")) this.useParticles = json.get("use_particles").getAsBoolean();
        if (json.has("consume_particles")) this.useParticles = json.get("consume_particles").getAsBoolean();
        if (json.has("use_cooldown")) this.useCooldownSeconds = json.get("use_cooldown").getAsFloat();
        if (json.has("use_cooldown_seconds")) this.useCooldownSeconds = json.get("use_cooldown_seconds").getAsFloat();
        if (json.has("cooldown_group")) this.cooldownGroup = json.get("cooldown_group").getAsString();
        if (json.has("use_remainder")) this.useRemainder = json.get("use_remainder").getAsString();
        if (json.has("remainder")) this.useRemainder = json.get("remainder").getAsString();
        if (json.has("repair_items") && json.get("repair_items").isJsonArray()) {
            this.repairItems.clear();
            json.getAsJsonArray("repair_items").forEach(e -> this.repairItems.add(e.getAsString()));
        }
        if (json.has("repair_item")) {
            this.repairItems.clear();
            this.repairItems.add(json.get("repair_item").getAsString());
        }

        if (json.has("item_states") && json.get("item_states").isJsonObject()) {
            this.itemStates.clear();
            int idx = 1;
            for (var ent : json.getAsJsonObject("item_states").entrySet()) {
                if (!ent.getValue().isJsonObject()) continue;
                ItemStateDef def = readItemState(ent.getKey(), ent.getValue().getAsJsonObject(), idx++);
                this.itemStates.put(def.name, def);
            }
        }
        readItemAnimations(json);

        if (json.has("events") && json.get("events").isJsonObject())
            this.events = json.getAsJsonObject("events");
        // small authoring shortcut: top-level event arrays/objects work too
        for (String ev : new String[]{"on_use", "on_hit", "on_consume", "on_equip", "on_unequip", "on_tick", "while_held_tick"}) {
            if (json.has(ev)) {
                if (this.events == null) this.events = new com.google.gson.JsonObject();
                this.events.add(ev, json.get(ev));
            }
        }

        // A6 JSON-only mechanics
        if (json.has("on_use_effects") && json.get("on_use_effects").isJsonArray()) {
            this.onUseEffects.clear();
            json.getAsJsonArray("on_use_effects").forEach(e -> {
                if (!e.isJsonObject()) return;
                EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                if (entry != null) this.onUseEffects.add(entry);
            });
        }
        if (json.has("on_hit_effects") && json.get("on_hit_effects").isJsonArray()) {
            this.onHitEffects.clear();
            json.getAsJsonArray("on_hit_effects").forEach(e -> {
                if (!e.isJsonObject()) return;
                EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                if (entry != null) this.onHitEffects.add(entry);
            });
        }
        if (json.has("on_hit_command"))
            this.onHitCommand = json.get("on_hit_command").getAsString();
        if (json.has("cooldown_ticks"))
            this.cooldownTicks = json.get("cooldown_ticks").getAsInt();
        if (json.has("consume_on_use"))
            this.consumeOnUse = json.get("consume_on_use").getAsBoolean();
        if (json.has("right_click_drop_xp"))
            this.rightClickDropXp = json.get("right_click_drop_xp").getAsInt();
        if (json.has("attack_aoe_radius"))
            this.attackAoeRadius = json.get("attack_aoe_radius").getAsDouble();
    }

    private void readFoodObject(JsonObject food) {
        if (food.has("nutrition")) this.foodHunger = food.get("nutrition").getAsInt();
        if (food.has("hunger")) this.foodHunger = food.get("hunger").getAsInt();
        if (food.has("food_hunger")) this.foodHunger = food.get("food_hunger").getAsInt();
        if (food.has("saturation")) this.foodSaturation = food.get("saturation").getAsFloat();
        if (food.has("saturation_modifier")) this.foodSaturation = food.get("saturation_modifier").getAsFloat();
        if (food.has("food_saturation")) this.foodSaturation = food.get("food_saturation").getAsFloat();
        if (food.has("always_edible")) this.alwaysEdible = food.get("always_edible").getAsBoolean();
        if (food.has("can_always_eat")) this.alwaysEdible = food.get("can_always_eat").getAsBoolean();
        if (food.has("effects") && food.get("effects").isJsonArray()) {
            this.consumeEffects.clear();
            food.getAsJsonArray("effects").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    this.consumeEffects.add(new EffectEntry(e.getAsString(), 200, 0, 1.0f));
                } else if (e.isJsonObject()) {
                    EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                    if (entry != null) this.consumeEffects.add(entry);
                }
            });
        }
    }

    private void readConsumableObject(JsonObject consumable) {
        if (consumable.has("seconds")) this.useSeconds = consumable.get("seconds").getAsFloat();
        if (consumable.has("use_seconds")) this.useSeconds = consumable.get("use_seconds").getAsFloat();
        if (consumable.has("consume_seconds")) this.useSeconds = consumable.get("consume_seconds").getAsFloat();
        if (consumable.has("animation")) this.useAnimation = consumable.get("animation").getAsString();
        if (consumable.has("use_animation")) this.useAnimation = consumable.get("use_animation").getAsString();
        if (consumable.has("sound")) this.useSound = consumable.get("sound").getAsString();
        if (consumable.has("use_sound")) this.useSound = consumable.get("use_sound").getAsString();
        if (consumable.has("particles")) this.useParticles = consumable.get("particles").getAsBoolean();
        if (consumable.has("use_particles")) this.useParticles = consumable.get("use_particles").getAsBoolean();
        if (consumable.has("cooldown")) this.useCooldownSeconds = consumable.get("cooldown").getAsFloat();
        if (consumable.has("cooldown_seconds")) this.useCooldownSeconds = consumable.get("cooldown_seconds").getAsFloat();
        if (consumable.has("cooldown_group")) this.cooldownGroup = consumable.get("cooldown_group").getAsString();
        if (consumable.has("remainder")) this.useRemainder = consumable.get("remainder").getAsString();
        if (consumable.has("use_remainder")) this.useRemainder = consumable.get("use_remainder").getAsString();
        if (consumable.has("effects") && consumable.get("effects").isJsonArray()) {
            this.consumeEffects.clear();
            consumable.getAsJsonArray("effects").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    this.consumeEffects.add(new EffectEntry(e.getAsString(), 200, 0, 1.0f));
                } else if (e.isJsonObject()) {
                    EffectEntry entry = readEffectEntry(e.getAsJsonObject());
                    if (entry != null) this.consumeEffects.add(entry);
                }
            });
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
        if (o.has("slot")) a.slot = o.get("slot").getAsString();
        if (o.has("hidden")) a.hidden = o.get("hidden").getAsBoolean();
        return a;
    }

    private EffectEntry readEffectEntry(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString()
                : o.has("effect") ? o.get("effect").getAsString()
                : o.has("type") ? o.get("type").getAsString()
                : null;
        if (id == null || id.isBlank()) return null;
        int ticks = o.has("ticks") ? o.get("ticks").getAsInt()
                : o.has("duration") ? o.get("duration").getAsInt() : 100;
        int amp = o.has("amp") ? o.get("amp").getAsInt()
                : o.has("amplifier") ? o.get("amplifier").getAsInt() : 0;
        float chance = o.has("chance") ? o.get("chance").getAsFloat()
                : o.has("probability") ? o.get("probability").getAsFloat() : 1.0f;
        return new EffectEntry(id, ticks, amp, chance);
    }

    private void readToolTier(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonPrimitive()) {
            this.toolTier = el.getAsString();
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        ToolTierDef def = new ToolTierDef();
        def.name = o.has("name") ? o.get("name").getAsString()
                : o.has("id") ? o.get("id").getAsString()
                : this.id != null ? this.id.substring(this.id.indexOf(':') + 1) : "custom";
        if (o.has("durability")) def.durability = o.get("durability").getAsInt();
        if (o.has("uses")) def.durability = o.get("uses").getAsInt();
        if (o.has("speed")) def.speed = o.get("speed").getAsFloat();
        if (o.has("mining_speed")) def.speed = o.get("mining_speed").getAsFloat();
        if (o.has("attack_damage_bonus")) def.attackDamageBonus = o.get("attack_damage_bonus").getAsFloat();
        if (o.has("attack_bonus")) def.attackDamageBonus = o.get("attack_bonus").getAsFloat();
        if (o.has("enchantability")) def.enchantability = o.get("enchantability").getAsInt();
        if (o.has("enchantment_value")) def.enchantability = o.get("enchantment_value").getAsInt();
        if (o.has("mining_level")) def.miningLevel = o.get("mining_level").getAsInt();
        if (o.has("harvest_level")) def.miningLevel = o.get("harvest_level").getAsInt();
        if (o.has("incorrect_for_tag")) def.incorrectForTag = o.get("incorrect_for_tag").getAsString();
        if (o.has("repair_tag")) def.repairTag = o.get("repair_tag").getAsString();
        if (o.has("repair_item")) def.repairItems.add(o.get("repair_item").getAsString());
        if (o.has("repair_items") && o.get("repair_items").isJsonArray())
            o.getAsJsonArray("repair_items").forEach(e -> def.repairItems.add(e.getAsString()));
        if (o.has("incorrect_for") && o.get("incorrect_for").isJsonArray())
            o.getAsJsonArray("incorrect_for").forEach(e -> def.incorrectFor.add(e.getAsString()));
        this.customToolTier = def;
        this.toolTier = def.name;
    }
}
