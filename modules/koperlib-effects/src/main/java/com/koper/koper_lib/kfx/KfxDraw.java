package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

// low-level draw helpers for addon styles/ops. coords are effect-local (already offset to fx origin).
// addon ops get the richer KfxDrawCtx, addon styles get a bare pose+consumer and reach for these.
public final class KfxDraw {
    private KfxDraw() {}

    public static void quad(PoseStack.Pose pose, VertexConsumer c,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            float x2, float y2, float z2, float x3, float y3, float z3, int color) {
        KfxRenderer.quad(pose, c, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, color);
    }

    public static void tri(PoseStack.Pose pose, VertexConsumer c,
                           float x0, float y0, float z0, float x1, float y1, float z1,
                           float x2, float y2, float z2, int color) {
        KfxRenderer.tri(pose, c, x0, y0, z0, x1, y1, z1, x2, y2, z2, color);
    }

    public static void sprite(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float size, int color) {
        KfxRenderer.spriteParticle(pose, c, x, y, z, size, color);
    }

    public static int alpha(int color, float mul) { return KfxRenderer.alpha(color, mul); }
    public static int rgba(float r, float g, float b, float a) { return KfxRenderer.rgba(r, g, b, a); }
    public static int mix(int a, int b, float t) { return KfxRenderer.mix(a, b, t); }
}
