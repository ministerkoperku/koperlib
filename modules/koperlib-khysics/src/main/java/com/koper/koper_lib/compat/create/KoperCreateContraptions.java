package com.koper.koper_lib.compat.create;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.koper.koper_lib.physics.KoperPhysicsEvents;
import com.zurrtum.create.content.trains.entity.Carriage;
import com.zurrtum.create.content.trains.entity.TravellingPoint;
import com.zurrtum.create.content.contraptions.StructureTransform;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Create owns the child animation. Koper owns the frame that animation happens inside.
public final class KoperCreateContraptions {
    private static final Map<Long, Long> BODY_REMAPS = new ConcurrentHashMap<>();
    private static boolean initialized;

    private KoperCreateContraptions() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        KoperPhysicsEvents.ON_RESTORE.add(KoperCreateContraptions::rememberRemap);
        KoperPhysicsEvents.ON_TRANSFER.add(KoperCreateContraptions::transferBoundEntities);
    }

    public static Vec3 mapSetPos(Entity entity, double x, double y, double z) {
        if (!(entity instanceof KoperCreateContraptionAccess access)) return new Vec3(x, y, z);
        if (entity.level() instanceof ServerLevel level) {
            resolveRemap(access);
            return mapServer(entity, access, level, x, y, z);
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            return KoperCreateContraptionsClient.mapSetPos(entity, access, x, y, z);
        return new Vec3(x, y, z);
    }

    public static void bindController(Entity entity, BlockPos controller) {
        if (!(entity instanceof KoperCreateContraptionAccess access)) return;
        if (entity.level() instanceof ServerLevel level) {
            KontraGrid grid = KoperPhys.gridAtLogical(level, controller);
            if (grid != null) bindFromCurrentWorld(entity, access, grid);
            return;
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            KoperCreateContraptionsClient.bindController(entity, access, controller);
    }

    public static void bindTrain(Entity entity, Carriage carriage) {
        if (!(entity instanceof KoperCreateContraptionAccess access) || carriage == null) return;
        if (entity.level() instanceof ServerLevel level) {
            resolveRemap(access);
            KontraGrid grid = trainGrid(level, carriage);
            if (grid != null) bindFromTrack(entity, access, grid);
            return;
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            for (BlockPos probe : trainProbes(entity, carriage)) {
                if (KoperCreateContraptionsClient.bindTrack(entity, access, probe)) return;
            }
        }
    }

    public static Vec3 follow(Entity entity) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || access.koperlib$logicalAnchor() == null) return null;
        if (entity.level() instanceof ServerLevel) {
            resolveRemap(access);
            var entry = KoperPhys.all().get(access.koperlib$parentBody());
            if (entry == null) return null;
            KontraGrid grid = entry.grid(access.koperlib$parentBody());
            Vec3 logical = access.koperlib$logicalAnchor();
            float[] world = KoperPhys.boundGridPointToWorld(grid, logical.x, logical.y, logical.z);
            return world != null ? new Vec3(world[0], world[1], world[2]) : null;
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            return KoperCreateContraptionsClient.follow(access);
        return null;
    }

    public static Vec3 rotateToWorld(Entity entity, Vec3 vector) {
        float[] rotation = parentRotation(entity);
        if (rotation == null) return vector;
        float[] world = KoperPhys.localToWorld((float)vector.x, (float)vector.y, (float)vector.z,
            new float[]{0f, 0f, 0f}, rotation);
        return new Vec3(world[0], world[1], world[2]);
    }

    public static Vec3 rotateToLocal(Entity entity, Vec3 vector) {
        float[] q = parentRotation(entity);
        if (q == null) return vector;
        float iqx=-q[0], iqy=-q[1], iqz=-q[2];
        float x=(float)vector.x, y=(float)vector.y, z=(float)vector.z;
        float tx=2*(iqy*z-iqz*y), ty=2*(iqz*x-iqx*z), tz=2*(iqx*y-iqy*x);
        return new Vec3(x+q[3]*tx+iqy*tz-iqz*ty,
            y+q[3]*ty+iqz*tx-iqx*tz, z+q[3]*tz+iqx*ty-iqy*tx);
    }

    public static float[] parentRotation(Entity entity) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || access.koperlib$parentBody() < 0) return null;
        if (entity.level() instanceof ServerLevel) {
            resolveRemap(access);
            return KoperPhys.cachedRot(access.koperlib$parentBody());
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            return KoperCreateContraptionsClient.parentRotation(access);
        return null;
    }

    public static Vec3 logicalPointToWorld(Entity entity, Vec3 logical) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || logical == null || access.koperlib$parentBody() < 0) return logical;
        if (entity.level() instanceof ServerLevel) {
            resolveRemap(access);
            var entry = KoperPhys.all().get(access.koperlib$parentBody());
            if (entry == null) return logical;
            float[] world = KoperPhys.boundGridPointToWorld(
                entry.grid(access.koperlib$parentBody()), logical.x, logical.y, logical.z);
            return world == null ? logical : new Vec3(world[0], world[1], world[2]);
        }
        if (entity.level().isClientSide()
                && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            return KoperCreateContraptionsClient.logicalPointToWorld(access, logical);
        return logical;
    }

    public static void inParentGrid(Entity entity, Runnable action) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || !(entity.level() instanceof ServerLevel)) {
            action.run();
            return;
        }
        resolveRemap(access);
        var entry = KoperPhys.all().get(access.koperlib$parentBody());
        if (entry == null) {
            action.run();
            return;
        }
        KontraGridContext.run(entry.grid(access.koperlib$parentBody()), action);
    }

    public static boolean hasLiveParent(Entity entity) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || !(entity.level() instanceof ServerLevel)) return false;
        resolveRemap(access);
        return KoperPhys.all().containsKey(access.koperlib$parentBody());
    }

    public static StructureTransform localizeTransform(Entity entity, StructureTransform transform) {
        if (!(entity instanceof KoperCreateContraptionAccess access)
                || access.koperlib$logicalAnchor() == null || !hasLiveParent(entity)) return transform;
        Vec3 anchor = access.koperlib$logicalAnchor();
        transform.offset = BlockPos.containing(anchor.add(0.5, 0.5, 0.5));
        return transform;
    }

    private static Vec3 mapServer(Entity entity, KoperCreateContraptionAccess access,
            ServerLevel level, double x, double y, double z) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null && access.koperlib$parentBody() >= 0) {
            var entry = KoperPhys.all().get(access.koperlib$parentBody());
            if (entry != null) grid = entry.grid(access.koperlib$parentBody());
        }
        if (grid == null) grid = KoperPhys.gridAtLogical(level, BlockPos.containing(x, y, z));
        if (grid == null) return new Vec3(x, y, z);
        if (!looksLogical(grid, x, y, z)) {
            if (access.koperlib$parentBody() == grid.kontraId()) {
                float[] logical = KoperPhys.worldPointToBoundGrid(grid, x, y, z);
                if (logical != null)
                    access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
            }
            return new Vec3(x, y, z);
        }

        access.koperlib$parentBody(grid.kontraId());
        access.koperlib$logicalAnchor(new Vec3(x, y, z));
        float[] world = KoperPhys.boundGridPointToWorld(grid, x, y, z);
        return world != null ? new Vec3(world[0], world[1], world[2]) : new Vec3(x, y, z);
    }

    private static void bindFromCurrentWorld(Entity entity, KoperCreateContraptionAccess access,
            KontraGrid grid) {
        if (access.koperlib$parentBody() == grid.kontraId()
                && access.koperlib$logicalAnchor() != null) return;
        float[] logical = KoperPhys.worldPointToBoundGrid(grid,
            entity.getX(), entity.getY(), entity.getZ());
        access.koperlib$parentBody(grid.kontraId());
        if (logical != null)
            access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
    }

    private static void bindFromTrack(Entity entity, KoperCreateContraptionAccess access,
            KontraGrid grid) {
        if (access.koperlib$parentBody() == grid.kontraId()
                && access.koperlib$logicalAnchor() != null) return;
        Vec3 current = entity.position();
        access.koperlib$parentBody(grid.kontraId());
        if (looksLogical(grid, current.x, current.y, current.z)) {
            access.koperlib$logicalAnchor(current);
        } else {
            float[] logical = KoperPhys.worldPointToBoundGrid(
                grid, current.x, current.y, current.z);
            if (logical != null)
                access.koperlib$logicalAnchor(new Vec3(logical[0], logical[1], logical[2]));
        }
    }

    private static KontraGrid trainGrid(ServerLevel level, Carriage carriage) {
        for (BlockPos probe : trainProbes(level, carriage)) {
            KontraGrid grid = KoperPhys.gridAtLogical(level, probe);
            if (grid != null) return grid;
        }
        return null;
    }

    private static Iterable<BlockPos> trainProbes(Entity entity, Carriage carriage) {
        return trainProbes(entity.level(), carriage);
    }

    private static Iterable<BlockPos> trainProbes(net.minecraft.world.level.Level level,
            Carriage carriage) {
        ArrayList<BlockPos> probes = new ArrayList<>(8);
        addTrainProbes(probes, carriage.getLeadingPoint(), level);
        addTrainProbes(probes, carriage.getTrailingPoint(), level);
        return probes;
    }

    private static void addTrainProbes(ArrayList<BlockPos> probes, TravellingPoint point,
            net.minecraft.world.level.Level level) {
        if (point == null) return;
        if (point.node1 != null && point.node1.getLocation().getDimension() == level.dimension())
            probes.add(BlockPos.containing(point.node1.getLocation().getLocation()));
        if (point.node2 != null && point.node2.getLocation().getDimension() == level.dimension())
            probes.add(BlockPos.containing(point.node2.getLocation().getLocation()));
    }

    private static void rememberRemap(long oldBody, long newBody) {
        if (oldBody > 0 && newBody > 0 && oldBody != newBody)
            BODY_REMAPS.put(oldBody, newBody);
    }

    private static void transferBoundEntities(Map<Long, Long> bodies,
            ServerLevel source, ServerLevel target) {
        bodies.forEach(KoperCreateContraptions::rememberRemap);
        ArrayList<Entity> entities = new ArrayList<>();
        for (Entity entity : source.getAllEntities()) entities.add(entity);
        for (Entity entity : entities) {
            if (!(entity instanceof KoperCreateContraptionAccess access)) continue;
            Long newBody = bodies.get(access.koperlib$parentBody());
            if (newBody == null) continue;
            remapAccess(access, access.koperlib$parentBody(), newBody);
            Vec3 destination = follow(entity);
            if (destination == null) continue;
            if (source == target) {
                entity.setPos(destination);
                continue;
            }
            entity.teleport(new TeleportTransition(target, destination,
                entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(),
                TeleportTransition.DO_NOTHING));
        }
    }

    private static void resolveRemap(KoperCreateContraptionAccess access) {
        long body = access.koperlib$parentBody();
        for (int depth = 0; depth < 16; depth++) {
            Long next = BODY_REMAPS.get(body);
            if (next == null || next == body) return;
            remapAccess(access, body, next);
            body = next;
        }
    }

    private static void remapAccess(KoperCreateContraptionAccess access, long oldBody, long newBody) {
        Vec3 anchor = access.koperlib$logicalAnchor();
        if (anchor != null) {
            BlockPos oldGrid = gridAnchor(oldBody);
            BlockPos newGrid = gridAnchor(newBody);
            access.koperlib$logicalAnchor(anchor.add(
                newGrid.getX() - oldGrid.getX(),
                newGrid.getY() - oldGrid.getY(),
                newGrid.getZ() - oldGrid.getZ()));
        }
        access.koperlib$parentBody(newBody);
    }

    private static BlockPos gridAnchor(long body) {
        long slot = Math.floorMod(body, 1_600_000_000L);
        int sx = (int)(slot % 40_000L);
        int sz = (int)(slot / 40_000L);
        return new BlockPos(-20_000_000 + sx * 1000, 0, -20_000_000 + sz * 1000);
    }

    private static boolean looksLogical(KontraGrid grid, double x, double y, double z) {
        BlockPos anchor = grid.anchor();
        return Math.abs(x - anchor.getX()) < 500
            && Math.abs(y - anchor.getY()) < 2048
            && Math.abs(z - anchor.getZ()) < 500;
    }
}
