package com.koper.koper_lib.physics.terrain;

import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

// feeds the Rust section terrain: polls what physics wants, reads 16^3 occupancy off the chunk,
// ships it as a 512-byte bitset. budgeted per tick so a monster kontra can't stall the server.
// replaces the old O(r^3) scan bubble — no radius cap, only touched sections ever re-mesh.
public final class TerrainSlurper {

    private TerrainSlurper() {}

    private static final int WANT_BUF_SECTIONS = 768;
    private static final int UPLOAD_BUDGET_PER_TICK = 32;
    private static final Map<ServerLevel, Map<Long, Integer>> COLLISION_SUPPRESSIONS =
        Collections.synchronizedMap(new WeakHashMap<>());

    // A fixed joint can live inside a thin world block. The section mesh only knows occupied
    // cells, so addons can temporarily remove that one cell from Rapier terrain while keeping
    // the real Minecraft block in place as the visible mount.
    public static void suppressBlockCollision(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> levelSuppressions = COLLISION_SUPPRESSIONS.computeIfAbsent(
            level, ignored -> new ConcurrentHashMap<>());
        int count = levelSuppressions.merge(pos.asLong(), 1, Integer::sum);
        if (count != 1) return;
        long wh = KoperPhys.worldHandleIfKontras(level);
        if (wh > 0)
            KoperPhysBridge.setTerrainBlock(wh, pos.getX(), pos.getY(), pos.getZ(), false);
    }

    public static void restoreBlockCollision(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> levelSuppressions = COLLISION_SUPPRESSIONS.get(level);
        if (levelSuppressions == null) return;
        long key = pos.asLong();
        Integer count = levelSuppressions.get(key);
        if (count == null) return;
        if (count > 1) {
            levelSuppressions.put(key, count - 1);
            return;
        }
        levelSuppressions.remove(key);
        if (levelSuppressions.isEmpty()) COLLISION_SUPPRESSIONS.remove(level);
        long wh = KoperPhys.worldHandleIfKontras(level);
        if (wh > 0) {
            // the REAL block. level.getBlockState serves a kontra's own block in an air cell it sits
            // over, so ending a suppression right after the wand (the bearing just left the world,
            // the new body sits exactly there) wrote that body's bearing into the terrain as solid:
            // its wheel, which lives in that cell by design, was stuck in stone and the car never moved
            BlockState state = KoperPhys.realBlockState(level, pos);
            KoperPhysBridge.setTerrainBlock(
                wh, pos.getX(), pos.getY(), pos.getZ(), isSolid(level, pos, state));
        }
    }

    public static boolean isBlockCollisionSuppressed(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> levelSuppressions = COLLISION_SUPPRESSIONS.get(level);
        return levelSuppressions != null && levelSuppressions.containsKey(pos.asLong());
    }

    // once per server tick per physics world that has live kontras
    public static void pump(ServerLevel level, long worldHandle) {
        int[] buf = new int[WANT_BUF_SECTIONS * 3];
        int count = KoperPhysBridge.wantedSections(worldHandle, buf, WANT_BUF_SECTIONS);
        if (count <= 0) return;
        KoperPhys.TERRAIN_SCAN_ACTIVE.set(true);
        try {
            int uploaded = 0;
            for (int i = 0; i < count && uploaded < UPLOAD_BUDGET_PER_TICK; i++) {
                if (uploadSection(level, worldHandle, buf[i * 3], buf[i * 3 + 1], buf[i * 3 + 2])) uploaded++;
            }
        } finally {
            KoperPhys.TERRAIN_SCAN_ACTIVE.set(false);
        }
    }

    // synchronous, unbudgeted upload of every section overlapping the given block box (+pad below).
    // called right before a spawn cmd so the body NEVER steps without its floor — same cmd queue,
    // uploads land first.
    public static void primeArea(ServerLevel level, long worldHandle,
                                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int pad = 4;
        int sx0 = (minX - pad) >> 4, sx1 = (maxX + pad) >> 4;
        int sy0 = (minY - pad - 16) >> 4, sy1 = (maxY + pad) >> 4; // extra section straight down
        int sz0 = (minZ - pad) >> 4, sz1 = (maxZ + pad) >> 4;
        KoperPhys.TERRAIN_SCAN_ACTIVE.set(true);
        try {
            for (int sx = sx0; sx <= sx1; sx++)
                for (int sy = sy0; sy <= sy1; sy++)
                    for (int sz = sz0; sz <= sz1; sz++)
                        uploadSection(level, worldHandle, sx, sy, sz);
        } finally {
            KoperPhys.TERRAIN_SCAN_ACTIVE.set(false);
        }
    }

    // relay a single block flip from the block-change mixin — Rust ignores uncached sections
    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState state) {
        long wh = KoperPhys.worldHandleIfKontras(level);
        if (wh <= 0) return;
        KoperPhysBridge.setTerrainBlock(wh, pos.getX(), pos.getY(), pos.getZ(), isSolid(level, pos, state));
        KoperPhysBridge.setFluidBlock(wh, pos.getX(), pos.getY(), pos.getZ(), isWet(state));
    }

    private static boolean uploadSection(ServerLevel level, long wh, int sx, int sy, int sz) {
        int minY = level.getMinY(), maxY = level.getMaxY();
        int baseY = sy << 4;
        if (baseY + 15 < minY || baseY > maxY) {
            // out of the build range → forever air. upload zeros so Rust stops asking
            KoperPhysBridge.uploadSection(wh, sx, sy, sz, new long[64]);
            return true;
        }
        if (!level.hasChunk(sx, sz)) return false; // unloaded — physics will keep wanting it, fine

        LevelChunk chunk = level.getChunk(sx, sz);
        long[] bits = new long[64];
        long[] wet  = new long[64]; // water occupancy rides along — buoyancy reads these
        boolean anyWet = false;
        var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
        if (!section.hasOnlyAir()) {
            var pos = new BlockPos.MutableBlockPos();
            int bx = sx << 4, bz = sz << 4;
            for (int ly = 0; ly < 16; ly++) {
                int wy = baseY + ly;
                if (wy < minY || wy > maxY) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        pos.set(bx + lx, wy, bz + lz);
                        BlockState bs = chunk.getBlockState(pos);
                        int idx = (ly * 16 + lz) * 16 + lx;
                        if (isWet(bs)) { wet[idx >> 6] |= 1L << (idx & 63); anyWet = true; }
                        if (!isSolid(level, pos, bs)) continue;
                        bits[idx >> 6] |= 1L << (idx & 63);
                    }
                }
            }
        }
        // solids FIRST, fluids right behind — Rust drops fluid bits for sections it doesn't know yet.
        // always send the fluid set (even empty): a re-upload must clear stale water, Rust keeps
        // the old bits otherwise
        KoperPhysBridge.uploadSection(wh, sx, sy, sz, bits);
        KoperPhysBridge.uploadSectionFluids(wh, sx, sy, sz, wet);
        if (anyWet && com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Slurp] wet section {},{},{}", sx, sy, sz);
        return true;
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos, BlockState bs) {
        return !isBlockCollisionSuppressed(level, pos)
            && !bs.isAir()
            && !bs.getFluidState().isSource()
            && !bs.getCollisionShape(level, pos).isEmpty();
    }

    // flowing counts too — a boat in a river edge cell should still get pushed up a bit
    private static boolean isWet(BlockState bs) {
        return !bs.getFluidState().isEmpty();
    }
}
