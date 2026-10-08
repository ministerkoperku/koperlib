package com.koper.koper_lib.kfx.fx;

import java.util.SplittableRandom;

/**
 * A fixed-size pool of simple particles, stored as parallel arrays.
 *
 * <p>Nothing allocates after construction. Dead particles are swapped with the last live one, so the
 * live range is always {@code 0..size()-1}; iterate it backwards when killing inside the loop.
 * {@code px/py/pz} keep the position before the last {@link #integrate} for motion streaks.
 */
public final class KfxSwarm {
    public final float[] x, y, z, px, py, pz, vx, vy, vz, age, life, size, seed;
    /** Free per-particle slot for the effect, for example which layer a particle belongs to. */
    public final int[] tag;
    private final SplittableRandom random;
    private int live;
    private float pending;

    public KfxSwarm(int capacity, long randomSeed) {
        int n = Math.max(1, capacity);
        x = new float[n]; y = new float[n]; z = new float[n];
        px = new float[n]; py = new float[n]; pz = new float[n];
        vx = new float[n]; vy = new float[n]; vz = new float[n];
        age = new float[n]; life = new float[n]; size = new float[n]; seed = new float[n];
        tag = new int[n];
        random = new SplittableRandom(randomSeed);
    }

    public int size() { return live; }
    public int capacity() { return x.length; }

    /** Uniform 0..1 from the swarm's own generator. */
    public float rand() { return (float)random.nextDouble(); }
    /** Uniform -1..1. */
    public float signed() { return (float)random.nextDouble() * 2.0f - 1.0f; }

    /** Normalised age, 0 at birth and 1 at death. */
    public float t(int i) { return age[i] / life[i]; }

    /** Adds a particle; returns its index, or -1 when the pool is full. */
    public int spawn(float x0, float y0, float z0, float vx0, float vy0, float vz0, float lifeTicks, float size0) {
        if (live >= x.length) return -1;
        int i = live++;
        x[i] = px[i] = x0; y[i] = py[i] = y0; z[i] = pz[i] = z0;
        vx[i] = vx0; vy[i] = vy0; vz[i] = vz0;
        age[i] = 0; life[i] = Math.max(1.0f, lifeTicks); size[i] = size0;
        seed[i] = (float)random.nextDouble() * 1000.0f;
        tag[i] = 0;
        return i;
    }

    /**
     * How many particles to emit this frame for a rate per tick. Fractions carry over, so a rate of 0.3
     * still emits one particle every three or four ticks at any frame rate.
     */
    public int emit(float perTick, float dt) {
        pending += Math.max(0.0f, perTick) * dt;
        int n = (int)pending;
        pending -= n;
        return n;
    }

    public void kill(int i) {
        int last = --live;
        if (i == last) return;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        age[i] = age[last]; life[i] = life[last]; size[i] = size[last]; seed[i] = seed[last];
        tag[i] = tag[last];
    }

    public void clear() { live = 0; }

    /**
     * Ages every particle, applies drag and a constant acceleration, moves it and removes the dead.
     * Drag is per tick: 0.1 loses ten percent of the speed each tick at any frame rate.
     */
    public void integrate(float dt, float drag, float ax, float ay, float az) {
        if (dt <= 0) return;
        float keep = (float)Math.pow(1.0 - Math.clamp(drag, 0.0f, 0.99f), dt);
        for (int i = live - 1; i >= 0; i--) {
            age[i] += dt;
            if (age[i] >= life[i]) { kill(i); continue; }
            px[i] = x[i]; py[i] = y[i]; pz[i] = z[i];
            vx[i] = (vx[i] + ax * dt) * keep;
            vy[i] = (vy[i] + ay * dt) * keep;
            vz[i] = (vz[i] + az * dt) * keep;
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt;
        }
    }

    /** Pushes every particle along the curl of a noise field: smoke-like swirling that never converges. */
    public void curl(float dt, float strength, float scale, float time) {
        if (dt <= 0 || strength == 0) return;
        float[] out = new float[3];
        for (int i = 0; i < live; i++) {
            KfxNoise.curl(x[i] * scale, y[i] * scale, z[i] * scale + time, out);
            vx[i] += out[0] * strength * dt;
            vy[i] += out[1] * strength * dt;
            vz[i] += out[2] * strength * dt;
        }
    }
}
