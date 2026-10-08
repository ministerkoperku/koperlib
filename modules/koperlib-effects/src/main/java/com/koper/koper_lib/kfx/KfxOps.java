package com.koper.koper_lib.kfx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// open program-op registry. builtins carry a native opcode, addons can register pure-java ops.
// metadata (opcode/native flag/aliases) is data-only so the compiler + parser work without the client renderer.
public final class KfxOps {
    public record Meta(String name, int opcode, boolean nativeBatch) {}

    private static final Map<String, Meta> META = new ConcurrentHashMap<>();
    private static final Map<String, KfxOp> DRAW = new ConcurrentHashMap<>();
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    private KfxOps() {}

    static {
        builtin("ring_particles", 1, true);
        builtin("pentagram_particles", 2, true, "pentagram", "star_polygon", "sigil", "polygon_star");
        builtin("ring_band", 3, false, "band", "circle_band");
        builtin("orb", 4, true, "point", "dot");
        // beam, ribbon, trail, mesh and decal are geometry, drawn java side on every backend; the native
        // batch only knows particles and used to scatter them as dots along the line
        builtin("beam", 5, false, "laser", "ray");
        builtin("burst_ring", 6, true, "ring_burst", "shockwave");
        builtin("stream", 7, true, "flow", "trail", "line_particles");
        builtin("spiral", 8, true, "helix", "vortex_line");
        builtin("ribbon", 9, false);
        builtin("trail", 10, false);
        builtin("mesh", 11, false);
        builtin("decal", 12, false);
        builtin("light", 13, false);
        builtin("group", 14, false);
        // a registered Java effect with its own simulation, see kfx.fx.KfxFxBook
        builtin("fx", 15, false, "program", "script");
    }

    private static void builtin(String name, int opcode, boolean batch, String... aliases) {
        META.put(name, new Meta(name, opcode, batch));
        for (String a : aliases) ALIASES.put(key(a), name);
    }

    // addon api — full control. opcode -1 = java only, nativeBatch false = always drawn java-side
    public static void register(String name, int nativeOpcode, boolean nativeBatch, KfxOp handler, String... aliases) {
        String key = key(name);
        META.put(key, new Meta(key, nativeOpcode, nativeBatch));
        DRAW.put(key, handler);
        for (String a : aliases) ALIASES.put(key(a), key);
    }

    public static void register(String name, KfxOp handler) {
        register(name, -1, false, handler);
    }

    // builtins wire their draw lambdas here once the renderer class loads
    static void registerDraw(String name, KfxOp handler) {
        DRAW.put(key(name), handler);
    }

    public static String canonical(String raw) {
        if (raw == null) return "ring_particles";
        String key = key(raw.replace("-", "_"));
        String aliased = ALIASES.get(key);
        if (aliased != null) key = aliased;
        return META.containsKey(key) ? key : "ring_particles";
    }

    public static int opcodeForName(String name) {
        Meta m = META.get(canonical(name));
        return m != null ? m.opcode : 0;
    }

    public static boolean nativeBatch(String name) {
        Meta m = META.get(canonical(name));
        return m != null && m.nativeBatch;
    }

    static KfxOp draw(String name) {
        return DRAW.get(canonical(name));
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
