package com.koper.koper_lib.kfx.runtime;

public final class KfxLossFade {
    private float start = Float.NaN;
    private float duration = 1;

    public void begin(float now, float duration) {
        if (!Float.isNaN(start)) return;
        this.start = now;
        this.duration = Math.max(1.0f, duration);
    }

    public float alpha(float now) {
        if (Float.isNaN(start)) return 1.0f;
        return Math.clamp(1.0f - (now - start) / duration, 0.0f, 1.0f);
    }

    public boolean done(float now) {
        return !Float.isNaN(start) && now - start >= duration;
    }

    public void cancel() {
        start = Float.NaN;
    }
}
