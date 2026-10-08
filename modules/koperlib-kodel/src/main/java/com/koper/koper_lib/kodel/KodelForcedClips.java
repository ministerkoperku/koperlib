package com.koper.koper_lib.kodel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// client side: which entities were told to play a specific clip, and until when.
// entries expire on their own so a script that forgets to clear one doesn't wedge a mob
// in a pose forever
public final class KodelForcedClips {

    private KodelForcedClips() {}

    private record Forced(String clip, long untilTick) {}

    private static final Map<Integer, Forced> ACTIVE = new ConcurrentHashMap<>();

    public static void set(int entityId, String clip, int holdTicks, long nowTick) {
        if (clip == null || clip.isBlank() || holdTicks <= 0) {
            ACTIVE.remove(entityId);
            return;
        }
        ACTIVE.put(entityId, new Forced(clip, nowTick + holdTicks));
    }

    // null = nothing forced, let the renderer pick by state as usual
    public static String get(int entityId, long nowTick) {
        Forced f = ACTIVE.get(entityId);
        if (f == null) return null;
        if (nowTick >= f.untilTick()) {
            ACTIVE.remove(entityId);
            return null;
        }
        return f.clip();
    }

    public static void clear() { ACTIVE.clear(); }
}
