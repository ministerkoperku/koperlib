package com.koper.koper_lib.kender;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicReference;

// light for a kontra block is computed ON the kontraktion: sampled from the HOST level at the block's
// transformed world pos. it is NEVER computed in a phantom level — the phantom level (functional blocks)
// must not own a light engine. this is the single seam the render chat plugs its sampler into via PROBE.
// worldPos comes from KoperPhys.kontraBlockWorldPos(id, ox,oy,oz). koper render: set PROBE on client init.
public interface KontraLight {

    // packed light (block<<4 | sky<<20) for one kontra block, sampled at its current world position
    int packedLightAt(long kontraId, BlockPos localPos, BlockPos worldPos, BlockState state);

    // placeholder until the render chat installs a host-level sampler — full bright so nothing renders black
    KontraLight FULL_BRIGHT = (id, local, world, state) -> 0xF000F0;

    AtomicReference<KontraLight> PROBE = new AtomicReference<>(FULL_BRIGHT);

    static int sample(long kontraId, BlockPos localPos, BlockPos worldPos, BlockState state) {
        return PROBE.get().packedLightAt(kontraId, localPos, worldPos, state);
    }
}
