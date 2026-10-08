package com.koper.koper_lib.kfx.fx.builtin;

import com.koper.koper_lib.kfx.KfxPaint;
import com.koper.koper_lib.kfx.fx.KfxColors;
import com.koper.koper_lib.kfx.fx.KfxFxFrame;
import com.koper.koper_lib.kfx.fx.KfxSwarm;

// small pieces every built-in effect shares
final class FxKit {
    static final float TAU = (float)(Math.PI * 2);

    private FxKit() {}

    /** Draws every live particle of a swarm as a motion streak, white hot when young. */
    static void streaks(KfxPaint paint, KfxSwarm s, KfxFxFrame f, int hot, int color, float width, float stretch) {
        if (!paint.lightPass()) return;
        for (int i = 0; i < s.size(); i++) {
            float t = s.t(i);
            float a = f.fade * (1 - t * t) * Math.min(1, s.age[i] * 0.5f + 0.3f);
            if (a <= 0.01f) continue;
            int head = KfxColors.alpha(KfxColors.life(hot, color, t), a);
            float tx = s.x[i] - s.vx[i] * stretch, ty = s.y[i] - s.vy[i] * stretch, tz = s.z[i] - s.vz[i] * stretch;
            paint.streak(tx, ty, tz, s.x[i], s.y[i], s.z[i], width * s.size[i] * (1 - t * 0.6f), head,
                KfxColors.alpha(color, a * 0.15f));
        }
    }

    /** Draws every live particle of a swarm as a soft twinkling mote. */
    static void motes(KfxPaint paint, KfxSwarm s, KfxFxFrame f, int hot, int color, float radius) {
        if (!paint.lightPass()) return;
        for (int i = 0; i < s.size(); i++) {
            float t = s.t(i);
            float tw = 0.75f + 0.25f * (float)Math.sin(f.age * 0.9f + s.seed[i]);
            float a = f.fade * Math.min(1, s.age[i] * 0.25f) * (1 - t) * tw;
            if (a <= 0.01f) continue;
            paint.spark(s.x[i], s.y[i], s.z[i], radius * s.size[i] * (1.1f - t * 0.6f),
                KfxColors.alpha(KfxColors.life(hot, color, t), a));
        }
    }

    /** Sprays sparks from a point into a cone around an axis. */
    static void spray(KfxSwarm s, int n, float x, float y, float z, float ax, float ay, float az,
                      float cone, float speedMin, float speedMax, float lifeMin, float lifeMax, float size) {
        for (int k = 0; k < n; k++) {
            float rx = s.signed(), ry = s.signed(), rz = s.signed();
            float dx = ax + rx * cone, dy = ay + ry * cone, dz = az + rz * cone;
            float l = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1.0e-4f) continue;
            float sp = speedMin + s.rand() * (speedMax - speedMin);
            s.spawn(x, y, z, dx / l * sp, dy / l * sp, dz / l * sp,
                lifeMin + s.rand() * (lifeMax - lifeMin), size * (0.6f + s.rand() * 0.8f));
        }
    }

    /** The glowing point where a beam lands, breathing slightly. */
    static void impactGlow(KfxPaint paint, KfxFxFrame f, int hot, int color, float radius, float seed) {
        float pulse = 0.85f + 0.15f * (float)Math.sin(f.age * 0.7f + seed);
        paint.glow(f.ex, f.ey, f.ez, radius * 2.4f * pulse, KfxColors.alpha(color, f.fade * 0.55f));
        paint.spark(f.ex, f.ey, f.ez, radius * pulse, KfxColors.alpha(hot, f.fade * 0.95f));
    }

    static float smooth(float t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float easeOut(float t) {
        t = Math.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }
}
