package com.koper.koper_lib;

import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.factory.EntityFactory;
import com.koper.koper_lib.loader.CreativeTabRegistry;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.network.FullPackSyncPayload;
import com.koper.koper_lib.network.ReloadResourcesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

public class KoperLibClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        com.koper.koper_lib.kui.KuiThemes.init();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client ->
            com.koper.koper_lib.loader.FullpackTombstones.registerStartupTombstones());
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (com.koper.koper_lib.loader.FullpackTombstones.isMissing(stack.getItem()))
                lines.add(Component.literal("§cIts Fullpack is not loaded. It comes back when the pack does."));
        });
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getEntrypoints("koperlib-fullpack-client-addon", Runnable.class)
            .forEach(Runnable::run);
        registerEntityRenderers();

        ClientPlayNetworking.registerGlobalReceiver(FullPackSyncPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                java.util.Map<String, com.koper.koper_lib.network.FullPackSyncPayload.PackEntry> localPacks =
                    com.koper.koper_lib.loader.FullpackManifest.loaded().stream().collect(
                        java.util.stream.Collectors.toMap(
                            com.koper.koper_lib.network.FullPackSyncPayload.PackEntry::id,
                            java.util.function.Function.identity(), (a, b) -> a));
                for (var pack : payload.packs()) {
                    var local = localPacks.get(pack.id());
                    boolean missing = local == null;
                    boolean mismatch = !missing && !pack.sha256().isBlank()
                        && !pack.sha256().equalsIgnoreCase(local.sha256());
                    if (missing || mismatch) {
                        String links = pack.links().isEmpty() ? ""
                            : "\n§7Manual HTTPS links (nothing was downloaded):\n§f"
                                + String.join("\n", pack.links());
                        String executable = pack.executableContent()
                            ? "\n§cWarning: this pack declares Java/JAR executable content. Review it before installing."
                            : "";
                        context.player().connection.getConnection().disconnect(
                            Component.literal("§c[KoperLib] " + (missing ? "Missing" : "Different")
                                + " Fullpack: §e" + pack.name() + " §7(" + pack.id() + ")"
                                + "\n§7Required version: §f" + pack.version()
                                + "\n§7Install it manually in koperlib/fullpacks/."
                                + executable + links));
                        return;
                    }
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ReloadResourcesPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                KoperLib.LOGGER.info("[KoperLib] Server requested resource reload.");
                context.client().reloadResourcePacks();
            });
        });

        // Kui — data-driven mc-style gui (no textures). server says "open id", we build the screen
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.BedrockKameraPayload.TYPE, (payload, context) ->
            context.client().execute(() -> com.koper.koper_lib.bedrock.BedrockKamera.odbierz(payload.json())));
        com.koper.koper_lib.bedrock.BedrockKamera.register();
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.KuiOpenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                com.koper.koper_lib.kui.KuiPage page = new com.koper.koper_lib.kui.KuiPage();
                page.id = payload.id();
                page.title = payload.title();
                page.w = payload.w();
                page.h = payload.h();
                page.mode = payload.mode();
                context.client().gui.setScreen(new com.koper.koper_lib.kui.KuiScreen(page, payload.layout(), payload.state(), payload.png()));
            })
        );

        // container gui: stash the render data so the menu's client factory can read it when the open packet lands
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.KuiMenuDataPayload.TYPE, (payload, context) ->
            context.client().execute(() -> com.koper.koper_lib.kui.KuiClientPending.set(payload.data()))
        );
        net.minecraft.client.gui.screens.MenuScreens.register(
            com.koper.koper_lib.kui.KuiMenus.TYPE, com.koper.koper_lib.kui.KuiMenuScreen::new);
        com.koper.koper_lib.kui.KuiHud.register();

        // koper.gui.set(widget, value) -> poke whatever kui screen or hud is showing
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.KuiWidgetUpdatePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                var s = context.client().gui.screen();
                if (s instanceof com.koper.koper_lib.kui.KuiScreen ks) ks.updateWidget(payload.widget(), payload.value());
                else if (s instanceof com.koper.koper_lib.kui.KuiMenuScreen ms) ms.updateWidget(payload.widget(), payload.value());
                com.koper.koper_lib.kui.KuiHud.update(payload.widget(), payload.value());
            })
        );
        // koper.gui.close — drop a plain kui screen (menus close server-side)
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.KuiClosePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (context.client().gui.screen() instanceof com.koper.koper_lib.kui.KuiScreen)
                    context.client().gui.setScreen(null);
            })
        );
        // koper.gui.hud — add/remove a hud overlay
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.KuiHudPayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                com.koper.koper_lib.kui.KuiHud.set(payload.id(), payload.show(), payload.layout(), payload.x(), payload.y()))
        );

        // persistent player state mirror — server pushes deltas + a full dump on join
        ClientPlayNetworking.registerGlobalReceiver(com.koper.koper_lib.network.SoulSyncPayload.TYPE, (payload, context) ->
            context.client().execute(() -> com.koper.koper_lib.state.KoperSoulClient.apply(payload.player(), payload.entries()))
        );

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> {
                com.koper.koper_lib.kui.KuiHud.clear();
                com.koper.koper_lib.kui.KuiPreview.clear();
                com.koper.koper_lib.state.KoperSoulClient.clear();
            }
        );

        // processTabs registers new custom tabs then forceFullTabRebuild runs the full Fabric pipeline
        // so pages get assigned (fabric_getPage requires this) and shouldDisplay() returns true
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof CreativeModeInventoryScreen)) return;
            CreativeTabRegistry.processTabs(KoperLib.MOD_ID);
            if (client.player != null) {
                CreativeTabRegistry.forceFullTabRebuild(
                    client.player.connection.enabledFeatures(),
                    false,
                    client.player.level().registryAccess()
                );
            }
        });
    }

    private void registerEntityRenderers() {
        // seat is invisible — the kontra under your butt is the visual
        for (Map.Entry<Identifier, EntityType<?>> entry : EntityFactory.getRegisteredEntities().entrySet()) {
            Identifier id = entry.getKey();
            @SuppressWarnings("unchecked")
            EntityType<net.minecraft.world.entity.PathfinderMob> type =
                (EntityType<net.minecraft.world.entity.PathfinderMob>) entry.getValue();

            KoperEntityData data = EntityFactory.getEntityData().get(id);
            if (data != null && Boolean.TRUE.equals(data.invisible)) {
                // not even its name: bedrock draws nothing of an entity without a client entity
                EntityRendererRegistry.register(type, ctx -> new net.minecraft.client.renderer.entity.NoopRenderer<net.minecraft.world.entity.PathfinderMob>(ctx) {
                    @Override
                    protected boolean shouldShowName(net.minecraft.world.entity.PathfinderMob entity, double distanceToCameraSq) {
                        return false;
                    }
                });
                continue;
            }
            final Identifier texId = resolveEntityTexture(id, data);

            // a model (.kodel, or a geo file kodel converts) draws through the installed model addon
            if (com.koper.koper_lib.api.FullpackClientAddons.registerEntity(type, data, texId)) {
                continue;
            }

            EntityRendererRegistry.register(type, ctx ->
                new net.minecraft.client.renderer.entity.HumanoidMobRenderer<
                    net.minecraft.world.entity.PathfinderMob,
                    net.minecraft.client.renderer.entity.state.HumanoidRenderState,
                    net.minecraft.client.model.HumanoidModel<
                        net.minecraft.client.renderer.entity.state.HumanoidRenderState>>(
                    ctx,
                    new net.minecraft.client.model.HumanoidModel<>(
                        ctx.bakeLayer(ModelLayers.PLAYER)),
                    0.5f) {
                    @Override
                    public Identifier getTextureLocation(
                        net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
                        return texId;
                    }
                    @Override
                    public net.minecraft.client.renderer.entity.state.HumanoidRenderState createRenderState() {
                        return new net.minecraft.client.renderer.entity.state.HumanoidRenderState();
                    }
                });
        }
    }

    private static Identifier resolveEntityTexture(Identifier id, KoperEntityData data) {
        if (data != null && data.texture != null) {
            String tex = data.texture;
            if (!tex.contains(":")) tex = id.getNamespace() + ":" + tex;
            String[] parts = tex.split(":", 2);
            String path = parts[1];
            if (!path.startsWith("textures/")) path = "textures/entity/" + path;
            if (!path.endsWith(".png"))        path += ".png";
            return Identifier.fromNamespaceAndPath(parts[0], path);
        }
        return Identifier.fromNamespaceAndPath("minecraft", "textures/entity/zombie/zombie.png");
    }
}
