package com.koper.koper_lib.kender;

import com.koper.koper_lib.api.core.KoperConfigFile;

/**
 * How Kender draws: the Vulkan path or vanilla, and the switches for entities, shadows and
 * shader compatibility. These lived in the KGecko settings file; a first load copies them from
 * there, because the field names are the same.
 */
public final class KenderConfig {
    private static final KoperConfigFile<KenderConfig> FILE = new KoperConfigFile<>(
        "kender", KenderConfig.class, KenderConfig::new);
    private static final KoperConfigFile<KenderConfig> OLD = new KoperConfigFile<>(
        "kgecko", KenderConfig.class, KenderConfig::new);
    private static KenderConfig instance;

    public float kenderBlockCullDistance = 128f;
    public String rendering = "koperlib";
    public boolean kenderEntityRender = true;
    public boolean kenderShaderCompat = true;
    public boolean kenderShadowCast = false;

    public enum RenderMode { VANILLA, KOPERLIB }

    private transient RenderMode modeCache;
    private transient String modeCacheOf;

    public RenderMode renderMode() {
        if (rendering == null) return RenderMode.VANILLA;
        if (modeCache != null && rendering == modeCacheOf) return modeCache;
        RenderMode parsed = switch (rendering.trim().toLowerCase()) {
            case "koperlib", "koper", "vulkan" -> RenderMode.KOPERLIB;
            default -> RenderMode.VANILLA;
        };
        modeCacheOf = rendering;
        modeCache = parsed;
        return parsed;
    }

    public static KenderConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        instance = FILE.exists() || !OLD.exists() ? FILE.load() : FILE.adopt(OLD.load());
    }

    public static void save() { FILE.save(get()); }
}
