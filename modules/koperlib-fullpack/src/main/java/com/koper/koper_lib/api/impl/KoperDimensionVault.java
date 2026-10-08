package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.DimensionsAPI;
import com.koper.koper_lib.api.KoperDimensionRef;
import com.koper.koper_lib.data.KoperDimensionData;
import com.koper.koper_lib.factory.DimensionFactory;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KoperDimensionVault implements DimensionsAPI {

    @Override
    public Optional<KoperDimensionRef> get(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        KoperDimensionData data = DimensionFactory.getDimensionData().get(id);
        if (data == null) return Optional.empty();
        return Optional.of(new KoperDimensionDataWrapper(id, data, resolvePackFolder(id.getNamespace())));
    }

    @Override
    public KoperDimensionRef require(String fullId) {
        return get(fullId).orElseThrow(() ->
            new IllegalArgumentException("KoperDimensionRef not found: " + fullId));
    }

    @Override
    public List<KoperDimensionRef> getAllFromNamespace(String namespace) {
        List<KoperDimensionRef> out = new ArrayList<>();
        String folder = resolvePackFolder(namespace);
        DimensionFactory.getDimensionData().forEach((id, data) -> {
            if (id.getNamespace().equals(namespace)) out.add(new KoperDimensionDataWrapper(id, data, folder));
        });
        return out;
    }

    @Override
    public List<KoperDimensionRef> getAll() {
        List<KoperDimensionRef> out = new ArrayList<>();
        DimensionFactory.getDimensionData().forEach((id, data) ->
            out.add(new KoperDimensionDataWrapper(id, data, resolvePackFolder(id.getNamespace()))));
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
