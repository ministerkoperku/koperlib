package com.koper.koper_lib.api.core;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Function;

/** Main-Kender block outline hook. Kodel installs model-aware shapes when present. */
public final class KoperBlockShapes {
    private static volatile Function<BlockState, VoxelShape> provider;

    private KoperBlockShapes() {}

    public static void install(Function<BlockState, VoxelShape> value) { provider = value; }

    public static VoxelShape shape(BlockState state) {
        Function<BlockState, VoxelShape> current = provider;
        if (current == null) return Shapes.block();
        VoxelShape shape = current.apply(state);
        return shape == null ? Shapes.block() : shape;
    }
}
