package com.koper.koper_lib.api;

import com.koper.koper_lib.KoperLib;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// custom cross-pack event bus — fire from Java or Lua (koper.events.fire in Lua routes here)
// @KoperSubscribe methods are registered automatically by JavaHookRegistry on reload
public final class KoperEventBus {
    private KoperEventBus() {}

    // CoW because handlers subscribe follow-ups from inside a dispatch and a plain ArrayList
    // threw CME straight out of fire(), killing the whole event instead of one handler
    private static final Map<String, List<MethodHandle>> SUBS = new ConcurrentHashMap<>();

    public static void fire(String eventId, KoperContext ctx) {
        List<MethodHandle> handlers = SUBS.get(eventId);
        if (handlers == null || handlers.isEmpty()) return;
        for (MethodHandle mh : handlers) {
            try { mh.invoke(ctx); }
            catch (Throwable t) {
                KoperLib.LOGGER.error("[KoperEventBus] {} handler blew up: {}", eventId, t.getMessage());
            }
        }
    }

    // called by JavaHookRegistry during class scan
    public static void subscribe(String eventId, MethodHandle handler) {
        SUBS.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    // packs that subscribe at runtime need a way back out — clearAll() is a sledgehammer
    public static boolean unsubscribe(String eventId, MethodHandle handler) {
        List<MethodHandle> handlers = SUBS.get(eventId);
        if (handlers == null) return false;
        boolean hit = handlers.remove(handler);
        if (handlers.isEmpty()) SUBS.remove(eventId, handlers);
        return hit;
    }

    // drops every handler on events owned by one namespace ("mypack:thing/*" and "mypack:thing")
    public static int clearNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return 0;
        String prefix = namespace + ":";
        int gone = 0;
        for (String id : SUBS.keySet()) {
            if (!id.startsWith(prefix)) continue;
            List<MethodHandle> dead = SUBS.remove(id);
            if (dead != null) gone += dead.size();
        }
        return gone;
    }

    public static int handlerCount(String eventId) {
        List<MethodHandle> handlers = SUBS.get(eventId);
        return handlers == null ? 0 : handlers.size();
    }

    public static void clearAll() { SUBS.clear(); }
}
