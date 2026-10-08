package com.koper.koper_lib.kender;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

// light for kontra blocks. the render loop used to ask the light engine twice per block per FRAME,
// which on a real ship is a few hundred thousand chunk walks a second for a value that changes at
// most 20 times a second. now it's cached per block and refreshed a slice at a time.
//
// heads up: this only samples the HOST world at the block's transformed position. a torch sitting ON
// the kontra is not a light source in the host light engine, so it currently lights nothing —
// that's the emitter half, see KontraLightSpill.
public final class KontraLightBook {

    // every block refreshed once per this many frames. 8 @ 60fps = ~7/s, faster than tick rate.
    private static final int SLICES = 8;
    private static final int MASK = SLICES - 1;

    private static int frameSlice;

    private KontraLightBook() {}

    public static void nextFrame() { frameSlice = (frameSlice + 1) & MASK; }

    // packed light (block<<4 | sky<<20) for one block, straight from cache unless this is its slice
    public static int sample(KenderClientState.KontraRenderData k, int index,
                             LevelLightEngine lightEngine, BlockPos worldPos) {
        int[] cache = k.lightCache;
        if (cache == null || cache.length != k.states.length) {
            cache = new int[k.states.length];
            java.util.Arrays.fill(cache, -1);
            k.lightCache = cache;
        }
        int cached = cache[index];
        if (cached >= 0 && (index & MASK) != frameSlice) return cached;

        int block = lightEngine.getLayerListener(LightLayer.BLOCK).getLightValue(worldPos);
        int sky = lightEngine.getLayerListener(LightLayer.SKY).getLightValue(worldPos);
        int emit = KontraLightSpill.ownEmission(k, index);
        if (emit > block) block = emit;
        int packed = (block << 4) | (sky << 20);
        cache[index] = packed;
        return packed;
    }

    // block moved / changed -> drop its cached value so the next frame resamples it
    public static void invalidate(KenderClientState.KontraRenderData k, int index) {
        int[] cache = k.lightCache;
        if (cache != null && index >= 0 && index < cache.length) cache[index] = -1;
    }

    public static void invalidateAll(KenderClientState.KontraRenderData k) {
        if (k.lightCache != null) java.util.Arrays.fill(k.lightCache, -1);
    }
}
