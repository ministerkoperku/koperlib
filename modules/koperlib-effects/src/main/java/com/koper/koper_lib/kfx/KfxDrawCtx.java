package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

// handed to every KfxOp.draw. carries the effect state + helpers so addon ops can draw
// without reaching into the renderer. eased/opColor are refreshed per op by the render loop.
public final class KfxDrawCtx {
    public final PoseStack.Pose pose;
    public final VertexConsumer consumer;
    public final KfxInstance fx;
    public final KfxBasis basis;
    public final float age, spin, pulse, fade;
    float eased = 1.0f;
    int opColor;

    KfxDrawCtx(PoseStack.Pose pose, VertexConsumer consumer, KfxInstance fx, KfxBasis basis,
               float age, float spin, float pulse, float fade) {
        this.pose = pose;
        this.consumer = consumer;
        this.fx = fx;
        this.basis = basis;
        this.age = age;
        this.spin = spin;
        this.pulse = pulse;
        this.fade = fade;
    }

    // eased progress (0..1) of the current op, already run through its ease curve
    public float eased() { return eased; }

    // current op color, alpha already folded with fade
    public int color() { return opColor; }

    // drop a particle in effect-local space (auto-oriented to the start->end basis)
    public void particle(String style, float x, float y, float z, float size, int color, float seed) {
        KfxRenderer.particleAt(pose, consumer, KfxStyles.codeFor(style), basis, x, y, z, size, color, seed);
    }

    public void particle(int styleCode, float x, float y, float z, float size, int color, float seed) {
        KfxRenderer.particleAt(pose, consumer, styleCode, basis, x, y, z, size, color, seed);
    }

    // the effect's beam quad, thickness multiplier + alpha
    public void beam(float thicknessMul, float alpha) {
        KfxRenderer.drawBeam(pose, consumer, fx, thicknessMul, alpha);
    }

    public int alpha(int color, float mul) { return KfxRenderer.alpha(color, mul); }
    public float smooth(float t) { return KfxRenderer.smooth(t); }
    public float ease(float t, String ease) { return KfxRenderer.ease(t, ease); }
}
