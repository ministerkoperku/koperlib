package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KfxPrimitiveRegistry {
    private static final KfxPrimitiveRegistry BUILTIN = createBuiltins();
    private final Map<Identifier, KfxPrimitive> entries = new LinkedHashMap<>();

    public static KfxPrimitiveRegistry builtin() { return BUILTIN; }

    public synchronized void register(KfxPrimitive primitive) {
        if (entries.putIfAbsent(primitive.id(), primitive) != null) {
            throw new IllegalStateException("duplicate KFX primitive " + primitive.id());
        }
    }

    public synchronized KfxPrimitive get(Identifier id) { return entries.get(id); }
    public synchronized Map<Identifier, KfxPrimitive> entries() { return Map.copyOf(entries); }

    private static KfxPrimitiveRegistry createBuiltins() {
        KfxPrimitiveRegistry registry = new KfxPrimitiveRegistry();
        for (String name : new String[]{"particles", "beam", "ribbon", "trail", "mesh", "decal", "light", "group"}) {
            Identifier id = Identifier.fromNamespaceAndPath("koper_lib", name);
            registry.register(new Builtin(id,
                (node, batches) -> batches.add("native", node),
                (node, batches) -> batches.add("portable", node)));
        }
        return registry;
    }

    private record Builtin(Identifier id, Compiler nativeCompiler, Compiler portableCompiler)
        implements KfxPrimitive {}
}
