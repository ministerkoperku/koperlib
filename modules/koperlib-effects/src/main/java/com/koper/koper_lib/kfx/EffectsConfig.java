package com.koper.koper_lib.kfx;

import com.koper.koper_lib.api.core.KoperConfigFile;

/** Settings owned by Koper Effects. */
public final class EffectsConfig {
    private static final KoperConfigFile<EffectsConfig> FILE = new KoperConfigFile<>(
        "effects", EffectsConfig.class, EffectsConfig::new);
    private static EffectsConfig instance;

    public boolean kenderVulkanParticles = true;

    public static EffectsConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() { instance = FILE.load(); }
    public static void save() { FILE.save(get()); }
}
