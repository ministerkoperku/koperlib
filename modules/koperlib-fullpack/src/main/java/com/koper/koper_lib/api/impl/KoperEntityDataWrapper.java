package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.KoperEntityRef;
import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.List;

class KoperEntityDataWrapper implements KoperEntityRef {

    private final Identifier id;
    private final KoperEntityData data;
    private final String packFolder;

    KoperEntityDataWrapper(Identifier id, KoperEntityData data, String packFolder) {
        this.id = id;
        this.data = data;
        this.packFolder = packFolder;
    }

    @Override public String namespace() { return id.getNamespace(); }
    @Override public String path()      { return id.getPath(); }
    @Override public String fullId()    { return id.toString(); }

    @Override
    public EntityType<?> asEntityType() {
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    @Override public float maxHealth()      { return data.maxHealth != null ? data.maxHealth : 10f; }
    @Override public float movementSpeed()  { return data.movementSpeed != null ? data.movementSpeed : 0.2f; }
    @Override public float attackDamage()  { return data.attackDamage != null ? data.attackDamage : 0f; }
    @Override public String aiType()       { return data.aiType != null ? data.aiType : "PASSIVE"; }
    @Override public String model()        { return data.model != null ? data.model : ""; }
    @Override public String texture()      { return data.texture != null ? data.texture : ""; }
    @Override public boolean burnsInDaylight() { return Boolean.TRUE.equals(data.burnsInDaylight); }
    @Override public boolean ranged()      { return Boolean.TRUE.equals(data.ranged); }
    @Override public float width()        { return data.width != null ? data.width : 0.6f; }
    @Override public float height()       { return data.height != null ? data.height : 1.8f; }
    @Override public boolean persistent()  { return Boolean.TRUE.equals(data.persistent); }
    @Override public boolean noAi()       { return Boolean.TRUE.equals(data.noAi); }
    @Override public boolean baby()       { return Boolean.TRUE.equals(data.baby); }
    @Override public String tamingItem()  { return data.tamingItem != null ? data.tamingItem : ""; }
    @Override public boolean rideable()    { return Boolean.TRUE.equals(data.rideable); }
    @Override public float rideSpeed()    { return data.rideSpeed != null ? data.rideSpeed : 0.2f; }

    @Override
    public List<String> entityAi() {
        return data.entityAi != null ? Collections.unmodifiableList(data.entityAi) : List.of();
    }

    @Override
    public String logic() {
        return data.logic != null ? data.logic : "";
    }

    @Override
    public List<String> scripts() {
        return data.scripts != null ? Collections.unmodifiableList(data.scripts) : List.of();
    }

    @Override
    public void fireEvent(ScriptEvent event, Object... args) {
        if (data.logic != null && !data.logic.isEmpty()) UniversalScriptEngine.call(data.logic, event, args);
        for (String script : scripts()) UniversalScriptEngine.call(script, event, args);
    }

    @Override
    public boolean isPackEnabled() {
        return FullPackLoader.isEnabled(packFolder);
    }
}
