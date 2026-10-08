package com.koper.koper_lib.compat.create;

import com.koper.koper_lib.kender.KenderClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@Environment(EnvType.CLIENT)
final class KoperCreateContraptionsClient {
    private KoperCreateContraptionsClient() {}

    static Vec3 mapSetPos(Entity entity, KoperCreateContraptionAccess access,
            double x, double y, double z) {
        KenderClientState.KontraRenderData grid = access.koperlib$parentBody() >= 0
            ? KenderClientState.getById(access.koperlib$parentBody()) : null;
        if (grid == null)
            grid = KenderClientState.getByLogical(BlockPos.containing(x, y, z));
        if (grid == null) return new Vec3(x, y, z);
        if (!looksLogical(grid, x, y, z)) {
            if (access.koperlib$parentBody() == grid.id) {
                double[] logical = KenderClientState.worldPointToBoundGrid(grid, x, y, z);
                if (logical != null)
                    access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
            }
            return new Vec3(x, y, z);
        }
        access.koperlib$parentBody(grid.id);
        access.koperlib$logicalAnchor(new Vec3(x, y, z));
        double[] world = KenderClientState.boundGridPointToWorld(grid, x, y, z);
        return world != null ? new Vec3(world[0], world[1], world[2]) : new Vec3(x, y, z);
    }

    static void bindController(Entity entity, KoperCreateContraptionAccess access, BlockPos controller) {
        KenderClientState.KontraRenderData grid = findGrid(controller);
        if (grid == null || access.koperlib$parentBody() == grid.id
                && access.koperlib$logicalAnchor() != null) return;
        double[] logical = KenderClientState.worldPointToBoundGrid(grid,
            entity.getX(), entity.getY(), entity.getZ());
        access.koperlib$parentBody(grid.id);
        if (logical != null)
            access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
    }

    static boolean bindTrack(Entity entity, KoperCreateContraptionAccess access, BlockPos probe) {
        KenderClientState.KontraRenderData grid = findGrid(probe);
        if (grid == null) return false;
        if (access.koperlib$parentBody() == grid.id
                && access.koperlib$logicalAnchor() != null) return true;
        Vec3 current = entity.position();
        access.koperlib$parentBody(grid.id);
        if (looksLogical(grid, current.x, current.y, current.z)) {
            access.koperlib$logicalAnchor(current);
        } else {
            double[] logical = KenderClientState.worldPointToBoundGrid(
                grid, current.x, current.y, current.z);
            if (logical != null)
                access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
        }
        return access.koperlib$logicalAnchor() != null;
    }

    static Vec3 follow(KoperCreateContraptionAccess access) {
        KenderClientState.KontraRenderData grid =
            KenderClientState.getById(access.koperlib$parentBody());
        Vec3 logical = access.koperlib$logicalAnchor();
        if (grid == null || logical == null) return null;
        double[] world = KenderClientState.boundGridPointToWorld(
            grid, logical.x, logical.y, logical.z);
        return world != null ? new Vec3(world[0], world[1], world[2]) : null;
    }

    static float[] parentRotation(KoperCreateContraptionAccess access) {
        KenderClientState.KontraRenderData grid =
            KenderClientState.getById(access.koperlib$parentBody());
        return grid != null ? KenderClientState.renderRot(grid, System.nanoTime()) : null;
    }

    static Vec3 logicalPointToWorld(KoperCreateContraptionAccess access, Vec3 logical) {
        KenderClientState.KontraRenderData grid =
            KenderClientState.getById(access.koperlib$parentBody());
        if (grid == null) return logical;
        double[] world = KenderClientState.boundGridPointToWorld(
            grid, logical.x, logical.y, logical.z);
        return world == null ? logical : new Vec3(world[0], world[1], world[2]);
    }

    private static KenderClientState.KontraRenderData findGrid(BlockPos pos) {
        KenderClientState.KontraRenderData grid = KenderClientState.getByLogical(pos);
        if (grid != null) return grid;
        for (var direction : net.minecraft.core.Direction.values()) {
            grid = KenderClientState.getByLogical(pos.relative(direction));
            if (grid != null) return grid;
        }
        return null;
    }

    private static boolean looksLogical(KenderClientState.KontraRenderData grid,
            double x, double y, double z) {
        return Math.abs(x - grid.gridAnchor.getX()) < 500
            && Math.abs(y - grid.gridAnchor.getY()) < 2048
            && Math.abs(z - grid.gridAnchor.getZ()) < 500;
    }
}
