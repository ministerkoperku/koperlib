package com.koper.koper_lib.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// client mirror of the soul vault, fed by SoulSyncPayload. lets client-side render/HUD code read another
// player's state (shroom_body, etc.) without a round trip. read-only here — the server is authoritative.
public final class KoperSoulClient {

    private static final Map<UUID, Map<String, String>> SOULS = new ConcurrentHashMap<>();

    private KoperSoulClient() {}

    // apply a delta or a full dump. "x" tombstones drop the key.
    public static void apply(UUID player, Map<String, String> entries) {
        Map<String, String> m = SOULS.computeIfAbsent(player, k -> new LinkedHashMap<>());
        for (var e : entries.entrySet()) {
            if (KoperSoulCodec.TOMBSTONE.equals(e.getValue())) m.remove(e.getKey());
            else m.put(e.getKey(), e.getValue());
        }
    }

    public static void clear() { SOULS.clear(); }

    // every key under a prefix, for ui that has to list what a player owns instead of asking key by key
    public static java.util.List<String> keys(UUID player, String ns, String prefix) {
        Map<String, String> soul = SOULS.get(player);
        if (soul == null) return java.util.List.of();
        String head = KoperSoulCodec.clean(ns) + ":" + prefix;
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String key : soul.keySet())
            if (key.startsWith(head)) found.add(key.substring(head.length()));
        return found;
    }

    public static double getNum(UUID player, String ns, String key, double def) {
        return KoperSoulCodec.asNum(raw(player, ns, key), def);
    }

    public static boolean getBool(UUID player, String ns, String key, boolean def) {
        return KoperSoulCodec.asBool(raw(player, ns, key), def);
    }

    public static String getStr(UUID player, String ns, String key, String def) {
        String raw = raw(player, ns, key);
        return raw == null ? def : KoperSoulCodec.asStr(raw);
    }

    private static String raw(UUID player, String ns, String key) {
        Map<String, String> m = SOULS.get(player);
        return m == null ? null : m.get(KoperSoulCodec.clean(ns) + ":" + key);
    }
}
