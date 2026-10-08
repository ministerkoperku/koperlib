package com.koper.koper_lib.kodel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

// without this the book keeps handing out last session's models after a reload,
// including the ones whose pack just got turned off
public final class KoperKodelClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        installBlockBridge();
        KodelBlockRenderer.init();
        KoperOverlayRenderTypes.install();
        installMovingGridBridge();
        KodelHitboxDebug.init();
        com.koper.koper_lib.api.core.KoperBoneAnchors.install(KodelBoneAnchors.provider());
        // a script asked an entity to play a clip for a while
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(KodelClipPayload.TYPE,
            (payload, ctx) -> ctx.client().execute(() -> {
                var level = ctx.client().level;
                if (level != null)
                    KodelForcedClips.set(payload.entityId(), payload.clip(), payload.holdTicks(), level.getGameTime());
            }));
        // entity ids start over in the next world
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            KodelBlockRenderer.clearIndex();
            KodelMobek.clearClocks();
            com.koper.koper_lib.api.core.KoperBoneAnchors.clear();
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) ->
            com.koper.koper_lib.api.core.KoperBoneAnchors.clear());
        com.koper.koper_lib.kodel.bedrock.BrPodglad.wlacz();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            com.koper.koper_lib.api.core.BedrockStanPayload.TYPE, (payload, ctx) -> ctx.client().execute(() ->
                com.koper.koper_lib.kodel.bedrock.BrStany.przyjmij(payload.entity(), payload.state())));
        // entity.playAnimation / playanimation from the server's addons
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            com.koper.koper_lib.api.core.BedrockAnimPayload.TYPE, (payload, ctx) -> ctx.client().execute(() ->
                com.koper.koper_lib.kodel.bedrock.BrAktorzy.zagraj(payload.entity(), payload.animation(), payload.blendOut(),
                    payload.stop(), payload.controller())));
        // bedrock particle effects the server's addon scripts ask for
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            com.koper.koper_lib.api.core.BedrockCzastkaPayload.TYPE, (payload, ctx) -> ctx.client().execute(() ->
                com.koper.koper_lib.kodel.bedrock.BrCzastki.spawn(payload.effect(), payload.x(), payload.y(), payload.z())));
        com.koper.koper_lib.api.core.KodelEntityBridge.install(new com.koper.koper_lib.api.core.KodelEntityBridge.Provider() {
            @Override
            public boolean handles(String model) {
                // the book loads lazily, a name that parses is a name we can draw
                return KodelBook.get(model) != null;
            }

            @Override
            public <T extends net.minecraft.world.entity.Mob> net.minecraft.client.renderer.entity.EntityRendererProvider<T> renderer(
                    String model, Identifier texture, com.koper.koper_lib.api.core.KodelEntityBridge.Clips clips, float shadow) {
                return ctx -> new KodelMobek<>(ctx, model, texture, clips, shadow);
            }
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
            .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    return Identifier.fromNamespaceAndPath(KoperKodelMod.MOD_ID, "kodel_book");
                }

                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    KodelBook.clear();
                    KodelKlocBook.clear();
                    KodelZbrojaBook.clear();
                    com.koper.koper_lib.kodel.bedrock.BrAktorzy.przeladuj();
                }
            });
    }

    // kender's block renderer asks this before baking its own mesh
    private static void installBlockBridge() {
        com.koper.koper_lib.api.core.KodelBlockBridge.install(
            new com.koper.koper_lib.api.core.KodelBlockBridge.Provider() {
                @Override
                public boolean handles(net.minecraft.world.level.block.state.BlockState state) {
                    return KodelKlocBook.of(state) != null;
                }

                @Override
                public boolean submit(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                                      com.mojang.blaze3d.vertex.PoseStack pose,
                                      net.minecraft.world.level.block.state.BlockState state, int light) {
                    return KodelKloc.submitBound(collector, pose, state, light);
                }

                @Override
                public boolean submitPosed(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                                           com.mojang.blaze3d.vertex.PoseStack pose,
                                           net.minecraft.world.level.block.state.BlockState state, int light, int tint,
                                           java.util.function.Function<String, float[]> bonePose,
                                           java.util.Set<String> visible) {
                    return KodelKloc.submitPosed(collector, pose, state, light, tint, bonePose, visible);
                }
            });
    }

    // model blocks riding a moving grid (contraptions, ships): kender asks, kodel draws
    private static void installMovingGridBridge() {
        com.koper.koper_lib.api.core.KenderGeoBridge.install(new com.koper.koper_lib.api.core.KenderGeoBridge.Provider() {
            @Override public Object binding(net.minecraft.world.level.block.state.BlockState state) {
                return KodelBlockBook.binding(state.getBlock());
            }
            @Override public boolean rendersOnMovingGrid(Object binding) {
                return ((KodelBlockBook.Binding) binding).onKontra();
            }
            @Override public boolean submit(net.minecraft.client.multiplayer.ClientLevel level,
                    Object binding, net.minecraft.world.level.block.state.BlockState state,
                    float bodyX, float bodyY, float bodyZ, org.joml.Quaternionf rotation,
                    float offsetX, float offsetY, float offsetZ,
                    net.minecraft.core.BlockPos worldPos, int packedLight) {
                return KodelBlockRenderer.kontraSubmit(level, (KodelBlockBook.Binding) binding, state,
                    bodyX, bodyY, bodyZ, rotation, offsetX, offsetY, offsetZ, worldPos, packedLight);
            }
            @Override public void dropMovingGrid(long id) {
                KodelTriggerBox.dropKontra(id);
            }
            @Override public void clearMovingGrids() {
                KodelTriggerBox.clear();
            }
            @Override public boolean vulkanActive() {
                return com.koper.koper_lib.kender.KenderFrame.vulkanActive();
            }
            @Override public void beginMovingFrame() {
                KodelBlockRenderer.kontraBegin();
                com.koper.koper_lib.kender.VanillaBlockKender.begin();
            }
            @Override public void endMovingFrame() {
                com.koper.koper_lib.kender.VanillaBlockKender.end();
                KodelBlockRenderer.kontraFlush();
            }
            @Override public net.minecraft.client.renderer.culling.Frustum frustum() {
                return com.koper.koper_lib.kender.KenderFrame.frustum();
            }
            @Override public boolean submitVanilla(com.koper.koper_lib.api.core.KenderMovingBlockContext context,
                    net.minecraft.world.level.block.state.BlockState state, byte faceMask,
                    float bodyX, float bodyY, float bodyZ, org.joml.Quaternionf rotation,
                    float offsetX, float offsetY, float offsetZ, int packedLight) {
                return com.koper.koper_lib.kender.VanillaBlockKender.submit(context, state, faceMask,
                    bodyX, bodyY, bodyZ, rotation, offsetX, offsetY, offsetZ, packedLight);
            }
            @Override public boolean submitFallbackGeo(net.minecraft.client.renderer.SubmitNodeCollector collector,
                    com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.level.block.state.BlockState state,
                    long gridId, net.minecraft.core.BlockPos localPos) {
                return KodelBlockRenderer.submitGeoOnKontra(collector, pose, state, gridId, localPos);
            }
        });
    }
}
