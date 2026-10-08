package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.KoperBlockRef;
import com.koper.koper_lib.data.KoperBlockData;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.List;

// KoperBlockRef wrapper over raw KoperBlockData — same pattern as KoperItemDataWrapper
public class KoperBlockDataWrapper implements KoperBlockRef {

    private final Identifier id;
    private final KoperBlockData data;
    private final String packFolder;

    KoperBlockDataWrapper(Identifier id, KoperBlockData data, String packFolder) {
        this.id = id;
        this.data = data;
        this.packFolder = packFolder;
    }

    @Override public String namespace() { return id.getNamespace(); }
    @Override public String path()      { return id.getPath(); }
    @Override public String fullId()    { return id.toString(); }

    @Override
    public Block asBlock() {
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    @Override public float   hardness()     { return data.hardness   != null ? data.hardness   : 1.5f; }
    @Override public float   resistance()   { return data.resistance != null ? data.resistance : 6.0f; }
    @Override public int     lightLevel()   { return data.lightLevel != null ? data.lightLevel : 0; }
    @Override public String  sound()        { return data.sound      != null ? data.sound      : "stone"; }
    @Override public float   slipperiness() { return data.slipperiness != null ? data.slipperiness : 0.6f; }
    @Override public boolean dropsSelf()    { return Boolean.TRUE.equals(data.dropsSelf); }
    @Override public boolean transparent()  { return Boolean.TRUE.equals(data.transparent); }
    @Override public boolean collidable()   { return !Boolean.FALSE.equals(data.collidable); }

    @Override public String  miningTool()   { return data.miningTool  != null ? data.miningTool  : ""; }
    @Override public boolean requiresTool() { return Boolean.TRUE.equals(data.requiresTool); }
    @Override public int     miningLevel()  { return data.miningLevel != null ? data.miningLevel : 0; }

    @Override public String renderType()    { return data.renderType  != null ? data.renderType  : "solid"; }
    @Override public String shape()         { return data.shape       != null ? data.shape       : "cube"; }
    @Override public int    redstonePower() { return data.redstonePower != null ? data.redstonePower : 0; }
    @Override public float  mass()          { return data.mass        != null ? data.mass        : 1.0f; }

    @Override public String texture()  { return data.texture != null ? data.texture : ""; }
    @Override public String logic()    { return data.logic != null ? data.logic : ""; }
    @Override public List<String> scripts() { return data.scripts != null ? data.scripts : List.of(); }

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
