package com.koper.koper_lib.kender;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client side of Kender: the GPU path Kodel models draw through, the entity bench command, and a
 * clean frame after a disconnect.
 */
public final class KoperKenderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KenderConfig.load();
        com.koper.koper_lib.api.core.KoperConfigs.register("kender", KenderConfig::load, KenderConfig::save);
        KenderEntityBench.register();
        installKodelGpuPath();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> KenderFrame.reset());
    }

    // kodel draws through blaze3d when nobody installs this; with it, kodel meshes ride the batch
    private static void installKodelGpuPath() {
        com.koper.koper_lib.api.core.KodelKenderBridge.install(
            new com.koper.koper_lib.api.core.KodelKenderBridge.Provider() {
                @Override
                public boolean available(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector) {
                    return KenderEntityBatch.available() && KenderEntityBatch.worldCollector(collector);
                }

                @Override
                public org.joml.Matrix4f originRelative(org.joml.Matrix4fc model,
                        net.minecraft.client.renderer.state.level.CameraRenderState camera, org.joml.Matrix4f out) {
                    return KenderEntityBatch.originRelative(model, camera, out);
                }

                @Override
                public boolean submit(Object identity, String debugName, java.util.function.Supplier<float[]> bake,
                        net.minecraft.resources.Identifier texture,
                        net.minecraft.client.renderer.rendertype.RenderType type,
                        org.joml.Matrix4fc transform, int light, int tint, int boneCount, float[] boneMatrices) {
                    return KenderEntityBatch.submit(identity, debugName, bake, texture, type, transform, light, tint,
                        boneCount, out -> { for (float f : boneMatrices) out.add(f); });
                }

                @Override
                public void ensurePass(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                        com.mojang.blaze3d.vertex.PoseStack pose,
                        net.minecraft.client.renderer.rendertype.RenderType type,
                        net.minecraft.resources.Identifier texture) {
                    KenderEntityBatch.ensurePass(collector, pose, type, texture);
                }
            });
    }
}
