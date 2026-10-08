package com.koper.koper_lib.physics;

import com.koper.koper_lib.kender.KenderClientState;
import com.koper.koper_lib.kender.KenderRenderer;
import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.kender.KontraRideClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import com.koper.koper_lib.api.core.KenderMovingGrids;

/** Client lifecycle for Khysics and its Kender adapter. */
public final class KoperKhysicsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KenderRenderer.init();
        KenderTargeting.init();
        KontraRideClient.register();
        KhysicsNetworking.initClient();
        KenderMovingGrids.install(new KenderMovingGrids.Provider() {
            @Override public java.util.List<KenderMovingGrids.Grid> snapshot(long nowNanos) {
                java.util.List<KenderMovingGrids.Grid> grids = new java.util.ArrayList<>();
                for (var kontra : KenderClientState.all()) {
                    float[] position = KenderClientState.renderPos(kontra, nowNanos);
                    float[] rotation = KenderClientState.renderRot(kontra, nowNanos);
                    if (position != null && rotation != null)
                        grids.add(new KenderMovingGrids.Grid(position, rotation, kontra.offsets, kontra.states));
                }
                return grids;
            }

            @Override public <T> T outsideGrid(java.util.function.Supplier<T> action) {
                return KenderClientState.outsideGrid(action);
            }
        });
        EntityRendererRegistry.register(KontraSeat.TYPE,
            net.minecraft.client.renderer.entity.NoopRenderer::new);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            KontraRideClient.clear();
            com.koper.koper_lib.kender.KenderSyncClient.clear();
            KenderClientState.clear();
        });
    }
}
