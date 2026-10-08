package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.panama.RustBridge;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;

// the behavior pack half of a bedrock mob, live: component groups switched on and off by events,
// events fired by sensors, timers, interactions and scripts, filters deciding. the parts that have
// a java twin are applied to the real mob (health, speed, attack, scale, baby, ai goals); the rest
// is kept as state the resource pack reads through queries on the client
public final class BedrockZachowanie {

    static final class Def {
        final String id;
        final JsonObject base;
        final Map<String, JsonObject> groups = new LinkedHashMap<>();
        final Map<String, JsonElement> events = new HashMap<>();
        final Map<String, JsonObject> properties = new LinkedHashMap<>();
        // a pack's definition of one of java's own mobs (minecraft:villager_v2 for villager news and friends).
        // java keeps being the mob: its health, speed, ai, breeding, trading, taming. the pack gets what java
        // has no word for: properties the resource pack reads, component groups and events, timers and
        // sensors that fire them, commands, sounds and particles
        boolean nakladka;
        // the pack's namespace: its commands say "function drop_xp" and mean that pack's drop_xp
        String ns;
        // bp animations / animation controllers (scripts.animate), native, one per entity type
        long bp;
        String[] bpQ = new String[0], bpFired = new String[0];
        Map<String, Integer> bpStr = Map.of();

        Def(String id, JsonObject base) {
            this.id = id;
            this.base = base;
        }
    }

    // live state of one mob. groups and properties also sit in its tags, so they survive a save
    static final class Stan {
        final Def def;
        final LinkedHashSet<String> groups = new LinkedHashSet<>();
        final Map<String, Object> props = new LinkedHashMap<>();
        JsonObject comps = new JsonObject();
        List<String> goals = List.of();
        final Map<String, int[]> timers = new HashMap<>();   // component -> [ticks left]
        int envCooldown;
        // minecraft:projectile: the velocity BedrockPocisk flies it with
        double[] lot;
        // native bp actor of this mob, freed by the cleaner when the mob's state goes away
        long bpInst;
        float[] bpQv;
        int[] bpQs;
        boolean dirty = true;
        String lastSync = "";

        Stan(Def def) {
            this.def = def;
        }
    }

    private static final Map<String, Def> DEFS = new HashMap<>();
    private static final Map<Entity, Stan> STANY = new WeakHashMap<>();
    private static final Map<String, Boolean> SAID = new HashMap<>();

    private BedrockZachowanie() {}

    static void once(String what) {
        if (SAID.putIfAbsent(what, true) == null) KoperLib.LOGGER.warn("[Bedrock] {}", what);
    }

    private static final java.lang.ref.Cleaner SPRZATACZ = java.lang.ref.Cleaner.create();

    public static void reload() {
        // live actors keep their compiled def alive on the native side, dropping ours is safe
        for (Def d : DEFS.values()) if (d.bp != 0) RustBridge.bpUndefine(d.bp);
        DEFS.clear();
        STANY.clear();
        // lowest priority first: a higher pack's definition of the same mob replaces it and the lower one hooks on
        List<File> packs = new ArrayList<>(FullPackLoader.getEnabledPackDirs());
        packs.sort(java.util.Comparator.comparingInt((File f) -> FullPackLoader.priority(f.getName())).reversed());
        for (File pack : packs) {
            wczytaj(pack, pack.toPath().resolve("bedrock_bp/entities"), false);
            wczytaj(pack, pack.toPath().resolve("bedrock_bp/entities/vanilla"), true);
        }
        if (!DEFS.isEmpty()) KoperLib.LOGGER.info("[Bedrock] {} behavior pack entities live ({} on java's own mobs)",
            DEFS.size(), DEFS.values().stream().filter(d -> d.nakladka).count());
    }

    // bp animations and controllers of one pack, full id -> json
    private static Map<String, JsonElement> bpCzesci(File pack, String dir, String key) {
        Map<String, JsonElement> out = new HashMap<>();
        Path d = pack.toPath().resolve("bedrock_bp").resolve(dir);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.walk(d)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject all = BedrockTlumacz.obj(BedrockTlumacz.czytajObj(f), key);
                if (all != null) all.entrySet().forEach(e -> out.put(e.getKey(), e.getValue()));
            }
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Bedrock] bp {} in {} unreadable: {}", dir, pack.getName(), e.toString());
        }
        return out;
    }

    // the entity's description.animations + scripts.animate -> a native bp actor definition
    private static void bpBuduj(Def d, JsonObject desc, Map<String, JsonElement> anims, Map<String, JsonElement> ctrls) {
        JsonObject shortMap = BedrockTlumacz.obj(desc, "animations");
        JsonObject scripts = BedrockTlumacz.obj(desc, "scripts");
        if (shortMap == null || scripts == null || !scripts.has("animate")) return;
        JsonObject a = new JsonObject(), c = new JsonObject();
        for (var e : shortMap.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            String full = e.getValue().getAsString();
            if (anims.containsKey(full)) a.add(full, anims.get(full));
            else if (ctrls.containsKey(full)) c.add(full, ctrls.get(full));
        }
        if (a.isEmpty() && c.isEmpty()) return;
        JsonObject src = new JsonObject();
        src.add("animations", a);
        src.add("controllers", c);
        src.add("short", shortMap);
        src.add("scripts", scripts);
        long h = RustBridge.bpDefine(src.toString());
        JsonObject info = h == 0 ? null : RustBridge.bpDescribe(h);
        if (info == null) { if (h != 0) RustBridge.bpUndefine(h); return; }
        d.bp = h;
        d.bpQ = strings(info.getAsJsonArray("queries"));
        d.bpFired = strings(info.getAsJsonArray("fired"));
        String[] str = strings(info.getAsJsonArray("strings"));
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < str.length; i++) ids.putIfAbsent(str[i], i);
        d.bpStr = ids;
        JsonArray errs = info.getAsJsonArray("errors");
        if (errs != null && !errs.isEmpty()) KoperLib.LOGGER.debug("[Bedrock] {} bp animations: {} molang errors, first {}", d.id, errs.size(), errs.get(0));
    }

    private static String[] strings(JsonArray a) {
        if (a == null) return new String[0];
        String[] out = new String[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsString();
        return out;
    }

    private static void wczytaj(File pack, Path dir, boolean nakladka) {
        if (!Files.isDirectory(dir)) return;
        Map<String, JsonElement> anims = bpCzesci(pack, "animations", "animations");
        Map<String, JsonElement> ctrls = bpCzesci(pack, "animation_controllers", "animation_controllers");
        JsonObject side = BedrockTlumacz.czytajObj(pack.toPath().resolve("bedrock.koper.json"));
        String ns = BedrockTlumacz.str(side, "namespace", null);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject root = BedrockTlumacz.czytajObj(f);
                JsonObject ent = BedrockTlumacz.obj(root, "minecraft:entity");
                String id = BedrockTlumacz.str(BedrockTlumacz.obj(ent, "description"), "identifier", null);
                if (id == null) continue;
                JsonObject base = BedrockTlumacz.obj(ent, "components");
                Def d = new Def(id, base == null ? new JsonObject() : base);
                JsonObject cg = BedrockTlumacz.obj(ent, "component_groups");
                if (cg != null) cg.entrySet().forEach(e -> { if (e.getValue().isJsonObject()) d.groups.put(e.getKey(), e.getValue().getAsJsonObject()); });
                JsonObject ev = BedrockTlumacz.obj(ent, "events");
                if (ev != null) ev.entrySet().forEach(e -> d.events.put(e.getKey(), e.getValue()));
                JsonObject props = BedrockTlumacz.obj(BedrockTlumacz.obj(ent, "description"), "properties");
                if (props != null) props.entrySet().forEach(e -> { if (e.getValue().isJsonObject()) d.properties.put(e.getKey(), e.getValue().getAsJsonObject()); });
                d.nakladka = nakladka;
                d.ns = ns;
                if (!anims.isEmpty() || !ctrls.isEmpty()) bpBuduj(d, BedrockTlumacz.obj(ent, "description"), anims, ctrls);
                // keyed by java's id: minecraft:villager_v2 lives on minecraft:villager. bedrock's current
                // name beats an old one (villager_v2 over villager), whichever file came first
                String java = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id);
                if (!nakladka || !DEFS.containsKey(java) || com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka(java).equals(id)) {
                    Def lower = DEFS.get(java);
                    if (lower != null && lower != d) hookOn(d, lower);
                    DEFS.put(java, d);
                }
            }
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[Bedrock] behavior entities in {} unreadable: {}", pack.getName(), e.toString());
        }
    }

    // a lower pack's definition of the same mob: its components, groups, events and properties where the
    // higher one has none. rlcraft's player properties (system_level...) survive a&s owning the player
    private static void hookOn(Def owner, Def lower) {
        lower.base.entrySet().forEach(e -> { if (!owner.base.has(e.getKey())) owner.base.add(e.getKey(), e.getValue()); });
        lower.groups.forEach(owner.groups::putIfAbsent);
        lower.events.forEach(owner.events::putIfAbsent);
        lower.properties.forEach(owner.properties::putIfAbsent);
        KoperLib.LOGGER.info("[Bedrock] {}: {} hooks onto the higher pack's definition", owner.id, lower.ns);
    }

    // minecraft:despawn: gone only when its filters pass and, with despawn_from_distance, the nearest player is
    // beyond max (at once) or beyond min (now and then, like java's 1 in 800). no component = it stays, like bedrock.
    // "minecraft:persistent" always stays
    public static void checkDespawn(Mob m) {
        Stan s = stan(m);
        if (s == null || m.isPersistenceRequired() || s.comps.has("minecraft:persistent")) return;
        JsonObject d = BedrockTlumacz.obj(s.comps, "minecraft:despawn");
        if (d == null) return;
        if (d.has("filters") && !BedrockFiltr.test(d.get("filters"), ctx(m, null))) return;
        JsonObject far = BedrockTlumacz.obj(d, "despawn_from_distance");
        boolean byFilterOnly = d.has("filters") && far == null;
        if (byFilterOnly) { m.discard(); return; }
        if (far == null && !d.entrySet().isEmpty()) return; // only chance / inactivity rules we don't run: stay
        double min = far != null && far.has("min_distance") ? far.get("min_distance").getAsDouble() : 32;
        double max = far != null && far.has("max_distance") ? far.get("max_distance").getAsDouble() : 128;
        Entity p = m.level().getNearestPlayer(m, -1.0);
        if (p == null) return;
        double dist = p.distanceTo(m);
        if (dist > max) m.discard();
        else if (dist > min && (!d.has("despawn_from_chance") || d.get("despawn_from_chance").getAsBoolean()) && m.getRandom().nextInt(800) == 0) m.discard();
    }

    static Def def(Entity e) {
        if (DEFS.isEmpty()) return null;
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? null : DEFS.get(key.toString());
    }

    public static boolean ma(Entity e) {
        return def(e) != null;
    }

    // ── state access for filters, queries, scripts ───────────────────────────

    private static Stan stan(Entity e) {
        Stan s = STANY.get(e);
        if (s != null) return s;
        Def d = def(e);
        if (d == null) return null;
        s = new Stan(d);
        // back from disk: groups and properties were written into tags
        for (String t : e.entityTags()) {
            if (t.startsWith("bcg:")) s.groups.add(t.substring(4));
            else if (t.startsWith("bprop:")) {
                int eq = t.indexOf('=');
                if (eq > 0) s.props.put(t.substring(6, eq), decode(t.substring(eq + 1)));
            }
        }
        for (var p : d.properties.entrySet()) s.props.putIfAbsent(p.getKey(), defaultOf(p.getValue()));
        STANY.put(e, s);
        return s;
    }

    private static Object decode(String raw) {
        if (raw.equals("true") || raw.equals("false")) return Boolean.parseBoolean(raw);
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return raw; }
    }

    private static Object defaultOf(JsonObject p) {
        JsonElement d = p.get("default");
        String type = BedrockTlumacz.str(p, "type", "int");
        if (d != null && d.isJsonPrimitive()) {
            if (d.getAsJsonPrimitive().isBoolean()) return d.getAsBoolean();
            if (d.getAsJsonPrimitive().isNumber()) return type.equals("float") ? d.getAsDouble() : (double) d.getAsInt();
            String s = d.getAsString();
            // a default can be molang, a bare value is by far the common case
            if (type.equals("enum")) return s;
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
            return type.equals("bool") ? Boolean.parseBoolean(s) : s;
        }
        return switch (type) {
            case "bool" -> false;
            case "enum" -> p.has("values") && !p.getAsJsonArray("values").isEmpty() ? p.getAsJsonArray("values").get(0).getAsString() : "";
            default -> 0.0;
        };
    }

    public static Set<String> families(Entity e) {
        Stan s = stan(e);
        Set<String> out = new HashSet<>();
        if (s != null) {
            JsonObject tf = BedrockTlumacz.obj(s.comps, "minecraft:type_family");
            if (tf != null && tf.has("family")) tf.getAsJsonArray("family").forEach(x -> out.add(x.getAsString()));
            if (!out.isEmpty()) return out;
        }
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (key != null) out.add(key.getPath());
        if (e instanceof Player) { out.add("player"); out.add("mob"); }
        if (e instanceof net.minecraft.world.entity.monster.Enemy) out.add("monster");
        if (e instanceof Mob) out.add("mob");
        if (e instanceof net.minecraft.world.entity.animal.Animal) out.add("animal");
        if (e.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.UNDEAD)) out.add("undead");
        if (e.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.ARTHROPOD)) out.add("arthropod");
        if (e.getType().builtInRegistryHolder().is(net.minecraft.tags.EntityTypeTags.ILLAGER)) { out.add("illager"); out.add("monster"); }
        return out;
    }

    public static boolean hasComponent(Entity e, String name) {
        Stan s = stan(e);
        if (s == null) return false;
        return s.comps.has(name.contains(":") ? name : "minecraft:" + name);
    }

    public static int intState(Entity e, String comp) {
        Stan s = stan(e);
        // java's own villagers: their job, biome and face are java's villager data, whatever an overlay says
        Integer w = s == null || s.def.nakladka ? com.koper.koper_lib.api.core.BedrockWiesniak.liczba(e, comp) : null;
        if (w != null) return w;
        if (s == null) return 0;
        JsonObject c = BedrockTlumacz.obj(s.comps, "minecraft:" + comp);
        return c != null && c.has("value") ? c.get("value").getAsInt() : 0;
    }

    public static Object property(Entity e, String name) {
        Stan s = stan(e);
        return s == null || name == null ? null : s.props.get(name);
    }

    // entity.setProperty from a script. same path as an event's set_property: typed, clamped to its range,
    // stored in tags, synced to clients on the next tick. false = this mob has no behavior definition
    public static boolean ustawZeSkryptu(Entity e, String name, JsonElement v) {
        Stan s = stan(e);
        if (s == null || name == null || v == null || v.isJsonNull()) return false;
        Object val;
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) val = v.getAsBoolean();
        else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) val = v.getAsDouble();
        else val = v.getAsString();
        setProp(e, s, name, val);
        return true;
    }

    // resetProperty: back to the definition's default
    public static boolean resetZeSkryptu(Entity e, String name) {
        Stan s = stan(e);
        JsonObject def = s == null ? null : s.def.properties.get(name);
        if (def == null) return false;
        setProp(e, s, name, defaultOf(def));
        return true;
    }

    // ── lifecycle hooks (KoperMobEntity calls these) ─────────────────────────

    // spawn events that replace entity_spawned (summon x<ev>, spawn_entity's spawn_event, spawn rules). bedrock fires
    // only that one: a pack whose entity_spawned spawns babies and gives the babies their own event relies on it,
    // firing both made every baby an adult that spawned 3 more (rlcraft's peacock: 3^n of them in seconds)
    private static final Map<Entity, String> SPAWN_EVENT_OVERRIDE = new java.util.WeakHashMap<>();

    public static void overrideSpawnEvent(Entity e, String event) {
        if (event != null && !event.isBlank() && !event.equals("minecraft:entity_spawned")) SPAWN_EVENT_OVERRIDE.put(e, event);
    }

    public static void spawned(Mob m) {
        Stan s = stan(m);
        if (s == null || m.entityTags().contains("binit")) return;
        m.addTag("binit");
        for (var p : s.def.properties.entrySet()) setProp(m, s, p.getKey(), defaultOf(p.getValue()));
        String ev = SPAWN_EVENT_OVERRIDE.remove(m);
        event(m, ev != null ? ev : "minecraft:entity_spawned", null);
        apply(m, s);
    }

    public static void tick(Mob m) {
        Stan s = stan(m);
        if (s == null) return;
        if (!m.entityTags().contains("binit")) {
            spawned(m);
            return;
        }
        if (s.dirty) apply(m, s);
        if (s.def.bp != 0) bpTick(m, s);
        if (m.isRemoved()) return;
        JsonObject pr = s.def.nakladka ? null : BedrockTlumacz.obj(s.comps, "minecraft:projectile");
        if (pr != null) s.lot = BedrockPocisk.lec(m, pr, s.lot);
        if (m.isRemoved()) return;
        if (s.def.nakladka) timerOnly(m, s);
        else timers(m, s);
        if (--s.envCooldown <= 0) {
            s.envCooldown = 5;
            JsonObject env = BedrockTlumacz.obj(s.comps, "minecraft:environment_sensor");
            if (env != null) for (JsonObject t : triggers(env.get("triggers"))) {
                if (BedrockFiltr.test(t.get("filters"), ctx(m, null))) fire(m, t, null);
            }
            entitySensor(m, s);
            targetNearbySensor(m, s);
        }
        boss(m, s);
        if (s.comps.has("minecraft:instant_despawn")) m.discard();
    }

    // ── bp animations and controllers: commands, events and molang on the server ─

    private static final int[] BP_FIRED = new int[64];

    private static void bpTick(Mob m, Stan s) {
        Def d = s.def;
        if (s.bpInst == 0) {
            s.bpInst = RustBridge.bpSpawn(d.bp, m.getId() * 31 + 7);
            if (s.bpInst == 0) return;
            long h = s.bpInst;
            SPRZATACZ.register(s, () -> RustBridge.bpFree(h));
            s.bpQv = new float[d.bpQ.length];
            s.bpQs = new int[d.bpQ.length];
        }
        for (int i = 0; i < d.bpQ.length; i++) {
            JsonElement v = query(m, s, d.bpQ[i]);
            s.bpQs[i] = -1;
            s.bpQv[i] = 0f;
            if (v == null || !v.isJsonPrimitive()) continue;
            if (v.getAsJsonPrimitive().isString()) s.bpQs[i] = d.bpStr.getOrDefault(v.getAsString(), -2);
            else if (v.getAsJsonPrimitive().isBoolean()) s.bpQv[i] = v.getAsBoolean() ? 1f : 0f;
            else s.bpQv[i] = v.getAsFloat();
        }
        int n = RustBridge.bpTick(s.bpInst, s.bpQv, s.bpQs, 0.05f, BP_FIRED);
        for (int k = 0; k < n && !m.isRemoved(); k++) {
            int i = BP_FIRED[k];
            if (i < 0 || i >= d.bpFired.length) continue;
            String line = d.bpFired[i].trim();
            if (line.startsWith("/")) runCommand(m, line.substring(1));
            else if (line.startsWith("@")) {
                // "@s koper:roar": an event on the mob itself. other selectors run as a command would
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String who = line.substring(0, sp).trim(), ev = line.substring(sp + 1).trim();
                if (who.equals("@s")) event(m, ev, null);
                else if (who.startsWith("@t") && m.getTarget() != null) event(m.getTarget(), ev, m);
                else once("bp animation event '" + line + "': only @s (and @target) are run");
            }
        }
    }

    // ── minecraft:boss: the bar at the top of the screen for everyone within hud_range ─

    private static final Map<Mob, net.minecraft.server.level.ServerBossEvent> BOSSY = new WeakHashMap<>();

    private static void boss(Mob m, Stan s) {
        // java's own wither and dragon have their bar already, a copied vanilla file would add a second
        JsonObject b = s.def.nakladka ? null : BedrockTlumacz.obj(s.comps, "minecraft:boss");
        net.minecraft.server.level.ServerBossEvent ev = BOSSY.get(m);
        if (b == null) {
            if (ev != null) { ev.removeAllPlayers(); BOSSY.remove(m); }
            return;
        }
        if (!(m.level() instanceof ServerLevel sl)) return;
        if (ev == null) {
            String name = BedrockTlumacz.str(b, "name", null);
            net.minecraft.network.chat.Component title = name == null ? m.getDisplayName()
                : name.contains(".") && !name.contains(" ") ? net.minecraft.network.chat.Component.translatable(name) : net.minecraft.network.chat.Component.literal(name);
            ev = new net.minecraft.server.level.ServerBossEvent(m.getUUID(), title, net.minecraft.world.BossEvent.BossBarColor.PURPLE, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
            ev.setDarkenScreen(b.has("should_darken_sky") && b.get("should_darken_sky").getAsBoolean());
            BOSSY.put(m, ev);
        }
        if (m.tickCount % 5 != 0) return;
        ev.setProgress(m.getMaxHealth() > 0 ? Math.max(0f, Math.min(1f, m.getHealth() / m.getMaxHealth())) : 0f);
        double range = b.has("hud_range") ? b.get("hud_range").getAsDouble() : 55;
        for (ServerPlayer p : sl.players()) {
            if (p.distanceToSqr(m) <= range * range) ev.addPlayer(p);
            else ev.removePlayer(p);
        }
    }

    // bosses that died, despawned or unloaded take their bar with them. server tick, cheap when there are none
    public static void sprzatnijBossy() {
        if (BOSSY.isEmpty()) return;
        var it = BOSSY.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().isRemoved() || !e.getKey().isAlive()) { e.getValue().removeAllPlayers(); it.remove(); }
        }
    }

    public static InteractionResult interact(Mob m, Player p, InteractionHand hand) {
        Stan s = stan(m);
        // java's own mobs trade, tame, heal and get milked the java way, a copied vanilla file would do it twice
        if (s == null || s.def.nakladka || m.level().isClientSide()) return null;
        ItemStack held = p.getItemInHand(hand);
        String heldId = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        JsonObject tame = BedrockTlumacz.obj(s.comps, "minecraft:tameable");
        if (tame != null && !held.isEmpty() && itemListed(tame.get("tame_items"), heldId)) {
            if (!p.getAbilities().instabuild) held.shrink(1);
            double chance = tame.has("probability") ? tame.get("probability").getAsDouble() : 1.0;
            if (m.getRandom().nextDouble() < chance && tame.has("tame_event")) {
                fire(m, tame.get("tame_event").getAsJsonObject(), p);
                if (m instanceof net.minecraft.world.entity.TamableAnimal ta) ta.tame(p);
                m.level().broadcastEntityEvent(m, (byte) 7);
            } else m.level().broadcastEntityEvent(m, (byte) 6);
            return InteractionResult.SUCCESS;
        }
        JsonObject heal = BedrockTlumacz.obj(s.comps, "minecraft:healable");
        if (heal != null && heal.has("items") && !held.isEmpty() && m.getHealth() < m.getMaxHealth()) {
            for (JsonElement it : heal.getAsJsonArray("items")) {
                JsonObject o = it.getAsJsonObject();
                if (!itemMatches(BedrockTlumacz.str(o, "item", ""), heldId)) continue;
                m.heal(o.has("heal_amount") ? o.get("heal_amount").getAsFloat() : 1f);
                if (!p.getAbilities().instabuild) held.shrink(1);
                return InteractionResult.SUCCESS;
            }
        }
        JsonObject inter = BedrockTlumacz.obj(s.comps, "minecraft:interact");
        if (inter != null) {
            JsonElement list = inter.has("interactions") ? inter.get("interactions") : inter;
            for (JsonObject i : triggers(list)) {
                JsonObject on = BedrockTlumacz.obj(i, "on_interact");
                if (on == null || !BedrockFiltr.test(on.get("filters"), ctx(m, p))) continue;
                if (i.has("use_item") && i.get("use_item").getAsBoolean() && !p.getAbilities().instabuild) held.shrink(1);
                if (i.has("hurt_item") && held.isDamageableItem()) held.hurtAndBreak(i.get("hurt_item").getAsInt(), p, hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
                if (i.has("transform_to_item")) {
                    Item it = item(BedrockTlumacz.str(i, "transform_to_item", ""));
                    if (it != null) p.setItemInHand(hand, new ItemStack(it));
                }
                if (i.has("play_sounds")) sound(m, BedrockTlumacz.str(i, "play_sounds", ""));
                if (i.has("swing") && i.get("swing").getAsBoolean()) p.swing(hand, p.getItemInHand(hand).getInteractAnimation(), true);
                fire(m, on, p);
                return InteractionResult.SUCCESS;
            }
        }
        return null;
    }

    // multiplier for the damage, or -1 = no damage at all
    public static float hurt(Mob m, DamageSource src, float amount) {
        Stan s = stan(m);
        if (s == null) return 1f;
        Entity by = src.getEntity();
        String cause = BedrockUszy.cause(src);
        float mult = 1f;
        JsonObject ds = BedrockTlumacz.obj(s.comps, "minecraft:damage_sensor");
        BedrockFiltr.OBRAZENIA.set(new Object[] {cause, amount >= m.getHealth()});
        try {
        if (ds != null) for (JsonObject t : triggers(ds.get("triggers"))) {
            String c = BedrockTlumacz.str(t, "cause", "all");
            if (!c.equals("all") && !c.equals(cause)) continue;
            JsonObject on = BedrockTlumacz.obj(t, "on_damage");
            BedrockFiltr.Kontekst k = new BedrockFiltr.Kontekst(m, by, by, m.getTarget(), null);
            if (on != null && !BedrockFiltr.test(on.get("filters"), k)) continue;
            if (on != null) fire(m, on, by);
            // on java's own mobs the pack only hears about it, how much it hurts stays java's
            if (s.def.nakladka) break;
            if (t.has("damage_multiplier")) mult *= t.get("damage_multiplier").getAsFloat();
            JsonElement deals = t.get("deals_damage");
            if (deals != null && (deals.getAsJsonPrimitive().isBoolean() ? !deals.getAsBoolean() : deals.getAsString().startsWith("no"))) return -1f;
            break;
        }
        } finally {
            BedrockFiltr.OBRAZENIA.remove();
        }
        comp(m, s, "minecraft:on_hurt", by);
        if (by instanceof Player) comp(m, s, "minecraft:on_hurt_by_player", by);
        return mult;
    }

    public static void died(Mob m, DamageSource src) {
        Stan s = stan(m);
        if (s == null) return;
        comp(m, s, "minecraft:on_death", src.getEntity());
    }

    public static void targetChanged(Mob m, LivingEntity before, LivingEntity after) {
        Stan s = stan(m);
        if (s == null) return;
        if (after != null && after != before) comp(m, s, "minecraft:on_target_acquired", after);
        if (after == null && before != null) comp(m, s, "minecraft:on_target_escape", before);
    }

    // "minecraft:on_death": {"event": "...", "target": "self", "filters": {...}}
    private static void comp(Mob m, Stan s, String name, Entity other) {
        JsonObject c = BedrockTlumacz.obj(s.comps, name);
        if (c == null) return;
        if (!BedrockFiltr.test(c.get("filters"), ctx(m, other))) return;
        fire(m, c, other);
    }

    private static BedrockFiltr.Kontekst ctx(Mob m, Entity other) {
        return new BedrockFiltr.Kontekst(m, other, null, m.getTarget(), null);
    }

    // {"event": x, "target": "self"|"other"|...}
    // minecraft:entity_sensor, both shapes: the old one (sensor_range, minimum/maximum_count, event, event_filters)
    // and 1.21's subsensors [{range: [xz, y], minimum_count, maximum_count, event, event_filters, cooldown}].
    // counts the entities around that pass the filters (the mob is self, each one is other) and fires when the
    // count is in range. mowzie's foliaath comes out of the ground and rlcraft's bosses switch phases on it
    private static void entitySensor(Mob m, Stan s) {
        JsonObject es = BedrockTlumacz.obj(s.comps, "minecraft:entity_sensor");
        if (es == null) return;
        List<JsonObject> subs = es.has("subsensors") ? triggers(es.get("subsensors")) : List.of(es);
        for (int i = 0; i < subs.size(); i++) {
            JsonObject sub = subs.get(i);
            int[] cool = s.timers.get("esensor" + i);
            if (cool != null && --cool[0] > 0) continue;
            double xz, y;
            JsonElement range = sub.get("range");
            if (range != null && range.isJsonArray()) {
                xz = range.getAsJsonArray().get(0).getAsDouble();
                y = range.getAsJsonArray().size() > 1 ? range.getAsJsonArray().get(1).getAsDouble() : xz;
            } else {
                xz = y = sub.has("sensor_range") ? sub.get("sensor_range").getAsDouble() : sub.has("range") ? sub.get("range").getAsDouble() : 10;
            }
            int min = sub.has("minimum_count") ? sub.get("minimum_count").getAsInt() : 1;
            int max = sub.has("maximum_count") ? sub.get("maximum_count").getAsInt() : -1;
            JsonElement filters = sub.has("event_filters") ? sub.get("event_filters") : sub.get("filters");
            int n = 0;
            for (Entity o : m.level().getEntities(m, m.getBoundingBox().inflate(xz, y, xz))) {
                if (o.distanceToSqr(m) > xz * xz + y * y || !o.isAlive() || o.isSpectator()) continue;
                if (filters == null || BedrockFiltr.test(filters, ctx(m, o))) n++;
            }
            if (n >= min && (max < 0 || n <= max)) {
                String ev = BedrockTlumacz.str(sub, "event", null);
                if (ev != null) event(m, ev, null);
                float cd = sub.has("cooldown") ? sub.get("cooldown").getAsFloat() : -1;
                if (cd > 0) s.timers.put("esensor" + i, new int[] {Math.max(1, Math.round(cd * 4))});
                if (m.isRemoved()) return;
            }
        }
    }

    // minecraft:target_nearby_sensor: on_inside_range when the target is closer than inside_range, on_outside_range
    // when it's further than outside_range, nothing in between (the hysteresis is the point: foliaath stays out
    // while you're 3..9 blocks away). must_see: only a target the mob can see counts
    private static void targetNearbySensor(Mob m, Stan s) {
        JsonObject ts = BedrockTlumacz.obj(s.comps, "minecraft:target_nearby_sensor");
        if (ts == null) return;
        LivingEntity t = m.getTarget();
        if (t == null || !t.isAlive()) return;
        boolean see = m.getSensing().hasLineOfSight(t);
        double d = m.distanceTo(t);
        double in = ts.has("inside_range") ? ts.get("inside_range").getAsDouble() : 1;
        double out = ts.has("outside_range") ? ts.get("outside_range").getAsDouble() : 5;
        if (ts.has("must_see") && ts.get("must_see").getAsBoolean() && !see) {
            JsonObject lost = BedrockTlumacz.obj(ts, "on_vision_lost_inside_range");
            if (lost != null && d <= in && BedrockFiltr.test(lost.get("filters"), ctx(m, t))) fire(m, lost, t);
            return;
        }
        JsonObject trig = d <= in ? BedrockTlumacz.obj(ts, "on_inside_range") : d > out ? BedrockTlumacz.obj(ts, "on_outside_range") : null;
        if (trig != null && BedrockFiltr.test(trig.get("filters"), ctx(m, t))) fire(m, trig, t);
    }

    private static void fire(Mob m, JsonObject trigger, Entity other) {
        String ev = BedrockTlumacz.str(trigger, "event", null);
        if (ev == null) return;
        String target = BedrockTlumacz.str(trigger, "target", "self");
        Entity who = switch (target) {
            case "other", "player" -> other;
            case "target" -> m.getTarget();
            default -> m;
        };
        if (who != null) event(who, ev, who == m ? other : m);
    }

    private static List<JsonObject> triggers(JsonElement e) {
        List<JsonObject> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isJsonArray()) e.getAsJsonArray().forEach(x -> { if (x.isJsonObject()) out.add(x.getAsJsonObject()); });
        else if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    // ── events ───────────────────────────────────────────────────────────────

    public static void event(Entity e, String name, Entity other) {
        Stan s = stan(e);
        if (s == null) return;
        JsonElement body = s.def.events.get(name);
        if (body == null) return;
        List<String> before = new ArrayList<>(s.groups);
        run(e, s, body, other, 0);
        if (!e.level().isClientSide() && BedrockSkrypciarz.wants("dataDrivenEntityTrigger", false)) {
            List<String> added = new ArrayList<>(s.groups), removed = new ArrayList<>(before);
            added.removeAll(before);
            removed.removeAll(s.groups);
            BedrockUszy.dataDriven(e, name, added, removed);
        }
        if (e instanceof Mob m && s.dirty) apply(m, s);
    }

    private static void run(Entity e, Stan s, JsonElement node, Entity other, int depth) {
        if (depth > 16 || node == null || !node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();
        if (o.has("filters") && !BedrockFiltr.test(o.get("filters"), new BedrockFiltr.Kontekst(e, other, other, e instanceof Mob m ? m.getTarget() : null, null))) return;
        JsonObject add = BedrockTlumacz.obj(o, "add");
        if (add != null && add.has("component_groups")) for (JsonElement g : add.getAsJsonArray("component_groups")) addGroup(e, s, g.getAsString());
        JsonObject rem = BedrockTlumacz.obj(o, "remove");
        if (rem != null && rem.has("component_groups")) for (JsonElement g : rem.getAsJsonArray("component_groups")) removeGroup(e, s, g.getAsString());
        if (o.has("sequence") && o.get("sequence").isJsonArray()) for (JsonElement x : o.getAsJsonArray("sequence")) run(e, s, x, other, depth + 1);
        if (o.has("randomize") && o.get("randomize").isJsonArray()) {
            JsonArray opts = o.getAsJsonArray("randomize");
            double total = 0;
            for (JsonElement x : opts) total += weight(x);
            double pick = e.getRandom().nextDouble() * total;
            for (JsonElement x : opts) {
                pick -= weight(x);
                if (pick <= 0) { run(e, s, x, other, depth + 1); break; }
            }
        }
        JsonObject props = BedrockTlumacz.obj(o, "set_property");
        if (props != null) for (var p : props.entrySet()) setProp(e, s, p.getKey(), evalValue(e, s, p.getValue()));
        if (o.has("trigger")) {
            JsonElement t = o.get("trigger");
            if (t.isJsonPrimitive()) event(e, t.getAsString(), other);
            else if (t.isJsonObject() && e instanceof Mob m) fire(m, t.getAsJsonObject(), other);
        }
        JsonObject qc = BedrockTlumacz.obj(o, "queue_command");
        if (qc != null && qc.has("command")) {
            List<String> cmds = new ArrayList<>();
            JsonElement c = qc.get("command");
            if (c.isJsonArray()) c.getAsJsonArray().forEach(x -> cmds.add(x.getAsString())); else cmds.add(c.getAsString());
            Entity as = "other".equals(BedrockTlumacz.str(qc, "target", "self")) && other != null ? other : e;
            for (String cmd : cmds) runCommand(as, cmd);
        }
        if (o.has("reset_target") && e instanceof Mob m) m.setTarget(null);
        JsonObject ps = BedrockTlumacz.obj(o, "play_sound");
        if (ps != null) sound(e, BedrockTlumacz.str(ps, "sound", ""));
        JsonObject ep = BedrockTlumacz.obj(o, "emit_particle");
        if (ep != null && e.level() instanceof ServerLevel sl) {
            var pkt = new com.koper.koper_lib.api.core.BedrockCzastkaPayload(BedrockTlumacz.str(ep, "particle", ""), e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ());
            for (ServerPlayer p : sl.players()) if (p.distanceToSqr(e) < 128 * 128) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
        }
    }

    private static double weight(JsonElement x) {
        return x.isJsonObject() && x.getAsJsonObject().has("weight") ? x.getAsJsonObject().get("weight").getAsDouble() : 1;
    }

    private static void addGroup(Entity e, Stan s, String g) {
        if (!s.def.groups.containsKey(g)) return;
        s.groups.remove(g);
        s.groups.add(g);
        e.addTag("bcg:" + g);
        s.dirty = true;
    }

    private static void removeGroup(Entity e, Stan s, String g) {
        if (s.groups.remove(g)) {
            e.removeTag("bcg:" + g);
            s.dirty = true;
        }
    }

    private static void setProp(Entity e, Stan s, String name, Object v) {
        if (v == null) return;
        JsonObject def = s.def.properties.get(name);
        if (def != null && v instanceof Double d) {
            String type = BedrockTlumacz.str(def, "type", "int");
            if (type.equals("bool")) v = d != 0;
            else if (type.equals("int")) v = (double) Math.round(d);
            JsonArray range = def.has("range") ? def.getAsJsonArray("range") : null;
            if (range != null && range.size() == 2 && v instanceof Double dd) v = Math.max(range.get(0).getAsDouble(), Math.min(range.get(1).getAsDouble(), dd));
        }
        Object old = s.props.put(name, v);
        if (v.equals(old)) return;
        for (String t : new ArrayList<>(e.entityTags())) if (t.startsWith("bprop:" + name + "=")) e.removeTag(t);
        e.addTag("bprop:" + name + "=" + (v instanceof Double d && d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(v)));
        s.dirty = true;
    }

    // set_property values: plain json values, or molang ("q.property('a:b') + 1", "!q.property('x')", "'open'")
    private static Object evalValue(Entity e, Stan s, JsonElement v) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return v.getAsDouble();
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
        String expr = v.getAsString();
        JsonObject q = new JsonObject();
        for (String key : RustBridge.molangQueries(expr)) {
            JsonElement ans = query(e, s, key);
            if (ans != null) q.add(key, ans);
        }
        JsonObject req = new JsonObject();
        req.addProperty("expr", expr);
        req.add("q", q);
        JsonObject res = RustBridge.molangEval(req);
        if (res == null) return null;
        if (res.has("s")) return res.get("s").getAsString();
        return res.has("n") ? res.get("n").getAsDouble() : null;
    }

    // flag components a query may ask about while the component is simply off: 0 is the right answer, not a gap
    private static final Set<String> FLAG_LIKE = Set.of("is_sheared", "is_saddled", "is_illager_captain", "is_charged",
        "is_tamed", "is_chested", "is_baby", "is_ignited", "is_powered", "is_stunned", "is_shaking", "is_stackable");

    // the handful of queries behavior pack molang reaches for, answered on the server
    private static JsonElement query(Entity e, Stan s, String key) {
        int paren = key.indexOf('(');
        String name = paren < 0 ? key : key.substring(0, paren);
        String arg = paren < 0 ? "" : key.substring(paren + 1, key.length() - 1).replace("'", "");
        LivingEntity le = e instanceof LivingEntity l ? l : null;
        return switch (name) {
            case "property" -> {
                Object v = s.props.get(arg);
                yield v instanceof String str ? new JsonPrimitive(str) : v instanceof Boolean b ? new JsonPrimitive(b ? 1 : 0) : v instanceof Number n ? new JsonPrimitive(n) : null;
            }
            case "has_property" -> new JsonPrimitive(s.props.containsKey(arg) ? 1 : 0);
            case "is_baby" -> new JsonPrimitive(le != null && le.isBaby() ? 1 : 0);
            case "health" -> new JsonPrimitive(le != null ? le.getHealth() : 0);
            case "max_health" -> new JsonPrimitive(le != null ? le.getMaxHealth() : 0);
            case "variant" -> new JsonPrimitive(intState(e, "variant"));
            case "mark_variant" -> new JsonPrimitive(intState(e, "mark_variant"));
            case "skin_id" -> new JsonPrimitive(intState(e, "skin_id"));
            case "is_on_ground" -> new JsonPrimitive(e.onGround() ? 1 : 0);
            case "is_in_water" -> new JsonPrimitive(e.isInWater() ? 1 : 0);
            case "time_of_day" -> new JsonPrimitive((e.level().getOverworldClockTime() % 24000) / 24000.0);
            case "life_time" -> new JsonPrimitive(e.tickCount / 20.0);
            case "is_in_water_or_rain" -> new JsonPrimitive(e.isInWaterOrRain() ? 1 : 0);
            case "is_in_lava" -> new JsonPrimitive(e.isInLava() ? 1 : 0);
            case "is_underwater" -> new JsonPrimitive(e.isUnderWater() ? 1 : 0);
            case "is_alive" -> new JsonPrimitive(e.isAlive() ? 1 : 0);
            case "is_on_fire" -> new JsonPrimitive(e.isOnFire() ? 1 : 0);
            case "is_sneaking" -> new JsonPrimitive(e.isShiftKeyDown() ? 1 : 0);
            case "is_sprinting" -> new JsonPrimitive(e.isSprinting() ? 1 : 0);
            case "is_swimming" -> new JsonPrimitive(e.isSwimming() ? 1 : 0);
            case "is_moving" -> new JsonPrimitive(e.getDeltaMovement().horizontalDistanceSqr() > 1e-4 ? 1 : 0);
            case "has_rider" -> new JsonPrimitive(e.isVehicle() ? 1 : 0);
            case "is_riding" -> new JsonPrimitive(e.isPassenger() ? 1 : 0);
            case "has_target" -> new JsonPrimitive(e instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive() ? 1 : 0);
            case "target_distance", "distance_to_target" -> new JsonPrimitive(e instanceof Mob mob && mob.getTarget() != null ? mob.distanceTo(mob.getTarget()) : 0);
            case "is_sleeping" -> new JsonPrimitive(le != null && le.isSleeping() ? 1 : 0);
            case "is_using_item" -> new JsonPrimitive(le != null && le.isUsingItem() ? 1 : 0);
            case "ground_speed" -> new JsonPrimitive(e.getDeltaMovement().horizontalDistance() * 20);
            case "vertical_speed" -> new JsonPrimitive(e.getDeltaMovement().y * 20);
            case "is_tamed" -> new JsonPrimitive(e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame() ? 1 : hasComponent(e, "is_tamed") ? 1 : 0);
            case "is_sheared" -> new JsonPrimitive(hasComponent(e, "is_sheared") ? 1 : 0);
            case "is_charged" -> new JsonPrimitive(hasComponent(e, "is_charged") ? 1 : 0);
            case "is_saddled" -> new JsonPrimitive(hasComponent(e, "is_saddled") ? 1 : 0);
            case "is_angry" -> new JsonPrimitive(e instanceof Mob mob && mob.getTarget() != null ? 1 : 0);
            case "day" -> new JsonPrimitive(e.level().getOverworldClockTime() / 24000);
            case "moon_phase" -> new JsonPrimitive((int) ((e.level().getOverworldClockTime() / 24000) % 8));
            case "is_day", "is_daytime" -> new JsonPrimitive((e.level().getOverworldClockTime() % 24000) < 12000 ? 1 : 0);
            case "position" -> new JsonPrimitive(arg.equals("1") ? e.getY() : arg.equals("2") ? e.getZ() : e.getX());
            case "has_any_family", "is_family" -> new JsonPrimitive(families(e).contains(arg) ? 1 : 0);
            case "scoreboard" -> {
                var sb = e.level().getServer() == null ? null : e.level().getServer().getScoreboard();
                var obj = sb == null ? null : sb.getObjective(arg);
                yield new JsonPrimitive(obj == null ? 0 : sb.getOrCreatePlayerScore(e, obj).get());
            }
            // mowzie's ferrous steps its whole attack chain on these in its bp controllers: stomp -> stuck -> open
            case "is_delayed_attacking" -> new JsonPrimitive(e instanceof com.koper.koper_lib.api.core.KoperDelayedAttacker d && d.koperDelayedAttacking() ? 1 : 0);
            case "is_levitating" -> new JsonPrimitive(le != null && le.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION) ? 1 : 0);
            case "has_any_effect" -> new JsonPrimitive(le != null && !le.getActiveEffects().isEmpty() ? 1 : 0);
            case "is_attacking" -> new JsonPrimitive(e instanceof Mob mob && mob.isAggressive() ? 1 : 0);
            case "is_jumping" -> new JsonPrimitive(!e.onGround() && e.getDeltaMovement().y > 0 ? 1 : 0);
            case "is_interested", "is_roaring", "is_stunned" -> new JsonPrimitive(hasComponent(e, "is_" + name.substring(3)) ? 1 : 0);
            case "modified_move_speed" -> new JsonPrimitive(le != null ? le.getSpeed() : 0);
            case "body_y_rotation" -> new JsonPrimitive(le != null ? le.yBodyRot : e.getYRot());
            case "skin_id_any", "mark_variant_any" -> null;
            default -> {
                // is_sheared, is_saddled, is_illager_captain, is_charged...: bedrock's flag components
                if (name.startsWith("is_") && s.comps.has("minecraft:" + name)) yield new JsonPrimitive(1);
                if (name.startsWith("is_") && FLAG_LIKE.contains(name)) yield new JsonPrimitive(0);
                once("behavior pack molang q." + name + " has no server side answer yet, it reads 0 and whatever waits on it never happens");
                yield null;
            }
        };
    }

    // ── applying components to the java mob ──────────────────────────────────

    private static void apply(Mob m, Stan s) {
        s.dirty = false;
        JsonObject comps = new JsonObject();
        s.def.base.entrySet().forEach(e -> comps.add(e.getKey(), e.getValue()));
        for (String g : s.groups) {
            JsonObject grp = s.def.groups.get(g);
            if (grp != null) grp.entrySet().forEach(e -> comps.add(e.getKey(), e.getValue()));
        }
        JsonObject before = s.comps;
        s.comps = comps;
        if (s.def.nakladka) {
            sync(m, s, null);
            return;
        }

        if (m instanceof com.koper.koper_lib.factory.EntityFactory.KoperMobEntity km) {
            JsonObject box = BedrockTlumacz.obj(comps, "minecraft:collision_box");
            float bw = box == null ? -1f : box.has("width") ? box.get("width").getAsFloat() : 0.6f;
            float bh = box == null ? -1f : box.has("height") ? box.get("height").getAsFloat() : 1.8f;
            // custom_hit_test: where bedrock lets you click / hit the mob, often far bigger than its collision box
            // (rlcraft's black room guide: 0.5 collision, 4x6 to click, and the tutorial starts on that click).
            // java has one box for both, so the bigger one wins
            JsonObject hit = BedrockTlumacz.obj(comps, "minecraft:custom_hit_test");
            if (hit != null && hit.get("hitboxes") instanceof JsonArray boxes) {
                for (JsonElement hb : boxes) {
                    if (!hb.isJsonObject()) continue;
                    JsonObject h = hb.getAsJsonObject();
                    bw = Math.max(bw, h.has("width") ? h.get("width").getAsFloat() : 0f);
                    bh = Math.max(bh, h.has("height") ? h.get("height").getAsFloat() : 0f);
                }
            }
            km.setBox(bw, bh);
        }

        JsonObject hp = BedrockTlumacz.obj(comps, "minecraft:health");
        if (hp != null) {
            float max = num(hp.has("max") ? hp.get("max") : hp.get("value"), m.getMaxHealth());
            attr(m, Attributes.MAX_HEALTH, max);
            if (!before.has("minecraft:health") || !before.get("minecraft:health").equals(hp)) m.setHealth(num(hp.get("value"), max));
        }
        JsonObject mv = BedrockTlumacz.obj(comps, "minecraft:movement");
        if (mv != null) attr(m, Attributes.MOVEMENT_SPEED, num(mv.get("value"), 0.25f));
        JsonObject att = BedrockTlumacz.obj(comps, "minecraft:attack");
        if (att != null && att.has("damage")) {
            JsonElement d = att.get("damage");
            attr(m, Attributes.ATTACK_DAMAGE, d.isJsonArray() ? (d.getAsJsonArray().get(0).getAsFloat() + d.getAsJsonArray().get(1).getAsFloat()) / 2 : num(d, 1));
        }
        JsonObject sc = BedrockTlumacz.obj(comps, "minecraft:scale");
        attr(m, Attributes.SCALE, sc != null ? num(sc.get("value"), 1) : 1);
        JsonObject kb = BedrockTlumacz.obj(comps, "minecraft:knockback_resistance");
        if (kb != null) attr(m, Attributes.KNOCKBACK_RESISTANCE, num(kb.get("value"), 0));
        JsonObject fr = BedrockTlumacz.obj(comps, "minecraft:follow_range");
        if (fr != null) attr(m, Attributes.FOLLOW_RANGE, num(fr.has("value") ? fr.get("value") : fr.get("max"), 16));
        m.setBaby(comps.has("minecraft:is_baby"));
        if (comps.has("minecraft:persistent")) m.setPersistenceRequired();
        JsonObject fuse = BedrockTlumacz.obj(comps, "minecraft:explode");
        if (fuse != null && !s.timers.containsKey("explode") && (!fuse.has("fuse_lit") || fuse.get("fuse_lit").getAsBoolean()))
            s.timers.put("explode", new int[] {ticks(fuse.get("fuse_length"), 1.5f, m)});
        JsonObject tr = BedrockTlumacz.obj(comps, "minecraft:transformation");
        if (tr != null && !s.timers.containsKey("transform")) {
            JsonElement delay = tr.get("delay");
            float secs = delay == null ? 0 : delay.isJsonObject() ? num(delay.getAsJsonObject().get("value"), 0) : num(delay, 0);
            s.timers.put("transform", new int[] {Math.max(1, Math.round(secs * 20))});
        }

        if (m instanceof com.koper.koper_lib.factory.EntityFactory.KoperMobEntity km) {
            List<String> goals = goals(comps);
            if (!goals.equals(s.goals)) {
                s.goals = goals;
                km.rebuildGoals(goals);
            }
        }
        sync(m, s, null);
    }

    private static void attr(Mob m, Holder<Attribute> a, float v) {
        var inst = m.getAttribute(a);
        if (inst == null) {
            once(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()) + " has no " + a.getRegisteredName() + " attribute, its pack value " + v + " is lost");
            return;
        }
        if (inst.getBaseValue() != v) inst.setBaseValue(v);
    }

    private static float num(JsonElement e, float def) {
        if (e == null) return def;
        if (e.isJsonPrimitive()) return e.getAsFloat();
        if (e.isJsonObject() && e.getAsJsonObject().has("value")) return num(e.getAsJsonObject().get("value"), def);
        if (e.isJsonObject() && e.getAsJsonObject().has("range_max")) return e.getAsJsonObject().get("range_max").getAsFloat();
        return def;
    }

    private static int ticks(JsonElement secs, float def, Entity e) {
        if (secs != null && secs.isJsonArray() && secs.getAsJsonArray().size() == 2) {
            float a = secs.getAsJsonArray().get(0).getAsFloat(), b = secs.getAsJsonArray().get(1).getAsFloat();
            return Math.max(1, Math.round((a + e.getRandom().nextFloat() * (b - a)) * 20));
        }
        return Math.max(1, Math.round(num(secs, def) * 20));
    }

    // entity_types of the mob's current nearest_attackable_target (prioritized one too), for BedrockCelownik
    public static List<JsonObject> celTypy(Mob m) {
        Stan s = stan(m);
        List<JsonObject> out = new ArrayList<>();
        if (s == null) return out;
        for (String k : List.of("minecraft:behavior.nearest_attackable_target", "minecraft:behavior.nearest_prioritized_attackable_target")) {
            JsonObject t = BedrockTlumacz.obj(s.comps, k);
            if (t != null) out.addAll(triggers(t.get("entity_types")));
        }
        return out;
    }

    // a script shot this projectile: fly with the new delta movement from the next tick on
    public static void wystrzel(Entity e) {
        Stan s = stan(e);
        if (s != null) s.lot = null;
    }

    // minecraft:shooter the mob has right now
    public static JsonObject strzelba(Mob m) {
        Stan s = stan(m);
        return s == null ? null : BedrockTlumacz.obj(s.comps, "minecraft:shooter");
    }

    public static void dzwiek(Entity e, String name) {
        sound(e, name);
    }

    // item list of tempt / breed style components -> "a~b,c~d" (ids carry ':' which splits goal args)
    private static String items(JsonElement list) {
        List<String> out = new ArrayList<>();
        if (list != null && list.isJsonArray()) for (JsonElement x : list.getAsJsonArray()) {
            String id = x.isJsonPrimitive() ? x.getAsString() : x.isJsonObject() ? BedrockTlumacz.str(x.getAsJsonObject(), "item", "") : "";
            if (!id.isEmpty()) out.add((id.contains(":") ? id : "minecraft:" + id).replace(':', '~'));
        }
        return String.join(",", out);
    }

    private static double d(JsonObject o, String k, double def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    // behavior.* components -> the koper goal names KoperMobEntity understands
    static List<String> goals(JsonObject c) {
        List<String> ai = new ArrayList<>();
        if (c.has("minecraft:behavior.float")) ai.add("swim");
        JsonObject ra = BedrockTlumacz.obj(c, "minecraft:behavior.ranged_attack");
        if (ra != null) {
            double lo = d(ra, "attack_interval_min", d(ra, "attack_interval", 1)), hi = d(ra, "attack_interval_max", lo);
            ai.add("ranged:" + d(ra, "speed_multiplier", 1) + ":" + Math.max(1, Math.round((lo + hi) / 2 * 20)) + ":" + d(ra, "attack_radius", 15));
        }
        JsonObject leap = BedrockTlumacz.obj(c, "minecraft:behavior.leap_at_target");
        if (leap != null) ai.add("leap:" + d(leap, "yd", 0.4));
        // each with its own numbers: the delayed attack's speed_multiplier was read from melee_attack (absent = 1.0),
        // so mowzie's ferrous at 0.4 ran 2.5x too fast, and its wind up / stand still swing never happened
        JsonObject da = BedrockTlumacz.obj(c, "minecraft:behavior.delayed_attack");
        JsonObject ma = BedrockTlumacz.obj(c, "minecraft:behavior.melee_attack");
        if (ma == null) ma = BedrockTlumacz.obj(c, "minecraft:behavior.melee_box_attack");
        if (da != null) {
            ai.add("attack_delayed:" + d(da, "speed_multiplier", 1) + ":" + d(da, "attack_duration", 0.75) + ":" + d(da, "hit_delay_pct", 0.5)
                + ":" + d(da, "reach_multiplier", 1) + ":" + (da.has("attack_once") && da.get("attack_once").getAsBoolean())
                + ":" + (!da.has("track_target") || da.get("track_target").getAsBoolean()));
        } else if (ma != null) {
            if (ma.has("reach_multiplier") && ma.get("reach_multiplier").getAsDouble() <= 0) ai.add("chase_only:" + d(ma, "speed_multiplier", 1));
            else ai.add("attack_melee:" + d(ma, "speed_multiplier", 1));
        }
        // panic is running around when hurt, not fleeing players
        JsonObject panic = BedrockTlumacz.obj(c, "minecraft:behavior.panic");
        if (panic != null) ai.add("panic:" + d(panic, "speed_multiplier", 1.25));
        JsonObject tempt = BedrockTlumacz.obj(c, "minecraft:behavior.tempt");
        if (tempt != null && !items(tempt.get("items")).isEmpty()) ai.add("tempt:" + d(tempt, "speed_multiplier", 1) + ":" + items(tempt.get("items")));
        if (c.has("minecraft:behavior.restrict_sun")) ai.add("restrict_sun");
        JsonObject sun = BedrockTlumacz.obj(c, "minecraft:behavior.flee_sun");
        if (sun != null) ai.add("flee_sun:" + d(sun, "speed_multiplier", 1));
        if (c.has("minecraft:behavior.avoid_mob_type")) ai.add("flee_player:8");
        JsonObject swim = BedrockTlumacz.obj(c, "minecraft:behavior.random_swim");
        if (swim != null) ai.add("swim_random:" + d(swim, "speed_multiplier", 1));
        if (c.has("minecraft:behavior.random_stroll") || c.has("minecraft:behavior.random_fly")) {
            JsonObject rs = BedrockTlumacz.obj(c, "minecraft:behavior.random_stroll");
            ai.add("wander:" + (rs != null && rs.has("speed_multiplier") ? rs.get("speed_multiplier").getAsDouble() : 0.8));
        }
        if (c.has("minecraft:behavior.look_at_player")) {
            JsonObject lp = BedrockTlumacz.obj(c, "minecraft:behavior.look_at_player");
            ai.add("look_at_player:" + (lp != null && lp.has("look_distance") ? lp.get("look_distance").getAsDouble() : 8));
        }
        if (c.has("minecraft:behavior.random_look_around")) ai.add("look_around");
        if (c.has("minecraft:behavior.hurt_by_target")) ai.add("revenge");
        JsonObject nat = BedrockTlumacz.obj(c, "minecraft:behavior.nearest_attackable_target");
        if (nat == null) nat = BedrockTlumacz.obj(c, "minecraft:behavior.nearest_prioritized_attackable_target");
        if (nat != null) {
            boolean see = !nat.has("must_see") || nat.get("must_see").getAsBoolean();
            // with entity_types: whatever its filters say (villagers, sheep, other families); without: players
            ai.add(nat.has("entity_types") ? "target_bedrock:" + see : "target_nearest_player:" + see);
        }
        return ai;
    }

    private static void timers(Mob m, Stan s) {
        // minecraft:timer
        JsonObject t = BedrockTlumacz.obj(s.comps, "minecraft:timer");
        if (t != null) {
            int[] left = s.timers.computeIfAbsent("timer", k -> new int[] {timerLength(t, m)});
            if (--left[0] <= 0) {
                JsonObject down = BedrockTlumacz.obj(t, "time_down_event");
                if (down != null) fire(m, down, null);
                if (!t.has("looping") || t.get("looping").getAsBoolean()) left[0] = timerLength(t, m);
                else left[0] = Integer.MAX_VALUE;
            }
        } else s.timers.remove("timer");
        // minecraft:behavior.emerge: coming up out of the ground for duration seconds, then on_done. the pose is
        // what the client reads as q.is_emerging. rlcraft's tree spirit spawns in this state with a 0x0 hitbox and
        // only gets its body, its moves and its damage after on_done: without this it stayed half buried forever,
        // unhittable, replaying its emerge animation
        JsonObject emerge = BedrockTlumacz.obj(s.comps, "minecraft:behavior.emerge");
        if (emerge != null) {
            int[] left = s.timers.computeIfAbsent("emerge", k -> new int[] {Math.max(1, Math.round(num(emerge.get("duration"), 5) * 20))});
            if (m.getPose() != net.minecraft.world.entity.Pose.EMERGING) m.setPose(net.minecraft.world.entity.Pose.EMERGING);
            if (--left[0] <= 0) {
                left[0] = Integer.MAX_VALUE;
                m.setPose(net.minecraft.world.entity.Pose.STANDING);
                JsonObject done = BedrockTlumacz.obj(emerge, "on_done");
                if (done != null) fire(m, done, null);
            }
        } else if (s.timers.remove("emerge") != null && m.getPose() == net.minecraft.world.entity.Pose.EMERGING) {
            m.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
        // minecraft:ageable grows the baby up
        // packs use ageable as a plain clock on grown mobs too (mowzie's bosses switch attacks with it)
        JsonObject age = BedrockTlumacz.obj(s.comps, "minecraft:ageable");
        if (age != null) {
            int[] left = s.timers.computeIfAbsent("ageable", k -> new int[] {Math.round(num(age.get("duration"), 1200) * 20)});
            if (--left[0] == 0) {
                // once per time the component is on, like growing up happens once
                left[0] = Integer.MIN_VALUE;
                JsonObject grow = BedrockTlumacz.obj(age, "grow_up");
                if (grow != null) fire(m, grow, null);
            }
        } else s.timers.remove("ageable");
        // minecraft:spawn_entity drops things now and then (eggs and friends)
        JsonObject spawn = BedrockTlumacz.obj(s.comps, "minecraft:spawn_entity");
        if (spawn != null) {
            List<JsonObject> list = triggers(spawn.has("entities") ? spawn.get("entities") : spawn);
            for (int i = 0; i < list.size(); i++) {
                JsonObject o = list.get(i);
                int[] left = s.timers.computeIfAbsent("spawn" + i, k -> new int[] {between(o, "min_wait_time", "max_wait_time", 300, 600, m)});
                if (left[0] < 0 || m.entityTags().contains("bsingle" + i)) continue; // single_use and already done
                if (--left[0] > 0) continue;
                left[0] = between(o, "min_wait_time", "max_wait_time", 300, 600, m);
                if (!BedrockFiltr.test(o.get("filters"), ctx(m, null))) continue;
                int count = o.has("num_to_spawn") ? Math.max(1, o.get("num_to_spawn").getAsInt()) : 1;
                for (int n = 0; n < count; n++) {
                    if (o.has("spawn_item")) {
                        Item it = item(BedrockTlumacz.str(o, "spawn_item", ""));
                        if (it != null) m.spawnAtLocation((ServerLevel) m.level(), new ItemStack(it));
                    } else if (o.has("spawn_entity")) {
                        String ev = BedrockTlumacz.str(o, "spawn_event", null);
                        spawnInto(m, BedrockTlumacz.str(o, "spawn_entity", "") + (ev != null ? "<" + ev + ">" : ""), false);
                    }
                }
                // single_use: once in this mob's life, kept in a tag so a chunk reload doesn't hatch them again
                if (o.has("single_use") && o.get("single_use").getAsBoolean()) {
                    left[0] = -1;
                    m.addTag("bsingle" + i);
                }
            }
        }
        int[] boom = s.timers.get("explode");
        if (boom != null && --boom[0] <= 0) {
            JsonObject ex = BedrockTlumacz.obj(s.comps, "minecraft:explode");
            s.timers.remove("explode");
            if (ex != null && m.level() instanceof ServerLevel sl) {
                boolean breaks = !ex.has("breaks_blocks") || ex.get("breaks_blocks").getAsBoolean();
                sl.explode(m, m.getX(), m.getY(), m.getZ(), num(ex.get("power"), 3), ex.has("causes_fire") && ex.get("causes_fire").getAsBoolean(),
                    breaks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
                m.discard();
                return;
            }
        }
        int[] tr = s.timers.get("transform");
        if (tr != null && --tr[0] <= 0) {
            s.timers.remove("transform");
            JsonObject t2 = BedrockTlumacz.obj(s.comps, "minecraft:transformation");
            // bedrock gives the new mob minecraft:entity_transformed, not entity_spawned. mowzie's dying foliaath
            // turns into a death animation that only starts its 1.7s fuse on entity_transformed: without it every
            // kill left an unkillable foliaath standing forever
            String into = BedrockTlumacz.str(t2, "into", "");
            if (t2 != null) spawnInto(m, into.contains("<") ? into : into + "<minecraft:entity_transformed>", true);
        }
    }

    // overlay: minecraft:timer is the pack's own clock (a villager deciding to gossip), the rest of timers()
    // (growing up, laying eggs, fuses) java already does for its mobs
    private static void timerOnly(Mob m, Stan s) {
        JsonObject t = BedrockTlumacz.obj(s.comps, "minecraft:timer");
        if (t == null) { s.timers.remove("timer"); return; }
        int[] left = s.timers.computeIfAbsent("timer", k -> new int[] {timerLength(t, m)});
        if (--left[0] <= 0) {
            JsonObject down = BedrockTlumacz.obj(t, "time_down_event");
            if (down != null) fire(m, down, null);
            if (!t.has("looping") || t.get("looping").getAsBoolean()) left[0] = timerLength(t, m);
            else left[0] = Integer.MAX_VALUE;
        }
    }

    // java's own mob with a pack definition: the hooks KoperMobEntity calls itself, from the mixins
    public static boolean nakladka(Entity e) {
        if (DEFS.isEmpty() || e instanceof com.koper.koper_lib.factory.EntityFactory.KoperMobEntity) return false;
        Def d = def(e);
        return d != null && d.nakladka && e instanceof Mob && !e.level().isClientSide();
    }

    private static int timerLength(JsonObject t, Mob m) {
        if (t.has("random_time_choices") && t.get("random_time_choices").isJsonArray()) {
            JsonArray ch = t.getAsJsonArray("random_time_choices");
            double total = 0;
            for (JsonElement x : ch) total += weight(x);
            double pick = m.getRandom().nextDouble() * total;
            for (JsonElement x : ch) {
                pick -= weight(x);
                if (pick <= 0) return Math.max(1, Math.round(num(x.getAsJsonObject().get("value"), 1) * 20));
            }
        }
        return ticks(t.get("time"), 1, m);
    }

    private static int between(JsonObject o, String lo, String hi, int a, int b, Mob m) {
        int min = o.has(lo) ? o.get(lo).getAsInt() : a, max = o.has(hi) ? o.get(hi).getAsInt() : b;
        return Math.max(1, (min + m.getRandom().nextInt(Math.max(1, max - min + 1))) * 20);
    }

    // "ns:thing<ns:event>" spawns thing and fires the event on it
    private static void spawnInto(Mob m, String spec, boolean replace) {
        if (!(m.level() instanceof ServerLevel sl) || spec.isBlank()) return;
        String id = spec, ev = null;
        int lt = spec.indexOf('<');
        if (lt > 0) { id = spec.substring(0, lt); ev = spec.substring(lt + 1, spec.length() - 1); }
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        EntityType<?> type = rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        if (type == null) return;
        Entity n = type.create(sl, EntitySpawnReason.CONVERSION);
        if (n == null) return;
        n.snapTo(m.getX(), m.getY(), m.getZ(), m.getYRot(), m.getXRot());
        if (m.hasCustomName()) n.setCustomName(m.getCustomName());
        if (ev != null && ma(n)) overrideSpawnEvent(n, ev);
        sl.addFreshEntity(n);
        // java's own mobs have no entity_spawned of ours to replace, they just get the event
        if (ev != null && !ma(n)) event(n, ev, m);
        if (replace) m.discard();
    }

    private static boolean itemListed(JsonElement list, String held) {
        if (list == null) return false;
        if (list.isJsonPrimitive()) return itemMatches(list.getAsString(), held);
        for (JsonElement x : list.getAsJsonArray()) if (itemMatches(x.getAsString(), held)) return true;
        return false;
    }

    private static boolean itemMatches(String want, String held) {
        return held.equals(want) || held.equals("minecraft:" + want);
    }

    private static Item item(String id) {
        return BedrockPytajnik.item(id);
    }

    private static void sound(Entity e, String name) {
        if (name.isBlank()) return;
        // bedrock's vanilla name (mob.zombie.say) becomes java's, same table as /playsound
        Identifier id = Identifier.tryParse(BedrockSkladnia.sound(name.contains(":") ? name : name.toLowerCase(Locale.ROOT)).toLowerCase(Locale.ROOT));
        if (id == null) return;
        e.level().playSound(null, e.getX(), e.getY(), e.getZ(), Holder.direct(SoundEvent.createVariableRangeEvent(id)), SoundSource.NEUTRAL, 1f, 1f);
    }

    private static void runCommand(Entity as, String cmd) {
        var server = as.level().getServer();
        if (server == null) return;
        var src = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS)
            .withEntity(as).withPosition(as.position()).withLevel((ServerLevel) as.level());
        Def d = def(as);
        BedrockSkladnia.PACK_NAMESPACE.set(d != null ? d.ns : null);
        try {
            BedrockKomendy.runQuietly(server, src, BedrockKomendy.przetlumacz(cmd));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            // a command we could not turn into java's is our bug, say it. "no entity was found" and the like
            // bedrock fails quietly too
            if (BedrockKomendy.isUnparseable(bad)) once("queue_command '" + cmd + "' does not parse: " + bad.getMessage());
        } catch (Exception bad) {
            once("queue_command '" + cmd + "' failed: " + bad);
        } finally {
            BedrockSkladnia.PACK_NAMESPACE.remove();
        }
    }

    // ── client sync ──────────────────────────────────────────────────────────

    private static final String[] FLAGS = {"is_tamed", "is_saddled", "is_chested", "is_sheared", "is_charged", "is_baby",
        "is_illager_captain", "is_stunned", "is_ignited", "is_powered", "can_climb", "can_fly", "can_power_jump", "is_shaking", "is_stackable"};

    private static String stateJson(Mob m, Stan s) {
        JsonObject o = new JsonObject();
        // java's villagers: job, biome and face are the villager's own, the client asks java for those
        if (!(s.def.nakladka && com.koper.koper_lib.api.core.BedrockWiesniak.jest(m))) {
            o.addProperty("variant", num(s.comps.get("minecraft:variant"), 0));
            o.addProperty("mark_variant", num(s.comps.get("minecraft:mark_variant"), 0));
            o.addProperty("skin_id", num(s.comps.get("minecraft:skin_id"), 0));
        }
        JsonArray flags = new JsonArray();
        for (String f : FLAGS) if (s.comps.has("minecraft:" + f)) flags.add(f);
        o.add("flags", flags);
        JsonArray fam = new JsonArray();
        JsonObject tf = BedrockTlumacz.obj(s.comps, "minecraft:type_family");
        if (tf != null && tf.has("family")) tf.getAsJsonArray("family").forEach(fam::add);
        o.add("family", fam);
        JsonObject props = new JsonObject();
        s.props.forEach((k, v) -> {
            if (v instanceof String str) props.addProperty(k, str);
            else if (v instanceof Boolean b) props.addProperty(k, b);
            else if (v instanceof Number n) props.addProperty(k, n);
        });
        o.add("props", props);
        return o.toString();
    }

    private static void sync(Mob m, Stan s, ServerPlayer only) {
        if (!(m.level() instanceof ServerLevel sl)) return;
        String json = stateJson(m, s);
        if (only == null && json.equals(s.lastSync)) return;
        if (only == null) s.lastSync = json;
        var pkt = new com.koper.koper_lib.api.core.BedrockStanPayload(m.getId(), json);
        if (only != null) { com.koper.koper_lib.api.core.KoperNetwork.send(only, pkt); return; }
        for (ServerPlayer p : sl.players()) if (p.distanceToSqr(m) < 160 * 160) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
    }

    public static void seenBy(Mob m, ServerPlayer p) {
        Stan s = stan(m);
        if (s != null) sync(m, s, p);
    }
}
