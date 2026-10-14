package com.koper.koper_lib.kender;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

// A torch bolted to a kontra is NOT a light source in the host world's light engine — the host has
// no block there, the kontra's blocks only exist in our grid. So sampling host light at the block's
// transformed position gives you whatever the surrounding world happens to be, and your lamps do
// nothing. That's the "kontra has no lighting" bug.
//
// This does the missing half: flood the kontra's OWN grid from its OWN emitters, in local space,
// and max it into the sampled host light. Rebuilt only when the block set changes, never per frame.
public final class KontraLightSpill {

    private static final Direction[] DIRS = Direction.values();

    private KontraLightSpill() {}

    public static int ownEmission(KenderClientState.KontraRenderData k, int index) {
        if (!com.koper.koper_lib.physics.KhysicsConfig.get().kontraFancyLight) return 0;
        byte[] own = k.ownLight;
        if (own == null || k.ownLightDirty || own.length != k.states.length) {
            own = rebuild(k);
            k.ownLight = own;
            k.ownLightDirty = false;
        }
        return index >= 0 && index < own.length ? own[index] : 0;
    }

    public static void markDirty(KenderClientState.KontraRenderData k) {
        k.ownLightDirty = true;
        KontraLightBook.invalidateAll(k);
    }

    private static byte[] rebuild(KenderClientState.KontraRenderData k) {
        int n = k.states.length;
        int[] emission = new int[n];
        boolean[] opaque = new boolean[n];
        for (int i = 0; i < n; i++) {
            BlockState s = k.states[i];
            if (s == null || s.isAir()) continue;
            emission[i] = s.getLightEmission();
            opaque[i] = s.canOcclude();
        }
        return flood(k.locals, emission, opaque);
    }

    // BFS over the grid's local volume, the empty cells between blocks included. the old flood only
    // walked cells the kontra owns, so a torch on a deck reached the block under it and nothing else,
    // the air next to it was not a cell. light drops by 1 per step like vanilla. opaque blocks stop it
    // and take the brightest light touching any of their faces, same as a world block's lit face.
    static byte[] flood(int[] locals, int[] emission, boolean[] opaque) {
        int n = emission.length;
        byte[] out = new byte[n];
        var index = new Long2IntOpenHashMap(n * 2);
        index.defaultReturnValue(-1);
        var light = new Long2ByteOpenHashMap();
        var queue = new LongArrayFIFOQueue();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int x = locals[i * 3], y = locals[i * 3 + 1], z = locals[i * 3 + 2];
            long key = BlockPos.asLong(x, y, z);
            index.put(key, i);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (emission[i] > 0) {
                light.put(key, (byte) Math.min(15, emission[i]));
                queue.enqueue(key);
            }
        }
        if (queue.isEmpty()) return out;
        // one cell of air around the hull is enough to carry light over its outer faces
        minX--; minY--; minZ--; maxX++; maxY++; maxZ++;

        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            int next = light.get(key) - 1;
            if (next <= 0) continue;
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            for (Direction d : DIRS) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                if (nx < minX || ny < minY || nz < minZ || nx > maxX || ny > maxY || nz > maxZ) continue;
                long nk = BlockPos.asLong(nx, ny, nz);
                int at = index.get(nk);
                if (at >= 0 && opaque[at]) continue;
                if (light.get(nk) >= next) continue;
                light.put(nk, (byte) next);
                queue.enqueue(nk);
            }
        }

        for (int i = 0; i < n; i++) {
            int x = locals[i * 3], y = locals[i * 3 + 1], z = locals[i * 3 + 2];
            int best = Math.min(15, emission[i]);
            if (!opaque[i]) best = Math.max(best, light.get(BlockPos.asLong(x, y, z)));
            else for (Direction d : DIRS)
                best = Math.max(best, light.get(BlockPos.asLong(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())));
            out[i] = (byte) best;
        }
        return out;
    }
}
