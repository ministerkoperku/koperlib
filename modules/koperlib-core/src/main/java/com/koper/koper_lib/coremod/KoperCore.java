package com.koper.koper_lib.coremod;

import com.koper.koper_lib.api.core.KoperCommands;
import com.koper.koper_lib.api.core.KoperConfigs;
import com.koper.koper_lib.api.core.KoperModules;
import com.koper.koper_lib.api.core.KoperNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KoperCore implements ModInitializer {
    public static final String MOD_ID = "koperlib_core";
    public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");
    public static final Logger LOGGER = LoggerFactory.getLogger("KoperLib/Core");

    @Override
    public void onInitialize() {
        // every koperlib mixin that did not take effect is reported loudly (KoperMixinKrzykacz); one summary per start
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> KoperMixinKrzykacz.summary());
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT)
            ClientSummary.register();
        KoperModules.register("core", VERSION, KoperModules.Environment.COMMON,
            "commands", "config", "network", "native-loader");
        KoperCommands.init();
        KoperNetwork.init();
        KoperNetwork.clientbound("core", com.koper.koper_lib.api.core.BedrockCzastkaPayload.TYPE,
            com.koper.koper_lib.api.core.BedrockCzastkaPayload.CODEC);
        KoperNetwork.clientbound("core", com.koper.koper_lib.api.core.BedrockStanPayload.TYPE,
            com.koper.koper_lib.api.core.BedrockStanPayload.CODEC);
        KoperNetwork.clientbound("core", com.koper.koper_lib.api.core.BedrockAnimPayload.TYPE,
            com.koper.koper_lib.api.core.BedrockAnimPayload.CODEC);
        KoperConfigs.register("core", com.koper.koper_lib.config.KoperLibConfig::load,
            com.koper.koper_lib.config.KoperLibConfig::save);
        com.koper.koper_lib.config.KoperLibConfig.load();
        LOGGER.info("[Core] ready v{}", VERSION);
    }

    // own class so a dedicated server never loads the client lifecycle event class
    private static final class ClientSummary {
        static void register() {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(c -> KoperMixinKrzykacz.summary());
        }
    }
}
