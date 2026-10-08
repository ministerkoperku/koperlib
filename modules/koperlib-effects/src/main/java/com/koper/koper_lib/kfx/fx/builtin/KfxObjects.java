package com.koper.koper_lib.kfx.fx.builtin;

import com.koper.koper_lib.kfx.KfxPaint;
import com.koper.koper_lib.kfx.fx.KfxColors;
import com.koper.koper_lib.kfx.fx.KfxFx;
import com.koper.koper_lib.kfx.fx.KfxFxBook;
import com.koper.koper_lib.kfx.fx.KfxFxFrame;
import com.koper.koper_lib.kfx.fx.KfxFxSpec;
import com.koper.koper_lib.kfx.fx.KfxNoise;
import com.koper.koper_lib.kfx.fx.KfxSwarm;

import static com.koper.koper_lib.kfx.fx.builtin.FxKit.TAU;

/**
 * Built-in objects and bursts, drawn at the effect's start anchor.
 *
 * <p>{@code koper_lib:object/relic} is a solid 3D body ({@code variant} picks the shape, see
 * {@link #SHAPES}) wrapped in light. {@code intensity} decides how much is around it: motes from 0,
 * orbiting satellites with trails from 1, rings and a motion trail from 1.4, arcs and shock pulses
 * from 2. {@code koper_lib:burst/nova} is a one-shot flash, shockwave and spark spray.
 */
public final class KfxObjects {
    /** Body shapes by {@code variant}, wrapping around: gem, cube, tetra, shard, orb, ring, star. */
    public static final int[] SHAPES = {KfxPaint.GEM, KfxPaint.CUBE, KfxPaint.TETRA, KfxPaint.SHARD,
        KfxPaint.ORB, KfxPaint.RING, KfxPaint.STAR};
    public static final String[] SHAPE_NAMES = {"gem", "cube", "tetra", "shard", "orb", "ring", "star"};

    private KfxObjects() {}

    public static void register() {
        KfxFxBook.register("koper_lib:object/relic", Relic::new);
        KfxFxBook.register("koper_lib:burst/nova", Nova::new);
    }

    static final class Relic implements KfxFx {
        private static final int TRAIL = 18, ORBIT_TRAIL = 12;
        private final KfxFxSpec spec;
        private final int color, hot, accent, shape;
        private final float s, seed;
        private final KfxSwarm motes;
        private final float axX, axY, axZ;
        private final int satellites;
        private final float[] satTilt, satRate, satPhase;
        // motion trail of the body itself, newest first
        private final float[] hx = new float[TRAIL], hy = new float[TRAIL], hz = new float[TRAIL];
        private int history;
        private float since;
        private final float[] rx = new float[Math.max(TRAIL, ORBIT_TRAIL)], ry = new float[rx.length],
            rz = new float[rx.length], rw = new float[rx.length];
        private final int[] rc = new int[rx.length];
        private final float[] arcX = new float[10], arcY = new float[10], arcZ = new float[10], arcW = new float[10];
        private final int[] arcC = new int[10];

        Relic(KfxFxSpec spec) {
            this.spec = spec;
            color = spec.color();
            hot = spec.hot();
            accent = KfxColors.hue(color, 30);
            shape = SHAPES[Math.floorMod(spec.variant(), SHAPES.length)];
            s = Math.max(0.08f, spec.size());
            seed = (spec.seed() & 0xFFFF) * 0.01f;
            motes = new KfxSwarm(Math.max(8, spec.count()), spec.seed());
            float a = KfxNoise.hash01(spec.seed(), 1) * 2 - 1, b = KfxNoise.hash01(spec.seed(), 2) * 2 - 1;
            float l = (float)Math.sqrt(a * a + 1 + b * b);
            axX = a / l; axY = 1 / l; axZ = b / l;
            satellites = spec.atLeast(1.0f) ? (spec.atLeast(2.0f) ? 5 : spec.atLeast(1.4f) ? 4 : 3) : 0;
            satTilt = new float[satellites]; satRate = new float[satellites]; satPhase = new float[satellites];
            for (int i = 0; i < satellites; i++) {
                satTilt[i] = (KfxNoise.hash01(spec.seed(), 10 + i) - 0.5f) * 2.4f;
                satRate[i] = (0.07f + KfxNoise.hash01(spec.seed(), 20 + i) * 0.06f) * (i % 2 == 0 ? 1 : -1);
                satPhase[i] = i * TAU / satellites;
            }
        }

        private float ox, oy, oz;

        // satellite i at angle a: an orbit tilted out of the horizontal plane
        private void orbit(KfxFxFrame f, int i, float a) {
            float rad = s * (1.55f + 0.2f * (i % 2));
            float c = (float)Math.cos(a) * rad, sn = (float)Math.sin(a) * rad;
            float tilt = satTilt[i], ct = (float)Math.cos(tilt), st = (float)Math.sin(tilt);
            ox = f.sx + c; oy = f.sy + sn * st; oz = f.sz + sn * ct;
        }

        @Override
        public void update(KfxFxFrame f) {
            int n = motes.emit((1.2f + spec.intensity() * 1.6f) * spec.speed(), f.dt);
            for (int k = 0; k < n; k++) {
                float dx = motes.signed(), dy = motes.signed(), dz = motes.signed();
                float l = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (l < 1.0e-3f) continue;
                dx /= l; dy /= l; dz /= l;
                motes.spawn(f.sx + dx * s * 0.7f, f.sy + dy * s * 0.7f, f.sz + dz * s * 0.7f,
                    dx * s * 0.02f, dy * s * 0.02f + 0.004f, dz * s * 0.02f, 18 + motes.rand() * 20,
                    0.6f + motes.rand() * 0.8f);
            }
            motes.curl(f.dt, 0.004f * s, 1.2f / s, f.age * 0.03f);
            motes.integrate(f.dt, 0.05f, 0, 0, 0);
            // the trail only grows while the body actually moves
            since += f.dt;
            float mx = f.sx - hx[0], my = f.sy - hy[0], mz = f.sz - hz[0];
            if (history == 0 || (since >= 1 && mx * mx + my * my + mz * mz > 1.0e-4f)) {
                System.arraycopy(hx, 0, hx, 1, TRAIL - 1);
                System.arraycopy(hy, 0, hy, 1, TRAIL - 1);
                System.arraycopy(hz, 0, hz, 1, TRAIL - 1);
                history = Math.min(TRAIL, history + 1);
                since = 0;
            } else if (since >= 1 && history > 1) {
                history--;
                since = 0;
            }
            hx[0] = f.sx; hy[0] = f.sy; hz[0] = f.sz;
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            float x = f.sx, y = f.sy, z = f.sz, fade = f.fade;
            float breathe = 1 + 0.06f * (float)Math.sin(f.age * 0.2f + seed);
            float spin = f.age * 0.06f * spec.speed() + seed;

            // body: solid, with light inside and around it
            p.mesh(shape, x, y, z, s * 0.55f * breathe, KfxColors.alpha(color, fade * 0.92f), axX, axY, axZ, spin);
            if (shape == KfxPaint.RING || shape == KfxPaint.STAR) {
                p.mesh(KfxPaint.ORB, x, y, z, s * 0.2f, KfxColors.alpha(hot, fade), axX, axY, axZ, spin);
            }
            p.glow(x, y, z, s * 1.9f * breathe, KfxColors.alpha(color, fade * 0.5f));
            p.spark(x, y, z, s * 0.75f, KfxColors.alpha(hot, fade * 0.75f));
            FxKit.motes(p, motes, f, hot, color, s * 0.16f);

            if (spec.atLeast(1.4f) && history > 2 && p.lightPass()) {
                for (int i = 0; i < history; i++) {
                    float u = i / (float)(history - 1);
                    rx[i] = hx[i]; ry[i] = hy[i]; rz[i] = hz[i];
                    rw[i] = s * 0.5f * (1 - u);
                    rc[i] = KfxColors.alpha(KfxColors.mix(hot, color, u), fade * (1 - u) * 0.8f);
                }
                p.ribbon(rx, ry, rz, rw, rc, history);
            }

            for (int i = 0; i < satellites; i++) {
                float a = satPhase[i] + f.age * satRate[i] * spec.speed();
                if (p.lightPass()) {
                    for (int k = 0; k < ORBIT_TRAIL; k++) {
                        orbit(f, i, a - Math.signum(satRate[i]) * k * 0.09f);
                        rx[k] = ox; ry[k] = oy; rz[k] = oz;
                        float u = k / (float)(ORBIT_TRAIL - 1);
                        rw[k] = s * 0.09f * (1 - u);
                        rc[k] = KfxColors.alpha(KfxColors.mix(hot, accent, u), fade * (1 - u));
                    }
                    p.ribbon(rx, ry, rz, rw, rc, ORBIT_TRAIL);
                }
                orbit(f, i, a);
                p.mesh(i % 2 == 0 ? KfxPaint.TETRA : KfxPaint.GEM, ox, oy, oz, s * 0.13f,
                    KfxColors.alpha(accent, fade), axZ, axX, axY, f.age * 0.25f + i);
                p.spark(ox, oy, oz, s * 0.3f, KfxColors.alpha(KfxColors.mix(accent, hot, 0.5f), fade * 0.8f));
            }

            if (spec.atLeast(1.4f)) {
                for (int k = 0; k < 2; k++) {
                    float t = f.age * (k == 0 ? 0.017f : -0.013f) * spec.speed() + k * 1.7f + seed;
                    float nx = (float)Math.sin(t), ny = 0.6f + 0.4f * (float)Math.cos(t * 0.7f), nz = (float)Math.cos(t);
                    p.ring(x, y, z, nx, ny, nz, s * (1.25f + k * 0.25f), s * 0.05f,
                        KfxColors.alpha(k == 0 ? color : accent, fade * 0.85f));
                }
            }

            if (spec.atLeast(2.0f)) {
                // crackling arcs from the body out to a satellite, re-rolled every few frames
                int tick = (int)(f.age * 0.5f);
                for (int k = 0; k < 2 && satellites > 0; k++) {
                    long h = spec.seed() + tick * 31L + k * 977L;
                    if (KfxNoise.hash01(h, 0) < 0.35f) continue;
                    int target = (int)(KfxNoise.hash01(h, 1) * satellites);
                    orbit(f, target, satPhase[target] + f.age * satRate[target] * spec.speed());
                    for (int i = 0; i < 10; i++) {
                        float u = i / 9f, env = (float)Math.sin(Math.PI * u);
                        arcX[i] = x + (ox - x) * u + (KfxNoise.hash01(h, i * 3 + 2) - 0.5f) * s * 0.5f * env;
                        arcY[i] = y + (oy - y) * u + (KfxNoise.hash01(h, i * 3 + 3) - 0.5f) * s * 0.5f * env;
                        arcZ[i] = z + (oz - z) * u + (KfxNoise.hash01(h, i * 3 + 4) - 0.5f) * s * 0.5f * env;
                        arcW[i] = s * 0.04f;
                        arcC[i] = KfxColors.alpha(hot, fade);
                    }
                    p.ribbon(arcX, arcY, arcZ, arcW, arcC, 10);
                }
                float ph = (f.age * 0.03f * spec.speed()) % 1.0f;
                p.ring(x, y, z, f.dx, f.dy, f.dz, s * (0.8f + 2.6f * FxKit.easeOut(ph)), s * 0.12f * (1 - ph),
                    KfxColors.alpha(KfxColors.mix(hot, color, ph), fade * (1 - ph)));
                p.flare(x, y, z, s * 2.6f, KfxColors.alpha(hot, fade * 0.55f), spin * 0.5f);
            }
        }
    }

    static final class Nova implements KfxFx {
        private final KfxFxSpec spec;
        private final KfxSwarm sparks, embers;
        private final int color, hot;
        private final float s;
        private boolean fired;
        private final float tilt;

        Nova(KfxFxSpec spec) {
            this.spec = spec;
            color = spec.color();
            hot = spec.hot();
            s = Math.max(0.2f, spec.size());
            sparks = new KfxSwarm(Math.max(8, Math.round(spec.count() * 0.65f)), spec.seed());
            embers = new KfxSwarm(Math.max(4, Math.round(spec.count() * 0.35f)), spec.seed() ^ 0xE);
            tilt = KfxNoise.hash01(spec.seed(), 3) * TAU;
        }

        @Override
        public void update(KfxFxFrame f) {
            if (!fired) {
                fired = true;
                FxKit.spray(sparks, sparks.capacity(), f.sx, f.sy, f.sz, 0, 0.25f, 0, 1.0f,
                    s * 0.12f, s * 0.32f * spec.speed(), 10, 22, 1);
                FxKit.spray(embers, embers.capacity(), f.sx, f.sy, f.sz, 0, 0.3f, 0, 1.0f,
                    s * 0.02f, s * 0.07f, 26, 46, 1);
            }
            sparks.integrate(f.dt, 0.11f, 0, -0.025f, 0);
            embers.curl(f.dt, 0.006f * s, 0.8f / s, f.age * 0.03f);
            embers.integrate(f.dt, 0.06f, 0, 0.002f, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            float x = f.sx, y = f.sy, z = f.sz, age = f.age;
            float flash = 1 - Math.clamp(age / 7f, 0, 1);
            if (flash > 0) {
                p.glow(x, y, z, s * 1.6f * (0.6f + flash), KfxColors.alpha(color, f.fade * flash));
                p.spark(x, y, z, s * 0.7f * flash + 0.05f, KfxColors.alpha(hot, f.fade * flash));
                if (spec.atLeast(1.4f)) p.flare(x, y, z, s * 2.2f * flash, KfxColors.alpha(hot, f.fade * flash), tilt);
            }
            for (int k = 0; k < (spec.atLeast(1.0f) ? 2 : 1); k++) {
                float t = Math.clamp((age - k * 3) / 14f, 0, 1);
                if (t <= 0 || t >= 1) continue;
                float nx = k == 0 ? 0 : (float)Math.sin(tilt), ny = 1, nz = k == 0 ? 0 : (float)Math.cos(tilt);
                p.ring(x, y, z, nx, ny, nz, s * (0.2f + 1.4f * FxKit.easeOut(t)), s * 0.14f * (1 - t),
                    KfxColors.alpha(KfxColors.mix(hot, color, t), f.fade * (1 - t)));
            }
            FxKit.streaks(p, sparks, f, hot, color, s * 0.035f, 2.4f);
            FxKit.motes(p, embers, f, hot, color, s * 0.07f);
        }
    }
}
