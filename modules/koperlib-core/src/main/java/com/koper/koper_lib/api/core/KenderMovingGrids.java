package com.koper.koper_lib.api.core;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Supplier;

/** Client-side Kender view consumed by optional render extensions such as attachment overlays. */
public final class KenderMovingGrids {
    public record Grid(float[] position, float[] rotation, float[] offsets, BlockState[] states) {}

    public interface Provider {
        List<Grid> snapshot(long nowNanos);
        <T> T outsideGrid(Supplier<T> action);
    }

    private static volatile Provider provider;

    private KenderMovingGrids() {}

    public static void install(Provider value) { provider = value; }

    public static List<Grid> snapshot(long nowNanos) {
        Provider current = provider;
        return current == null ? List.of() : current.snapshot(nowNanos);
    }

    public static <T> T outsideGrid(Supplier<T> action) {
        Provider current = provider;
        return current == null ? action.get() : current.outsideGrid(action);
    }
}
