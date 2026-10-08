package com.koper.koper_lib.api.core;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Optional block-state model/collider offset understood across KoperLib modules. */
public interface KoperStateOffset {
    Vec3 koperStateOffset(BlockState state);
}
