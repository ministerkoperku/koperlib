package com.koper.koper_lib.loader;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.network.FullPackSyncPayload;
import com.koper.koper_lib.network.KuiActionPayload;
import com.koper.koper_lib.network.KuiClosePayload;
import com.koper.koper_lib.network.KuiHudPayload;
import com.koper.koper_lib.network.KuiMenuDataPayload;
import com.koper.koper_lib.network.KuiOpenPayload;
import com.koper.koper_lib.network.KuiWidgetUpdatePayload;
import com.koper.koper_lib.network.ReloadResourcesPayload;
import com.koper.koper_lib.network.ScriptChannelPayload;
import com.koper.koper_lib.network.SoulSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

// fullpack owns pack sync, scripting channels, KUI and persistent player-state wire data
public final class FullpackNetworking {
    private FullpackNetworking() {}

    public static void init() {
        KoperNetwork.clientbound("fullpack", FullPackSyncPayload.TYPE, FullPackSyncPayload.CODEC);
        KoperNetwork.clientbound("fullpack", ReloadResourcesPayload.TYPE, ReloadResourcesPayload.CODEC);
        KoperNetwork.clientbound("fullpack", KuiOpenPayload.TYPE, KuiOpenPayload.CODEC);
        KoperNetwork.clientbound("fullpack", com.koper.koper_lib.network.BedrockKameraPayload.TYPE, com.koper.koper_lib.network.BedrockKameraPayload.CODEC);
        KoperNetwork.clientbound("fullpack", KuiMenuDataPayload.TYPE, KuiMenuDataPayload.CODEC);
        KoperNetwork.clientbound("fullpack", KuiWidgetUpdatePayload.TYPE, KuiWidgetUpdatePayload.CODEC);
        KoperNetwork.clientbound("fullpack", KuiClosePayload.TYPE, KuiClosePayload.CODEC);
        KoperNetwork.clientbound("fullpack", KuiHudPayload.TYPE, KuiHudPayload.CODEC);
        KoperNetwork.clientbound("fullpack", SoulSyncPayload.TYPE, SoulSyncPayload.CODEC);
        KoperNetwork.serverbound("fullpack", KuiActionPayload.TYPE, KuiActionPayload.CODEC);
        KoperNetwork.serverbound("fullpack", ScriptChannelPayload.TYPE, ScriptChannelPayload.CODEC);
        KoperNetwork.clientbound("fullpack", ScriptChannelPayload.TYPE, ScriptChannelPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ScriptChannelPayload.TYPE, (payload, context) ->
            context.server().execute(() ->
                com.koper.koper_lib.scripting.UniversalScriptEngine.fireChannelEvent(
                    payload.channel(), context.player(), payload.data()))
        );

        KoperNetwork.onJoin("fullpack", "pack-state", (player, server) ->
            KoperNetwork.send(player, new FullPackSyncPayload(FullpackManifest.loaded())));
        KoperNetwork.onJoin("fullpack", "saved-state", (player, server) -> {
            com.koper.koper_lib.kui.KuiContainers.loadIfNeeded(server);
            com.koper.koper_lib.state.KoperSoulVault.syncOnJoin(player);
        });
        KoperNetwork.onLeave("fullpack", "kui-session", (player, server) -> {
            com.koper.koper_lib.kui.KuiSessions.clear(player.getUUID());
            com.koper.koper_lib.kui.KuiContainers.save(server);
        });

        ServerPlayNetworking.registerGlobalReceiver(KuiActionPayload.TYPE, (payload, context) ->
            context.server().execute(() -> handleKui(context.player(), payload))
        );
    }

    private static void handleKui(ServerPlayer player, KuiActionPayload payload) {
        if (player == null) return;
        String guiId = payload.guiId();
        String widget = payload.widget();
        String action = payload.action();

        switch (action) {
            case "toggle" -> com.koper.koper_lib.kui.KuiSessions.put(
                player.getUUID(), guiId, widget, String.valueOf(payload.checked()));
            case "slider" -> com.koper.koper_lib.kui.KuiSessions.put(
                player.getUUID(), guiId, widget, String.valueOf(payload.value()));
            case "input", "select" -> com.koper.koper_lib.kui.KuiSessions.put(
                player.getUUID(), guiId, widget, payload.text());
            default -> {}
        }

        if (action.equals("close")) com.koper.koper_lib.kui.KuiPoke.closed(player, guiId);
        if (com.koper.koper_lib.kui.KuiPoke.fire(player, guiId, widget, action,
            payload.value(), payload.text(), payload.checked())) return;

        net.minecraft.world.Container slots = null;
        if (player.containerMenu instanceof com.koper.koper_lib.kui.KuiMenu menu && menu.data.id().equals(guiId))
            slots = menu.getContainer();

        var page = com.koper.koper_lib.kui.KuiBook.get(guiId);
        if (page != null && page.script != null) {
            com.koper.koper_lib.scripting.UniversalScriptEngine.fireGuiEvent(
                page.script, player, guiId, widget, action, payload.value(), payload.text(), payload.checked(), slots);
        }

        if (page == null || !action.equals("click") || slots == null
            || page.recipes.isEmpty() || !page.craftButtons.contains(widget)) return;
        var recipe = com.koper.koper_lib.kui.KuiCraft.match(slots, page.recipes);
        if (recipe == null) return;
        com.koper.koper_lib.kui.KuiCraft.consume(slots, recipe);
        com.koper.koper_lib.kui.KuiCraft.giveResult(player, recipe);
        player.containerMenu.broadcastChanges();
        if (page.script != null) {
            com.koper.koper_lib.scripting.UniversalScriptEngine.fireGuiEvent(
                page.script, player, guiId, recipe.id(), "recipe", 0f, recipe.result(), false, slots);
        }
    }
}
