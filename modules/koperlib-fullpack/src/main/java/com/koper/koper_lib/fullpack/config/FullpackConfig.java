package com.koper.koper_lib.fullpack.config;

import com.koper.koper_lib.api.core.KoperConfigFile;

/** Settings owned by the Fullpack content and scripting engine. */
public final class FullpackConfig {
    private static final KoperConfigFile<FullpackConfig> FILE = new KoperConfigFile<>(
        "fullpack", FullpackConfig.class, FullpackConfig::new);
    private static FullpackConfig instance;

    public int scriptTimeoutMs = 5000;
    public boolean autoReloadScripts = false;
    public float globalDamageMultiplier = 1.0f;
    public float globalHealthMultiplier = 1.0f;
    public boolean disableCustomMobs = false;

    public static FullpackConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() { instance = FILE.load(); }
    public static void save() { FILE.save(get()); }
}
