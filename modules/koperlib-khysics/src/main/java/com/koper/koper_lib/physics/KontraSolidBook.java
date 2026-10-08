package com.koper.koper_lib.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// answers "is an ALIGNED kontra pretending to be a real block in this world cell?" — feeds the
// BlockCollisions redirect, so a parked kontra IS solid ground for every entity, item, mob and mod
// that walks the vanilla collision path. moving/rotated kontras stay with the SAT pushout instead.
public final class KontraSolidBook {

    private KontraSolidBook() {}

    public interface ClientLookup {
        BlockState at(BlockPos pos);
        /** boxes for that cell with local data, null = use the state shape */
        default java.util.List<net.minecraft.world.phys.AABB> shapeAt(BlockPos pos) { return null; }
    }

    // client plugs its kender-state lookup in here at init — server never touches client classes
    public static volatile ClientLookup CLIENT = null;

    public static BlockState at(CollisionGetter getter, BlockPos pos) {
        if (getter instanceof ServerLevel sl) return KoperPhys.alignedSolidAt(sl, pos);
        if (getter instanceof Level lvl && lvl.isClientSide()) {
            ClientLookup c = CLIENT;
            return c != null ? c.at(pos) : null;
        }
        // PathNavigationRegion and friends — mobs learn to pathfind across kontras later
        return null;
    }

    /** boxes for a parked kontra cell, local-data geometry included */
    public static java.util.List<net.minecraft.world.phys.AABB> shapeAt(
            CollisionGetter getter, BlockPos pos) {
        if (getter instanceof ServerLevel sl) return KoperPhys.alignedShapeAt(sl, pos);
        if (getter instanceof Level lvl && lvl.isClientSide()) {
            ClientLookup c = CLIENT;
            return c != null ? c.shapeAt(pos) : null;
        }
        return null;
    }
}
