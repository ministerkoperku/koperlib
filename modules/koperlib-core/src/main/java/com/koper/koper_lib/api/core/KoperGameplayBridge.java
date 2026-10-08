package com.koper.koper_lib.api.core;

/** Optional balance values supplied by a content mod such as Fullpack. */
public final class KoperGameplayBridge {
    public interface Provider {
        default float damageMultiplier() { return 1.0f; }
        default float healthMultiplier() { return 1.0f; }
    }

    private static volatile Provider provider;

    private KoperGameplayBridge() {}

    public static void install(Provider value) { provider = value; }

    public static float damageMultiplier() {
        Provider current = provider;
        return current == null ? 1.0f : current.damageMultiplier();
    }

    public static float healthMultiplier() {
        Provider current = provider;
        return current == null ? 1.0f : current.healthMultiplier();
    }
}
