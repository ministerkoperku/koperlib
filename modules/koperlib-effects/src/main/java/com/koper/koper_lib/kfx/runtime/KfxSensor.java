package com.koper.koper_lib.kfx.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface KfxSensor {
    Contact sweep(Vec3 from, Vec3 to, double radius, Set<UUID> ignoredEntities);

    static Contact clipBox(AABB shape, BlockPos block, Vec3 from, Vec3 to, double radius) {
        AABB fat = shape.inflate(Math.max(0, radius));
        Vec3 point = fat.clip(from, to).orElse(null);
        if (point == null) return null;
        double[] distance = {
            Math.abs(point.x - fat.minX), Math.abs(point.x - fat.maxX),
            Math.abs(point.y - fat.minY), Math.abs(point.y - fat.maxY),
            Math.abs(point.z - fat.minZ), Math.abs(point.z - fat.maxZ)
        };
        int nearest = 0;
        for (int i = 1; i < distance.length; i++) if (distance[i] < distance[nearest]) nearest = i;
        Vec3 normal = switch (nearest) {
            case 0 -> new Vec3(-1, 0, 0);
            case 1 -> new Vec3(1, 0, 0);
            case 2 -> new Vec3(0, -1, 0);
            case 3 -> new Vec3(0, 1, 0);
            case 4 -> new Vec3(0, 0, -1);
            default -> new Vec3(0, 0, 1);
        };
        return Contact.block(block, point, normal);
    }

    static Contact nearest(Vec3 from, Contact a, Contact b) {
        if (a == null) return b;
        if (b == null) return a;
        return from.distanceToSqr(a.position) <= from.distanceToSqr(b.position) ? a : b;
    }

    record Contact(UUID entity, BlockPos block, Vec3 position, Vec3 normal, boolean unavailable) {
        public Contact {
            if (!unavailable && (entity == null) == (block == null)) {
                throw new IllegalArgumentException("KFX contact needs one target");
            }
            if (unavailable && (entity != null || block != null)) {
                throw new IllegalArgumentException("unavailable KFX terrain cannot have a target");
            }
            if (position == null || normal == null || normal.lengthSqr() < 1.0e-8) {
                throw new IllegalArgumentException("KFX contact needs a position and normal");
            }
            normal = normal.normalize();
        }

        public static Contact entity(UUID entity, Vec3 position, Vec3 normal) {
            return new Contact(entity, null, position, normal, false);
        }

        public static Contact block(BlockPos block, Vec3 position, Vec3 normal) {
            return new Contact(null, block, position, normal, false);
        }

        public static Contact unavailable(Vec3 safePosition) {
            return new Contact(null, null, safePosition, new Vec3(0, 1, 0), true);
        }
    }
}
