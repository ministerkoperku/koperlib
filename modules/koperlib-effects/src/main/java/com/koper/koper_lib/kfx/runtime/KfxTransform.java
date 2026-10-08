package com.koper.koper_lib.kfx.runtime;

import net.minecraft.world.phys.Vec3;

public record KfxTransform(Vec3 position, Vec3 forward, Vec3 normal) {
    public KfxTransform {
        position = position == null ? Vec3.ZERO : position;
        forward = forward == null || forward.lengthSqr() < 1.0e-8 ? new Vec3(0, 0, 1) : forward.normalize();
        normal = normal == null || normal.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : normal.normalize();
    }

    public static KfxTransform at(Vec3 position) {
        return new KfxTransform(position, new Vec3(0, 0, 1), new Vec3(0, 1, 0));
    }
}
