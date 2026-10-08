package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.FullPackAPI;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.api.KoperPackSnapshot;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import com.koper.koper_lib.loader.FullpackReloader;
import com.koper.koper_lib.scripting.JavaHookRegistry;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.InteractionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

// FullPackAPI impl — the shelf where all the packs live
public class KoperPackShelf implements FullPackAPI {

    @Override
    public Optional<KoperPackSnapshot> getPack(String namespace) {
        for (var entry : FullPackLoader.getAllPacks().entrySet()) {
            String folder = entry.getKey();
            KoperMeta meta = entry.getValue();
            String ns = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            if (ns.equals(namespace)) return Optional.of(snap(folder, meta));
        }
        return Optional.empty();
    }

    @Override
    public List<KoperPackSnapshot> listAll() {
        List<KoperPackSnapshot> out = new ArrayList<>();
        FullPackLoader.getAllPacks().forEach((folder, meta) -> out.add(snap(folder, meta)));
        return out;
    }

    @Override
    public List<KoperPackSnapshot> listEnabled() {
        List<KoperPackSnapshot> out = new ArrayList<>();
        FullPackLoader.getAllPacks().forEach((folder, meta) -> {
            if (FullPackLoader.isEnabled(folder)) out.add(snap(folder, meta));
        });
        return out;
    }

    @Override
    public boolean isEnabled(String namespace) {
        for (var entry : FullPackLoader.getAllPacks().entrySet()) {
            String folder = entry.getKey();
            KoperMeta meta = entry.getValue();
            String ns = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            if (ns.equals(namespace)) return FullPackLoader.isEnabled(folder);
        }
        return false;
    }

    @Override
    public void setEnabled(String namespace, boolean enabled) {
        for (var entry : FullPackLoader.getAllPacks().entrySet()) {
            String folder = entry.getKey();
            KoperMeta meta = entry.getValue();
            String ns = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            if (ns.equals(namespace)) {
                FullPackLoader.setDisabled(folder, !enabled);
                return;
            }
        }
    }

    @Override
    public void reload() {
        var server = UniversalScriptEngine.getCurrentServer();
        if (server != null) {
            FullpackReloader.reload(server);
        }
    }

    @Override
    public void addItemHook(String itemFullId, String event, Function<KoperContext, InteractionResult> hook) {
        JavaHookRegistry.registerModHook(itemFullId + "/" + event, hook);
    }

    @Override
    public void addEntityHook(String entityFullId, String event, Function<KoperContext, InteractionResult> hook) {
        JavaHookRegistry.registerModHook(entityFullId + "/" + event, hook);
    }

    @Override
    public void addBlockHook(String blockFullId, String event, Function<KoperContext, InteractionResult> hook) {
        JavaHookRegistry.registerModHook(blockFullId + "/" + event, hook);
    }

    private static KoperPackSnapshot snap(String folder, KoperMeta meta) {
        boolean enabled = FullPackLoader.isEnabled(folder);
        if (meta == null) return new KoperPackSnapshot(folder, folder, folder, "?", "", "", enabled);
        return new KoperPackSnapshot(
            folder,
            meta.getEffectiveNamespace(folder),
            meta.name != null ? meta.name : folder,
            meta.version != null ? meta.version : "?",
            meta.author != null ? meta.author : "",
            meta.description != null ? meta.description : "",
            enabled
        );
    }
}
