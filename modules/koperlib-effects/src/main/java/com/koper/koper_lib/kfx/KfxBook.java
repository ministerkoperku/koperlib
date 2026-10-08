package com.koper.koper_lib.kfx;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KfxBook {
    private static final Map<Identifier, KfxDef> DEFS = new ConcurrentHashMap<>();
    private static Map<Identifier, KfxDef> stagedDefs;

    static {
        installBuiltins(DEFS);
    }

    private KfxBook() {}

    public static synchronized void clear() {
        stagedDefs = new ConcurrentHashMap<>();
        installBuiltins(stagedDefs);
        com.koper.koper_lib.kfx.graph.KfxGraphs.beginReload(java.util.Set.of(
            com.koper.koper_lib.kfx.graph.KfxOrigin.JSON,
            com.koper.koper_lib.kfx.graph.KfxOrigin.LUA
        ));
    }

    public static synchronized void register(JsonObject json, String sourceName) {
        boolean versionTwo = json.has("version")
            && json.get("version").isJsonPrimitive()
            && json.getAsJsonPrimitive("version").isNumber()
            && json.get("version").getAsInt() == 2;
        if (json.has("nodes") || versionTwo) {
            var graph = com.koper.koper_lib.kfx.graph.KfxGraphJson.parse(json.toString(), sourceName);
            com.koper.koper_lib.kfx.graph.KfxGraphs.declare(com.koper.koper_lib.kfx.graph.KfxOrigin.JSON, graph);
            return;
        }
        String base = sourceName.endsWith(".json") ? sourceName.substring(0, sourceName.length() - 5) : sourceName;
        Identifier fallback = Identifier.fromNamespaceAndPath("koper_lib", base);
        KfxDef def = KfxDef.fromJson(json, fallback);
        definitionsBeingLoaded().put(def.id(), def);
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KFX] loaded {} ({})", def.id(), def.kind().name().toLowerCase());
    }

    public static KfxDef get(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null && !id.contains(":")) parsed = Identifier.fromNamespaceAndPath("koper_lib", id);
        return parsed != null ? DEFS.get(parsed) : null;
    }

    public static Collection<KfxDef> all() {
        return DEFS.values();
    }

    public static synchronized void finishReload(boolean success) {
        if (stagedDefs == null) {
            if (success) com.koper.koper_lib.kfx.graph.KfxGraphs.linkAll();
            return;
        }
        if (!success) {
            stagedDefs = null;
            com.koper.koper_lib.kfx.graph.KfxGraphs.abortReload();
            return;
        }
        Map<Identifier, KfxDef> candidate = stagedDefs;
        try {
            com.koper.koper_lib.kfx.graph.KfxGraphs.commitReload();
            DEFS.clear();
            DEFS.putAll(candidate);
        } catch (RuntimeException error) {
            com.koper.koper_lib.kfx.graph.KfxGraphs.abortReload();
            throw error;
        } finally {
            stagedDefs = null;
        }
    }

    private static Map<Identifier, KfxDef> definitionsBeingLoaded() {
        return stagedDefs != null ? stagedDefs : DEFS;
    }

    private static void installBuiltins(Map<Identifier, KfxDef> target) {
        putBuiltin(target, "demo_beam", KfxDef.Kind.BEAM, 0xDD55CCFF, 0.45f, 0.28f, 80, false);
        putBuiltin(target, "demo_ring", KfxDef.Kind.RING, 0xDD55CCFF, 1.35f, 0.18f, -1, true);
        putBuiltin(target, "demo_sphere", KfxDef.Kind.SPHERE, 0xDD55CCFF, 1.15f, 0.14f, 100, false);
        putBuiltin(target, "demo_vortex", KfxDef.Kind.VORTEX, 0xCCB24DFF, 1.55f, 0.20f, 160, false);
        putBuiltin(target, "demo_emitter", KfxDef.Kind.EMITTER, 0xDDA5F7FF, 0.24f, 0.08f, 120, false);
        putBuiltin(target, "demo_chargebeam", KfxDef.Kind.CHARGE_BEAM, 0xDDA5F7FF, 0.95f, 0.22f, 90, false);
        putBuiltin(target, "laser_test", KfxDef.Kind.BEAM, 0xDDFF3355, 0.45f, 0.28f, 80, false);
        putBuiltin(target, "blue_ring", KfxDef.Kind.RING, 0xDD55CCFF, 1.35f, 0.18f, -1, true);
    }

    private static void putBuiltin(Map<Identifier, KfxDef> target, String name, KfxDef.Kind kind, int color, float radius, float thickness, int lifetime, boolean loop) {
        Identifier id = Identifier.fromNamespaceAndPath("koper_lib", name);
        target.put(id, new KfxDef(id, kind, color, KfxDef.brighten(color), radius, thickness, lifetime, loop, 72.0f,
            1.4f, kind == KfxDef.Kind.BEAM ? 0.08f : 0.16f, 3.0f, 10.0f,
            kind == KfxDef.Kind.EMITTER ? 46.0f : 0.0f,
            kind == KfxDef.Kind.EMITTER ? 100 : 0,
            36, 0.62f, 0.15f, -0.0045f, 0.965f, radius * 0.12f,
            kind == KfxDef.Kind.EMITTER ? "sphere" : "point",
            kind == KfxDef.Kind.EMITTER ? "star" : "sprite",
            "free", 36.0f, 10.0f, 2.8f, 0.72f,
            "",
            kind == KfxDef.Kind.EMITTER ? 700 : 0,
            kind == KfxDef.Kind.EMITTER ? 0.014f : 0.0f,
            "none", 8, false, 0.65f, 0.08f,
            new KfxLight(kind == KfxDef.Kind.BEAM ? 12.0f : 9.0f, 1.0f, color)));
    }
}
