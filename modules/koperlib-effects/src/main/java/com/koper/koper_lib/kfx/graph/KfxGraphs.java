package com.koper.koper_lib.kfx.graph;

import java.util.Map;

/** Process-wide graph declaration registry shared by JSON, Lua, and Java authoring paths. */
public final class KfxGraphs {
    private static final KfxGraphRegistry REGISTRY = new KfxGraphRegistry();

    private KfxGraphs() {}

    public static void declare(KfxOrigin origin, KfxGraph graph) {
        REGISTRY.declare(origin, graph);
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KFX] declared {} graph {}",
            origin.name().toLowerCase(java.util.Locale.ROOT), graph.id());
    }

    public static KfxLinkedGraph linked(String id) {
        return REGISTRY.linked(id);
    }

    public static KfxLinkedGraph runtimeLinked(String id) {
        return REGISTRY.runtimeLinked(id);
    }

    public static Map<String, KfxLinkedGraph> linkAll() {
        return REGISTRY.linkAll();
    }

    public static void beginReload(java.util.Set<KfxOrigin> rebuiltOrigins) {
        REGISTRY.beginReload(rebuiltOrigins);
    }

    public static Map<String, KfxLinkedGraph> commitReload() {
        return REGISTRY.commitReload();
    }

    public static void abortReload() {
        REGISTRY.abortReload();
    }

    public static void clearOrigin(KfxOrigin origin) {
        REGISTRY.clearOrigin(origin);
    }
}
