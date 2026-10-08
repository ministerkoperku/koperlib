package com.koper.koper_lib.api.core;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Module-neutral live bone positions; Kodel publishes, combat APIs may consume. */
public final class KoperBonePositions {
    private static final ConcurrentHashMap<String, Vec3> POSITIONS = new ConcurrentHashMap<>();

    private KoperBonePositions() {}

    public static void update(int entityId, String bone, Vec3 position) {
        POSITIONS.put(entityId + ":" + bone, position);
    }

    public static Vec3 get(int entityId, String bone) { return POSITIONS.get(entityId + ":" + bone); }

    public static void clearEntity(int entityId) {
        POSITIONS.keySet().removeIf(key -> key.startsWith(entityId + ":"));
    }

    public static void clear() { POSITIONS.clear(); }

    public static Map<Integer, Map<String, Vec3>> grouped() {
        Map<Integer, Map<String, Vec3>> grouped = new HashMap<>();
        POSITIONS.forEach((key, value) -> {
            int separator = key.indexOf(':');
            if (separator < 0) return;
            try {
                int entity = Integer.parseInt(key.substring(0, separator));
                grouped.computeIfAbsent(entity, ignored -> new HashMap<>())
                    .put(key.substring(separator + 1), value);
            } catch (NumberFormatException ignored) {}
        });
        return grouped;
    }
}
