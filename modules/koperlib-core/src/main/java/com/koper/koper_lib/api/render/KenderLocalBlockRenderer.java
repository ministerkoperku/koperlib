package com.koper.koper_lib.api.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

import java.util.IdentityHashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class KenderLocalBlockRenderer {
    @FunctionalInterface
    public interface Renderer {
        boolean submit(Context context);
    }

    public record Context(BlockState state, CompoundTag data, BlockPos localPos, BlockPos worldPos,
                          float bodyX, float bodyY, float bodyZ, Quaternionf bodyRotation,
                          float offsetX, float offsetY, float offsetZ, int packedLight) {}

    private static final Map<Block, Renderer> RENDERERS = new IdentityHashMap<>();

    private KenderLocalBlockRenderer() {}

    public static synchronized void register(Block block, Renderer renderer) {
        if (block == null || renderer == null) return;
        RENDERERS.put(block, renderer);
    }

    public static synchronized boolean handles(Block block) {
        return RENDERERS.containsKey(block);
    }

    public static boolean submit(Context context) {
        Renderer renderer;
        synchronized (KenderLocalBlockRenderer.class) {
            renderer = RENDERERS.get(context.state().getBlock());
        }
        return renderer != null && renderer.submit(context);
    }
}
