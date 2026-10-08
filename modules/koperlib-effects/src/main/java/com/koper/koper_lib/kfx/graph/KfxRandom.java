package com.koper.koper_lib.kfx.graph;

final class KfxRandom {
    private KfxRandom() {}

    static double unit(long castSeed, String propertyPath) {
        long mixed = mix64(castSeed ^ hashPath(propertyPath));
        return (mixed >>> 11) * 0x1.0p-53;
    }

    static long derivedSeed(long castSeed, String propertyPath) {
        return mix64(castSeed ^ hashPath(propertyPath));
    }

    private static long hashPath(String path) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < path.length(); i++) {
            hash ^= path.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
