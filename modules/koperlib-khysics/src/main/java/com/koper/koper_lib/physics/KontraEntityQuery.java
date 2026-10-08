package com.koper.koper_lib.physics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Immutable world-space oriented box for an entity query made by a moving grid. */
public final class KontraEntityQuery {
    private final double[] axes = new double[9];
    private final double cx, cy, cz, hx, hy, hz;
    private final AABB bounds;

    /** Grid coordinates occupy the reserved logical region; world queries keep their coordinates. */
    public static boolean isGridQuery(ServerLevel level, KontraGrid grid, AABB box) {
        return grid != null && grid.entry().levelKey().equals(KoperPhys.levelKey(level))
            && (box.minZ + box.maxZ) * .5 <= KontraGrid.REGION_MAX_Z;
    }

    /** Returns null if the body's pose is unavailable. Call only after isGridQuery succeeds. */
    public static KontraEntityQuery of(ServerLevel level, KontraGrid grid, AABB box) {
        if (!isGridQuery(level, grid, box)) return null;
        float[] position = KoperPhys.cachedPos(grid.kontraId());
        float[] rotation = KoperPhys.cachedRot(grid.kontraId());
        if (position == null || rotation == null) return null;
        double x = (box.minX + box.maxX) * .5;
        double y = (box.minY + box.maxY) * .5;
        double z = (box.minZ + box.maxZ) * .5;
        float[] offset = grid.offsetForBoundGridPoint(x, y, z);
        double hx = (box.maxX - box.minX) * .5;
        double hy = (box.maxY - box.minY) * .5;
        double hz = (box.maxZ - box.minZ) * .5;
        return new KontraEntityQuery(new AABB(offset[0] - hx, offset[1] - hy, offset[2] - hz,
            offset[0] + hx, offset[1] + hy, offset[2] + hz), position, rotation);
    }

    KontraEntityQuery(AABB localBox, float[] position, float[] rotation) {
        double x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
        double scale = 2 / (x*x + y*y + z*z + w*w);
        // Columns of the normalized quaternion rotation matrix: local axes in world space.
        axes[0] = 1 - scale*(y*y + z*z);
        axes[1] = scale*(x*y + z*w);
        axes[2] = scale*(x*z - y*w);
        axes[3] = scale*(x*y - z*w);
        axes[4] = 1 - scale*(x*x + z*z);
        axes[5] = scale*(y*z + x*w);
        axes[6] = scale*(x*z + y*w);
        axes[7] = scale*(y*z - x*w);
        axes[8] = 1 - scale*(x*x + y*y);
        double lx = (localBox.minX + localBox.maxX) * .5;
        double ly = (localBox.minY + localBox.maxY) * .5;
        double lz = (localBox.minZ + localBox.maxZ) * .5;
        cx = position[0] + axes[0]*lx + axes[3]*ly + axes[6]*lz;
        cy = position[1] + axes[1]*lx + axes[4]*ly + axes[7]*lz;
        cz = position[2] + axes[2]*lx + axes[5]*ly + axes[8]*lz;
        hx = (localBox.maxX - localBox.minX) * .5;
        hy = (localBox.maxY - localBox.minY) * .5;
        hz = (localBox.maxZ - localBox.minZ) * .5;
        double wx = Math.abs(axes[0])*hx + Math.abs(axes[3])*hy + Math.abs(axes[6])*hz;
        double wy = Math.abs(axes[1])*hx + Math.abs(axes[4])*hy + Math.abs(axes[7])*hz;
        double wz = Math.abs(axes[2])*hx + Math.abs(axes[5])*hy + Math.abs(axes[8])*hz;
        bounds = new AABB(cx - wx, cy - wy, cz - wz, cx + wx, cy + wy, cz + wz);
    }

    public AABB bounds() { return bounds; }

    /** SAT on the three world axes, three grid axes and nine edge cross products. */
    public boolean intersects(AABB box) {
        if (!bounds.intersects(box)) return false;
        double bx = (box.maxX - box.minX) * .5;
        double by = (box.maxY - box.minY) * .5;
        double bz = (box.maxZ - box.minZ) * .5;
        double dx = (box.minX + box.maxX) * .5 - cx;
        double dy = (box.minY + box.maxY) * .5 - cy;
        double dz = (box.minZ + box.maxZ) * .5 - cz;
        // The world-axis tests are already covered by the enclosing AABB overlap.
        for (int i = 0; i < 9; i += 3) {
            double x = axes[i], y = axes[i + 1], z = axes[i + 2];
            if (separated(x, y, z, dx, dy, dz, bx, by, bz)
                || separated(0, z, -y, dx, dy, dz, bx, by, bz)
                || separated(-z, 0, x, dx, dy, dz, bx, by, bz)
                || separated(y, -x, 0, dx, dy, dz, bx, by, bz)) return false;
        }
        return true;
    }

    private boolean separated(double x, double y, double z, double dx, double dy, double dz,
                              double bx, double by, double bz) {
        if (x*x + y*y + z*z < 1e-24) return false; // parallel edges have no separating axis
        double queryRadius = hx*Math.abs(x*axes[0] + y*axes[1] + z*axes[2])
            + hy*Math.abs(x*axes[3] + y*axes[4] + z*axes[5])
            + hz*Math.abs(x*axes[6] + y*axes[7] + z*axes[8]);
        double entityRadius = bx*Math.abs(x) + by*Math.abs(y) + bz*Math.abs(z);
        return Math.abs(x*dx + y*dy + z*dz) >= queryRadius + entityRadius;
    }
}
