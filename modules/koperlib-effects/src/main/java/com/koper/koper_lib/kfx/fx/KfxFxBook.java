package com.koper.koper_lib.kfx.fx;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of Java effects that a {@code koper_lib:render/fx} graph node can name.
 *
 * <p>Registration is client side: the server only sends the effect id and its values. A graph naming
 * an id no client registered still spawns, logs one error and draws nothing for that node.
 */
public final class KfxFxBook {
    private static final Map<String, KfxFx.Factory> FACTORIES = new ConcurrentHashMap<>();

    private KfxFxBook() {}

    /** Registers an effect. Ids are namespaced like {@code mymod:laser/frost}. A second id is an error. */
    public static void register(String id, KfxFx.Factory factory) {
        if (id == null || !id.contains(":")) throw new IllegalArgumentException("KFX fx id must be namespaced: " + id);
        if (FACTORIES.putIfAbsent(id, factory) != null) throw new IllegalStateException("duplicate KFX fx " + id);
    }

    public static KfxFx.Factory get(String id) {
        return FACTORIES.get(id);
    }

    public static Set<String> ids() {
        return new TreeSet<>(FACTORIES.keySet());
    }

    /** Registered ids starting with a prefix, for example every {@code koper_lib:laser/} effect. */
    public static Set<String> ids(String prefix) {
        Set<String> out = new TreeSet<>();
        for (String id : FACTORIES.keySet()) if (id.startsWith(prefix)) out.add(id);
        return out;
    }
}
