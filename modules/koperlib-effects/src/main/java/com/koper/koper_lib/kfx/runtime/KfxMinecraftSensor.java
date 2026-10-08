package com.koper.koper_lib.kfx.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class KfxMinecraftSensor implements KfxSensor {
    public static final int MAX_BLOCK_CHECKS_PER_LEVEL_TICK = 8192;
    private final ServerLevel level;
    private final KfxController controller;
    private final Budget budget;

    public KfxMinecraftSensor(ServerLevel level, KfxController controller, Budget budget) {
        this.level = level;
        this.controller = controller;
        this.budget = budget;
    }

    @Override
    public Contact sweep(Vec3 from, Vec3 to, double radius, Set<UUID> ignoredEntities) {
        Contact block = blockSweep(from, to, radius);
        if (block != null && block.unavailable()) return block;
        Contact entity = entitySweep(from, to, radius, ignoredEntities);
        return KfxSensor.nearest(from, block, entity);
    }

    private Contact blockSweep(Vec3 from, Vec3 to, double radius) {
        int pad = (int)Math.ceil(radius);
        Set<Long> checked = new HashSet<>();
        Contact closest = null;
        for (BlockPos center : centerlineVoxels(from, to)) {
            for (int ox = -pad; ox <= pad; ox++) for (int oy = -pad; oy <= pad; oy++) {
                for (int oz = -pad; oz <= pad; oz++) {
                    BlockPos pos = center.offset(ox, oy, oz);
                    if (!checked.add(pos.asLong())) continue;
                    if (!touchesCell(from, to, pos, radius)) continue;
                    if (!budget.take()) return Contact.unavailable(from);
                    if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) continue;
                    if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return Contact.unavailable(from);
                    var shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty());
                    if (shape.isEmpty()) continue;
                    for (AABB local : shape.toAabbs()) {
                        AABB world = local.move(pos.getX(), pos.getY(), pos.getZ());
                        closest = KfxSensor.nearest(from, closest,
                            KfxSensor.clipBox(world, pos, from, to, radius));
                    }
                }
            }
        }
        return closest;
    }

    private static Iterable<BlockPos> centerlineVoxels(Vec3 from, Vec3 to) {
        var cells = new ArrayList<BlockPos>(32);
        int x = net.minecraft.util.Mth.floor(from.x);
        int y = net.minecraft.util.Mth.floor(from.y);
        int z = net.minecraft.util.Mth.floor(from.z);
        int tx = net.minecraft.util.Mth.floor(to.x);
        int ty = net.minecraft.util.Mth.floor(to.y);
        int tz = net.minecraft.util.Mth.floor(to.z);
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int sx = Integer.compare(tx, x), sy = Integer.compare(ty, y), sz = Integer.compare(tz, z);
        double dtx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double dty = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double dtz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double mx = firstBoundary(from.x, x, sx, dx);
        double my = firstBoundary(from.y, y, sy, dy);
        double mz = firstBoundary(from.z, z, sz, dz);
        for (int guard = 0; guard < 64; guard++) {
            cells.add(new BlockPos(x, y, z));
            if (x == tx && y == ty && z == tz) break;
            if (mx <= my && mx <= mz) { x += sx; mx += dtx; }
            else if (my <= mz) { y += sy; my += dty; }
            else { z += sz; mz += dtz; }
        }
        return cells;
    }

    private static double firstBoundary(double value, int cell, int step, double delta) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return step > 0 ? (cell + 1.0 - value) / delta : (value - cell) / -delta;
    }

    static boolean touchesCell(Vec3 from, Vec3 to, BlockPos pos, double radius) {
        AABB cell = new AABB(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(radius);
        return cell.contains(from) || cell.contains(to) || cell.clip(from, to).isPresent();
    }

    private Contact entitySweep(Vec3 from, Vec3 to, double radius, Set<UUID> ignored) {
        AABB search = new AABB(from, to).inflate(radius + 1.0);
        UUID owner = controller.owner();
        Entity ownerEntity = level.getEntity(owner);
        Contact closest = null;
        for (Entity entity : level.getEntities(ownerEntity, search, entity ->
                entity.isAlive() && !entity.isSpectator() && entity.isPickable()
                    && !entity.getUUID().equals(owner) && !ignored.contains(entity.getUUID())
                    && controller.canHit(entity))) {
            AABB box = entity.getBoundingBox().inflate(radius);
            Vec3 point = box.clip(from, to).orElse(null);
            if (point == null) continue;
            closest = KfxSensor.nearest(from, closest,
                Contact.entity(entity.getUUID(), point, boxNormal(box, point)));
        }
        return closest;
    }

    private static Vec3 boxNormal(AABB box, Vec3 point) {
        double[] d = {
            Math.abs(point.x - box.minX), Math.abs(point.x - box.maxX),
            Math.abs(point.y - box.minY), Math.abs(point.y - box.maxY),
            Math.abs(point.z - box.minZ), Math.abs(point.z - box.maxZ)
        };
        int nearest = 0;
        for (int i = 1; i < d.length; i++) if (d[i] < d[nearest]) nearest = i;
        return switch (nearest) {
            case 0 -> new Vec3(-1, 0, 0);
            case 1 -> new Vec3(1, 0, 0);
            case 2 -> new Vec3(0, -1, 0);
            case 3 -> new Vec3(0, 1, 0);
            case 4 -> new Vec3(0, 0, -1);
            default -> new Vec3(0, 0, 1);
        };
    }

    public static final class Budget {
        private int left;

        public Budget(int checks) {
            if (checks < 1) throw new IllegalArgumentException("KFX collision budget must be positive");
            left = checks;
        }

        public boolean take() {
            if (left == 0) return false;
            left--;
            return true;
        }
    }
}
