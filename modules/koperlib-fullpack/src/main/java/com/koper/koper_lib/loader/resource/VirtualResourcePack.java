package com.koper.koper_lib.loader.resource;

import com.koper.koper_lib.KoperLib;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class VirtualResourcePack implements PackResources {
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private final Map<Identifier, String> assets = new HashMap<>();
    private final Set<String> namespaces = new HashSet<>();
    // lang code -> key -> text. en_us is the fallback minecraft itself falls back to
    private final Map<String, Map<String, String>> translations = new HashMap<>();

    // SERVER_DATA assets (e.g. loot tables, recipes served as data pack)
    private final Map<Identifier, String> serverAssets = new HashMap<>();
    private final Set<String> serverNamespaces = new HashSet<>();

    public VirtualResourcePack() {
        namespaces.add(KoperLib.MOD_ID);
        addAsset(Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "lang/en_us.json"), "{}");
    }

    // nukes all generated assets; keeps the default lang stub
    public void clearAssets() {
        assets.clear();
        serverAssets.clear();
        translations.clear();
        generatedEquipmentModels.clear();
        soundsJsonByNamespace.clear();
        // Keep default namespaces
        namespaces.clear();
        serverNamespaces.clear();
        namespaces.add(KoperLib.MOD_ID);
        addAsset(Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "lang/en_us.json"), "{}");
    }

    public void addAsset(Identifier id, String jsonContent) {
        assets.put(id, jsonContent);
        namespaces.add(id.getNamespace());
    }

    public void addServerAsset(Identifier id, String jsonContent) {
        serverAssets.put(id, jsonContent);
        serverNamespaces.add(id.getNamespace());
    }

    public void addServerTagValue(Identifier tagId, String value) {
        com.google.gson.JsonObject tag = new com.google.gson.JsonObject();
        tag.addProperty("replace", false);
        com.google.gson.JsonArray values = new com.google.gson.JsonArray();
        Identifier fileId = Identifier.fromNamespaceAndPath(tagId.getNamespace(), "tags/" + tagId.getPath() + ".json");
        if (serverAssets.containsKey(fileId)) {
            try {
                tag = com.google.gson.JsonParser.parseString(serverAssets.get(fileId)).getAsJsonObject();
                if (tag.has("values") && tag.get("values").isJsonArray()) {
                    values = tag.getAsJsonArray("values");
                }
            } catch (Exception ignored) {
                tag = new com.google.gson.JsonObject();
                tag.addProperty("replace", false);
            }
        }
        boolean exists = false;
        for (com.google.gson.JsonElement el : values) {
            if (el.isJsonPrimitive() && value.equals(el.getAsString())) {
                exists = true;
                break;
            }
        }
        if (!exists) values.add(value);
        tag.add("values", values);
        addServerAsset(fileId, GSON.toJson(tag));
    }

    public void addTranslation(String key, String value) {
        addTranslation("en_us", key, value);
    }

    // one name per language. packs write a "lang" block, everything else lands in en_us
    public void addTranslation(String lang, String key, String value) {
        if (key == null || key.isBlank() || value == null) return;
        String code = lang == null || lang.isBlank() ? "en_us" : lang.toLowerCase(java.util.Locale.ROOT);
        translations.computeIfAbsent(code, c -> new HashMap<>()).put(key, value);
        updateLangFile(code);
    }

    private void updateLangFile(String lang) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        translations.getOrDefault(lang, Map.of()).forEach(json::addProperty);
        addAsset(Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "lang/" + lang + ".json"), json.toString());
    }

    public void addPresetModel(Identifier id, String texture, String type) {
		addPresetModel(id, texture, type, com.koper.koper_lib.api.FullpackColors.NONE, false);
	}

    public void addStatefulItemModel(Identifier id, String baseTexture, String baseType,
            java.util.Map<String, com.koper.koper_lib.data.KoperItemData.ItemStateDef> states,
            String defaultState, int tint, boolean dyeable) {
        if (states == null || states.isEmpty()) {
            addPresetModel(id, baseTexture, baseType, tint, dyeable);
            return;
        }

        String ns = id.getNamespace();
        String path = id.getPath();
        String parent = ("sword".equals(baseType) || "handheld".equals(baseType))
            ? "minecraft:item/handheld" : "minecraft:item/generated";

        addFlatItemModel(ns, path, parent, resolveItemTex(ns, baseTexture));

        StringBuilder entries = new StringBuilder();
        for (var ent : states.entrySet()) {
            var state = ent.getValue();
            String stateName = state.name != null ? state.name : ent.getKey();
            String tex = state.texture != null && !state.texture.isBlank() ? state.texture : baseTexture;
            String stateModel = path + "_" + stateName;
            String stateParent = state.modelType != null && state.modelType.equalsIgnoreCase("handheld")
                ? "minecraft:item/handheld" : parent;
            addFlatItemModel(ns, stateModel, stateParent, resolveItemTex(ns, tex));
            if (state.frametime != null || !state.frames.isEmpty()) addTextureAnimation(ns, tex, state);
            if (entries.length() > 0) entries.append(',');
            entries.append("{\"threshold\":").append(state.customModelData)
                .append(",\"model\":{\"type\":\"minecraft:model\",\"model\":\"")
                .append(ns).append(":item/").append(stateModel).append("\"}}");
        }

        String fallbackModel = ns + ":item/" + path;
        var fallback = states.get(defaultState);
        if (fallback != null && fallback.customModelData > 0) {
            fallbackModel = ns + ":item/" + path + "_" + fallback.name;
        }

        String itemDef = """
            {
              "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "entries": [%s],
                "fallback": { "type": "minecraft:model", "model": "%s" }
              }
            }
            """.formatted(entries, fallbackModel);
        addAsset(Identifier.fromNamespaceAndPath(ns, "items/" + path + ".json"), itemDef);
    }

    private void addFlatItemModel(String ns, String path, String parent, String texture) {
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + ".json"), """
            {
              "parent": "%s",
              "textures": { "layer0": "%s" }
            }
            """.formatted(parent, texture));
    }

    private void addTextureAnimation(String ns, String texture, com.koper.koper_lib.data.KoperItemData.ItemStateDef state) {
        String tex = resolveItemTex(ns, texture);
        int colon = tex.indexOf(':');
        String texNs = colon > 0 ? tex.substring(0, colon) : ns;
        String texPath = colon > 0 ? tex.substring(colon + 1) : tex;
        com.google.gson.JsonObject anim = new com.google.gson.JsonObject();
        if (state.frametime != null) anim.addProperty("frametime", state.frametime);
        if (state.interpolate != null) anim.addProperty("interpolate", state.interpolate);
        if (!state.frames.isEmpty()) {
            com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
            state.frames.forEach(frames::add);
            anim.add("frames", frames);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("animation", anim);
        addAsset(Identifier.fromNamespaceAndPath(texNs, "textures/" + texPath + ".png.mcmeta"), root.toString());
    }

    // two greyscale layers (shell + spots) tinted by the colours the pack gave us. 26.2 dropped
    // the vanilla template so we ship our own, which means a pack gets a coloured egg for free
    // instead of having to draw one
    public void addSpawnEggModel(Identifier id, int primary, int secondary) {
        Identifier modelId = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/item/" + id.getPath() + ".json");
        addAsset(modelId, """
            {
              "parent": "minecraft:item/generated",
              "textures": {
                "layer0": "koper_lib:item/spawn_egg_base",
                "layer1": "koper_lib:item/spawn_egg_spots"
              }
            }
            """);

        addAsset(Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                + id.getNamespace() + ":item/" + id.getPath() + "\",\"tints\":["
                + "{\"type\":\"minecraft:constant\",\"value\":" + (primary & 0xFFFFFF) + "},"
                + "{\"type\":\"minecraft:constant\",\"value\":" + (secondary & 0xFFFFFF) + "}]}}");
    }

	public void addPresetModel(Identifier id, String texture, String type, int tint, boolean dyeable) {
        // trident = throwable spear, needs display_context select + swap_animation_scale
        if (type.equals("trident")) {
            addSpearModels(id, texture);
            return;
        }

        Identifier modelId = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/item/" + id.getPath() + ".json");
        String parent = type.equals("block") ? id.getNamespace() + ":block/" + id.getPath() :
                      (type.equals("sword") || type.equals("handheld") ? "minecraft:item/handheld" : "minecraft:item/generated");

        // Strip .png extension if present
        String texNoPng = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;

        String finalTexture;
        if (texNoPng.contains(":")) {
            // Has namespace — check if it already has a path separator after the colon
            String afterColon = texNoPng.substring(texNoPng.indexOf(':') + 1);
            if (!afterColon.contains("/")) {
                // e.g. "koper_master:fire_sword" → "koper_master:item/fire_sword"
                String ns = texNoPng.substring(0, texNoPng.indexOf(':'));
                if (type.equals("block")) {
                    finalTexture = ns + ":block/" + afterColon;
                } else {
                    finalTexture = ns + ":item/" + afterColon;
                }
            } else {
                finalTexture = texNoPng;
            }
        } else {
            if (type.equals("block")) finalTexture = id.getNamespace() + ":block/" + texNoPng;
            else finalTexture = id.getNamespace() + ":item/" + texNoPng;
        }

        String json;
        if (type.equals("block")) {
            json = """
                {
                  "parent": "%s"
                }
                """.formatted(parent);
        } else {
            json = """
                {
                  "parent": "%s",
                  "textures": {
                    "layer0": "%s"
                  }
                }
                """.formatted(parent, finalTexture);
        }
        addAsset(modelId, json);

        // MC 1.21.4+ Item Model Definition — required for item to display a model at all
        String modelRef = type.equals("block")
            ? id.getNamespace() + ":block/" + id.getPath()
            : id.getNamespace() + ":item/" + id.getPath();

        String itemDefJson;
        if (type.equals("bow")) {
            // 3 pulling state models + base
            String ns = id.getNamespace(), path = id.getPath();
            String pullModelJson = """
                {
                  "parent": "minecraft:item/handheld",
                  "textures": { "layer0": "%s" }
                }
                """.formatted(finalTexture);
            for (int i = 0; i < 3; i++) {
                addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_pulling_" + i + ".json"), pullModelJson);
            }
            // vanilla 26.2 bow structure: condition(using_item) → range_dispatch(use_duration, scale:0.05)
            // "normalize" was removed in 26.2, use scale instead
            itemDefJson = """
                {
                  "model": {
                    "type": "minecraft:condition",
                    "property": "minecraft:using_item",
                    "on_true": {
                      "type": "minecraft:range_dispatch",
                      "property": "minecraft:use_duration",
                      "scale": 0.05,
                      "entries": [
                        { "threshold": 0.65, "model": { "type": "minecraft:model", "model": "%s:item/%s_pulling_1" } },
                        { "threshold": 0.9,  "model": { "type": "minecraft:model", "model": "%s:item/%s_pulling_2" } }
                      ],
                      "fallback": { "type": "minecraft:model", "model": "%s:item/%s_pulling_0" }
                    },
                    "on_false": { "type": "minecraft:model", "model": "%s:item/%s" }
                  }
                }
                """.formatted(ns, path, ns, path, ns, path, ns, path);
        } else if (type.equals("crossbow")) {
            String ns = id.getNamespace(), path = id.getPath();
            String handheldModel = """
                {
                  "parent": "minecraft:item/handheld",
                  "textures": { "layer0": "%s" }
                }
                """.formatted(finalTexture);
            addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_arrow.json"), handheldModel);
            addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_firework.json"), handheldModel);
            addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_pulling_0.json"), handheldModel);
            addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_pulling_1.json"), handheldModel);
            addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_pulling_2.json"), handheldModel);
            // vanilla structure: using_item wins over charge_type (matches assets/minecraft/items/crossbow.json exactly)
            itemDefJson = """
                {
                  "model": {
                    "type": "minecraft:condition",
                    "property": "minecraft:using_item",
                    "on_true": {
                      "type": "minecraft:range_dispatch",
                      "property": "minecraft:crossbow/pull",
                      "entries": [
                        { "threshold": 0.58, "model": { "type": "minecraft:model", "model": "%s:item/%s_pulling_1" } },
                        { "threshold": 1.0,  "model": { "type": "minecraft:model", "model": "%s:item/%s_pulling_2" } }
                      ],
                      "fallback": { "type": "minecraft:model", "model": "%s:item/%s_pulling_0" }
                    },
                    "on_false": {
                      "type": "minecraft:select",
                      "property": "minecraft:charge_type",
                      "cases": [
                        { "when": "arrow",  "model": { "type": "minecraft:model", "model": "%s:item/%s_arrow" } },
                        { "when": "rocket", "model": { "type": "minecraft:model", "model": "%s:item/%s_firework" } }
                      ],
                      "fallback": { "type": "minecraft:model", "model": "%s:item/%s" }
                    }
                  }
                }
                """.formatted(ns, path, ns, path, ns, path, ns, path, ns, path, ns, path);
        } else if (type.equals("shield")) {
            // vanilla special renderer handles 3D shield geometry, first-person pose, blocking animation
            // handheld was wrong — caused the snap-back jitter in first person
            itemDefJson = """
                {
                  "model": {
                    "type": "minecraft:condition",
                    "property": "minecraft:using_item",
                    "on_true":  { "type": "minecraft:special", "base": "minecraft:item/shield_blocking", "model": { "type": "minecraft:shield" } },
                    "on_false": { "type": "minecraft:special", "base": "minecraft:item/shield",          "model": { "type": "minecraft:shield" } },
                    "transformation": {
                      "left_rotation":  [0.0, 0.0, 0.0, 1.0],
                      "right_rotation": [0.0, 0.0, 0.0, 1.0],
                      "scale":          [1.0, -1.0, -1.0],
                      "translation":    [0.0, 0.0, 0.0]
                    }
                  }
                }
                """;
        } else {
			String tintJson = "";
			if (dyeable) {
				tintJson = ",\"tints\":[{\"type\":\"minecraft:dye\",\"default\":" + tint + "}]";
			} else if (tint != com.koper.koper_lib.api.FullpackColors.NONE) {
				tintJson = ",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":" + tint + "}]";
			}
			itemDefJson = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelRef + "\"" + tintJson + "}}";
        }
        addAsset(Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json"), itemDefJson);
    }

    // sounds.json per-namespace accumulator: namespace → { soundKey → soundEntry }
    private final Map<String, com.google.gson.JsonObject> soundsJsonByNamespace = new HashMap<>();

    /**
     * Merges a single sound entry into the sounds.json for its namespace.
     * Must be called after content load; re-called on hot-reload.
     */
    public void mergeSoundsJson(String namespace, String soundKey, com.google.gson.JsonObject soundEntry) {
        soundsJsonByNamespace.computeIfAbsent(namespace, ns -> new com.google.gson.JsonObject())
                             .add(soundKey, soundEntry);
        rebuildSoundsJson(namespace);
    }

    private void rebuildSoundsJson(String namespace) {
        com.google.gson.JsonObject root = soundsJsonByNamespace.getOrDefault(namespace, new com.google.gson.JsonObject());
        addAsset(Identifier.fromNamespaceAndPath(namespace, "sounds.json"), root.toString());
    }

    private final Set<String> generatedEquipmentModels = new HashSet<>();

    /**
     * Generates an equipment model JSON for armor rendering on the player.
     * Equipment model at: assets/{ns}/equipment/{material}.json
     * Expects textures at: textures/entity/equipment/humanoid/{material}.png (fullpack provides these)
     */
    public void addEquipmentModel(String namespace, String material) {
        addEquipmentModel(namespace, material, null, null);
    }

    /**
     * @param layer1 explicit texture path for humanoid layer (null = derive from namespace:material)
     * @param layer2 explicit texture path for humanoid_leggings layer (null = same as layer1)
     */
    public void addEquipmentModel(String namespace, String material, String layer1, String layer2) {
        String key = namespace + ":" + material;
        if (generatedEquipmentModels.contains(key)) return;
        generatedEquipmentModels.add(key);

        String tex1 = (layer1 != null && !layer1.isEmpty()) ? normalizeEquipTexture(layer1, namespace, material)
                                                             : namespace + ":" + material;
        String tex2 = (layer2 != null && !layer2.isEmpty()) ? normalizeEquipTexture(layer2, namespace, material)
                                                             : tex1;
        String equipJson = """
            {
              "layers": {
                "humanoid": [
                  { "texture": "%s" }
                ],
                "humanoid_leggings": [
                  { "texture": "%s" }
                ]
              }
            }
            """.formatted(tex1, tex2);

        Identifier equipId = Identifier.fromNamespaceAndPath(namespace, "equipment/" + material + ".json");
        addAsset(equipId, equipJson);
    }

    private String normalizeEquipTexture(String path, String fallbackNs, String fallbackName) {
        // Strip .png, ensure namespace format
        String tex = path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
        if (tex.contains(":")) return tex;
        return fallbackNs + ":" + tex;
    }

    public void addSpearModels(Identifier id, String guiTexture) {
        addSpearModels(id, guiTexture, null);
    }

    // narrow texture_in_hand → spear_in_hand parent (calibrated for it); square texture → custom display with spear rotations but handheld scale
    public void addSpearModels(Identifier id, String guiTexture, String inHandTexture) {
        String ns   = id.getNamespace();
        String path = id.getPath();

        String guiTex   = resolveItemTex(ns, guiTexture);
        String handTex  = inHandTexture != null ? resolveItemTex(ns, inHandTexture) : guiTex;

        String guiModel = """
            {
              "parent": "minecraft:item/generated",
              "textures": { "layer0": "%s" }
            }
            """.formatted(guiTex);
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + ".json"), guiModel);

        String inHandModel;
        if (inHandTexture != null) {
            // narrow sprite provided — spear_in_hand parent is calibrated for it, looks perfect
            inHandModel = """
                {
                  "parent": "minecraft:item/spear_in_hand",
                  "textures": { "layer0": "%s" }
                }
                """.formatted(handTex);
        } else {
            // square texture — spear_in_hand scale 1.7 makes it huge, use spear ROTATIONS with handheld SCALE
            inHandModel = """
                {
                  "parent": "minecraft:item/generated",
                  "display": {
                    "thirdperson_righthand": { "rotation": [5, 270, -40], "translation": [0, 2, 2], "scale": [0.85, 0.85, 0.42] },
                    "thirdperson_lefthand":  { "rotation": [5, -270, 40], "translation": [0, 2, 2], "scale": [0.85, 0.85, 0.42] },
                    "firstperson_righthand": { "rotation": [-20, 90, -35], "translation": [3.13, 2.0, 0.13], "scale": [0.68, 0.68, 0.34] },
                    "firstperson_lefthand":  { "rotation": [-20, -90, 35], "translation": [3.13, 2.0, 0.13], "scale": [0.68, 0.68, 0.34] }
                  },
                  "textures": { "layer0": "%s" }
                }
                """.formatted(handTex);
        }
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_in_hand.json"), inHandModel);

        String itemDef = """
            {
              "model": {
                "type": "minecraft:select",
                "property": "minecraft:display_context",
                "cases": [
                  {
                    "when": ["gui", "ground", "fixed", "on_shelf"],
                    "model": { "type": "minecraft:model", "model": "%s:item/%s" }
                  }
                ],
                "fallback": { "type": "minecraft:model", "model": "%s:item/%s_in_hand" }
              },
              "swap_animation_scale": 1.95
            }
            """.formatted(ns, path, ns, path);
        addAsset(Identifier.fromNamespaceAndPath(ns, "items/" + path + ".json"), itemDef);
    }

    private String resolveItemTex(String ns, String texture) {
        String t = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;
        if (t.contains(":")) {
            String after = t.substring(t.indexOf(':') + 1);
            return after.contains("/") ? t : t.substring(0, t.indexOf(':') + 1) + "item/" + after;
        }
        return ns + ":item/" + t;
    }

    public void addBlockStateAndModel(Identifier id, String texture) {
        addBlockStateAndModel(id, texture, java.util.Collections.emptyMap());
    }

    public void addBlockStateAndModel(Identifier id, String texture, java.util.Map<String, String> textureFaces) {
        // Block State
        Identifier stateId = Identifier.fromNamespaceAndPath(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        String stateJson = """
            {
              "variants": {
                "": { "model": "%s:block/%s" }
              }
            }
            """.formatted(id.getNamespace(), id.getPath());
        addAsset(stateId, stateJson);

        // Block Model — per-face or cube_all
        Identifier modelId = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/block/" + id.getPath() + ".json");
        String modelJson = textureFaces.isEmpty()
            ? buildCubeAllModel(texture)
            : buildCubeFacesModel(texture, textureFaces);
        addAsset(modelId, modelJson);

        // Item Model for this block
        addPresetModel(id, texture, "block");
    }

    public void addBlockStateAndModel(Identifier id, String texture, java.util.Map<String, String> textureFaces,
            java.util.List<String> stateProperties) {
        if (stateProperties == null || stateProperties.isEmpty()) {
            addBlockStateAndModel(id, texture, textureFaces);
            return;
        }

        Identifier stateId = Identifier.fromNamespaceAndPath(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        addAsset(stateId, buildStatefulBlockstate(id, stateProperties));

        Identifier modelId = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/block/" + id.getPath() + ".json");
        String modelJson = textureFaces.isEmpty()
            ? buildCubeAllModel(texture)
            : buildCubeFacesModel(texture, textureFaces);
        addAsset(modelId, modelJson);

        addPresetModel(id, texture, "block");
    }

    public void addConnectedBlockStateAndModel(Identifier id, com.koper.koper_lib.data.KoperBlockData data,
            String texture, java.util.List<String> stateProperties) {
        Identifier stateId = Identifier.fromNamespaceAndPath(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        java.util.LinkedHashMap<String, java.util.List<String>> props = new java.util.LinkedHashMap<>();
        for (String raw : stateProperties) {
            String key = blockstatePropertyName(raw);
            if (key == null || props.containsKey(key)) continue;
            props.put(key, blockstateValues(raw));
        }
        if (props.isEmpty()) {
            addBlockStateAndModel(id, texture);
            return;
        }

        java.util.List<java.util.Map<String, String>> combos = new java.util.ArrayList<>();
        combos.add(new java.util.LinkedHashMap<>());
        for (var ent : props.entrySet()) {
            java.util.List<java.util.Map<String, String>> next = new java.util.ArrayList<>();
            for (var combo : combos) {
                for (String value : ent.getValue()) {
                    java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>(combo);
                    copy.put(ent.getKey(), value);
                    next.add(copy);
                }
            }
            combos = next;
        }

        StringBuilder variants = new StringBuilder("{\"variants\":{");
        for (int i = 0; i < combos.size(); i++) {
            if (i > 0) variants.append(',');
            var combo = combos.get(i);
            String modelPath = id.getPath() + "_ctm_" + i;
            variants.append('"').append(blockstateKey(combo)).append("\":{\"model\":\"")
                .append(id.getNamespace()).append(":block/").append(modelPath).append('"');
            addBlockstateRotation(variants, combo);
            variants.append('}');
            addAsset(Identifier.fromNamespaceAndPath(id.getNamespace(), "models/block/" + modelPath + ".json"),
                buildConnectedCubeModel(data, texture, combo));
        }
        variants.append("}}");
        addAsset(stateId, variants.toString());
        addPresetModel(id, texture, "block");
    }

    private String buildConnectedCubeModel(com.koper.koper_lib.data.KoperBlockData data, String baseTexture,
            java.util.Map<String, String> combo) {
        java.util.Map<String, String> tex = data.connectedTextures != null ? data.connectedTextures : java.util.Map.of();
        String up = connectedTexture(tex, baseTexture, "up", combo, "north", "east", "south", "west");
        String down = connectedTexture(tex, baseTexture, "down", combo, "north", "east", "south", "west");
        String north = connectedTexture(tex, baseTexture, "north", combo, "west", "up", "east", "down");
        String south = connectedTexture(tex, baseTexture, "south", combo, "east", "up", "west", "down");
        String east = connectedTexture(tex, baseTexture, "east", combo, "north", "up", "south", "down");
        String west = connectedTexture(tex, baseTexture, "west", combo, "south", "up", "north", "down");
        return """
            {
              "parent": "minecraft:block/cube",
              "textures": {
                "up": "%s",
                "down": "%s",
                "north": "%s",
                "south": "%s",
                "east": "%s",
                "west": "%s"
              }
            }
            """.formatted(up, down, north, south, east, west);
    }

    private String connectedTexture(java.util.Map<String, String> textures, String baseTexture, String face,
            java.util.Map<String, String> combo, String a, String b, String c, String d) {
        String role;
        if ("true".equals(combo.get(face))) {
            role = "connected";
        } else {
            int open = boolFalse(combo, a) + boolFalse(combo, b) + boolFalse(combo, c) + boolFalse(combo, d);
            role = open >= 2 ? "corner" : open == 1 ? "edge" : "middle";
        }
        String raw = firstTexture(textures,
            face + ":" + role,
            face + "_" + role,
            face + ":center",
            role,
            "center",
            "middle",
            "all");
        return normalizeBlockTexture(raw != null ? raw : baseTexture);
    }

    private int boolFalse(java.util.Map<String, String> combo, String key) {
        return "false".equals(combo.get(key)) ? 1 : 0;
    }

    private String firstTexture(java.util.Map<String, String> textures, String... keys) {
        for (String key : keys) {
            String value = textures.get(key.toLowerCase());
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String buildStatefulBlockstate(Identifier id, java.util.List<String> stateProperties) {
        String model = id.getNamespace() + ":block/" + id.getPath();
        java.util.LinkedHashMap<String, java.util.List<String>> props = new java.util.LinkedHashMap<>();
        for (String raw : stateProperties) {
            String key = blockstatePropertyName(raw);
            if (key == null || props.containsKey(key)) continue;
            props.put(key, blockstateValues(raw));
        }
        if (props.isEmpty()) {
            return "{\"variants\":{\"\":{\"model\":\"" + model + "\"}}}";
        }

        java.util.List<java.util.Map<String, String>> combos = new java.util.ArrayList<>();
        combos.add(new java.util.LinkedHashMap<>());
        for (var ent : props.entrySet()) {
            java.util.List<java.util.Map<String, String>> next = new java.util.ArrayList<>();
            for (var combo : combos) {
                for (String value : ent.getValue()) {
                    java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>(combo);
                    copy.put(ent.getKey(), value);
                    next.add(copy);
                }
            }
            combos = next;
        }

        StringBuilder variants = new StringBuilder("{\"variants\":{");
        for (int i = 0; i < combos.size(); i++) {
            if (i > 0) variants.append(',');
            var combo = combos.get(i);
            variants.append('"').append(blockstateKey(combo)).append("\":{\"model\":\"").append(model).append('"');
            addBlockstateRotation(variants, combo);
            variants.append('}');
        }
        variants.append("}}");
        return variants.toString();
    }

    private String blockstatePropertyName(String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase()) {
            case "facing", "horizontal_facing", "horizontal", "facing_horizontal" -> "facing";
            case "lit" -> "lit";
            case "powered" -> "powered";
            case "open" -> "open";
            case "north" -> "north";
            case "east" -> "east";
            case "south" -> "south";
            case "west" -> "west";
            case "up" -> "up";
            case "down" -> "down";
            case "age", "age_1", "age_2", "age_3", "age_4", "age_5", "age_7", "age_15", "age_25" -> "age";
            default -> null;
        };
    }

    private java.util.List<String> blockstateValues(String raw) {
        String s = raw == null ? "" : raw.toLowerCase();
        return switch (s) {
            case "facing" -> java.util.List.of("north", "east", "south", "west", "up", "down");
            case "horizontal_facing", "horizontal", "facing_horizontal" -> java.util.List.of("north", "east", "south", "west");
            case "lit", "powered", "open", "north", "east", "south", "west", "up", "down" -> java.util.List.of("false", "true");
            case "age_1" -> intRange(1);
            case "age_2" -> intRange(2);
            case "age_3" -> intRange(3);
            case "age_4" -> intRange(4);
            case "age_5" -> intRange(5);
            case "age", "age_7" -> intRange(7);
            case "age_15" -> intRange(15);
            case "age_25" -> intRange(25);
            default -> java.util.List.of("");
        };
    }

    private java.util.List<String> intRange(int max) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int i = 0; i <= max; i++) values.add(Integer.toString(i));
        return values;
    }

    private String blockstateKey(java.util.Map<String, String> combo) {
        StringBuilder key = new StringBuilder();
        boolean first = true;
        for (var ent : combo.entrySet()) {
            if (!first) key.append(',');
            key.append(ent.getKey()).append('=').append(ent.getValue());
            first = false;
        }
        return key.toString();
    }

    private void addBlockstateRotation(StringBuilder json, java.util.Map<String, String> combo) {
        String facing = combo.get("facing");
        if (facing == null) return;
        switch (facing) {
            case "east" -> json.append(",\"y\":90");
            case "south" -> json.append(",\"y\":180");
            case "west" -> json.append(",\"y\":270");
            case "up" -> json.append(",\"x\":270");
            case "down" -> json.append(",\"x\":90");
            default -> {
            }
        }
    }

    // koperblock tier-0: vanilla draws NOTHING (empty model), geometry is added by KoperBlockRenderer
    // particle is set to the geo texture so the break effect isn't bare
    public void addInvisibleBlock(Identifier id, Identifier particleTex) {
        String ns = id.getNamespace(), path = id.getPath();
        addAsset(Identifier.fromNamespaceAndPath(ns, "blockstates/" + path + ".json"),
            "{\"variants\":{\"\":{\"model\":\"" + ns + ":block/" + path + "\"}}}");

        String particle = particleTex.getNamespace() + ":" + particleTex.getPath()
            .replaceFirst("^textures/", "").replaceFirst("\\.png$", "");
        if (!particle.substring(particle.indexOf(':') + 1).contains("/")) {
            particle = particle.substring(0, particle.indexOf(':') + 1) + "block/"
                + particle.substring(particle.indexOf(':') + 1);
        }
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/block/" + path + ".json"),
            "{\"textures\":{\"particle\":\"" + particle + "\"},\"elements\":[]}");

        // 3D item: a base model carries vanilla block display transforms, the special renderer draws the geo model
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_base.json"),
            "{\"parent\":\"minecraft:block/block\",\"textures\":{\"particle\":\"" + particle + "\"}}");
        addAsset(Identifier.fromNamespaceAndPath(ns, "items/" + path + ".json"),
            "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"" + ns + ":item/" + path + "_base\","
            + "\"model\":{\"type\":\"koperlib:kodel\"}}}");
    }

    // item whose GUI/hand icon is its 3D geo model (armor part etc.) instead of a flat sprite.
    // base carries vanilla item display transforms, the koperlib:kodel special renderer draws the model
    public void addGeoItemModel(Identifier id) {
        String ns = id.getNamespace(), path = id.getPath();
        // base = block/block: gives 3D GUI display transforms with no texture requirement (item/generated needs layer0)
        addAsset(Identifier.fromNamespaceAndPath(ns, "models/item/" + path + "_base.json"),
            "{\"parent\":\"minecraft:block/block\"}");
        addAsset(Identifier.fromNamespaceAndPath(ns, "items/" + path + ".json"),
            "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"" + ns + ":item/" + path + "_base\","
            + "\"model\":{\"type\":\"koperlib:kodel\"}}}");
    }

    public void addCrossModel(Identifier id, String texture) {
        // Blockstate
        Identifier stateId = Identifier.fromNamespaceAndPath(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        addAsset(stateId, """
            {"variants":{"":{"model":"%s:block/%s"}}}
            """.formatted(id.getNamespace(), id.getPath()));

        // Cross model (plants, flowers, etc.)
        String tex = normalizeBlockTexture(texture);
        Identifier modelId = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/block/" + id.getPath() + ".json");
        addAsset(modelId, """
            {"parent":"minecraft:block/cross","textures":{"cross":"%s"}}
            """.formatted(tex));

        addPresetModel(id, texture, "block");
    }

    private String buildCubeAllModel(String texture) {
        String tex = normalizeBlockTexture(texture);
        return """
            {
              "parent": "minecraft:block/cube_all",
              "textures": {
                "all": "%s"
              }
            }
            """.formatted(tex);
    }

    private String buildCubeFacesModel(String defaultTexture, java.util.Map<String, String> faces) {
        // Resolve each face; fall back to defaultTexture if not specified
        String all  = faces.getOrDefault("all",    defaultTexture);
        String top  = faces.getOrDefault("top",    faces.getOrDefault("all", defaultTexture));
        String bot  = faces.getOrDefault("bottom", faces.getOrDefault("all", defaultTexture));
        String side = faces.getOrDefault("side",   faces.getOrDefault("all", defaultTexture));
        String north = faces.getOrDefault("north", side);
        String south = faces.getOrDefault("south", side);
        String east  = faces.getOrDefault("east",  side);
        String west  = faces.getOrDefault("west",  side);

        boolean hasDirectional = faces.containsKey("north") || faces.containsKey("south")
            || faces.containsKey("east") || faces.containsKey("west");

        if (hasDirectional) {
            return """
                {
                  "parent": "minecraft:block/cube",
                  "textures": {
                    "up":    "%s",
                    "down":  "%s",
                    "north": "%s",
                    "south": "%s",
                    "east":  "%s",
                    "west":  "%s"
                  }
                }
                """.formatted(
                    normalizeBlockTexture(top), normalizeBlockTexture(bot),
                    normalizeBlockTexture(north), normalizeBlockTexture(south),
                    normalizeBlockTexture(east), normalizeBlockTexture(west));
        } else if (faces.containsKey("top") || faces.containsKey("bottom") || faces.containsKey("side")) {
            return """
                {
                  "parent": "minecraft:block/cube_bottom_top",
                  "textures": {
                    "top":    "%s",
                    "bottom": "%s",
                    "side":   "%s"
                  }
                }
                """.formatted(
                    normalizeBlockTexture(top), normalizeBlockTexture(bot), normalizeBlockTexture(side));
        } else {
            return buildCubeAllModel(normalizeBlockTexture(all));
        }
    }

    private String normalizeBlockTexture(String texture) {
        String tex = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;
        if (tex.contains(":")) {
            String afterColon = tex.substring(tex.indexOf(':') + 1);
            if (!afterColon.contains("/")) {
                return tex.substring(0, tex.indexOf(':') + 1) + "block/" + afterColon;
            }
            return tex;
        }
        return "minecraft:block/" + tex;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... segments) {
        if (segments.length == 1 && "pack.mcmeta".equals(segments[0])) {
            byte[] bytes = "{\"pack\":{\"pack_format\":34,\"description\":\"KoperLib Virtual Assets\"}}".getBytes(StandardCharsets.UTF_8);
            return () -> new ByteArrayInputStream(bytes);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type == PackType.CLIENT_RESOURCES && assets.containsKey(id)) {
            return () -> new ByteArrayInputStream(assets.get(id).getBytes(StandardCharsets.UTF_8));
        }
        if (type == PackType.SERVER_DATA && serverAssets.containsKey(id)) {
            return () -> new ByteArrayInputStream(serverAssets.get(id).getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput consumer) {
        if (type == PackType.CLIENT_RESOURCES) {
            assets.keySet().stream()
                .filter(id -> id.getNamespace().equals(namespace) && pathMatchesPrefix(id.getPath(), prefix))
                .forEach(id -> consumer.accept(id, getResource(type, id)));
        } else if (type == PackType.SERVER_DATA) {
            serverAssets.keySet().stream()
                .filter(id -> id.getNamespace().equals(namespace) && pathMatchesPrefix(id.getPath(), prefix))
                .forEach(id -> consumer.accept(id, getResource(type, id)));
        }
    }

    // "dimension_type/foo.json" must NOT match prefix "dimension" — check directory boundary
    private static boolean pathMatchesPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) return false;
        if (path.length() == prefix.length()) return true;
        return path.charAt(prefix.length()) == '/' || prefix.charAt(prefix.length() - 1) == '/';
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == PackType.SERVER_DATA) return serverNamespaces;
        return namespaces;
    }

    @Override
    public <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> metaReader) throws java.io.IOException {
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier == null) return null;
        try (InputStream is = supplier.get()) {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(
                new java.io.InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonObject section = root.getAsJsonObject(metaReader.name());
            if (section == null) return null;
            return metaReader.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, section).result().orElse(null);
        }
    }

    public PackLocationInfo getInfo() {
        return new PackLocationInfo("koper_lib_virtual", Component.literal("KoperLib Virtual Assets"), net.minecraft.server.packs.repository.PackSource.BUILT_IN, Optional.empty());
    }

    @Override
    public PackLocationInfo location() {
        return getInfo();
    }

    @Override
    public void close() {}
}
