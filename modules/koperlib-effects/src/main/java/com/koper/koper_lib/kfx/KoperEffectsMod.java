package com.koper.koper_lib.kfx;

import com.koper.koper_lib.api.core.KoperModules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entrypoint for the standalone Koper Effects (KFX) mod. */
public final class KoperEffectsMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_effects";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        KoperModules.register("effects", version, KoperModules.Environment.COMMON,
            "kfx", "particles", "lasers", "effect-programs");
        com.koper.koper_lib.api.core.KoperConfigs.register("effects", EffectsConfig::load, EffectsConfig::save);
        EffectsConfig.load();
        KfxNetworking.init();
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.init();
    }
}
