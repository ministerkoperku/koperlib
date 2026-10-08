package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.panama.RustBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// one quickjs vm per converted addon that ships scripts. lives from server start (or reload)
// to server stop. everything java wants to tell the scripts goes out through here
public final class BedrockSkrypciarz {

    // hello person reading this, yes it is all static, there is one server and one server thread
    static final class Addon {
        final String folder;
        final String ns;
        final Path root;
        final JsonObject sidecar;
        long vm;
        final Set<String> after = new HashSet<>();
        final Set<String> before = new HashSet<>();
        final Map<String, JsonElement> itemComps = new HashMap<>();
        final Map<String, JsonElement> blockComps = new HashMap<>();
        boolean dead;
        // inside a call right now: quickjs is not re-entrant, a second call from in here panics the
        // native side and takes the whole game down (summon a zombie, the spawn event lands mid script)
        boolean wSrodku;
        final java.util.ArrayDeque<String> poZatym = new java.util.ArrayDeque<>();
        boolean krzyknal;

        Addon(String folder, String ns, Path root, JsonObject sidecar) {
            this.folder = folder;
            this.ns = ns;
            this.root = root;
            this.sidecar = sidecar;
        }
    }

    private static final List<Addon> ADDONY = new ArrayList<>();
    // custom component data for every converted addon, scripts or not, keyed by item/block id
    private static final Map<String, JsonElement> ITEM_COMPS = new HashMap<>();
    private static final Map<String, JsonElement> BLOCK_COMPS = new HashMap<>();
    private static final Map<String, JsonElement> PROP_DEFAULTS = new HashMap<>();
    private static final Map<String, JsonElement> FAMILIES = new HashMap<>();
    // item id -> {entity, power, swing}: minecraft:throwable items
    private static final Map<String, JsonElement> THROWABLES = new HashMap<>();
    // pack mob voices from the sidecar ("voices"): entity id (or "*") -> event -> {s, v, p}
    static final Map<String, JsonElement> VOICES = new java.util.concurrent.ConcurrentHashMap<>();
    static Addon teraz;
    private static MinecraftServer server;
    private static int tick;
    // uuids of removed entities, sent with the next tick. without it the js side kept a wrapper for every
    // entity it ever saw and villager news ran its vm out of memory in minutes
    static final java.util.List<String> GONE = new java.util.ArrayList<>();

    static void gone(java.util.UUID id) {
        if (!ADDONY.isEmpty()) GONE.add(id.toString());
    }

    private BedrockSkrypciarz() {}

    public static MinecraftServer server() { return server; }

    static int tick() { return tick; }

    // the upcall has no vm handle, so whoever is inside js right now is who asked
    static Addon teraz() { return teraz; }

    public static boolean anyAddons() { return !ADDONY.isEmpty(); }

    // ── lifecycle ────────────────────────────────────────────────────────────

    public static synchronized void serverStarted(MinecraftServer s) {
        server = s;
        BedrockStruktury.indeks();
        restart();
    }

    public static synchronized void serverStopping() {
        for (Addon a : ADDONY) if (a.vm != 0 && !a.dead) call(a, "{\"t\":\"shutdown\"}");
        zabij();
        BedrockStruktury.wyczysc();
        BedrockFormy.clear();
        BedrockStrefy.wyczysc();
        server = null;
    }

    // after /koperlib reload the scripts start again from zero, same as /reload does on bedrock
    public static synchronized void afterContentReload() {
        readSidecars();
        BedrockStruktury.indeks();
        if (server != null) restart();
    }

    private static void zabij() {
        for (Addon a : ADDONY) if (a.vm != 0) RustBridge.jsDestroy(a.vm);
        ADDONY.clear();
        AFTER.clear();
        JavaHookRegistryEar.off();
    }

    private static void readSidecars() {
        BedrockZachowanie.reload();
        BedrockRozsiewacz.reload();
        ITEM_COMPS.clear();
        BLOCK_COMPS.clear();
        PROP_DEFAULTS.clear();
        FAMILIES.clear();
        THROWABLES.clear();
        VOICES.clear();
        for (File pack : FullPackLoader.getEnabledPackDirs()) {
            JsonObject side = BedrockTlumacz.czytajObj(pack.toPath().resolve("bedrock.koper.json"));
            if (side == null) continue;
            copyInto(side, "items", ITEM_COMPS);
            copyInto(side, "blocks", BLOCK_COMPS);
            copyInto(side, "properties", PROP_DEFAULTS);
            copyInto(side, "families", FAMILIES);
            copyInto(side, "throwables", THROWABLES);
            copyInto(side, "voices", VOICES);
        }
        if (!ITEM_COMPS.isEmpty() || !BLOCK_COMPS.isEmpty()) JavaHookRegistryEar.on();
    }

    private static void copyInto(JsonObject side, String key, Map<String, JsonElement> into) {
        JsonObject o = BedrockTlumacz.obj(side, key);
        if (o != null) o.entrySet().forEach(e -> into.put(e.getKey(), e.getValue()));
    }

    private static void restart() {
        zabij();
        readSidecars();
        if (!RustBridge.isLoaded()) {
            KoperLib.LOGGER.warn("[Bedrock] native engine missing, addon scripts stay off");
            return;
        }
        for (File pack : FullPackLoader.getEnabledPackDirs()) {
            Path root = pack.toPath();
            JsonObject side = BedrockTlumacz.czytajObj(root.resolve("bedrock.koper.json"));
            JsonObject script = BedrockTlumacz.obj(side, "script");
            if (script == null) continue;
            Path entry = root.resolve(script.get("entry").getAsString()).normalize();
            if (!Files.isRegularFile(entry)) {
                // microsoft's samples (and packs straight out of a dev folder) ship only the typescript, bedrock can not run that either
                String ts = entry.getFileName().toString().replaceAll("\\.js$", ".ts");
                if (Files.isRegularFile(entry.resolveSibling(ts)))
                    KoperLib.LOGGER.warn("[Bedrock] {} ships its script as typescript only ({}), build it to javascript first (npm run build in the pack's project), scripts are off", pack.getName(), ts);
                else KoperLib.LOGGER.warn("[Bedrock] {} says its script is {} but that file is not there", pack.getName(), entry);
                continue;
            }
            Addon a = new Addon(pack.getName(), BedrockTlumacz.str(side, "namespace", pack.getName()), root, side);
            long vm = RustBridge.jsCreate(root.resolve("bedrock_scripts").toAbsolutePath().toString());
            if (vm == 0) { KoperLib.LOGGER.error("[Bedrock] quickjs vm for {} did not start", pack.getName()); continue; }
            a.vm = vm;
            RustBridge.jsSetQuery(vm);
            RustBridge.jsSetTimeout(vm, Math.max(50, com.koper.koper_lib.fullpack.config.FullpackConfig.get().scriptTimeoutMs));
            ADDONY.add(a);

            int major = script.has("api_major") ? script.get("api_major").getAsInt() : 1;
            call(a, "{\"t\":\"init\",\"major\":" + major + ",\"tick\":" + tick + "}");
            teraz = a;
            boolean ok;
            try {
                ok = RustBridge.jsLoad(vm, entry.toAbsolutePath().toString());
            } finally {
                teraz = null;
            }
            if (!ok) KoperLib.LOGGER.error("[Bedrock] {} main script did not load, see the error above", pack.getName());
            call(a, "{\"t\":\"startup\"}");
            KoperLib.LOGGER.info("[Bedrock] scripts of {} running (api {}.x)", pack.getName(), major);
        }
        for (Addon a : ADDONY) call(a, "{\"t\":\"load\"}");
        if (server != null) BedrockKomendy.resendTree(server);
        // players already in the world at a reload: bedrock scripts expect a spawn for each
        if (server != null) for (var p : server.getPlayerList().getPlayers()) {
            JsonObject d = new JsonObject();
            d.add("player", BedrockPytajnik.entRef(p));
            d.addProperty("initialSpawn", false);
            fireAfter("playerSpawn", d);
        }
    }

    // ── calls ────────────────────────────────────────────────────────────────

    static String call(Addon a, String json) {
        if (a.vm == 0 || a.dead) return "";
        // quickjs is single threaded and so are the scripts' assumptions. netty threads get nothing
        if (server != null && !server.isSameThread()) return "";
        if (a.wSrodku) {
            // after-events wait until the script that caused them returns, like bedrock delivers them.
            // a before-event can't wait (its answer is needed now), it goes through uncancelled
            boolean before = json.contains("\"b\":true");
            if (before && !a.krzyknal) {
                a.krzyknal = true;
                KoperLib.LOGGER.error("[Bedrock] {} caused a before-event from inside its own script, it can not cancel that one: {}",
                    a.folder, json.length() > 160 ? json.substring(0, 160) : json);
            }
            if (!before) a.poZatym.add(json);
            return "";
        }
        String answer = raz(a, json);
        // what the script set off while it ran, one after another. bounded: a script that spawns on
        // every spawn would go on forever
        for (int n = 0; n < 4096 && !a.poZatym.isEmpty() && !a.dead; n++) raz(a, a.poZatym.poll());
        if (!a.poZatym.isEmpty()) {
            KoperLib.LOGGER.error("[Bedrock] {} keeps causing events from its own events, dropped {} of them", a.folder, a.poZatym.size());
            a.poZatym.clear();
        }
        return answer;
    }

    private static String raz(Addon a, String json) {
        Addon was = teraz;
        teraz = a;
        a.wSrodku = true;
        try {
            return RustBridge.jsCall(a.vm, json);
        } catch (Throwable t) {
            KoperLib.LOGGER.error("[Bedrock] {} call blew up, turning its scripts off", a.folder, t);
            a.dead = true;
            return "";
        } finally {
            a.wSrodku = false;
            teraz = was;
        }
    }

    public static synchronized void tick(MinecraftServer s) {
        tick++;
        // this tick's after-events first, while the mobs they are about (removed this tick) still resolve
        if (!ADDONY.isEmpty()) deliverAfter();
        else AFTER.clear();
        BedrockPytajnik.REMOVED_THIS_TICK.clear();
        BedrockStruktury.tick();
        if (ADDONY.isEmpty()) return;
        BedrockUszy.joinTick(s);
        BedrockUszy.inventoryTick(s);
        BedrockUszy.weatherTick(s);
        // entities the world dropped since the last tick ride along, the scripts forget their wrappers
        String gone = "";
        if (!GONE.isEmpty()) {
            StringBuilder sb = new StringBuilder(",\"gone\":[");
            for (int i = 0; i < GONE.size(); i++) sb.append(i == 0 ? "" : ",").append('"').append(GONE.get(i)).append('"');
            gone = sb.append(']').toString();
            GONE.clear();
        }
        String msg = "{\"t\":\"tick\",\"tick\":" + tick + gone + "}";
        for (Addon a : ADDONY.toArray(new Addon[0])) call(a, msg);
    }

    // every event something on the java side actually fires. subscribing to anything else used to
    // just never call back, which is how half an addon went dead without a single line in the log
    private static final Set<String> GRA_AFTER = Set.of("buttonPush", "chatSend", "dataDrivenEntityTrigger", "effectAdd",
        "entityDie", "entityHeal", "entityHealthChanged", "entityHitEntity", "entityHitBlock", "entityHurt", "entityItemDrop", "entityItemPickup",
        "entityLoad", "entityRemove", "entitySpawn", "itemCompleteUse", "itemReleaseUse", "itemStartUse", "itemStartUseOn",
        "itemStopUse", "itemUse", "itemUseOn", "playerBreakBlock", "playerDimensionChange", "playerGameModeChange",
        "playerInteractWithBlock", "playerInteractWithEntity", "playerInventoryItemChange", "playerJoin", "playerLeave",
        "playerPlaceBlock", "playerSpawn", "playerSwingStart", "projectileHitBlock", "projectileHitEntity", "scriptEventReceive",
        "weatherChange", "worldLoad", "worldInitialize");
    private static final Set<String> GRA_BEFORE = Set.of("chatSend", "effectAdd", "entityHurt", "entityItemPickup", "entityRemove", "itemUse", "itemUseOn",
        "playerBreakBlock", "playerInteractWithBlock", "playerInteractWithEntity", "playerLeave", "startup", "shutdown",
        "watchdogTerminate", "worldInitialize");

    static void subscribe(Addon a, String name, boolean before, boolean on) {
        Set<String> set = before ? a.before : a.after;
        if (on && set.add(name) && !(before ? GRA_BEFORE : GRA_AFTER).contains(name))
            KoperLib.LOGGER.error("[Bedrock] {} listens to {}Events.{} and koperlib NEVER fires that event, whatever the addon does with it will not happen",
                a.folder, before ? "before" : "after", name);
        if (!on) set.remove(name);
    }

    // cheap check for the hot hooks (hurt, spawn) so they don't build json for nobody
    public static boolean wants(String name, boolean before) {
        for (Addon a : ADDONY) if ((before ? a.before : a.after).contains(name)) return true;
        return false;
    }

    // after-events wait for the end of the server tick, like bedrock delivers them. fired on the spot they ran
    // scripts from inside java's chunk loading (entityLoad, entityRemove...) and a script touching the world
    // there changed the chunk tickets mid iteration: DistanceManager crashed the server
    private static final java.util.ArrayDeque<String[]> AFTER = new java.util.ArrayDeque<>();

    public static void fireAfter(String name, JsonObject data) {
        if (!wants(name, false)) return;
        String msg = envelope(name, false, data);
        // already inside a script: call() queues it behind that script, same tick, as before
        if (teraz != null) {
            for (Addon a : ADDONY.toArray(new Addon[0])) if (a.after.contains(name)) call(a, msg);
            return;
        }
        AFTER.add(new String[] {name, msg});
    }

    private static void deliverAfter() {
        // bounded: an event handler that causes events would otherwise never let the tick end
        for (int n = 0; n < 20000 && !AFTER.isEmpty(); n++) {
            String[] ev = AFTER.poll();
            for (Addon a : ADDONY.toArray(new Addon[0])) if (a.after.contains(ev[0])) call(a, ev[1]);
        }
        if (!AFTER.isEmpty()) {
            KoperLib.LOGGER.error("[Bedrock] {} after-events left over at the end of the tick, dropped (events causing events?)", AFTER.size());
            AFTER.clear();
        }
    }

    // true = some addon cancelled it
    public static boolean fireBefore(String name, JsonObject data) {
        if (!wants(name, true)) return false;
        String msg = envelope(name, true, data);
        boolean cancel = false;
        for (Addon a : ADDONY.toArray(new Addon[0])) {
            if (!a.before.contains(name)) continue;
            if (cancelled(call(a, msg))) cancel = true;
        }
        return cancel;
    }

    // before events a script may also write into (entityHurt.damage, effectAdd.duration): every
    // addon sees what the one before it left, like bedrock chains them. null = cancelled
    public static JsonObject fireBeforeEdit(String name, JsonObject data, String... writable) {
        if (!wants(name, true)) return data;
        for (Addon a : ADDONY.toArray(new Addon[0])) {
            if (!a.before.contains(name)) continue;
            String answer = call(a, envelope(name, true, data));
            if (cancelled(answer)) return null;
            if (answer == null || answer.isEmpty()) continue;
            try {
                JsonObject o = JsonParser.parseString(answer).getAsJsonObject();
                for (String k : writable) if (o.has(k) && o.get(k).isJsonPrimitive()) data.add(k, o.get(k));
            } catch (RuntimeException bad) {
                KoperLib.LOGGER.error("[Bedrock] {} answered {} with junk: {}", a.folder, name, answer);
            }
        }
        return data;
    }

    static void fireTo(Addon a, String json) {
        call(a, json);
    }

    static List<Addon> all() { return ADDONY; }

    private static String envelope(String name, boolean before, JsonObject data) {
        JsonObject m = new JsonObject();
        m.addProperty("t", "ev");
        m.addProperty("n", name);
        m.addProperty("b", before);
        m.add("d", data);
        return m.toString();
    }

    private static boolean cancelled(String answer) {
        if (answer == null || answer.isEmpty()) return false;
        try {
            JsonObject o = JsonParser.parseString(answer).getAsJsonObject();
            return o.has("cancel") && o.get("cancel").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    // ── custom components ────────────────────────────────────────────────────

    public static JsonElement itemComps(String id) { return ITEM_COMPS.get(id); }

    public static JsonObject throwable(String id) {
        JsonElement t = THROWABLES.get(id);
        return t != null && t.isJsonObject() ? t.getAsJsonObject() : null;
    }

    public static JsonElement blockComps(String id) {
        JsonElement b = BLOCK_COMPS.get(id);
        return b != null && b.isJsonObject() ? b.getAsJsonObject().get("comps") : null;
    }

    static JsonElement propDefault(String type, String key) {
        JsonElement defs = PROP_DEFAULTS.get(type);
        return defs != null && defs.isJsonObject() ? defs.getAsJsonObject().get(key) : null;
    }

    static List<String> families(String type) {
        JsonElement f = FAMILIES.get(type);
        List<String> out = new ArrayList<>();
        if (f != null && f.isJsonArray()) f.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        return out;
    }

    public static boolean slucha(String hookId) {
        int slash = hookId.lastIndexOf('/');
        if (slash < 0) return false;
        String id = hookId.substring(0, slash);
        return ITEM_COMPS.containsKey(id) || BLOCK_COMPS.containsKey(id);
    }

    // every addon gets it, the component registry decides who actually has a handler by that name
    static boolean fireComponent(String kind, String id, String ev, JsonElement comps, JsonObject data, boolean cancelable) {
        if (comps == null || ADDONY.isEmpty()) return false;
        JsonObject m = new JsonObject();
        m.addProperty("t", "cc");
        m.addProperty("kind", kind);
        m.addProperty("id", id);
        m.addProperty("ev", ev);
        m.add("comps", comps);
        m.add("d", data);
        m.addProperty("cancelable", cancelable);
        String msg = m.toString();
        boolean cancel = false;
        for (Addon a : ADDONY.toArray(new Addon[0])) if (cancelled(call(a, msg))) cancel = true;
        return cancel;
    }

    // koper item/block hooks -> bedrock custom component events
    static final class JavaHookRegistryEar {
        static void on() {
            com.koper.koper_lib.scripting.JavaHookRegistry.bedrockEar(JavaHookRegistryEar::heard);
        }

        static void off() {
            com.koper.koper_lib.scripting.JavaHookRegistry.bedrockEar(null);
        }

        private static InteractionResult heard(String hookId, KoperContext ctx) {
            int slash = hookId.lastIndexOf('/');
            if (slash < 0 || ADDONY.isEmpty()) return InteractionResult.PASS;
            String id = hookId.substring(0, slash);
            String ev = hookId.substring(slash + 1);
            JsonElement items = ITEM_COMPS.get(id);
            if (items != null) {
                JsonObject d = new JsonObject();
                switch (ev) {
                    case "on_use" -> {
                        d.add("source", BedrockPytajnik.entRef(ctx.player()));
                        d.add("itemStack", BedrockPytajnik.itemRef(ctx.stack()));
                        fireComponent("item", id, "onUse", items, d, false);
                    }
                    case "on_hit" -> {
                        d.add("attackingEntity", BedrockPytajnik.entRef(ctx.player()));
                        d.add("hitEntity", BedrockPytajnik.entRef(ctx.target()));
                        d.addProperty("hadEffect", true);
                        d.add("itemStack", BedrockPytajnik.itemRef(ctx.stack()));
                        fireComponent("item", id, "onHitEntity", items, d, false);
                    }
                    case "on_consume" -> {
                        d.add("source", BedrockPytajnik.entRef(ctx.player()));
                        d.add("itemStack", BedrockPytajnik.itemRef(ctx.stack()));
                        fireComponent("item", id, "onConsume", items, d, false);
                        fireComponent("item", id, "onCompleteUse", items, d, false);
                    }
                    default -> {}
                }
                return InteractionResult.PASS;
            }
            JsonElement blocks = blockComps(id);
            if (blocks != null && ctx.world() instanceof net.minecraft.server.level.ServerLevel level && ctx.pos() != null) {
                JsonObject d = new JsonObject();
                d.add("block", BedrockPytajnik.blockRef(level, ctx.pos()));
                d.add("dimension", BedrockPytajnik.dimRef(level));
                switch (ev) {
                    case "on_use" -> {
                        d.add("player", BedrockPytajnik.entRef(ctx.player()));
                        fireComponent("block", id, "onPlayerInteract", blocks, d, false);
                    }
                    case "on_place" -> fireComponent("block", id, "onPlace", blocks, d, false);
                    case "on_break" -> {
                        d.add("player", BedrockPytajnik.entRef(ctx.player()));
                        d.add("source", BedrockPytajnik.entRef(ctx.player()));
                        JsonObject perm = new JsonObject();
                        perm.addProperty("ty", id);
                        perm.add("st", new JsonObject());
                        JsonObject wrap = new JsonObject();
                        wrap.add("$p", perm);
                        d.add("destroyedBlockPermutation", wrap);
                        d.add("brokenBlockPermutation", wrap);
                        fireComponent("block", id, "onPlayerBreak", blocks, d, false);
                        fireComponent("block", id, "onBreak", blocks, d, false);
                    }
                    case "on_tick" -> {
                        fireComponent("block", id, "onTick", blocks, d, false);
                        fireComponent("block", id, "onRandomTick", blocks, d, false);
                    }
                    case "on_step" -> {
                        d.add("entity", BedrockPytajnik.entRef(ctx.player()));
                        fireComponent("block", id, "onStepOn", blocks, d, false);
                    }
                    default -> {}
                }
            }
            return InteractionResult.PASS;
        }
    }
}
