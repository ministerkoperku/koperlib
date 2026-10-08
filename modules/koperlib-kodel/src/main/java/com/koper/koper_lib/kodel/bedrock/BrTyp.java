package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.kodel.KodelConverters;
import com.koper.koper_lib.kodel.KodelModel;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// one bedrock client entity (or attachable) turned into what the native side runs and what the
// draw needs. geometry becomes kodel models in memory, one compiled def per geometry
final class BrTyp {

    public static final class Geo {
        final String key;
        public final KodelModel model;   // null = the pack leans on a geometry bedrock ships, see humanoid below
        // the same model mirrored into render space, what entity drawing uses
        public final KodelModel renderModel;
        final long def;
        final String meshKey;
        final String[] bones;
        final BrPytania.P[] queries;
        final Map<String, Integer> strings;
        final int[] qstrScratch;
        final float[] qScratch;
        final List<String> events;
        final List<String> contexts;
        final float[] cScratch;
        final int[] cstrScratch;
        // variables bedrock's engine fills in, the ones this def reads: var id + which one (BrAktorzy.SILNIK)
        int[] silnikIds = new int[0], silnikCo = new int[0];
        float[] silnikVals = new float[0];
        // per bone: which holder bone it rides on. "@slot" = the hand the item is in, null = entity root
        final String[] binding;
        // locator name -> bone index + offset in that bone's space (bedrock pixels)
        final Map<String, float[]> locators = new HashMap<>();
        // render type per bone, from the render controller's "materials" list. jeden = all bones agree
        BrFarby.Tryb jeden = BrFarby.Tryb.CUTOUT;
        Map<BrFarby.Tryb, java.util.Set<String>> grupy = Map.of();
        final Map<String, Malowanie> malowania = new java.util.concurrent.ConcurrentHashMap<>();
        // book string id -> text, for material names the native side picked
        String[] napisy = new String[0];

        Geo(String key, KodelModel model, long def, String meshKey, String[] bones, BrPytania.P[] queries,
            Map<String, Integer> strings, List<String> events, List<String> contexts, String[] binding) {
            this.key = key;
            this.model = model;
            this.renderModel = model == null ? null : com.koper.koper_lib.kodel.KodelBedrock.toRenderSpace(model);
            this.def = def;
            this.meshKey = meshKey;
            this.bones = bones;
            this.queries = queries;
            this.strings = strings;
            int top = 0;
            for (int v : strings.values()) top = Math.max(top, v + 1);
            this.napisy = new String[top];
            for (var e : strings.entrySet()) napisy[e.getValue()] = e.getKey();
            this.qstrScratch = new int[queries.length];
            this.qScratch = new float[queries.length];
            this.events = events;
            this.contexts = contexts;
            this.cScratch = new float[contexts.size()];
            this.cstrScratch = new int[contexts.size()];
            this.binding = binding;
        }

        private Malowanie nakladka;

        // every bone cutout: an overlay texture only shows where it has pixels
        Malowanie nakladka() {
            if (nakladka == null) nakladka = new Malowanie(BrFarby.Tryb.CUTOUT, Map.of(BrFarby.Tryb.CUTOUT, new java.util.HashSet<>(List.of(bones))));
            return nakladka;
        }

        void silnik(List<String> vars) {
            List<Integer> ids = new ArrayList<>(), co = new ArrayList<>();
            for (int i = 0; i < vars.size(); i++) {
                int k = java.util.Arrays.asList(BrAktorzy.SILNIK).indexOf(vars.get(i));
                if (k >= 0) { ids.add(i); co.add(k); }
            }
            silnikIds = ids.stream().mapToInt(Integer::intValue).toArray();
            silnikCo = co.stream().mapToInt(Integer::intValue).toArray();
            silnikVals = new float[silnikIds.length];
        }
    }

    final String id;
    final List<Geo> geos = new ArrayList<>();
    // biggest visible_bounds of its geometries in blocks, 0 = none said. java culls by hitbox, bedrock by this
    float visibleBounds;
    final List<String> textureKeys = new ArrayList<>();
    final List<Identifier> textures = new ArrayList<>();
    final Map<String, BrFarby.Tryb> farby = new HashMap<>();
    // material keys whose material samples the later textures too (multitexture)
    final java.util.Set<String> wielo = new java.util.HashSet<>();
    // an animation the entity names is in no pack = it is bedrock's own (player walk etc.), java's stands in
    boolean vanillaRuch, vanillaGlowa;
    BrFarby.Tryb domyslna = BrFarby.Tryb.CUTOUT;
    // per render controller, in the order the native def has them: {bone pattern, material key}
    final List<List<String[]>> wzoryRc = new ArrayList<>();

    final List<String> geoKeys = new ArrayList<>(); // native geometry index -> name

    // native geometry index -> our geos index, -1 when that one did not build
    int geo(int defIdx) {
        if (defIdx < 0 || defIdx >= geoKeys.size()) return -1;
        String key = geoKeys.get(defIdx);
        for (int i = 0; i < geos.size(); i++) if (geos.get(i).key.equals(key)) return i;
        return -1;
    }

    record Malowanie(BrFarby.Tryb jeden, Map<BrFarby.Tryb, java.util.Set<String>> grupy) {}
    // true when the entity's geometry is one bedrock ships itself (a humanoid): drive the java model
    final boolean onVanillaModel;

    // bone names bedrock humanoids use, with their bind pivots. these are the shape of the vanilla
    // player every skin tool knows, just numbers
    static final String[][] HUMANOID = {
        {"root", "", "0", "0", "0"}, {"waist", "root", "0", "12", "0"}, {"body", "waist", "0", "24", "0"},
        {"head", "body", "0", "24", "0"}, {"hat", "head", "0", "24", "0"}, {"cape", "body", "0", "24", "3"},
        {"rightArm", "body", "-5", "22", "0"}, {"rightSleeve", "rightArm", "-5", "22", "0"}, {"rightItem", "rightArm", "-6", "15", "1"},
        {"leftArm", "body", "5", "22", "0"}, {"leftSleeve", "leftArm", "5", "22", "0"}, {"leftItem", "leftArm", "6", "15", "1"},
        {"rightLeg", "root", "-1.9", "12", "0"}, {"rightPants", "rightLeg", "-1.9", "12", "0"},
        {"leftLeg", "root", "1.9", "12", "0"}, {"leftPants", "leftLeg", "1.9", "12", "0"}, {"jacket", "body", "0", "24", "0"}};

    // texture_meshes only make geometry where the png has pixels (that's why packs can put them on the
    // opaque "entity" material). turn every row into runs of solid pixels, one 1px slab per run.
    // no readable texture -> left alone, the converter makes a plain slab
    private static JsonObject wytlocz(JsonObject geo, BrTyp t, BrPaczki.Paczka p) {
        if (!geo.has("bones") || !geo.toString().contains("texture_meshes")) return geo;
        JsonObject out = geo.deepCopy();
        JsonObject desc = BrPaczki.obj(out, "description");
        int texW = desc != null && desc.has("texture_width") ? desc.get("texture_width").getAsInt() : 64;
        int texH = desc != null && desc.has("texture_height") ? desc.get("texture_height").getAsInt() : 64;
        for (JsonElement be : out.getAsJsonArray("bones")) {
            JsonObject b = be.getAsJsonObject();
            if (!b.has("texture_meshes")) continue;
            JsonArray keep = new JsonArray();
            JsonArray cubes = b.has("cubes") ? b.getAsJsonArray("cubes") : new JsonArray();
            for (JsonElement tme : b.getAsJsonArray("texture_meshes")) {
                JsonObject tm = tme.getAsJsonObject();
                String name = tm.has("texture") ? tm.get("texture").getAsString().toLowerCase(Locale.ROOT) : "default";
                int ti = t.textureKeys.indexOf(name);
                int[][] alpha = ti >= 0 ? alfa(p, t.textures.get(ti)) : null;
                if (alpha == null) { keep.add(tm); continue; }
                runs(tm, alpha, texW, texH, b.has("pivot") ? v3(b, "pivot", 0)[1] : 0f, cubes);
            }
            b.add("cubes", cubes);
            if (keep.isEmpty()) b.remove("texture_meshes"); else b.add("texture_meshes", keep);
        }
        return out;
    }

    // blockbench's reading of a texture mesh (bedrock.js import + texture_mesh.js), the one reference
    // there is: the picture in x/z (u along +x, v along +z here), 1px deep going down, local_pivot added
    // unscaled, then turned like a cube about "position", whose y counts DOWN from the bone's pivot.
    // with y as authored A&S's sword hung 20px under the hand; like this its middle lands exactly on
    // the helper bone the pack put there (0, 11.31)
    static float[] meshOrigin(float[] pos, float boneY) {
        return new float[] {pos[0], boneY - pos[1], pos[2]};
    }

    private static void runs(JsonObject tm, int[][] alpha, int texW, int texH, float boneY, JsonArray into) {
        float[] pos = meshOrigin(v3(tm, "position", 0), boneY), lp = v3(tm, "local_pivot", 0), sc = v3(tm, "scale", 1);
        int w = alpha.length, h = alpha[0].length;
        float ku = texW / (float) w, kv = texH / (float) h;
        for (int v = 0; v < h; v++) {
            int u = 0;
            while (u < w) {
                while (u < w && alpha[u][v] < 26) u++;
                if (u >= w) break;
                int u0 = u;
                while (u < w && alpha[u][v] >= 26) u++;
                float U0 = u0 * ku, U1 = u * ku, V0 = v * kv, V1 = (v + 1) * kv;
                JsonObject c = new JsonObject();
                // one image pixel is texture_width / image width model units (blockbench: uv_size / texture.width).
                // a 1024 atlas on a 16 wide geometry is 1/64 per pixel, as 1 per pixel A&S's mace
                // grew the whole atlas around the player
                c.add("origin", arr(new float[] {pos[0] + sc[0] * u0 * ku - lp[0], pos[1] - sc[1] + lp[1], pos[2] + sc[2] * v * kv - lp[2]}));
                c.add("size", arr(new float[] {(u - u0) * ku * sc[0], sc[1], kv * sc[2]}));
                c.add("pivot", arr(pos));
                // plain x-y-z like the converter's cubes: the A&S bow's grip lands on the hand (0.5, -0.05, 0)
                if (tm.has("rotation")) c.add("rotation", tm.get("rotation"));
                JsonObject uv = new JsonObject();
                // same flips KodelConverters measured for its plain slab: pixel (u,v) lands on (x=u, z=v)
                uv.add("up", uvf(U1, V1, U0 - U1, V0 - V1));
                uv.add("down", uvf(U1, V0, U0 - U1, V1 - V0));
                uv.add("north", uvf(U1, V0, U0 - U1, V1 - V0));
                uv.add("south", uvf(U1, V0, U0 - U1, V1 - V0));
                uv.add("west", uvf(U0, V0, ku, kv));
                uv.add("east", uvf(U1 - ku, V0, ku, kv));
                c.add("uv", uv);
                into.add(c);
            }
        }
    }

    private static JsonObject uvf(float u, float v, float uw, float vh) {
        JsonObject f = new JsonObject();
        f.add("uv", arr(new float[] {u, v}));
        f.add("uv_size", arr(new float[] {uw, vh}));
        return f;
    }

    private static float[] v3(JsonObject o, String k, float def) {
        float[] r = {def, def, def};
        if (o.has(k) && o.get(k).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(k);
            for (int i = 0; i < 3 && i < a.size(); i++) r[i] = a.get(i).getAsFloat();
        }
        return r;
    }

    private static final Map<Identifier, int[][]> ALFA = new java.util.concurrent.ConcurrentHashMap<>();

    // alpha per pixel [x][y] of a texture, from the pack folder or the game jar
    private static int[][] alfa(BrPaczki.Paczka p, Identifier id) {
        int[][] got = ALFA.get(id);
        if (got != null) return got.length == 0 ? null : got;
        int[][] a = null;
        byte[] bytes = null;
        try (java.io.InputStream in = otworz(p, id)) {
            if (in != null) bytes = in.readAllBytes();
        } catch (java.io.IOException nope) {
            bytes = null;
        }
        if (bytes != null) {
            a = BrPng.alfa(bytes);
            if (a == null) {
                // 16 bit, interlaced and friends: stb knows them, when it's around
                try (com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(bytes)) {
                    a = new int[img.getWidth()][img.getHeight()];
                    for (int x = 0; x < img.getWidth(); x++)
                        for (int y = 0; y < img.getHeight(); y++) a[x][y] = img.getPixel(x, y) >>> 24;
                } catch (Throwable nope) {
                    a = null;
                }
            }
        }
        if (a == null) KoperCore.LOGGER.warn("[Kodel/Bedrock] texture mesh can't read {}, drawing it as a plain slab", id);
        ALFA.put(id, a == null || a.length == 0 ? new int[0][] : a);
        return a;
    }

    private static java.io.InputStream otworz(BrPaczki.Paczka p, Identifier id) throws java.io.IOException {
        java.nio.file.Path f = p.root.resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
        if (java.nio.file.Files.isRegularFile(f)) return java.nio.file.Files.newInputStream(f);
        return BrTyp.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
    }

    // humanoid bone pivot in bedrock pixels, null if the name is not one of them
    static float[] pivotOf(String bone) {
        for (String[] h : HUMANOID)
            if (h[0].equalsIgnoreCase(bone)) return new float[] {Float.parseFloat(h[2]), Float.parseFloat(h[3]), Float.parseFloat(h[4])};
        return null;
    }

    private static final Map<String, float[]> locatorScratch = new HashMap<>();

    private BrTyp(String id, boolean onVanillaModel) {
        this.id = id;
        this.onVanillaModel = onVanillaModel;
    }

    // the owner's client entity with every lower pack's one hooked on: their geometries, textures, materials,
    // animations, particles and sounds where the owner has no such key, their render controllers and animate
    // entries after the owner's. a key both define stays the owner's. three packs redraw the player
    // (a&s, rlcraft, mowzie's): the top one is the player, the others add their layers and moves to it
    static JsonObject merged(BrPaczki.Indeks idx, BrPaczki.Paczka owner, String id) {
        JsonObject d = owner.entities.get(id);
        List<BrPaczki.Paczka> extras = idx.entityExtras.get(id);
        if (d == null || extras == null || extras.isEmpty()) return d;
        JsonObject out = d.deepCopy();
        for (BrPaczki.Paczka p : extras) {
            JsonObject e = p.entities.get(id);
            if (e == null) continue;
            for (String map : new String[] {"geometry", "textures", "materials", "animations", "particle_effects", "sound_effects"}) {
                JsonObject from = BrPaczki.obj(e, map);
                if (from == null) continue;
                JsonObject into = BrPaczki.obj(out, map);
                if (into == null) { into = new JsonObject(); out.add(map, into); }
                for (var kv : from.entrySet()) {
                    if (!into.has(kv.getKey())) into.add(kv.getKey(), kv.getValue());
                    else if (!into.get(kv.getKey()).equals(kv.getValue()))
                        KoperCore.LOGGER.debug("[Kodel/Bedrock] {}: {} '{}' of {} loses to the higher pack's", id, map, kv.getKey(), p.ns);
                }
            }
            appendNew(out, e, "render_controllers");
            JsonElement legacy = e.get("animation_controllers");
            if (legacy != null && legacy.isJsonArray()) {
                JsonArray keep = new JsonArray();
                for (JsonElement x : legacy.getAsJsonArray()) {
                    String full = x.isJsonPrimitive() ? x.getAsString() : x.isJsonObject() && !x.getAsJsonObject().isEmpty() ? x.getAsJsonObject().entrySet().iterator().next().getValue().getAsString() : null;
                    if (full != null && !movesBody(idx, p, null, full, 0)) keep.add(x);
                }
                JsonObject only = new JsonObject();
                only.add("animation_controllers", keep);
                appendNew(out, only, "animation_controllers");
            }
            JsonObject sFrom = BrPaczki.obj(e, "scripts");
            if (sFrom != null) {
                JsonObject sInto = BrPaczki.obj(out, "scripts");
                if (sInto == null) { sInto = new JsonObject(); out.add("scripts", sInto); }
                // the body belongs to the top pack: a lower pack's moves that turn the head, arms or legs would
                // fight the owner's (three packs swinging the same arm). only its own extras' moves join
                JsonObject lowerAnims = BrPaczki.obj(e, "animations");
                JsonElement anim = sFrom.get("animate");
                if (anim != null && anim.isJsonArray()) {
                    JsonArray keep = new JsonArray();
                    for (JsonElement a : anim.getAsJsonArray()) {
                        String shortName = a.isJsonPrimitive() ? a.getAsString() : a.isJsonObject() && !a.getAsJsonObject().isEmpty() ? a.getAsJsonObject().keySet().iterator().next() : null;
                        if (shortName == null) continue;
                        if (movesBody(idx, p, lowerAnims, shortName, 0)) {
                            KoperCore.LOGGER.debug("[Kodel/Bedrock] {}: {}'s '{}' moves the body, the higher pack owns that", id, p.ns, shortName);
                            continue;
                        }
                        keep.add(a);
                    }
                    JsonObject only = new JsonObject();
                    only.add("animate", keep);
                    appendNew(sInto, only, "animate");
                }
                // variables a lower pack sets for its own extras. ones the owner sets itself stay the owner's
                java.util.Set<String> ownerVars = assigned(sInto);
                for (String part : new String[] {"pre_animation", "initialize"}) {
                    JsonElement lines = sFrom.get(part);
                    if (lines == null || !lines.isJsonArray()) continue;
                    JsonArray keep = new JsonArray();
                    for (JsonElement l : lines.getAsJsonArray()) {
                        if (!l.isJsonPrimitive()) continue;
                        java.util.Set<String> mine = assignedIn(l.getAsString());
                        mine.retainAll(ownerVars);
                        if (mine.isEmpty()) keep.add(l);
                    }
                    JsonObject only = new JsonObject();
                    only.add(part, keep);
                    appendNew(sInto, only, part);
                }
            }
            if (e.has("enable_attachables") && e.get("enable_attachables").getAsBoolean()) out.addProperty("enable_attachables", true);
        }
        KoperCore.LOGGER.info("[Kodel/Bedrock] {} is drawn by {} with {} more pack(s) hooked on", id, owner.ns, extras.size());
        return out;
    }

    private static final java.util.Set<String> BODY = new java.util.HashSet<>();
    static {
        for (String[] h : HUMANOID) BODY.add(h[0].toLowerCase(Locale.ROOT));
        BODY.addAll(List.of("leftarm", "rightarm", "leftleg", "rightleg", "head", "body", "waist", "root", "hat", "jacket"));
    }

    // does this animation (or any animation a controller plays) move a bone of the humanoid body
    private static boolean movesBody(BrPaczki.Indeks idx, BrPaczki.Paczka p, JsonObject shortMap, String shortName, int depth) {
        if (depth > 3) return false;
        String full = shortMap != null && shortMap.has(shortName) && shortMap.get(shortName).isJsonPrimitive() ? shortMap.get(shortName).getAsString() : shortName;
        JsonElement a = find(idx, p, full, true, true);
        if (a != null && a.isJsonObject()) {
            JsonObject bones = BrPaczki.obj(a.getAsJsonObject(), "bones");
            if (bones != null) for (String b : bones.keySet()) if (BODY.contains(b.toLowerCase(Locale.ROOT))) return true;
            return false;
        }
        JsonElement c = find(idx, p, full, false, true);
        if (c == null || !c.isJsonObject()) return false;
        JsonObject states = BrPaczki.obj(c.getAsJsonObject(), "states");
        if (states == null) return false;
        for (var st : states.entrySet()) {
            if (!st.getValue().isJsonObject()) continue;
            JsonElement list = st.getValue().getAsJsonObject().get("animations");
            if (list == null || !list.isJsonArray()) continue;
            for (JsonElement x : list.getAsJsonArray()) {
                String n = x.isJsonPrimitive() ? x.getAsString() : x.isJsonObject() && !x.getAsJsonObject().isEmpty() ? x.getAsJsonObject().keySet().iterator().next() : null;
                if (n != null && movesBody(idx, p, shortMap, n, depth + 1)) return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern ASSIGN = java.util.regex.Pattern.compile("(?i)\\b(?:v|variable)\\.([a-z0-9_]+)\\s*=(?!=)");

    private static java.util.Set<String> assignedIn(String line) {
        java.util.Set<String> out = new java.util.HashSet<>();
        var m = ASSIGN.matcher(line);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return out;
    }

    private static java.util.Set<String> assigned(JsonObject scripts) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String part : new String[] {"pre_animation", "initialize"}) {
            JsonElement lines = scripts.get(part);
            if (lines != null && lines.isJsonArray()) for (JsonElement l : lines.getAsJsonArray()) if (l.isJsonPrimitive()) out.addAll(assignedIn(l.getAsString()));
        }
        return out;
    }

    // arrays: the lower pack's entries the owner doesn't already have, appended
    private static void appendNew(JsonObject into, JsonObject from, String key) {
        JsonElement f = from.get(key);
        if (f == null || !f.isJsonArray()) return;
        JsonElement i = into.get(key);
        JsonArray arr = i != null && i.isJsonArray() ? i.getAsJsonArray() : new JsonArray();
        for (JsonElement x : f.getAsJsonArray()) if (!arr.contains(x)) arr.add(x);
        into.add(key, arr);
    }

    static boolean javaBody(String entityId, String geometryId) {
        return "minecraft:player".equals(entityId) && BrPaczki.cialoGracza(geometryId);
    }

    static BrTyp zbuduj(BrPaczki.Indeks idx, BrPaczki.Paczka p, String id, JsonObject d) {
        JsonObject geoMap = BrPaczki.obj(d, "geometry");
        if (geoMap == null || geoMap.entrySet().isEmpty()) return null;
        boolean missing = false;
        for (var e : geoMap.entrySet()) if (javaBody(id, e.getValue().getAsString()) || geometry(idx, p, e.getValue().getAsString()) == null) missing = true;

        BrTyp t = new BrTyp(id, missing);
        t.geoKeys.addAll(geoMap.keySet());
        JsonObject mats = BrPaczki.obj(d, "materials");
        if (mats != null) for (var m : mats.entrySet())
            if (m.getValue().isJsonPrimitive()) {
                t.farby.put(m.getKey().toLowerCase(Locale.ROOT), BrFarby.tryb(idx, p, m.getValue().getAsString()));
                if (BrFarby.wielo(idx, p, m.getValue().getAsString())) t.wielo.add(m.getKey().toLowerCase(Locale.ROOT));
            }
        t.domyslna = t.farby.getOrDefault("default", t.farby.isEmpty() ? BrFarby.Tryb.CUTOUT : t.farby.values().iterator().next());

        JsonObject tex = BrPaczki.obj(d, "textures");
        if (tex != null) for (var e : tex.entrySet()) {
            t.textureKeys.add(e.getKey());
            t.textures.add(texture(idx, p, e.getValue().getAsString()));
        }

        JsonObject common = common(idx, p, d, t);
        for (var e : geoMap.entrySet()) {
            // the player's body is java's even when the pack ships its own copy of bedrock's humanoid
            // (mowzie's does): drawing that copy too gave a second player and a camera inside its head
            JsonObject geo = javaBody(id, e.getValue().getAsString()) ? null : geometry(idx, p, e.getValue().getAsString());
            KodelModel model = null;
            JsonArray bones = new JsonArray();
            String[] names;
            String[] binding = null;
            if (geo != null) {
                JsonObject gd = BrPaczki.obj(geo, "description");
                if (gd != null) for (String k : new String[] {"visible_bounds_width", "visible_bounds_height"})
                    if (gd.has(k) && gd.get(k).isJsonPrimitive()) t.visibleBounds = Math.max(t.visibleBounds, gd.get(k).getAsFloat());
                model = KodelConverters.geometry(wytlocz(geo, t, p));
                if (model.bones.isEmpty()) continue;
                Map<String, JsonObject> raw = new HashMap<>();
                if (geo.has("bones")) for (JsonElement b : geo.getAsJsonArray("bones")) {
                    JsonObject bo = b.getAsJsonObject();
                    if (bo.has("name")) raw.put(bo.get("name").getAsString().strip(), bo);
                }
                names = new String[model.bones.size()];
                for (int i = 0; i < model.bones.size(); i++) {
                    KodelModel.KodelBone kb = model.bones.get(i);
                    names[i] = kb.name;
                    JsonObject bj = new JsonObject();
                    bj.addProperty("name", kb.name);
                    bj.addProperty("parent", kb.parent);
                    bj.add("pivot", arr(kb.pivot));
                    bj.add("pos", arr(kb.position));
                    JsonObject src = raw.get(kb.name);
                    // pos already carries the parent bind pose shift, KodelConverters did it on the same raw bones
                    JsonObject srcRodzic = kb.parent >= 0 ? raw.get(model.bones.get(kb.parent).name) : null;
                    bj.add("rot", arr(src != null ? KodelConverters.spoczynek(src, srcRodzic, new float[3]) : new float[3]));
                    bones.add(bj);
                }
                for (int i = 0; i < names.length; i++) {
                    JsonObject srcBone = raw.get(names[i]);
                    JsonObject locs = srcBone == null ? null : BrPaczki.obj(srcBone, "locators");
                    if (locs == null) continue;
                    for (var l : locs.entrySet()) {
                        JsonElement v = l.getValue();
                        JsonArray off = v.isJsonArray() ? v.getAsJsonArray() : v.isJsonObject() && v.getAsJsonObject().has("offset") ? v.getAsJsonObject().getAsJsonArray("offset") : null;
                        if (off == null || off.size() < 3) continue;
                        locatorScratch.put(l.getKey().toLowerCase(Locale.ROOT), new float[] {i, off.get(0).getAsFloat(), off.get(1).getAsFloat(), off.get(2).getAsFloat()});
                    }
                }
                binding = new String[names.length];
                for (int i = 0; i < names.length; i++) {
                    JsonObject srcBone = raw.get(names[i]);
                    String b = srcBone != null && srcBone.has("binding") ? srcBone.get("binding").getAsString() : null;
                    if (b != null) {
                        b = b.trim().toLowerCase(Locale.ROOT);
                        binding[i] = b.contains("item_slot_to_bone_name") ? "@slot" : b.replace("'", "");
                    } else {
                        int par = model.bones.get(i).parent;
                        // no binding but named like a holder bone: bedrock glues it to that bone, in the
                        // holder's own model space ("=" marks absolute coords, bound ones are relative)
                        binding[i] = par >= 0 ? binding[par] : pivotOf(names[i]) != null ? "=" + names[i].toLowerCase(Locale.ROOT) : null;
                    }
                }
            } else if (missing) {
                names = new String[HUMANOID.length];
                for (int i = 0; i < HUMANOID.length; i++) {
                    String[] h = HUMANOID[i];
                    names[i] = h[0];
                    JsonObject bj = new JsonObject();
                    bj.addProperty("name", h[0]);
                    int parent = -1;
                    for (int k = 0; k < i; k++) if (HUMANOID[k][0].equals(h[1])) parent = k;
                    bj.addProperty("parent", parent);
                    bj.add("pivot", arr(new float[] {Float.parseFloat(h[2]), Float.parseFloat(h[3]), Float.parseFloat(h[4])}));
                    bones.add(bj);
                }
            } else continue;

            java.util.Map<String, float[]> locs = new HashMap<>(locatorScratch);
            locatorScratch.clear();
            JsonObject src = common.deepCopy();
            src.add("bones", bones);
            // vanilla's old wolf: its setup animation says "-14 - this" for a position that bedrock starts at the
            // pivot, a rule of its engine for these legacy models only (addons, even mojang's own, start at 0)
            if (BrPaczki.legacyPozycja(e.getValue().getAsString())) src.addProperty("legacy_pos", true);
            long def = BrNatywka.define(src.toString());
            if (def == 0) continue;
            JsonObject desc = BrNatywka.describe(def);
            if (desc == null) { BrNatywka.undefine(def); continue; }
            List<String> qs = new ArrayList<>();
            desc.getAsJsonArray("queries").forEach(x -> qs.add(x.getAsString()));
            Map<String, Integer> strings = new HashMap<>();
            JsonArray sa = desc.getAsJsonArray("strings");
            for (int i = 0; i < sa.size(); i++) strings.putIfAbsent(sa.get(i).getAsString(), i);
            List<String> events = new ArrayList<>();
            desc.getAsJsonArray("events").forEach(x -> events.add(x.getAsString()));
            List<String> contexts = new ArrayList<>();
            if (desc.has("contexts")) desc.getAsJsonArray("contexts").forEach(x -> contexts.add(x.getAsString()));
            List<String> vars = new ArrayList<>();
            if (desc.has("vars")) desc.getAsJsonArray("vars").forEach(x -> vars.add(x.getAsString()));
            JsonArray errs = desc.getAsJsonArray("errors");
            if (!errs.isEmpty()) KoperCore.LOGGER.debug("[Kodel/Bedrock] {} has {} molang errors, first: {}", id, errs.size(), errs.get(0));
            t.geos.add(new Geo(e.getKey(), model, def, "bedrock:" + p.ns + ":" + e.getValue().getAsString(), names,
                BrPytania.compile(qs), strings, events, contexts, binding));
            t.geos.get(t.geos.size() - 1).locators.putAll(locs);
            t.geos.get(t.geos.size() - 1).silnik(vars);
            t.pomaluj(t.geos.get(t.geos.size() - 1));
        }
        return t.geos.isEmpty() ? null : t;
    }

    // bone pattern -> material key from one render controller. later entries win, same as bedrock
    private static List<String[]> wzory(JsonObject rc) {
        List<String[]> into = new ArrayList<>();
        JsonElement ml = rc.get("materials");
        if (ml == null || !ml.isJsonArray()) return into;
        for (JsonElement m : ml.getAsJsonArray()) {
            if (!m.isJsonObject()) continue;
            for (var e : m.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String v = e.getValue().getAsString().trim().toLowerCase(Locale.ROOT);
                // "Material.x" is fixed, anything else is molang the native side picks per frame: "?"
                v = v.matches("material\\.[a-z0-9_.\\-]+") ? v.substring(9) : "?";
                into.add(new String[] {e.getKey().toLowerCase(Locale.ROOT), v});
            }
        }
        return into;
    }

    void pomaluj(Geo g) {
        List<String[]> first = List.of();
        for (List<String[]> w : wzoryRc) if (!w.isEmpty()) { first = w; break; }
        Malowanie m = maluj(g, first);
        g.grupy = m.grupy();
        g.jeden = m.jeden();
    }

    // the materials one render controller puts on this geometry, cached per controller
    private static final int[] MATY = new int[64];

    // handle = this geometry's native instance, asked when an entry is molang ("v.x ? Material.a : Material.b")
    Malowanie malowanie(Geo g, int rc, long handle) {
        if (rc < 0 || rc >= wzoryRc.size()) return new Malowanie(g.jeden, g.grupy);
        List<String[]> wz = wzoryRc.get(rc);
        boolean dyn = false;
        for (String[] w : wz) if (w[1].equals("?")) { dyn = true; break; }
        if (!dyn) return g.malowania.computeIfAbsent(String.valueOf(rc), r -> maluj(g, wz));
        int n = Math.min(BrNatywka.rcMaterials(handle, rc, MATY), Math.min(MATY.length, wz.size()));
        List<String[]> got = new ArrayList<>(wz.size());
        StringBuilder key = new StringBuilder().append(rc);
        for (int i = 0; i < wz.size(); i++) {
            String name = wz.get(i)[1];
            if (name.equals("?")) {
                int id = i < n ? MATY[i] : -1;
                String t = id >= 0 && id < g.napisy.length ? g.napisy[id] : null;
                name = t != null && t.startsWith("material.") ? t.substring(9) : "default";
            }
            got.add(new String[] {wz.get(i)[0], name});
            key.append('|').append(name);
        }
        return g.malowania.computeIfAbsent(key.toString(), r -> maluj(g, got));
    }

    // the materials this controller picks right now, any of them a multitexture one
    boolean wieloTekstura(int rc, long handle) {
        if (wielo.isEmpty()) return false;
        if (rc < 0 || rc >= wzoryRc.size()) return wielo.contains("default");
        List<String[]> wz = wzoryRc.get(rc);
        int n = Math.min(BrNatywka.rcMaterials(handle, rc, MATY), MATY.length);
        for (int i = 0; i < wz.size(); i++) {
            String name = wz.get(i)[1];
            if (name.equals("?")) {
                int id = i < n ? MATY[i] : -1;
                Geo g0 = geos.isEmpty() ? null : geos.get(0);
                String t = g0 != null && id >= 0 && id < g0.napisy.length ? g0.napisy[id] : null;
                name = t != null && t.startsWith("material.") ? t.substring(9) : "default";
            }
            if (wielo.contains(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private Malowanie maluj(Geo g, List<String[]> wz) {
        Map<BrFarby.Tryb, java.util.Set<String>> by = new java.util.EnumMap<>(BrFarby.Tryb.class);
        for (String bone : g.bones) {
            BrFarby.Tryb tr = domyslna;
            String low = bone.toLowerCase(Locale.ROOT);
            for (String[] w : wz) if (pasuje(w[0], low)) tr = farby.getOrDefault(w[1], domyslna);
            by.computeIfAbsent(tr, x -> new java.util.HashSet<>()).add(bone);
        }
        return new Malowanie(by.size() == 1 ? by.keySet().iterator().next() : null, by);
    }

    private static boolean pasuje(String wzor, String bone) {
        if (wzor.equals("*") || wzor.equals(bone)) return true;
        if (!wzor.contains("*")) return false;
        return bone.matches(java.util.regex.Pattern.quote(wzor).replace("*", "\\E.*\\Q"));
    }

    // 1.8.0 client entities list their controllers in "animation_controllers": [{"move": "controller.animation.x.move"}]
    // and every one of them runs, no scripts.animate needed. their short names are their own namespace (the cat
    // has an animation and a controller both called look_at_target), so they go in under the full id. before this
    // not one of them ran and such a mob stood frozen in its geometry pose
    private static void legacyKontrolery(JsonObject d, JsonObject shortMap, JsonObject scripts) {
        JsonElement lista = d.get("animation_controllers");
        if (lista == null || !lista.isJsonArray()) return;
        JsonArray animate = scripts.has("animate") && scripts.get("animate").isJsonArray() ? scripts.getAsJsonArray("animate") : new JsonArray();
        for (JsonElement el : lista.getAsJsonArray()) {
            if (el.isJsonPrimitive()) { String full = el.getAsString(); shortMap.addProperty(full, full); animate.add(full); continue; }
            if (!el.isJsonObject()) continue;
            for (var e : el.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String full = e.getValue().getAsString();
                shortMap.addProperty(full, full);
                animate.add(full);
            }
        }
        scripts.add("animate", animate);
    }

    // everything a def needs apart from bones: only the animations and controllers this entity names
    private static JsonObject common(BrPaczki.Indeks idx, BrPaczki.Paczka p, JsonObject d, BrTyp t) {
        JsonObject src = new JsonObject();
        JsonObject shortMap = BrPaczki.obj(d, "animations");
        shortMap = shortMap == null ? new JsonObject() : shortMap.deepCopy();
        JsonObject scripts = BrPaczki.obj(d, "scripts");
        scripts = scripts == null ? new JsonObject() : scripts.deepCopy();
        legacyKontrolery(d, shortMap, scripts);
        JsonObject anims = new JsonObject();
        JsonObject ctrls = new JsonObject();
        for (var e : shortMap.entrySet()) {
            String full = e.getValue().getAsString();
            JsonElement a = find(idx, p, full, true, t.onVanillaModel);
            if (a != null) { anims.add(full, a); continue; }
            JsonElement c = find(idx, p, full, false, t.onVanillaModel);
            if (c != null) ctrls.add(full, c);
            else {
                String low = full.toLowerCase(Locale.ROOT);
                if (low.contains("look_at_target")) t.vanillaGlowa = true;
                else if (low.contains("move") || low.contains("bob") || low.endsWith(".root") || low.contains("base_pose") || low.contains("walk"))
                    t.vanillaRuch = true;
            }
        }
        // java's pose carries the walk already: koperlib's stand in for the walk would swing it twice
        if (t.vanillaRuch) anims.entrySet().removeIf(e -> e.getKey().startsWith("animation.player.move.") && jestWbudowana(idx, e.getKey(), e.getValue()));
        grane(p, d, t, idx, anims);
        src.add("animations", anims);
        src.add("controllers", ctrls);
        src.add("short", shortMap);
        src.add("scripts", scripts);
        JsonArray texNames = new JsonArray();
        t.textureKeys.forEach(texNames::add);
        src.add("textures", texNames);
        JsonArray geoNames = new JsonArray();
        JsonObject geoMap = BrPaczki.obj(d, "geometry");
        if (geoMap != null) geoMap.keySet().forEach(geoNames::add);
        src.add("geometries", geoNames);

        JsonArray render = new JsonArray();
        JsonElement rcs = d.get("render_controllers");
        if (rcs != null && rcs.isJsonArray()) for (JsonElement r : rcs.getAsJsonArray()) {
            String name;
            JsonElement cond = null;
            if (r.isJsonPrimitive()) name = r.getAsString();
            else {
                var first = r.getAsJsonObject().entrySet().iterator().next();
                name = first.getKey();
                cond = first.getValue();
            }
            JsonObject rc = renderController(idx, p, name, t.onVanillaModel);
            if (rc == null) continue;
            JsonObject copy = rc.deepCopy();
            if (cond != null) copy.add("condition", cond);
            render.add(copy);
            t.wzoryRc.add(wzory(rc));
        }
        src.add("render", render);
        return src;
    }

    // naJavie: the entity stays on the java model (the player). koperlib's own minimal stand ins are not
    // for it, java's walk and head look already are what they would give
    // entity.playAnimation / /playanimation may name any animation of the pack, not only the ones the client
    // entity lists. every animation of the owning pack that moves a bone this entity has goes into the def
    // too; they cost nothing until played. bounded, a pack of thousands of player emotes stays a few hundred
    private static final int GRANE_MAX = 512;

    private static void grane(BrPaczki.Paczka p, JsonObject d, BrTyp t, BrPaczki.Indeks idx, JsonObject into) {
        java.util.Set<String> kosci = new java.util.HashSet<>();
        JsonObject geoMap = BrPaczki.obj(d, "geometry");
        if (geoMap != null) for (var e : geoMap.entrySet()) {
            JsonObject g = e.getValue().isJsonPrimitive() ? geometry(idx, p, e.getValue().getAsString()) : null;
            if (g != null && g.has("bones")) for (JsonElement b : g.getAsJsonArray("bones"))
                if (b.isJsonObject() && b.getAsJsonObject().has("name")) kosci.add(b.getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT));
        }
        if (t.onVanillaModel) for (String[] h : HUMANOID) kosci.add(h[0].toLowerCase(Locale.ROOT));
        if (kosci.isEmpty()) return;
        int added = 0;
        for (var e : p.animations.entrySet()) {
            if (added >= GRANE_MAX) break;
            if (into.has(e.getKey()) || !e.getValue().isJsonObject()) continue;
            JsonObject bones = BrPaczki.obj(e.getValue().getAsJsonObject(), "bones");
            if (bones == null) continue;
            for (String b : bones.keySet()) {
                if (kosci.contains(b.trim().toLowerCase(Locale.ROOT))) {
                    into.add(e.getKey(), e.getValue());
                    added++;
                    break;
                }
            }
        }
    }

    private static boolean jestWbudowana(BrPaczki.Indeks idx, String id, JsonElement v) {
        for (BrPaczki.Paczka p : idx.packs) if (p.wbudowana && p.animations.get(id) == v) return true;
        return false;
    }

    private static JsonElement find(BrPaczki.Indeks idx, BrPaczki.Paczka own, String id, boolean anim, boolean naJavie) {
        JsonElement v = anim ? own.animations.get(id) : own.controllers.get(id);
        if (v != null) return v;
        // koperlib's own animations answer on the java model too. skipping them there and pasting java's
        // pose on instead ignored the pack's weights and controllers: move.arms played at 0.001 still
        // swung the arms fully, a pack with its own look_at got java's look on top (head bent twice)
        for (int i = idx.packs.size() - 1; i >= 0; i--) {
            BrPaczki.Paczka p = idx.packs.get(i);
            if (naJavie && p.wbudowana && !anim) continue;
            v = anim ? p.animations.get(id) : p.controllers.get(id);
            if (v != null) return v;
        }
        // mojang renamed some vanilla ones since (controller.animation.polarbear.move is polar_bear.move now) and
        // old packs still name the old spelling. last try: same name with the underscores ignored
        String bez = id.replace("_", "");
        for (int i = idx.packs.size() - 1; i >= 0; i--) {
            BrPaczki.Paczka p = idx.packs.get(i);
            if (naJavie && p.wbudowana) continue;
            for (var e : (anim ? p.animations : p.controllers).entrySet())
                if (e.getKey().replace("_", "").equals(bez)) return e.getValue();
        }
        return null;
    }

    private static JsonObject renderController(BrPaczki.Indeks idx, BrPaczki.Paczka own, String id, boolean naJavie) {
        JsonObject v = own.renders.get(id);
        if (v != null) return v;
        for (int i = idx.packs.size() - 1; i >= 0; i--) {
            if (naJavie && idx.packs.get(i).wbudowana) continue;
            v = idx.packs.get(i).renders.get(id);
            if (v != null) return v;
        }
        return null;
    }

    static JsonObject geometry(BrPaczki.Indeks idx, BrPaczki.Paczka own, String id) {
        return geometry(idx, own, id, 0);
    }

    private static JsonObject geometry(BrPaczki.Indeks idx, BrPaczki.Paczka own, String id, int depth) {
        JsonObject g = own.geometries.get(id);
        if (g == null) for (int i = idx.packs.size() - 1; i >= 0 && g == null; i--) g = idx.packs.get(i).geometries.get(id);
        if (g == null || !g.has(BrPaczki.RODZIC) || depth > 8) return g;
        // a parent nobody has (bedrock's own humanoid on the java model path): the child alone, like before
        JsonObject parent = geometry(idx, own, g.get(BrPaczki.RODZIC).getAsString(), depth + 1);
        if (parent != null) return BrPaczki.dziedzicz(parent, g);
        JsonObject alone = g.deepCopy();
        JsonObject d = BrPaczki.obj(alone, "description");
        if (d != null && !d.has("texture_width")) d.addProperty("texture_width", 64);
        if (d != null && !d.has("texture_height")) d.addProperty("texture_height", 64);
        return alone;
    }

    // the pack's own texture, else the same path in java's vanilla textures (packs lean on bedrock's
    // stock textures and the layouts match), else the pack path anyway so the log shows what is missing
    // stands for "the skin of whoever wears this", BrAktorzy.withSkin swaps the real one in each frame
    static final Identifier PLAYER_SKIN = Identifier.fromNamespaceAndPath("koper_lib", "bedrock/player_skin");

    // the owner's file first, then a hooked-on pack that has it, then java's
    static Identifier texture(BrPaczki.Indeks idx, BrPaczki.Paczka own, String path) {
        Identifier mine = textureId(own.ns, path);
        if (java.nio.file.Files.isRegularFile(own.root.resolve("assets").resolve(own.ns).resolve(mine.getPath()))) return mine;
        for (int i = idx.packs.size() - 1; i >= 0; i--) {
            BrPaczki.Paczka p = idx.packs.get(i);
            if (p == own || p.baza) continue;
            Identifier t = textureId(p.ns, path);
            if (java.nio.file.Files.isRegularFile(p.root.resolve("assets").resolve(p.ns).resolve(t.getPath()))) return t;
        }
        return texture(own, path);
    }

    static Identifier texture(BrPaczki.Paczka p, String path) {
        Identifier own = textureId(p.ns, path);
        if (java.nio.file.Files.isRegularFile(p.root.resolve("assets").resolve(p.ns).resolve(own.getPath()))) return own;
        String rel = path.startsWith("textures/") ? path : "textures/" + path;
        String lower = rel.toLowerCase(Locale.ROOT);
        // bedrock's default skins on a player model mean the player's own skin
        if (lower.matches("textures/entity/(steve|alex|char)")) return PLAYER_SKIN;
        // the few vanilla textures java renamed outright
        String renamed = switch (lower) {
            case "textures/misc/enchanted_item_glint" -> "textures/misc/enchanted_glint_item";
            case "textures/misc/enchanted_actor_glint" -> "textures/misc/enchanted_glint_armor";
            case "textures/entity/shield" -> "textures/entity/shield/shield_base_nopattern";
            default -> null;
        };
        if (renamed != null && BrTyp.class.getResource("/assets/minecraft/" + renamed + ".png") != null) return Identifier.withDefaultNamespace(renamed + ".png");
        String j = lower.replaceFirst("^textures/items/", "textures/item/").replaceFirst("^textures/blocks/", "textures/block/");
        String jn = j.replaceFirst("/gold_", "/golden_").replaceFirst("/wood_", "/wooden_").replaceFirst("/chain_", "/chainmail_");
        // bedrock's block names for java's own textures (brick -> bricks, concrete_white -> white_concrete)
        String jb = null;
        if (lower.startsWith("textures/blocks/")) {
            String other = BrBlockTextureNames.toJava(lower.substring("textures/blocks/".length()));
            if (other != null) jb = "textures/block/" + other;
        }
        for (String cand : jb != null ? new String[] {lower, jb, j, jn} : new String[] {lower, lower.replaceAll("_v\\d+$", ""), j, jn}) {
            if (BrTyp.class.getResource("/assets/minecraft/" + cand + ".png") != null) return Identifier.withDefaultNamespace(cand + ".png");
        }
        if (MISSING_SAID.add(p.ns + "|" + path))
            KoperCore.LOGGER.error("[Kodel/Bedrock] texture {} of pack {} is nowhere (not in the pack, not a java texture by any name we know), it draws as the missing texture", path, p.ns);
        return own;
    }

    private static final java.util.Set<String> MISSING_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static Identifier textureId(String ns, String path) {
        String p = path.startsWith("textures/") ? path.substring("textures/".length()) : path;
        // packs sometimes write the extension, bedrock shrugs. textures/x.png.png was every A&S skull
        p = p.replaceFirst("(?i)\\.(png|tga|jpg)$", "");
        p = p.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return Identifier.fromNamespaceAndPath(ns, "textures/bedrock/" + p + ".png");
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) a.add(new JsonPrimitive(f));
        return a;
    }

    void zwolnij() {
        for (Geo g : geos) BrNatywka.undefine(g.def);
        geos.clear();
    }
}
