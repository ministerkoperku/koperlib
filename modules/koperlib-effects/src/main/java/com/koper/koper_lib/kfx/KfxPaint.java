package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kfx.fx.KfxColors;
import com.koper.koper_lib.kfx.fx.KfxFxFrame;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Drawing for {@link com.koper.koper_lib.kfx.fx.KfxFx} effects, in the effect's simulation space.
 *
 * <p>Light (glows, streaks, ribbons, rings, flares) is added on top of the scene in the glow pass, so
 * overlapping light gets brighter instead of hiding what is behind it. Solid things (meshes) draw in the
 * main pass. Each call knows its own pass and does nothing in the other one, so an effect simply calls
 * everything it wants from {@code draw}. When the glow pass is off (low quality) light falls back to a
 * dimmer translucent version in the main pass instead of vanishing.
 */
public final class KfxPaint {
    public static final int SPARK = 1, STAR = 2, RING = 3, SHARD = 4, CUBE = 5, TETRA = 6, ORB = 7, GEM = 8;

    private final PoseStack.Pose pose;
    private final VertexConsumer c;
    private final boolean glowPass, light;
    private final float lightScale;
    private final KfxFxFrame frame;
    private final float lx, ly, lz, ux, uy, uz;

    KfxPaint(PoseStack.Pose base, VertexConsumer consumer, KfxFxFrame frame, boolean glowPass, boolean glowActive) {
        this.pose = base.copy();
        this.pose.translate(-frame.sx, -frame.sy, -frame.sz);
        this.c = consumer;
        this.frame = frame;
        this.glowPass = glowPass;
        this.light = glowPass || !glowActive;
        this.lightScale = glowPass ? KfxGlow.ambient : 0.55f;
        lx = KfxGlow.lx; ly = KfxGlow.ly; lz = KfxGlow.lz;
        ux = KfxGlow.ux; uy = KfxGlow.uy; uz = KfxGlow.uz;
    }

    /** True in the call that draws light. Use it to skip building light geometry in the other pass. */
    public boolean lightPass() { return light; }

    /** True in the call that draws solid meshes. */
    public boolean solidPass() { return !glowPass; }

    // ---- light ----

    /** A soft round glow facing the camera: bright middle, zero at the rim. */
    public void glow(float x, float y, float z, float radius, int argb) {
        if (!light || radius <= 0) return;
        int a = Math.round((argb >>> 24) * lightScale);
        if (a <= 0) return;
        int col = KfxColors.withAlpha(argb, a), mid = KfxColors.withAlpha(argb, Math.round(a * 0.38f)),
            edge = argb & 0x00FFFFFF;
        float r1 = radius * 0.38f;
        for (int i = 0; i < SEG; i++) {
            float ax0 = lx * COS[i] + ux * SIN[i], ay0 = ly * COS[i] + uy * SIN[i], az0 = lz * COS[i] + uz * SIN[i];
            float ax1 = lx * COS[i + 1] + ux * SIN[i + 1], ay1 = ly * COS[i + 1] + uy * SIN[i + 1],
                az1 = lz * COS[i + 1] + uz * SIN[i + 1];
            v(x, y, z, col); v(x + ax0 * r1, y + ay0 * r1, z + az0 * r1, mid);
            v(x + ax1 * r1, y + ay1 * r1, z + az1 * r1, mid); v(x, y, z, col);
            v(x + ax0 * r1, y + ay0 * r1, z + az0 * r1, mid); v(x + ax0 * radius, y + ay0 * radius, z + az0 * radius, edge);
            v(x + ax1 * radius, y + ay1 * radius, z + az1 * radius, edge); v(x + ax1 * r1, y + ay1 * r1, z + az1 * r1, mid);
        }
    }

    /** A point of light: a tinted halo with a small near white heart. */
    public void spark(float x, float y, float z, float radius, int argb) {
        glow(x, y, z, radius, argb);
        glow(x, y, z, radius * 0.32f, KfxColors.withAlpha(KfxColors.mix(argb, 0xFFFFFFFF, 0.7f), argb >>> 24));
    }

    /**
     * A streak of light from a tail point to a head point, widest and brightest at the head. Feed it a
     * particle's previous and current position (stretched by its speed) for motion blurred sparks.
     */
    public void streak(float tx, float ty, float tz, float hx, float hy, float hz, float width, int head, int tail) {
        if (!light) return;
        float ax = hx - tx, ay = hy - ty, az = hz - tz;
        float mx = (hx + tx) * 0.5f, my = (hy + ty) * 0.5f, mz = (hz + tz) * 0.5f;
        float sx = ay * (frame.camZ - mz) - az * (frame.camY - my);
        float sy = az * (frame.camX - mx) - ax * (frame.camZ - mz);
        float sz = ax * (frame.camY - my) - ay * (frame.camX - mx);
        float sl = (float)Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0e-7f) return;
        float k = width / sl;
        sx *= k; sy *= k; sz *= k;
        int h = scaled(head), t = scaled(tail);
        int he = head & 0x00FFFFFF, te = tail & 0x00FFFFFF;
        // two halves, solid centre line fading to nothing at the sides; the tail end is half as wide
        v(tx, ty, tz, t); v(hx, hy, hz, h); v(hx + sx, hy + sy, hz + sz, he); v(tx + sx * 0.5f, ty + sy * 0.5f, tz + sz * 0.5f, te);
        v(tx, ty, tz, t); v(tx - sx * 0.5f, ty - sy * 0.5f, tz - sz * 0.5f, te); v(hx - sx, hy - sy, hz - sz, he); v(hx, hy, hz, h);
        // a rounded head so a streak does not end on a hard edge
        glow(hx, hy, hz, width * 1.6f, KfxColors.alpha(head, 0.7f));
    }

    /**
     * A soft strip of light through {@code n} points, facing the camera. Width and colour are per point,
     * so a ribbon can taper and change colour along its length.
     */
    public void ribbon(float[] xs, float[] ys, float[] zs, float[] widths, int[] colors, int n) {
        if (!light || n < 2) return;
        ensure(n);
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            float tx = xs[b] - xs[a], ty = ys[b] - ys[a], tz = zs[b] - zs[a];
            float cx = frame.camX - xs[i], cy = frame.camY - ys[i], cz = frame.camZ - zs[i];
            float sx = ty * cz - tz * cy, sy = tz * cx - tx * cz, sz = tx * cy - ty * cx;
            float sl = (float)Math.sqrt(sx * sx + sy * sy + sz * sz);
            float k = sl < 1.0e-7f ? 0 : widths[i] / sl;
            SX[i] = sx * k; SY[i] = sy * k; SZ[i] = sz * k;
        }
        for (int i = 0; i < n - 1; i++) {
            int c0 = scaled(colors[i]), c1 = scaled(colors[i + 1]);
            int e0 = colors[i] & 0x00FFFFFF, e1 = colors[i + 1] & 0x00FFFFFF;
            float x0 = xs[i], y0 = ys[i], z0 = zs[i], x1 = xs[i + 1], y1 = ys[i + 1], z1 = zs[i + 1];
            v(x0, y0, z0, c0); v(x1, y1, z1, c1);
            v(x1 + SX[i + 1], y1 + SY[i + 1], z1 + SZ[i + 1], e1); v(x0 + SX[i], y0 + SY[i], z0 + SZ[i], e0);
            v(x0, y0, z0, c0); v(x0 - SX[i], y0 - SY[i], z0 - SZ[i], e0);
            v(x1 - SX[i + 1], y1 - SY[i + 1], z1 - SZ[i + 1], e1); v(x1, y1, z1, c1);
        }
    }

    /** A flat ring of light around a centre, lying in the plane with normal n. Soft on both edges. */
    public void ring(float cx, float cy, float cz, float nx, float ny, float nz, float radius, float width, int argb) {
        if (!light || radius <= 0) return;
        float nl = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0e-6f) { nx = 0; ny = 1; nz = 0; } else { nx /= nl; ny /= nl; nz /= nl; }
        // a basis in the plane
        float ax = Math.abs(ny) < 0.9f ? 0 : 1, ay = Math.abs(ny) < 0.9f ? 1 : 0, az = 0;
        float px = ay * nz - az * ny, py = az * nx - ax * nz, pz = ax * ny - ay * nx;
        float pl = (float)Math.sqrt(px * px + py * py + pz * pz);
        px /= pl; py /= pl; pz /= pl;
        float qx = ny * pz - nz * py, qy = nz * px - nx * pz, qz = nx * py - ny * px;
        int col = scaled(argb), edge = argb & 0x00FFFFFF;
        float r0 = Math.max(0, radius - width), r2 = radius + width;
        int seg = Math.clamp(Math.round(radius * 10), 24, 72);
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            float c0 = (float)Math.cos(a0), s0 = (float)Math.sin(a0), c1 = (float)Math.cos(a1), s1 = (float)Math.sin(a1);
            float d0x = px * c0 + qx * s0, d0y = py * c0 + qy * s0, d0z = pz * c0 + qz * s0;
            float d1x = px * c1 + qx * s1, d1y = py * c1 + qy * s1, d1z = pz * c1 + qz * s1;
            v(cx + d0x * r0, cy + d0y * r0, cz + d0z * r0, edge); v(cx + d1x * r0, cy + d1y * r0, cz + d1z * r0, edge);
            v(cx + d1x * radius, cy + d1y * radius, cz + d1z * radius, col); v(cx + d0x * radius, cy + d0y * radius, cz + d0z * radius, col);
            v(cx + d0x * radius, cy + d0y * radius, cz + d0z * radius, col); v(cx + d1x * radius, cy + d1y * radius, cz + d1z * radius, col);
            v(cx + d1x * r2, cy + d1y * r2, cz + d1z * r2, edge); v(cx + d0x * r2, cy + d0y * r2, cz + d0z * r2, edge);
        }
    }

    /** A four point lens flare: two thin crossed streaks through a glow, turned by {@code angle}. */
    public void flare(float x, float y, float z, float size, int argb, float angle) {
        if (!light || size <= 0) return;
        float co = (float)Math.cos(angle), si = (float)Math.sin(angle);
        for (int k = 0; k < 2; k++) {
            float c2 = k == 0 ? co : -si, s2 = k == 0 ? si : co;
            float dx = (lx * c2 + ux * s2) * size, dy = (ly * c2 + uy * s2) * size, dz = (lz * c2 + uz * s2) * size;
            float w = size * 0.07f;
            streak(x - dx, y - dy, z - dz, x, y, z, w, argb, argb & 0x00FFFFFF);
            streak(x + dx, y + dy, z + dz, x, y, z, w, argb, argb & 0x00FFFFFF);
        }
        glow(x, y, z, size * 0.45f, argb);
    }

    // ---- solid ----

    /**
     * A lit 3D mesh (one of the style constants) rotated by {@code angle} around the axis (ax, ay, az).
     * Key light, sky fill and a pale rim like the particle meshes.
     */
    public void mesh(int style, float x, float y, float z, float size, int argb,
                     float ax, float ay, float az, float angle) {
        if (glowPass || size <= 0) return;
        float[] mesh = KfxMeshes.forStyle(style);
        if (mesh == null || mesh.length == 0) return;
        float al = (float)Math.sqrt(ax * ax + ay * ay + az * az);
        if (al < 1.0e-5f) { ax = 0; ay = 1; az = 0; } else { ax /= al; ay /= al; az /= al; }
        float co = (float)Math.cos(angle), si = (float)Math.sin(angle), t = 1 - co;
        float m00 = t * ax * ax + co, m01 = t * ax * ay - si * az, m02 = t * ax * az + si * ay;
        float m10 = t * ax * ay + si * az, m11 = t * ay * ay + co, m12 = t * ay * az - si * ax;
        float m20 = t * ax * az - si * ay, m21 = t * ay * az + si * ax, m22 = t * az * az + co;
        float br = (argb >> 16) & 255, bg = (argb >> 8) & 255, bb = argb & 255;
        int a = argb >>> 24;
        for (int i = 0; i < mesh.length; i += KfxMeshes.FLOATS_PER_VERTEX * 3) {
            for (int k = 0; k < 3; k++) {
                int o = i + k * KfxMeshes.FLOATS_PER_VERTEX;
                float px = mesh[o], py = mesh[o + 1], pz = mesh[o + 2];
                float nx = mesh[o + 3], ny = mesh[o + 4], nz = mesh[o + 5];
                float wx = x + (m00 * px + m01 * py + m02 * pz) * size;
                float wy = y + (m10 * px + m11 * py + m12 * pz) * size;
                float wz = z + (m20 * px + m21 * py + m22 * pz) * size;
                float rnx = m00 * nx + m01 * ny + m02 * nz, rny = m10 * nx + m11 * ny + m12 * nz,
                    rnz = m20 * nx + m21 * ny + m22 * nz;
                int col = KfxRenderer.litColor(br, bg, bb, a, rnx, rny, rnz,
                    frame.camX - wx, frame.camY - wy, frame.camZ - wz);
                solid(wx, wy, wz, col, rnx, rny, rnz);
                if (k == 2) solid(wx, wy, wz, col, rnx, rny, rnz);
            }
        }
    }

    /**
     * The built-in laser between two points: core, sheath, flowing bands and glow. {@code style} is one
     * of {@code tube}, {@code square}, {@code lightning}, {@code helix}, {@code pulse}.
     */
    public void laser(float x0, float y0, float z0, float x1, float y1, float z1, String style, float radius,
                      int color, int core, float glow, float flicker, float noise, float speed, float taper,
                      boolean capStart, boolean capEnd, float seed, float age, float fade) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4f || fade <= 0) return;
        PoseStack.Pose at = pose.copy();
        at.translate(x0, y0, z0);
        KfxBeams.Look look = new KfxBeams.Look(KfxBeams.style(style), color, core, radius, 0.4f, glow, flicker,
            taper, noise, speed, 16, capStart, capEnd, seed);
        boolean saved = KfxRenderer.glowPass;
        KfxBeams.draw(at, c, look, dx / len, dy / len, dz / len, len, age, fade, glowPass,
            frame.camX - x0, frame.camY - y0, frame.camZ - z0);
        KfxRenderer.glowPass = saved;
    }

    // ---- internals ----

    private static final int SEG = 12;
    private static final float[] COS = new float[SEG + 1], SIN = new float[SEG + 1];
    private static float[] SX = new float[64], SY = new float[64], SZ = new float[64];

    static {
        for (int i = 0; i <= SEG; i++) {
            double a = Math.PI * 2 * i / SEG;
            COS[i] = (float)Math.cos(a);
            SIN[i] = (float)Math.sin(a);
        }
    }

    private static void ensure(int n) {
        if (SX.length >= n) return;
        int size = Math.max(n, SX.length * 2);
        SX = new float[size]; SY = new float[size]; SZ = new float[size];
    }

    private int scaled(int argb) {
        return KfxColors.withAlpha(argb, Math.round((argb >>> 24) * lightScale));
    }

    // a light vertex: position + colour in the glow pass, the full entity format in the fallback
    private void v(float x, float y, float z, int color) {
        if (glowPass) c.addVertex(pose, x, y, z).setColor(color);
        else c.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(0xF000F0).setNormal(pose, 0, 1, 0);
    }

    private void solid(float x, float y, float z, int color, float nx, float ny, float nz) {
        c.addVertex(pose, x, y, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(0xF000F0).setNormal(pose, nx, ny, nz);
    }
}
