package com.koper.koper_lib.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

// when a block joins a kontraption its world copy goes away, but flywheel was never told, so its
// visual keeps living and spinning at the old spot. our grid draws the block too, and you end up
// looking at a static cog inside a turning one.
//
// blocking canVisualize does not help: the visual already exists by then. it has to be evicted.
// reflection because create is optional and this must not exist as a hard link when it is absent.
public final class KenderFlywheelEvict {

    private KenderFlywheelEvict() {}

    private static final boolean CREATE =
        net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("create");

    private static java.lang.reflect.Method GET, BLOCK_ENTITIES, QUEUE_REMOVE;
    private static boolean resolved, broken;

    private static boolean resolve() {
        if (resolved) return !broken;
        resolved = true;
        try {
            Class<?> mgr = Class.forName(
                "com.zurrtum.create.client.flywheel.impl.visualization.VisualizationManagerImpl");
            GET = mgr.getMethod("get", net.minecraft.world.level.LevelAccessor.class);
            BLOCK_ENTITIES = mgr.getMethod("blockEntities");
            Class<?> vm = Class.forName(
                "com.zurrtum.create.client.flywheel.api.visualization.VisualManager");
            QUEUE_REMOVE = vm.getMethod("queueRemove", Object.class);
        } catch (Throwable t) {
            broken = true;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] cannot reach flywheel visuals ({}), "
                + "kontraption block entities may draw twice", t.getClass().getSimpleName());
        }
        return !broken;
    }

    // this runs off the transform refresh, so effectively every frame. evicting the same visual
    // over and over would be pure waste; remember what we already told flywheel to drop
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet DONE =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    public static void forget() { DONE.clear(); }

    // what the chunk actually holds, overlay off
    private static BlockEntity realBlockEntity(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
        try { return level.getBlockEntity(pos); }
        finally { com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(false); }
    }

    // drop whatever flywheel is still drawing at these world positions
    public static void evictAt(java.util.Collection<BlockPos> worldPositions) {
        if (!CREATE || worldPositions.isEmpty() || !resolve()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;

        try {
            // fed a fresh trail of world cells every frame — without a cap this set eats memory forever
            if (DONE.size() > 8192) DONE.clear();

            Object manager = null, beManager = null;
            int gone = 0;
            for (BlockPos p : worldPositions) {
                if (!DONE.add(p.asLong())) continue;      // already handled this spot
                // a REAL world block entity the ship just happens to fly over is NOT ours to kill.
                // level.getBlockEntity goes through the kontra overlay and hands back the world's own
                // copy whenever one exists, so we were queueRemove-ing the flywheel visual of every
                // burner/cog/shaft under the flight path — once, permanently, at debug log level.
                // that is the blaze burner that lost its head everywhere except on a kontraption.
                if (realBlockEntity(p) != null) continue;
                BlockEntity be = level.getBlockEntity(p); // overlay: the kontra's own copy
                if (be == null) continue;

                if (beManager == null) {
                    manager = GET.invoke(null, level);
                    if (manager == null) return;
                    beManager = BLOCK_ENTITIES.invoke(manager);
                    if (beManager == null) return;
                }
                QUEUE_REMOVE.invoke(beManager, be);
                gone++;
            }
            if (gone > 0)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[Kender/Create] evicted {} stale flywheel visuals", gone);
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kender/Create] visual eviction failed: {}", t.getMessage());
        }
    }
}
