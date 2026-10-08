package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

// a particle look. coords are already effect-local. draw whatever you want with KfxDraw.*
@FunctionalInterface
public interface KfxStyle {
    void draw(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float size, int color, float seed);
}
