package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperModules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class KoperKodelMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_kodel";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        KoperModules.register("kodel", version, KoperModules.Environment.COMMON,
            "animation", "smooth", "native-parser");
        com.koper.koper_lib.api.core.KodelBedrockBridge.install(new KodelBedrockKonwerter());
        // collision and outline of model blocks come from their bound bones
        com.koper.koper_lib.api.core.KoperBlockShapes.install(KodelBlockBook::shape);
        com.koper.koper_lib.api.core.KoperConfigs.register("kodel", KodelConfig::load, KodelConfig::save);
        KodelConfig.load();
        KodelNetworking.init();
    }
}