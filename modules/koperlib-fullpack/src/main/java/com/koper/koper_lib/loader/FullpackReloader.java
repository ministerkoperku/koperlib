package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.FullpackAddons;
import com.koper.koper_lib.network.ReloadResourcesPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Production hot reload path with feature-owned cleanup hooks. */
public final class FullpackReloader {
    private FullpackReloader() {}

    public static void reload(net.minecraft.server.MinecraftServer server) {
        DevPackWatcher.beginInternalReload();
        try {
            prepareCaches();
        } catch (Throwable error) {
            FullpackAddons.abortReload();
            DevPackWatcher.endInternalReload();
            throw error;
        }
        server.getPackRepository().reload();
        var selected = server.getPackRepository().getSelectedIds();
        server.reloadResources(selected).whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    KoperLib.LOGGER.error("[Fullpack] reload failed", error);
                    return;
                }
                for (var player : server.getPlayerList().getPlayers())
                    ServerPlayNetworking.send(player, new ReloadResourcesPayload());
                // quests only auto-started on join, so anyone already in the world when a pack
                // showed up sat there with nothing running and no way to tell
                for (var player : server.getPlayerList().getPlayers())
                    com.koper.koper_lib.quest.QuestChase.offerAutoStarts(player);
                KoperLib.LOGGER.info("[Fullpack] reload complete");
            } finally {
                DevPackWatcher.endInternalReload();
            }
        });
    }

    public static void prepareCaches() {
        FullpackAddons.prepareReload();
        com.koper.koper_lib.scripting.JavaHookRegistry.clearAll();
        com.koper.koper_lib.scripting.ScriptCommandDispatcher.clearBacklog();
        com.koper.koper_lib.scripting.KoperScriptCommands.clear();
        com.koper.koper_lib.scripting.UniversalScriptEngine.clearCache();
        KoperLib.VIRTUAL_PACK.clearAssets();
        CreativeTabRegistry.clearDeferredItems();
        ContentRegistry.clearAll();
        com.koper.koper_lib.block.KoperBrainRegistry.clear();
        com.koper.koper_lib.factory.EntityFactory.clearReloadData();
        com.koper.koper_lib.factory.DimensionFactory.clearReloadData();
        com.koper.koper_lib.kui.KuiBook.clear();
        com.koper.koper_lib.quest.QuestBook.clear();
        com.koper.koper_lib.quest.BookShelf.clear();
        com.koper.koper_lib.quest.DialogBook.clear();

        FullPackLoader.loadFullPacks();
        ContentRegistry.prepareRegistriesForNewContent();
        FullPackLoader.getAllPacks().forEach((folder, meta) -> {
            if (!FullPackLoader.isEnabled(folder)) return;
            String namespace = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            com.koper.koper_lib.scripting.JavaHookRegistry.loadPackJava(
                KoperLibDirectories.FULLPACKS.resolve(folder), namespace);
        });
        new UniversalLoader().loadExternalContent();
        CreativeTabRegistry.removeDisabledPackTabs();
        CreativeTabRegistry.processTabs(KoperLib.MOD_ID);
        com.koper.koper_lib.kui.KuiBaker.syncAll(false);
    }
}
