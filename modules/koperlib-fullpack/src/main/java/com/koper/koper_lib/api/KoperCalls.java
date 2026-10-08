package com.koper.koper_lib.api;

import com.koper.koper_lib.KoperLib;
import net.minecraft.world.InteractionResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class KoperCalls {
    private static final Map<String, KoperCallHandler> CALLS = new ConcurrentHashMap<>();

    private KoperCalls() {}

    public static void register(String id, KoperCallHandler handler) {
        if (id == null || id.isBlank() || handler == null) return;
        CALLS.put(id, handler);
        KoperLib.LOGGER.debug("[KoperCall] registered {}", id);
    }

    public static InteractionResult run(String id, KoperCallContext ctx) {
        KoperCallHandler handler = CALLS.get(id);
        if (handler == null) {
            KoperLib.LOGGER.warn("[KoperCall] unknown call '{}'", id);
            return InteractionResult.PASS;
        }
        try {
            InteractionResult result = handler.run(ctx);
            return result != null ? result : InteractionResult.PASS;
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[KoperCall] '{}' failed: {}", id, e.getMessage());
            return InteractionResult.FAIL;
        }
    }

    public static boolean has(String id) {
        return CALLS.containsKey(id);
    }

    public static Set<String> ids() {
        return java.util.Collections.unmodifiableSet(CALLS.keySet());
    }
}
