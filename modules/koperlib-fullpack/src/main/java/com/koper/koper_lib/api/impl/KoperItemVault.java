package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.FullPackJarsAPI;
import com.koper.koper_lib.api.KoperItemRef;
import com.koper.koper_lib.data.KoperItemData;
import com.koper.koper_lib.loader.ContentRegistry;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// ItemsAPI impl — the vault where every json item lives
public class KoperItemVault implements FullPackJarsAPI {

    @Override
    public Optional<KoperItemRef> get(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        KoperItemData data = ContentRegistry.getItemData(id);
        if (data == null) return Optional.empty();
        return Optional.of(new KoperItemDataWrapper(id, data, resolvePackFolder(id.getNamespace())));
    }

    @Override
    public KoperItemRef require(String fullId) {
        return get(fullId).orElseThrow(() ->
            new IllegalArgumentException("KoperItemRef not found: " + fullId + " — check pack is loaded and enabled"));
    }

    @Override
    public List<KoperItemRef> getAllFromNamespace(String namespace) {
        List<KoperItemRef> out = new ArrayList<>();
        String packFolder = resolvePackFolder(namespace);
        ContentRegistry.getAllItemData().forEach((id, data) -> {
            if (id.getNamespace().equals(namespace)) {
                out.add(new KoperItemDataWrapper(id, data, packFolder));
            }
        });
        return out;
    }

    @Override
    public List<KoperItemRef> getAll() {
        List<KoperItemRef> out = new ArrayList<>();
        ContentRegistry.getAllItemData().forEach((id, data) -> {
            String folder = resolvePackFolder(id.getNamespace());
            out.add(new KoperItemDataWrapper(id, data, folder));
        });
        return out;
    }

    // namespace → folder name (best effort — namespace can differ from folder name)
    private static String resolvePackFolder(String namespace) {
        for (var entry : FullPackLoader.getAllPacks().entrySet()) {
            KoperMeta meta = entry.getValue();
            if (meta != null && namespace.equals(meta.getEffectiveNamespace(entry.getKey()))) {
                return entry.getKey();
            }
        }
        // fallback: treat namespace as folder name
        return namespace;
    }
}
