package com.koper.koper_lib.core;

import com.koper.koper_lib.api.core.KoperModules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Private developer/stress mod. It is deliberately not a dependency of normal modules. */
public final class KoperstuffMod implements ModInitializer {
    public static final String MOD_ID = "koperlib_koperstuff";

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        KoperModules.register("koperstuff", version, KoperModules.Environment.COMMON,
            "stress", "debug", "diagnostics");
        com.koper.koper_lib.loader.CommandRegistry.register();
        KoperFastFlightProbe.register();
    }
}
