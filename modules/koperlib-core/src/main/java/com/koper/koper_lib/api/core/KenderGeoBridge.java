package com.koper.koper_lib.api.core;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/** Optional Kodel backend used by Khysics' moving-block renderer for model blocks. */
public final class KenderGeoBridge {
    public interface Provider {
        Object binding(BlockState state);
        boolean rendersOnMovingGrid(Object binding);
        boolean submit(ClientLevel level, Object binding, BlockState state,
                       float bodyX, float bodyY, float bodyZ, Quaternionf rotation,
                       float offsetX, float offsetY, float offsetZ,
                       BlockPos worldPos, int packedLight);
        default void dropMovingGrid(long id) {}
        default void clearMovingGrids() {}
        default boolean vulkanActive() { return false; }
        default void beginMovingFrame() {}
        default void endMovingFrame() {}
        default Frustum frustum() { return null; }
        default boolean submitVanilla(KenderMovingBlockContext context, BlockState state, byte faceMask,
                                      float bodyX, float bodyY, float bodyZ, Quaternionf rotation,
                                      float offsetX, float offsetY, float offsetZ, int packedLight) { return false; }
        default boolean submitFallbackGeo(SubmitNodeCollector collector, PoseStack pose,
                                          BlockState state, long gridId, BlockPos localPos) { return false; }
    }

    private static volatile Provider provider;

    private KenderGeoBridge() {}

    public static void install(Provider value) { provider = value; }
    public static Object binding(BlockState state) {
        Provider current = provider;
        return current == null ? null : current.binding(state);
    }
    public static boolean rendersOnMovingGrid(Object binding) {
        Provider current = provider;
        return current != null && binding != null && current.rendersOnMovingGrid(binding);
    }
    public static boolean submit(ClientLevel level, Object binding, BlockState state,
                                 float bodyX, float bodyY, float bodyZ, Quaternionf rotation,
                                 float offsetX, float offsetY, float offsetZ,
                                 BlockPos worldPos, int packedLight) {
        Provider current = provider;
        return current != null && binding != null && current.submit(level, binding, state,
            bodyX, bodyY, bodyZ, rotation, offsetX, offsetY, offsetZ, worldPos, packedLight);
    }
    public static void drop(long id) {
        Provider current = provider;
        if (current != null) current.dropMovingGrid(id);
    }
    public static void clear() {
        Provider current = provider;
        if (current != null) current.clearMovingGrids();
    }
    public static boolean vulkanActive() {
        Provider current = provider;
        return current != null && current.vulkanActive();
    }
    public static void beginMovingFrame() {
        Provider current = provider;
        if (current != null) current.beginMovingFrame();
    }
    public static void endMovingFrame() {
        Provider current = provider;
        if (current != null) current.endMovingFrame();
    }
    public static Frustum frustum() {
        Provider current = provider;
        return current == null ? null : current.frustum();
    }
    public static boolean submitVanilla(KenderMovingBlockContext context, BlockState state, byte faceMask,
                                        float bodyX, float bodyY, float bodyZ, Quaternionf rotation,
                                        float offsetX, float offsetY, float offsetZ, int packedLight) {
        Provider current = provider;
        return current != null && current.submitVanilla(context, state, faceMask, bodyX, bodyY, bodyZ,
            rotation, offsetX, offsetY, offsetZ, packedLight);
    }
    public static boolean submitFallbackGeo(SubmitNodeCollector collector, PoseStack pose,
                                            BlockState state, long gridId, BlockPos localPos) {
        Provider current = provider;
        return current != null && current.submitFallbackGeo(collector, pose, state, gridId, localPos);
    }
}
