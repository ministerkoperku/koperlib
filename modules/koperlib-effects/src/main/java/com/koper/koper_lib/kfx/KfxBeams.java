package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// lasers on the portable path. a beam is a chain of rings from start to end: a see-through sheath
// that is denser through its middle than at its silhouette, a hot core, bands of brightness flowing
// toward the end, flares at both ends and an additive halo strip in the glow pass
final class KfxBeams {
    static final int TUBE = 0, SQUARE = 1, LIGHTNING = 2, HELIX = 3, PULSE = 4;
    private static final int MAX_RINGS = 64;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final float[] PX = new float[MAX_RINGS + 1], PY = new float[MAX_RINGS + 1],
        PZ = new float[MAX_RINGS + 1], W = new float[MAX_RINGS + 1];

    record Look(int style, int color, int coreColor, float radius, float core, float glow, float flicker,
                float taper, float noise, float speed, int segments, boolean capStart, boolean capEnd, float seed) {}

    private KfxBeams() {}

    static int style(String raw) {
        if (raw == null || raw.isBlank()) return TUBE;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "tube", "round", "laser", "ray", "default" -> TUBE;
            case "square", "box", "cube", "rect", "prism" -> SQUARE;
            case "lightning", "arc", "bolt", "zap", "electric" -> LIGHTNING;
            case "helix", "spiral", "twist", "dna" -> HELIX;
            case "pulse", "beads", "plasma", "orbs" -> PULSE;
            default -> {
                if (WARNED.add(raw)) com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KFX] beam style '{}' does not exist, drawing a tube. styles: tube, square, lightning, helix, pulse", raw);
                yield TUBE;
            }
        };
    }

    static boolean capStart(String caps) {
        return caps == null || caps.equals("both") || caps.equals("start");
    }

    static boolean capEnd(String caps) {
        return caps == null || caps.equals("both") || caps.equals("end");
    }

    static void draw(PoseStack.Pose pose, VertexConsumer c, Look look, float dx, float dy, float dz, float len,
                     float age, float fade, boolean glowPass, float camX, float camY, float camZ) {
        if (len < 1.0e-4f || fade <= 0.0f) return;
        // u, v: an orthonormal frame around the beam axis
        float ux = -dz, uy = 0.0f, uz = dx;
        if (ux * ux + uz * uz < 1.0e-6f) { ux = 1.0f; uz = 0.0f; }
        float ul = 1.0f / (float)Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux *= ul; uy *= ul; uz *= ul;
        float vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

        float speed = look.speed;
        float flick = 1.0f + look.flicker * (0.6f * (float)Math.sin(age * 1.7f + look.seed)
            + 0.4f * (float)Math.sin(age * 4.3f + look.seed * 2.0f));
        int n = look.style == LIGHTNING ? Math.clamp(look.segments, 4, MAX_RINGS)
            : Math.clamp(Math.round(len * 3.0f), 6, MAX_RINGS);
        float amp = look.style == LIGHTNING ? (look.noise > 0 ? look.noise : look.radius * 2.5f) : look.noise;
        int frame = (int)Math.floor(age * Math.max(0.05f, speed) * 0.5f);
        for (int i = 0; i <= n; i++) {
            float t = i / (float)n;
            float ou = 0.0f, ov = 0.0f;
            if (look.style == LIGHTNING) {
                if (i > 0 && i < n) {
                    ou = amp * (hash(look.seed, frame, i * 2) * 2 - 1);
                    ov = amp * (hash(look.seed, frame, i * 2 + 1) * 2 - 1);
                }
            } else if (amp > 0) {
                float env = (float)Math.sin(Math.PI * t);
                ou = amp * env * (float)Math.sin(t * len * 1.3f + age * 0.21f * speed + look.seed);
                ov = amp * env * (float)Math.cos(t * len * 1.1f - age * 0.17f * speed + look.seed * 1.3f);
            }
            PX[i] = dx * len * t + ux * ou + vx * ov;
            PY[i] = dy * len * t + uy * ou + vy * ov;
            PZ[i] = dz * len * t + uz * ou + vz * ov;
            W[i] = look.radius * Math.max(0.05f, 1.0f - look.taper * t) * flick;
        }

        int a = Math.round(((look.color >>> 24) & 255) * fade);
        int coreA = Math.round(((look.coreColor >>> 24) & 255) * fade);
        if (glowPass) {
            // halos scale themselves; the strips take the same daylight factor here
            int ga = Math.round(a * KfxGlow.ambient);
            float glowW = 2.8f * look.glow;
            // the sheath of a light beam is light too: added on top, never hiding the glow behind it
            if (look.style == TUBE || look.style == LIGHTNING || look.style == PULSE) {
                additive = true;
                tube(pose, c, n, look.style == LIGHTNING ? 6 : 14, look.style == LIGHTNING ? 0.9f : 1.0f, look.color,
                    Math.round(ga * 0.6f), 0.35f, len, age * 0.6f * speed, false, camX, camY, camZ);
                additive = false;
            }
            // the colour of a laser is mostly light: a tight bright band over the core, a wide soft glow
            strip(pose, c, n, 1.35f, look.color, Math.round(ga * 0.8f), camX, camY, camZ);
            if (look.glow > 0) strip(pose, c, n, glowW, KfxRenderer.glowTint(look.color), Math.round(ga * 0.4f), camX, camY, camZ);
            if (look.style == LIGHTNING && look.glow > 0) fork(pose, c, look, n, frame, len, glowW, true, ga, coreA, camX, camY, camZ);
            if (look.capStart) KfxGlow.halo(pose, c, PX[0], PY[0], PZ[0], W[0] * 5.0f * Math.max(0.4f, look.glow),
                withAlpha(KfxRenderer.glowTint(look.color), Math.round(a * 0.7f)));
            if (look.capEnd) KfxGlow.halo(pose, c, PX[n], PY[n], PZ[n], W[n] * 6.5f * Math.max(0.4f, look.glow),
                withAlpha(KfxRenderer.mix(look.color, look.coreColor, 0.4f), Math.round(a * 0.8f)));
            if (look.style == PULSE) forBeads(look, n, age, len, (x, y, z, w) ->
                KfxGlow.halo(pose, c, x, y, z, w * 5.0f, withAlpha(KfxRenderer.glowTint(look.color), Math.round(a * 0.6f))));
            return;
        }

        float flow = age * 0.6f * speed;
        switch (look.style) {
            case HELIX -> {
                tube(pose, c, n, 8, look.core, look.coreColor, coreA, 0.55f, len, flow, false, camX, camY, camZ);
                for (int strand = 0; strand < 2; strand++) {
                    float phase = strand * (float)Math.PI - age * 0.3f * speed;
                    for (int i = 0; i <= n; i++) {
                        float t = i / (float)n, th = t * len * 2.4f + phase, r = W[i] * 1.8f;
                        float cu = (float)Math.cos(th) * r, cv = (float)Math.sin(th) * r;
                        HX[i] = dx * len * t + ux * cu + vx * cv;
                        HY[i] = dy * len * t + uy * cu + vy * cv;
                        HZ[i] = dz * len * t + uz * cu + vz * cv;
                    }
                    tubeAlong(pose, c, n, HX, HY, HZ, 0.35f, look.color, a, len, flow, camX, camY, camZ);
                }
            }
            case LIGHTNING -> {
                tube(pose, c, n, 6, 0.42f, look.coreColor, coreA, 0.0f, len, flow, false, camX, camY, camZ);
                fork(pose, c, look, n, frame, len, 0, false, a, coreA, camX, camY, camZ);
            }
            case PULSE -> {
                tube(pose, c, n, 12, look.core, look.coreColor, coreA, 0.0f, len, flow, false, camX, camY, camZ);
                forBeads(look, n, age, len, (x, y, z, w) -> KfxRenderer.meshParticle(pose, c, 7, x, y, z, w * 1.6f,
                    withAlpha(KfxRenderer.mix(look.color, look.coreColor, 0.5f), Math.max(a, coreA)), look.seed));
            }
            default -> {
                // core before sheath: a solid prism writes depth and would hide whatever is inside it
                boolean square = look.style == SQUARE;
                tube(pose, c, n, square ? 4 : 10, look.core, look.coreColor, coreA, 0.0f, len, flow, square, camX, camY, camZ);
                if (square) tube(pose, c, n, 4, 1.0f, look.color, Math.round(a * 0.7f), 0.35f, len, flow, true, camX, camY, camZ);
            }
        }
        if (look.capStart) KfxRenderer.meshParticle(pose, c, 7, PX[0], PY[0], PZ[0], W[0] * 1.25f,
            withAlpha(look.coreColor, coreA), look.seed);
        if (look.capEnd) KfxRenderer.meshParticle(pose, c, 7, PX[n], PY[n], PZ[n], W[n] * 1.6f,
            withAlpha(look.coreColor, coreA), look.seed + 1);
    }

    private static final float[] HX = new float[MAX_RINGS + 1], HY = new float[MAX_RINGS + 1], HZ = new float[MAX_RINGS + 1];

    private interface Bead { void at(float x, float y, float z, float w); }

    // energy beads riding the beam toward its end
    private static void forBeads(Look look, int n, float age, float len, Bead bead) {
        int beads = Math.clamp(Math.round(len / 1.6f), 2, 24);
        for (int k = 0; k < beads; k++) {
            float t = (float)((k / (double)beads + age * 0.035f * look.speed / Math.max(1.0f, len * 0.25f)) % 1.0);
            float f = t * n;
            int i = Math.min(n - 1, (int)f);
            float s = f - i;
            bead.at(PX[i] + (PX[i + 1] - PX[i]) * s, PY[i] + (PY[i + 1] - PY[i]) * s, PZ[i] + (PZ[i + 1] - PZ[i]) * s,
                W[i] + (W[i + 1] - W[i]) * s);
        }
    }

    // a thinner jagged branch leaving the bolt from one of its middle joints
    private static void fork(PoseStack.Pose pose, VertexConsumer c, Look look, int n, int frame, float len,
                             float glowW, boolean glowPass, int a, int coreA, float camX, float camY, float camZ) {
        if (n < 4) return;
        int from = 1 + (int)(hash(look.seed, frame, 997) * (n - 2));
        int seg = 5;
        float bx = PX[from], by = PY[from], bz = PZ[from], bw = W[from] * 0.55f;
        float tx = PX[n] - PX[0], ty = PY[n] - PY[0], tz = PZ[n] - PZ[0];
        float reach = len * 0.22f / Math.max(1.0e-4f, (float)Math.sqrt(tx * tx + ty * ty + tz * tz));
        float sideX = (hash(look.seed, frame, 991) * 2 - 1) * len * 0.15f;
        float sideY = (hash(look.seed, frame, 993) * 2 - 1) * len * 0.15f;
        float sideZ = (hash(look.seed, frame, 995) * 2 - 1) * len * 0.15f;
        float[] bxs = new float[seg + 1], bys = new float[seg + 1], bzs = new float[seg + 1], ws = new float[seg + 1];
        for (int i = 0; i <= seg; i++) {
            float t = i / (float)seg;
            float jag = i == 0 ? 0 : (hash(look.seed, frame, 900 + i) * 2 - 1) * W[from] * 2.0f;
            bxs[i] = bx + (tx * reach + sideX) * t + jag;
            bys[i] = by + (ty * reach + sideY) * t - jag * 0.5f;
            bzs[i] = bz + (tz * reach + sideZ) * t + jag * 0.7f;
            ws[i] = bw * (1.0f - 0.8f * t);
        }
        if (glowPass) {
            additive = true;
            ringsAlong(pose, c, seg, bxs, bys, bzs, ws, 5, 1.0f, look.color, Math.round(a * 0.6f), 0.3f, 0, 0, false, camX, camY, camZ);
            additive = false;
            stripAlong(pose, c, seg, bxs, bys, bzs, ws, 1.35f, look.color, Math.round(a * 0.7f), camX, camY, camZ);
            stripAlong(pose, c, seg, bxs, bys, bzs, ws, glowW, KfxRenderer.glowTint(look.color), Math.round(a * 0.35f), camX, camY, camZ);
        } else {
            ringsAlong(pose, c, seg, bxs, bys, bzs, ws, 5, 0.45f, look.coreColor, coreA, 0.0f, 0, 0, false, camX, camY, camZ);
        }
    }

    private static void tube(PoseStack.Pose pose, VertexConsumer c, int n, int sides, float scale, int color, int a,
                             float bands, float len, float flow, boolean square, float camX, float camY, float camZ) {
        ringsAlong(pose, c, n, PX, PY, PZ, W, sides, scale, color, a, bands, len, flow, square, camX, camY, camZ);
    }

    private static void tubeAlong(PoseStack.Pose pose, VertexConsumer c, int n, float[] xs, float[] ys, float[] zs,
                                  float scale, int color, int a, float len, float flow, float camX, float camY, float camZ) {
        ringsAlong(pose, c, n, xs, ys, zs, W, 6, scale, color, a, 0.3f, len, flow, false, camX, camY, camZ);
    }

    // rings of `sides` vertices around each joint, joined into quads. each ring faces along its local segment
    private static void ringsAlong(PoseStack.Pose pose, VertexConsumer c, int n, float[] xs, float[] ys, float[] zs,
                                   float[] ws, int sides, float scale, int color, int a, float bands, float len,
                                   float flow, boolean square, float camX, float camY, float camZ) {
        if (a <= 0) return;
        float off = square ? 0.7853982f : 0.0f;
        for (int i = 0; i < n; i++) {
            for (int end = 0; end < 2; end++) {
                int j = i + end;
                // tangent: centred difference, so neighbouring segments share their joint rings
                int j0 = Math.max(0, j - 1), j1 = Math.min(n, j + 1);
                float tx = xs[j1] - xs[j0], ty = ys[j1] - ys[j0], tz = zs[j1] - zs[j0];
                float tl = (float)Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl < 1.0e-6f) { tx = 0; ty = 1; tz = 0; } else { tx /= tl; ty /= tl; tz /= tl; }
                float ux = -tz, uy = 0, uz = tx;
                if (ux * ux + uz * uz < 1.0e-6f) { ux = 1; uz = 0; }
                float ul = 1.0f / (float)Math.sqrt(ux * ux + uz * uz);
                ux *= ul; uz *= ul;
                float vx = ty * uz - tz * uy, vy = tz * ux - tx * uz, vz = tx * uy - ty * ux;
                RING_U[end * 6] = ux; RING_U[end * 6 + 1] = uy; RING_U[end * 6 + 2] = uz;
                RING_U[end * 6 + 3] = vx; RING_U[end * 6 + 4] = vy; RING_U[end * 6 + 5] = vz;
            }
            float r0 = ws[i] * scale, r1 = ws[i + 1] * scale;
            float t0 = i / (float)n, t1 = (i + 1) / (float)n;
            float band0 = bands <= 0 ? 1 : 1.0f + bands * (0.5f + 0.5f * (float)Math.sin(t0 * len * 3.0f - flow));
            float band1 = bands <= 0 ? 1 : 1.0f + bands * (0.5f + 0.5f * (float)Math.sin(t1 * len * 3.0f - flow));
            for (int s = 0; s < sides; s++) {
                float a0 = off + (float)(Math.PI * 2 * s / sides), a1 = off + (float)(Math.PI * 2 * (s + 1) / sides);
                float c0 = (float)Math.cos(a0), s0 = (float)Math.sin(a0), c1 = (float)Math.cos(a1), s1 = (float)Math.sin(a1);
                emitRingVertex(pose, c, xs[i], ys[i], zs[i], 0, c0, s0, r0, color, a, band0, camX, camY, camZ);
                emitRingVertex(pose, c, xs[i], ys[i], zs[i], 0, c1, s1, r0, color, a, band0, camX, camY, camZ);
                emitRingVertex(pose, c, xs[i + 1], ys[i + 1], zs[i + 1], 1, c1, s1, r1, color, a, band1, camX, camY, camZ);
                emitRingVertex(pose, c, xs[i + 1], ys[i + 1], zs[i + 1], 1, c0, s0, r1, color, a, band1, camX, camY, camZ);
            }
        }
    }

    private static final float[] RING_U = new float[12];
    // true while drawing into the additive glow buffer (POSITION_COLOR)
    private static boolean additive;

    private static void emitRingVertex(PoseStack.Pose pose, VertexConsumer c, float cx, float cy, float cz, int end,
                                       float co, float si, float r, int color, int a, float band,
                                       float camX, float camY, float camZ) {
        int o = end * 6;
        float nx = RING_U[o] * co + RING_U[o + 3] * si, ny = RING_U[o + 1] * co + RING_U[o + 4] * si,
            nz = RING_U[o + 2] * co + RING_U[o + 5] * si;
        float x = cx + nx * r, y = cy + ny * r, z = cz + nz * r;
        float vx = camX - x, vy = camY - y, vz = camZ - z;
        float vl = (float)Math.sqrt(vx * vx + vy * vy + vz * vz);
        float ndv = vl < 1.0e-6f ? 1 : Math.abs(nx * vx + ny * vy + nz * vz) / vl;
        // a sheath of light: dense where the eye looks through most of it, thin at the silhouette
        // fully clear at the silhouette: a dim edge would sit over the additive glow as a dark outline
        float density = ndv * (float)Math.sqrt(ndv);
        float key = Math.max(0.0f, nx * 0.3363f + ny * 0.9034f + nz * 0.2649f);
        float lit = (1.0f + 0.15f * key) * band;
        // light added on light saturates fast; only the solid sheath gets the extra white
        float hot = additive ? 0.0f : 60.0f * ndv * band;
        if (additive) lit *= 0.75f;
        int rr = Math.min(255, Math.round(((color >> 16) & 255) * lit + hot));
        int gg = Math.min(255, Math.round(((color >> 8) & 255) * lit + hot));
        int bb = Math.min(255, Math.round((color & 255) * lit + hot));
        int aa = Math.min(255, Math.round(a * density));
        if (additive) {
            c.addVertex(pose, x, y, z).setColor((aa << 24) | (rr << 16) | (gg << 8) | bb);
            return;
        }
        c.addVertex(pose, x, y, z).setColor((aa << 24) | (rr << 16) | (gg << 8) | bb).setUv(0.5f, 0.5f)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0, 1, 0);
    }

    private static void strip(PoseStack.Pose pose, VertexConsumer c, int n, float widthScale, int rgb, int a,
                              float camX, float camY, float camZ) {
        stripAlong(pose, c, n, PX, PY, PZ, W, widthScale, rgb, a, camX, camY, camZ);
    }

    private static final float[] SIDE = new float[(MAX_RINGS + 1) * 3];

    // camera-facing glow strip, brightest on the line, zero at its edges and its two ends. each joint
    // has one side vector shared by both of its segments, so a bent bolt glows without notches
    private static void stripAlong(PoseStack.Pose pose, VertexConsumer c, int n, float[] xs, float[] ys, float[] zs,
                                   float[] ws, float widthScale, int rgb, int a, float camX, float camY, float camZ) {
        if (a <= 0) return;
        for (int j = 0; j <= n; j++) {
            int j0 = Math.max(0, j - 1), j1 = Math.min(n, j + 1);
            float tx = xs[j1] - xs[j0], ty = ys[j1] - ys[j0], tz = zs[j1] - zs[j0];
            float mx = xs[j] - camX, my = ys[j] - camY, mz = zs[j] - camZ;
            float sx = ty * mz - tz * my, sy = tz * mx - tx * mz, sz = tx * my - ty * mx;
            float sl = (float)Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0e-6f) { sx = 0; sy = 0; sz = 0; sl = 1; }
            float w = ws[j] * widthScale / sl;
            SIDE[j * 3] = sx * w; SIDE[j * 3 + 1] = sy * w; SIDE[j * 3 + 2] = sz * w;
        }
        int edge = rgb & 0x00FFFFFF;
        for (int i = 0; i < n; i++) {
            int c0 = i == 0 ? edge : withAlpha(rgb, a), c1 = i + 1 == n ? edge : withAlpha(rgb, a);
            int o0 = i * 3, o1 = (i + 1) * 3;
            for (int side = -1; side <= 1; side += 2) {
                glowVertex(pose, c, xs[i], ys[i], zs[i], c0);
                glowVertex(pose, c, xs[i] + SIDE[o0] * side, ys[i] + SIDE[o0 + 1] * side, zs[i] + SIDE[o0 + 2] * side, edge);
                glowVertex(pose, c, xs[i + 1] + SIDE[o1] * side, ys[i + 1] + SIDE[o1 + 1] * side, zs[i + 1] + SIDE[o1 + 2] * side, edge);
                glowVertex(pose, c, xs[i + 1], ys[i + 1], zs[i + 1], c1);
            }
        }
    }

    private static void glowVertex(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, int color) {
        c.addVertex(pose, x, y, z).setColor(color);
    }

    static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.clamp(a, 0, 255) << 24);
    }

    private static float hash(float seed, int frame, int i) {
        int h = Float.floatToIntBits(seed) * 0x9E3779B1 ^ frame * 0x85EBCA6B ^ i * 0xC2B2AE35;
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; h ^= h >>> 16;
        return (h >>> 8) * (1.0f / 16777216.0f);
    }
}
