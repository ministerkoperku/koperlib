package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperConfigFile;

/**
 * Kodel settings. The player model lived in the KGecko settings file; a first load copies it
 * from there, because the field names are the same.
 */
public final class KodelConfig {
    private static final KoperConfigFile<KodelConfig> FILE = new KoperConfigFile<>(
        "kodel", KodelConfig.class, KodelConfig::new);
    private static final KoperConfigFile<KodelConfig> OLD = new KoperConfigFile<>(
        "kgecko", KodelConfig.class, KodelConfig::new);
    private static KodelConfig instance;

    /** A model that replaces the player's own, worn with the player's skin. Empty for vanilla. */
    public String playerModel = "";
    /** A clip that loops on that model while the body still drives the limbs. */
    public String playerModelAnim = "";

    public static KodelConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        instance = FILE.exists() || !OLD.exists() ? FILE.load() : FILE.adopt(OLD.load());
        KodelPlayerModel.set(instance.playerModel, instance.playerModelAnim);
    }

    public static void save() { FILE.save(get()); }
}
