package com.koper.koper_lib.api.core;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** State-aware block shape contract shared by model and attachment modules. */
public interface KoperStateShape {
    VoxelShape koperStateShape(BlockState state);

    // what you aim at and see outlined. defaults to the physics shape; a part whose model is drawn
    // somewhere its collider isn't (a thin collider under a big model) picks with the model instead
    default VoxelShape koperPickShape(BlockState state) {
        return koperStateShape(state);
    }
}
