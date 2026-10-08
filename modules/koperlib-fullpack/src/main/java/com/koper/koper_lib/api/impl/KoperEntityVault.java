package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.EntitiesAPI;
import com.koper.koper_lib.api.KoperEntityRef;
import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.factory.EntityFactory;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperMeta;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KoperEntityVault implements EntitiesAPI {

    @Override
    public Optional<KoperEntityRef> get(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        KoperEntityData data = EntityFactory.getEntityData().get(id);
        if (data == null) return Optional.empty();
        return Optional.of(new KoperEntityDataWrapper(id, data, resolvePackFolder(id.getNamespace())));
    }

    @Override
    public KoperEntityRef require(String fullId) {
        return get(fullId).orElseThrow(() ->
            new IllegalArgumentException("KoperEntityRef not found: " + fullId + " — pack loaded?"));
    }

    @Override
    public List<KoperEntityRef> getAllFromNamespace(String namespace) {
        List<KoperEntityRef> out = new ArrayList<>();
        String folder = resolvePackFolder(namespace);
        EntityFactory.getEntityData().forEach((id, data) -> {
            if (id.getNamespace().equals(namespace)) out.add(new KoperEntityDataWrapper(id, data, folder));
        });
        return out;
    }

    @Override
    public List<KoperEntityRef> getAll() {
        List<KoperEntityRef> out = new ArrayList<>();
        EntityFactory.getEntityData().forEach((id, data) ->
            out.add(new KoperEntityDataWrapper(id, data, resolvePackFolder(id.getNamespace()))));
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
