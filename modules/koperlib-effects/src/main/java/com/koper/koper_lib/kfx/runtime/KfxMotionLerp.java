package com.koper.koper_lib.kfx.runtime;

import net.minecraft.world.phys.Vec3;

public final class KfxMotionLerp {
    private Frame from;
    private Frame target;
    private float started;

    public KfxMotionLerp(Vec3 start, Vec3 end) {
        from = target = new Frame(start, end);
    }

    public void retarget(float now, Vec3 start, Vec3 end) {
        from = sample(now);
        target = new Frame(start, end);
        started = now;
    }

    public Frame sample(float now) {
        double t = Math.clamp(now - started, 0.0f, 1.0f);
        return new Frame(from.start.lerp(target.start, t), from.end.lerp(target.end, t));
    }

    public record Frame(Vec3 start, Vec3 end) {}
}
