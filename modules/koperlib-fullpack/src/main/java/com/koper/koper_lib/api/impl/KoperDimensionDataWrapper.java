package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.KoperDimensionRef;
import com.koper.koper_lib.data.KoperDimensionData;
import com.koper.koper_lib.loader.FullPackLoader;
import net.minecraft.resources.Identifier;

class KoperDimensionDataWrapper implements KoperDimensionRef {

    private final Identifier id;
    private final KoperDimensionData data;
    private final String packFolder;

    KoperDimensionDataWrapper(Identifier id, KoperDimensionData data, String packFolder) {
        this.id = id;
        this.data = data;
        this.packFolder = packFolder;
    }

    @Override public String namespace() { return id.getNamespace(); }
    @Override public String path()      { return id.getPath(); }
    @Override public String fullId()    { return id.toString(); }

    @Override public Identifier asResourceKey() { return id; }

    @Override public String displayName() { return data.name != null ? data.name : path(); }
    @Override public String baseType()    { return data.baseType != null ? data.baseType : "custom"; }
    @Override public String generator()   { return data.generator != null ? data.generator : "void"; }
    @Override public String biome()      { return data.biome != null ? data.biome : "minecraft:the_void"; }
    @Override public String environment(){ return data.environment != null ? data.environment : "overworld"; }
    @Override public String effects()     { return data.effects != null ? data.effects : "minecraft:overworld"; }

    @Override public int minY()           { return data.minY != null ? data.minY : -64; }
    @Override public int height()         { return data.height != null ? data.height : 384; }
    @Override public int logicalHeight() { return data.logicalHeight != null ? data.logicalHeight : 384; }
    @Override public double coordinateScale() { return data.coordinateScale != null ? data.coordinateScale : 1.0; }
    @Override public float ambientLight() { return data.ambientLight != null ? data.ambientLight : 0f; }

    @Override public boolean hasSkylight() { return !Boolean.FALSE.equals(data.hasSkylight); }
    @Override public boolean hasCeiling()  { return Boolean.TRUE.equals(data.hasCeiling); }
    @Override public boolean ultrawarm()    { return Boolean.TRUE.equals(data.ultrawarm); }
    @Override public boolean natural()    { return !Boolean.FALSE.equals(data.natural); }
    @Override public boolean bedWorks()   { return !Boolean.FALSE.equals(data.bedWorks); }
    @Override public boolean piglinSafe() { return Boolean.TRUE.equals(data.piglinSafe); }
    @Override public boolean respawnAnchorWorks() { return Boolean.TRUE.equals(data.respawnAnchorWorks); }
    @Override public boolean hasRaids()   { return !Boolean.FALSE.equals(data.hasRaids); }
    @Override public boolean hasEnderDragonFight() { return Boolean.TRUE.equals(data.hasEnderDragonFight); }
    @Override public int monsterSpawnLight() { return data.monsterSpawnLight != null ? data.monsterSpawnLight : 7; }
    @Override public String infiniburn()  { return data.infiniburn != null ? data.infiniburn : "#minecraft:infiniburn_overworld"; }
    @Override public int fixedTime()     { return data.fixedTime != null ? data.fixedTime : -1; }

    @Override
    public boolean isPackEnabled() {
        return FullPackLoader.isEnabled(packFolder);
    }
}
