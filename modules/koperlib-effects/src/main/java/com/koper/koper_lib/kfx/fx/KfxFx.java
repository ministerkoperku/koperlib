package com.koper.koper_lib.kfx.fx;

import com.koper.koper_lib.kfx.KfxPaint;

/**
 * A Java effect with its own state, drawn by a {@code koper_lib:render/fx} graph node.
 *
 * <p>One instance lives per node of a live effect on each client. The host calls {@link #update} once
 * per rendered frame and then {@link #draw} once per render pass. Nothing here runs on the server, so an
 * effect is free to keep particles, histories and timers of its own. Gameplay never reads it back.
 */
public interface KfxFx {
    /** Advances the simulation by {@code frame.dt()} ticks. Never called with a negative step. */
    void update(KfxFxFrame frame);

    /** Draws the current state. Called for the solid pass and again for the additive glow pass. */
    void draw(KfxFxFrame frame, KfxPaint paint);

    /** Builds one effect instance from the values its graph node resolved for this cast. */
    @FunctionalInterface
    interface Factory {
        KfxFx create(KfxFxSpec spec);
    }
}
