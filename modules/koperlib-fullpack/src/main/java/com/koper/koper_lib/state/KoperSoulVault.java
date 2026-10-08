package com.koper.koper_lib.state;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.network.KoperNetworking;
import com.koper.koper_lib.network.SoulSyncPayload;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// official persistent per-player key/value store. lives in the overworld save (data/koper_lib_soul_vault.dat),
// so it survives relog AND restart and works for offline players too. keys are namespaced per pack ("ns:key")
// so two fullpacks can both keep a "level" without trampling each other.
//
// this is the thing that replaces the "custom energy / scoreboard / tag" hacks fullpacks used for stuff like
// shroom_body, antishroom_body, shrooming attempts, etc. — koper.pstate.* in lua, KoperSoulVault.* in java.
//
// everything here runs on the server thread (scripts call in synchronously, lifecycle hooks are main-thread).
public final class KoperSoulVault extends SavedData {

    private static final Identifier ID = Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "soul_vault");

    // uuid -> ("ns:key" -> tagged value)
    private final Map<UUID, Map<String, String>> souls;

    private static final Codec<Map<String, Map<String, String>>> RAW_CODEC =
        Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.STRING));

    private static final Codec<KoperSoulVault> CODEC = RecordCodecBuilder.create(b -> b.group(
        RAW_CODEC.fieldOf("souls").forGetter(KoperSoulVault::export)
    ).apply(b, KoperSoulVault::new));

    @SuppressWarnings("DataFlowIssue")
    private static final SavedDataType<KoperSoulVault> TYPE = new SavedDataType<>(ID, KoperSoulVault::new, CODEC, null);

    private KoperSoulVault() {
        this.souls = new LinkedHashMap<>();
    }

    private KoperSoulVault(Map<String, Map<String, String>> raw) {
        this.souls = new LinkedHashMap<>(raw.size());
        for (var e : raw.entrySet()) {
            try {
                souls.put(UUID.fromString(e.getKey()), new LinkedHashMap<>(e.getValue()));
            } catch (IllegalArgumentException bad) {
                KoperLib.LOGGER.warn("[SoulVault] dropping entry with bad uuid '{}'", e.getKey());
            }
        }
    }

    private Map<String, Map<String, String>> export() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>(souls.size());
        for (var e : souls.entrySet()) out.put(e.getKey().toString(), new LinkedHashMap<>(e.getValue()));
        return out;
    }

    private static KoperSoulVault vault(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private static String nsKey(String ns, String key) {
        return KoperSoulCodec.clean(ns) + ":" + key;
    }

    // ── read ────────────────────────────────────────────────────────────────────

    // raw tagged value or null. most callers want the typed helpers below or the codec.
    public static String getRaw(MinecraftServer server, UUID player, String ns, String key) {
        Map<String, String> m = vault(server).souls.get(player);
        return m == null ? null : m.get(nsKey(ns, key));
    }

    public static double getNum(MinecraftServer server, UUID player, String ns, String key, double def) {
        return KoperSoulCodec.asNum(getRaw(server, player, ns, key), def);
    }

    public static boolean getBool(MinecraftServer server, UUID player, String ns, String key, boolean def) {
        return KoperSoulCodec.asBool(getRaw(server, player, ns, key), def);
    }

    public static String getStr(MinecraftServer server, UUID player, String ns, String key, String def) {
        String raw = getRaw(server, player, ns, key);
        return raw == null ? def : KoperSoulCodec.asStr(raw);
    }

    public static boolean has(MinecraftServer server, UUID player, String ns, String key) {
        return getRaw(server, player, ns, key) != null;
    }

    // every key a player has under a prefix, for anything that has to list a collection
    // WARNING: returns what is left AFTER the prefix, not the whole key. keys(.., "quest", "koper_")
    // hands back "mod_fabric:waking_up", so stick the prefix back on before using it as an id.
    // gets you nothing but silence otherwise: no crash, no log, the lookup just never matches
    public static java.util.List<String> keys(MinecraftServer server, UUID player, String ns, String prefix) {
        Map<String, String> m = vault(server).souls.get(player);
        if (m == null) return java.util.List.of();
        String head = nsKey(ns, prefix);
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String key : m.keySet()) if (key.startsWith(head)) found.add(key.substring(head.length()));
        return found;
    }

    // full snapshot for one player ("ns:key" -> tagged). empty map if nothing stored.
    public static Map<String, String> snapshot(UUID player, MinecraftServer server) {
        Map<String, String> m = vault(server).souls.get(player);
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    public static Map<UUID, Map<String, String>> everything(MinecraftServer server) {
        Map<UUID, Map<String, String>> out = new LinkedHashMap<>();
        for (var e : vault(server).souls.entrySet())
            if (!e.getValue().isEmpty()) out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        return out;
    }

    // ── write ───────────────────────────────────────────────────────────────────

    // tagged == null removes the key. always persists + pushes a delta to every online client.
    public static void setRaw(MinecraftServer server, UUID player, String ns, String key, String tagged) {
        KoperSoulVault v = vault(server);
        String fullKey = nsKey(ns, key);
        Map<String, String> m = v.souls.computeIfAbsent(player, k -> new LinkedHashMap<>());
        if (tagged == null) {
            if (m.remove(fullKey) == null) return; // nothing changed
        } else {
            if (tagged.equals(m.put(fullKey, tagged))) return; // same value, skip the dirty + sync
        }
        v.setDirty();
        broadcast(server, player, fullKey, tagged == null ? KoperSoulCodec.TOMBSTONE : tagged);
    }

    // numeric bump (default +1). returns the new value. great for "shrooming attempts" counters.
    public static double add(MinecraftServer server, UUID player, String ns, String key, double amount) {
        double next = getNum(server, player, ns, key, 0) + amount;
        setRaw(server, player, ns, key, "n" + next);
        return next;
    }

    public static void remove(MinecraftServer server, UUID player, String ns, String key) {
        setRaw(server, player, ns, key, null);
    }

    // ── sync ──────────────────────────────────────────────────────────────────────

    // soul state is per-player but everyone needs it (rendering another player's shroom_body etc.) -> broadcast.
    private static void broadcast(MinecraftServer server, UUID player, String fullKey, String tagged) {
        SoulSyncPayload pkt = SoulSyncPayload.one(player, fullKey, tagged);
        KoperNetworking.broadcastToAll(server, pkt);
    }

    // a fresh client knows nothing — hand the newcomer the whole vault, then tell everyone about the newcomer.
    public static void syncOnJoin(ServerPlayer joined) {
        MinecraftServer server = joined.level().getServer();
        if (server == null) return;
        for (var e : everything(server).entrySet())
            KoperNetworking.sendToPlayer(joined, new SoulSyncPayload(e.getKey(), e.getValue()));
        Map<String, String> mine = snapshot(joined.getUUID(), server);
        if (!mine.isEmpty()) {
            SoulSyncPayload pkt = new SoulSyncPayload(joined.getUUID(), mine);
            for (ServerPlayer p : server.getPlayerList().getPlayers())
                if (p != joined) KoperNetworking.sendToPlayer(p, pkt);
        }
    }
}
