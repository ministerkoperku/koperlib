package com.koper.koper_lib.api.core;

/** Optional client bridge between main Kender and Koper Effects. */
public final class KenderEffectsBridge {
    public interface Provider {
        float nowTicks();
        void gpuDrew(boolean drew);
        default boolean gpuDrew() { return false; }
        default boolean gpuEnabled() { return true; }
    }

    private static volatile Provider provider;

    private KenderEffectsBridge() {}

    public static void install(Provider value) { provider = value; }

    public static float nowTicks() {
        Provider current = provider;
        return current == null ? 0f : current.nowTicks();
    }

    public static void gpuDrew(boolean drew) {
        Provider current = provider;
        if (current != null) current.gpuDrew(drew);
    }

    public static boolean gpuDrew() {
        Provider current = provider;
        return current != null && current.gpuDrew();
    }

    public static boolean gpuEnabled() {
        Provider current = provider;
        return current != null && current.gpuEnabled();
    }
}
