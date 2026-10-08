package com.koper.koper_lib.kodel;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// manual trigger switchboard — when a blockstate can't carry the signal, client code/Lua flips a
// named trigger here and the kender anim system picks it up like any "when"/clips trigger.
// world blocks key by BlockPos, kontra blocks by (kontraId, local). client-side only.
// hello people reading this o/ if you know a smarter wiring PLEASE HELP A SILLY LITTLE KOPERDEV
public final class KodelTriggerBox {

    private KodelTriggerBox() {}

    private static final Map<BlockPos, Set<String>> WORLD = new ConcurrentHashMap<>();
    private static final Map<Long, Map<BlockPos, Set<String>>> KONTRA = new ConcurrentHashMap<>();

    public static void set(BlockPos pos, String trigger, boolean on) {
        if (on) {
            WORLD.computeIfAbsent(pos.immutable(), k -> ConcurrentHashMap.newKeySet()).add(trigger.toLowerCase());
        } else {
            Set<String> s = WORLD.get(pos);
            if (s != null) { s.remove(trigger.toLowerCase()); if (s.isEmpty()) WORLD.remove(pos); }
        }
    }

    public static void setKontra(long kontraId, BlockPos local, String trigger, boolean on) {
        if (on) {
            KONTRA.computeIfAbsent(kontraId, k -> new ConcurrentHashMap<>())
                  .computeIfAbsent(local.immutable(), k -> ConcurrentHashMap.newKeySet()).add(trigger.toLowerCase());
        } else {
            Map<BlockPos, Set<String>> m = KONTRA.get(kontraId);
            if (m == null) return;
            Set<String> s = m.get(local);
            if (s != null) { s.remove(trigger.toLowerCase()); if (s.isEmpty()) m.remove(local); }
            if (m.isEmpty()) KONTRA.remove(kontraId);
        }
    }

    public static boolean has(BlockPos pos, String trigger) {
        Set<String> s = WORLD.get(pos);
        return s != null && s.contains(trigger);
    }

    public static boolean hasKontra(long kontraId, BlockPos local, String trigger) {
        Map<BlockPos, Set<String>> m = KONTRA.get(kontraId);
        if (m == null) return false;
        Set<String> s = m.get(local);
        return s != null && s.contains(trigger);
    }

    public static void dropKontra(long kontraId) { KONTRA.remove(kontraId); }

    public static void clear() { WORLD.clear(); KONTRA.clear(); }
}
