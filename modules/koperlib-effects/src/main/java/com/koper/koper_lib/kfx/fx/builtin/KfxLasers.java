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
 * Built-in lasers, drawn between the effect's start and end anchors. Ordered roughly from plain to
 * showy; {@code intensity} adds density and extra layers to any of them.
 *
 * <ul>
 *   <li>{@code lance}: a clean beam shedding sparks, with a splash where it lands</li>
 *   <li>{@code ember}: a warm flickering beam giving off embers that drift up and curl</li>
 *   <li>{@code storm}: a crackling bolt with extra arcs re-rolling around it</li>
 *   <li>{@code helix}: two ribbons of light twisting around a thin core, beads riding them</li>
 *   <li>{@code rift}: a dark core with a bright rim that drags streaks and shards into itself</li>
 *   <li>{@code prism}: one beam splitting into three hues and joining again at the target</li>
 *   <li>{@code comet}: comets with long tails spiral down the beam into a turning sigil</li>
 *   <li>{@code serpent}: a living ribbon coils along the beam, shedding glowing scales</li>
 * </ul>
 */
public final class KfxLasers {
    private KfxLasers() {}

    public static void register() {
        KfxFxBook.register("koper_lib:laser/lance", Lance::new);
        KfxFxBook.register("koper_lib:laser/ember", Ember::new);
        KfxFxBook.register("koper_lib:laser/storm", Storm::new);
        KfxFxBook.register("koper_lib:laser/helix", Helix::new);
        KfxFxBook.register("koper_lib:laser/rift", Rift::new);
        KfxFxBook.register("koper_lib:laser/prism", Prism::new);
        KfxFxBook.register("koper_lib:laser/comet", Comet::new);
        KfxFxBook.register("koper_lib:laser/serpent", Serpent::new);
    }

    /** Shared skeleton: a spark swarm, a splash at the target and a seed. */
    abstract static class Base implements KfxFx {
        final KfxFxSpec spec;
        final KfxSwarm sparks;
        final int color, hot;
        final float r, seed;

        Base(KfxFxSpec spec, float sparkShare) {
            this.spec = spec;
            this.sparks = new KfxSwarm(Math.max(8, Math.round(spec.count() * sparkShare)), spec.seed());
            this.color = spec.color();
            this.hot = spec.hot();
            this.r = spec.size();
            this.seed = (spec.seed() & 0xFFFF) * 0.01f;
        }

        /** Splash sparks bouncing back off whatever the beam hits. */
        void splash(KfxFxFrame f, float rate) {
            if (f.length < 0.05f) return;
            int n = sparks.emit(rate * spec.intensity(), f.dt);
            FxKit.spray(sparks, n, f.ex, f.ey, f.ez, -f.dx, -f.dy, -f.dz, 1.1f,
                r * 1.2f, r * 3.0f * spec.speed(), 7, 15, 1.0f);
        }

        /** Sparks peeling off the side of the beam at random points. */
        void shed(KfxFxFrame f, float rate, float out, float forward) {
            if (f.length < 0.05f) return;
            int n = sparks.emit(rate * spec.intensity() * Math.min(3, f.length * 0.25f), f.dt);
            for (int k = 0; k < n; k++) {
                float t = sparks.rand(), a = sparks.rand() * TAU;
                float cu = (float)Math.cos(a), sv = (float)Math.sin(a);
                float ox = f.ux * cu + f.vx * sv, oy = f.uy * cu + f.vy * sv, oz = f.uz * cu + f.vz * sv;
                float sp = out * (0.5f + sparks.rand());
                sparks.spawn(f.alongX(t) + ox * r, f.alongY(t) + oy * r, f.alongZ(t) + oz * r,
                    ox * sp + f.dx * forward, oy * sp + f.dy * forward, oz * sp + f.dz * forward,
                    8 + sparks.rand() * 10, 0.7f + sparks.rand() * 0.6f);
            }
        }

        void flareStart(KfxFxFrame f, KfxPaint p, float size) {
            p.flare(f.sx, f.sy, f.sz, size, KfxColors.alpha(hot, f.fade * 0.8f), f.age * 0.05f + seed);
        }
    }

    // ---- common ----

    static final class Lance extends Base {
        Lance(KfxFxSpec spec) { super(spec, 1.0f); }

        @Override
        public void update(KfxFxFrame f) {
            shed(f, 1.6f, r * 0.55f, r * 0.4f);
            splash(f, 2.2f);
            sparks.integrate(f.dt, 0.13f, 0, -0.012f, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r, color, hot, spec.glow(), 0.1f, 0,
                spec.speed(), 0, false, false, seed, f.age, f.fade);
            FxKit.streaks(p, sparks, f, hot, color, r * 0.22f, 2.2f);
            FxKit.impactGlow(p, f, hot, color, r * 3.2f, seed);
            flareStart(f, p, r * 4);
        }
    }

    static final class Ember extends Base {
        private final KfxSwarm embers;

        Ember(KfxFxSpec spec) {
            super(spec, 0.35f);
            embers = new KfxSwarm(Math.max(8, Math.round(spec.count() * 0.65f)), spec.seed() ^ 0x5EED);
        }

        @Override
        public void update(KfxFxFrame f) {
            if (f.length > 0.05f) {
                int n = embers.emit(3.2f * spec.intensity() * Math.min(3, f.length * 0.2f), f.dt);
                for (int k = 0; k < n; k++) {
                    float t = embers.rand();
                    embers.spawn(f.alongX(t) + embers.signed() * r, f.alongY(t) + embers.signed() * r,
                        f.alongZ(t) + embers.signed() * r, embers.signed() * 0.01f, 0.012f + embers.rand() * 0.02f,
                        embers.signed() * 0.01f, 24 + embers.rand() * 26, 0.6f + embers.rand() * 0.8f);
                }
            }
            embers.curl(f.dt, 0.006f, 0.7f, f.age * 0.02f);
            embers.integrate(f.dt, 0.05f, 0, 0.0015f, 0);
            splash(f, 1.2f);
            sparks.integrate(f.dt, 0.12f, 0, -0.02f, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            int warm = KfxColors.mix(color, 0xFFFFC070, 0.25f);
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 0.9f, warm, hot, spec.glow() * 1.2f, 0.35f,
                r * 0.4f, spec.speed() * 0.7f, 0.25f, false, false, seed, f.age, f.fade);
            FxKit.motes(p, embers, f, hot, warm, r * 0.75f);
            FxKit.streaks(p, sparks, f, hot, warm, r * 0.2f, 2.0f);
            FxKit.impactGlow(p, f, hot, warm, r * 3.6f, seed);
        }
    }

    // ---- rare ----

    static final class Storm extends Base {
        private static final int N = 24;
        private final float[] ax = new float[N], ay = new float[N], az = new float[N], aw = new float[N];
        private final int[] ac = new int[N];
        private int frameSeen = -1;

        Storm(KfxFxSpec spec) { super(spec, 1.0f); }

        @Override
        public void update(KfxFxFrame f) {
            splash(f, 2.0f);
            // crackle: a short fan of sparks somewhere along the bolt every few ticks
            if (f.length > 0.05f && sparks.emit(0.35f * spec.intensity(), f.dt) > 0) {
                float t = 0.15f + sparks.rand() * 0.8f;
                FxKit.spray(sparks, 5, f.alongX(t), f.alongY(t), f.alongZ(t), f.ux, f.uy, f.uz, 2.0f,
                    r * 0.8f, r * 2.2f, 4, 9, 0.8f);
            }
            sparks.integrate(f.dt, 0.18f, 0, -0.01f, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "lightning", r * 0.45f, color, hot, spec.glow() * 1.3f,
                0.3f, r * 3.0f, spec.speed() * 1.6f, 0, false, false, seed, f.age, f.fade);
            if (p.lightPass() && f.length > 0.05f) {
                int arcs = spec.atLeast(1.4f) ? 3 : 2;
                int tick = (int)(f.age * 0.6f * spec.speed());
                for (int k = 0; k < arcs; k++) arc(f, p, tick, k);
            }
            FxKit.streaks(p, sparks, f, hot, color, r * 0.18f, 1.6f);
            float flick = 0.7f + 0.3f * KfxNoise.hash01(spec.seed(), (int)(f.age * 2));
            FxKit.impactGlow(p, f, hot, color, r * 4.2f * flick, seed);
            flareStart(f, p, r * 5 * flick);
        }

        // one jagged arc around the main bolt, re-rolled every couple of ticks
        private void arc(KfxFxFrame f, KfxPaint p, int tick, int k) {
            long s = spec.seed() + tick * 7919L + k * 104729L;
            float amp = r * (3.5f + 3 * KfxNoise.hash01(s, 99));
            float life = KfxNoise.hash01(s, 98);
            if (life < 0.25f) return;
            for (int i = 0; i < N; i++) {
                float t = i / (float)(N - 1);
                float env = (float)Math.sin(Math.PI * t);
                float ou = (KfxNoise.hash01(s, i * 2) * 2 - 1) * amp * env;
                float ov = (KfxNoise.hash01(s, i * 2 + 1) * 2 - 1) * amp * env;
                ax[i] = f.alongX(t) + f.ux * ou + f.vx * ov;
                ay[i] = f.alongY(t) + f.uy * ou + f.vy * ov;
                az[i] = f.alongZ(t) + f.uz * ou + f.vz * ov;
                aw[i] = r * 0.32f * (0.5f + env);
                ac[i] = KfxColors.alpha(KfxColors.mix(hot, color, 0.35f), f.fade * life);
            }
            p.ribbon(ax, ay, az, aw, ac, N);
            for (int i = 0; i < N; i++) aw[i] *= 4.5f;
            for (int i = 0; i < N; i++) ac[i] = KfxColors.alpha(color, f.fade * life * 0.22f);
            p.ribbon(ax, ay, az, aw, ac, N);
        }
    }

    static final class Helix extends Base {
        private final float[] hx = new float[97], hy = new float[97], hz = new float[97], hw = new float[97];
        private final int[] hc = new int[97];
        private final int second;

        Helix(KfxFxSpec spec) {
            super(spec, 0.8f);
            second = KfxColors.hue(color, 38);
        }

        @Override
        public void update(KfxFxFrame f) {
            shed(f, 0.9f, r * 0.35f, r * 0.6f);
            splash(f, 1.4f);
            sparks.integrate(f.dt, 0.12f, 0, -0.008f, 0);
        }

        private float px, py, pz;

        // position on strand `strand` a fraction t along the beam
        private void at(KfxFxFrame f, float t, int strand) {
            float ramp = Math.min(1, t * f.length * 1.4f) * Math.min(1, (1 - t) * f.length * 2.5f + 0.25f);
            float rad = r * 2.6f * ramp;
            float th = t * f.length * 1.7f - f.age * 0.32f * spec.speed() + strand * (float)Math.PI;
            float cu = (float)Math.cos(th) * rad, sv = (float)Math.sin(th) * rad;
            px = f.alongX(t) + f.ux * cu + f.vx * sv;
            py = f.alongY(t) + f.uy * cu + f.vy * sv;
            pz = f.alongZ(t) + f.uz * cu + f.vz * sv;
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 0.5f, color, hot, spec.glow() * 0.8f, 0.08f, 0,
                spec.speed(), 0, false, false, seed, f.age, f.fade);
            if (f.length < 0.05f) return;
            int n = Math.clamp(Math.round(f.length * 7), 12, 96);
            int beads = spec.atLeast(1.4f) ? 5 : 3;
            for (int strand = 0; strand < 2; strand++) {
                int c = strand == 0 ? color : second;
                if (p.lightPass()) {
                    for (int i = 0; i <= n; i++) {
                        float t = i / (float)n;
                        at(f, t, strand);
                        hx[i] = px; hy[i] = py; hz[i] = pz;
                        hw[i] = r * 0.5f * (0.35f + 0.65f * (float)Math.sin(Math.PI * t));
                        hc[i] = KfxColors.alpha(KfxColors.mix(c, hot, 0.25f), f.fade * 0.9f);
                    }
                    p.ribbon(hx, hy, hz, hw, hc, n + 1);
                    for (int i = 0; i <= n; i++) { hw[i] *= 3.2f; hc[i] = KfxColors.alpha(c, f.fade * 0.2f); }
                    p.ribbon(hx, hy, hz, hw, hc, n + 1);
                }
                // beads riding the strand toward the target
                for (int b = 0; b < beads; b++) {
                    float t = (b / (float)beads + f.age * 0.012f * spec.speed()) % 1.0f;
                    at(f, t, strand);
                    float a = f.fade * (float)Math.sin(Math.PI * t);
                    p.spark(px, py, pz, r * 1.4f, KfxColors.alpha(KfxColors.mix(c, hot, 0.5f), a));
                    p.mesh(KfxPaint.GEM, px, py, pz, r * 0.55f, KfxColors.alpha(c, Math.min(1, a * 1.4f)),
                        f.dx, f.dy, f.dz, f.age * 0.15f + b);
                }
            }
            FxKit.streaks(p, sparks, f, hot, color, r * 0.16f, 2.0f);
            FxKit.impactGlow(p, f, hot, color, r * 3.4f, seed);
            flareStart(f, p, r * 4);
        }
    }

    // ---- epic ----

    static final class Rift extends Base {
        private final KfxSwarm pull;
        private final int abyss;

        Rift(KfxFxSpec spec) {
            super(spec, 0.25f);
            pull = new KfxSwarm(Math.max(12, Math.round(spec.count() * 0.75f)), spec.seed() ^ 0xA11);
            abyss = 0xF0000000 | (KfxColors.scale(color, 0.08f) & 0xFFFFFF);
        }

        @Override
        public void update(KfxFxFrame f) {
            if (f.length > 0.05f) {
                int n = pull.emit(4.0f * spec.intensity() * Math.min(3, f.length * 0.2f), f.dt);
                for (int k = 0; k < n; k++) {
                    float t = pull.rand(), a = pull.rand() * TAU, rad = r * (7 + pull.rand() * 6);
                    float cu = (float)Math.cos(a), sv = (float)Math.sin(a);
                    float ox = f.ux * cu + f.vx * sv, oy = f.uy * cu + f.vy * sv, oz = f.uz * cu + f.vz * sv;
                    // a little tangential start so everything spirals in instead of falling straight
                    float tx = f.vx * cu - f.ux * sv, ty = f.vy * cu - f.uy * sv, tz = f.vz * cu - f.uz * sv;
                    int i = pull.spawn(f.alongX(t) + ox * rad, f.alongY(t) + oy * rad, f.alongZ(t) + oz * rad,
                        tx * r * 0.35f, ty * r * 0.35f, tz * r * 0.35f, 40, 0.7f + pull.rand() * 0.6f);
                    if (i >= 0 && pull.rand() < 0.18f) pull.tag[i] = 1;
                }
            }
            // pull toward the nearest point of the axis, harder the closer it gets
            for (int i = pull.size() - 1; i >= 0; i--) {
                float rx = pull.x[i] - f.sx, ry = pull.y[i] - f.sy, rz = pull.z[i] - f.sz;
                float along = rx * f.dx + ry * f.dy + rz * f.dz;
                float qx = rx - f.dx * along, qy = ry - f.dy * along, qz = rz - f.dz * along;
                float d = (float)Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (d < r * 1.1f) { pull.kill(i); continue; }
                float acc = r * 0.06f * spec.speed() / Math.max(0.15f, d / (r * 6));
                pull.vx[i] -= qx / d * acc * f.dt;
                pull.vy[i] -= qy / d * acc * f.dt;
                pull.vz[i] -= qz / d * acc * f.dt;
            }
            pull.integrate(f.dt, 0.04f, 0, 0, 0);
            splash(f, 1.0f);
            sparks.integrate(f.dt, 0.1f, 0, 0, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            // dark core, bright rim: the core colour is near black and drawn solid, the sheath is light
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 1.2f, color, abyss, spec.glow() * 1.4f, 0.18f,
                0, spec.speed() * 0.5f, 0, false, false, seed, f.age, f.fade);
            for (int i = 0; i < pull.size(); i++) {
                float t = pull.t(i);
                float a = f.fade * Math.min(1, pull.age[i] * 0.15f);
                if (pull.tag[i] == 1) {
                    p.mesh(KfxPaint.SHARD, pull.x[i], pull.y[i], pull.z[i], r * 0.9f * pull.size[i],
                        KfxColors.alpha(KfxColors.mix(abyss, color, 0.35f), a), pull.vx[i], pull.vy[i] + 0.3f,
                        pull.vz[i], f.age * 0.2f + pull.seed[i]);
                    p.glow(pull.x[i], pull.y[i], pull.z[i], r * 2.2f, KfxColors.alpha(color, a * 0.35f));
                } else if (p.lightPass()) {
                    float s = 3.0f;
                    int head = KfxColors.alpha(KfxColors.mix(color, hot, t), a);
                    p.streak(pull.x[i] - pull.vx[i] * s, pull.y[i] - pull.vy[i] * s, pull.z[i] - pull.vz[i] * s,
                        pull.x[i], pull.y[i], pull.z[i], r * 0.16f * pull.size[i], head, KfxColors.alpha(color, 0));
                }
            }
            FxKit.streaks(p, sparks, f, hot, color, r * 0.18f, 2.0f);
            // the target folds inward: rings collapsing onto the end point
            for (int k = 0; k < 2; k++) {
                float ph = ((f.age * 0.045f * spec.speed() + k * 0.5f) % 1.0f);
                float rad = r * 11 * (1 - FxKit.easeOut(ph));
                p.ring(f.ex, f.ey, f.ez, f.dx, f.dy, f.dz, rad, r * 0.6f, KfxColors.alpha(color, f.fade * ph * 0.8f));
            }
            p.glow(f.ex, f.ey, f.ez, r * 7, KfxColors.alpha(color, f.fade * 0.5f));
            p.mesh(KfxPaint.ORB, f.ex, f.ey, f.ez, r * 1.6f, abyss, 0, 1, 0, 0);
        }
    }

    static final class Prism extends Base {
        private static final int N = 40;
        private final float[] bx = new float[N], by = new float[N], bz = new float[N], bw = new float[N];
        private final int[] bc = new int[N];
        private final int[] hues = new int[3];

        Prism(KfxFxSpec spec) {
            super(spec, 1.0f);
            hues[0] = color;
            hues[1] = KfxColors.hue(color, 120);
            hues[2] = KfxColors.hue(color, -120);
        }

        @Override
        public void update(KfxFxFrame f) {
            // glints: short lived stars along the split, each a hue of its own
            if (f.length > 0.05f) {
                int n = sparks.emit(1.6f * spec.intensity(), f.dt);
                for (int k = 0; k < n; k++) {
                    float t = 0.1f + sparks.rand() * 0.85f;
                    int i = sparks.spawn(f.alongX(t) + sparks.signed() * r * 4, f.alongY(t) + sparks.signed() * r * 4,
                        f.alongZ(t) + sparks.signed() * r * 4, 0, 0.004f, 0, 8 + sparks.rand() * 8, 0.7f + sparks.rand() * 0.6f);
                    if (i >= 0) sparks.tag[i] = (int)(sparks.rand() * 3);
                }
            }
            sparks.integrate(f.dt, 0.05f, 0, 0, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 0.45f, 0xFFFFFFFF, 0xFFFFFFFF, 0.6f, 0.05f, 0,
                spec.speed(), 0, false, false, seed, f.age, f.fade * 0.9f);
            if (f.length > 0.05f && p.lightPass()) {
                float spread = r * 4.5f * Math.min(1, f.length * 0.15f);
                for (int k = 0; k < 3; k++) {
                    float phase = k * TAU / 3 + f.age * 0.04f * spec.speed();
                    for (int i = 0; i < N; i++) {
                        float t = i / (float)(N - 1);
                        float env = (float)Math.pow(Math.sin(Math.PI * t), 0.8);
                        float tw = phase + t * 2.2f;
                        float ou = (float)Math.cos(tw) * spread * env, ov = (float)Math.sin(tw) * spread * env;
                        bx[i] = f.alongX(t) + f.ux * ou + f.vx * ov;
                        by[i] = f.alongY(t) + f.uy * ou + f.vy * ov;
                        bz[i] = f.alongZ(t) + f.uz * ou + f.vz * ov;
                        bw[i] = r * (0.35f + 0.4f * env);
                        bc[i] = KfxColors.alpha(KfxColors.mix(0xFFFFFFFF, hues[k], env), f.fade * 0.95f);
                    }
                    p.ribbon(bx, by, bz, bw, bc, N);
                    for (int i = 0; i < N; i++) { bw[i] *= 3.5f; bc[i] = KfxColors.alpha(hues[k], f.fade * 0.18f); }
                    p.ribbon(bx, by, bz, bw, bc, N);
                }
            }
            for (int i = 0; i < sparks.size(); i++) {
                float t = sparks.t(i);
                float a = f.fade * (float)Math.sin(Math.PI * t);
                p.flare(sparks.x[i], sparks.y[i], sparks.z[i], r * 2.4f * sparks.size[i],
                    KfxColors.alpha(KfxColors.mix(hues[sparks.tag[i]], 0xFFFFFFFF, 0.4f), a), sparks.seed[i] + f.age * 0.08f);
            }
            p.flare(f.ex, f.ey, f.ez, r * 9, KfxColors.alpha(0xFFFFFFFF, f.fade * 0.9f), f.age * 0.03f);
            p.glow(f.ex, f.ey, f.ez, r * 6, KfxColors.alpha(color, f.fade * 0.5f));
            flareStart(f, p, r * 4);
        }
    }

    // ---- legendary ----

    static final class Comet extends Base {
        private static final int TAIL = 14;
        private final int comets;
        private final float[] tx = new float[TAIL], ty = new float[TAIL], tz = new float[TAIL], tw = new float[TAIL];
        private final int[] tc = new int[TAIL];
        private final float[] progress;
        private final KfxSwarm rings;
        private final float[] sx = new float[11], sy = new float[11], sz = new float[11], sw = new float[11];
        private final int[] sc = new int[11];

        Comet(KfxFxSpec spec) {
            super(spec, 0.7f);
            comets = Math.clamp(Math.round(2 + spec.intensity() * 1.5f), 2, 6);
            progress = new float[comets];
            for (int i = 0; i < comets; i++) progress[i] = i / (float)comets;
            rings = new KfxSwarm(8, spec.seed() ^ 0xC0);
        }

        private float cx, cy, cz;

        private void cometAt(KfxFxFrame f, int k, float t) {
            float rad = r * 3.2f * (float)Math.sin(Math.PI * Math.min(1, t * 1.05f));
            float th = t * f.length * 0.9f + k * TAU / comets;
            float ou = (float)Math.cos(th) * rad, ov = (float)Math.sin(th) * rad;
            cx = f.alongX(t) + f.ux * ou + f.vx * ov;
            cy = f.alongY(t) + f.uy * ou + f.vy * ov;
            cz = f.alongZ(t) + f.uz * ou + f.vz * ov;
        }

        @Override
        public void update(KfxFxFrame f) {
            float step = f.dt * 0.022f * spec.speed() * Math.clamp(12 / Math.max(1, f.length), 0.4f, 2.5f);
            for (int k = 0; k < comets; k++) {
                progress[k] += step;
                if (progress[k] >= 1) {
                    progress[k] -= 1;
                    if (f.length > 0.05f) {
                        FxKit.spray(sparks, 7, f.ex, f.ey, f.ez, -f.dx, -f.dy, -f.dz, 1.4f, r * 1.5f, r * 3.5f, 8, 16, 1);
                        rings.spawn(f.ex, f.ey, f.ez, 0, 0, 0, 14, 1);
                    }
                }
            }
            sparks.integrate(f.dt, 0.12f, 0, -0.012f, 0);
            rings.integrate(f.dt, 0, 0, 0, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 0.7f, color, hot, spec.glow() * 1.2f, 0.1f, 0,
                spec.speed(), 0, false, false, seed, f.age, f.fade);
            if (f.length < 0.05f) return;
            for (int k = 0; k < comets; k++) {
                float head = progress[k];
                for (int i = 0; i < TAIL; i++) {
                    float t = Math.max(0, head - i * 0.018f);
                    cometAt(f, k, t);
                    tx[i] = cx; ty[i] = cy; tz[i] = cz;
                    float u = i / (float)(TAIL - 1);
                    tw[i] = r * 0.9f * (1 - u) + r * 0.05f;
                    tc[i] = KfxColors.alpha(KfxColors.mix(hot, color, u), f.fade * (1 - u));
                }
                p.ribbon(tx, ty, tz, tw, tc, TAIL);
                for (int i = 0; i < TAIL; i++) { tw[i] *= 3; tc[i] = KfxColors.alpha(tc[i], 0.25f); }
                p.ribbon(tx, ty, tz, tw, tc, TAIL);
                cometAt(f, k, head);
                p.spark(cx, cy, cz, r * 2.2f, KfxColors.alpha(hot, f.fade));
                p.mesh(KfxPaint.STAR, cx, cy, cz, r * 0.7f, KfxColors.alpha(KfxColors.mix(color, hot, 0.5f), f.fade),
                    f.dx, f.dy, f.dz, f.age * 0.3f + k);
            }
            FxKit.streaks(p, sparks, f, hot, color, r * 0.2f, 2.0f);
            for (int i = 0; i < rings.size(); i++) {
                float t = rings.t(i);
                p.ring(f.ex, f.ey, f.ez, f.dx, f.dy, f.dz, r * (2 + 12 * FxKit.easeOut(t)), r * 0.8f * (1 - t),
                    KfxColors.alpha(KfxColors.mix(hot, color, t), f.fade * (1 - t)));
            }
            sigil(f, p);
            FxKit.impactGlow(p, f, hot, color, r * 4, seed);
            flareStart(f, p, r * 6);
        }

        // a pentagram of light turning in front of the target
        private void sigil(KfxFxFrame f, KfxPaint p) {
            if (!p.lightPass()) return;
            float rad = r * 9, turn = f.age * 0.02f * spec.speed();
            float pulse = 0.75f + 0.25f * (float)Math.sin(f.age * 0.15f);
            float bx = f.ex - f.dx * r * 2, by = f.ey - f.dy * r * 2, bz = f.ez - f.dz * r * 2;
            for (int i = 0; i <= 10; i++) {
                int pt = (i * 2) % 5;
                float a = turn + pt * TAU / 5;
                float cu = (float)Math.cos(a) * rad, sv = (float)Math.sin(a) * rad;
                sx[i] = bx + f.ux * cu + f.vx * sv; sy[i] = by + f.uy * cu + f.vy * sv; sz[i] = bz + f.uz * cu + f.vz * sv;
                sw[i] = r * 0.3f;
                sc[i] = KfxColors.alpha(KfxColors.mix(color, hot, 0.4f), f.fade * pulse * 0.85f);
            }
            p.ribbon(sx, sy, sz, sw, sc, 6);
            p.ring(bx, by, bz, f.dx, f.dy, f.dz, rad * 1.08f, r * 0.25f, KfxColors.alpha(color, f.fade * pulse * 0.7f));
            for (int i = 0; i < 5; i++) p.spark(sx[i], sy[i], sz[i], r * 1.3f, KfxColors.alpha(hot, f.fade * pulse));
        }
    }

    static final class Serpent extends Base {
        private static final int N = 64;
        private final float[] bx = new float[N], by = new float[N], bz = new float[N], bw = new float[N];
        private final int[] bc = new int[N];
        private final KfxSwarm scales;
        private final int belly;

        Serpent(KfxFxSpec spec) {
            super(spec, 0.3f);
            scales = new KfxSwarm(Math.max(10, Math.round(spec.count() * 0.7f)), spec.seed() ^ 0x5E4);
            belly = KfxColors.hue(color, 42);
        }

        private float px, py, pz;

        private void body(KfxFxFrame f, float t) {
            float env = (float)Math.pow(Math.sin(Math.PI * t), 0.6);
            float amp = r * 4.2f * env;
            float w = t * f.length * 0.75f - f.age * 0.22f * spec.speed();
            float ou = (float)Math.sin(w) * amp, ov = (float)Math.cos(w * 0.8f + 1.3f) * amp * 0.8f;
            px = f.alongX(t) + f.ux * ou + f.vx * ov;
            py = f.alongY(t) + f.uy * ou + f.vy * ov;
            pz = f.alongZ(t) + f.uz * ou + f.vz * ov;
        }

        @Override
        public void update(KfxFxFrame f) {
            if (f.length > 0.05f) {
                int n = scales.emit(3.0f * spec.intensity() * Math.min(3, f.length * 0.2f), f.dt);
                for (int k = 0; k < n; k++) {
                    body(f, scales.rand());
                    int i = scales.spawn(px, py, pz, scales.signed() * r * 0.15f, scales.signed() * r * 0.15f,
                        scales.signed() * r * 0.15f, 20 + scales.rand() * 20, 0.6f + scales.rand() * 0.7f);
                    if (i >= 0 && scales.rand() < 0.3f) scales.tag[i] = 1;
                }
            }
            scales.curl(f.dt, 0.004f, 0.8f, f.age * 0.03f);
            scales.integrate(f.dt, 0.06f, 0, 0.001f, 0);
            splash(f, 1.4f);
            sparks.integrate(f.dt, 0.12f, 0, -0.015f, 0);
        }

        @Override
        public void draw(KfxFxFrame f, KfxPaint p) {
            p.laser(f.sx, f.sy, f.sz, f.ex, f.ey, f.ez, "tube", r * 0.55f, color, hot, spec.glow(), 0.1f, 0,
                spec.speed(), 0, false, false, seed, f.age, f.fade);
            if (f.length < 0.05f) return;
            if (p.lightPass()) {
                for (int i = 0; i < N; i++) {
                    float t = i / (float)(N - 1);
                    body(f, t);
                    bx[i] = px; by[i] = py; bz[i] = pz;
                    float swell = 0.55f + 0.45f * (float)Math.sin(t * 9 - f.age * 0.4f * spec.speed());
                    bw[i] = r * 1.25f * swell * (float)Math.pow(Math.sin(Math.PI * t), 0.4);
                    bc[i] = KfxColors.alpha(KfxColors.mix(color, belly, swell), f.fade * 0.9f);
                }
                p.ribbon(bx, by, bz, bw, bc, N);
                for (int i = 0; i < N; i++) { bw[i] *= 0.35f; bc[i] = KfxColors.alpha(hot, f.fade * 0.9f); }
                p.ribbon(bx, by, bz, bw, bc, N);
                for (int i = 0; i < N; i++) { bw[i] *= 9; bc[i] = KfxColors.alpha(color, f.fade * 0.14f); }
                p.ribbon(bx, by, bz, bw, bc, N);
            }
            // scales along the back, solid
            int plates = Math.clamp(Math.round(f.length * 1.5f), 6, 28);
            for (int k = 1; k < plates; k++) {
                float t = k / (float)plates;
                body(f, t);
                float s = r * 0.75f * (float)Math.pow(Math.sin(Math.PI * t), 0.5);
                p.mesh(KfxPaint.SHARD, px, py, pz, s, KfxColors.alpha(KfxColors.mix(color, belly, t), f.fade),
                    f.dx, f.dy, f.dz, t * 7 + f.age * 0.05f);
            }
            for (int i = 0; i < scales.size(); i++) {
                float t = scales.t(i);
                float a = f.fade * Math.min(1, scales.age[i] * 0.3f) * (1 - t);
                if (scales.tag[i] == 1) p.mesh(KfxPaint.GEM, scales.x[i], scales.y[i], scales.z[i],
                    r * 0.35f * scales.size[i] * (1 - t), KfxColors.alpha(belly, a), scales.vx[i], 1, scales.vz[i],
                    f.age * 0.2f + scales.seed[i]);
                p.spark(scales.x[i], scales.y[i], scales.z[i], r * 0.9f * scales.size[i] * (1 - t * 0.5f),
                    KfxColors.alpha(KfxColors.life(hot, KfxColors.mix(color, belly, scales.seed[i] % 1), t), a));
            }
            FxKit.streaks(p, sparks, f, hot, color, r * 0.18f, 2.0f);
            p.flare(f.ex, f.ey, f.ez, r * 8, KfxColors.alpha(hot, f.fade * 0.85f), -f.age * 0.04f);
            FxKit.impactGlow(p, f, hot, color, r * 4.5f, seed);
            flareStart(f, p, r * 5);
        }
    }
}
