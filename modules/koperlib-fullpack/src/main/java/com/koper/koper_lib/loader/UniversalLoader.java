package com.koper.koper_lib.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.factory.BlockFactory;
import com.koper.koper_lib.factory.EntityFactory;
import com.koper.koper_lib.factory.ItemFactory;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// scans fullpack dirs for JSON content and dispatches to factories
public class UniversalLoader {
    private static final Gson GSON = new Gson();

    // dir name → content type string
    private static final java.util.Map<String, String> DIR_TO_TYPE = java.util.Map.ofEntries(
        java.util.Map.entry("items", "item"),
        java.util.Map.entry("blocks", "block"),
        java.util.Map.entry("enchantments", "enchantment"),
        java.util.Map.entry("entities", "entity"),
        java.util.Map.entry("mobs", "entity"),
        java.util.Map.entry("spawn_eggs", "spawn_egg"),
        java.util.Map.entry("dimensions", "dimension"),
        java.util.Map.entry("potions", "potion"),
        java.util.Map.entry("effects", "effect"),
        java.util.Map.entry("status_effects", "effect"),
        java.util.Map.entry("attributes", "attribute"),
        java.util.Map.entry("recipes", "recipe"),
        java.util.Map.entry("loot", "loot"),
        java.util.Map.entry("advancements", "advancement"),
        java.util.Map.entry("particles", "particle"),
        java.util.Map.entry("kfx", "kfx"),
        java.util.Map.entry("fx", "kfx"),
        java.util.Map.entry("sounds", "sound"),
        java.util.Map.entry("events", "event"),
        java.util.Map.entry("creative_tabs", "creative_tab"),
        java.util.Map.entry("gui", "gui"),
        java.util.Map.entry("guis", "gui"),   // every other type takes the plural, this one should too
        java.util.Map.entry("quests", "quest"),
        java.util.Map.entry("books", "book"),
        java.util.Map.entry("dialogs", "dialog"),
        java.util.Map.entry("dialogues", "dialog")
    );

    // tracks loaded IDs for hot-reload diffing
    private static final List<String> loadedIds = new ArrayList<>();

    // set to the pack root during loadFullPack; used for script auto-binding
    private File currentPackDir;

    public void loadContent() {
        loadExternalContent();
    }

    public static List<String> getLoadedIds() {
        return loadedIds;
    }

    public void loadExternalContent() {
        com.koper.koper_lib.api.FullpackAddons.beginContentScan();
        boolean completed = false;
        try {
            KoperLib.LOGGER.info("UniversalLoader: Starting content scan...");
            loadedIds.clear();

            // 1. Root koperlib/ directory (legacy jsons/ path)
            File rootJsons = KoperLibDirectories.JSONS.toFile();
            if (rootJsons.exists()) scanContentDirectory(rootJsons);

            // 2. Each FullPack — FullPackLoader owns discovery (fullpacks/ + mods/ + datapacks/),
            // re-listing the fullpacks dir here would silently drop packs from the other roots
            for (File pack : FullPackLoader.getEnabledPackDirs()) {
                loadFullPack(pack);
            }

            // scripts/kfx is the eager, static Lua graph declaration tier. It runs after JSON fragments
            // exist and before addons atomically link their post-reload snapshots.
            com.koper.koper_lib.scripting.UniversalScriptEngine.preloadDirectory("kfx");
            // converted bedrock addons: custom component tables, and on a live reload their scripts start over
            com.koper.koper_lib.bedrock.BedrockSkrypciarz.afterContentReload();
            completed = true;
            KoperLib.LOGGER.info("UniversalLoader: Loaded {} content entries.", loadedIds.size());
        } finally {
            if (!completed) com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
            com.koper.koper_lib.api.FullpackAddons.finishReload();
        }
    }

    public void loadFullPack(File packDir) {
        String packName = packDir.getName();
        this.currentPackDir = packDir;
        boolean found = false;

        File koperDir = new File(packDir, "koperlib");
        if (koperDir.exists() && koperDir.isDirectory()) {
            KoperLib.LOGGER.info("[UniversalLoader] Scanning FullPack (koperlib/): {}", packName);
            scanContentDirectory(koperDir);
            found = true;
        }

        File dataDir = new File(packDir, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            KoperLib.LOGGER.info("[UniversalLoader] Scanning FullPack (data/): {}", packName);
            scanContentDirectory(dataDir);
            found = true;
        }

        File jsonsDir = new File(packDir, "jsons");
        if (jsonsDir.exists() && jsonsDir.isDirectory()) {
            KoperLib.LOGGER.info("[UniversalLoader] Scanning FullPack (jsons/): {}", packName);
            scanContentDirectory(jsonsDir);
            found = true;
        }

        // flat layout: items/, blocks/ etc. directly in pack root
        boolean flatFound = false;
        for (String dirName : DIR_TO_TYPE.keySet()) {
            File flatDir = new File(packDir, dirName);
            if (flatDir.exists() && flatDir.isDirectory()) {
                if (!flatFound) {
                    KoperLib.LOGGER.info("[UniversalLoader] Scanning FullPack (flat/): {}", packName);
                    flatFound = true;
                }
                if (recursiveContentType(dirName)) scanJsonTree(flatDir, DIR_TO_TYPE.get(dirName));
                else scanJsonsDirectory(flatDir);
            }
        }
        if (flatFound) found = true;

        if (!found) {
            KoperLib.LOGGER.debug("[UniversalLoader] FullPack '{}' has no recognised content directory.", packName);
        }

        // geo models: scan models/ and geo/ for *.geo.json / *.geo.hjson, auto-bound by file name
        scanGeoModels(new File(packDir, "models"));
        scanGeoModels(new File(packDir, "geo"));
        // animation clips: scan animations/ for *.animation.json / *.animation.hjson, auto-bound by file name
        scanGeoAnims(new File(packDir, "animations"));

        // auto-detect .ogg files — no JSON needed, pack creators just drop sounds/ files
        KoperMeta packMeta = FullPackLoader.getMeta(packName);
        String packNs = packMeta != null
            ? packMeta.getEffectiveNamespace(packName)
            : packName.toLowerCase();
        autoDetectSounds(packDir, packNs);

        // datapacks/ subfolder — standard MC datapack structure, pushed into virtual server data pack
        loadEmbeddedDatapacks(packDir);
    }

    // registers .geo.json/.geo.hjson files by base name (e.g. driver_seat.geo.json -> "driver_seat")
    private void scanGeoModels(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) { scanGeoModels(f); continue; }
            String n = f.getName();
            String base = null;
            if (n.endsWith(".geo.json")) base = n.substring(0, n.length() - ".geo.json".length());
            else if (n.endsWith(".geo.hjson")) base = n.substring(0, n.length() - ".geo.hjson".length());
            if (base != null) {
                com.koper.koper_lib.api.FullpackAddons.modelFile(base, f);
                if (com.koper.koper_lib.api.FullpackAddons.hasModelFiles())
                    KoperLib.LOGGER.info("[Fullpack/addon] model file: {} -> {}", base, f.getName());
            }
        }
    }

    // registers .animation.json/.animation.hjson files by base name (e.g. kapoka.animation.json -> "kapoka")
    private void scanGeoAnims(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) { scanGeoAnims(f); continue; }
            String n = f.getName();
            String base = null;
            if (n.endsWith(".animation.json")) base = n.substring(0, n.length() - ".animation.json".length());
            else if (n.endsWith(".animation.hjson")) base = n.substring(0, n.length() - ".animation.hjson".length());
            if (base != null) {
                com.koper.koper_lib.api.FullpackAddons.animationFile(base, f);
                if (com.koper.koper_lib.api.FullpackAddons.hasAnimationFiles())
                    KoperLib.LOGGER.info("[Fullpack/addon] animation file: {} -> {}", base, f.getName());
            }
        }
    }

    // scans sounds/ for .ogg files and auto-registers any without a matching .json
    private void autoDetectSounds(File packDir, String namespace) {
        File soundsDir = new File(packDir, "sounds");
        if (!soundsDir.exists() || !soundsDir.isDirectory()) return;

        autoDetectSoundsInDir(soundsDir, soundsDir, namespace);
    }

    private void autoDetectSoundsInDir(File rootSoundsDir, File dir, String namespace) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                autoDetectSoundsInDir(rootSoundsDir, f, namespace);
                continue;
            }
            if (!f.getName().endsWith(".ogg")) continue;

            // derive key: relative path from sounds/ without .ogg
            String rel = rootSoundsDir.toPath().relativize(f.toPath()).toString()
                .replace('\\', '/').replaceAll("\\.ogg$", "");

            // skip if there's a matching JSON already handled (SoundFactory took care of it)
            File matchingJson = new File(rootSoundsDir, rel + ".json");
            // only the flat-name JSON (not subdir) — if json exists SoundFactory already registered it
            File flatJson = new File(rootSoundsDir, f.getName().replace(".ogg", ".json"));
            if (matchingJson.exists() || flatJson.exists()) continue;

            // build synthetic sound json and hand it to SoundFactory
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("id", namespace + ":" + rel);
            // SoundFactory will build the sounds.json entry pointing to namespace:sounds/rel
            try {
                com.koper.koper_lib.factory.SoundFactory.createAndRegister(json);
                KoperLib.LOGGER.debug("[UniversalLoader] Auto-detected sound: {}:{}", namespace, rel);
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[UniversalLoader] Failed auto-registering sound {}:{}: {}", namespace, rel, e.getMessage());
            }
        }
    }

    // walks datapacks/{sub}/data/{namespace}/{type}/...{name}.json and pushes into virtual server pack
    // pack creators can drop standard MC datapacks here — recipes, loot tables, tags, advancements, etc.
    private void loadEmbeddedDatapacks(File packDir) {
        File datapacks = new File(packDir, "datapacks");
        if (!datapacks.exists() || !datapacks.isDirectory()) return;

        File[] subs = datapacks.listFiles(File::isDirectory);
        if (subs == null) return;

        for (File sub : subs) {
            File dataDir = new File(sub, "data");
            if (!dataDir.exists() || !dataDir.isDirectory()) continue;

            File[] namespaces = dataDir.listFiles(File::isDirectory);
            if (namespaces == null) continue;

            int count = 0;
            for (File nsDir : namespaces) {
                String namespace = nsDir.getName();
                count += pushServerFiles(nsDir, nsDir, namespace);
            }
            KoperLib.LOGGER.info("[UniversalLoader] Embedded datapack '{}' — {} server assets registered.", sub.getName(), count);
        }
    }

    // recursively registers all .json under nsDir as SERVER_DATA assets
    private int pushServerFiles(File root, File dir, String namespace) {
        int count = 0;
        File[] entries = dir.listFiles();
        if (entries == null) return 0;
        for (File f : entries) {
            if (f.isDirectory()) {
                count += pushServerFiles(root, f, namespace);
            } else if (f.getName().endsWith(".json")) {
                String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                // strip .json suffix for identifier path
                String idPath = rel.replaceAll("\\.json$", "");
                try {
                    String content = java.nio.file.Files.readString(f.toPath());
                    net.minecraft.resources.Identifier id =
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, idPath);
                    KoperLib.VIRTUAL_PACK.addServerAsset(id, content);
                    count++;
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[UniversalLoader] Failed reading datapack asset {}/{}: {}", namespace, idPath, e.getMessage());
                }
            }
        }
        return count;
    }

    private void scanContentDirectory(File dir) {
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) for (File subdir : subdirs) {
            if (recursiveContentType(subdir.getName())) {
                scanJsonTree(subdir, DIR_TO_TYPE.get(subdir.getName().toLowerCase()));
            } else {
                scanJsonsDirectory(subdir);
            }
        }
        scanJsonsDirectory(dir);
    }

    private void scanJsonsDirectory(File dir) {
        scanJsonsDirectory(dir, null);
    }

    private void scanJsonTree(File dir, String forcedType) {
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) for (File child : children) scanJsonTree(child, forcedType);
        scanJsonsDirectory(dir, forcedType);
    }

    static boolean recursiveContentType(String directory) {
        if (directory == null) return false;
        return "kfx".equals(DIR_TO_TYPE.get(directory.toLowerCase()));
    }

    private void scanJsonsDirectory(File dir, String forcedType) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                // kui companion files aren't registrations — they're pointed at by the main gui json
                String fn = file.getName();
                if (fn.endsWith(".layout.json") || fn.endsWith(".regions.json")) continue;
                try (java.io.FileReader reader = new java.io.FileReader(file)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    if (json == null) {
                        com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
                        KoperLib.LOGGER.warn("Failed to load JSON: {} in {} is empty or null", file.getName(), dir.getName());
                        continue;
                    }

                    if (!json.has("type")) {
                        String inferred = forcedType != null
                            ? forcedType
                            : DIR_TO_TYPE.get(dir.getName().toLowerCase());
                        if (inferred != null) json.addProperty("type", inferred);
                    }

                    // id from the filename when the pack didn't spell one out. scripts already bind
                    // this way, so items/flame_sword.json being flame_sword is the obvious rule.
                    // without this the factories used to return silently and the content just vanished
                    if (!json.has("id") && currentPackDir != null) {
                        KoperMeta idMeta = FullPackLoader.getMeta(currentPackDir.getName());
                        String idNs = idMeta != null
                            ? idMeta.getEffectiveNamespace(currentPackDir.getName())
                            : currentPackDir.getName().toLowerCase();
                        json.addProperty("id", idNs + ":" + file.getName().replace(".json", ""));
                    }

                    // auto-bind script if JSON has none: check {pack}/scripts/{name}.lua
                    if (!json.has("scripts") && !json.has("script") && currentPackDir != null) {
                        String baseName   = file.getName().replace(".json", "");
                        String parentType = dir.getName().toLowerCase();
                        File candidate = new File(currentPackDir, "scripts/" + baseName + ".lua");
                        if (!candidate.exists()) {
                            candidate = new File(currentPackDir, "scripts/" + parentType + "/" + baseName + ".lua");
                        }
                        if (candidate.exists()) {
                            KoperMeta meta = FullPackLoader.getMeta(currentPackDir.getName());
                            String ns = meta != null
                                ? meta.getEffectiveNamespace(currentPackDir.getName())
                                : currentPackDir.getName().toLowerCase();
                            String scriptId = ns + ":" + baseName;
                            json.addProperty("script", scriptId);
                            KoperLib.LOGGER.info("[UniversalLoader] Auto-bound script {} → {}", scriptId,
                                json.has("id") ? json.get("id").getAsString() : baseName);
                        }
                    }

                    // gui: resolve the layout/texture file to an absolute path so the factory + open
                    // command can read it. default layout sits next to the registration json as <base>.layout.json
                    if (isGuiDir(dir) && currentPackDir != null) {
                        String base = file.getName().replace(".json", "");
                        File layoutF = json.has("layout")
                            ? new File(currentPackDir, json.get("layout").getAsString())
                            : new File(file.getParentFile(), base + ".layout.json");
                        if (layoutF.exists()) json.addProperty("layout_file", layoutF.getAbsolutePath());

                        // texture/regions are generated by the baker later in this same reload, so store the
                        // expected path even if it doesn't exist yet — open-time reads it once it's been baked
                        File texF = json.has("texture")
                            ? new File(currentPackDir, json.get("texture").getAsString())
                            : new File(currentPackDir, "textures/gui/" + base + ".png");
                        json.addProperty("texture_file", texF.getAbsolutePath());

                        File regF = json.has("regions")
                            ? new File(currentPackDir, json.get("regions").getAsString())
                            : new File(file.getParentFile(), base + ".regions.json");
                        json.addProperty("regions_file", regF.getAbsolutePath());
                    }

                    processJson(json, file.getName());
                } catch (Exception e) {
                    com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
                    KoperLib.LOGGER.warn("Failed to load JSON: {} in {}", file.getName(), dir.getName(), e);
                }
            }
        }
    }

    private static boolean isGuiDir(File dir) {
        String n = dir.getName().toLowerCase();
        return n.equals("gui") || n.equals("guis");
    }

    private void processJson(JsonObject json, String sourceName) {
        if (!json.has("type")) {
            com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
            KoperLib.LOGGER.warn("UniversalLoader: Skipping {} — missing 'type' and could not infer.", sourceName);
            return;
        }
        String type = json.get("type").getAsString();
        try {
            switch (type) {
                // Items (all subtypes)
                case "sword", "tool", "pickaxe", "axe", "shovel", "hoe", "item", "food", "gem",
                     "material", "armor", "helmet", "chestplate", "leggings", "boots",
                     "bow", "crossbow", "spear", "trident", "shield", "portal_igniter" ->
                    ItemFactory.createAndRegister(json);

                case "block" ->
                    BlockFactory.createAndRegister(json);

                case "mob", "entity" ->
                    EntityFactory.createAndRegister(json);

                case "spawn_egg", "spawnegg" ->
                    com.koper.koper_lib.factory.SpawnEggFactory.createAndRegister(json);

                case "enchantment" ->
                    com.koper.koper_lib.factory.EnchantmentFactory.createAndRegister(json);

                case "dimension" ->
                    com.koper.koper_lib.factory.DimensionFactory.createAndRegister(json);

                case "potion" ->
                    com.koper.koper_lib.factory.PotionFactory.createAndRegister(json);

                case "attribute" ->
                    com.koper.koper_lib.factory.AttributeFactory.createAndRegister(json);

                case "effect", "status_effect" ->
                    com.koper.koper_lib.factory.StatusEffectFactory.createAndRegister(json);

                case "recipe", "shaped", "shapeless", "smelting", "smoking",
                     "blasting", "campfire", "stonecutting", "smithing", "custom_station" ->
                    com.koper.koper_lib.factory.RecipeFactory.createAndRegister(json);

                case "loot", "loot_table" ->
                    com.koper.koper_lib.factory.LootTableFactory.createAndRegister(json);

                case "creative_tab" -> {
                    if (json.has("id")) {
                        String tabId   = json.get("id").getAsString();
                        String tabName = json.has("name") ? json.get("name").getAsString() : null;
                        String icon    = json.has("icon")  ? json.get("icon").getAsString()  : null;
                        int order      = json.has("order") ? json.get("order").getAsInt()    : 999;
                        CreativeTabRegistry.defineTab(tabId, tabName, icon, order);
                        KoperLib.LOGGER.info("UniversalLoader: Registered creative tab: {}", tabId);
                    }
                }

                case "advancement" ->
                    com.koper.koper_lib.factory.AdvancementFactory.createAndRegister(json);

                case "gui" ->
                    com.koper.koper_lib.factory.GuiFactory.createAndRegister(json);

                case "quest" ->
                    com.koper.koper_lib.factory.QuestFactory.createAndRegister(json);

                case "book" ->
                    com.koper.koper_lib.factory.BookFactory.createAndRegister(json);

                case "dialog" ->
                    com.koper.koper_lib.factory.DialogFactory.createAndRegister(json);

                case "sound" ->
                    com.koper.koper_lib.factory.SoundFactory.createAndRegister(json);

                case "event" ->
                    KoperLib.LOGGER.debug("UniversalLoader: {} type '{}' — handler not yet implemented.", sourceName, type);

                default -> {
                    // java-registered item types (wands etc.) are items too — the hard case list
                    // above knows nothing about them and used to drop the json on the floor
                    if (com.koper.koper_lib.api.FullpackAddons.dispatch(type, json, sourceName)) {
                        // handled by an installed feature module
                    } else if (com.koper.koper_lib.api.KoperItemTypes.ids().contains(type.toLowerCase())) {
                        ItemFactory.createAndRegister(json);
                    } else {
                        com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
                        KoperLib.LOGGER.warn("UniversalLoader: Unknown type '{}' in {}", type, sourceName);
                    }
                }
            }

            // Track loaded content
            if (json.has("id")) {
                loadedIds.add(json.get("id").getAsString());
            }
        } catch (Exception e) {
            com.koper.koper_lib.api.FullpackAddons.markReloadFailure();
            KoperLib.LOGGER.warn("Failed processing {} (type={}): {}", sourceName, type, e.getMessage(), e);
        }
    }

    public void loadFromResourceManager(ResourceManager manager) {
        KoperLib.LOGGER.info("UniversalLoader: Reloading data-driven logic from ResourceManager...");
    }
}
