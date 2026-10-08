package com.koper.koper_lib.kfx.fx;

/** Cheap smooth 3D value noise and its curl, for turbulence that looks like air instead of jitter. */
public final class KfxNoise {
    private KfxNoise() {}

    /** Smooth noise in -1..1. */
    public static float noise(float x, float y, float z) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        float fx = x - x0, fy = y - y0, fz = z - z0;
        float u = fade(fx), v = fade(fy), w = fade(fz);
        float c000 = hash(x0, y0, z0), c100 = hash(x0 + 1, y0, z0);
        float c010 = hash(x0, y0 + 1, z0), c110 = hash(x0 + 1, y0 + 1, z0);
        float c001 = hash(x0, y0, z0 + 1), c101 = hash(x0 + 1, y0, z0 + 1);
        float c011 = hash(x0, y0 + 1, z0 + 1), c111 = hash(x0 + 1, y0 + 1, z0 + 1);
        float a = lerp(lerp(c000, c100, u), lerp(c010, c110, u), v);
        float b = lerp(lerp(c001, c101, u), lerp(c011, c111, u), v);
        return lerp(a, b, w) * 2.0f - 1.0f;
    }

    /** Divergence-free flow at a point, from three offset noise fields. Roughly unit scale. */
    public static void curl(float x, float y, float z, float[] out) {
        // curl of the potential (A, B, C), three noise fields offset far apart
        final float e = 0.25f, inv = 1.0f / (2 * e);
        float dCdy = noise(x + 71.9f, y + e, z) - noise(x + 71.9f, y - e, z);
        float dBdz = noise(x + 9.7f, y, z + e) - noise(x + 9.7f, y, z - e);
        float dAdz = noise(x, y, z + 31.4f + e) - noise(x, y, z + 31.4f - e);
        float dCdx = noise(x + 71.9f + e, y, z) - noise(x + 71.9f - e, y, z);
        float dBdx = noise(x + 9.7f + e, y, z) - noise(x + 9.7f - e, y, z);
        float dAdy = noise(x, y + e, z + 31.4f) - noise(x, y - e, z + 31.4f);
        out[0] = (dCdy - dBdz) * inv;
        out[1] = (dAdz - dCdx) * inv;
        out[2] = (dBdx - dAdy) * inv;
    }

    /** Stable 0..1 hash of an integer and a seed, for per-index choices that must not flicker. */
    public static float hash01(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return (h >>> 40) * (1.0f / (1L << 24));
    }

    private static float hash(int x, int y, int z) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA6B; h ^= h >>> 13; h *= 0xC2B2AE35; h ^= h >>> 16;
        return (h >>> 8) * (1.0f / 16777216.0f);
    }

    private static int floor(float v) {
        int i = (int)v;
        return v < i ? i - 1 : i;
    }

    private static float fade(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
