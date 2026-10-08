package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KfxMaterialBook {
    public enum Blend { ALPHA, ADDITIVE, MULTIPLY }
    public enum Depth { TEST, ALWAYS, DECAL_BIAS }
    public record Material(Identifier id, Identifier texture, Blend blend, boolean emissive, Depth depth) {}

    private final Map<Identifier, Material> materials = new LinkedHashMap<>();
    private static final KfxMaterialBook BUILTIN = new KfxMaterialBook();

    public static KfxMaterialBook builtin() { return BUILTIN; }

    public KfxMaterialBook() {
        register(new Material(Identifier.parse("koper_lib:additive"), Identifier.parse("koper_lib:kfx/white"),
            Blend.ADDITIVE, true, Depth.TEST));
        register(new Material(Identifier.parse("koper_lib:translucent"), Identifier.parse("koper_lib:kfx/white"),
            Blend.ALPHA, false, Depth.TEST));
        register(new Material(Identifier.parse("koper_lib:decal"), Identifier.parse("koper_lib:kfx/white"),
            Blend.ALPHA, true, Depth.DECAL_BIAS));
    }

    public synchronized void register(Material material) {
        if (materials.putIfAbsent(material.id(), material) != null) {
            throw new IllegalStateException("duplicate KFX material " + material.id());
        }
    }
    public synchronized Material get(Identifier id) { return materials.get(id); }
    public synchronized Map<Identifier, Material> entries() { return Map.copyOf(materials); }
}
