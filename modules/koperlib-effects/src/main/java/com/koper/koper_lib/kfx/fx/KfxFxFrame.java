package com.koper.koper_lib.kfx.fx;

/**
 * Where an fx is this frame, in its own simulation space.
 *
 * <p>Simulation space is world space shifted so the effect's start anchor at birth sits on the origin.
 * Particles stored there stay put in the world when the anchors move, so a beam swept across the sky
 * leaves its sparks behind instead of dragging them along. {@link com.koper.koper_lib.kfx.KfxPaint}
 * draws in the same space.
 */
public final class KfxFxFrame {
    /** Effect age in ticks, including the partial tick. */
    public float age;
    /** Ticks since the previous update, 0 on the first frame and capped so a hitch cannot explode a sim. */
    public float dt;
    /** Fade-in, fade-out and anchor loss folded into one 0..1 alpha multiplier. */
    public float fade;
    /** Start and end anchors. */
    public float sx, sy, sz, ex, ey, ez;
    /** Unit axis from start to end; for a single-point effect the anchor's forward axis. */
    public float dx, dy, dz;
    /** Two unit axes perpendicular to {@code d} and to each other. */
    public float ux, uy, uz, vx, vy, vz;
    /** Distance from start to end. */
    public float length;
    /** Camera position. */
    public float camX, camY, camZ;
    /** Client quality tier: 0 low, 1 medium, 2 high. */
    public int quality;

    /** X of the point a fraction {@code t} of the way from start to end. */
    public float alongX(float t) { return sx + (ex - sx) * t; }
    public float alongY(float t) { return sy + (ey - sy) * t; }
    public float alongZ(float t) { return sz + (ez - sz) * t; }

    /** Squared distance from the camera to a point, for level of detail. */
    public float camDist2(float x, float y, float z) {
        float a = x - camX, b = y - camY, c = z - camZ;
        return a * a + b * b + c * c;
    }
}
