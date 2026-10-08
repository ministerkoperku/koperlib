package com.koper.koper_lib.config;

import com.koper.koper_lib.api.core.KoperConfigFile;

/** Core-owned settings shared by every KoperLib module. */
public final class KoperLibConfig {
    private static final KoperConfigFile<KoperLibConfig> FILE = new KoperConfigFile<>(
        "core", KoperLibConfig.class, KoperLibConfig::new);
    private static KoperLibConfig instance;

    public boolean debugMode = false;

    public static KoperLibConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        instance = FILE.load();
    }

    public static void save() {
        FILE.save(get());
    }
}
