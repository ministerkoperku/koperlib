package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.koper.koper_lib.api.core.KoperPackSources;
import com.koper.koper_lib.coremod.KoperCore;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

// every enabled pack that carries bedrock_rp/ (converted bedrock resource packs), read once in
// the background after a resource reload. until it is done nothing changes on screen, mobs
// keep whatever renderer they had
final class BrPaczki {

    static final class Paczka {
        final Path root;
        final String ns;
        final Map<String, JsonObject> entities = new HashMap<>();   // identifier -> client entity description
        final Map<String, JsonElement> animations = new HashMap<>();
        final Map<String, JsonElement> controllers = new HashMap<>();
        final Map<String, JsonObject> renders = new HashMap<>();
        final Map<String, JsonObject> geometries = new HashMap<>();
        final Map<String, JsonObject> attachables = new HashMap<>(); // item identifier -> attachable description
        final Map<String, JsonObject> particles = new HashMap<>();   // effect identifier -> whole file
        final Map<String, Farba> materials = new HashMap<>();
        // the bottom of the stack: definitions every bedrock addon may name without shipping them
        // (animation.common.look_at_target, controller.render.default...). a base never draws anything
        // on its own, it only answers lookups. wbudowana = koperlib's own minimal set, the other one is
        // the vanilla resource pack the player dropped into koperlib/bedrock_vanilla
        boolean baza, wbudowana;

        Paczka(Path root, String ns) {
            this.root = root;
            this.ns = ns;
        }
    }

    record Farba(String parent, JsonObject body) {}

    // the last pack wins, same as bedrock's pack stack
    static final class Indeks {
        final List<Paczka> packs = new ArrayList<>();
        final Map<String, Paczka> entityOwner = new LinkedHashMap<>();
        // the other packs that define the same entity, highest priority first. they hook onto the owner's
        // definition (BrTyp.merged) instead of being thrown away
        final Map<String, List<Paczka>> entityExtras = new HashMap<>();
        final Map<String, Paczka> attachableOwner = new LinkedHashMap<>();
        // item id -> every attachable that says it draws that item, with its holder condition
        final Map<String, List<Przyczepa>> byItem = new HashMap<>();
        final Map<String, Paczka> particleOwner = new HashMap<>();
    }

    record Przyczepa(Paczka pack, String id, JsonObject desc, String condition) {}

    private static volatile Indeks gotowy;
    private static CompletableFuture<Indeks> wBudowie;

    private BrPaczki() {}

    static Indeks indeks() {
        return gotowy;
    }

    static synchronized void przeladuj() {
        gotowy = null;
        if (wBudowie != null) wBudowie.cancel(true);
        wBudowie = CompletableFuture.supplyAsync(BrPaczki::zbuduj);
        wBudowie.thenAccept(i -> gotowy = i);
    }

    private static Indeks zbuduj() {
        Indeks idx = new Indeks();
        long start = System.currentTimeMillis();
        bazy(idx);
        int baz = idx.packs.size();
        for (KoperPackSources.Source src : KoperPackSources.all()) {
            if (!Files.isDirectory(src.root())) continue;
            List<Path> packs;
            try (Stream<Path> s = Files.list(src.root())) {
                // lowest priority first: whatever comes later wins the owner tables, so the top pack owns
                packs = s.filter(p -> Files.isDirectory(p.resolve("bedrock_rp")))
                    .sorted(java.util.Comparator.comparingInt((Path p) -> KoperPackSources.priority(p.getFileName().toString())).reversed()
                        .thenComparing(java.util.Comparator.reverseOrder()))
                    .toList();
            } catch (Exception e) {
                continue;
            }
            for (Path pack : packs) {
                if (!src.isEnabled(pack.getFileName().toString())) continue;
                try {
                    idx.packs.add(czytaj(pack));
                } catch (Exception e) {
                    KoperCore.LOGGER.warn("[Kodel/Bedrock] {} could not be read: {}", pack.getFileName(), e.toString());
                }
            }
        }
        for (Paczka p : idx.packs) {
            if (p.baza) continue;
            // a pack redrawing minecraft:villager_v2 redraws java's minecraft:villager. the alias goes into the
            // pack's own table too, everything downstream looks entities up by the java id. bedrock's current
            // name wins over an old one (villager_v2 over villager) whatever order the files came in
            for (String id : List.copyOf(p.entities.keySet())) {
                String java = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id);
                if (java.equals(id)) continue;
                if (com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka(java).equals(id)) p.entities.put(java, p.entities.get(id));
                else p.entities.putIfAbsent(java, p.entities.get(id));
            }
            for (String id : p.entities.keySet()) {
                Paczka was = idx.entityOwner.put(id, p);
                if (was != null && was != p) idx.entityExtras.computeIfAbsent(id, k -> new ArrayList<>()).add(0, was);
            }
            p.attachables.keySet().forEach(id -> idx.attachableOwner.put(id, p));
            p.particles.keySet().forEach(id -> idx.particleOwner.put(id, p));
            for (var e : p.attachables.entrySet()) {
                JsonElement item = e.getValue().get("item");
                if (item != null && item.isJsonObject()) {
                    for (var it : item.getAsJsonObject().entrySet())
                        idx.byItem.computeIfAbsent(it.getKey(), k -> new ArrayList<>()).add(0, new Przyczepa(p, e.getKey(), e.getValue(), it.getValue().isJsonPrimitive() ? it.getValue().getAsString() : ""));
                } else {
                    String itemId = item != null && item.isJsonPrimitive() ? item.getAsString() : e.getKey();
                    idx.byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(0, new Przyczepa(p, e.getKey(), e.getValue(), ""));
                }
            }
        }
        if (idx.packs.size() > baz)
            KoperCore.LOGGER.info("[Kodel/Bedrock] {} resource packs (+{} base), {} entities, {} attachables ready in {} ms",
                idx.packs.size() - baz, baz, idx.entityOwner.size(), idx.attachableOwner.size(), System.currentTimeMillis() - start);
        return idx;
    }

    // what the game dir calls koperlib/bedrock_vanilla: bedrock's own resource pack, either the folder
    // itself (entity/, animations/...) or one with resource_pack/ inside (the bedrock-samples layout)
    static final String VANILLA = "bedrock_vanilla";

    private static void bazy(Indeks idx) {
        try (var in = BrPaczki.class.getResourceAsStream("/koperlib_kodel/bedrock_wbudowane.json")) {
            if (in != null) {
                JsonObject j = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                Paczka w = new Paczka(Path.of("."), "koper_lib");
                w.baza = w.wbudowana = true;
                putAll(obj(j, "animations"), w.animations);
                putAll(obj(j, "animation_controllers"), w.controllers);
                JsonObject rc = obj(j, "render_controllers");
                if (rc != null) rc.entrySet().forEach(e -> { if (e.getValue().isJsonObject()) w.renders.put(e.getKey(), e.getValue().getAsJsonObject()); });
                idx.packs.add(w);
            }
        } catch (Exception e) {
            KoperCore.LOGGER.warn("[Kodel/Bedrock] built in bedrock definitions broken: {}", e.toString());
        }
        Path v;
        try {
            v = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("koperlib").resolve(VANILLA);
        } catch (Throwable noGame) {
            return;
        }
        if (Files.isDirectory(v.resolve("resource_pack"))) v = v.resolve("resource_pack");
        if (!Files.isDirectory(v)) return;
        try {
            Paczka p = new Paczka(v, "minecraft");
            p.baza = true;
            czytajRp(v, p);
            // bedrock's player body is what the java player model already is, BrTyp keeps the java one for it.
            // armor pieces (geometry.humanoid.armor.*) are attachable geometry, those stay
            p.geometries.keySet().removeIf(BrPaczki::cialoGracza);
            // stock materials stay BrFarby's table, tuned against what bedrock draws, not the shader files
            p.materials.clear();
            p.particles.clear();
            p.entities.clear();
            p.attachables.clear();
            idx.packs.add(p);
            KoperCore.LOGGER.info("[Kodel/Bedrock] vanilla base from {}: {} animations, {} controllers, {} render controllers, {} geometries",
                v, p.animations.size(), p.controllers.size(), p.renders.size(), p.geometries.size());
        } catch (Exception e) {
            KoperCore.LOGGER.warn("[Kodel/Bedrock] {} could not be read: {}", v, e.toString());
        }
    }

    static boolean cialoGracza(String geo) {
        String g = geo.toLowerCase(java.util.Locale.ROOT);
        return g.startsWith("geometry.humanoid") && !g.startsWith("geometry.humanoid.armor");
    }

    static JsonObject json(Path f) {
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            if (!raw.isEmpty() && raw.charAt(0) == '﻿') raw = raw.substring(1);
            JsonReader r = new JsonReader(new StringReader(raw));
            r.setStrictness(Strictness.LENIENT);
            JsonElement e = JsonParser.parseReader(r);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        } catch (Exception e) {
            KoperCore.LOGGER.error("[Kodel/Bedrock] {} is not readable json, everything in it is missing: {}", f, e.toString());
            return null;
        }
    }

    static Paczka czytaj(Path pack) throws Exception {
        JsonObject side = json(pack.resolve("bedrock.koper.json"));
        String ns = side != null && side.has("namespace") ? side.get("namespace").getAsString() : pack.getFileName().toString();
        Paczka p = new Paczka(pack, ns);
        czytajRp(pack.resolve("bedrock_rp"), p);
        return p;
    }

    // every json of a bedrock resource pack folder into p. parsing in parallel, filing in order,
    // so which file wins for a duplicate id does not depend on thread timing
    static void czytajRp(Path rp, Paczka p) throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(rp)) {
            files = s.filter(x -> x.toString().endsWith(".json") || x.toString().endsWith(".material")).sorted().toList();
        }
        List<JsonObject> parsed = files.parallelStream().map(BrPaczki::json).toList();
        for (int fi = 0; fi < files.size(); fi++) {
            Path f = files.get(fi);
            JsonObject j = parsed.get(fi);
            if (j == null) continue;
            String top = rp.relativize(f).getName(0).toString();
            if (f.toString().endsWith(".material")) {
                if (top.equals("materials")) BrFarby.czytaj(j, p.materials);
                continue;
            }
            switch (top) {
                case "entity" -> {
                    JsonObject ce = obj(j, "minecraft:client_entity");
                    JsonObject d = obj(ce, "description");
                    if (d != null && d.has("identifier")) wezNowszy(p.entities, d.get("identifier").getAsString(), d);
                }
                case "attachables" -> {
                    JsonObject at = obj(j, "minecraft:attachable");
                    JsonObject d = obj(at, "description");
                    if (d != null && d.has("identifier")) wezNowszy(p.attachables, d.get("identifier").getAsString(), d);
                }
                case "animations" -> putAll(obj(j, "animations"), p.animations);
                case "animation_controllers" -> putAll(obj(j, "animation_controllers"), p.controllers);
                case "render_controllers" -> {
                    JsonObject rc = obj(j, "render_controllers");
                    if (rc != null) rc.entrySet().forEach(e -> { if (e.getValue().isJsonObject()) p.renders.put(e.getKey(), e.getValue().getAsJsonObject()); });
                }
                case "models" -> geometries(j, p.geometries);
                case "particles" -> {
                    JsonObject d = obj(obj(j, "particle_effect"), "description");
                    if (d != null && d.has("identifier")) p.particles.put(d.get("identifier").getAsString(), j);
                }
                default -> {}
            }
        }
    }

    private static void putAll(JsonObject from, Map<String, JsonElement> into) {
        if (from != null) from.entrySet().forEach(e -> into.put(e.getKey(), e.getValue()));
    }

    // 1.12+ is an array under minecraft:geometry, 1.8 keys each geometry by id ("geometry.a:geometry.parent")
    static void geometries(JsonObject j, Map<String, JsonObject> into) {
        JsonElement list = j.get("minecraft:geometry");
        if (list != null && list.isJsonArray()) {
            for (JsonElement g : list.getAsJsonArray()) {
                JsonObject d = obj(g.getAsJsonObject(), "description");
                if (d != null && d.has("identifier")) into.put(d.get("identifier").getAsString(), legacyPozy(d.get("identifier").getAsString(), g.getAsJsonObject()));
            }
        }
        for (var e : j.entrySet()) {
            if (!e.getKey().startsWith("geometry.") || !e.getValue().isJsonObject()) continue;
            String id = e.getKey().contains(":") ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
            JsonObject g = e.getValue().getAsJsonObject().deepCopy();
            // "geometry.witch:geometry.villager": the witch is the villager plus whatever it adds or redefines.
            // remembered here, merged when looked up, the parent may live in another pack of the stack
            if (e.getKey().contains(":")) g.addProperty(RODZIC, e.getKey().substring(e.getKey().indexOf(':') + 1).trim());
            if (!g.has("description")) {
                JsonObject d = new JsonObject();
                d.addProperty("identifier", id);
                // a child that does not say its texture size has its parent's, not 64
                if (g.has("texturewidth") || !g.has(RODZIC)) d.addProperty("texture_width", g.has("texturewidth") ? g.get("texturewidth").getAsInt() : 64);
                if (g.has("textureheight") || !g.has(RODZIC)) d.addProperty("texture_height", g.has("textureheight") ? g.get("textureheight").getAsInt() : 64);
                g.add("description", d);
            }
            into.put(id, legacyPozy(id, g));
        }
    }

    // a few vanilla geometries whose rest pose bedrock keeps in the engine, not in the file (their old java
    // models set it in code): the villager's hat brim lies flat, the chicken's and the cat's bodies lie along
    // the mob. packs copying these files (villager mods do) got an upright brim and standing bodies. applied
    // only to a bone that has no rest turn of its own
    private static final Map<String, float[]> LEGACY = Map.of(
        "brim", new float[] {-90, 0, 0});
    static JsonObject legacyPozy(String id, JsonObject g) {
        String low = id.toLowerCase(java.util.Locale.ROOT);
        boolean wiesniak = low.contains("villager"), kura = low.startsWith("geometry.chicken") && !low.contains("baby"),
            kot = (low.equals("geometry.cat") || low.startsWith("geometry.cat.") || low.startsWith("geometry.ocelot")) && !low.contains("baby");
        if (!wiesniak && !kura && !kot) return g;
        JsonElement bones = g.get("bones");
        if (bones == null || !bones.isJsonArray()) return g;
        boolean zmiana = false;
        JsonObject out = g.deepCopy();
        for (JsonElement be : out.getAsJsonArray("bones")) {
            if (!be.isJsonObject()) continue;
            JsonObject b = be.getAsJsonObject();
            if (b.has("rotation") || b.has("bind_pose_rotation") || !b.has("name")) continue;
            String n = b.get("name").getAsString();
            float[] r = null;
            if (wiesniak) r = LEGACY.get(n);
            else if (n.equals("body")) r = new float[] {90, 0, 0};
            if (r == null) continue;
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (float x : r) a.add(x);
            b.add("bind_pose_rotation", a);
            zmiana = true;
        }
        return zmiana ? out : g;
    }

    // geometries whose position animations expect bedrock's legacy default (checked in game: the wolf)
    static boolean legacyPozycja(String geometryId) {
        String low = geometryId.toLowerCase(java.util.Locale.ROOT);
        return low.equals("geometry.wolf") || low.equals("geometry.wolf.armor");
    }

    static final String RODZIC = "koper:rodzic";

    // legacy geometry inheritance: the parent's bones, a child bone with the same name replaces the
    // parent's one whole, new ones go on the end. texture size and the rest from the child when it says
    static JsonObject dziedzicz(JsonObject parent, JsonObject child) {
        JsonObject out = parent.deepCopy();
        out.remove(RODZIC);
        for (var e : child.entrySet()) {
            if (e.getKey().equals("bones") || e.getKey().equals(RODZIC)) continue;
            if (e.getKey().equals("description") && out.has("description") && e.getValue().isJsonObject()) {
                JsonObject d = out.getAsJsonObject("description");
                e.getValue().getAsJsonObject().entrySet().forEach(x -> d.add(x.getKey(), x.getValue()));
                continue;
            }
            out.add(e.getKey(), e.getValue());
        }
        com.google.gson.JsonArray bones = new com.google.gson.JsonArray();
        com.google.gson.JsonArray mine = child.has("bones") && child.get("bones").isJsonArray() ? child.getAsJsonArray("bones") : new com.google.gson.JsonArray();
        java.util.Set<String> used = new java.util.HashSet<>();
        if (parent.has("bones") && parent.get("bones").isJsonArray()) for (JsonElement b : parent.getAsJsonArray("bones")) {
            JsonElement swap = b;
            String n = b.isJsonObject() && b.getAsJsonObject().has("name") ? b.getAsJsonObject().get("name").getAsString() : null;
            if (n != null) for (JsonElement c : mine) {
                if (c.isJsonObject() && c.getAsJsonObject().has("name") && c.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(n)) {
                    swap = c;
                    used.add(n.toLowerCase(java.util.Locale.ROOT));
                }
            }
            bones.add(swap);
        }
        for (JsonElement c : mine) {
            String n = c.isJsonObject() && c.getAsJsonObject().has("name") ? c.getAsJsonObject().get("name").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
            if (!used.contains(n)) bones.add(c);
        }
        out.add("bones", bones);
        return out;
    }

    // two files for one identifier (vanilla keeps creeper.entity.json next to creeper.v1.0.entity.json): bedrock
    // takes the one with the highest min_engine_version, none counts as the oldest. the old creeper drew its
    // charged layer always, the current one only when powered
    private static void wezNowszy(Map<String, JsonObject> into, String id, JsonObject d) {
        JsonObject had = into.get(id);
        if (had == null || wersja(d) >= wersja(had)) into.put(id, d);
    }

    // "1.8.0" -> comparable number, missing = 0
    static long wersja(JsonObject d) {
        JsonElement v = d.get("min_engine_version");
        if (v == null || !v.isJsonPrimitive()) return 0;
        long out = 0;
        String[] p = v.getAsString().split("\\.");
        for (int i = 0; i < 4; i++) {
            long n = 0;
            try { if (i < p.length) n = Long.parseLong(p[i].replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) {}
            out = out * 10000 + Math.min(n, 9999);
        }
        return out;
    }

    static JsonObject obj(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
