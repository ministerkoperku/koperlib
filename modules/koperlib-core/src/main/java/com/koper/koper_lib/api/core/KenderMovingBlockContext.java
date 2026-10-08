package com.koper.koper_lib.api.core;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;

/** World/model context exposed by a moving grid without depending on Khysics classes. */
public interface KenderMovingBlockContext extends BlockAndTintGetter {
    BlockPos kenderWorldPos();
    long kenderNeighbourSignature();
    // the cell's own local data on the kontra (a micro grid keeps its whole shape in there).
    // null = none. the world at kenderWorldPos() is whatever the kontra flies over, never ask it
    default net.minecraft.nbt.CompoundTag kenderLocalData() { return null; }
}
