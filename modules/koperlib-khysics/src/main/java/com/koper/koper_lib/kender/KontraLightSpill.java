package com.koper.koper_lib.kender;

import com.koper.koper_lib.config.KoperLibConfig;
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

    // plain BFS, level drops by 1 per step. opaque blocks still receive light (their face is lit)
    // but don't pass it on, so a lamp doesn't shine through the hull.
    private static byte[] rebuild(KenderClientState.KontraRenderData k) {
        int n = k.states.length;
        byte[] out = new byte[n];
        var queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();

        for (int i = 0; i < n; i++) {
            BlockState s = k.states[i];
            if (s == null) continue;
            int emit = s.getLightEmission();
            if (emit > 0) {
                out[i] = (byte) emit;
                queue.enqueue(i);
            }
        }
        if (queue.isEmpty()) return out;

        var scratch = new BlockPos.MutableBlockPos();
        Direction[] dirs = Direction.values();
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            int next = out[i] - 1;
            if (next <= 0) continue;
            int lx = k.locals[i * 3];
            int ly = k.locals[i * 3 + 1];
            int lz = k.locals[i * 3 + 2];
            for (Direction d : dirs) {
                scratch.set(lx + d.getStepX(), ly + d.getStepY(), lz + d.getStepZ());
                Integer ni = k.indicesByLocal.get(scratch);
                if (ni == null) continue;
                int at = ni;
                if (out[at] >= next) continue;
                out[at] = (byte) next;
                BlockState ns = k.states[at];
                if (ns == null || !ns.canOcclude()) queue.enqueue(at);
            }
        }
        return out;
    }
}
