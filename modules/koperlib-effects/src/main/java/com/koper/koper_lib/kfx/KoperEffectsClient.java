package com.koper.koper_lib.kfx;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client renderer and network entrypoint for KFX. */
public final class KoperEffectsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KfxClient.initClock();
        KfxRenderer.init();
        KfxNetworking.initClient();
        com.koper.koper_lib.api.core.KenderEffectsBridge.install(
            new com.koper.koper_lib.api.core.KenderEffectsBridge.Provider() {
                @Override public float nowTicks() { return KfxClient.nowTicks(); }
                @Override public void gpuDrew(boolean drew) { KfxRenderer.gpuDrewLastFrame = drew; }
                @Override public boolean gpuDrew() { return KfxRenderer.gpuDrewLastFrame; }
                @Override public boolean gpuEnabled() { return EffectsConfig.get().kenderVulkanParticles; }
            });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> KfxClient.clear());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> KfxClient.clear());
    }
}
