package com.koper.koper_lib.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import net.minecraft.resources.Identifier;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.koper.koper_lib.KoperLib;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

// turns a behavior pack + resource pack into a plain koper fullpack folder. every bedrock json
// shape here comes from the public addon docs, the output is the same json a person would
// hand write for koperlib, so nothing downstream has to know bedrock exists
public final class BedrockTlumacz {

    private static final Gson GSON_LADNY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // one converted addon. bp/rp can be null, a lone resource pack is still worth converting for its lang/sounds
    public record Paczka(String name, Path bp, Path rp) {}

    private final Paczka paczka;
    private final Path out;
    private String ns;
    private final Map<String, String> lang = new LinkedHashMap<>();
    private final Map<String, List<String>> itemIcons = new HashMap<>();
    private final Map<String, List<String>> terrain = new HashMap<>();
    private final Map<String, JsonObject> rpBlocks = new HashMap<>();
    private final Map<String, JsonObject> clientEntities = new HashMap<>();
    private final Map<String, JsonObject> geometries = new HashMap<>();
    private final Map<String, Path> geometryFiles = new HashMap<>();
    private final Map<String, String> geometryParents = new HashMap<>();
    private final Map<String, JsonObject> animations = new HashMap<>();
    private final JsonObject sidecar = new JsonObject();
    // the texture pass runs on the wheelbarrow's threads, both of these get written from there
    private final List<String> wtf = java.util.Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger copied = new java.util.concurrent.atomic.AtomicInteger();
    private final BedrockTaczka.Katalogi katalogi = new BedrockTaczka.Katalogi();
    // rp items/ by identifier, read once. it used to be read again for every bp item

    private BedrockTlumacz(Paczka paczka, Path out) {
        this.paczka = paczka;
        this.out = out;
    }

    public static JsonObject translate(Paczka paczka, Path out) throws IOException {
        BedrockTlumacz t = new BedrockTlumacz(paczka, out);
        t.run();
        return t.sidecar;
    }

    // bedrock json is allowed // comments and trailing commas, gson lenient eats both
    // does java have this item? the game swaps in the registry at start (KoperLib init), tests know vanilla's
    // namespace only. not the registry right here: converting runs in plain unit tests too
    static volatile java.util.function.Predicate<String> javaItem = id -> id.startsWith("minecraft:");

    static JsonElement czytaj(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (!raw.isEmpty() && raw.charAt(0) == '﻿') raw = raw.substring(1);
        JsonReader reader = new JsonReader(new StringReader(raw));
        reader.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(reader);
    }

    static JsonObject czytajObj(Path file) {
        // absent is normal (ordinary fullpacks, no subpack config), only a broken file is worth a warning
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonElement e = czytaj(file);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception bad) {
            KoperLib.LOGGER.warn("[Bedrock] cant read {}: {}", file, bad.getMessage());
            return null;
        }
    }

    private void run() throws IOException {
        Files.createDirectories(out);
        ns = pickNamespace();

        if (paczka.rp() != null) {
            readLang(paczka.rp());
            readHudTitleCodes(paczka.rp());
            readTextureAtlases();
            readClientEntities();
            readGeometry();
            readAnimations();
            copySounds();
            glosy();
            writeLang();
            rpRuntime();
        }
        if (paczka.bp() != null) {
            readLang(paczka.bp());
            items();
            blocks();
            entities();
            recipes();
            lootTables();
            spawnRules();
            bpAnimations();
            functions();
            scripts();
            structures();
            sprzataj();
        }
        leftoverModels();
        writeMeta();

        sidecar.addProperty("namespace", ns);
        JsonArray w = new JsonArray();
        wtf.forEach(w::add);
        sidecar.add("warnings", w);
        Files.writeString(out.resolve("bedrock.koper.json"), GSON_LADNY.toJson(sidecar));
        KoperLib.LOGGER.info("[Bedrock] {} -> {} (namespace {}, {} files, {} warnings)",
            paczka.name(), out.getFileName(), ns, copied.get(), wtf.size());
    }

    // ── namespace ────────────────────────────────────────────────────────────

    // most used identifier namespace wins. an addon usually is one namespace anyway
    private String pickNamespace() throws IOException {
        Map<String, Integer> votes = new HashMap<>();
        if (paczka.bp() != null) for (String dir : List.of("items", "blocks", "entities")) {
            for (Path f : jsons(paczka.bp().resolve(dir))) {
                JsonObject root = czytajObj(f);
                if (root == null) continue;
                for (String key : List.of("minecraft:item", "minecraft:block", "minecraft:entity")) {
                    String id = str(obj(obj(root, key), "description"), "identifier", null);
                    if (id != null && id.contains(":") && !id.startsWith("minecraft:"))
                        votes.merge(id.substring(0, id.indexOf(':')), 1, Integer::sum);
                }
            }
        }
        String best = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (best == null) best = paczka.name();
        return best.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    // ── lang ─────────────────────────────────────────────────────────────────

    private void readLang(Path pack) throws IOException {
        Path texts = pack.resolve("texts");
        if (!Files.isDirectory(texts)) return;
        Path en = null;
        try (Stream<Path> s = Files.list(texts)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.equalsIgnoreCase("en_US.lang")) en = p;
                else if (en == null && n.equalsIgnoreCase("en_GB.lang")) en = p;
            }
        }
        if (en == null) return;
        for (String line : Files.readAllLines(en, StandardCharsets.UTF_8)) {
            if (line.startsWith("##") || !line.contains("=")) continue;
            int eq = line.indexOf('=');
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1);
            // "value\t#comment" and "value #" both show up in the wild
            int hash = val.indexOf("\t#");
            if (hash >= 0) val = val.substring(0, hash);
            // bedrock lang has its own newline token, java just wants \n
            lang.put(key, val.strip().replace("~LINEBREAK~", "\n"));
        }
    }

    // packs with their own hud_screen.json drive bars and icons through titles: the json ui compares
    // ('%.12s' * #hud_title_text_string = 'thirstbar20;') and never shows the text. java has no json ui,
    // so the client needs the list to not print "thirstbar20;" across the screen (BedrockHudCodes)
    private static final java.util.regex.Pattern HUD_CODE = java.util.regex.Pattern.compile(
        "#hud_(?:title|subtitle|actionbar)_text_string\\s*=\\s*'([^']{1,48})'");

    private static final java.util.regex.Pattern HUD_FUNCTION_TITLE = java.util.regex.Pattern.compile(
        "\\btitle\\s+@\\S+\\s+(?:title|subtitle)\\s+([^\"{\\[].{0,47})$");

    private void readHudTitleCodes(Path pack) throws IOException {
        Path ui = pack.resolve("ui");
        if (!Files.isDirectory(ui)) return;
        java.util.Set<String> codes = new java.util.TreeSet<>();
        try (Stream<Path> s = Files.walk(ui)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                var m = HUD_CODE.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) if (!m.group(1).isBlank()) codes.add(m.group(1));
            }
        }
        if (codes.isEmpty()) return;
        // the hud owns the title: what the pack's own functions put there is a state for it too, even the ones the
        // ui only shows by not matching anything (mowzie's "effects" = no effect, its hud draws nothing)
        if (paczka.bp() != null && Files.isDirectory(paczka.bp().resolve("functions"))) {
            try (Stream<Path> s = Files.walk(paczka.bp().resolve("functions"))) {
                for (Path f : s.filter(p -> p.toString().endsWith(".mcfunction")).toList()) {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        var m = HUD_FUNCTION_TITLE.matcher(line);
                        if (m.find()) codes.add(m.group(1).strip());
                    }
                }
            }
        }
        JsonArray a = new JsonArray();
        codes.forEach(a::add);
        sidecar.add("hud_title_codes", a);
    }

    private void writeLang() throws IOException {
        if (lang.isEmpty()) return;
        JsonObject j = new JsonObject();
        lang.forEach(j::addProperty);
        Path f = out.resolve("assets").resolve(ns).resolve("lang").resolve("en_us.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON_LADNY.toJson(j));
    }

    private String nice(String key, String fallbackId) {
        if (key != null) {
            String hit = lang.get(key);
            if (hit != null) return hit;
            if (!key.contains(".") || key.contains(" ")) return key;
        }
        for (String k : List.of("item." + fallbackId + ".name", "item." + fallbackId, "tile." + fallbackId + ".name",
                                "entity." + fallbackId + ".name", "item.spawn_egg.entity." + fallbackId + ".name")) {
            String hit = lang.get(k);
            if (hit != null) return hit;
        }
        String path = fallbackId.contains(":") ? fallbackId.substring(fallbackId.indexOf(':') + 1) : fallbackId;
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("_")) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    // ── textures ─────────────────────────────────────────────────────────────

    private void readTextureAtlases() {
        readAtlas(paczka.rp().resolve("textures/item_texture.json"), itemIcons);
        readAtlas(paczka.rp().resolve("textures/terrain_texture.json"), terrain);
        JsonObject blocksJson = czytajObj(paczka.rp().resolve("blocks.json"));
        if (blocksJson != null) blocksJson.entrySet().forEach(e -> {
            if (e.getValue().isJsonObject()) rpBlocks.put(e.getKey(), e.getValue().getAsJsonObject());
        });
    }

    private void readAtlas(Path file, Map<String, List<String>> into) {
        if (!Files.isRegularFile(file)) return;
        JsonObject data = obj(czytajObj(file), "texture_data");
        if (data == null) return;
        for (var e : data.entrySet()) {
            List<String> paths = new ArrayList<>();
            JsonElement t = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("textures") : null;
            collectTexturePaths(t, paths);
            if (!paths.isEmpty()) into.put(e.getKey(), paths);
        }
    }

    private static void collectTexturePaths(JsonElement t, List<String> paths) {
        if (t == null) return;
        if (t.isJsonPrimitive()) paths.add(t.getAsString());
        else if (t.isJsonArray()) t.getAsJsonArray().forEach(x -> collectTexturePaths(x, paths));
        else if (t.isJsonObject() && t.getAsJsonObject().has("path")) paths.add(t.getAsJsonObject().get("path").getAsString());
    }

    // copies an rp texture (path without extension) to assets/<ns>/textures/<kind>/<name>.png, returns "<ns>:<kind>/<name>"
    private String przenosTeksture(String rpPath, String kind) {
        if (rpPath == null || paczka.rp() == null) return null;
        // item_texture.json sometimes says "textures/items/xp.png", the extension is ours to add (it made xp.png.png)
        rpPath = rpPath.replaceFirst("(?i)\\.(png|tga|jpg)$", "");
        Path src = null;
        for (String ext : List.of(".png", ".tga", "")) {
            Path p = paczka.rp().resolve(rpPath + ext);
            if (Files.isRegularFile(p)) { src = p; break; }
        }
        if (src == null) {
            wtf.add("texture missing: " + rpPath);
            return null;
        }
        if (src.getFileName().toString().endsWith(".tga")) {
            wtf.add("tga texture not converted: " + rpPath + " (save it as png)");
            return null;
        }
        String name = rpPath.substring(rpPath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        Path dst = out.resolve("assets").resolve(ns).resolve("textures").resolve(kind).resolve(name + ".png");
        kopiuj(src, dst);
        // mcmeta rides along for animated textures
        Path meta = src.resolveSibling(src.getFileName() + ".mcmeta");
        if (Files.isRegularFile(meta)) kopiuj(meta, dst.resolveSibling(name + ".png.mcmeta"));
        return ns + ":" + kind + "/" + name;
    }

    private void kopiuj(Path src, Path dst) {
        try {
            katalogi.dla(dst);
            BedrockTaczka.przenies(src, dst);
            copied.incrementAndGet();
        } catch (IOException e) {
            wtf.add("copy failed " + src.getFileName() + ": " + e.getMessage());
        }
    }

    // ── items ────────────────────────────────────────────────────────────────

    private void items() throws IOException {
        JsonObject itemComps = new JsonObject();
        JsonObject throwables = new JsonObject();
        for (Path f : jsons(paczka.bp().resolve("items"))) {
            JsonObject root = czytajObj(f);
            JsonObject item = obj(root, "minecraft:item");
            if (item == null) continue;
            String id = str(obj(item, "description"), "identifier", null);
            if (id == null || id.startsWith("minecraft:")) continue;
            JsonObject c = obj(item, "components");
            if (c == null) c = new JsonObject();
            // old 1.10 format keeps the icon in the rp item file, pull it in
            JsonObject rpItem = rpItem(id);
            if (rpItem != null) for (var e : rpItem.entrySet()) if (!c.has(e.getKey())) c.add(e.getKey(), e.getValue());

            JsonObject k = new JsonObject();
            k.addProperty("id", id);
            k.addProperty("type", itemType(c));
            k.addProperty("name", nice(displayName(c), id));

            String icon = iconShort(c.get("minecraft:icon"));
            List<String> iconPaths = icon != null ? itemIcons.get(icon) : null;
            String tex = iconPaths != null ? przenosTeksture(iconPaths.get(0), "item") : null;
            if (tex != null) k.addProperty("texture", tex);
            else if (icon != null) wtf.add("item " + id + " icon '" + icon + "' not in item_texture.json");

            int stack = num(c.get("minecraft:max_stack_size"), "value", -1);
            if (stack > 0) k.addProperty("max_stack", stack);
            JsonObject dur = obj(c, "minecraft:durability");
            if (dur != null) k.addProperty("durability", num(dur.get("max_durability"), null, 100));
            int dmg = num(c.get("minecraft:damage"), "value", -1);
            if (dmg >= 0) k.addProperty("damage", dmg);
            JsonObject food = obj(c, "minecraft:food");
            if (food != null) {
                k.addProperty("food_hunger", num(food.get("nutrition"), null, 1));
                k.addProperty("food_saturation", saturation(food.get("saturation_modifier")));
                if (bool(food, "can_always_eat")) k.addProperty("always_edible", true);
            }
            if (c.has("minecraft:glint") || c.has("minecraft:foil")) k.addProperty("glint", true);
            if (c.has("minecraft:fire_resistant")) k.addProperty("fireproof", true);
            JsonObject cool = obj(c, "minecraft:cooldown");
            if (cool != null && cool.has("duration")) k.addProperty("cooldown_ticks", Math.round(cool.get("duration").getAsFloat() * 20));
            String rarity = str(obj(c, "minecraft:rarity"), "value", c.has("minecraft:rarity") && c.get("minecraft:rarity").isJsonPrimitive() ? c.get("minecraft:rarity").getAsString() : null);
            if (rarity != null) k.addProperty("rarity", rarity.toLowerCase(Locale.ROOT));
            JsonObject wear = obj(c, "minecraft:wearable");
            int prot = wear != null ? num(wear.get("protection"), null, -1) : -1;
            if (prot < 0) prot = num(obj(c, "minecraft:armor") == null ? null : obj(c, "minecraft:armor").get("protection"), null, -1);
            if (prot >= 0) k.addProperty("defense", prot);

            JsonArray comps = customComponents(c);
            if (!comps.isEmpty()) itemComps.add(id, comps);

            // throwable + projectile: right click throws projectile_entity (spears, bombs, spells)
            JsonObject thr = obj(c, "minecraft:throwable");
            JsonObject proj = obj(c, "minecraft:projectile");
            if (thr != null && proj != null && proj.has("projectile_entity")) {
                JsonObject t = new JsonObject();
                t.addProperty("entity", proj.get("projectile_entity").getAsString());
                t.addProperty("power", thr.has("max_launch_power") ? thr.get("max_launch_power").getAsFloat() * 1.5f
                    : thr.has("launch_power_scale") ? thr.get("launch_power_scale").getAsFloat() * 1.5f : 1.5f);
                t.addProperty("swing", !thr.has("do_swing_animation") || thr.get("do_swing_animation").getAsBoolean());
                throwables.add(id, t);
            }

            Files.createDirectories(out.resolve("items"));
            Files.writeString(out.resolve("items").resolve(fileName(id) + ".json"), GSON_LADNY.toJson(k));
        }
        sidecar.add("items", itemComps);
        if (!throwables.isEmpty()) sidecar.add("throwables", throwables);
    }

    private Map<String, JsonObject> rpItems;

    private JsonObject rpItem(String id) throws IOException {
        if (paczka.rp() == null) return null;
        if (rpItems == null) {
            rpItems = new HashMap<>();
            for (Path f : jsons(paczka.rp().resolve("items"))) {
                JsonObject item = obj(czytajObj(f), "minecraft:item");
                String iid = str(obj(item, "description"), "identifier", null);
                if (iid != null) rpItems.putIfAbsent(iid, obj(item, "components"));
            }
        }
        return rpItems.get(id);
    }

    private static String itemType(JsonObject c) {
        JsonObject wear = obj(c, "minecraft:wearable");
        if (wear != null) {
            String slot = str(wear, "slot", "");
            if (slot.contains("head")) return "helmet";
            if (slot.contains("chest")) return "chestplate";
            if (slot.contains("legs")) return "leggings";
            if (slot.contains("feet")) return "boots";
        }
        if (c.has("minecraft:food")) return "food";
        if (c.has("minecraft:shooter")) return "bow";
        JsonObject dig = obj(c, "minecraft:digger");
        if (dig != null) {
            String speeds = dig.toString();
            if (speeds.contains("log") || speeds.contains("wood") || speeds.contains("is_axe")) return "axe";
            if (speeds.contains("dirt") || speeds.contains("sand") || speeds.contains("is_shovel")) return "shovel";
            if (speeds.contains("is_hoe")) return "hoe";
            return "pickaxe";
        }
        if (c.has("minecraft:damage") && bool(c, "minecraft:hand_equipped")) return "sword";
        return "item";
    }

    private static String displayName(JsonObject c) {
        JsonElement dn = c.get("minecraft:display_name");
        if (dn == null) return null;
        if (dn.isJsonPrimitive()) return dn.getAsString();
        return str(dn.getAsJsonObject(), "value", null);
    }

    private static String iconShort(JsonElement icon) {
        if (icon == null) return null;
        if (icon.isJsonPrimitive()) return icon.getAsString();
        JsonObject o = icon.getAsJsonObject();
        if (o.has("texture")) return o.get("texture").getAsString();
        JsonObject tx = obj(o, "textures");
        if (tx != null && tx.has("default")) return tx.get("default").getAsString();
        return null;
    }

    private static float saturation(JsonElement e) {
        if (e == null) return 0.6f;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
        return switch (e.getAsString()) {
            case "poor" -> 0.1f;
            case "low" -> 0.3f;
            case "good" -> 0.8f;
            case "max" -> 1.0f;
            case "supernatural" -> 1.2f;
            default -> 0.6f;
        };
    }

    // 1.x: "minecraft:custom_components": ["a:b"], 2.x: the component key itself with params
    private static JsonArray customComponents(JsonObject c) {
        JsonArray out = new JsonArray();
        JsonElement old = c.get("minecraft:custom_components");
        if (old != null && old.isJsonArray()) for (JsonElement n : old.getAsJsonArray()) {
            JsonObject e = new JsonObject();
            e.addProperty("n", n.getAsString());
            e.add("p", new JsonObject());
            out.add(e);
        }
        for (var e : c.entrySet()) {
            String key = e.getKey();
            if (!key.contains(":") || key.startsWith("minecraft:") || key.startsWith("tag:")) continue;
            JsonObject cc = new JsonObject();
            cc.addProperty("n", key);
            cc.add("p", e.getValue());
            out.add(cc);
        }
        return out;
    }

    // ── blocks ───────────────────────────────────────────────────────────────

    private void blocks() throws IOException {
        JsonObject blockComps = new JsonObject();
        for (Path f : jsons(paczka.bp().resolve("blocks"))) {
            JsonObject block = obj(czytajObj(f), "minecraft:block");
            if (block == null) continue;
            JsonObject desc = obj(block, "description");
            String id = str(desc, "identifier", null);
            if (id == null || id.startsWith("minecraft:")) continue;
            JsonObject c = obj(block, "components");
            if (c == null) c = new JsonObject();

            JsonObject k = new JsonObject();
            k.addProperty("id", id);
            k.addProperty("type", "block");
            k.addProperty("name", nice(displayName(c), id));

            JsonElement mine = c.get("minecraft:destructible_by_mining");
            if (mine != null) {
                if (mine.isJsonPrimitive() && !mine.getAsBoolean()) k.addProperty("hardness", -1);
                else if (mine.isJsonObject()) k.addProperty("hardness", mine.getAsJsonObject().has("seconds_to_destroy")
                    ? mine.getAsJsonObject().get("seconds_to_destroy").getAsFloat() / 1.5f : 1f);
            } else if (c.has("minecraft:destroy_time")) {
                k.addProperty("hardness", num(c.get("minecraft:destroy_time"), "value", 1));
            }
            JsonElement boom = c.get("minecraft:destructible_by_explosion");
            if (boom != null && boom.isJsonObject() && boom.getAsJsonObject().has("explosion_resistance"))
                k.addProperty("resistance", boom.getAsJsonObject().get("explosion_resistance").getAsFloat() / 5f);
            JsonElement light = c.get("minecraft:light_emission");
            if (light != null) k.addProperty("light_level", light.isJsonPrimitive() ? light.getAsInt() : num(light, "emission", 0));
            JsonElement fric = c.get("minecraft:friction");
            // bedrock friction goes the other way round: 0.4 there is the plain 0.6 here
            if (fric != null && fric.isJsonPrimitive()) k.addProperty("slipperiness", 1f - fric.getAsFloat());
            JsonElement coll = c.get("minecraft:collision_box");
            if (coll != null && coll.isJsonPrimitive() && !coll.getAsBoolean()) k.addProperty("no_collision", true);

            // textures: material_instances first, rp blocks.json for old packs
            Map<String, String> faces = blockFaces(id, obj(c, "minecraft:material_instances"));
            if (faces.size() == 1 || faces.containsKey("all") && faces.size() == 1) {
                k.addProperty("texture", faces.values().iterator().next());
            } else if (!faces.isEmpty()) {
                JsonObject t = new JsonObject();
                faces.forEach(t::addProperty);
                k.add("texture", t);
            }
            JsonObject mats = obj(c, "minecraft:material_instances");
            if (mats != null && mats.toString().contains("blend")) k.addProperty("render_type", "translucent");
            else if (mats != null && mats.toString().contains("alpha_test")) k.addProperty("render_type", "cutout");

            String geo = geometryId(c.get("minecraft:geometry"));
            if (geo != null && !geo.startsWith("minecraft:geometry.full_block") && !geo.equals("minecraft:geometry.full_block")) {
                if (geo.startsWith("minecraft:geometry.")) {
                    wtf.add("block " + id + " uses built in shape " + geo + ", drawn as a full cube");
                } else {
                    String model = modelName(geo);
                    JsonObject kender = new JsonObject();
                    kender.addProperty("model", model);
                    String tex = faces.getOrDefault("all", faces.isEmpty() ? null : faces.values().iterator().next());
                    if (tex != null) kender.addProperty("texture", tex.substring(tex.indexOf(':') + 1).replace("block/", ""));
                    kender.addProperty("render_type", "cutout");
                    k.add("kender", kender);
                    k.addProperty("non_opaque", true);
                    wantModel(geo, List.of(), tex);
                }
            }

            JsonElement loot = c.get("minecraft:loot");
            if (loot != null && loot.isJsonPrimitive()) {
                if (lootTable(loot.getAsString(), "loot_table/blocks/" + fileName(id), "minecraft:block")) k.addProperty("drops_self", false);
            }
            if (c.has("minecraft:tick") || c.has("minecraft:random_ticking")) k.addProperty("random_ticks", true);

            JsonArray comps = customComponents(c);
            // permutations can add components, the scripts still expect the handlers to exist
            JsonArray perms = block.has("permutations") && block.get("permutations").isJsonArray() ? block.getAsJsonArray("permutations") : null;
            if (perms != null) {
                for (JsonElement p : perms) {
                    JsonObject pc = obj(p.getAsJsonObject(), "components");
                    if (pc != null) for (JsonElement x : customComponents(pc)) if (!comps.contains(x)) comps.add(x);
                }
            }
            stany(id, desc, c, perms, k, faces);
            if (!comps.isEmpty()) {
                // koper blocks tick and get stepped on through their json events; empty lists are enough
                // to switch that machinery on, the actual work happens in the bedrock hook ear
                JsonObject ev = new JsonObject();
                ev.add("on_step", new JsonArray());
                JsonObject tickC = obj(c, "minecraft:tick");
                if (tickC != null) {
                    ev.add("on_tick", new JsonArray());
                    JsonArray range = tickC.has("interval_range") ? tickC.getAsJsonArray("interval_range") : null;
                    k.addProperty("tick_interval", range != null ? Math.max(1, range.get(0).getAsInt()) : 20);
                }
                k.add("events", ev);
                JsonObject b = new JsonObject();
                b.add("comps", comps);
                JsonObject tick = obj(c, "minecraft:tick");
                if (tick != null) {
                    JsonArray range = tick.has("interval_range") ? tick.getAsJsonArray("interval_range") : null;
                    b.addProperty("tick_min", range != null ? range.get(0).getAsInt() : 20);
                    b.addProperty("tick_max", range != null && range.size() > 1 ? range.get(1).getAsInt() : 20);
                    b.addProperty("tick_loop", !tick.has("looping") || tick.get("looping").getAsBoolean());
                }
                blockComps.add(id, b);
            }

            Files.createDirectories(out.resolve("blocks"));
            Files.writeString(out.resolve("blocks").resolve(fileName(id) + ".json"), GSON_LADNY.toJson(k));
        }
        sidecar.add("blocks", blockComps);
    }

    // ── block states, traits, permutations ──────────────────────────────────

    // one bedrock state as java sees it
    private record Stan(String bedrock, String java, List<String> values, String decl) {}

    private void stany(String id, JsonObject desc, JsonObject base, JsonArray perms, JsonObject k, Map<String, String> baseFaces) throws IOException {
        List<Stan> stany = new ArrayList<>();
        JsonObject decl = obj(desc, "states");
        if (decl == null) decl = obj(desc, "properties");
        if (decl != null) for (var e : decl.entrySet()) {
            String jn = e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            List<String> vals = new ArrayList<>();
            String d;
            JsonElement v = e.getValue();
            if (v.isJsonObject() && obj(v.getAsJsonObject(), "values") != null) {
                JsonObject r = obj(v.getAsJsonObject(), "values");
                int lo = r.get("min").getAsInt(), hi = r.get("max").getAsInt();
                for (int i = lo; i <= hi; i++) vals.add(String.valueOf(i));
                d = "int:" + jn + ":" + lo + ":" + hi;
            } else if (v.isJsonArray()) {
                JsonArray a = v.getAsJsonArray();
                boolean bools = true, ints = true;
                for (JsonElement x : a) {
                    bools &= x.isJsonPrimitive() && x.getAsJsonPrimitive().isBoolean();
                    ints &= x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber();
                    vals.add(x.getAsString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"));
                }
                if (bools) d = "bool:" + jn;
                else if (ints) {
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    for (JsonElement x : a) { lo = Math.min(lo, x.getAsInt()); hi = Math.max(hi, x.getAsInt()); }
                    boolean dense = hi - lo + 1 == a.size();
                    d = dense ? "int:" + jn + ":" + lo + ":" + hi : "enum:" + jn + ":" + String.join("|", vals);
                } else d = "enum:" + jn + ":" + String.join("|", vals);
            } else continue;
            stany.add(new Stan(e.getKey(), jn, vals, d));
        }
        JsonObject traits = obj(desc, "traits");
        JsonObject dir = obj(traits, "minecraft:placement_direction");
        JsonObject pos = obj(traits, "minecraft:placement_position");
        List<String> dirStates = new ArrayList<>();
        if (dir != null && dir.has("enabled_states")) dir.getAsJsonArray("enabled_states").forEach(x -> dirStates.add(x.getAsString()));
        if (pos != null && pos.has("enabled_states")) pos.getAsJsonArray("enabled_states").forEach(x -> dirStates.add(x.getAsString()));
        boolean six = dirStates.contains("minecraft:facing_direction") || dirStates.contains("minecraft:block_face");
        if (six) stany.add(new Stan("minecraft:facing_direction", "facing", List.of("down", "up", "north", "south", "west", "east"), "facing"));
        else if (dirStates.contains("minecraft:cardinal_direction"))
            stany.add(new Stan("minecraft:cardinal_direction", "facing", List.of("north", "south", "west", "east"), "horizontal_facing"));
        if (dirStates.contains("minecraft:vertical_half")) stany.add(new Stan("minecraft:vertical_half", "half", List.of("bottom", "top"), "half"));
        if (stany.isEmpty()) {
            if (perms != null && !perms.isEmpty()) wtf.add("block " + id + " has permutations but no states, only the base is used");
            return;
        }

        JsonArray decls = new JsonArray();
        JsonObject defaults = new JsonObject();
        for (Stan st : stany) {
            decls.add(st.decl());
            defaults.addProperty(st.java(), st.values().get(0));
        }
        k.add("states", decls);
        k.add("default_states", defaults);
        if (six) k.addProperty("facing_from", dirStates.contains("minecraft:block_face") && !dirStates.contains("minecraft:facing_direction") ? "face" : "look");
        else if (dirStates.contains("minecraft:cardinal_direction")) k.addProperty("facing_from", "player");

        // every combination of states, capped: 1024 variants is already a silly block
        List<Map<String, String>> combos = new ArrayList<>();
        combos.add(new LinkedHashMap<>());
        for (Stan st : stany) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> c0 : combos) for (String v : st.values()) {
                Map<String, String> c1 = new LinkedHashMap<>(c0);
                c1.put(st.java(), v);
                next.add(c1);
            }
            combos = next;
            if (combos.size() > 1024) { wtf.add("block " + id + " has too many state combinations, permutations dropped"); return; }
        }

        String path = fileName(id);
        JsonObject variants = new JsonObject();
        JsonObject light = new JsonObject();
        Map<String, String> models = new HashMap<>();
        boolean anyLight = false;
        String defaultModel = null;
        for (Map<String, String> combo : combos) {
            JsonObject comp = new JsonObject();
            base.entrySet().forEach(e -> comp.add(e.getKey(), e.getValue()));
            if (perms != null) for (JsonElement pe : perms) {
                JsonObject po = pe.getAsJsonObject();
                if (!warunek(BedrockTlumacz.str(po, "condition", "true"), stany, combo)) continue;
                JsonObject pc = obj(po, "components");
                if (pc != null) pc.entrySet().forEach(e -> comp.add(e.getKey(), e.getValue()));
            }
            Map<String, String> faces = comp.has("minecraft:material_instances") ? blockFaces(id, obj(comp, "minecraft:material_instances")) : baseFaces;
            String faceKey = faces.toString();
            String model = models.get(faceKey);
            if (model == null) {
                model = ns + ":block/" + path + "_" + models.size();
                models.put(faceKey, model);
                Path mf = out.resolve("assets").resolve(ns).resolve("models/block/" + path + "_" + (models.size() - 1) + ".json");
                Files.createDirectories(mf.getParent());
                Files.writeString(mf, GSON_LADNY.toJson(cubeModel(faces)));
            }
            JsonObject variant = new JsonObject();
            variant.addProperty("model", model);
            JsonObject tr = obj(comp, "minecraft:transformation");
            JsonArray rot = tr != null && tr.has("rotation") ? tr.getAsJsonArray("rotation") : null;
            int rx = 0, ry = 0;
            if (rot != null) {
                rx = Math.floorMod(Math.round(rot.get(0).getAsFloat() / 90f) * 90, 360);
                ry = Math.floorMod(Math.round(-rot.get(1).getAsFloat() / 90f) * 90, 360);
            } else if (combo.containsKey("facing")) {
                // no explicit rotation: turn the model like vanilla directional blocks do
                ry = switch (combo.get("facing")) { case "east" -> 90; case "south" -> 180; case "west" -> 270; default -> 0; };
                rx = switch (combo.get("facing")) { case "up" -> 270; case "down" -> 90; default -> 0; };
            }
            if (rx != 0) variant.addProperty("x", rx);
            if (ry != 0) variant.addProperty("y", ry);
            // java lists a block's properties sorted by name, the light lookup builds keys the same way
            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(combo);
            StringBuilder key = new StringBuilder();
            sorted.forEach((kk, vv) -> { if (!key.isEmpty()) key.append(','); key.append(kk).append('=').append(vv); });
            variants.add(key.toString(), variant);
            JsonElement le = comp.get("minecraft:light_emission");
            int lv = le == null ? 0 : le.isJsonPrimitive() ? le.getAsInt() : le.getAsJsonObject().has("emission") ? le.getAsJsonObject().get("emission").getAsInt() : 0;
            light.addProperty(key.toString(), lv);
            if (lv > 0) anyLight = true;
            if (defaultModel == null) defaultModel = model;
        }
        JsonObject bs = new JsonObject();
        bs.add("variants", variants);
        Path bsf = out.resolve("assets").resolve(ns).resolve("blockstates/" + path + ".json");
        Files.createDirectories(bsf.getParent());
        Files.writeString(bsf, GSON_LADNY.toJson(bs));
        // the item wants models/block/<path>, point it at the first variant's look
        JsonObject itemParent = new JsonObject();
        itemParent.addProperty("parent", defaultModel);
        Files.writeString(out.resolve("assets").resolve(ns).resolve("models/block/" + path + ".json"), GSON_LADNY.toJson(itemParent));
        k.addProperty("own_assets", true);
        if (anyLight) k.add("light_by_state", light);
    }

    private JsonObject cubeModel(Map<String, String> faces) {
        JsonObject m = new JsonObject();
        JsonObject t = new JsonObject();
        String all = faces.getOrDefault("all", faces.getOrDefault("side", faces.isEmpty() ? "minecraft:block/stone" : faces.values().iterator().next()));
        if (faces.size() <= 1) {
            m.addProperty("parent", "minecraft:block/cube_all");
            t.addProperty("all", all);
        } else {
            m.addProperty("parent", "minecraft:block/cube");
            String side = faces.getOrDefault("side", all);
            t.addProperty("up", faces.getOrDefault("top", all));
            t.addProperty("down", faces.getOrDefault("bottom", all));
            for (String f : List.of("north", "south", "east", "west")) t.addProperty(f, faces.getOrDefault(f, side));
            t.addProperty("particle", side);
        }
        m.add("textures", t);
        return m;
    }

    // "q.block_state('ns:lit') == true && query.block_state('ns:color') == 'red'"
    private boolean warunek(String cond, List<Stan> stany, Map<String, String> combo) {
        if (cond == null || cond.isBlank() || cond.equals("true") || cond.equals("1")) return true;
        JsonObject q = new JsonObject();
        for (Stan st : stany) {
            String v = combo.get(st.java());
            JsonElement val;
            if (st.bedrock().equals("minecraft:facing_direction")) val = new JsonPrimitive(List.of("down", "up", "north", "south", "west", "east").indexOf(v));
            else if (st.decl().startsWith("bool:")) val = new JsonPrimitive(v.equals("true") ? 1 : 0);
            else if (st.decl().startsWith("int:")) val = new JsonPrimitive(Integer.parseInt(v));
            else val = new JsonPrimitive(originalValue(st, v));
            q.add("block_state('" + st.bedrock() + "')", val);
            q.add("has_block_state('" + st.bedrock() + "')", new JsonPrimitive(1));
        }
        // the facing trait answers under both of its bedrock names
        if (combo.containsKey("facing")) {
            q.add("block_state('minecraft:cardinal_direction')", new JsonPrimitive(combo.get("facing")));
            q.add("block_state('minecraft:block_face')", new JsonPrimitive(combo.get("facing")));
        }
        JsonObject req = new JsonObject();
        req.addProperty("expr", cond);
        req.add("q", q);
        JsonObject res = null;
        try {
            res = com.koper.koper_lib.panama.RustBridge.molangEval(req);
        } catch (Throwable noNative) {
            // unit tests and broken installs: the small evaluator below still covers the usual shape
        }
        if (res != null && res.has("n")) return res.get("n").getAsFloat() != 0f;
        return prostyWarunek(cond, q);
    }

    private static String originalValue(Stan st, String javaValue) {
        return javaValue;
    }

    // a && b && c of "q.block_state('x') == value" / "!=" terms, what 95% of packs write
    static boolean prostyWarunek(String cond, JsonObject q) {
        for (String part : cond.split("&&")) {
            String t = part.trim().replace("query.", "q.");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("q\\.block_state\\s*\\(\\s*'([^']+)'\\s*\\)\\s*(==|!=)\\s*(.+)").matcher(t);
            if (!m.find()) {
                // bare "q.block_state('x')" or "!q.block_state('x')": truthiness
                java.util.regex.Matcher b = java.util.regex.Pattern.compile("^(!?)\\s*q\\.block_state\\s*\\(\\s*'([^']+)'\\s*\\)\\s*$").matcher(t);
                if (!b.find()) return false;
                JsonElement have = q.get("block_state('" + b.group(2) + "')");
                boolean truthy = have != null && !have.getAsString().equals("0") && !have.getAsString().equals("false") && !have.getAsString().isEmpty();
                if (b.group(1).isEmpty() != truthy) return false;
                continue;
            }
            JsonElement have = q.get("block_state('" + m.group(1) + "')");
            if (have == null) return false;
            String want = m.group(3).trim().replace("'", "");
            if (want.equals("true")) want = "1";
            if (want.equals("false")) want = "0";
            String got = have.getAsString();
            boolean eq = got.equals(want) || (have.getAsJsonPrimitive().isNumber() && isNum(want) && Double.parseDouble(want) == have.getAsDouble());
            if (m.group(2).equals("==") != eq) return false;
        }
        return true;
    }

    private static boolean isNum(String s) {
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private Map<String, String> blockFaces(String id, JsonObject mats) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mats != null) {
            for (var e : mats.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                String shortName = str(e.getValue().getAsJsonObject(), "texture", null);
                if (shortName == null) continue;
                List<String> paths = terrain.get(shortName);
                String tex = paths != null ? przenosTeksture(paths.get(0), "block") : null;
                if (tex == null) { wtf.add("block " + id + " texture '" + shortName + "' not in terrain_texture.json"); continue; }
                String face = switch (e.getKey()) {
                    case "*" -> "all";
                    case "up" -> "top";
                    case "down" -> "bottom";
                    default -> e.getKey();
                };
                out.put(face, tex);
            }
            // a material key can point at another one ("north": "side"), those are named instances, fold them
            if (out.containsKey("side") && !out.containsKey("all")) out.put("all", out.get("side"));
            return out;
        }
        JsonObject rb = rpBlocks.get(id);
        if (rb == null) return out;
        JsonElement t = rb.get("textures");
        if (t == null) return out;
        if (t.isJsonPrimitive()) {
            List<String> paths = terrain.get(t.getAsString());
            String tex = paths != null ? przenosTeksture(paths.get(0), "block") : null;
            if (tex != null) out.put("all", tex);
        } else for (var e : t.getAsJsonObject().entrySet()) {
            List<String> paths = terrain.get(e.getValue().getAsString());
            String tex = paths != null ? przenosTeksture(paths.get(0), "block") : null;
            if (tex == null) continue;
            out.put(switch (e.getKey()) { case "up" -> "top"; case "down" -> "bottom"; default -> e.getKey(); }, tex);
        }
        return out;
    }

    private static String geometryId(JsonElement g) {
        if (g == null) return null;
        if (g.isJsonPrimitive()) return g.getAsString();
        return str(g.getAsJsonObject(), "identifier", null);
    }

    // ── entities ─────────────────────────────────────────────────────────────

    private void readClientEntities() throws IOException {
        for (Path f : jsons(paczka.rp().resolve("entity"))) {
            JsonObject ce = obj(czytajObj(f), "minecraft:client_entity");
            JsonObject d = obj(ce, "description");
            String id = str(d, "identifier", null);
            if (id != null) clientEntities.put(id, d);
        }
    }

    private void readGeometry() throws IOException {
        for (Path f : jsonsDeep(paczka.rp().resolve("models"))) {
            JsonObject root = czytajObj(f);
            if (root == null) continue;
            JsonElement list = root.get("minecraft:geometry");
            if (list != null && list.isJsonArray()) {
                for (JsonElement g : list.getAsJsonArray()) {
                    String id = str(obj(g.getAsJsonObject(), "description"), "identifier", null);
                    if (id != null) { geometries.put(id, g.getAsJsonObject()); geometryFiles.put(id, f); }
                }
            }
            // 1.8 format: {"geometry.x": {...}} and "geometry.x:geometry.parent" inheritance names
            for (var e : root.entrySet()) {
                if (!e.getKey().startsWith("geometry.") || !e.getValue().isJsonObject()) continue;
                String id = e.getKey().contains(":") ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
                geometries.put(id, e.getValue().getAsJsonObject());
                geometryFiles.put(id, f);
                if (e.getKey().contains(":")) geometryParents.put(id, e.getKey().substring(e.getKey().indexOf(':') + 1).trim());
            }
        }
    }

    private void readAnimations() throws IOException {
        for (Path f : jsonsDeep(paczka.rp().resolve("animations"))) {
            JsonObject anims = obj(czytajObj(f), "animations");
            if (anims != null) for (var e : anims.entrySet())
                if (e.getValue().isJsonObject()) animations.put(e.getKey(), e.getValue().getAsJsonObject());
        }
    }

    private void entities() throws IOException {
        JsonObject families = new JsonObject();
        JsonObject props = new JsonObject();
        for (Path f : jsons(paczka.bp().resolve("entities"))) {
            JsonObject ent = obj(czytajObj(f), "minecraft:entity");
            if (ent == null) continue;
            JsonObject desc = obj(ent, "description");
            String id = str(desc, "identifier", null);
            if (id == null) continue;
            if (id.startsWith("minecraft:")) {
                // the pack redefines one of java's own mobs (villager news' minecraft:villager_v2). no koper mob
                // for it, java keeps the mob; the behavior runtime lays the pack's properties, groups and events
                // over it (BedrockZachowanie, nakladka)
                Path raw = out.resolve("bedrock_bp/entities/vanilla").resolve(fileName(id) + ".json");
                Files.createDirectories(raw.getParent());
                Files.writeString(raw, czytaj(f).toString());
                JsonObject p = obj(desc, "properties");
                if (p != null) {
                    JsonObject defs = new JsonObject();
                    for (var e : p.entrySet()) {
                        JsonObject pd = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                        if (pd != null && pd.has("default") && pd.get("default").isJsonPrimitive()) defs.add(e.getKey(), pd.get("default"));
                    }
                    props.add(id, defs);
                }
                continue;
            }

            // base components + whatever the spawn event switches on, that is what the mob looks like 99% of the time
            JsonObject c = new JsonObject();
            JsonObject base = obj(ent, "components");
            if (base != null) base.entrySet().forEach(e -> c.add(e.getKey(), e.getValue()));
            JsonObject groups = obj(ent, "component_groups");
            JsonObject spawned = obj(obj(ent, "events"), "minecraft:entity_spawned");
            if (groups != null && spawned != null) for (String g : spawnGroups(spawned)) {
                JsonObject grp = obj(groups, g);
                if (grp != null) grp.entrySet().forEach(e -> c.add(e.getKey(), e.getValue()));
            }

            JsonObject k = new JsonObject();
            k.addProperty("id", id);
            k.addProperty("type", "entity");
            k.addProperty("name", nice(null, id));
            // bedrock: no egg in the creative menu unless the definition says is_spawnable (talisman blocks, ui helpers)
            JsonObject spawnDesc = obj(ent, "description");
            if (spawnDesc != null && spawnDesc.has("is_spawnable") && !spawnDesc.get("is_spawnable").getAsBoolean()) k.addProperty("has_spawn_egg", false);

            JsonObject hp = obj(c, "minecraft:health");
            if (hp != null) k.addProperty("health", num(hp.has("max") ? hp.get("max") : hp.get("value"), "range_max", 20));
            JsonObject mv = obj(c, "minecraft:movement");
            if (mv != null) k.addProperty("movement_speed", mv.has("value") ? numF(mv.get("value")) : 0.25f);
            JsonObject att = obj(c, "minecraft:attack");
            if (att != null && att.has("damage")) {
                JsonElement d = att.get("damage");
                k.addProperty("attack_damage", d.isJsonArray() ? (d.getAsJsonArray().get(0).getAsFloat() + d.getAsJsonArray().get(1).getAsFloat()) / 2f : numF(d));
            }
            JsonObject box = obj(c, "minecraft:collision_box");
            if (box != null) {
                k.addProperty("width", box.has("width") ? box.get("width").getAsFloat() : 0.6f);
                k.addProperty("height", box.has("height") ? box.get("height").getAsFloat() : 1.8f);
            }
            JsonObject fr = obj(c, "minecraft:follow_range");
            if (fr != null) k.addProperty("follow_range", numF(fr.has("value") ? fr.get("value") : fr.get("max")));

            JsonArray fam = new JsonArray();
            JsonObject tf = obj(c, "minecraft:type_family");
            if (tf != null && tf.has("family")) tf.getAsJsonArray("family").forEach(fam::add);
            families.add(id, fam);
            boolean hostile = fam.contains(new JsonPrimitive("monster")) || c.has("minecraft:behavior.nearest_attackable_target");
            k.addProperty("ai_type", hostile ? "HOSTILE" : c.has("minecraft:behavior.melee_attack") ? "NEUTRAL" : "PASSIVE");

            JsonArray ai = new JsonArray();
            if (c.has("minecraft:behavior.float")) ai.add("swim");
            if (c.has("minecraft:behavior.melee_attack") || c.has("minecraft:behavior.delayed_attack")) ai.add("attack_melee");
            if (c.has("minecraft:behavior.nearest_attackable_target") || c.has("minecraft:behavior.nearest_prioritized_attackable_target")) ai.add("target_nearest_player");
            if (c.has("minecraft:behavior.random_stroll") || c.has("minecraft:behavior.random_swim") || c.has("minecraft:behavior.random_fly")) ai.add("wander");
            if (c.has("minecraft:behavior.look_at_player")) ai.add("look_at_player");
            if (c.has("minecraft:behavior.random_look_around")) ai.add("look_around");
            if (!ai.isEmpty()) k.add("entity_ai", ai);

            // a projectile: no walking, no falling, BedrockPocisk flies it
            if (c.has("minecraft:projectile")) { k.addProperty("no_ai", true); k.remove("entity_ai"); }
            if (c.has("minecraft:is_baby")) k.addProperty("baby", true);
            if (c.has("minecraft:persistent")) k.addProperty("persistent", true);
            if (c.has("minecraft:rideable")) k.addProperty("rideable", true);
            if (c.has("minecraft:burns_in_daylight")) k.addProperty("burns_in_daylight", true);
            JsonObject tame = obj(c, "minecraft:tameable");
            if (tame != null && tame.has("tame_items")) {
                JsonElement ti = tame.get("tame_items");
                k.addProperty("taming_item", ti.isJsonArray() ? ti.getAsJsonArray().get(0).getAsString() : ti.getAsString());
            }
            JsonObject loot = obj(c, "minecraft:loot");
            if (loot != null && loot.has("table")) lootTable(loot.get("table").getAsString(), "loot_table/entities/" + fileName(id), "minecraft:entity");

            JsonObject p = obj(desc, "properties");
            if (p != null) {
                JsonObject defs = new JsonObject();
                for (var e : p.entrySet()) {
                    JsonObject pd = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                    if (pd != null && pd.has("default") && pd.get("default").isJsonPrimitive()) defs.add(e.getKey(), pd.get("default"));
                }
                props.add(id, defs);
            }

            // bedrock draws an entity only through its client entity. none = an invisible helper (rlcraft keeps
            // trinkets in one at the player's feet), not a steve to stand inside the player
            if (!clientEntities.containsKey(id)) k.addProperty("invisible", true);
            clientSide(id, k);
            // the whole definition goes along too, component groups and events run at run time
            Path raw = out.resolve("bedrock_bp/entities").resolve(fileName(id) + ".json");
            Files.createDirectories(raw.getParent());
            Files.writeString(raw, czytaj(f).toString());

            Files.createDirectories(out.resolve("entities"));
            Files.writeString(out.resolve("entities").resolve(fileName(id) + ".json"), GSON_LADNY.toJson(k));
        }
        sidecar.add("families", families);
        sidecar.add("properties", props);
    }

    private static List<String> spawnGroups(JsonObject ev) {
        List<String> out = new ArrayList<>();
        JsonObject add = obj(ev, "add");
        if (add != null && add.has("component_groups")) add.getAsJsonArray("component_groups").forEach(g -> out.add(g.getAsString()));
        // randomize/sequence pick one, grab the first so the mob gets *something*
        for (String k : List.of("randomize", "sequence")) {
            if (!ev.has(k) || !ev.get(k).isJsonArray() || ev.getAsJsonArray(k).isEmpty()) continue;
            JsonObject first = ev.getAsJsonArray(k).get(0).getAsJsonObject();
            out.addAll(spawnGroups(first));
        }
        return out;
    }

    private void clientSide(String id, JsonObject k) {
        JsonObject d = clientEntities.get(id);
        if (d == null) return;
        JsonObject textures = obj(d, "textures");
        String texPath = textures != null && textures.has("default") ? textures.get("default").getAsString()
            : textures != null && !textures.entrySet().isEmpty() ? textures.entrySet().iterator().next().getValue().getAsString() : null;
        String tex = przenosTeksture(texPath, "entity");
        if (tex != null) k.addProperty("texture", ns + ":textures/entity/" + tex.substring(tex.lastIndexOf('/') + 1) + ".png");

        JsonObject geo = obj(d, "geometry");
        String geoId = geo != null && geo.has("default") ? geo.get("default").getAsString()
            : geo != null && !geo.entrySet().isEmpty() ? geo.entrySet().iterator().next().getValue().getAsString() : null;

        List<String> clips = new ArrayList<>();
        JsonObject anims = obj(d, "animations");
        if (anims != null) for (var e : anims.entrySet()) {
            String full = e.getValue().getAsString();
            if (!animations.containsKey(full)) continue; // controllers and vanilla anims, nothing to bake
            clips.add(full);
            String shortName = e.getKey().toLowerCase(Locale.ROOT);
            if (shortName.contains("walk") || shortName.equals("move") || shortName.contains("run")) k.addProperty("run_animation", full);
            else if (shortName.contains("idle")) k.addProperty("idle_animation", full);
            else if (shortName.contains("attack")) k.addProperty("attack_animation", full);
            else if (shortName.contains("death") || shortName.contains("die")) k.addProperty("death_animation", full);
        }
        if (geoId != null) {
            if (geometries.containsKey(geoId)) {
                k.addProperty("model", modelName(geoId));
                wantModel(geoId, clips, tex);
            } else {
                wtf.add("entity " + id + " geometry " + geoId + " not found, it will render as a humanoid");
            }
        }

        JsonObject egg = obj(d, "spawn_egg");
        if (egg != null) {
            if (egg.has("base_color")) k.addProperty("spawn_egg_primary", egg.get("base_color").getAsString());
            if (egg.has("overlay_color")) k.addProperty("spawn_egg_secondary", egg.get("overlay_color").getAsString());
            if (egg.has("texture")) {
                List<String> paths = itemIcons.get(egg.get("texture").getAsString());
                String et = paths != null ? przenosTeksture(paths.get(0), "item") : null;
                if (et != null) k.addProperty("spawn_egg_texture", et);
            }
        }
    }

    // ── models: packed .kodel, or the geo.json kept for kodel to convert on load ─

    private final Map<String, List<String>> wantedModels = new LinkedHashMap<>();
    private final Map<String, String> wantedTextures = new HashMap<>();

    private void wantModel(String geoId, List<String> clips, String texture) {
        wantedModels.computeIfAbsent(geoId, g -> new ArrayList<>()).addAll(clips);
        if (texture != null) wantedTextures.putIfAbsent(geoId, texture);
    }

    // 1.8 "geometry.child:geometry.parent": the parent's bones, a child bone of the same name replaces
    // the parent's whole, new ones go at the end, the child's texture size when it gives one
    private JsonObject geometry(String id, int depth) {
        JsonObject g = geometries.get(id);
        String parent = geometryParents.get(id);
        if (g == null || parent == null || depth > 8) return g;
        JsonObject p = geometry(parent, depth + 1);
        return p == null ? g : dziedzicz(p, g);
    }

    static JsonObject dziedzicz(JsonObject parent, JsonObject child) {
        JsonObject out = parent.deepCopy();
        for (var e : child.entrySet()) if (!e.getKey().equals("bones")) out.add(e.getKey(), e.getValue());
        JsonArray bones = new JsonArray();
        JsonArray mine = child.has("bones") && child.get("bones").isJsonArray() ? child.getAsJsonArray("bones") : new JsonArray();
        java.util.Set<String> used = new java.util.HashSet<>();
        if (parent.has("bones") && parent.get("bones").isJsonArray()) for (JsonElement b : parent.getAsJsonArray("bones")) {
            JsonElement swap = b;
            String n = b.isJsonObject() ? str(b.getAsJsonObject(), "name", null) : null;
            if (n != null) for (JsonElement c : mine) {
                if (c.isJsonObject() && n.equalsIgnoreCase(str(c.getAsJsonObject(), "name", ""))) { swap = c; used.add(n.toLowerCase(Locale.ROOT)); }
            }
            bones.add(swap);
        }
        for (JsonElement c : mine) {
            String n = c.isJsonObject() ? str(c.getAsJsonObject(), "name", "").toLowerCase(Locale.ROOT) : "";
            if (!used.contains(n)) bones.add(c);
        }
        out.add("bones", bones);
        return out;
    }

    static String modelName(String geoId) {
        String n = geoId.startsWith("geometry.") ? geoId.substring("geometry.".length()) : geoId;
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private void leftoverModels() throws IOException {
        for (var e : wantedModels.entrySet()) {
            String geoId = e.getKey();
            JsonObject g = geometry(geoId, 0);
            if (g == null) { wtf.add("geometry " + geoId + " missing"); continue; }
            if (geometryParents.containsKey(geoId) && !geometries.containsKey(geometryParents.get(geoId)))
                wtf.add("geometry " + geoId + " inherits " + geometryParents.get(geoId) + " which is not in the pack, drawn without it");
            String name = modelName(geoId);

            JsonObject geoFile = new JsonObject();
            geoFile.addProperty("format_version", "1.12.0");
            JsonArray arr = new JsonArray();
            JsonObject copy = g.deepCopy();
            if (!copy.has("description")) {
                // 1.8 layout -> 1.12 layout, the loaders only speak the array form
                JsonObject desc = new JsonObject();
                desc.addProperty("identifier", geoId);
                desc.addProperty("texture_width", copy.has("texturewidth") ? copy.get("texturewidth").getAsInt() : 64);
                desc.addProperty("texture_height", copy.has("textureheight") ? copy.get("textureheight").getAsInt() : 64);
                copy.add("description", desc);
            }
            arr.add(copy);
            geoFile.add("minecraft:geometry", arr);

            JsonObject animFile = new JsonObject();
            animFile.addProperty("format_version", "1.8.0");
            JsonObject clips = new JsonObject();
            for (String clip : e.getValue()) if (animations.containsKey(clip)) clips.add(clip, animations.get(clip));
            animFile.add("animations", clips);

            byte[] png = null;
            String tex = wantedTextures.get(geoId);
            if (tex != null) {
                Path p = out.resolve("assets").resolve(ns).resolve("textures").resolve(tex.substring(tex.indexOf(':') + 1) + ".png");
                if (Files.isRegularFile(p)) png = Files.readAllBytes(p);
            }

            byte[] kodel = com.koper.koper_lib.api.core.KodelBedrockBridge.convert(geoFile, animFile, png, name);
            if (kodel != null) {
                Path dst = out.resolve("kodel").resolve(name + ".kodel");
                Files.createDirectories(dst.getParent());
                Files.write(dst, kodel);
                copied.incrementAndGet();
            } else {
                // no kodel module yet: keep models/<name>.geo.json and animations/<name>.animation.json,
                // which kodel converts on load once it is installed
                Path geoDst = out.resolve("models").resolve(name + ".geo.json");
                Files.createDirectories(geoDst.getParent());
                Files.writeString(geoDst, GSON_LADNY.toJson(geoFile));
                if (!clips.entrySet().isEmpty()) {
                    Path animDst = out.resolve("animations").resolve(name + ".animation.json");
                    Files.createDirectories(animDst.getParent());
                    Files.writeString(animDst, GSON_LADNY.toJson(animFile));
                }
                if (com.koper.koper_lib.api.core.KodelBedrockBridge.installed())
                    wtf.add("kodel could not convert " + geoId + ", kept the geo.json");
            }
        }
    }

    // ── resource pack runtime: what kodel's bedrock actors read at render time ─

    private static final List<String> RP_RUNTIME = List.of("entity", "animations", "animation_controllers",
        "render_controllers", "models", "attachables", "particles", "materials");

    private void rpRuntime() throws IOException {
        Path rp = paczka.rp();
        Path dst = out.resolve("bedrock_rp");
        List<Path> runtime = new ArrayList<>();
        for (String dir : RP_RUNTIME) {
            Path src = rp.resolve(dir);
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".material")).forEach(runtime::add);
            }
        }
        BedrockTaczka.kazdy(runtime, f -> {
            Path to = dst.resolve(rp.relativize(f).toString());
            katalogi.dla(to);
            BedrockTaczka.przenies(f, to);
        });
        int json = runtime.size();
        Path defs = rp.resolve("sounds/sound_definitions.json");
        if (Files.isRegularFile(defs)) kopiuj(defs, dst.resolve("sounds/sound_definitions.json"));

        // every texture, png or tga, under assets/<ns>/textures/bedrock/<path below textures/>
        Path tex = rp.resolve("textures");
        var textures = new java.util.concurrent.atomic.AtomicInteger();
        var overrides = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, JsonObject> flipbooki = flipbooki(rp);
        if (Files.isDirectory(tex)) {
            List<Path> all;
            try (Stream<Path> s = Files.walk(tex)) {
                all = s.filter(Files::isRegularFile).filter(f -> {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".png") || n.endsWith(".tga");
                }).toList();
            }
            // x.png and x.tga side by side land on the same file. png wins, same as przenosTeksture,
            // and it has to be decided up front now that the copies race each other
            java.util.Set<String> pngs = new java.util.HashSet<>();
            for (Path f : all) {
                String n = f.toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".png")) pngs.add(n.substring(0, n.length() - 4));
            }
            all = all.stream().filter(f -> {
                String n = f.toString().toLowerCase(Locale.ROOT);
                return !n.endsWith(".tga") || !pngs.contains(n.substring(0, n.length() - 4));
            }).toList();
            Path texOut = out.resolve("assets").resolve(ns).resolve("textures/bedrock");
            BedrockTaczka.kazdy(all, f -> {
                boolean tga = f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tga");
                String rel = tex.relativize(f).toString().replace('\\', '/');
                rel = rel.substring(0, rel.lastIndexOf('.')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                Path to = texOut.resolve(rel + ".png");
                katalogi.dla(to);
                String java = vanillaTwin(rel);
                byte[] data = null;
                if (tga) {
                    data = BedrockSkladacz.tgaToPng(Files.readAllBytes(f));
                    if (data == null) { wtf.add("tga " + rel + " did not decode"); return; }
                    Files.write(to, data);
                } else if (java == null) {
                    // nothing to check against: the bytes never pass through java, usually not even copied
                    BedrockTaczka.przenies(f, to);
                } else {
                    data = Files.readAllBytes(f);
                    Files.write(to, data);
                }
                textures.incrementAndGet();
                String meta = java == null ? null : podmiana(rel, java, data, flipbooki);
                if (meta != null) {
                    Path v = out.resolve("assets/minecraft/textures").resolve(java + ".png");
                    katalogi.dla(v);
                    Files.write(v, data);
                    if (!meta.isEmpty()) Files.writeString(v.resolveSibling(java.substring(java.lastIndexOf('/') + 1) + ".png.mcmeta"), meta);
                    overrides.incrementAndGet();
                }
            });
        }
        sidecar.addProperty("rp_runtime", json > 0);
        KoperLib.LOGGER.info("[Bedrock] {}: {} runtime files, {} textures, {} replace vanilla ones", paczka.name(), json, textures.get(), overrides.get());
    }

    // textures/flipbook_textures.json: bedrock's animated strips, keyed by path below textures/
    private static Map<String, JsonObject> flipbooki(Path rp) {
        Map<String, JsonObject> out = new HashMap<>();
        try {
            Path f = rp.resolve("textures/flipbook_textures.json");
            if (!Files.isRegularFile(f)) return out;
            JsonReader r = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8).replace("\uFEFF", "")));
            r.setStrictness(Strictness.LENIENT);
            JsonElement all = JsonParser.parseReader(r);
            if (!all.isJsonArray()) return out;
            for (JsonElement e : all.getAsJsonArray()) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("flipbook_texture")) continue;
                String k = e.getAsJsonObject().get("flipbook_texture").getAsString().toLowerCase(Locale.ROOT);
                if (k.startsWith("textures/")) k = k.substring("textures/".length());
                out.put(k.replaceAll("[^a-z0-9_./-]", "_"), e.getAsJsonObject());
            }
        } catch (Exception ignored) {}
        return out;
    }

    // may this bedrock texture replace java's? null = no, "" = yes, else yes + this mcmeta.
    // bedrock and java disagree on a lot for the SAME name: strips that are animated through
    // flipbook_textures.json instead of mcmeta, alpha used as a tint mask on opaque blocks
    // (grass side), different layouts. anything that doesnt line up keeps the java one
    static String podmiana(String rel, String java, byte[] data, Map<String, JsonObject> flipbooki) {
        byte[] jp = vanillaBytes(java + ".png");
        if (jp == null) return null;
        byte[] jm = vanillaBytes(java + ".png.mcmeta");
        String jmeta = jm == null ? null : new String(jm, StandardCharsets.UTF_8);
        int[] jd = BedrockPikselarz.rozmiar(jp), bd = BedrockPikselarz.rozmiar(data);
        if (jd == null || bd == null) return null;
        if (BedrockPikselarz.przezroczysty(jp) == 0 && BedrockPikselarz.przezroczysty(data) == 1) return null;
        JsonObject flip = flipbooki.get(rel);
        if (flip != null && bd[1] > bd[0] && bd[1] % bd[0] == 0) {
            JsonObject anim = new JsonObject();
            anim.addProperty("frametime", flip.has("ticks_per_frame") ? Math.max(1, flip.get("ticks_per_frame").getAsInt()) : 1);
            if (flip.has("blend_frames") && flip.get("blend_frames").getAsBoolean()) anim.addProperty("interpolate", true);
            if (flip.has("frames") && flip.get("frames").isJsonArray()) {
                JsonArray fr = new JsonArray();
                int max = bd[1] / bd[0];
                for (JsonElement f : flip.getAsJsonArray("frames")) if (f.isJsonPrimitive() && f.getAsInt() < max) fr.add(f.getAsInt());
                if (!fr.isEmpty()) anim.add("frames", fr);
            }
            JsonObject m = new JsonObject();
            m.add("animation", anim);
            return m.toString();
        }
        // same aspect or its a different picture layout (strip vs single, atlas vs tile)
        if ((long) bd[0] * jd[1] != (long) bd[1] * jd[0]) return null;
        return jmeta != null ? jmeta : "";
    }

    // bedrock "blocks/log_oak" -> java "block/oak_log" when the game really has that texture.
    // bedrock kept its legacy names, these rules undo the systematic ones
    static String vanillaTwin(String rel) {
        String kind;
        String rest;
        if (rel.startsWith("blocks/")) { kind = "block/"; rest = rel.substring(7); }
        else if (rel.startsWith("items/")) { kind = "item/"; rest = rel.substring(6); }
        else return null;
        String name = rest.substring(rest.lastIndexOf('/') + 1);
        for (String c : javaNames(name)) {
            if (vanillaHas(kind + c)) return kind + c;
        }
        return null;
    }

    private static final java.util.Map<String, String> COLORS = java.util.Map.of(
        "silver", "light_gray", "lightblue", "light_blue", "light_blue", "light_blue");

    static List<String> javaNames(String n) {
        List<String> out = new ArrayList<>();
        out.add(n);
        String[] parts = n.split("_");
        String color = parts.length > 0 ? COLORS.getOrDefault(parts[parts.length - 1], parts[parts.length - 1]) : n;
        java.util.function.Function<String, String> tail = prefix -> COLORS.getOrDefault(n.substring(prefix.length()), n.substring(prefix.length()));
        record Rule(String prefix, String pattern) {}
        for (Rule r : List.of(
                new Rule("log_", "%s_log"), new Rule("planks_", "%s_planks"), new Rule("leaves_", "%s_leaves"),
                new Rule("sapling_", "%s_sapling"), new Rule("wool_colored_", "%s_wool"), new Rule("glass_", "%s_stained_glass"),
                new Rule("hardened_clay_stained_", "%s_terracotta"), new Rule("concrete_powder_", "%s_concrete_powder"),
                new Rule("concrete_", "%s_concrete"), new Rule("glazed_terracotta_", "%s_glazed_terracotta"),
                new Rule("stone_", "%s"), new Rule("door_", "%s_door"), new Rule("flower_", "%s"),
                new Rule("mushroom_block_skin_", "%s_mushroom_block"), new Rule("shulker_top_", "%s_shulker_box"),
                new Rule("bed_", "%s_bed"), new Rule("dye_powder_", "%s_dye"), new Rule("sword_", "%s_sword"),
                new Rule("pickaxe_", "%s_pickaxe"), new Rule("axe_", "%s_axe"), new Rule("shovel_", "%s_shovel"),
                new Rule("hoe_", "%s_hoe"), new Rule("helmet_", "%s_helmet"), new Rule("chestplate_", "%s_chestplate"),
                new Rule("leggings_", "%s_leggings"), new Rule("boots_", "%s_boots"))) {
            if (!n.startsWith(r.prefix())) continue;
            String t = tail.apply(r.prefix());
            // "log_oak_top" keeps its suffix: oak_log_top
            if (t.endsWith("_top") || t.endsWith("_side") || t.endsWith("_bottom") || t.endsWith("_upper") || t.endsWith("_lower")) {
                String suf = t.substring(t.lastIndexOf('_'));
                out.add(String.format(r.pattern(), t.substring(0, t.lastIndexOf('_'))) + suf);
            }
            out.add(String.format(r.pattern(), t));
        }
        if (parts.length == 2) out.add(parts[1] + "_" + parts[0]);
        if (!color.equals(parts[parts.length - 1])) out.add(n.substring(0, n.lastIndexOf('_') + 1) + color);
        switch (n) {
            case "grass_top" -> out.add("grass_block_top");
            case "grass_side_carried", "grass_side" -> out.add("grass_block_side");
            case "grass_side_snowed" -> out.add("grass_block_snow");
            case "dirt_podzol_top" -> out.add("podzol_top");
            case "dirt_podzol_side" -> out.add("podzol_side");
            case "stonebrick" -> out.add("stone_bricks");
            case "brick" -> out.add("bricks");
            case "sandstone_normal" -> out.add("sandstone");
            case "reeds" -> out.add("sugar_cane");
            case "tallgrass" -> out.add("short_grass");
            case "apple_golden" -> out.add("golden_apple");
            case "fish_cooked_cod", "fish_cod_cooked" -> out.add("cooked_cod");
            default -> {}
        }
        return out;
    }

    private static final java.util.Map<String, Boolean> HAS = new java.util.concurrent.ConcurrentHashMap<>();

    // the client jar is on the classpath, a dedicated server has no textures and never overrides
    private static boolean vanillaHas(String path) {
        Szafa sz = szafa();
        if (sz != null) return sz.pliki().containsKey(path + ".png");
        return HAS.computeIfAbsent(path, p -> BedrockTlumacz.class.getResource("/assets/minecraft/textures/" + p + ".png") != null);
    }

    private static byte[] vanillaBytes(String rel) {
        Szafa sz = szafa();
        try {
            if (sz != null) {
                Path f = sz.pliki().get(rel);
                return f == null ? null : Files.readAllBytes(f);
            }
            try (var in = BedrockTlumacz.class.getResourceAsStream("/assets/minecraft/textures/" + rel)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // java's textures/block and textures/item listed once. a big pack asked the classloader ~5 names
    // per texture, 50k lookups through every mod jar knot has, most of them misses.
    // path below textures/ -> the file, first classpath root wins like getResource
    record Szafa(Map<String, Path> pliki) {}

    private static volatile Szafa szafa;
    private static volatile boolean szafaPusta;

    static Szafa szafa() {
        Szafa sz = szafa;
        if (sz != null || szafaPusta) return sz;
        synchronized (BedrockTlumacz.class) {
            if (szafa != null || szafaPusta) return szafa;
            try {
                Map<String, Path> pliki = new HashMap<>();
                ClassLoader cl = BedrockTlumacz.class.getClassLoader();
                var urls = cl.getResources("assets/minecraft/textures/block/stone.png");
                while (urls.hasMoreElements()) {
                    java.net.URI uri = urls.nextElement().toURI();
                    Path stone;
                    try {
                        stone = Path.of(uri);
                    } catch (java.nio.file.FileSystemNotFoundException notOpenYet) {
                        stone = java.nio.file.FileSystems.newFileSystem(uri, Map.of()).provider().getPath(uri);
                    }
                    Path root = stone.getParent().getParent();
                    for (String dir : List.of("block", "item")) {
                        Path d = root.resolve(dir);
                        if (!Files.isDirectory(d)) continue;
                        try (Stream<Path> s = Files.walk(d)) {
                            s.filter(Files::isRegularFile).forEach(f -> pliki.putIfAbsent(root.relativize(f).toString().replace('\\', '/'), f));
                        }
                    }
                }
                // the game jar alone has thousands. a handful = the listing did not work here, ask the classloader
                if (pliki.size() < 100) { szafaPusta = true; return null; }
                szafa = new Szafa(Map.copyOf(pliki));
            } catch (Throwable odd) {
                KoperLib.LOGGER.debug("[Bedrock] vanilla texture listing failed, asking the classloader one by one: {}", odd.toString());
                szafaPusta = true;
            }
            return szafa;
        }
    }

    // ── sounds ───────────────────────────────────────────────────────────────

    private void copySounds() throws IOException {
        Path defs = paczka.rp().resolve("sounds/sound_definitions.json");
        JsonObject root = czytajObj(defs);
        if (root == null) return;
        JsonObject list = obj(root, "sound_definitions");
        if (list == null) list = root; // pre 1.14 files had no wrapper
        JsonObject javaSounds = new JsonObject();
        for (var e : list.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            String event = e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            JsonArray files = new JsonArray();
            JsonElement sounds = e.getValue().getAsJsonObject().get("sounds");
            if (sounds == null || !sounds.isJsonArray()) continue;
            for (JsonElement s : sounds.getAsJsonArray()) {
                String path = s.isJsonPrimitive() ? s.getAsString() : str(s.getAsJsonObject(), "name", null);
                if (path == null) continue;
                Path src = paczka.rp().resolve(path + ".ogg");
                if (!Files.isRegularFile(src)) {
                    // java plays ogg vorbis only. a wav or fsb stays silent, say which so it can be re-saved
                    for (String ext : List.of(".wav", ".fsb", ".mp3"))
                        if (Files.isRegularFile(paczka.rp().resolve(path + ext))) { wtf.add("sound " + path + ext + " is not ogg, java cannot play it (save it as .ogg)"); break; }
                    continue;
                }
                String rel = path.startsWith("sounds/") ? path.substring("sounds/".length()) : path;
                rel = rel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                kopiuj(src, out.resolve("assets").resolve(ns).resolve("sounds").resolve(rel + ".ogg"));
                JsonObject one = new JsonObject();
                one.addProperty("name", ns + ":" + rel);
                if (s.isJsonObject() && s.getAsJsonObject().has("volume")) one.add("volume", s.getAsJsonObject().get("volume"));
                if (s.isJsonObject() && s.getAsJsonObject().has("pitch")) one.add("pitch", s.getAsJsonObject().get("pitch"));
                files.add(one);
            }
            if (files.isEmpty()) continue;
            JsonObject def = new JsonObject();
            def.add("sounds", files);
            javaSounds.add(event, def);
        }
        packowe.addAll(javaSounds.keySet());
        if (javaSounds.entrySet().isEmpty()) return;
        Path f = out.resolve("assets").resolve(ns).resolve("sounds.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON_LADNY.toJson(javaSounds));
        sidecar.addProperty("sound_namespace", ns);
        vanillaSounds(javaSounds);
    }

    // sound events the pack brings files for (copySounds fills it)
    private final java.util.Set<String> packowe = new java.util.HashSet<>();

    // the pack's own mobs' voices: sounds.json entity_sounds says mcl:black_bear's "ambient" is mob.polarbear.idle
    // and "hurt" is mob.polarbear.hurt at volume 1, pitch [0.8, 1.2]. each becomes the java sound event to play,
    // for KoperMobEntity: the pack's own event, bedrock's vanilla one under its java name, or java's same name.
    // before this every addon mob was silent. "defaults" covers every entity the list does not name
    private void glosy() throws IOException {
        JsonObject es = obj(czytajObj(paczka.rp().resolve("sounds.json")), "entity_sounds");
        if (es == null) return;
        java.util.Set<String> javaEvents = javaSoundEvents();
        JsonObject voices = new JsonObject();
        JsonObject domyslne = glos(obj(es, "defaults"), javaEvents);
        JsonObject ents = obj(es, "entities");
        if (ents != null) for (var e : ents.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            if (id.startsWith("minecraft:")) continue; // java's own mobs go through vanillaSounds
            JsonObject g = glos(e.getValue().getAsJsonObject(), javaEvents);
            if (domyslne != null) for (var d : domyslne.entrySet()) if (!g.has(d.getKey())) g.add(d.getKey(), d.getValue());
            if (!g.entrySet().isEmpty()) voices.add(id, g);
        }
        if (domyslne != null && !domyslne.entrySet().isEmpty()) voices.add("*", domyslne);
        if (!voices.entrySet().isEmpty()) sidecar.add("voices", voices);
    }

    // one entity_sounds entry -> {"ambient": {"s": "<java event>", "v": 1, "p": [0.8, 1.2]}, ...}
    private JsonObject glos(JsonObject ent, java.util.Set<String> javaEvents) {
        if (ent == null) return null;
        JsonObject out = new JsonObject();
        JsonObject evs = obj(ent, "events");
        if (evs == null) return out;
        for (var ev : evs.entrySet()) {
            JsonElement v = ev.getValue();
            String snd = v.isJsonPrimitive() ? v.getAsString() : v.isJsonObject() ? str(v.getAsJsonObject(), "sound", "") : "";
            if (snd.isEmpty()) continue;
            String low = snd.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
            String java;
            if (packowe.contains(low)) java = ns + ":" + low;
            else {
                String j = com.koper.koper_lib.api.core.BedrockNazwy.dzwiekDoJavy(low);
                if (j != null) java = "minecraft:" + j;
                else if (javaEvents.isEmpty() || javaEvents.contains(low)) java = "minecraft:" + low;
                else { wtf.add("entity sound " + snd + " is neither in the pack nor a sound java knows"); continue; }
            }
            JsonObject one = new JsonObject();
            one.addProperty("s", java);
            JsonElement vol = v.isJsonObject() && v.getAsJsonObject().has("volume") ? v.getAsJsonObject().get("volume") : ent.get("volume");
            JsonElement pit = v.isJsonObject() && v.getAsJsonObject().has("pitch") ? v.getAsJsonObject().get("pitch") : ent.get("pitch");
            if (vol != null) one.add("v", vol);
            if (pit != null) one.add("p", pit);
            out.add(ev.getKey(), one);
        }
        return out;
    }

    // the pack gives java's own mobs new voices: sounds.json says villager_v2's "ambient" is mob.villager.idle,
    // the pack's sound_definitions has its own files for mob.villager.idle. java knows that sound as
    // entity.villager.ambient. every such pair becomes a replace in assets/minecraft/sounds.json, only
    // when the pack really brings files and java really has the event (read from the game jar)
    private void vanillaSounds(JsonObject packSounds) throws IOException {
        java.util.Set<String> javaEvents = javaSoundEvents();
        if (javaEvents.isEmpty()) return;
        Map<String, String> replaces = new LinkedHashMap<>(); // java event -> pack event
        JsonObject ents = obj(obj(czytajObj(paczka.rp().resolve("sounds.json")), "entity_sounds"), "entities");
        if (ents != null) for (var e : ents.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            if (!id.startsWith("minecraft:")) continue;
            String java = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id).substring(10);
            JsonObject evs = obj(e.getValue().getAsJsonObject(), "events");
            if (evs != null) for (var ev : evs.entrySet()) {
                String snd = ev.getValue().isJsonPrimitive() ? ev.getValue().getAsString() : str(obj(evs, ev.getKey()), "sound", "");
                String packEvent = snd.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
                if (snd.isEmpty() || !packSounds.has(packEvent)) continue;
                String je = javaEntityEvent(java, ev.getKey(), javaEvents);
                if (je != null) replaces.putIfAbsent(je, packEvent);
            }
        }
        // no sounds.json, just new files for bedrock's own events: mob.villager.idle and friends by their names
        for (String packEvent : packSounds.keySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("mob\\.([a-z0-9_]+)\\.([a-z0-9_.]+)").matcher(packEvent);
            if (!m.matches()) continue;
            String java = com.koper.koper_lib.api.core.BedrockNazwy.doJavy("minecraft:" + m.group(1)).substring(10);
            String ev = switch (m.group(2)) {
                case "idle", "say", "ambient" -> "ambient";
                case "hit", "hurt" -> "hurt";
                default -> m.group(2);
            };
            String je = javaEntityEvent(java, ev, javaEvents);
            if (je != null) replaces.putIfAbsent(je, packEvent);
        }
        if (replaces.isEmpty()) return;
        JsonObject over = new JsonObject();
        replaces.forEach((je, pe) -> {
            JsonObject d = new JsonObject();
            d.addProperty("replace", true);
            d.add("sounds", packSounds.getAsJsonObject(pe).get("sounds").deepCopy());
            over.add(je, d);
        });
        Path f = out.resolve("assets/minecraft/sounds.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON_LADNY.toJson(over));
        KoperLib.LOGGER.info("[Bedrock] {}: {} of java's own sounds replaced", paczka.name(), replaces.size());
    }

    // bedrock entity sound event -> java's name for it, when java has one
    static String javaEntityEvent(String javaEntity, String bedrockEvent, java.util.Set<String> javaEvents) {
        String ev = switch (bedrockEvent) {
            case "haggle" -> "trade";
            case "haggle.yes" -> "yes";
            case "haggle.no" -> "no";
            case "mad" -> "angry";
            case "ambient.in.water" -> "ambient_water";
            case "hurt.in.water" -> "hurt_water";
            case "death.in.water" -> "death_water";
            default -> bedrockEvent.replace('.', '_');
        };
        String name = "entity." + javaEntity + "." + ev;
        return javaEvents.contains(name) ? name : null;
    }

    private static volatile java.util.Set<String> JAVA_SOUNDS;

    // every sound event the game jar's assets/minecraft/sounds.json names. a server has none, nothing to replace there
    static java.util.Set<String> javaSoundEvents() {
        java.util.Set<String> got = JAVA_SOUNDS;
        if (got != null) return got;
        java.util.Set<String> out = new java.util.HashSet<>();
        try (var in = BedrockTlumacz.class.getResourceAsStream("/assets/minecraft/sounds.json")) {
            if (in != null) {
                JsonElement all = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (all.isJsonObject()) out.addAll(all.getAsJsonObject().keySet());
            }
        } catch (Exception ignored) {}
        return JAVA_SOUNDS = java.util.Set.copyOf(out);
    }

    // ── recipes / loot / functions → embedded datapack ───────────────────────

    private Path data() {
        return out.resolve("datapacks").resolve("bedrock").resolve("data").resolve(ns);
    }

    private void recipes() throws IOException {
        for (Path f : jsonsDeep(paczka.bp().resolve("recipes"))) {
            JsonObject root = czytajObj(f);
            if (root == null) continue;
            for (var e : root.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject r = e.getValue().getAsJsonObject();
                String rid = str(obj(r, "description"), "identifier", f.getFileName().toString().replace(".json", ""));
                JsonObject j = switch (e.getKey()) {
                    case "minecraft:recipe_shaped" -> shaped(r);
                    case "minecraft:recipe_shapeless" -> shapeless(r);
                    case "minecraft:recipe_furnace" -> furnace(r);
                    case "minecraft:recipe_smithing_transform" -> smithing(r);
                    default -> null;
                };
                if (j == null) {
                    if (e.getKey().startsWith("minecraft:recipe")) wtf.add("recipe " + rid + " (" + e.getKey() + ") not converted");
                    continue;
                }
                Path dst = data().resolve("recipe").resolve(fileName(rid) + ".json");
                Files.createDirectories(dst.getParent());
                Files.writeString(dst, GSON_LADNY.toJson(j));
            }
        }
    }

    private static JsonElement ingredient(JsonElement in) {
        if (in == null) return null;
        if (in.isJsonPrimitive()) return new JsonPrimitive(fixItem(in.getAsString()));
        if (in.isJsonArray()) {
            JsonArray a = new JsonArray();
            in.getAsJsonArray().forEach(x -> { JsonElement y = ingredient(x); if (y != null) a.add(y); });
            return a.size() == 1 ? a.get(0) : a;
        }
        JsonObject o = in.getAsJsonObject();
        if (o.has("tag")) {
            // a tag is a name, not an item: never through the item table (planks would become oak_planks)
            String t = o.get("tag").getAsString();
            return new JsonPrimitive("#" + (t.contains(":") ? t : "minecraft:" + t));
        }
        if (o.has("item")) return new JsonPrimitive(fixItem(o.get("item").getAsString()));
        return null;
    }

    private static JsonObject result(JsonElement res) {
        if (res != null && res.isJsonArray()) res = res.getAsJsonArray().get(0);
        JsonObject out = new JsonObject();
        if (res == null) return out;
        if (res.isJsonPrimitive()) { out.addProperty("id", fixItem(res.getAsString())); return out; }
        JsonObject o = res.getAsJsonObject();
        out.addProperty("id", fixItem(str(o, "item", "minecraft:air")));
        if (o.has("count")) out.addProperty("count", o.get("count").getAsInt());
        return out;
    }

    // "minecraft:planks:2", "muttonRaw", "record_cat": bedrock's own item names -> java's (BedrockNazwy has the table)
    private static String fixItem(String id) {
        return com.koper.koper_lib.api.core.BedrockNazwy.przedmiotDoJavy(id);
    }

    private static JsonObject shaped(JsonObject r) {
        JsonObject j = new JsonObject();
        j.addProperty("type", "minecraft:crafting_shaped");
        // bedrock takes ragged rows and keys nobody uses, java refuses the whole recipe (and on 26.3 a
        // recipe that fails to load stops the WORLD from loading). rows padded, unused keys dropped
        List<String> rows = new ArrayList<>();
        if (r.get("pattern") instanceof JsonArray pa) for (JsonElement e : pa) rows.add(e.getAsString());
        int w = rows.stream().mapToInt(String::length).max().orElse(0);
        JsonArray pattern = new JsonArray();
        java.util.Set<Character> used = new java.util.HashSet<>();
        for (String row : rows) {
            String padded = row + " ".repeat(w - row.length());
            pattern.add(padded);
            for (char c : padded.toCharArray()) if (c != ' ') used.add(c);
        }
        j.add("pattern", pattern);
        JsonObject key = new JsonObject();
        JsonObject bk = obj(r, "key");
        if (bk != null) for (var e : bk.entrySet()) {
            if (e.getKey().length() != 1 || !used.contains(e.getKey().charAt(0))) continue;
            JsonElement ing = ingredient(e.getValue());
            if (ing != null) key.add(e.getKey(), ing);
        }
        for (char c : used) if (!key.has(String.valueOf(c))) return null; // a symbol with nothing behind it
        j.add("key", key);
        j.add("result", result(r.get("result")));
        return j;
    }

    private static JsonObject shapeless(JsonObject r) {
        JsonObject j = new JsonObject();
        j.addProperty("type", "minecraft:crafting_shapeless");
        JsonArray ing = new JsonArray();
        JsonElement list = r.get("ingredients");
        if (list != null && list.isJsonArray()) for (JsonElement i : list.getAsJsonArray()) {
            int n = i.isJsonObject() && i.getAsJsonObject().has("count") ? i.getAsJsonObject().get("count").getAsInt() : 1;
            JsonElement one = ingredient(i);
            for (int c = 0; c < n && one != null; c++) ing.add(one);
        }
        j.add("ingredients", ing);
        j.add("result", result(r.get("result")));
        return j;
    }

    private static JsonObject furnace(JsonObject r) {
        JsonArray tags = r.has("tags") ? r.getAsJsonArray("tags") : new JsonArray();
        String type = tags.contains(new JsonPrimitive("blast_furnace")) && !tags.contains(new JsonPrimitive("furnace")) ? "minecraft:blasting"
            : tags.contains(new JsonPrimitive("smoker")) && !tags.contains(new JsonPrimitive("furnace")) ? "minecraft:smoking"
            : tags.contains(new JsonPrimitive("campfire")) && !tags.contains(new JsonPrimitive("furnace")) ? "minecraft:campfire_cooking"
            : "minecraft:smelting";
        JsonObject j = new JsonObject();
        j.addProperty("type", type);
        j.add("ingredient", ingredient(r.get("input")));
        j.add("result", result(r.get("output")));
        j.addProperty("experience", 0.1f);
        // java cooks in 100 ticks on blast furnaces and smokers; 26.3 stores the furnace-speed time (x2)
        // and speeds up through the fuel, so those store 200 like smelting
        j.addProperty("cookingtime", type.equals("minecraft:campfire_cooking") ? 100 : 200);
        return j;
    }

    private static JsonObject smithing(JsonObject r) {
        JsonObject j = new JsonObject();
        j.addProperty("type", "minecraft:smithing_transform");
        j.add("template", ingredient(r.get("template")));
        j.add("base", ingredient(r.get("base")));
        j.add("addition", ingredient(r.get("addition")));
        j.add("result", result(r.get("result")));
        return j;
    }


    // java paths already written, the typed ones (block, entity) first. the generic copy never overwrites them
    private final java.util.Set<String> lootZapisane = new java.util.HashSet<>();

    // bp path like "loot_tables/entities/x.json" -> java loot table at data/<ns>/<dst>.json
    private boolean lootTable(String bpPath, String dst, String type) throws IOException {
        if (!lootZapisane.add(dst)) return true;
        Path src = paczka.bp().resolve(bpPath.endsWith(".json") ? bpPath : bpPath + ".json");
        JsonObject root = czytajObj(src);
        if (root == null) {
            // not in the pack = bedrock's own (loot_tables/entities/zombie.json). java has the same tables
            // under the same names nearly always, hand over to it instead of dropping nothing
            String java = vanillaLoot(bpPath);
            if (java == null) { wtf.add("loot table missing: " + bpPath); return false; }
            JsonObject j = new JsonObject();
            j.addProperty("type", type);
            JsonObject en = new JsonObject();
            en.addProperty("type", "minecraft:loot_table");
            en.addProperty("value", java);
            JsonArray entries = new JsonArray();
            entries.add(en);
            JsonObject pool = new JsonObject();
            pool.addProperty("rolls", 1);
            pool.add("entries", entries);
            JsonArray pools = new JsonArray();
            pools.add(pool);
            j.add("pools", pools);
            Path out = data().resolve(dst + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON_LADNY.toJson(j));
            return true;
        }
        JsonObject j = new JsonObject();
        j.addProperty("type", type);
        JsonArray pools = new JsonArray();
        JsonArray bpPools = root.has("pools") ? root.getAsJsonArray("pools") : new JsonArray();
        for (JsonElement pe : bpPools) {
            JsonObject bp = pe.getAsJsonObject();
            JsonObject pool = new JsonObject();
            pool.add("rolls", numberProvider(bp.get("rolls"), 1));
            JsonArray entries = new JsonArray();
            JsonArray bpEntries = bp.has("entries") ? bp.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement ee : bpEntries) {
                JsonObject be = ee.getAsJsonObject();
                String t = str(be, "type", "item");
                JsonObject en = new JsonObject();
                if (t.equals("empty")) en.addProperty("type", "minecraft:empty");
                else if (t.equals("loot_table")) {
                    // every table of the pack also lands at its own path (lootTables), so a nested one can point there
                    String inner = str(be, "name", str(be, "value", null));
                    String id = inner == null ? null : Files.isRegularFile(paczka.bp().resolve(inner.endsWith(".json") ? inner : inner + ".json"))
                        ? ns + ":" + lootPath(inner) : vanillaLoot(inner);
                    if (id == null) { wtf.add("nested loot table " + inner + " in " + bpPath + " skipped"); continue; }
                    en.addProperty("type", "minecraft:loot_table");
                    en.addProperty("value", id);
                }
                else {
                    en.addProperty("type", "minecraft:item");
                    en.addProperty("name", fixItem(str(be, "name", "minecraft:air")));
                }
                if (be.has("weight")) en.addProperty("weight", be.get("weight").getAsInt());
                JsonArray fns = new JsonArray();
                if (be.has("functions")) for (JsonElement fe : be.getAsJsonArray("functions")) {
                    JsonObject bf = fe.getAsJsonObject();
                    String fn = str(bf, "function", "");
                    if (fn.equals("set_count")) {
                        JsonObject jf = new JsonObject();
                        jf.addProperty("type", "minecraft:set_count");
                        jf.add("count", numberProvider(bf.get("count"), 1));
                        fns.add(jf);
                    } else if (fn.equals("looting_enchant")) {
                        JsonObject jf = new JsonObject();
                        jf.addProperty("type", "minecraft:enchanted_count_increase");
                        jf.addProperty("enchantment", "minecraft:looting");
                        jf.add("count", numberProvider(bf.get("count"), 1));
                        fns.add(jf);
                    } else if (fn.equals("set_damage")) {
                        JsonObject jf = new JsonObject();
                        jf.addProperty("type", "minecraft:set_damage");
                        jf.add("damage", numberProvider(bf.get("damage"), 1));
                        fns.add(jf);
                    } else if (fn.equals("furnace_smelt")) {
                        JsonObject jf = new JsonObject();
                        jf.addProperty("type", "minecraft:furnace_smelt");
                        fns.add(jf);
                    } else {
                        wtf.add("loot function " + fn + " in " + bpPath + " has no java equivalent, dropped");
                    }
                }
                // 26.3: "modifier" (a list is a sequence), not "functions"
                if (fns.size() == 1) en.add("modifier", fns.get(0));
                else if (!fns.isEmpty()) en.add("modifier", fns);
                entries.add(en);
            }
            pool.add("entries", entries);
            JsonArray conds = new JsonArray();
            if (bp.has("conditions")) for (JsonElement ce : bp.getAsJsonArray("conditions")) {
                JsonObject bc = ce.getAsJsonObject();
                String cn = str(bc, "condition", "");
                JsonObject jc = new JsonObject();
                if (cn.equals("random_chance") || cn.equals("random_chance_with_looting")) {
                    jc.addProperty("type", "minecraft:random_chance");
                    jc.addProperty("chance", bc.has("chance") ? bc.get("chance").getAsFloat() : 1f);
                } else if (cn.equals("killed_by_player") || cn.equals("killed_by_player_or_pets")) {
                    jc.addProperty("type", "minecraft:killed_by_player");
                } else {
                    wtf.add("loot condition " + cn + " in " + bpPath + " has no java equivalent, dropped");
                    continue;
                }
                conds.add(jc);
            }
            // 26.3: one "condition", several go into all_of
            if (conds.size() == 1) pool.add("condition", conds.get(0));
            else if (!conds.isEmpty()) {
                JsonObject all = new JsonObject();
                all.addProperty("type", "minecraft:all_of");
                all.add("terms", conds);
                pool.add("condition", all);
            }
            pools.add(pool);
        }
        j.add("pools", pools);
        Path out = data().resolve(dst + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, GSON_LADNY.toJson(j));
        return true;
    }

    private static JsonElement numberProvider(JsonElement e, int def) {
        if (e == null) return new JsonPrimitive(def);
        if (e.isJsonPrimitive()) return e;
        if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            JsonObject u = new JsonObject();
            u.addProperty("type", "minecraft:uniform");
            u.add("min", e.getAsJsonArray().get(0));
            u.add("max", e.getAsJsonArray().get(1));
            return u;
        }
        JsonObject o = e.getAsJsonObject();
        JsonObject u = new JsonObject();
        u.addProperty("type", "minecraft:uniform");
        u.add("min", o.has("min") ? o.get("min") : new JsonPrimitive(def));
        u.add("max", o.has("max") ? o.get("max") : new JsonPrimitive(def));
        return u;
    }

    private void lootTables() throws IOException {
        Path root = paczka.bp().resolve("loot_tables");
        for (Path f : jsonsDeep(root)) {
            String rel = paczka.bp().relativize(f).toString().replace('\\', '/');
            // anything a script reaches with /loot, a chest or a nested entry refers to by path, keep the path.
            // tables an entity or block already took get this copy too, nested entries point here
            lootTable(rel, "loot_table/" + lootPath(rel), "minecraft:generic");
        }
    }

    // "loot_tables/entities/pig.json" -> "entities/pig"
    private static String lootPath(String bpPath) {
        String p = bpPath.replace('\\', '/');
        if (p.startsWith("loot_tables/")) p = p.substring("loot_tables/".length());
        if (p.endsWith(".json")) p = p.substring(0, p.length() - 5);
        return p.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    // bedrock's own table paths, java keeps the same folders (entities, chests, gameplay, blocks)
    private static String vanillaLoot(String bpPath) {
        if (bpPath == null) return null;
        String p = lootPath(bpPath);
        return p.matches("(entities|chests|gameplay|blocks|equipment|spawners|pots|dispensers|shearing|archaeology)/[a-z0-9_./-]+") ? "minecraft:" + p : null;
    }

    // bp animations and animation controllers (server side timelines of commands and events), as they are.
    // BedrockZachowanie runs them on the mobs whose scripts.animate names them
    private void bpAnimations() throws IOException {
        for (String dir : List.of("animations", "animation_controllers")) {
            for (Path f : jsonsDeep(paczka.bp().resolve(dir))) {
                JsonObject root = czytajObj(f);
                if (root == null) continue;
                Path dst = out.resolve("bedrock_bp").resolve(dir).resolve(paczka.bp().resolve(dir).relativize(f).toString());
                Files.createDirectories(dst.getParent());
                Files.writeString(dst, root.toString());
            }
        }
    }

    // spawn_rules go along as they are, BedrockRozsiewacz reads them at run time
    private void spawnRules() throws IOException {
        int n = 0;
        for (Path f : jsonsDeep(paczka.bp().resolve("spawn_rules"))) {
            JsonObject root = czytajObj(f);
            if (root == null || !root.has("minecraft:spawn_rules")) continue;
            Path dst = out.resolve("bedrock_bp/spawn_rules").resolve(f.getFileName().toString());
            Files.createDirectories(dst.getParent());
            Files.writeString(dst, root.toString());
            n++;
        }
        if (n > 0) sidecar.addProperty("spawn_rules", n);
    }

    private void functions() throws IOException {
        Path root = paczka.bp().resolve("functions");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.endsWith(".mcfunction")) {
                    Path dst = data().resolve("function").resolve(rel.toLowerCase(Locale.ROOT));
                    Files.createDirectories(dst.getParent());
                    // bedrock lets you write "/say hi" with the slash, java chokes on it
                    List<String> lines = new ArrayList<>();
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        String t = line.strip();
                        lines.add(t.isEmpty() || t.startsWith("#") ? line : BedrockSkladnia.przetlumaczDla(ns, t));
                    }
                    Files.write(dst, lines, StandardCharsets.UTF_8);
                } else if (rel.equals("tick.json")) {
                    JsonObject tick = czytajObj(f);
                    JsonArray vals = new JsonArray();
                    if (tick != null && tick.has("values")) tick.getAsJsonArray("values").forEach(v -> vals.add(ns + ":" + v.getAsString().toLowerCase(Locale.ROOT)));
                    JsonObject tag = new JsonObject();
                    tag.add("values", vals);
                    Path dst = out.resolve("datapacks/bedrock/data/minecraft/tags/function/tick.json");
                    Files.createDirectories(dst.getParent());
                    Files.writeString(dst, GSON_LADNY.toJson(tag));
                }
            }
        }
        wtf.add("functions copied as is, bedrock only commands (tickingarea, camera...) will fail when they run");
    }

    // ── structures ───────────────────────────────────────────────────────────

    // .mcstructure files go along untouched, BedrockStruktury reads them when a script places one.
    // structures/foo.mcstructure is "mystructure:foo", structures/ns/foo.mcstructure is "ns:foo"
    private void structures() throws IOException {
        Path src = paczka.bp().resolve("structures");
        if (!Files.isDirectory(src)) return;
        Path dst = out.resolve("bedrock_structures");
        int n = 0;
        try (Stream<Path> s = Files.walk(src)) {
            for (Path f : s.filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".mcstructure")).toList()) {
                kopiuj(f, dst.resolve(src.relativize(f).toString()));
                n++;
            }
        }
        if (n > 0) KoperLib.LOGGER.info("[Bedrock] {}: {} structures", paczka.name(), n);
    }

    // ── last pass: nothing goes out that java would refuse ───────────────────

    // on 26.3 recipes and loot tables load as registries: ONE bad file stops a world from being created.
    // bedrock is forgiving about the same things (an entity id used as an item, a table it does not have)
    // so before anything ships, every item and table they name has to exist. a recipe that names a
    // missing item is dropped, a loot entry that does is cut out of its pool. all of it in the warnings
    private void sprzataj() throws IOException {
        java.util.Set<String> naszeItemy = new java.util.HashSet<>();
        for (String dir : List.of("items", "blocks")) for (Path f : jsons(out.resolve(dir))) {
            JsonObject o = czytajObj(f);
            if (o != null && o.has("id")) naszeItemy.add(o.get("id").getAsString());
        }
        java.util.function.Predicate<String> item = id -> {
            if (id == null || id.isEmpty() || id.startsWith("#")) return true;
            if (id.equals("minecraft:air")) return false;
            if (naszeItemy.contains(id)) return true;
            return javaItem.test(id);
        };
        Path lootRoot = data().resolve("loot_table");
        java.util.function.Predicate<String> tabela = id -> {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            if (rl.getNamespace().equals(ns)) return Files.isRegularFile(lootRoot.resolve(rl.getPath() + ".json"));
            return BedrockTlumacz.class.getResource("/data/" + rl.getNamespace() + "/loot_table/" + rl.getPath() + ".json") != null;
        };
        int przepisy = 0, wpisy = 0;
        for (Path f : jsonsDeep(data().resolve("recipe"))) {
            JsonObject r = czytajObj(f);
            List<String> brak = new ArrayList<>();
            zbierzItemy(r, brak, item);
            if (!brak.isEmpty()) {
                Files.delete(f);
                przepisy++;
                wtf.add("recipe " + f.getFileName() + " dropped, java has no " + brak);
            }
        }
        // two passes: a table can only point at tables that survived
        for (int pass = 0; pass < 2; pass++) for (Path f : jsonsDeep(lootRoot)) {
            JsonObject t = czytajObj(f);
            if (t == null || !t.has("pools")) continue;
            int przed = wpisy;
            for (JsonElement pool : t.getAsJsonArray("pools")) if (pool.isJsonObject() && pool.getAsJsonObject().get("entries") instanceof JsonArray en)
                wpisy += wytnij(en, item, tabela, f.getFileName().toString());
            if (wpisy != przed) Files.writeString(f, GSON_LADNY.toJson(t));
        }
        if (przepisy + wpisy > 0)
            KoperLib.LOGGER.warn("[Bedrock] {}: {} recipes and {} loot entries name things java does not have, left out (see bedrock.koper.json warnings)",
                paczka.name(), przepisy, wpisy);
    }

    private static void zbierzItemy(JsonElement e, List<String> brak, java.util.function.Predicate<String> item) {
        if (e instanceof JsonObject o) {
            for (var en : o.entrySet()) {
                if ((en.getKey().equals("id") || en.getKey().equals("item")) && en.getValue().isJsonPrimitive()) {
                    if (!item.test(en.getValue().getAsString())) brak.add(en.getValue().getAsString());
                } else if (en.getKey().equals("type") || en.getKey().equals("pattern")) {
                    continue;
                } else if (en.getValue().isJsonPrimitive() && en.getValue().getAsString().contains(":")
                    && !en.getKey().equals("group") && !en.getKey().equals("category")) {
                    if (!item.test(en.getValue().getAsString())) brak.add(en.getValue().getAsString());
                } else zbierzItemy(en.getValue(), brak, item);
            }
        } else if (e instanceof JsonArray a) {
            for (JsonElement x : a) {
                if (x.isJsonPrimitive() && x.getAsString().contains(":")) { if (!item.test(x.getAsString())) brak.add(x.getAsString()); }
                else zbierzItemy(x, brak, item);
            }
        }
    }

    private int wytnij(JsonArray entries, java.util.function.Predicate<String> item, java.util.function.Predicate<String> tabela, String plik) {
        int n = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!(entries.get(i) instanceof JsonObject en)) continue;
            String typ = str(en, "type", "");
            String bad = null;
            if (typ.equals("minecraft:item") && !item.test(str(en, "name", ""))) bad = str(en, "name", "?");
            if (typ.equals("minecraft:loot_table") && en.get("value") != null && en.get("value").isJsonPrimitive()
                && !tabela.test(en.get("value").getAsString())) bad = "table " + en.get("value").getAsString();
            if (en.get("children") instanceof JsonArray kids) n += wytnij(kids, item, tabela, plik);
            if (bad != null) {
                entries.remove(i);
                n++;
                wtf.add("loot " + plik + ": entry " + bad + " left out, java has no such thing");
            }
        }
        return n;
    }

    // ── scripts ──────────────────────────────────────────────────────────────

    private void scripts() throws IOException {
        JsonObject manifest = czytajObj(paczka.bp().resolve("manifest.json"));
        if (manifest == null || !manifest.has("modules")) return;
        String entry = null;
        for (JsonElement m : manifest.getAsJsonArray("modules")) {
            JsonObject mo = m.getAsJsonObject();
            if ("script".equals(str(mo, "type", "")) && mo.has("entry")) entry = mo.get("entry").getAsString();
        }
        if (entry == null) return;
        int major = 1;
        String serverVersion = null;
        if (manifest.has("dependencies")) for (JsonElement d : manifest.getAsJsonArray("dependencies")) {
            JsonObject dep = d.getAsJsonObject();
            if ("@minecraft/server".equals(str(dep, "module_name", ""))) {
                serverVersion = dep.get("version").isJsonPrimitive() ? dep.get("version").getAsString() : dep.get("version").toString();
                try { major = Integer.parseInt(serverVersion.replaceAll("[^0-9.].*$", "").split("\\.")[0]); } catch (Exception ignored) {}
            }
        }
        Path src = paczka.bp().resolve("scripts");
        Path dst = out.resolve("bedrock_scripts");
        if (Files.isDirectory(src)) try (Stream<Path> s = Files.walk(src)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) kopiuj(f, dst.resolve(src.relativize(f).toString()));
        }
        String rel = entry.startsWith("scripts/") ? entry.substring("scripts/".length()) : entry;
        JsonObject js = new JsonObject();
        js.addProperty("entry", "bedrock_scripts/" + rel);
        js.addProperty("api_major", major);
        if (serverVersion != null) js.addProperty("server_version", serverVersion);
        sidecar.add("script", js);
    }

    // ── meta ─────────────────────────────────────────────────────────────────

    private void writeMeta() throws IOException {
        Path manifestFile = paczka.bp() != null ? paczka.bp().resolve("manifest.json") : paczka.rp().resolve("manifest.json");
        JsonObject header = obj(czytajObj(manifestFile), "header");
        JsonObject meta = new JsonObject();
        meta.addProperty("pack_format", "K1");
        meta.addProperty("name", nice(str(header, "name", paczka.name()), paczka.name()));
        meta.addProperty("namespace", ns);
        meta.addProperty("description", nice(str(header, "description", "bedrock addon"), "") + " (converted from bedrock)");
        meta.addProperty("author", "bedrock addon");
        JsonElement v = header != null ? header.get("version") : null;
        meta.addProperty("version", v == null ? "1.0.0" : v.isJsonArray()
            ? v.getAsJsonArray().get(0) + "." + v.getAsJsonArray().get(1) + "." + v.getAsJsonArray().get(2) : v.getAsString());
        Files.writeString(out.resolve("pack.kopermeta"), GSON_LADNY.toJson(meta));
    }

    // ── tiny json helpers, bedrock loves wrapping one number in three objects ─

    static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static String str(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null) return false;
        if (e.isJsonPrimitive()) return e.getAsBoolean();
        return e.isJsonObject() && (!e.getAsJsonObject().has("value") || e.getAsJsonObject().get("value").getAsBoolean());
    }

    private static int num(JsonElement e, String inner, int def) {
        if (e == null) return def;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return Math.round(e.getAsFloat());
        if (e.isJsonObject() && inner != null && e.getAsJsonObject().has(inner)) return num(e.getAsJsonObject().get(inner), null, def);
        if (e.isJsonObject() && e.getAsJsonObject().has("value")) return num(e.getAsJsonObject().get("value"), null, def);
        return def;
    }

    private static float numF(JsonElement e) {
        if (e == null) return 0f;
        if (e.isJsonPrimitive()) return e.getAsFloat();
        if (e.isJsonObject() && e.getAsJsonObject().has("value")) return numF(e.getAsJsonObject().get("value"));
        return 0f;
    }

    static String fileName(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static List<Path> jsons(Path dir) throws IOException {
        return jsonsDeep(dir);
    }

    private static List<Path> jsonsDeep(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
