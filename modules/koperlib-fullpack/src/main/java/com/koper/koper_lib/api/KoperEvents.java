package com.koper.koper_lib.api;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.function.Consumer;

// wrap fabric events with stale-generation check so we don't accumulate dead handlers after reload
// pack java/ code uses these instead of registering directly on Fabric event buses
public final class KoperEvents {
    private KoperEvents() {}

    public static void onServerTick(Consumer<MinecraftServer> handler) {
        long gen = KoperEventProxy.currentGeneration();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (gen != KoperEventProxy.currentGeneration()) return;
            handler.accept(server);
        });
    }

    public static void onServerStartTick(Consumer<MinecraftServer> handler) {
        long gen = KoperEventProxy.currentGeneration();
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (gen != KoperEventProxy.currentGeneration()) return;
            handler.accept(server);
        });
    }
}
