package com.koper.koper_lib.kender;

import com.koper.koper_lib.api.core.KoperModules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class KoperKenderMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_kender";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        KoperModules.register("kender", version, KoperModules.Environment.COMMON,
            "geometry", "vulkan", "vanilla-fallback");
    }
}
