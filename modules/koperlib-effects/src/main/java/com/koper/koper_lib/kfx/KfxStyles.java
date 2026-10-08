package com.koper.koper_lib.kfx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// particle look registry. builtins keep codes 0-7 (drawn by KfxRenderer's switch),
// addon styles grab codes >=100 and bring their own KfxStyle draw lambda.
public final class KfxStyles {
    private static final Map<String, Integer> CODES = new ConcurrentHashMap<>();
    private static final Map<Integer, KfxStyle> DRAWERS = new ConcurrentHashMap<>();
    private static final AtomicInteger nextCode = new AtomicInteger(100);

    private KfxStyles() {}

    static {
        alias("sprite", 0);
        alias("spark", 1); alias("streak", 1); alias("trail", 1);
        alias("star", 2); alias("flare", 2);
        alias("ring", 3); alias("halo", 3);
        alias("shard", 4); alias("diamond", 4); alias("crystal", 4);
        alias("cube", 5); alias("box", 5); alias("voxel", 5);
        alias("tetra", 6); alias("tetrahedron", 6); alias("pyramid", 6);
        alias("orb3d", 7); alias("orb", 7); alias("mini_sphere", 7);
        alias("sphere3d", 7); alias("ball", 7); alias("sphere", 7);
    }

    public static void alias(String name, int code) {
        if (name != null) CODES.put(name.toLowerCase(), code);
    }

    // addon api — register a java-drawn style, get back its code
    public static int register(String name, KfxStyle drawer) {
        int code = nextCode.getAndIncrement();
        DRAWERS.put(code, drawer);
        alias(name, code);
        return code;
    }

    public static int codeFor(String name) {
        if (name == null) return 0;
        return CODES.getOrDefault(name.toLowerCase(), 0);
    }

    static KfxStyle drawer(int code) {
        return DRAWERS.get(code);
    }
}
