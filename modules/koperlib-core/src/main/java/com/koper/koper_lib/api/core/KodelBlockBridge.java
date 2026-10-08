package com.koper.koper_lib.api.core;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Function;

// optional kodel backend for geo blocks. the kender block renderer asks here before
// building its own mesh; a block the pack never bound to a .kodel answers false and
// nothing changes. kodel installs the provider, so neither module imports the other
public final class KodelBlockBridge {

    public interface Provider {
        boolean handles(BlockState state);

        // stack sits on the block corner, kodel places the model itself
        boolean submit(OrderedSubmitNodeCollector collector, PoseStack pose,
                       BlockState state, int light);

        // stack already placed like the geo mesh would be. bonePose = kender pose (bone -> rx,ry,rz rad,
        // tx,ty,tz blocks, sx,sy,sz, 0 scale = untouched), null = rest. read it before returning,
        // kender reuses the map. visible null = all bones
        default boolean submitPosed(OrderedSubmitNodeCollector collector, PoseStack pose,
                                    BlockState state, int light, int tint,
                                    Function<String, float[]> bonePose, Set<String> visible) {
            return false;
        }
    }

    private static volatile Provider provider;

    private KodelBlockBridge() {}

    public static void install(Provider value) {
        provider = value;
    }

    public static boolean handles(BlockState state) {
        Provider current = provider;
        return current != null && state != null && current.handles(state);
    }

    public static boolean submit(OrderedSubmitNodeCollector collector, PoseStack pose,
                                 BlockState state, int light) {
        Provider current = provider;
        return current != null && current.submit(collector, pose, state, light);
    }

    public static boolean submitPosed(OrderedSubmitNodeCollector collector, PoseStack pose,
                                      BlockState state, int light, int tint,
                                      Function<String, float[]> bonePose, Set<String> visible) {
        Provider current = provider;
        return current != null && current.submitPosed(collector, pose, state, light, tint, bonePose, visible);
    }
}
