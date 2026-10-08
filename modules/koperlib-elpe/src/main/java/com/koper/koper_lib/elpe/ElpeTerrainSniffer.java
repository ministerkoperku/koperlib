package com.koper.koper_lib.elpe;

import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

// turns mc sections into the 512 byte bitsets elpe eats. slab/stair/fence = solid cube, elpe has no shapes
public final class ElpeTerrainSniffer {
    // blockstates never change collision, so ask once per state and remember. server thread only
    private static final Reference2BooleanOpenHashMap<BlockState> SOLID = new Reference2BooleanOpenHashMap<>();

    private ElpeTerrainSniffer() {}

    public static boolean solid(BlockState state) {
        if (state.isAir()) return false;
        if (SOLID.containsKey(state)) return SOLID.getBoolean(state);
        boolean s;
        try {
            s = !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable weirdBlock) {
            // some modded blocks need a real level for their shape. guess solid, walls are safer than holes
            s = true;
        }
        SOLID.put(state, s);
        return s;
    }

    // fills out, returns false when the whole section is air for elpe (then send null, saves 512 bytes)
    public static boolean sniff(LevelChunkSection section, long[] out) {
        java.util.Arrays.fill(out, 0L);
        if (section == null || section.hasOnlyAir()) return false;
        if (!section.maybeHas(ElpeTerrainSniffer::solid)) return false;
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (solid(section.getBlockState(x, y, z))) {
                        int i = (y << 8) | (z << 4) | x;
                        out[i >>> 6] |= 1L << (i & 63);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
