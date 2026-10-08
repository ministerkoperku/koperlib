package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.BlocksAPI;
import com.koper.koper_lib.api.KoperBlockRef;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// BlocksAPI impl — all the koper blocks in a vault, epic lore and stuff
public class KoperBlockVault implements BlocksAPI {

    @Override
    public Optional<KoperBlockRef> get(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        KoperBlockData data = ContentRegistry.getBlockData(id);
        if (data == null) return Optional.empty();
        return Optional.of(new KoperBlockDataWrapper(id, data, resolvePackFolder(id.getNamespace())));
    }

    @Override
    public KoperBlockRef require(String fullId) {
        return get(fullId).orElseThrow(() ->
            new IllegalArgumentException("KoperBlockRef not found: " + fullId + " — is the pack loaded and enabled?"));
    }

    @Override
    public List<KoperBlockRef> getAllFromNamespace(String namespace) {
        List<KoperBlockRef> out = new ArrayList<>();
        String folder = resolvePackFolder(namespace);
        ContentRegistry.getAllBlockData().forEach((id, data) -> {
            if (id.getNamespace().equals(namespace)) out.add(new KoperBlockDataWrapper(id, data, folder));
        });
        return out;
    }

    @Override
    public List<KoperBlockRef> getAll() {
        List<KoperBlockRef> out = new ArrayList<>();
        ContentRegistry.getAllBlockData().forEach((id, data) ->
            out.add(new KoperBlockDataWrapper(id, data, resolvePackFolder(id.getNamespace()))));
        return out;
    }

    private static String resolvePackFolder(String namespace) {
        for (var entry : FullPackLoader.getAllPacks().entrySet()) {
            KoperMeta meta = entry.getValue();
            if (meta != null && namespace.equals(meta.getEffectiveNamespace(entry.getKey()))) return entry.getKey();
        }
        return namespace;
    }
}
