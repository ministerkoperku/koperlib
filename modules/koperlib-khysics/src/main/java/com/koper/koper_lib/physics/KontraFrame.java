package com.koper.koper_lib.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public record KontraFrame(Vec3 origin, Quaterniond rotation, Vec3 linearVelocity,
                          Vec3 angularVelocity, Vec3 centerOfMass, long epoch) {
    public KontraFrame {
        if (!finite(origin) || !finite(linearVelocity) || !finite(angularVelocity) || !finite(centerOfMass)
                || rotation == null || !rotation.isFinite() || rotation.lengthSquared() < 1e-12)
            throw new IllegalArgumentException("invalid body frame");
        rotation = new Quaterniond(rotation).normalize();
    }
    @Override public Quaterniond rotation() { return new Quaterniond(rotation); }
    public Vec3 toWorld(Vec3 local) { return directionToWorld(local).add(origin); }
    public Vec3 toLocal(Vec3 world) {
        Vec3 p = world.subtract(origin);
        return vec(rotation.transformInverse(new Vector3d(p.x, p.y, p.z)));
    }
    public Vec3 directionToWorld(Vec3 local) {
        return vec(rotation.transform(new Vector3d(local.x, local.y, local.z)));
    }
    public Vec3 velocityAt(Vec3 world) {
        return linearVelocity.add(angularVelocity.cross(world.subtract(centerOfMass)));
    }
    public static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static Vec3 vec(Vector3d v) { return new Vec3(v.x, v.y, v.z); }
}
