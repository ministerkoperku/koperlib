package com.koper.koper_lib.api;

import net.minecraft.resources.Identifier;

public interface KoperDimensionRef {
    String namespace();
    String path();
    String fullId();

    Identifier asResourceKey();

    String displayName();
    String baseType();
    String generator();
    String biome();
    String environment();
    String effects();

    int minY();
    int height();
    int logicalHeight();
    double coordinateScale();
    float ambientLight();

    boolean hasSkylight();
    boolean hasCeiling();
    boolean ultrawarm();
    boolean natural();
    boolean bedWorks();
    boolean piglinSafe();
    boolean respawnAnchorWorks();
    boolean hasRaids();
    boolean hasEnderDragonFight();
    int monsterSpawnLight();
    String infiniburn();
    int fixedTime(); // -1 = no fixed time

    boolean isPackEnabled();
}
