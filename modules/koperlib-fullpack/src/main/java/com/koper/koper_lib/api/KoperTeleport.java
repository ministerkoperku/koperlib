package com.koper.koper_lib.api;

import com.koper.koper_lib.KoperLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Moves an entity to a position, in its own or another dimension, keeping its velocity and facing. */
public final class KoperTeleport {
    private KoperTeleport() {}

    /** The loaded level for a dimension id such as {@code "mypack:moon"}, or null. */
    public static ServerLevel level(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /**
     * Teleports and returns the entity as it exists afterwards (a dimension change makes a new instance
     * for anything that is not a player), or null when it did not happen. A null dimension means the
     * entity's own. An unknown dimension is logged as an error.
     */
    public static Entity to(Entity entity, String dimension, Vec3 pos, Vec3 velocity) {
        if (!(entity.level() instanceof ServerLevel current)) return null;
        ServerLevel target = dimension == null ? current : level(current.getServer(), dimension);
        if (target == null) {
            KoperLib.LOGGER.error("[KoperTeleport] teleport does not work: dimension {} does not exist", dimension);
            return null;
        }
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) return null;
        return entity.teleport(new TeleportTransition(target, pos, velocity == null ? entity.getDeltaMovement() : velocity,
            entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
    }

    public static Entity to(Entity entity, String dimension, double x, double y, double z) {
        return to(entity, dimension, new Vec3(x, y, z), null);
    }
}
