package com.koper.koper_lib.physics;

import net.minecraft.world.phys.Vec3;

public final class KontraMotionState {
    public final long body, epoch;
    public final String dimension;
    private Vec3 anchor;
    private long sequence, lastTick = Long.MIN_VALUE;
    public KontraMotionState(long body, String dimension, long epoch, Vec3 anchor) {
        this.body = body; this.dimension = dimension; this.epoch = epoch; this.anchor = anchor;
    }
    public boolean accept(long body, String dimension, long epoch, long sequence, long tick, Vec3 proposed) {
        if (body != this.body || !this.dimension.equals(dimension) || epoch != this.epoch
                || sequence <= this.sequence || tick <= lastTick || !KontraFrame.finite(proposed)) return false;
        long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.min(4, tick - lastTick);
        if (proposed.distanceToSqr(anchor) > 2.25 * elapsed * elapsed) return false;
        this.sequence = sequence; lastTick = tick;
        return true;
    }
    public Vec3 anchor() { return anchor; }
    public long sequence() { return sequence; }
    public void solved(Vec3 anchor) {
        if (!KontraFrame.finite(anchor)) throw new IllegalArgumentException("invalid local anchor");
        this.anchor = anchor;
    }
}
