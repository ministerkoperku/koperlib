package com.koper.koper_lib.api.core;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

// optional kender backend for kodel. kender installs a provider here and kodel asks
// through this, so neither imports the other; nobody installed one means kodel draws
// through blaze3d instead and nothing else changes
public final class KodelKenderBridge {

    public interface Provider {
        boolean available(OrderedSubmitNodeCollector collector);

        Matrix4f originRelative(Matrix4fc model, CameraRenderState camera, Matrix4f out);

        /// false when this mesh cannot go on the gpu path, and the caller falls back
        boolean submit(Object identity, String debugName, Supplier<float[]> bake,
                       Identifier texture, RenderType type, Matrix4fc transform,
                       int light, int tint, int boneCount, float[] boneMatrices);

        void ensurePass(OrderedSubmitNodeCollector collector, PoseStack pose,
                        RenderType type, Identifier texture);
    }

    private static volatile Provider provider;

    private KodelKenderBridge() {}

    public static void install(Provider value) {
        provider = value;
    }

    public static boolean available(OrderedSubmitNodeCollector collector) {
        Provider current = provider;
        return current != null && collector != null && current.available(collector);
    }

    public static Matrix4f originRelative(Matrix4fc model, CameraRenderState camera, Matrix4f out) {
        Provider current = provider;
        return current == null ? out.set(model) : current.originRelative(model, camera, out);
    }

    public static boolean submit(Object identity, String debugName, Supplier<float[]> bake,
                                 Identifier texture, RenderType type, Matrix4fc transform,
                                 int light, int tint, int boneCount, float[] boneMatrices) {
        Provider current = provider;
        return current != null && current.submit(identity, debugName, bake, texture, type,
            transform, light, tint, boneCount, boneMatrices);
    }

    public static void ensurePass(OrderedSubmitNodeCollector collector, PoseStack pose,
                                  RenderType type, Identifier texture) {
        Provider current = provider;
        if (current != null) current.ensurePass(collector, pose, type, texture);
    }
}
