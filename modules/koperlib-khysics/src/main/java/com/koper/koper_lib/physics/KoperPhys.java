package com.koper.koper_lib.physics;

import com.koper.koper_lib.physics.weight.KhysWeightBook;
import com.koper.koper_lib.network.KenderRemovePayload;
import com.koper.koper_lib.network.KenderSpawnPayload;
import com.koper.koper_lib.network.KenderUpdatePayload;
import com.koper.koper_lib.network.KoperNetworking;
import com.koper.koper_lib.panama.KoperPhysBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// big brain server-side physics manager — owns all live kontraktions
// single source of truth: KONTRAS map + CACHED_POS/ROT from last Rust snapshot
public class KoperPhys {

    // world-local prevent-reentry — set true during makeKontraktion block removal
    public static final ThreadLocal<Boolean> ASSEMBLY_ACTIVE = ThreadLocal.withInitial(() -> false);
    // set true during terrain scan so ServerLevelAccessMixin bypasses physics overlay
    // without this: scan sees physics blocks as terrain → self-collision → levitation/sinking
    public static final ThreadLocal<Boolean> TERRAIN_SCAN_ACTIVE = ThreadLocal.withInitial(() -> false);
    // vanilla Entity.collide() must see real terrain only; kontra collision is injected separately
    public static final ThreadLocal<Boolean> ENTITY_COLLISION_ACTIVE = ThreadLocal.withInitial(() -> false);
    // client render probes like screen overlay must not see targeted phantom physics blocks
    public static final ThreadLocal<Boolean> CLIENT_BLOCK_LOOKUP_BYPASS = ThreadLocal.withInitial(() -> false);
    // set while vanilla Level.setBlock is running. it re-reads the cell straight after writing and
    // compares: newState == state, and only THEN notifies clients. hand it the kontra projection
    // there and the compare fails, markAndNotifyBlock never runs, and the client is never told the
    // block went away — a ghost with collision, which is exactly what koper kept getting
    private static final ThreadLocal<int[]> WORLD_WRITE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    public static void worldWriteIn()  { WORLD_WRITE_DEPTH.get()[0]++; }
    public static void worldWriteOut() { int[] d = WORLD_WRITE_DEPTH.get(); if (d[0] > 0) d[0]--; }
    public static boolean worldWriting() { return WORLD_WRITE_DEPTH.get()[0] > 0; }
    public static final ThreadLocal<Boolean> REAL_WORLD_LOOKUP = ThreadLocal.withInitial(() -> false);
    // set around ServerLevel.tickChunk — world random ticks (grass/crops/fire) must not read the
    // physical projection overlay or grass under every parked kontra rots to dirt
    public static final ThreadLocal<Boolean> WORLD_RANDOM_TICK = ThreadLocal.withInitial(() -> false);
    // player is deliberately building ON the grid — unowned cells read as air and writes join the grid,
    // never the world. without this, static clone litter next to the hull poisons canBeReplaced → Fail
    public static final ThreadLocal<Boolean> GRID_PLACEMENT = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LOGIC_REFRESH_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<java.util.ArrayDeque<Long>> DEFERRED_LOGIC_BOOTSTRAPS =
        ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<Boolean> LOGIC_DRAIN_ACTIVE = ThreadLocal.withInitial(() -> false);
    // tracks previous chunk-load state per kontraktion — only send SetSleepAllowed when it changes
    // sending every 20 ticks resets Rapier activation struct → disrupts settling
    private static final Map<Long, Boolean> KONTRA_SLEEP_STATE = new ConcurrentHashMap<>();

    // wing blocks — high lift, low drag. wool/leaves/glass/scaffolding etc.
    static final TagKey<Block> LIGHT_BLOCKS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("koperlib", "light"));
    // hold an item in this tag (or the physics wand) while standing on a ship → you steer it
    static final TagKey<net.minecraft.world.item.Item> HELM = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("koperlib", "helm"));

    private static final Map<String, Long>          WORLD_HANDLES   = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, String>   LEVEL_KEYS      = new ConcurrentHashMap<>();
    private static final Map<Long, KontraEntry>     KONTRAS         = new ConcurrentHashMap<>();
    private static final Map<Long, float[]>         CACHED_POS      = new ConcurrentHashMap<>();
    private static final Map<Long, float[]>         CACHED_ROT      = new ConcurrentHashMap<>();
    private static final Map<Object, ServerLevel>   LEVEL_TICKS     = Collections.synchronizedMap(new WeakHashMap<>());
    private record GridCell(long kontraId, BlockPos local) {}
    private static final Map<String, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<GridCell>> LOGICAL_CELL_INDEX = new ConcurrentHashMap<>();
    private static final Map<String, PhysicalIndex> PHYSICAL_CELL_INDEX = new ConcurrentHashMap<>();
    static final class PhysicalIndex {
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap primary = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList> overlaps =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        PhysicalIndex() { primary.defaultReturnValue(0L); }
        boolean contains(long cell) { return primary.get(cell) != 0L; }
        // authoritative: forget this kontra everywhere. removing by a recomputed cell set only
        // works if the pose never moved since it was indexed, and a kontra that moved and then
        // got destroyed (which is exactly what the lift does when it picks something up) left
        // its old cells in here until the next restart
        void removeAll(long kontraId) {
            var dead = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (var e : primary.long2LongEntrySet())
                if (e.getLongValue() == kontraId) dead.add(e.getLongKey());
            for (int i = 0; i < dead.size(); i++) remove(dead.getLong(i), kontraId);
            dead.clear();
            for (var e : overlaps.long2ObjectEntrySet())
                if (e.getValue().contains(kontraId)) dead.add(e.getLongKey());
            for (int i = 0; i < dead.size(); i++) remove(dead.getLong(i), kontraId);
        }
        boolean isEmpty() { return primary.isEmpty(); }
        void add(long cell, long kontraId) {
            long first=primary.get(cell);
            if (first == 0L) { primary.put(cell,kontraId); return; }
            if (first == kontraId) return;
            var more=overlaps.computeIfAbsent(cell, ignored -> new it.unimi.dsi.fastutil.longs.LongArrayList(2));
            if (!more.contains(kontraId)) more.add(kontraId);
        }
        void remove(long cell, long kontraId) {
            long first=primary.get(cell);
            if (first == kontraId) {
                var more=overlaps.get(cell);
                if (more == null || more.isEmpty()) {
                    primary.remove(cell);
                    overlaps.remove(cell);
                } else {
                    primary.put(cell,more.removeLong(more.size()-1));
                    if (more.isEmpty()) overlaps.remove(cell);
                }
                return;
            }
            var more=overlaps.get(cell);
            if (more == null) return;
            more.rem(kontraId);
            if (more.isEmpty()) overlaps.remove(cell);
        }
    }
    private static final Map<Long, Long>            SELF_RIGHT_TICK = new ConcurrentHashMap<>();
    private static final int                        SELF_RIGHT_COOLDOWN = 60; // ticks (3s at 20Hz)
    // NOT a speed limit (koper: kontra moze zapierdalac ile chce) — only a teleport/NaN net
    private static final double                     KONTRA_EMERGENCY_DELTA_BT = 50.0;
    // world cells just removed via the kontra break path → ServerBreakMixin uses this so the vanilla
    // STOP_DESTROY completion can't double-break the same/neighbour cell or chew a real block there
    private static final Map<Long, Long>            RECENT_KONTRA_BREAKS = new ConcurrentHashMap<>();
    // debug only — one-shot settle log per kontra to tell physics-rest-Y apart from render offset (half-under-map)
    private static final Set<Long>                  DIAG_SETTLED = ConcurrentHashMap.newKeySet();
    private static final Set<String>                BE_TICK_ERRORS = ConcurrentHashMap.newKeySet();

    private KoperPhys() {}

    // create kinetics: Network/Source are coords from whatever space the BE was serialized in — garbage
    // after any world<->grid move (and in old saves). stale net = machine frozen until something pokes it
    // with an update. strip and the BE re-initializes like freshly placed (the path that provably works).
    public static void stripKineticNbt(net.minecraft.nbt.CompoundTag tag) {
        if (!tag.getStringOr("id", "").startsWith("create:")) return;
        for (String k : new String[]{"Network", "Source", "Speed", "Stress", "Capacity",
                "AddedStress", "AddedCapacity", "NeedsSpeedUpdate", "Sequence"})
            tag.remove(k);
    }

    private static BlockEntity relocateBlockEntity(BlockEntity oldBe, BlockPos newPos, BlockState state, ServerLevel level) {
        if (oldBe == null) return null;
        // a be left behind by a block that since changed: loading it onto the new state only makes vanilla throw
        if (!oldBe.getType().isValid(state)) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] dropped a stale {} be, the cell now holds {}",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(oldBe.getType()), state);
            return null;
        }
        try {
            var tag = oldBe.saveWithFullMetadata(level.registryAccess());
            stripKineticNbt(tag);
            BlockEntity moved = BlockEntity.loadStatic(newPos, state, tag, level.registryAccess());
            if (moved != null) moved.setLevel(level);
            else com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] couldn't relocate BE {} to {}", oldBe.getType(), newPos);
            return moved;
        } catch (Throwable ex) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KoperPhys] BE relocate failed for {} to {}", oldBe.getType(), newPos, ex);
            return null;
        }
    }

    // set once at SERVER_STARTED — included in levelKey to isolate different world saves
    private static volatile String currentWorldSave = "default";

    public static void setWorldSave(String name) { currentWorldSave = name; }

    public static void bindLevelTicks(Object ticks, ServerLevel level) {
        if (ticks != null) LEVEL_TICKS.put(ticks, level);
    }

    public static ServerLevel levelForTicks(Object ticks) {
        return LEVEL_TICKS.get(ticks);
    }

    // ── world handle ──────────────────────────────────────────────────────────

    public static long getWorldHandle(ServerLevel level) {
        return WORLD_HANDLES.computeIfAbsent(levelKey(level), k -> {
            long wh = KoperPhysBridge.createWorld();
            if (wh <= 0) com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KoperPhys] createWorld() returned {} — Rust DLL missing koper_khysics_* exports! Run: cd engine && cargo build --release", wh);
            else         com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys] physics world {} created for {}", wh, k);
            return wh;
        });
    }

    // includes world save name so World A's "overworld" ≠ World B's "overworld"
    public static String levelKey(ServerLevel level) {
        return LEVEL_KEYS.computeIfAbsent(level,
            l -> currentWorldSave + "/" + l.dimension().identifier().toString());
    }

    public static ServerLevel levelFor(KontraEntry entry) {
        if (entry == null) return null;
        for (var level : LEVEL_KEYS.entrySet())
            if (level.getValue().equals(entry.levelKey())) return level.getKey();
        return null;
    }

    // ── queries ───────────────────────────────────────────────────────────────

    public static Map<Long, KontraEntry> all() { return Collections.unmodifiableMap(KONTRAS); }

    public static float[] getCachedPos(long id) { return CACHED_POS.get(id); }
    public static float[] getCachedRot(long id) { return CACHED_ROT.get(id); }

    public static void suppressTerrainCollision(ServerLevel level, BlockPos pos) {
        com.koper.koper_lib.physics.terrain.TerrainSlurper.suppressBlockCollision(level, pos);
    }

    public static void restoreTerrainCollision(ServerLevel level, BlockPos pos) {
        com.koper.koper_lib.physics.terrain.TerrainSlurper.restoreBlockCollision(level, pos);
    }

    public static boolean isTerrainCollisionSuppressed(ServerLevel level, BlockPos pos) {
        return com.koper.koper_lib.physics.terrain.TerrainSlurper
            .isBlockCollisionSuppressed(level, pos);
    }

    public static boolean translateKontraktion(long id, float dx, float dy, float dz) {
        KontraEntry data = KONTRAS.get(id);
        float[] pos = CACHED_POS.get(id);
        float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f, 0f, 0f, 1f});
        if (data == null || pos == null) return false;
        float[] moved = new float[]{pos[0] + dx, pos[1] + dy, pos[2] + dz};
        KoperPhysBridge.setTransform(data.worldHandle(), id,
            moved[0], moved[1], moved[2], rot[0], rot[1], rot[2], rot[3]);
        CACHED_POS.put(id, moved);
        clearSaveTransform(id);
        return true;
    }

    public static boolean setKontraktionTransform(long id,
                                                  float px, float py, float pz,
                                                  float qx, float qy, float qz, float qw) {
        if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)
                || !Float.isFinite(qx) || !Float.isFinite(qy)
                || !Float.isFinite(qz) || !Float.isFinite(qw)) return false;
        KontraEntry data = KONTRAS.get(id);
        if (data == null) return false;
        org.joml.Quaternionf rotation = new org.joml.Quaternionf(qx, qy, qz, qw);
        if (rotation.lengthSquared() < 1.0e-8f) return false;
        rotation.normalize();
        KoperPhysBridge.setTransform(data.worldHandle(), id, px, py, pz,
            rotation.x, rotation.y, rotation.z, rotation.w);
        CACHED_POS.put(id, new float[]{px, py, pz});
        CACHED_ROT.put(id, new float[]{rotation.x, rotation.y, rotation.z, rotation.w});
        clearSaveTransform(id);
        return true;
    }

    /**
     * Moves one connected joint island without letting the solver drag its children behind.
     * The ignored joint is normally the old world bearing currently being replaced.
     */
    public static int rebaseJointAssembly(long rootId, long ignoredJointId,
                                          float px, float py, float pz,
                                          float qx, float qy, float qz, float qw) {
        float[] rootPos = CACHED_POS.get(rootId);
        float[] rootRot = CACHED_ROT.get(rootId);
        if (rootPos == null || rootRot == null || !KONTRAS.containsKey(rootId)) return 0;

        org.joml.Quaternionf target = new org.joml.Quaternionf(qx, qy, qz, qw);
        if (target.lengthSquared() < 1.0e-8f) return 0;
        target.normalize();
        org.joml.Quaternionf oldRoot = new org.joml.Quaternionf(
            rootRot[0], rootRot[1], rootRot[2], rootRot[3]).normalize();
        org.joml.Quaternionf delta = new org.joml.Quaternionf(target)
            .mul(new org.joml.Quaternionf(oldRoot).conjugate());

        java.util.Set<Long> island = new java.util.HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        island.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            long body = queue.removeFirst();
            for (var entry : JOINT_ENDS.entrySet()) {
                if (entry.getKey() == ignoredJointId) continue;
                long[] ends = entry.getValue();
                long other = ends[0] == body ? ends[1] : ends[1] == body ? ends[0] : 0L;
                if (other > 0L && KONTRAS.containsKey(other) && island.add(other)) queue.add(other);
            }
        }

        int moved = 0;
        for (long body : island) {
            float[] oldPos = CACHED_POS.get(body);
            float[] oldRot = CACHED_ROT.get(body);
            if (oldPos == null || oldRot == null) continue;
            org.joml.Vector3f relative = new org.joml.Vector3f(
                oldPos[0] - rootPos[0], oldPos[1] - rootPos[1], oldPos[2] - rootPos[2]);
            delta.transform(relative);
            org.joml.Quaternionf rotation = new org.joml.Quaternionf(delta).mul(
                new org.joml.Quaternionf(oldRot[0], oldRot[1], oldRot[2], oldRot[3])).normalize();
            if (setKontraktionTransform(body,
                    px + relative.x, py + relative.y, pz + relative.z,
                    rotation.x, rotation.y, rotation.z, rotation.w)) moved++;
        }
        return moved;
    }

    // frozen save transform per kontra — persistence idempotency. each reload the body re-settles a hair
    // (Rapier doesn't land bit-identically), and re-saving that drift accumulates → after a few reloads the
    // kontra is visibly off / its world collision goes haywire. so while a kontra stays within ~1 block of
    // its last-saved spot (i.e. it's resting, only drifting), we persist the LAST-SAVED transform, not the
    // drifted live one → restore is deterministic → no accumulation. a kontra that actually moves updates it.
    private static final Map<Long, float[]> SAVE_TRANSFORM = new ConcurrentHashMap<>();
    public static float[] getSaveTransform(long id) {
        float[] pos = CACHED_POS.get(id);
        if (pos == null) return null;
        float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f,0f,0f,1f});
        float[] stable = SAVE_TRANSFORM.get(id);
        if (stable != null) {
            double dx = pos[0]-stable[0], dy = pos[1]-stable[1], dz = pos[2]-stable[2];
            // resting jitter only. this used to keep anything that moved under a block, rotation not even
            // looked at: a spinning rotor or a sagging part got saved up to a block and a turn off and
            // came back with its joint stretched
            double dot = Math.abs(rot[0]*stable[3] + rot[1]*stable[4] + rot[2]*stable[5] + rot[3]*stable[6]);
            if (dx*dx + dy*dy + dz*dz < 0.0025 && dot > 0.99995) return stable;
        }
        float[] live = { pos[0],pos[1],pos[2], rot[0],rot[1],rot[2],rot[3] };
        SAVE_TRANSFORM.put(id, live);
        return live;
    }
    public static void clearSaveTransform(long id) { SAVE_TRANSFORM.remove(id); }
    public static void pinSaveTransform(long id, float[] pos, float[] rot) {
        if (pos == null || rot == null) return;
        SAVE_TRANSFORM.put(id, new float[]{ pos[0],pos[1],pos[2], rot[0],rot[1],rot[2],rot[3] });
    }

    // delta position from last poseLedger() call - 20Hz, used by steering/terrain lead
    public static float[] getKontraVelocity(long id) {
        float[] last = LAST_KONTRA_DELTA.get(id);
        if (last != null) return last.clone();
        float[] pos  = CACHED_POS.get(id);
        float[] prev = PREV_KONTRA_POS.get(id);
        if (pos == null || prev == null) return null;
        return new float[]{ pos[0]-prev[0], pos[1]-prev[1], pos[2]-prev[2] };
    }

    // fresh ride state — the player is being carried, movement validation must chill out.
    // the mind on the entity IS the ride bookkeeping now, no side tables
    public static boolean isRiding(Entity entity) {
        return KontraGlue.tracked(entity);
    }

    // loose "moved wrongly" gate: riding, OR anywhere near ANY kontra. aligned ones used to stay
    // strict, but their detection eps lets the hull sit up to 0.06 off the cells — brushing that
    // wall while jumping read as "colliding with something new" and the server teleported the
    // player right back down. looked exactly like the jump doing nothing.
    public static boolean softMoveValidation(ServerPlayer player) {
        if (player == null) return false;
        if (isRiding(player)) return true;
        if (KONTRAS.isEmpty()) return false;
        String key = levelKey((ServerLevel) player.level());
        for (var e : KONTRAS.entrySet()) {
            KontraEntry d = e.getValue();
            if (!d.levelKey().equals(key)) continue;
            float[] p = CACHED_POS.get(e.getKey());
            if (p == null) continue;
            double dx = player.getX() - p[0], dy = player.getY() - p[1], dz = player.getZ() - p[2];
            double reach = d.cachedRadius + 4.0;
            if (dx*dx + dy*dy + dz*dz <= reach * reach) return true;
        }
        return false;
    }

    // no fall damage while riding, and none when landing on a deck that's moving vertically —
    // only a genuinely parked kontra hurts like normal ground
    public static boolean shouldEatFallDamage(net.minecraft.world.entity.LivingEntity entity) {
        if (isRiding(entity)) return true;
        if (KONTRAS.isEmpty() || !(entity.level() instanceof ServerLevel)) return false;
        KontraRide.Ride probe = KontraRide.serverClamp(entity, new Vec3(0, -0.3, 0));
        if (probe == null || !probe.onGround || probe.kontraId == 0) return false;
        float[] vel = getKontraVelocity(probe.kontraId);
        return vel != null && Math.abs(vel[1]) > 0.02f;
    }

    // null = no physics block here
    public static BlockState getBlockStateAt(ServerLevel level, BlockPos worldPos) {
        if (KONTRAS.isEmpty()) return null;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? nearestPhysicalCell(index, worldPos.asLong(),
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? data.logicCells().get(worldPos.asLong()) : null;
    }

    public record GridContact(KontraGrid grid, BlockPos pos, BlockState state) {}

    public static GridContact projectedBlockContact(ServerLevel level, BlockPos worldPos, BlockState projected) {
        if (KontraGridContext.active() != null || KONTRAS.isEmpty()) return null;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? nearestPhysicalCell(index, worldPos.asLong(),
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5) : null;
        if (cell == null || !realBlockState(level, worldPos).isAir()) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        BlockState state = data != null ? data.blocks.get(cell.local()) : null;
        if (state == null || state.getBlock() != projected.getBlock()) return null;
        KontraGrid grid = data.grid(cell.kontraId());
        return new GridContact(grid, grid.toGrid(cell.local()), state);
    }

    // how much of a world cell a kontra actually fills, 0..1. a microblock can own a cell while
    // taking up one pixel of it, and refusing to let you place there is what makes patching a hole
    // under a machine impossible. koper: "do pixela dwoch huj niech wypchnie lekko"
    public static float kontraFillAt(ServerLevel level, BlockPos worldPos) {
        if (KONTRAS.isEmpty()) return 0f;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null) return 0f;
        long packed = worldPos.asLong();
        GridCell cell = nearestPhysicalCell(index, packed,
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        if (cell == null) return 0f;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        if (data == null || data.logicCells().get(packed) == null) return 0f;

        // a parked kontra can hand over its real boxes, micro geometry and all
        var boxes = alignedShapeAt(level, worldPos);
        if (boxes != null && !boxes.isEmpty()) {
            double filled = 0;
            for (var b : boxes) filled += overlapVolume(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, worldPos);
            return (float) Math.min(1.0, filled);
        }

        // a moving one has no aligned shape — a wheel rolling along is never "aligned" — so work
        // it out from where the block actually is. this is the case that matters: a wheel dipping
        // a couple of pixels into the cell below used to read as a completely occupied cell
        float[] pos = CACHED_POS.get(cell.kontraId());
        float[] rot = CACHED_ROT.get(cell.kontraId());
        float[] off = data.blockOffsets.get(cell.local());
        if (pos == null || rot == null || off == null) return 1f;
        float[] c = localToWorld(off[0], off[1], off[2], pos, rot);
        return (float) Math.min(1.0, overlapVolume(
            c[0] - 0.5, c[1] - 0.5, c[2] - 0.5, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5, worldPos));
    }

    private static double overlapVolume(double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ, BlockPos cell) {
        double x = Math.min(maxX, cell.getX() + 1) - Math.max(minX, cell.getX());
        double y = Math.min(maxY, cell.getY() + 1) - Math.max(minY, cell.getY());
        double z = Math.min(maxZ, cell.getZ() + 1) - Math.max(minZ, cell.getZ());
        return x > 0 && y > 0 && z > 0 ? x * y * z : 0;
    }

    // a couple of pixels of kontra in a cell is not "occupied". the placement goes through and
    // khysics' own terrain collision shoves the machine out of the new solid block, which IS the
    // little nudge up that was wanted — no special push code needed
    public static boolean kontraBarelyThere(ServerLevel level, BlockPos worldPos) {
        // a cell the machine OWNS is its own, however thin the block in it is. a lift plate is a
        // few pixels tall and this used to call it "barely there", which sent clicks meant for the
        // plate off to vanilla and made it impossible to build on a lowered lift
        if (kontraOwnsCell(level, worldPos)) return false;
        float fill = kontraFillAt(level, worldPos);
        return fill > 0f && fill <= 2f / 16f;
    }

    // true when a kontra block is properly IN this cell, not merely overlapping it
    public static boolean kontraOwnsCell(ServerLevel level, BlockPos worldPos) {
        if (KONTRAS.isEmpty()) return false;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null) return false;
        long packed = worldPos.asLong();
        GridCell cell = nearestPhysicalCell(index, packed,
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        if (cell == null) return false;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null && data.logicOwner(packed) != null;
    }

    // a world-context neighborChanged that lands on a kontra's world projection would run the block's
    // survival logic in world space (deck's world-below is air on a rotated ship) → redstone/rails drop
    // an item even though the Level.setBlock guard (ServerLevelBlockChangeMixin) keeps the block. re-run it in grid space instead.
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap REROUTE_TICK_STAMP =
        new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    // world-space setBlock aimed at a kontra projection (plate press, arrow-lit target, wire pop):
    // vanilla would materialize a REAL block there = the static clones. reroute the write into the grid.
    public static Boolean routeProjectionWrite(ServerLevel level, BlockPos worldPos, BlockState state,
                                               int flags, int recursionLeft) {
        if (KONTRAS.isEmpty()) return null;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? nearestPhysicalCell(index, worldPos.asLong(),
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        if (data == null) return null;
        BlockPos local = data.logicOwner(worldPos.asLong());
        if (local == null) return null;                          // not an exact projection cell
        if (!realBlockState(level, worldPos).isAir()) return null; // real world block owns this cell
        // only SAME-block state flips ride this reroute (plate POWERED, target lit). a foreign block
        // (flowing water!) or air landing here would replace/delete the kontra's own block — swallow it.
        // water on the seam did exactly that and then ground the server into 0 tps.
        BlockState owned = data.blocks.get(local);
        if (owned == null || state.getBlock() != owned.getBlock()) {
            // the client may have already predicted this placement — push the real (air) state back
            // or it lingers as a static ghost until reload
            BlockState real = realBlockState(level, worldPos);
            level.sendBlockUpdated(worldPos, real, real, Block.UPDATE_CLIENTS);
            return false;
        }
        float[] rot = CACHED_ROT.get(cell.kontraId());
        BlockState gridState = rot != null ? stateToGrid(state, rot) : state;
        KontraGrid grid = data.grid(cell.kontraId());
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] world-write rerouted worldPos={} -> local={} state={}",
                worldPos, local, state.getBlock());
        return grid.setBlock(level, grid.toGrid(local), gridState, flags, recursionLeft);
    }

    public static boolean redirectProjectionNeighbor(ServerLevel level, BlockPos worldPos, Block sourceBlock) {
        if (KONTRAS.isEmpty()) return false;
        // damp grid↔world ping-pong: one reroute per cell per tick, or a powered target/plate can
        // spin the collector forever (tps hits zero, server thread never exits)
        long stampKey = worldPos.asLong();
        if (REROUTE_TICK_STAMP.getOrDefault(stampKey, Long.MIN_VALUE) == tickCounter) return true;
        REROUTE_TICK_STAMP.put(stampKey, tickCounter);
        if (REROUTE_TICK_STAMP.size() > 4096) REROUTE_TICK_STAMP.clear();
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null) return false;
        GridCell cell = nearestPhysicalCell(index, worldPos.asLong(),
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5);
        if (cell == null) return false;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        if (data == null) return false;
        BlockState state = data.blocks.get(cell.local());
        if (state == null) return false;
        KontraGrid grid = data.grid(cell.kontraId());
        KontraGridContext.run(grid, () ->
            state.handleNeighborChanged(level, grid.toGrid(cell.local()), sourceBlock, null, false));
        return true;
    }

    // vanilla isInWall samples a width*0.8 box around the eye, not one cell. our access mixin makes
    // physics blocks show up in those cells → fake suffocation next to a kontra. check the SAME box
    // footprint so the isInWall mixin can bail. old code only tested eye + eye.below → rounding missed
    // the real cell on rotated/offset kontras and suffocation leaked through.
    public static boolean eyeBoxHasPhysicsBlock(ServerLevel level, double eyeX, double eyeY, double eyeZ, double width) {
        if (KONTRAS.isEmpty()) return false;
        double f = width * 0.4; // half of vanilla's width*0.8
        int minX = (int)Math.floor(eyeX - f), maxX = (int)Math.floor(eyeX + f);
        int minZ = (int)Math.floor(eyeZ - f), maxZ = (int)Math.floor(eyeZ + f);
        // 0.06 covers both the isInWall plane (1e-6) and the screen-overlay sampling (eye ± 0.05*scale)
        int minY = (int)Math.floor(eyeY - 0.06), maxY = (int)Math.floor(eyeY + 0.06);
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null) return false;
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    if (index.contains(BlockPos.asLong(x, y, z))) return true;
                }
        return false;
    }

    // a kontra just parked on the grid: collision went from SAT-slop to exact solid cells, and
    // anything standing on the deck can be a hair INSIDE the new floor — then the neighbouring
    // block sides eat every horizontal move (the "frozen on a parked ship" bug). one-shot pop-up.
    private static void settleRidersOnParked(ServerLevel lvl, long id, KontraEntry data, float[] pos, float[] rot) {
        Map<Long, BlockState> cells = data.solidCells(pos, rot);
        if (cells.isEmpty()) return;
        float r = data.cachedRadius + 2f;
        AABB box = new AABB(pos[0]-r, pos[1]-r, pos[2]-r, pos[0]+r, pos[1]+r, pos[2]+r);
        for (Entity ent : lvl.getEntitiesOfClass(Entity.class, box)) {
            if (ent.isSpectator()) continue;
            AABB bb = ent.getBoundingBox();
            int cy = (int)Math.floor(bb.minY + 1.0e-5);
            double lift = 0;
            int x0 = (int)Math.floor(bb.minX), x1 = (int)Math.floor(bb.maxX);
            int z0 = (int)Math.floor(bb.minZ), z1 = (int)Math.floor(bb.maxZ);
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    if (cells.containsKey(BlockPos.asLong(x, cy, z)))
                        lift = Math.max(lift, (cy + 1) - bb.minY);
            if (lift > 1.0e-4 && lift <= 0.35) {
                ent.setPos(ent.getX(), ent.getY() + lift + 1.0e-3, ent.getZ());
                ent.setOnGround(true);
                if (ent instanceof ServerPlayer sp) sp.syncVelocity = true; // one-shot fix, not the old spam
            }
        }
    }

    // spawn eggs & friends: vanilla getYOffset fits the mob through chunk collisions, where a
    // rotated kontra doesn't exist — Shapes.collide over ZERO shapes returns the full -2, so
    // yOff = -1 and the mob materializes INSIDE the clicked deck block. drop-probe onto the
    // deck plane instead; keep vanilla's spot only when there is genuinely no deck here.
    public static void fitSpawnOnKontra(ServerLevel level, Entity e, BlockPos spawnPos) {
        if (e == null || KONTRAS.isEmpty() || e.isPassenger()) return;
        String key = levelKey(level);
        double cx = spawnPos.getX() + 0.5, cy = spawnPos.getY() + 0.5, cz = spawnPos.getZ() + 0.5;
        boolean near = false;
        for (var en : KONTRAS.entrySet()) {
            KontraEntry d = en.getValue();
            if (d.aligned || !d.levelKey().equals(key)) continue;
            float[] p = CACHED_POS.get(en.getKey());
            if (p == null) continue;
            double dx = cx - p[0], dy = cy - p[1], dz = cz - p[2];
            double reach = d.cachedRadius + 3.0;
            if (dx * dx + dy * dy + dz * dz <= reach * reach) { near = true; break; }
        }
        if (!near) return;
        double ox = e.getX(), oy = e.getY(), oz = e.getZ();
        // generous window: rotated decks poke above/below the phantom cell the ray clicked,
        // and vanilla may have parked us a whole block deep already
        double startY = spawnPos.getY() + 2.1;
        e.setPos(cx, startY, cz);
        KontraRide.Ride drop = KontraRide.serverClamp(e, new Vec3(0.0, -4.5, 0.0));
        if (drop != null && drop.delta != null && drop.onGround && drop.kontraId != 0) {
            e.setPos(cx + drop.delta.x, startY + drop.delta.y, cz + drop.delta.z);
            e.setOnGround(true);
            e.setDeltaMovement(Vec3.ZERO);
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[RideDbg] (S) spawn-fit {} onto kontra {} feetY={}",
                        e.getType().toShortString(), drop.kontraId, String.format("%.2f", e.getY()));
        } else {
            e.setPos(ox, oy, oz); // no deck under the click after all — keep vanilla's spot
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[RideDbg] (S) spawn-fit MISS for {} at {} (drop={} ground={} kontra={}) kept vanilla y={}",
                        e.getType().toShortString(), spawnPos,
                        drop != null, drop != null && drop.onGround, drop != null ? drop.kontraId : 0,
                        String.format("%.2f", oy));
        }
    }

    // solid-block oracle for BlockCollisions: which ALIGNED kontra owns this world cell.
    // per-kontra world-cell caches make this a hashmap hit — hot path, runs per collision query cell
    public static BlockState alignedSolidAt(ServerLevel level, BlockPos pos) {
        if (KONTRAS.isEmpty()) return null;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        long packed=pos.asLong();
        if (index == null) return null;
        long first=index.primary.get(packed);
        if (first != 0L) {
            KontraEntry data = KONTRAS.get(first);
            if (data != null && data.aligned) {
                BlockState state = data.logicCells().get(packed);
                if (state != null) return state;
            }
        }
        var more=index.overlaps.get(packed);
        if (more != null) for (int i=0;i<more.size();i++) {
            KontraEntry data=KONTRAS.get(more.getLong(i));
            if (data == null || !data.aligned) continue;
            BlockState state=data.logicCells().get(packed);
            if (state != null) return state;
        }
        return null;
    }

    // same scan as alignedSolidAt but hands back the SHAPE. parked kontras go through vanilla
    // collision, which asks the world for the shape, and a micro block has none out there -> empty
    // -> you fall through. lift at rest was ghost, lift a quarter block up was solid. yeah.
    public static java.util.List<net.minecraft.world.phys.AABB> alignedShapeAt(
            ServerLevel level, BlockPos pos) {
        if (KONTRAS.isEmpty()) return null;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null) return null;
        long packed = pos.asLong();
        long first = index.primary.get(packed);
        if (first != 0L) {
            java.util.List<net.minecraft.world.phys.AABB> hit = alignedShapeIn(KONTRAS.get(first), packed);
            if (hit != null) return hit;
        }
        var more = index.overlaps.get(packed);
        if (more != null) for (int i = 0; i < more.size(); i++) {
            java.util.List<net.minecraft.world.phys.AABB> hit =
                alignedShapeIn(KONTRAS.get(more.getLong(i)), packed);
            if (hit != null) return hit;
        }
        return null;
    }

    private static java.util.List<net.minecraft.world.phys.AABB> alignedShapeIn(
            KontraEntry data, long packed) {
        if (data == null || !data.aligned) return null;
        if (data.logicCells().get(packed) == null) return null;
        BlockPos owner = data.logicOwner(packed);
        return owner == null ? null : data.shapeAt(owner);
    }

    // true if this world cell was just removed via the kontra break path — lets ServerBreakMixin
    // cancel the vanilla STOP_DESTROY completion even after our removal already cleared the phantom
    public static boolean wasRecentlyKontraBroken(BlockPos worldPos) {
        Long t = RECENT_KONTRA_BREAKS.get(worldPos.asLong());
        return t != null && tickCounter - t <= 10;
    }

    // update block state in kontraktion (door open/close, lever toggle, etc.) — world pos → local pos lookup
    public static void updateKontraBlockState(ServerLevel level, BlockPos worldPos, BlockState newState) {
        String key = levelKey(level);
        for (var e : KONTRAS.entrySet()) {
            KontraEntry data = e.getValue();
            if (!data.levelKey().equals(key)) continue;
            float[] pos = CACHED_POS.get(e.getKey());
            float[] rot = CACHED_ROT.get(e.getKey());
            if (pos == null || rot == null) continue;
            BlockPos local = worldPosToLocal(worldPos, pos, rot);
            if (!data.blocks.containsKey(local)) continue;
            // a state update, not a removal: breaking goes through breakBlock, which drops the collider too
            if (newState.isAir()) continue;
            data.blocks.put(local, newState);
            // same rule as the grid: a be that doesn't fit the new block goes, else the next split tried
            // to load a magic table's be onto planks and vanilla threw
            BlockEntity koperStaleBe = data.blockEntities.get(local);
            if (koperStaleBe != null) {
                if (koperStaleBe.isValidBlockState(newState)) koperStaleBe.setBlockState(newState);
                else {
                    data.blockEntities.remove(local);
                    koperStaleBe.setRemoved();
                }
            }
            // for two-part blocks (doors): sync partner half's OPEN/powered state
            if (newState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                DoubleBlockHalf half = newState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
                BlockPos partner = half == DoubleBlockHalf.LOWER ? local.above() : local.below();
                BlockState partnerState = data.blocks.get(partner);
                if (partnerState != null && partnerState.getBlock() == newState.getBlock()
                        && newState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
                    boolean open = newState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
                    data.blocks.put(partner, partnerState.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, open));
                }
            }
            data.invalidateSolidCells();
            data.invalidateLogicCells();
            return;
        }
    }

    // which kontraktion + local pos is at this world pos — null if none
    public static long[] findKontraAt(ServerLevel level, BlockPos worldPos) {
        String key = levelKey(level);
        for (var e : KONTRAS.entrySet()) {
            KontraEntry data = e.getValue();
            if (!data.levelKey().equals(key)) continue;
            float[] pos = CACHED_POS.get(e.getKey());
            float[] rot = CACHED_ROT.get(e.getKey());
            if (pos == null || rot == null) continue;
            BlockPos local = worldPosToLocal(worldPos, pos, rot);
            if (data.blocks.containsKey(local)) return new long[]{ e.getKey(), local.asLong() };
        }
        return null;
    }

    public record GridEndpoint(long kontraId, BlockPos local) {}

    // Resolves an old assembly position or a current world projection to a stable moving endpoint.
    // Addons persist (body id, local) after this instead of pinning wires to a world coordinate.
    public static GridEndpoint resolveMovingEndpoint(ServerLevel level, BlockPos worldPos) {
        String key = levelKey(level);
        for (var e : KONTRAS.entrySet()) {
            KontraEntry data = e.getValue();
            if (!data.levelKey().equals(key)) continue;
            BlockPos local = data.localForWorld(worldPos);
            if (local != null && data.blocks.containsKey(local))
                return new GridEndpoint(e.getKey(), local.immutable());
        }
        var index = PHYSICAL_CELL_INDEX.get(key);
        GridCell cell = index != null ? nearestPhysicalCell(index, worldPos.asLong(),
            worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5) : null;
        if (cell != null)
            return new GridEndpoint(cell.kontraId(), cell.local().immutable());
        long[] fallback = findKontraAt(level, worldPos);
        return fallback == null ? null
            : new GridEndpoint(fallback[0], BlockPos.of(fallback[1]).immutable());
    }

    // ── spawn ─────────────────────────────────────────────────────────────────

    // yanks the given blocks from the world and spawns a Rapier body
    // returns kontraktion ID, or -1 on failure
    public static long makeKontraktion(ServerLevel level, List<BlockPos> positions) {
        if (!KoperPhysBridge.isLoaded() || !com.koper.koper_lib.physics.KhysicsConfig.get().enablePhysics) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] makeKontraktion blocked — physics disabled (set enablePhysics=true in config)");
            return -1L;
        }
        if (positions.isEmpty()) return -1L;
        // an empty cell is not a block of the body. one that went air between being picked and being
        // made (an explosion, a piston, water) was stored as an "air" block with a collider of its own,
        // saved with the world and loaded back as a body nobody built
        java.util.List<BlockPos> solid = new ArrayList<>(positions.size());
        for (BlockPos p : positions) if (!realBlockState(level, p).isAir()) solid.add(p);
        if (solid.size() != positions.size())
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] makeKontraktion: {} of {} cells were air and were left out",
                positions.size() - solid.size(), positions.size());
        positions = solid;
        if (positions.isEmpty()) return -1L;
        int maxBlocks = com.koper.koper_lib.physics.KhysicsConfig.get().maxKontraktionBlocks;
        if (maxBlocks > 0 && positions.size() > maxBlocks) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] makeKontraktion: {} blocks exceeds maxKontraktionBlocks={} in config", positions.size(), maxBlocks);
            return -2L;
        }

        double exactCx = 0, exactCy = 0, exactCz = 0;
        for (BlockPos p : positions) {
            exactCx += p.getX() + 0.5;
            exactCy += p.getY() + 0.5;
            exactCz += p.getZ() + 0.5;
        }
        exactCx /= positions.size();
        exactCy /= positions.size();
        exactCz /= positions.size();
        float cx = (float) exactCx, cy = (float) exactCy, cz = (float) exactCz;
        // NOTE: no centroid snap here — snapping to floor+0.5 caused 0.5-block physics offset
        // because Rust recomputes its own centroid from the integer offsets we send it

        // snapshot block states + BEs before touching anything
        List<BlockState> states  = new ArrayList<>();
        List<BlockEntity> bes    = new ArrayList<>();
        List<net.minecraft.nbt.CompoundTag> localPayloads = new ArrayList<>();
        for (BlockPos p : positions) {
            BlockState state = level.getBlockState(p);
            states.add(state);
            bes.add(level.getBlockEntity(p)); // null if no BE
            localPayloads.add(com.koper.koper_lib.api.local.KoperLocalData.capture(level, p, state));
        }

        // detach BEs from chunk BEFORE setBlock — so onRemove finds null BE and skips container drops
        // (chests, barrels, flower pots etc. all check level.getBlockEntity() in their onRemove)
        for (int i = 0; i < positions.size(); i++) {
            BlockEntity be = bes.get(i);
            if (be == null) continue;
            level.removeBlockEntity(positions.get(i));
        }

        // remove blocks from real world — NO neighbor updates (KNOWN_SHAPE, no UPDATE_NEIGHBORS)!
        // with them on, pulling the support out from under a torch/lever made vanilla pop the
        // attached block as a DROP while the same block also joined the kontra = duplication
        ASSEMBLY_ACTIVE.set(true);
        try {
            for (BlockPos p : positions) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE);
            }
        } finally {
            ASSEMBLY_ACTIVE.set(false);
        }

        // build Rapier input: offsets as ints from centroid
        int n = positions.size();
        int[] blockCoords = new int[n * 3];
        float[] masses    = new float[n];
        float[] offsets   = new float[n * 3];
        int[] stateIds    = new int[n];

        int lightCount = 0;
        for (int i = 0; i < n; i++) {
            BlockPos p = positions.get(i);
            // subtract in double or adjacent blocks alias once world coordinates exceed float precision
            float ox = (float) (p.getX() + 0.5 - (double) cx);
            float oy = (float) (p.getY() + 0.5 - (double) cy);
            float oz = (float) (p.getZ() + 0.5 - (double) cz);
            blockCoords[i*3]   = Math.round(ox);
            blockCoords[i*3+1] = Math.round(oy);
            blockCoords[i*3+2] = Math.round(oz);
            offsets[i*3]   = ox; offsets[i*3+1] = oy; offsets[i*3+2] = oz;
            masses[i] = KhysWeightBook.get(states.get(i)).mass();
            if (bes.get(i) instanceof KhysicsShapeProvider shape) {
                masses[i] = shape.khysicsMass(masses[i]);
            }
            var localPhysics = com.koper.koper_lib.api.local.KoperLocalData.physics(
                states.get(i), localPayloads.get(i), masses[i]);
            if (localPhysics != null) masses[i] = localPhysics.mass();
            stateIds[i] = Block.getId(states.get(i));
            if (states.get(i).is(LIGHT_BLOCKS)) lightCount++;
        }

        long worldHandle = getWorldHandle(level);
        if (worldHandle <= 0) return -1L; // createWorld failed, already logged

        // prime the section cache around the build BEFORE the spawn cmd — same queue, sections land
        // first, so the body never steps without its floor (the old fall-through-for-2-ticks bounce)
        int mnX = Integer.MAX_VALUE, mnY = Integer.MAX_VALUE, mnZ = Integer.MAX_VALUE;
        int mxX = Integer.MIN_VALUE, mxY = Integer.MIN_VALUE, mxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            mnX = Math.min(mnX, p.getX()); mxX = Math.max(mxX, p.getX());
            mnY = Math.min(mnY, p.getY()); mxY = Math.max(mxY, p.getY());
            mnZ = Math.min(mnZ, p.getZ()); mxZ = Math.max(mxZ, p.getZ());
        }
        com.koper.koper_lib.physics.terrain.TerrainSlurper.primeArea(level, worldHandle, mnX, mnY, mnZ, mxX, mxY, mxZ);

        // send the FLOAT offsets, not ints — the int path had Rust re-derive its own MASS-WEIGHTED
        // centroid while ours is unweighted → every mixed-mass kontra got its colliders shifted by
        // the delta (the "small kontra sits 5px in the floor" one). floats = render and collider
        // read the same numbers, nothing left to disagree
        long kontraId = KoperPhysBridge.spawnKontraktionOffsets(worldHandle, offsets, masses, lightCount, cx, cy, cz);
        if (kontraId <= 0) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KoperPhys] spawnKontraktion returned {} — Rapier spawn failed", kontraId);
            return -1L;
        }

        KontraEntry data = new KontraEntry(levelKey(level), worldHandle, states, offsets);

        // world→local guarantee for addons: local slot i came from positions[i]. published BEFORE
        // fireSpawn so connection graphs (mechanics_dream etc.) can re-key in the spawn event
        for (int i = 0; i < n; i++) {
            BlockPos local = new BlockPos(Math.round(offsets[i*3]), Math.round(offsets[i*3+1]), Math.round(offsets[i*3+2]));
            data.assembledFrom.put(local, positions.get(i).immutable());
        }

        // attach BEs into data
        for (int i = 0; i < n; i++) {
            BlockPos local = new BlockPos(Math.round(offsets[i*3]), Math.round(offsets[i*3+1]), Math.round(offsets[i*3+2]));
            if (bes.get(i) != null) {
                BlockEntity be = relocateBlockEntity(bes.get(i), data.grid(kontraId).toGrid(local), states.get(i), level);
                if (be != null) data.blockEntities.put(local, be);
            }
            net.minecraft.nbt.CompoundTag payload = localPayloads.get(i);
            if (payload != null && !payload.isEmpty()) data.localData.put(local, payload.copy());
        }
        pushMaterials(kontraId, data);

        KONTRAS.put(kontraId, data);
        indexLogicalEntry(kontraId, data);
        CACHED_POS.put(kontraId, new float[]{cx, cy, cz});
        CACHED_ROT.put(kontraId, new float[]{0f, 0f, 0f, 1f});
        bootstrapGrid(level, kontraId, data);

        if (level.getServer() != null) {
            var render = data.renderArrays();
            KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(kontraId,
                new float[]{cx,cy,cz}, new float[]{0f,0f,0f,1f}, render.stateIds(), render.offsets(), render.locals(),
                blockEntityTags(data, level)));
        }

        pushAero(kontraId, data);
        KoperPhysicsEvents.fireSpawn(kontraId, data);
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys] spawned kontra {} ({} blocks) at {},{},{}", kontraId, n, cx, cy, cz);
        return kontraId;
    }

    public static long spawnGridPart(ServerLevel level, long parentId, BlockPos parentLocal,
                                     net.minecraft.core.Direction direction, BlockState state) {
        return spawnGridPart(level, parentId, parentLocal,
            direction.getStepX(), direction.getStepY(), direction.getStepZ(), state, null);
    }

    public static long spawnGridPart(ServerLevel level, long parentId, BlockPos parentLocal,
                                     float localX, float localY, float localZ, BlockState state) {
        return spawnGridPart(level, parentId, parentLocal, localX, localY, localZ, state, null);
    }

    public static long spawnGridPart(ServerLevel level, long parentId, BlockPos parentLocal,
                                     float localX, float localY, float localZ, BlockState state,
                                     java.util.function.Consumer<BlockEntity> blockEntitySetup) {
		return spawnGridPart0(level, parentId, parentLocal, localX, localY, localZ, state,
			blockEntitySetup, null);
	}

	public static long spawnGridPartWithLocalData(ServerLevel level, long parentId, BlockPos parentLocal,
			float localX, float localY, float localZ, BlockState state,
			net.minecraft.nbt.CompoundTag localData) {
		return spawnGridPart0(level, parentId, parentLocal, localX, localY, localZ, state,
			null, localData);
	}

	private static long spawnGridPart0(ServerLevel level, long parentId, BlockPos parentLocal,
			float localX, float localY, float localZ, BlockState state,
			java.util.function.Consumer<BlockEntity> blockEntitySetup,
			net.minecraft.nbt.CompoundTag localData) {
        KontraEntry parent = KONTRAS.get(parentId);
        float[] parentPos = CACHED_POS.get(parentId);
        float[] parentRot = CACHED_ROT.get(parentId);
        float[] parentOffset = parent != null ? parent.blockOffsets.get(parentLocal) : null;
        if (parent == null || parentPos == null || parentRot == null || parentOffset == null || state == null) return -1L;

        BlockEntity initialBe = null;
        if (state.hasBlockEntity() && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            initialBe = entityBlock.newBlockEntity(BlockPos.ZERO, state);
            if (initialBe != null) {
                initialBe.setLevel(level);
                if (blockEntitySetup != null) blockEntitySetup.accept(initialBe);
            }
        }
        float lx = parentOffset[0] + localX;
        float ly = parentOffset[1] + localY;
        float lz = parentOffset[2] + localZ;
        float[] world = localToWorld(lx, ly, lz, parentPos, parentRot);
        float mass = KhysWeightBook.get(state).mass();
        if (initialBe instanceof KhysicsShapeProvider shape)
            mass = shape.khysicsMass(mass);
		var localPhysics = com.koper.koper_lib.api.local.KoperLocalData.physics(state, localData, mass);
		if (localPhysics != null) mass = localPhysics.mass();
        long id = KoperPhysBridge.spawnKontraktionOffsets(
            parent.worldHandle(), new float[]{0f, 0f, 0f}, new float[]{mass},
            state.is(LIGHT_BLOCKS) ? 1 : 0, world[0], world[1], world[2]);
        if (id <= 0) return id;

        KoperPhysBridge.setTransform(parent.worldHandle(), id,
            world[0], world[1], world[2], parentRot[0], parentRot[1], parentRot[2], parentRot[3]);
        KontraEntry entry = new KontraEntry(parent.levelKey(), parent.worldHandle(), List.of(state),
            new float[]{0f, 0f, 0f});
		if (localData != null && !localData.isEmpty()) entry.localData.put(BlockPos.ZERO, localData.copy());
        if (initialBe != null) {
            BlockEntity moved = relocateBlockEntity(initialBe, entry.grid(id).toGrid(BlockPos.ZERO), state, level);
            if (moved != null) entry.blockEntities.put(BlockPos.ZERO, moved);
        }
        KONTRAS.put(id, entry);
        indexLogicalEntry(id, entry);
        CACHED_POS.put(id, world);
        CACHED_ROT.put(id, parentRot.clone());
        bootstrapGrid(level, id, entry);
        pushMaterials(id, entry);
        pushAero(id, entry);
        var render = entry.renderArrays();
        KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(id, world, parentRot.clone(),
            render.stateIds(), render.offsets(), render.locals(), blockEntityTags(entry, level)));
        KoperPhysicsEvents.fireSpawn(id, entry);
        return id;
    }

    // build the aero surface list from ONLY the aero-tagged blocks and hand it to Rust. plain blocks
    // contribute nothing. each surface = local offset, lift normal (local up), 1 m² area, Cd, Cl (WING
    // lift from motion), buoy (BALLOON air-buoyancy). resend on every block change; empty clears the aero.
    public static void pushAero(long kontraId, KontraEntry data) {
        int count = 0;
        for (BlockState bs : data.blocks.values()) if (KhysWeightBook.get(bs).aero()) count++;

        float[] surf = new float[count * 10];
        int i = 0;
        for (var e : data.blocks.entrySet()) {
            var p = KhysWeightBook.get(e.getValue());
            if (!p.aero()) continue;
            float[] off = data.blockOffsets.get(e.getKey());
            float ox = off != null ? off[0] : e.getKey().getX();
            float oy = off != null ? off[1] : e.getKey().getY();
            float oz = off != null ? off[2] : e.getKey().getZ();
            float[] plate = koperPlateOf(e.getValue(), data.localData.get(e.getKey()));
            int b = i * 10;
            surf[b]   = ox + plate[4];  surf[b+1] = oy + plate[5];  surf[b+2] = oz + plate[6];
            surf[b+3] = plate[0];  surf[b+4] = plate[1];  surf[b+5] = plate[2];
            surf[b+6] = plate[3];
            surf[b+7] = p.dragCoeff();
            surf[b+8] = p.liftCoeff();
            surf[b+9] = p.balloonLift();
            i++;
        }
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Aero] kontra {} → {} aero blocks ({} total)", kontraId, count, data.blocks.size());
        KoperPhysBridge.setAero(data.worldHandle(), kontraId, surf);
        KoperPhysBridge.setAeroMode(data.worldHandle(), kontraId, data.aeroMode().id());
        pushBuoy(kontraId, data);
    }

    // which way a block faces the air: a thin plate (a micro block layer, a slab, a pressure plate)
    // lifts across its thin axis with the area of its big face. anything chunky keeps local up and
    // one square, like every block did before. out = nx,ny,nz, area, then the plate centre's shift
    // from the cell centre, so a plate sitting on a cell edge pushes from where it really is
    static float[] koperPlateOf(BlockState state, net.minecraft.nbt.CompoundTag localData) {
        List<AABB> boxes = com.koper.koper_lib.physics.shape.KhysShapeCache.get(state, localData);
        // a zero normal = chunky, no face of its own: the engine lays it flat on a wing and across
        // the axle on a spinning prop. a full block blade used to count as local up, a paddle
        if (boxes == null || boxes.isEmpty()) return new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f};
        double x0 = 1, y0 = 1, z0 = 1, x1 = 0, y1 = 0, z1 = 0;
        for (AABB box : boxes) {
            x0 = Math.min(x0, box.minX); y0 = Math.min(y0, box.minY); z0 = Math.min(z0, box.minZ);
            x1 = Math.max(x1, box.maxX); y1 = Math.max(y1, box.maxY); z1 = Math.max(z1, box.maxZ);
        }
        float ex = (float) (x1 - x0), ey = (float) (y1 - y0), ez = (float) (z1 - z0);
        float cx = (float) ((x0 + x1) * 0.5 - 0.5), cy = (float) ((y0 + y1) * 0.5 - 0.5),
              cz = (float) ((z0 + z1) * 0.5 - 0.5);
        float thin = Math.min(ex, Math.min(ey, ez));
        float thick = Math.max(ex, Math.max(ey, ez));
        if (thin > thick * 0.5f) return new float[] {0f, 0f, 0f, Math.max(0.05f, ex * ez), cx, cy, cz};
        if (thin == ey) return new float[] {0f, 1f, 0f, ex * ez, cx, cy, cz};
        if (thin == ex) return new float[] {1f, 0f, 0f, ey * ez, cx, cy, cz};
        return new float[] {0f, 0f, 1f, ex * ey, cx, cy, cz};
    }

    public static void pushMaterials(long kontraId, KontraEntry data) {
        float[][] built = buildMaterials(data);
        KoperPhysBridge.setBlockMaterials(data.worldHandle(), kontraId, built[0]);
        if (built[1].length > 0) KoperPhysBridge.setBlockShapes(data.worldHandle(), kontraId, built[1]);
    }

    // what pushMaterials sends, without sending it: [0] = 15 floats per block (friction, restitution,
    // wheel flag, axle, micro resolution + cells, collider offset, block offset), [1] = 11 floats per
    // collision box (block index, centre, half size, rotation). a scene dump replays exactly these
    public static float[][] buildMaterials(KontraEntry data) {
        float[] materials = new float[data.blocks.size() * 15];
        java.util.List<java.util.List<com.koper.koper_lib.api.core.KoperShapeBox>> blockShapes =
                new java.util.ArrayList<>(data.blocks.size());
        int shapeCount = 0;
        int i = 0;
        for (var entry : data.blocks.entrySet()) {
            BlockState state = entry.getValue();
            var props = KhysWeightBook.get(state);
            int at = i * 15;
            materials[at] = props.friction();
            materials[at + 1] = props.restitution();
            materials[at + 2] = props.wheel() ? 1f : 0f;
            net.minecraft.core.Direction axis = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING) : net.minecraft.core.Direction.UP;
            materials[at + 3] = axis.getStepX();
            materials[at + 4] = axis.getStepY();
            materials[at + 5] = axis.getStepZ();
            BlockEntity blockEntity = data.blockEntities.get(entry.getKey());
            if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                materials[at + 6] = -1f;
            } else if (blockEntity instanceof KhysicsShapeProvider shape) {
                long cells = shape.khysicsCells();
                materials[at + 6] = shape.khysicsResolution();
                materials[at + 7] = Float.intBitsToFloat((int)cells);
                materials[at + 8] = Float.intBitsToFloat((int)(cells >>> 32));
            } else {
                var localPhysics = com.koper.koper_lib.api.local.KoperLocalData.physics(
                    state, data.localData.get(entry.getKey()), props.mass());
                if (localPhysics != null) {
                    materials[at + 6] = localPhysics.resolution();
                    materials[at + 7] = Float.intBitsToFloat((int)localPhysics.occupiedCells());
                    materials[at + 8] = Float.intBitsToFloat((int)(localPhysics.occupiedCells() >>> 32));
                }
            }
            float[] blockOffset = data.blockOffsets.get(entry.getKey());
            float ox = blockOffset != null ? blockOffset[0] : entry.getKey().getX();
            float oy = blockOffset != null ? blockOffset[1] : entry.getKey().getY();
            float oz = blockOffset != null ? blockOffset[2] : entry.getKey().getZ();
            net.minecraft.world.phys.Vec3 modelOffset = KhysicsShapeAdapters.colliderOffset(state);
            materials[at + 9] = ox + (float)modelOffset.x;
            materials[at + 10] = oy + (float)modelOffset.y;
            materials[at + 11] = oz + (float)modelOffset.z;
            materials[at + 12] = ox;
            materials[at + 13] = oy;
            materials[at + 14] = oz;
            if (props.wheel()) {
                blockShapes.add(java.util.List.of());
            } else {
                java.util.List<com.koper.koper_lib.api.core.KoperShapeBox> shapes = KhysicsShapeAdapters.forState(state);
                blockShapes.add(shapes);
                shapeCount += shapes.size();
            }
            i++;
        }
        float[] shapes = new float[shapeCount * 11];
        int out = 0;
        for (int blockIndex = 0; blockIndex < blockShapes.size(); blockIndex++) {
            for (com.koper.koper_lib.api.core.KoperShapeBox box : blockShapes.get(blockIndex)) {
                int at = out++ * 11;
                shapes[at] = blockIndex;
                shapes[at + 1] = box.cx();
                shapes[at + 2] = box.cy();
                shapes[at + 3] = box.cz();
                shapes[at + 4] = box.hx();
                shapes[at + 5] = box.hy();
                shapes[at + 6] = box.hz();
                shapes[at + 7] = box.qx();
                shapes[at + 8] = box.qy();
                shapes[at + 9] = box.qz();
                shapes[at + 10] = box.qw();
            }
        }
        return new float[][] { materials, shapes };
    }

    // water displacement per block — rides the same resend points as aero, so it never goes stale.
    // Rust defaults missing keys to 1.0, we only ship blocks whose weight-book volume differs
    public static void pushBuoy(long kontraId, KontraEntry data) {
        int diff = 0;
        for (BlockState bs : data.blocks.values()) if (KhysWeightBook.get(bs).buoyancyVolume() != 1.0f) diff++;
        if (diff == 0) { KoperPhysBridge.setBuoyancy(data.worldHandle(), kontraId, new float[0]); return; }
        float[] buf = new float[diff * 4];
        int i = 0;
        for (var e : data.blocks.entrySet()) {
            var p = KhysWeightBook.get(e.getValue());
            if (p.buoyancyVolume() == 1.0f) continue;
            float[] off = data.blockOffsets.get(e.getKey());
            int b = i * 4;
            buf[b]   = off != null ? off[0] : e.getKey().getX();
            buf[b+1] = off != null ? off[1] : e.getKey().getY();
            buf[b+2] = off != null ? off[2] : e.getKey().getZ();
            buf[b+3] = p.buoyancyVolume();
            i++;
        }
        KoperPhysBridge.setBuoyancy(data.worldHandle(), kontraId, buf);
    }

    // ── assemblies: a whole machine as one thing ─────────────────────────────
    // A vehicle is not one body — it is a graph of bodies held by joints. Anything that rebuilds it
    // from world blocks quantises every part onto integer cells and the wheels come back in the wrong
    // place. Capture and respawn it with the EXACT poses instead: nothing is rounded, nothing is lost.

    public record BodySnap(List<BlockState> states, float[] offsets, float[] pos, float[] rot,
                           net.minecraft.nbt.CompoundTag[] blockEntityTags) {
        public BodySnap(List<BlockState> states, float[] offsets, float[] pos, float[] rot) {
            this(states, offsets, pos, rot, new net.minecraft.nbt.CompoundTag[states.size()]);
        }
    }

    public record JointSnap(int a, int b, float[] anchorA, float[] anchorB, float[] axis,
                            boolean prismatic, float limitMin, float limitMax,
                            float motorVelocity, float motorForce,
                            float motorTarget, float motorStiffness,
                            float motorDamping, float motorMaxForce,
                            boolean forceBasedPositionMotor) {
        public JointSnap(int a, int b, float[] anchorA, float[] anchorB, float[] axis,
                         boolean prismatic, float limitMin, float limitMax,
                         float motorVelocity, float motorForce,
                         float motorTarget, float motorStiffness,
                         float motorDamping, float motorMaxForce) {
            this(a, b, anchorA, anchorB, axis, prismatic, limitMin, limitMax,
                motorVelocity, motorForce, motorTarget, motorStiffness,
                motorDamping, motorMaxForce, false);
        }

        public JointSnap(int a, int b, float[] anchorA, float[] anchorB, float[] axis,
                         boolean prismatic, float limitMin, float limitMax) {
            this(a, b, anchorA, anchorB, axis, prismatic, limitMin, limitMax,
                0f, 0f, Float.NaN, 0f, 0f, 0f, false);
        }
    }

    // bodies[0] is the root — every pose is stored relative to it, so the group can be put down
    // anywhere and keep its shape exactly
    public record AssemblySnap(List<BodySnap> bodies, List<JointSnap> joints, float[] rootRot) {
        public AssemblySnap(List<BodySnap> bodies, List<JointSnap> joints) {
            this(bodies, joints, new float[]{0f, 0f, 0f, 1f});
        }
        public int blockCount() {
            int total = 0;
            for (BodySnap body : bodies) total += body.states().size();
            return total;
        }
    }

    public static List<Long> assemblyOf(long rootId) {
        return assemblyOf(rootId, -1L);
    }

    public static List<Long> assemblyOf(long rootId, long ignoredJointId) {
        List<Long> out = new ArrayList<>();
        if (!KONTRAS.containsKey(rootId)) return out;
        out.add(rootId);
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            for (var joint : jointSpecs().entrySet()) {
                if (joint.getKey() == ignoredJointId) continue;
                JointSpec spec = joint.getValue();
                if (spec.b() == 0L) continue;           // world anchors are not part of the machine
                long other = spec.a() == current ? spec.b() : spec.b() == current ? spec.a() : -1L;
                if (other <= 0 || out.contains(other) || !KONTRAS.containsKey(other)) continue;
                out.add(other);
                queue.add(other);
            }
        }
        return out;
    }

    public static AssemblySnap captureAssembly(long rootId) {
        return captureAssembly(rootId, -1L);
    }

    public static AssemblySnap captureAssembly(long rootId, long ignoredJointId) {
        List<Long> bodies = assemblyOf(rootId, ignoredJointId);
        if (bodies.isEmpty()) return null;
        float[] rootPos = CACHED_POS.get(bodies.get(0));
        float[] rootRot = CACHED_ROT.get(bodies.get(0));
        if (rootPos == null || rootRot == null) return null;
        org.joml.Quaternionf rootInverse =
            new org.joml.Quaternionf(rootRot[0], rootRot[1], rootRot[2], rootRot[3]).normalize().conjugate();

        List<BodySnap> snaps = new ArrayList<>();
        for (long body : bodies) {
            KontraEntry entry = KONTRAS.get(body);
            float[] pos = CACHED_POS.get(body);
            float[] rot = CACHED_ROT.get(body);
            if (entry == null || pos == null || rot == null) return null;
            var render = entry.renderArrays();
            ServerLevel bodyLevel = levelFor(entry);
            net.minecraft.nbt.CompoundTag[] tags = bodyLevel != null
                ? blockEntityTags(entry, bodyLevel)
                : new net.minecraft.nbt.CompoundTag[render.states().size()];
            // pose relative to the root, in the root's own frame
            org.joml.Vector3f delta = rootInverse.transform(
                new org.joml.Vector3f(pos[0] - rootPos[0], pos[1] - rootPos[1], pos[2] - rootPos[2]));
            org.joml.Quaternionf relative = new org.joml.Quaternionf(rootInverse)
                .mul(new org.joml.Quaternionf(rot[0], rot[1], rot[2], rot[3]).normalize());
            snaps.add(new BodySnap(List.copyOf(render.states()), render.offsets().clone(),
                new float[]{delta.x, delta.y, delta.z},
                new float[]{relative.x, relative.y, relative.z, relative.w}, tags));
        }

        List<JointSnap> joints = new ArrayList<>();
        for (var savedJoint : jointSpecs().entrySet()) {
            if (savedJoint.getKey() == ignoredJointId) continue;
            JointSpec spec = savedJoint.getValue();
            int a = bodies.indexOf(spec.a());
            int b = bodies.indexOf(spec.b());
            if (a < 0 || b < 0) continue;
            PositionMotor position = JOINT_POSITION_MOTORS.get(savedJoint.getKey());
            joints.add(new JointSnap(a, b, spec.anchorA().clone(), spec.anchorB().clone(),
                spec.axis().clone(), spec.prismatic(), spec.limitMin(), spec.limitMax(),
                spec.motorVel(), spec.motorForce(),
                position != null ? position.target() : Float.NaN,
                position != null ? position.stiffness() : 0f,
                position != null ? position.damping() : 0f,
                position != null ? position.maxForce() : 0f,
                position != null && position.forceBased()));
        }
        return new AssemblySnap(List.copyOf(snaps), List.copyOf(joints), rootRot.clone());
    }

    // A lift stores the mechanism at its neutral joint pose. Motor configuration stays in JointSnap;
    // only the live hinge/slider displacement and momentary redstone input are cleared.
    public static AssemblySnap freezeAssembly(AssemblySnap snap) {
        if (snap == null || snap.bodies().isEmpty()) return snap;

        List<BodySnap> bodies = new ArrayList<>(snap.bodies().size());
        for (BodySnap body : snap.bodies()) {
            List<BlockState> states = new ArrayList<>(body.states().size());
            for (BlockState state : body.states()) {
                Block block = state.getBlock();
                if ((block instanceof net.minecraft.world.level.block.LeverBlock
                        || block instanceof net.minecraft.world.level.block.ButtonBlock)
                        && state.hasProperty(BlockStateProperties.POWERED)) {
                    state = state.setValue(BlockStateProperties.POWERED, false);
                }
                states.add(state);
            }
            bodies.add(new BodySnap(List.copyOf(states), body.offsets().clone(),
                body.pos().clone(), body.rot().clone(), copyTags(body.blockEntityTags())));
        }

        org.joml.Vector3f[] positions = new org.joml.Vector3f[bodies.size()];
        org.joml.Quaternionf[] rotations = new org.joml.Quaternionf[bodies.size()];
        BodySnap root = bodies.getFirst();
        positions[0] = new org.joml.Vector3f(root.pos()[0], root.pos()[1], root.pos()[2]);
        rotations[0] = new org.joml.Quaternionf(
            root.rot()[0], root.rot()[1], root.rot()[2], root.rot()[3]).normalize();

        boolean changed;
        do {
            changed = false;
            for (JointSnap joint : snap.joints()) {
                if (joint.a() < 0 || joint.b() < 0
                        || joint.a() >= bodies.size() || joint.b() >= bodies.size()) continue;
                int parent;
                int child;
                float[] parentAnchor;
                float[] childAnchor;
                if (positions[joint.a()] != null && positions[joint.b()] == null) {
                    parent = joint.a();
                    child = joint.b();
                    parentAnchor = joint.anchorA();
                    childAnchor = joint.anchorB();
                } else if (positions[joint.b()] != null && positions[joint.a()] == null) {
                    parent = joint.b();
                    child = joint.a();
                    parentAnchor = joint.anchorB();
                    childAnchor = joint.anchorA();
                } else {
                    continue;
                }

                org.joml.Quaternionf childRotation =
                    new org.joml.Quaternionf(rotations[parent]);
                org.joml.Vector3f jointCenter = rotations[parent].transform(
                    new org.joml.Vector3f(parentAnchor[0], parentAnchor[1], parentAnchor[2]))
                    .add(positions[parent]);
                org.joml.Vector3f childOffset = childRotation.transform(
                    new org.joml.Vector3f(childAnchor[0], childAnchor[1], childAnchor[2]));
                positions[child] = jointCenter.sub(childOffset);
                rotations[child] = childRotation;
                changed = true;
            }
        } while (changed);

        for (int i = 1; i < bodies.size(); i++) {
            if (positions[i] == null || rotations[i] == null) continue;
            BodySnap body = bodies.get(i);
            org.joml.Vector3f pos = positions[i];
            org.joml.Quaternionf rot = rotations[i];
            bodies.set(i, new BodySnap(body.states(), body.offsets(),
                new float[]{pos.x, pos.y, pos.z},
                new float[]{rot.x, rot.y, rot.z, rot.w}, body.blockEntityTags()));
        }
        return new AssemblySnap(List.copyOf(bodies), snap.joints(),
            snap.rootRot() == null ? null : snap.rootRot().clone());
    }

    private static net.minecraft.nbt.CompoundTag[] copyTags(
            net.minecraft.nbt.CompoundTag[] tags) {
        if (tags == null) return null;
        net.minecraft.nbt.CompoundTag[] copy = new net.minecraft.nbt.CompoundTag[tags.length];
        for (int i = 0; i < tags.length; i++) copy[i] = tags[i] == null ? null : tags[i].copy();
        return copy;
    }

    // put the machine down at `where`, optionally turned by quarter turns about Y. returns the new
    // body ids in snapshot order (index 0 = root), or null if anything refused to spawn
    public static long[] spawnAssembly(ServerLevel level, AssemblySnap snap, float[] where, int yawQuarters) {
        return spawnAssembly(level, snap, where, yawQuarters, false);
    }

    // Park during the spawn command batch, before any restored joint can feed an impulse into the group.
    // The lift uses this path; normal schematic/world spawns stay dynamic.
    public static long[] spawnAssembly(ServerLevel level, AssemblySnap snap, float[] where,
                                       int yawQuarters, boolean parked) {
        if (snap == null || snap.bodies().isEmpty()) return null;
        org.joml.Quaternionf yaw = new org.joml.Quaternionf()
            .rotateY((float) (Math.floorMod(yawQuarters, 4) * Math.PI / 2));
        float[] savedRoot = snap.rootRot() != null && snap.rootRot().length == 4
            ? snap.rootRot()
            : new float[]{0f, 0f, 0f, 1f};
        org.joml.Quaternionf base = new org.joml.Quaternionf(yaw).mul(
            new org.joml.Quaternionf(savedRoot[0], savedRoot[1], savedRoot[2], savedRoot[3])).normalize();

        long[] ids = new long[snap.bodies().size()];
        for (int i = 0; i < snap.bodies().size(); i++) {
            BodySnap body = snap.bodies().get(i);
            org.joml.Vector3f at = base.transform(
                new org.joml.Vector3f(body.pos()[0], body.pos()[1], body.pos()[2]));
            org.joml.Quaternionf rot = new org.joml.Quaternionf(base).mul(
                new org.joml.Quaternionf(body.rot()[0], body.rot()[1], body.rot()[2], body.rot()[3]));
            ids[i] = spawnBody(level, body.states(), body.offsets(), body.blockEntityTags(),
                where[0] + at.x, where[1] + at.y, where[2] + at.z,
                new float[]{rot.x, rot.y, rot.z, rot.w});
            if (ids[i] <= 0) {
                for (int done = 0; done < i; done++) destroyKontraktion(level.getServer(), ids[done]);
                return null;
            }
            if (parked) setBodyParked(ids[i], true);
        }
        for (JointSnap joint : snap.joints()) {
            long id = joint.prismatic()
                ? createPrismaticJoint(ids[joint.a()], ids[joint.b()],
                    joint.anchorA()[0], joint.anchorA()[1], joint.anchorA()[2],
                    joint.anchorB()[0], joint.anchorB()[1], joint.anchorB()[2],
                    joint.axis()[0], joint.axis()[1], joint.axis()[2])
                : createRevoluteJoint(ids[joint.a()], ids[joint.b()],
                    joint.anchorA()[0], joint.anchorA()[1], joint.anchorA()[2],
                    joint.anchorB()[0], joint.anchorB()[1], joint.anchorB()[2],
                    joint.axis()[0], joint.axis()[1], joint.axis()[2]);
            if (id > 0 && Float.isFinite(joint.limitMin()) && Float.isFinite(joint.limitMax()))
                setJointLimits(id, joint.limitMin(), joint.limitMax());
            if (id > 0 && joint.motorForce() > 0f)
                setJointMotor(id, joint.motorVelocity(), joint.motorForce());
            if (id > 0 && Float.isFinite(joint.motorTarget()) && joint.motorMaxForce() > 0f)
                setJointMotorPosition(id, joint.motorTarget(), joint.motorStiffness(),
                    joint.motorDamping(), joint.motorMaxForce(), joint.forceBasedPositionMotor());
        }
        return ids;
    }

    public record AssemblyTransfer(Map<Long, Long> bodies, long root) {}

    // Move the whole joint island between Rapier worlds as one transaction. New bodies and joints are
    // alive before addons see the remap; only then are the old ids removed.
    public static AssemblyTransfer transferAssembly(long rootId, ServerLevel target,
                                                    float[] where, int yawQuarters) {
        KontraEntry rootEntry = KONTRAS.get(rootId);
        ServerLevel source = rootEntry == null ? null : levelFor(rootEntry);
        if (source == null || target == null) return null;
        List<Long> oldIds = assemblyOf(rootId);
        AssemblySnap snap = captureAssembly(rootId);
        float[] oldRootPos = CACHED_POS.get(rootId);
        if (snap == null || oldRootPos == null) return null;
        float[] destination = where != null && where.length >= 3
            ? new float[]{where[0], where[1], where[2]}
            : oldRootPos.clone();

        List<float[]> velocities = new ArrayList<>(oldIds.size());
        for (long oldId : oldIds) velocities.add(getKontraVelocity(oldId));
        long[] newIds = spawnAssembly(target, snap, destination, yawQuarters);
        if (newIds == null || newIds.length != oldIds.size()) return null;

        Map<Long, Long> remap = new LinkedHashMap<>();
        for (int i = 0; i < oldIds.size(); i++) {
            remap.put(oldIds.get(i), newIds[i]);
            float[] velocity = velocities.get(i);
            KontraEntry spawned = KONTRAS.get(newIds[i]);
            if (velocity != null && spawned != null)
                KoperPhysBridge.setVelocity(spawned.worldHandle(), newIds[i],
                    velocity[0] * 20f, velocity[1] * 20f, velocity[2] * 20f);
        }
        KoperPhysicsEvents.fireTransfer(Collections.unmodifiableMap(remap), source, target);
        for (long oldId : oldIds) destroyKontraktion(source.getServer(), oldId);
        return new AssemblyTransfer(Collections.unmodifiableMap(remap), newIds[0]);
    }

    // one body straight from states + offsets at an exact pose. no world blocks are touched, which is
    // the whole point: block cells would round the poses and break the machine
    public static long spawnBody(ServerLevel level, List<BlockState> states, float[] offsets,
                                 float cx, float cy, float cz, float[] rot) {
        return spawnBody(level, states, offsets, new net.minecraft.nbt.CompoundTag[states.size()],
            cx, cy, cz, rot);
    }

    public static long spawnBody(ServerLevel level, List<BlockState> states, float[] offsets,
                                 net.minecraft.nbt.CompoundTag[] blockEntityTags,
                                 float cx, float cy, float cz, float[] rot) {
        if (states.isEmpty() || offsets.length != states.size() * 3) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                "[KoperPhys] spawnBody refused: {} states but {} offset floats", states.size(), offsets.length);
            return -1L;
        }
        // air is not a block of a body (the same rule makeKontraktion keeps): it got a collider, was
        // saved, and loaded back as a body with "minecraft:air" in it
        if (states.stream().anyMatch(BlockState::isAir)) {
            List<BlockState> kept = new ArrayList<>();
            List<net.minecraft.nbt.CompoundTag> tags = new ArrayList<>();
            float[] keptOffsets = new float[offsets.length];
            for (int i = 0; i < states.size(); i++) {
                if (states.get(i).isAir()) continue;
                System.arraycopy(offsets, i * 3, keptOffsets, kept.size() * 3, 3);
                kept.add(states.get(i));
                tags.add(blockEntityTags != null && i < blockEntityTags.length ? blockEntityTags[i] : null);
            }
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] spawnBody: {} of {} states were air and were left out",
                states.size() - kept.size(), states.size());
            if (kept.isEmpty()) return -1L;
            states = kept;
            offsets = java.util.Arrays.copyOf(keptOffsets, kept.size() * 3);
            blockEntityTags = tags.toArray(new net.minecraft.nbt.CompoundTag[0]);
        }
        long worldHandle = getWorldHandle(level);
        if (worldHandle <= 0) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] spawnBody refused: no world for {}", levelKey(level));
            return -1L;
        }

        float[] masses = new float[states.size()];
        int lightCount = 0;
        for (int i = 0; i < states.size(); i++) {
            masses[i] = KhysWeightBook.get(states.get(i)).mass();
            net.minecraft.nbt.CompoundTag carrier = blockEntityTags != null && i < blockEntityTags.length
                ? blockEntityTags[i] : null;
            var localPhysics = com.koper.koper_lib.api.local.KoperLocalData.physics(states.get(i),
                com.koper.koper_lib.api.local.KoperLocalData.unpack(carrier), masses[i]);
            if (localPhysics != null) masses[i] = localPhysics.mass();
            if (states.get(i).is(LIGHT_BLOCKS)) lightCount++;
        }
        com.koper.koper_lib.physics.terrain.TerrainSlurper.primeArea(level, worldHandle,
            Math.round(cx) - 8, Math.round(cy) - 8, Math.round(cz) - 8,
            Math.round(cx) + 8, Math.round(cy) + 8, Math.round(cz) + 8);

        long id = KoperPhysBridge.spawnKontraktionOffsets(worldHandle, offsets, masses, lightCount, cx, cy, cz);
        if (id <= 0) {
            // whoever asked gets nothing and usually cannot say why. say it here instead of
            // leaving a caller (blocksgrab, for one) silently doing nothing
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                "[KoperPhys] spawnBody refused by {}: id={} for {} block(s), first mass {}, at {} {} {}",
                KoperPhysBridge.of(worldHandle).name(), id, states.size(),
                masses.length > 0 ? masses[0] : Float.NaN, cx, cy, cz);
            return -1L;
        }
        KoperPhysBridge.setTransform(worldHandle, id, cx, cy, cz, rot[0], rot[1], rot[2], rot[3]);

        KontraEntry data = new KontraEntry(levelKey(level), worldHandle, states, offsets);
        KONTRAS.put(id, data);
        indexLogicalEntry(id, data);
        CACHED_POS.put(id, new float[]{cx, cy, cz});
        CACHED_ROT.put(id, rot.clone());
        if (blockEntityTags != null) {
            for (int i = 0; i < states.size() && i < blockEntityTags.length; i++) {
                net.minecraft.nbt.CompoundTag tag = blockEntityTags[i];
                if (tag == null) continue;
                BlockPos local = new BlockPos(
                    Math.round(offsets[i * 3]),
                    Math.round(offsets[i * 3 + 1]),
                    Math.round(offsets[i * 3 + 2]));
                try {
                    net.minecraft.nbt.CompoundTag movedTag = tag.copy();
                    net.minecraft.nbt.CompoundTag localPayload =
                        com.koper.koper_lib.api.local.KoperLocalData.unpack(movedTag);
                    if (localPayload != null) data.localData.put(local, localPayload);
                    // A carrier may contain only koper_local_data. It is not a block-entity snapshot
                    // and has no id; feeding it to loadStatic prints "invalid type: null" once per
                    // ordinary car block and can abort callers that restore a whole assembly.
                    if (!states.get(i).hasBlockEntity() || !movedTag.contains("id")) continue;
                    stripKineticNbt(movedTag);
                    BlockEntity moved = BlockEntity.loadStatic(
                        data.grid(id).toGrid(local), states.get(i), movedTag, level.registryAccess());
                    if (moved != null) {
                        moved.setLevel(level);
                        data.blockEntities.put(local, moved);
                    }
                } catch (Throwable ex) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] couldn't restore BE snapshot at {}: {}",
                        local, ex.getMessage());
                }
            }
        }
        bootstrapGrid(level, id, data);
        pushMaterials(id, data);
        pushAero(id, data);
        var render = data.renderArrays();
        KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(id, new float[]{cx, cy, cz},
            rot.clone(), render.stateIds(), render.offsets(), render.locals(), blockEntityTags(data, level)));
        KoperPhysicsEvents.fireSpawn(id, data);
        return id;
    }

    // ── joints — bearing = revolute + motor, piston = prismatic + motor ──────
    // ids come back instantly, the joint itself materialises next physics step. anchors are
    // LOCAL block-offset coords of each kontra (same space as KontraEntry.blockOffsets values)

    private static final Map<Long, Long> JOINT_WORLDS = new ConcurrentHashMap<>(); // jointId → world handle
    private static final Map<Long, long[]> JOINT_ENDS = new ConcurrentHashMap<>(); // jointId → [kontraA, kontraB]

    // full creation params + last motor command — this is what survives a restart (kontras.bin v6)
    public record JointSpec(long a, long b, float[] anchorA, float[] anchorB, float[] axis,
                            boolean prismatic, float motorVel, float motorForce,
                            float limitMin, float limitMax) {
        public boolean limited() {
            return Float.isFinite(limitMin) && Float.isFinite(limitMax);
        }
    }
    private static final Map<Long, JointSpec> JOINT_SPECS = new ConcurrentHashMap<>();
    // A joint whose two anchors have drifted this far apart is not holding anything any more. Normal
    // solver error on a settled bearing is under 0.005 blocks, so this only trips on real breakage.
    private static final float JOINT_STRETCH_WARN = 0.25f;
    private static final java.util.Set<Long> JOINT_STRETCHED = ConcurrentHashMap.newKeySet();
    private record PositionMotor(float target, float stiffness, float damping, float maxForce,
                                 boolean forceBased) {}
    private static final Map<Long, PositionMotor> JOINT_POSITION_MOTORS = new ConcurrentHashMap<>();

    public static Map<Long, JointSpec> jointSpecs() { return Collections.unmodifiableMap(JOINT_SPECS); }

    public static float jointAnchorError(long jointId) {
        JointSpec spec = JOINT_SPECS.get(jointId);
        if (spec == null) return Float.NaN;
        float[] posA = CACHED_POS.get(spec.a());
        float[] rotA = CACHED_ROT.get(spec.a());
        if (posA == null || rotA == null) return Float.NaN;
        float[] a = localToWorld(
            spec.anchorA()[0], spec.anchorA()[1], spec.anchorA()[2], posA, rotA);
        float[] b;
        if (spec.b() == 0L) {
            b = spec.anchorB();
        } else {
            float[] posB = CACHED_POS.get(spec.b());
            float[] rotB = CACHED_ROT.get(spec.b());
            if (posB == null || rotB == null) return Float.NaN;
            b = localToWorld(
                spec.anchorB()[0], spec.anchorB()[1], spec.anchorB()[2], posB, rotB);
        }
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static long createRevoluteJoint(long kontraA, long kontraB,
                                           float ax, float ay, float az,
                                           float bx, float by, float bz,
                                           float axisX, float axisY, float axisZ) {
        return makeJoint(kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ, false);
    }

    public static long createPrismaticJoint(long kontraA, long kontraB,
                                            float ax, float ay, float az,
                                            float bx, float by, float bz,
                                            float axisX, float axisY, float axisZ) {
        return makeJoint(kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ, true);
    }

    public static long createWorldRevoluteJoint(long kontraId,
                                                float localX, float localY, float localZ,
                                                float worldX, float worldY, float worldZ,
                                                float axisX, float axisY, float axisZ) {
        return makeJoint(kontraId, 0L, localX, localY, localZ, worldX, worldY, worldZ,
            axisX, axisY, axisZ, false);
    }

    // HEADS UP on sign: the world end becomes rapier's body2, so the joint measures the ANCHOR moving
    // relative to your kontra. a positive motor target slides the kontra along -axis, and limits read
    // the same way. negate both if you want "positive = my body goes along +axis".
    public static long createWorldPrismaticJoint(long kontraId,
                                                 float localX, float localY, float localZ,
                                                 float worldX, float worldY, float worldZ,
                                                 float axisX, float axisY, float axisZ) {
        return makeJoint(kontraId, 0L, localX, localY, localZ, worldX, worldY, worldZ,
            axisX, axisY, axisZ, true);
    }

    private static long makeJoint(long kontraA, long kontraB,
                                  float ax, float ay, float az, float bx, float by, float bz,
                                  float axisX, float axisY, float axisZ, boolean prismatic) {
        KontraEntry a = KONTRAS.get(kontraA);
        KontraEntry b = kontraB == 0L ? null : KONTRAS.get(kontraB);
        if (a == null || (kontraB != 0L && b == null)) return -1L;
        if (b != null && a.worldHandle() != b.worldHandle()) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] joint across physics worlds ({} vs {}) — nope", kontraA, kontraB);
            return -1L;
        }
        long jid = prismatic
            ? KoperPhysBridge.createPrismaticJoint(a.worldHandle(), kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ)
            : KoperPhysBridge.createRevoluteJoint(a.worldHandle(), kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ);
        if (jid > 0) {
            JOINT_WORLDS.put(jid, a.worldHandle());
            JOINT_ENDS.put(jid, new long[]{kontraA, kontraB});
            JOINT_SPECS.put(jid, new JointSpec(kontraA, kontraB,
                new float[]{ax, ay, az}, new float[]{bx, by, bz}, new float[]{axisX, axisY, axisZ},
                prismatic, 0f, 0f, Float.NaN, Float.NaN));
        }
        return jid;
    }

    // revolute: rad/s + max torque. prismatic: blocks/s + max force. vel 0 + big torque = handbrake
    public static boolean setJointMotor(long jointId, float targetVel, float maxForce) {
        if (!Float.isFinite(targetVel) || !Float.isFinite(maxForce) || maxForce < 0f) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] rejected invalid motor command for joint {}: velocity={}, force={}",
                jointId, targetVel, maxForce);
            return false;
        }
        Long wh = JOINT_WORLDS.get(jointId);
        if (wh == null) return false;
        JointSpec current = JOINT_SPECS.get(jointId);
        if (!JOINT_POSITION_MOTORS.containsKey(jointId) && current != null
                && Float.floatToIntBits(current.motorVel()) == Float.floatToIntBits(targetVel)
                && Float.floatToIntBits(current.motorForce()) == Float.floatToIntBits(maxForce))
            return true;
        KoperPhysBridge.jointSetMotor(wh, jointId, targetVel, maxForce);
        JOINT_POSITION_MOTORS.remove(jointId);
        JOINT_SPECS.computeIfPresent(jointId, (k, s) -> new JointSpec(
            s.a(), s.b(), s.anchorA(), s.anchorB(), s.axis(), s.prismatic(), targetVel, maxForce,
            s.limitMin(), s.limitMax()));
        return true;
    }

    // Rapier position servo. Revolute targets are radians, prismatic targets are blocks.
    public static boolean setJointMotorPosition(long jointId, float target, float stiffness,
                                                float damping, float maxForce) {
        return setJointMotorPosition(jointId, target, stiffness, damping, maxForce, false);
    }

    /** Force-based position servo: cargo mass changes its displacement, suitable for springs. */
    public static boolean setJointMotorPositionForceBased(long jointId, float target, float stiffness,
                                                          float damping, float maxForce) {
        return setJointMotorPosition(jointId, target, stiffness, damping, maxForce, true);
    }

    private static boolean setJointMotorPosition(long jointId, float target, float stiffness,
                                                 float damping, float maxForce, boolean forceBased) {
        if (!Float.isFinite(target) || !Float.isFinite(stiffness)
                || !Float.isFinite(damping) || !Float.isFinite(maxForce)
                || stiffness < 0f || damping < 0f || maxForce < 0f) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                "[KoperPhys] rejected invalid position motor for joint {}: target={}, stiffness={}, damping={}, force={}",
                jointId, target, stiffness, damping, maxForce);
            return false;
        }
        Long wh = JOINT_WORLDS.get(jointId);
        if (wh == null) return false;
        PositionMotor current = JOINT_POSITION_MOTORS.get(jointId);
        PositionMotor wanted = new PositionMotor(target, stiffness, damping, maxForce, forceBased);
        if (wanted.equals(current)) return true;
        if (forceBased)
            KoperPhysBridge.jointSetMotorPositionForceBased(
                wh, jointId, target, stiffness, damping, maxForce);
        else
            KoperPhysBridge.jointSetMotorPosition(
                wh, jointId, target, stiffness, damping, maxForce);
        JOINT_POSITION_MOTORS.put(jointId, wanted);
        return true;
    }

    // Limits use radians for revolute joints and blocks for prismatic joints.
    public static boolean setJointLimits(long jointId, float min, float max) {
        if (!Float.isFinite(min) || !Float.isFinite(max) || min > max) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] rejected invalid limits for joint {}: min={}, max={}",
                jointId, min, max);
            return false;
        }
        Long wh = JOINT_WORLDS.get(jointId);
        if (wh == null) return false;
        JointSpec current = JOINT_SPECS.get(jointId);
        if (current != null
                && Float.floatToIntBits(current.limitMin()) == Float.floatToIntBits(min)
                && Float.floatToIntBits(current.limitMax()) == Float.floatToIntBits(max))
            return true;
        KoperPhysBridge.jointSetLimits(wh, jointId, min, max);
        JOINT_SPECS.computeIfPresent(jointId, (k, s) -> new JointSpec(
            s.a(), s.b(), s.anchorA(), s.anchorB(), s.axis(), s.prismatic(),
            s.motorVel(), s.motorForce(), min, max));
        return true;
    }

    public static boolean clearJointLimits(long jointId) {
        Long wh = JOINT_WORLDS.get(jointId);
        if (wh == null) return false;
        KoperPhysBridge.jointClearLimits(wh, jointId);
        JOINT_SPECS.computeIfPresent(jointId, (k, s) -> new JointSpec(
            s.a(), s.b(), s.anchorA(), s.anchorB(), s.axis(), s.prismatic(),
            s.motorVel(), s.motorForce(), Float.NaN, Float.NaN));
        return true;
    }

    public static void destroyJoint(long jointId) {
        if (JOINT_SPECS.containsKey(jointId))
            for (var hook : KoperPhysicsEvents.ON_JOINT_DESTROY) {
                try { hook.accept(jointId); }
                catch (Throwable error) { com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] joint destroy hook failed", error); }
            }
        Long wh = JOINT_WORLDS.remove(jointId);
        JOINT_ENDS.remove(jointId);
        JOINT_SPECS.remove(jointId);
        JOINT_POSITION_MOTORS.remove(jointId);
        if (wh != null) KoperPhysBridge.destroyJoint(wh, jointId);
    }

    // [angle rad | slide blocks, velocity along axis, angle counted past +-pi] — wheels read their spin
    // speed off this. the third one is missing on backends that don't count turns (elpe)
    // null while the async create hasn't landed yet (poll next tick) or after the joint died
    public static float[] jointState(long jointId) {
        Long wh = JOINT_WORLDS.get(jointId);
        return wh == null ? null : KoperPhysBridge.jointState(wh, jointId);
    }

    // both kontra ids of a live joint, [a, b] — addons rebuild their graphs from this after splits
    public static long[] jointEnds(long jointId) {
        long[] e = JOINT_ENDS.get(jointId);
        return e == null ? null : e.clone();
    }

    // world-space point. off-centre hits get the spin Rapier calculates from the real mass properties.
    public static boolean applyImpulseAtPoint(long kontraId,
                                              float ix, float iy, float iz,
                                              float px, float py, float pz) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return false;
        KoperPhysBridge.applyImpulseAtPoint(data.worldHandle(), kontraId, ix, iy, iz, px, py, pz);
        return true;
    }

    public static boolean applyAttackImpulse(ServerPlayer player, long kontraId,
                                             int lx, int ly, int lz,
                                             float hitX, float hitY, float hitZ) {
        KontraEntry data = KONTRAS.get(kontraId);
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.get(kontraId);
        if (data == null || pos == null || rot == null || player == null) return false;
        // creative does NOT shove. you are building in there, and a machine that jumps every time
        // you tap it is unusable. punching things around is a survival thing
        if (player.isCreative()) return false;
        BlockPos local = new BlockPos(lx, ly, lz);
        float[] off = data.blockOffsets.get(local);
        float ox = off != null ? off[0] : lx;
        float oy = off != null ? off[1] : ly;
        float oz = off != null ? off[2] : lz;
        float[] point = localToWorld(ox + hitX, oy + hitY, oz + hitZ, pos, rot);
        Vec3 look = player.getLookAngle().normalize();
        float strength = 14f;
        return applyImpulseAtPoint(kontraId,
            (float)look.x * strength, (float)look.y * strength, (float)look.z * strength,
            point[0], point[1], point[2]);
    }

    // Rust drops the joints with the body; here we just forget the records + tell addons via ON_DESTROY
    private static void dropJointsOf(long kontraId) {
        JOINT_ENDS.entrySet().removeIf(en -> {
            if (en.getValue()[0] != kontraId && en.getValue()[1] != kontraId) return false;
            for (var hook : KoperPhysicsEvents.ON_JOINT_DESTROY) {
                try { hook.accept(en.getKey()); }
                catch (Throwable error) { com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] joint destroy hook failed", error); }
            }
            JOINT_WORLDS.remove(en.getKey());
            JOINT_SPECS.remove(en.getKey());
            JOINT_POSITION_MOTORS.remove(en.getKey());
            return true;
        });
    }

    // one knob for how hard water pushes up (displaced mass per full block). 1.0 default
    public static void setWaterDensity(ServerLevel level, float density) {
        long wh = getWorldHandle(level);
        if (wh > 0) KoperPhysBridge.setWaterDensity(wh, density);
    }

    // ── seats ────────────────────────────────────────────────────────────────

    // spawn the official mount at LOCAL kontra coords (same space as KontraEntry.blockOffsets).
    // returns null if the kontra doesn't live here. addon then player.startRiding(seat, true)
    public static KontraSeat spawnSeat(ServerLevel level, long kontraId, float lx, float ly, float lz) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null || !data.levelKey().equals(levelKey(level))) return null;
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
        if (pos == null) return null;
        KontraSeat seat = new KontraSeat(KontraSeat.TYPE, level);
        seat.bindTo(kontraId, lx, ly, lz);
        float[] w = localToWorld(lx, ly, lz, pos, rot);
        seat.setPos(w[0], w[1], w[2]);
        if (!level.addFreshEntity(seat)) return null;
        return seat;
    }

    // mode null = drop the override, the kontra follows the global default again
    public static boolean setAeroMode(long kontraId, AeroMode mode) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return false;
        data.setAeroMode(mode);
        KoperPhysBridge.setAeroMode(data.worldHandle(), kontraId, data.aeroMode().id());
        return true;
    }

    public static AeroMode defaultAeroMode() {
        AeroMode mode = AeroMode.parse(KhysicsConfig.get().defaultAeroMode);
        return mode != null ? mode : AeroMode.CORRECT;
    }

    // new global default, pushed at once to every kontra without its own override
    public static int setDefaultAeroMode(AeroMode mode) {
        KhysicsConfig.get().defaultAeroMode = mode.key();
        KhysicsConfig.save();
        int moved = 0;
        for (var entry : KONTRAS.entrySet()) {
            if (entry.getValue().aeroOverride() != null) continue;
            KoperPhysBridge.setAeroMode(entry.getValue().worldHandle(), entry.getKey(), mode.id());
            moved++;
        }
        return moved;
    }

    public static boolean setBodyDamping(long kontraId, float linear, float angular) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null || !Float.isFinite(linear) || !Float.isFinite(angular)
                || linear < 0f || angular < 0f) return false;
        KoperPhysBridge.setDamping(data.worldHandle(), kontraId, linear, angular);
        return true;
    }

    public static boolean setBodyVelocity(long kontraId, float x, float y, float z) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            return false;
        KoperPhysBridge.setVelocity(data.worldHandle(), kontraId, x, y, z);
        return true;
    }

    public static boolean setBodyParked(long kontraId, boolean parked) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return false;
        KoperPhysBridge.setParked(data.worldHandle(), kontraId, parked);
        return true;
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    private static long tickCounter = 0L;
    private static volatile boolean physicsPaused = false;
    // when paused + step requested, run exactly one tick then re-pause
    private static volatile boolean doOneStep = false;

    public static void setPaused(boolean paused) { physicsPaused = paused; }
    public static boolean isPaused()             { return physicsPaused; }
    public static void requestStep()             { doOneStep = true; }

    public static void tickPlayerKontraInteractions(MinecraftServer server) {
        if (KONTRAS.isEmpty()) return;
        if (physicsPaused && !doOneStep) return;
        pilotShips(server);
        flightStickLoop(server);
    }

    private static boolean bootBannerShown = false;

    private static String fmtMs(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public static void tickAll(MinecraftServer server) {
        if (KONTRAS.isEmpty()) return;
        if (physicsPaused && !doOneStep) return;
        long tickStart = System.nanoTime();
        try {
            tickAll0(server);
            com.koper.koper_lib.physics.body.KhysPushers.serverTick();
        } finally {
            serverTickNanos += System.nanoTime() - tickStart;
            if (++serverTickSamples >= 100) {
                serverTickMs = (float)(serverTickNanos / 1.0e6 / serverTickSamples);
                serverTickNanos = 0L;
                serverTickSamples = 0;
            }
        }
    }

    private static long serverTickNanos;
    private static int serverTickSamples;
    private static volatile float serverTickMs;

    // what the kontraption bookkeeping costs the SERVER thread each tick (physics itself runs on its
    // own thread — if this number is small and the game still stutters, the cost is elsewhere)
    public static float serverTickCostMs() { return serverTickMs; }

    // bodies whose last block went away through the grid (an explosion, a piston, a script setBlock).
    // breakBlock destroys its body on the spot, the grid path is inside a block update and can't, so
    // it lands here and goes at the start of the next tick. left alone it was a body of nothing: no
    // collider, still jointed, and dropped from the save while everything wired to it stayed
    private static final Set<Long> EMPTIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void emptied(long kontraId) { EMPTIED.add(kontraId); }

    private static void tickAll0(MinecraftServer server) {
        doOneStep = false;
        tickCounter++;
        if (!EMPTIED.isEmpty()) {
            for (long id : List.copyOf(EMPTIED)) {
                EMPTIED.remove(id);
                KontraEntry emptied = KONTRAS.get(id);
                if (emptied != null && emptied.blocks.isEmpty()) destroyKontraktion(server, id);
            }
            if (KONTRAS.isEmpty()) return;
        }
        if (!bootBannerShown) {
            bootBannerShown = true;
            // build marker — if this line is missing from the log, the game is running a STALE build
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys] build marker: r20-real-packets");
        }

        // only poll worlds that actually have kontraktions — empty worlds spam 0-transforms warning
        Set<Long> handles = new HashSet<>();
        Map<Long, ServerLevel> handleLevels = new HashMap<>();
        for (KontraEntry d : KONTRAS.values()) {
            if (handles.add(d.worldHandle())) {
                ServerLevel lvl = findLevel(server, d.levelKey());
                if (lvl != null) handleLevels.put(d.worldHandle(), lvl);
            }
        }

        // heartbeat the physics threads so they keep stepping. this only runs while the game is ticking,
        // so a pause (or manual physics-pause above) stops the pings → the threads freeze → nothing drifts
        for (long wh : handles) if (wh > 0) KoperPhysBridge.heartbeat(wh);

        // where the physics second actually goes. debugMode only — two array reads a second
        if (tickCounter % 100 == 0 && com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
            for (long wh : handles) {
                if (wh <= 0) continue;
                float[] p = KoperPhysBridge.profile(wh);
                if (p == null) continue;
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                    "[KhysProfile] world={} kontras={} step={}ms (sections {} | fluid+aero {} | solver {} | joints {} | post {}) serverTick={}ms",
                    wh, KONTRAS.size(), fmtMs(p[0]), fmtMs(p[1]), fmtMs(p[2]), fmtMs(p[3]), fmtMs(p[4]), fmtMs(p[5]),
                    fmtMs(serverTickMs));
            }
        }

        // feed whatever terrain sections the physics is missing — budgeted inside
        for (var he : handleLevels.entrySet()) {
            if (he.getKey() > 0) com.koper.koper_lib.physics.terrain.TerrainSlurper.pump(he.getValue(), he.getKey());
        }

        // group by level key so updates only go to players in the right dimension
        Map<String, List<Long>>    idsByLevel  = new HashMap<>();
        Map<String, List<float[]>> tfsByLevel  = new HashMap<>();
        List<Long> lost = null; // kontras that escaped to NaN/inf or way below the world → cull after

        for (long wh : handles) {
            if (wh <= 0) continue;
            int maxK = KONTRAS.size() + 4;
            float[] buf = new float[maxK * KoperPhysBridge.TRANSFORM_STRIDE];
            int count   = KoperPhysBridge.getAllTransforms(wh, buf, maxK);
            if (count == 0 && tickCounter % 100 == 1) com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] getAllTransforms handle={} returned 0 — physics thread dead or snapshot empty", wh);

            for (int i = 0; i < count; i++) {
                int b = i * KoperPhysBridge.TRANSFORM_STRIDE;
                long id = ((long) Float.floatToRawIntBits(buf[b]) & 0xFFFFFFFFL)
                        | (((long) Float.floatToRawIntBits(buf[b+1]) & 0xFFFFFFFFL) << 32);
                float[] pos = { buf[b+2], buf[b+3], buf[b+4] };
                float[] rot = { buf[b+5], buf[b+6], buf[b+7], buf[b+8] };
                boolean aligned = buf[b+9] > 0.5f;

                // escaped to NaN/inf or fell miles out of the world → it's lost. don't cache it (a broken
                // pos feeds getEntities a NaN AABB and overflows MC's section longs → server crash); cull it.
                if (!Float.isFinite(pos[0]) || !Float.isFinite(pos[1]) || !Float.isFinite(pos[2])
                        || pos[1] < -2048f || pos[1] > 6000f) {
                    if (lost == null) lost = new ArrayList<>();
                    lost.add(id);
                    continue;
                }

                float[] oldPos = CACHED_POS.get(id);
                float[] oldRot = CACHED_ROT.get(id);
                if (oldPos != null) {
                    double jx = pos[0] - oldPos[0], jy = pos[1] - oldPos[1], jz = pos[2] - oldPos[2];
                    double jump = Math.sqrt(jx * jx + jy * jy + jz * jz);
                    if (jump > KONTRA_EMERGENCY_DELTA_BT) {
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] kontra {} overspeed delta/tick={} - zeroing velocity", id, String.format("%.2f", jump));
                        KoperPhysBridge.setTransform(wh, id, pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3]);
                        LAST_KONTRA_DELTA.put(id, new float[]{0f, 0f, 0f});
                    }
                }

                CACHED_POS.put(id, pos);
                CACHED_ROT.put(id, rot);

                KontraEntry kd = KONTRAS.get(id);
                if (kd != null) {
                    boolean wasAligned = kd.aligned;
                    kd.aligned = aligned;
                    if (aligned && !wasAligned) {
                        ServerLevel plvl = handleLevels.get(wh);
                        if (plvl != null) settleRidersOnParked(plvl, id, kd, pos, rot);
                    }
                    ServerLevel logicLevel = handleLevels.get(wh);
                    if (logicLevel != null) {
                        // moved this tick? then its cells just reprojected — not a redstone edit
                        boolean moving = oldPos == null || oldRot == null
                            || Math.abs(pos[0]-oldPos[0]) > 1e-3f || Math.abs(pos[1]-oldPos[1]) > 1e-3f
                            || Math.abs(pos[2]-oldPos[2]) > 1e-3f
                            || Math.abs(rot[0]-oldRot[0]) > 1e-4f || Math.abs(rot[1]-oldRot[1]) > 1e-4f
                            || Math.abs(rot[2]-oldRot[2]) > 1e-4f || Math.abs(rot[3]-oldRot[3]) > 1e-4f;
                        refreshLogicContacts(logicLevel, id, kd, pos, rot, moving);
                    }
                }
                String lk = kd != null ? kd.levelKey() : null;
                if (lk != null) {
                    idsByLevel .computeIfAbsent(lk, k -> new ArrayList<>()).add(id);
                    tfsByLevel .computeIfAbsent(lk, k -> new ArrayList<>())
                               .add(new float[]{ pos[0],pos[1],pos[2], rot[0],rot[1],rot[2],rot[3], aligned ? 1f : 0f });
                }
                if (!KoperPhysicsEvents.ON_TICK.isEmpty())
                    KoperPhysicsEvents.fireTick(id, pos, rot, new float[]{0f, 0f, 0f});
            }
        }

        if (lost != null) for (long id : lost) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] kontra {} escaped (NaN/out of world) — culling it", id);
            destroyKontraktion(server, id);
        }

        poseLedger(server);

        // ── joint stretch watchdog ──────────────────────────────────────────────────────────────
        // A bearing that stops being enforced lets its two bodies sag apart under gravity, and the
        // next solve that DOES see the joint fires them across the map. That is why re-seating a
        // broken build on a lift repairs it: layoutAssembly rebuilds every child position from the
        // joint anchors instead of trusting the drifted pose. So say something the moment a joint
        // starts stretching — the tick it happens on is the tick that broke the machine.
        for (var stretchEntry : JOINT_SPECS.entrySet()) {
            long jointId = stretchEntry.getKey();
            float error = jointAnchorError(jointId);
            if (!Float.isFinite(error)) continue;
            if (error > JOINT_STRETCH_WARN) {
                if (JOINT_STRETCHED.add(jointId)) {
                    JointSpec spec = stretchEntry.getValue();
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                        "[KoperPhys] joint {} stretched to {} blocks (a={} b={} prismatic={}) — the bearing stopped holding",
                        jointId, String.format("%.3f", error), spec.a(), spec.b(), spec.prismatic());
                }
            } else if (error < JOINT_STRETCH_WARN * 0.5f) {
                JOINT_STRETCHED.remove(jointId);
            }
        }

        // half-under-map diag: once per kontra, when it stops moving, log where the lowest block bottom
        // ends up. equal to ground top = physics rests correctly (so any visual sink is a render offset)
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
            for (var e : KONTRAS.entrySet()) {
                long id = e.getKey();
                if (DIAG_SETTLED.contains(id)) continue;
                float[] pos = CACHED_POS.get(id);
                float[] vel = getKontraVelocity(id);
                if (pos == null || vel == null) continue;
                if (Math.sqrt(vel[0]*vel[0]+vel[1]*vel[1]+vel[2]*vel[2]) > 0.01) continue; // still moving
                float minOy = Float.MAX_VALUE;
                for (float[] o : e.getValue().blockOffsets.values()) if (o[1] < minOy) minOy = o[1];
                DIAG_SETTLED.add(id);
                double bottom = pos[1] + minOy - 0.5;
                // probe the first real-terrain top directly under the lowest block — sink>0 means sunk in
                ServerLevel dlvl = findLevel(server, e.getValue().levelKey());
                int terrTop = Integer.MIN_VALUE;
                if (dlvl != null) {
                    TERRAIN_SCAN_ACTIVE.set(true);
                    try {
                        int bx = Math.round(pos[0]), bz = Math.round(pos[2]);
                        for (int y = (int)Math.floor(bottom)+1; y >= (int)Math.floor(bottom)-5; y--) {
                            BlockPos bp = new BlockPos(bx, y, bz);
                            BlockState s = dlvl.getBlockState(bp);
                            if (!s.isAir() && !s.getCollisionShape(dlvl, bp).isEmpty()) { terrTop = y + 1; break; }
                        }
                    } finally { TERRAIN_SCAN_ACTIVE.set(false); }
                }
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys][diag] kontra {} ({} blk) bodyY={} blockBottomY={} terrainTopBelow={} sink={}",
                    id, e.getValue().blockCount(), String.format("%.3f", pos[1]), String.format("%.3f", bottom),
                    terrTop == Integer.MIN_VALUE ? "?" : terrTop,
                    terrTop == Integer.MIN_VALUE ? "?" : String.format("%.3f", terrTop - bottom));
            }
        }

        // Loaded bodies use a tight settle threshold; unloaded ones get the relaxed four-second mode.
        // Only send on chunk-state changes because touching activation every tick resets its timer.
        if (tickCounter % 20 == 7) {
            for (var e : KONTRAS.entrySet()) {
                long kontraId = e.getKey();
                KontraEntry data = e.getValue();
                float[] pos = CACHED_POS.get(kontraId);
                if (pos == null) continue;
                ServerLevel lvl = findLevel(server, data.levelKey());
                if (lvl == null) continue;
                boolean loaded = lvl.isLoaded(BlockPos.containing(pos[0], pos[1], pos[2]));
                boolean sleepAllowed = !loaded;
                Boolean prev = KONTRA_SLEEP_STATE.get(kontraId);
                if (prev == null || prev != sleepAllowed) {
                    KONTRA_SLEEP_STATE.put(kontraId, sleepAllowed);
                    KoperPhysBridge.setKontraSleepAllowed(data.worldHandle(), kontraId, sleepAllowed);
                }
            }
        }

        // drain connectivity splits from Rust — spawn new kontraktions for disconnected chunks
        processSplits(server);

        for (var le : idsByLevel.entrySet()) {
            ServerLevel lvl = findLevel(server, le.getKey());
            if (lvl == null || lvl.players().isEmpty()) continue;
            List<Long>    levelIds = le.getValue();
            List<float[]> levelTfs = tfsByLevel.get(le.getKey());
            long[]    idArr = levelIds.stream().mapToLong(Long::longValue).toArray();
            float[][] tArr  = levelTfs.toArray(new float[0][]);
            KoperNetworking.broadcastToLevel(lvl, new KenderUpdatePayload(tickCounter, idArr, tArr));
        }

        // ghost sweeper — once a second every client gets told what each kontra should hash to.
        // whatever swallowed a block update (dropped broadcast, split race, BE tick that threw
        // mid-setBlock) the client notices within ~2s and asks for the real list. self-healing beats
        // hunting the next path that forgets to broadcast, we already lost that game twice
        if (tickCounter % 20 == 13 && server != null) broadcastStamps(server);

        // tick block entities with world-pos swap so sounds/progress work at correct location
        for (var ke : KONTRAS.entrySet()) {
            KontraEntry data = ke.getValue();
            long kontraId = ke.getKey();
            float[] pos = CACHED_POS.get(kontraId);
            float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
            if (pos == null) continue;
            ServerLevel lvl = findLevel(server, data.levelKey());
            if (lvl == null) continue;

            KontraGridContext.run(data.grid(kontraId), () -> data.grid(kontraId).tick(lvl));

            KontraGrid beGrid = data.grid(kontraId);
            for (BlockEntity be : data.snapshotBlockEntities()) {
                if (be == null) break;
                BlockPos localPos = beGrid.toLocal(be.getBlockPos());
                if (be == null || data.blockEntities.get(localPos) != be || be.isRemoved()) continue;
                BlockState state = data.blocks.get(localPos);
                if (state == null) continue;

                @SuppressWarnings("unchecked")
                BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>)
                    state.getTicker(lvl, (BlockEntityType<BlockEntity>) be.getType());
                if (ticker == null) continue;

                BlockPos gridPos = data.grid(kontraId).toGrid(localPos);
                try { KontraGridContext.run(data.grid(kontraId), () -> ticker.tick(lvl, gridPos, state, be)); }
                catch (Throwable ex) {
                    String errorKey = be.getType() + ":" + ex.getClass().getName();
                    if (BE_TICK_ERRORS.add(errorKey))
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KoperPhys] kontra BE tick failed for {} at {}", be.getType(), gridPos, ex);
                }
            }
        }
    }

    // ── break / place ─────────────────────────────────────────────────────────

    public static boolean breakBlockAt(ServerLevel level, ServerPlayer player, BlockPos worldPos) {
        String key = levelKey(level);
        for (var e : KONTRAS.entrySet()) {
            KontraEntry data = e.getValue();
            if (!data.levelKey().equals(key)) continue;
            float[] pos = CACHED_POS.get(e.getKey());
            float[] rot = CACHED_ROT.get(e.getKey());
            if (pos == null || rot == null) continue;
            // worldPos is the clicked block — use it directly for drops/particles
            BlockPos local = worldPosToLocal(worldPos, pos, rot);
            if (removeKontraBlock(level, player, e.getKey(), data, local, worldPos, pos, rot)) return true;
        }
        return false;
    }

    // client broke block locally. boom, it flies away into the void
    public static boolean breakBlockAtLocal(ServerLevel level, ServerPlayer player, long kontraId, int lx, int ly, int lz) {
        return breakBlockAtLocal(level, player, kontraId, lx, ly, lz,
            net.minecraft.core.Direction.UP.ordinal(), 0f, 0f, 0f);
    }

    public static boolean breakBlockAtLocal(ServerLevel level, ServerPlayer player, long kontraId,
                                            int lx, int ly, int lz, int directionOrdinal,
                                            float hitX, float hitY, float hitZ) {
        KoperPhysicsEvents.GridHit detailedHit = player != null
            ? resolveGridHit(player, new com.koper.koper_lib.network.KenderHitRef(
                kontraId, new BlockPos(lx, ly, lz), directionOrdinal, hitX, hitY, hitZ))
            : null;
        if (player != null && detailedHit == null) return false;
        KontraEntry data = detailedHit != null ? detailedHit.entry() : KONTRAS.get(kontraId);
        if (data == null) return false;
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.get(kontraId);
        if (pos == null || rot == null) return false;

        BlockPos local = new BlockPos(lx, ly, lz);
        // derive world pos from the float offset — integer local loses 0.5 precision
        float[] off = data.blockOffsets.get(local);
        float[] w = localToWorld(off != null ? off[0] : lx, off != null ? off[1] : ly, off != null ? off[2] : lz, pos, rot);
        BlockPos worldPos = new BlockPos((int)Math.floor(w[0]), (int)Math.floor(w[1]), (int)Math.floor(w[2]));
        if (detailedHit != null && KoperPhysicsEvents.fireDetailedGridAttack(player, detailedHit)) return true;
        return removeKontraBlock(level, player, kontraId, data, local, worldPos, pos, rot);
    }

    // shared removal: drop loot (unless creative), strip from maps, tell Rust, break fx, then destroy-or-respawn
    private static boolean removeKontraBlock(ServerLevel level, ServerPlayer player, long kontraId, KontraEntry data,
                                             BlockPos local, BlockPos worldPos, float[] pos, float[] rot) {
        BlockState state = data.blocks.get(local);
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] break kontra={} local={} worldPos={} hit={}",
                kontraId, local, worldPos, state != null ? state.getBlock() : "MISS");
        if (state == null) return false;
        // an addon may protect its block (bearing under load etc). true = the hit "landed" but nothing breaks
        if (player != null && KoperPhysicsEvents.fireGridAttack(player, kontraId, local, state)) return true;
        KontraGrid dropGrid = data.grid(kontraId);
        BlockPos gridPos = dropGrid.toGrid(local);
        net.minecraft.world.item.ItemStack liveTool = player == null
            ? net.minecraft.world.item.ItemStack.EMPTY : player.getMainHandItem();
        if (player != null && !player.isCreative()
                && !KontraGridContext.call(dropGrid,
                    () -> liveTool.canDestroyBlock(state, level, gridPos, player)))
            return false;
        net.minecraft.world.item.ItemStack minedWith = liveTool.copy();
        boolean drops = player != null && !player.isCreative() && player.hasCorrectToolForDrops(state);
        BlockEntity minedBlockEntity = data.blockEntities.get(local);
        // mark this world cell so the vanilla STOP_DESTROY can't double-break it (see ServerBreakMixin)
        RECENT_KONTRA_BREAKS.entrySet().removeIf(en -> tickCounter - en.getValue() > 20);
        RECENT_KONTRA_BREAKS.put(worldPos.asLong(), tickCounter);
        data.blocks.remove(local);
        data.assembledFrom.remove(local);
        data.localData.remove(local);
        removeLogicalCell(kontraId, data, local);
        BlockEntity removedBe = data.blockEntities.remove(local);
        if (removedBe != null) {
            // chest contents spill lives in preRemoveSideEffects — we never called it, so mined chests
            // kept their loot. run it IN grid ctx so the spilled items remap onto the kontra, not the anchor.
            KontraGridContext.run(dropGrid, () ->
                removedBe.preRemoveSideEffects(dropGrid.toGrid(local), state));
            removedBe.setRemoved();
        }
        if (player != null && !player.isCreative()) {
            KontraGridContext.run(dropGrid, () ->
                liveTool.mineBlock(level, state, gridPos, player));
            if (drops) KontraGridContext.run(dropGrid, () ->
                state.getBlock().playerDestroy(level, player, gridPos, state,
                    minedBlockEntity, minedWith));
        }
        // Rust keys colliders by jround(COM offset), NOT grid locals — passing locals here removed the
        // collider of whatever block sits a centroid-shift away (usually the one BELOW) or missed
        // entirely leaving a ghost. THE "mine a BE, floor under it stops colliding" bug.
        float[] minedOff = data.blockOffsets.remove(local);
        data.invalidateSolidCells();
        data.invalidateLogicCells();
        if (minedOff != null)
            KoperPhysBridge.removeBlockAtOffset(data.worldHandle(), kontraId,
                Math.round(minedOff[0]), Math.round(minedOff[1]), Math.round(minedOff[2]));
        KoperPhysicsEvents.fireBlockBreak(kontraId, local, state);
        // levelEvent 2001 = break particles + sound on the client
        level.levelEvent(2001, worldPos, Block.getId(state));
        // doors / beds / tall plants are 2 blocks — break the partner half too (vanilla does), else it floats
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos partner = half == DoubleBlockHalf.LOWER ? local.above() : local.below();
            BlockState ps = data.blocks.get(partner);
            if (ps != null && ps.getBlock() == state.getBlock()) {
                data.blocks.remove(partner);
                data.assembledFrom.remove(partner);
                data.localData.remove(partner);
                removeLogicalCell(kontraId, data, partner);
                BlockEntity partnerBe = data.blockEntities.remove(partner);
                if (partnerBe != null) partnerBe.setRemoved();
                float[] partnerOff = data.blockOffsets.remove(partner);
                data.invalidateSolidCells();
                data.invalidateLogicCells();
                if (partnerOff != null)
                    KoperPhysBridge.removeBlockAtOffset(data.worldHandle(), kontraId,
                        Math.round(partnerOff[0]), Math.round(partnerOff[1]), Math.round(partnerOff[2]));
                KoperPhysicsEvents.fireBlockBreak(kontraId, partner, ps);
            }
        }
        if (data.blocks.isEmpty()) destroyKontraktion(level.getServer(), kontraId);
        else {
            data.grid(kontraId).finishBlockChange(level, local, state, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL, Block.UPDATE_LIMIT);
            gridBlockChanged(level, kontraId, data, local, state, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            pushAero(kontraId, data);
        }
        return true;
    }

    // rebuild + broadcast KenderSpawnPayload (client clears old state + respawns)
    private static void respawnWithNewBlocks(MinecraftServer server, long kontraId, KontraEntry data, float[] pos, float[] rot) {
        var ra = data.renderArrays();
        data.rebuildRenderData(ra.offsets(), ra.states());
        ServerLevel lvl = findLevel(server, data.levelKey());
        var payload = new KenderSpawnPayload(kontraId, pos, rot, ra.stateIds(), ra.offsets(), ra.locals(),
            lvl != null ? blockEntityTags(data, lvl) : new net.minecraft.nbt.CompoundTag[ra.stateIds().length]);
        if (lvl != null) KhysicsNetworking.broadcastSpawn(lvl, payload);
        else KhysicsNetworking.broadcastSpawn(server, payload);
    }

    public static BlockState getLogicalBlockStateAt(ServerLevel level, BlockPos gridPos) {
        var index = LOGICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? index.get(gridPos.asLong()) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? data.blocks.get(cell.local()) : null;
    }

    public static BlockEntity getLogicalBlockEntityAt(ServerLevel level, BlockPos gridPos) {
        var index = LOGICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? index.get(gridPos.asLong()) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? data.blockEntities.get(cell.local()) : null;
    }

    // THIS cell and nothing else. gridAtLogical below also answers for the six neighbours, which
    // is what some Create probes want — but anything deciding "is this block part of a kontra"
    // must not use it, or a plain world block touching a kontraption gets routed into the grid:
    // the server quietly files the change in the kontra and the client never hears about it,
    // which is the ghost block you get from mining next to a machine
    public static KontraGrid gridAtLogicalExact(ServerLevel level, BlockPos gridPos) {
        var index = LOGICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? index.get(gridPos.asLong()) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? data.grid(cell.kontraId()) : null;
    }

    public static KontraGrid gridAtLogical(ServerLevel level, BlockPos gridPos) {
        var index = LOGICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? index.get(gridPos.asLong()) : null;
        if (cell == null && index != null) {
            for (var direction : net.minecraft.core.Direction.values()) {
                cell = index.get(gridPos.relative(direction).asLong());
                if (cell != null) break;
            }
        }
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? data.grid(cell.kontraId()) : null;
    }

    public static BlockPos logicalToWorld(ServerLevel level, BlockPos gridPos) {
        var index = LOGICAL_CELL_INDEX.get(levelKey(level));
        GridCell cell = index != null ? index.get(gridPos.asLong()) : null;
        if (cell == null) return null;
        KontraEntry data = KONTRAS.get(cell.kontraId());
        return data != null ? gridToWorld(cell.kontraId(), data, cell.local()) : null;
    }

    public static BlockEntity getBlockEntityAt(ServerLevel level, BlockPos worldPos) {
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        long packed=worldPos.asLong();
        if (index == null) return null;
        BlockEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        long first=index.primary.get(packed);
        if (first != 0L) {
            GridCell cell=physicalCell(first,packed);
            KontraEntry data=cell != null ? KONTRAS.get(first) : null;
            BlockEntity be=data != null ? data.blockEntities.get(cell.local()) : null;
            if (be != null) {
                bestDistance=physicalDistanceSquared(cell,worldPos.getX()+0.5,worldPos.getY()+0.5,worldPos.getZ()+0.5);
                best=be;
            }
        }
        var more=index.overlaps.get(packed);
        if (more != null) for (int i=0;i<more.size();i++) {
            long id=more.getLong(i);
            GridCell cell=physicalCell(id,packed);
            KontraEntry data=cell != null ? KONTRAS.get(id) : null;
            BlockEntity be=data != null ? data.blockEntities.get(cell.local()) : null;
            if (be == null) continue;
            double distance=physicalDistanceSquared(cell,worldPos.getX()+0.5,worldPos.getY()+0.5,worldPos.getZ()+0.5);
            if (distance < bestDistance) { bestDistance=distance; best=be; }
        }
        return best;
    }

    public static Integer physicalSignal(ServerLevel level, BlockPos worldPos,
                                         net.minecraft.core.Direction worldDirection, boolean direct) {
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        long packed=worldPos.asLong();
        if (index == null || !index.contains(packed)) return null;
        int strongest = 0;
        boolean found = false;
        long first=index.primary.get(packed);
        var more=index.overlaps.get(packed);
        int count=1+(more != null ? more.size() : 0);
        for (int i=-1;i<count-1;i++) {
            long id=i < 0 ? first : more.getLong(i);
            GridCell cell=physicalCell(id,packed);
            if (cell == null) continue;
            KontraEntry data = KONTRAS.get(cell.kontraId());
            if (data == null) continue;
            BlockState state = data.blocks.get(cell.local());
            if (state == null) continue;
            found = true;
            KontraGrid grid = data.grid(cell.kontraId());
            BlockPos gridPos = grid.toGrid(cell.local());
            net.minecraft.core.Direction localDirection = worldDirectionToGrid(cell.kontraId(), worldDirection);
            int signal = KontraGridContext.call(grid, () -> direct
                ? state.getDirectSignal(level, gridPos, localDirection)
                : state.getSignal(level, gridPos, localDirection));
            strongest = Math.max(strongest, signal);
            if (strongest >= 15) break;
        }
        return found ? strongest : null;
    }

    public static int physicalSignalAt(ServerLevel level, BlockPos worldPos, boolean direct) {
        int strongest = 0;
        for (var direction : net.minecraft.core.Direction.values()) {
            Integer signal = physicalSignal(level, worldPos, direction, direct);
            if (signal != null) strongest = Math.max(strongest, signal);
            if (strongest >= 15) return 15;
        }
        return strongest;
    }

    public static void staticBlockChanged(ServerLevel level, BlockPos worldPos, BlockState newState) {
        if (KontraGridContext.active() != null) return;
        var index = PHYSICAL_CELL_INDEX.get(levelKey(level));
        if (index == null || index.isEmpty()) return;
        Set<GridCell> touched = new HashSet<>();
        // A rotated local neighbour can land diagonally across world cells.
        // Block changes are rare enough that 27 primitive lookups beat missing the disconnect.
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    long packed=worldPos.offset(dx, dy, dz).asLong();
                    long first=index.primary.get(packed);
                    if (first != 0L) {
                        GridCell cell=physicalCell(first,packed);
                        if (cell != null) touched.add(cell);
                    }
                    var more=index.overlaps.get(packed);
                    if (more != null) for (int i=0;i<more.size();i++) {
                        GridCell cell=physicalCell(more.getLong(i),packed);
                        if (cell != null) touched.add(cell);
                    }
                }
        for (GridCell cell : touched) {
            KontraEntry data = KONTRAS.get(cell.kontraId());
            if (data == null) continue;
            BlockState state = data.blocks.get(cell.local());
            if (state == null) continue;
            KontraGrid grid = data.grid(cell.kontraId());
            grid.invalidateExternalCache();
            KontraGridContext.run(grid, () -> state.handleNeighborChanged(level,
                grid.toGrid(cell.local()), newState.getBlock(), null, false));
        }
    }

    public static void gridBlockEntityChanged(ServerLevel level, KontraGrid grid, BlockPos gridPos) {
        BlockPos local = grid.toLocal(gridPos);
        KontraEntry data = grid.entry();
        BlockState sourceState = data.blocks.get(local);
        if (sourceState == null) return;
        for (var owner : data.logicOwners().long2ObjectEntrySet()) {
            if (!local.equals(owner.getValue())) continue;
            notifyRealComparators(level, BlockPos.of(owner.getLongKey()), sourceState.getBlock());
        }
    }

    public static void gridEntitySpawn(KontraGrid grid, Entity entity) {
        float[] local = grid.offsetForGridPoint(entity.getX(), entity.getY(), entity.getZ());
        if (local == null) return;
        long id = grid.kontraId();
        float[] pos = CACHED_POS.get(id), rot = CACHED_ROT.get(id);
        if (pos == null || rot == null) return;

        float[] world = localToWorld(local[0], local[1], local[2], pos, rot);
        Vec3 movement = entity.getDeltaMovement();
        float[] spunMovement = localToWorld((float)movement.x, (float)movement.y, (float)movement.z,
            new float[]{0f, 0f, 0f}, rot);
        float carryX=0f, carryY=0f, carryZ=0f;
        float[] linear = LAST_KONTRA_DELTA.get(id);
        if (linear != null) { carryX+=linear[0]; carryY+=linear[1]; carryZ+=linear[2]; }
        float[] previousRot = LAST_KONTRA_PREVIOUS_ROT.get(id);
        if (previousRot != null) {
            float[] nowOff = localToWorld(local[0], local[1], local[2], new float[]{0f,0f,0f}, rot);
            float[] oldOff = localToWorld(local[0], local[1], local[2], new float[]{0f,0f,0f}, previousRot);
            carryX += nowOff[0]-oldOff[0];
            carryY += nowOff[1]-oldOff[1];
            carryZ += nowOff[2]-oldOff[2];
        }

        Vec3 look = entity.getLookAngle();
        float[] worldLook = localToWorld((float)look.x, (float)look.y, (float)look.z,
            new float[]{0f,0f,0f}, rot);
        var view = new Vec3(worldLook[0], worldLook[1], worldLook[2]).rotation();
        entity.setPos(world[0], world[1], world[2]);
        entity.setDeltaMovement(spunMovement[0]+carryX, spunMovement[1]+carryY, spunMovement[2]+carryZ);
        entity.setXRot(view.x);
        entity.setYRot(view.y);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            living.setYBodyRot(view.y);
            living.setYHeadRot(view.y);
        }
    }

    public static float[] gridPointToWorld(KontraGrid grid, double x, double y, double z) {
        float[] local = grid.offsetForGridPoint(x, y, z);
        float[] pos = CACHED_POS.get(grid.kontraId()), rot = CACHED_ROT.get(grid.kontraId());
        return local != null && pos != null && rot != null
            ? localToWorld(local[0], local[1], local[2], pos, rot) : null;
    }

    public static float[] boundGridPointToWorld(KontraGrid grid, double x, double y, double z) {
        float[] local = grid.offsetForBoundGridPoint(x, y, z);
        float[] pos = CACHED_POS.get(grid.kontraId()), rot = CACHED_ROT.get(grid.kontraId());
        return pos != null && rot != null
            ? localToWorld(local[0], local[1], local[2], pos, rot) : null;
    }

    public static float[] worldPointToBoundGrid(KontraGrid grid, double x, double y, double z) {
        float[] pos = CACHED_POS.get(grid.kontraId()), rot = CACHED_ROT.get(grid.kontraId());
        if (pos == null || rot == null) return null;
        float[] local = worldToLocalPoint((float)x, (float)y, (float)z, pos, rot);
        return new float[]{
            (float)(grid.anchor().getX() + 0.5) + local[0],
            (float)(grid.anchor().getY() + 0.5) + local[1],
            (float)(grid.anchor().getZ() + 0.5) + local[2]
        };
    }

    public static float[] gridVectorToWorld(KontraGrid grid, double x, double y, double z) {
        float[] rot = CACHED_ROT.get(grid.kontraId());
        return rot != null ? localToWorld((float)x, (float)y, (float)z,
            new float[]{0f,0f,0f}, rot) : null;
    }

    private static void notifyRealComparators(ServerLevel level, BlockPos sourcePos, Block sourceBlock) {
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos neighbourPos = sourcePos.relative(direction);
            BlockState neighbour = realBlockState(level, neighbourPos);
            if (neighbour.is(Blocks.COMPARATOR)) {
                withRealWorld(() -> level.neighborChanged(neighbour, neighbourPos, sourceBlock, null, false));
                continue;
            }
            if (!withRealWorld(() -> neighbour.isRedstoneConductor(level, neighbourPos))) continue;
            BlockPos behindPos = neighbourPos.relative(direction);
            BlockState behind = realBlockState(level, behindPos);
            if (behind.is(Blocks.COMPARATOR))
                withRealWorld(() -> level.neighborChanged(behind, behindPos, sourceBlock, null, false));
        }
    }

    public record GridUse(KontraGrid grid, net.minecraft.world.phys.BlockHitResult hit) {}

    // one physical right-click can reach the server twice: our precise KenderUsePayload AND a vanilla
    // use packet that slipped past the client suppression. both toggle the block → door opens then shuts.
    // whoever grabs the (player, tick, cell) slot first runs it, the other bails.
    private static final java.util.Map<java.util.UUID, long[]> GRID_USE_CLAIM = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean claimGridUse(ServerPlayer player, BlockPos gridPos) {
        long tick = player.level().getGameTime();
        long cell = gridPos.asLong();
        long[] slot = GRID_USE_CLAIM.get(player.getUUID());
        if (slot != null && slot[0] == tick && slot[1] == cell) return false;
        if (slot == null) GRID_USE_CLAIM.put(player.getUUID(), new long[]{tick, cell});
        else { slot[0] = tick; slot[1] = cell; }
        return true;
    }

    // the hit has to sit on what you can aim at. used to be a flat +-0.5 cell, which silently threw
    // away every click on a part drawn outside its own cell (a bearing's turning half)
    private static boolean koperHitOnShape(BlockState state, net.minecraft.nbt.CompoundTag localData,
                                           com.koper.koper_lib.network.KenderHitRef ref) {
        float x = ref.hitX(), y = ref.hitY(), z = ref.hitZ();
        if (Math.abs(x) <= 0.501f && Math.abs(y) <= 0.501f && Math.abs(z) <= 0.501f) return true;
        var shape = com.koper.koper_lib.physics.shape.KhysShapeCache.voxel(state, localData);
        if (shape == null || shape.isEmpty()) return false;
        // shape boxes are 0..1 cell space, the hit is centre-relative
        return shape.bounds().inflate(0.01).contains(x + 0.5, y + 0.5, z + 0.5);
    }

    public static KoperPhysicsEvents.GridHit resolveGridHit(
            ServerPlayer player, com.koper.koper_lib.network.KenderHitRef ref) {
        if (player == null || ref == null || !ref.hasFiniteBlockPoint()
                || !(player.level() instanceof ServerLevel level)) return null;
        KontraEntry data = KONTRAS.get(ref.kontraId());
        if (data == null || !data.levelKey().equals(levelKey(level))) return null;
        BlockState state = data.blocks.get(ref.localPos());
        float[] off = data.blockOffsets.get(ref.localPos());
        float[] bodyPos = CACHED_POS.get(ref.kontraId());
        float[] bodyRot = CACHED_ROT.get(ref.kontraId());
        if (state == null || off == null || bodyPos == null || bodyRot == null) return null;
        if (!koperHitOnShape(state, data.localData.get(ref.localPos()), ref)) return null;

        float[] center = localToWorld(off[0], off[1], off[2], bodyPos, bodyRot);
        BlockPos projected = BlockPos.containing(center[0], center[1], center[2]);
        if (!player.isWithinBlockInteractionRange(projected, 1.0)
                || !level.mayInteract(player, projected)
                || level.getServer().isUnderSpawnProtection(level, projected, player)) return null;

        var directions = net.minecraft.core.Direction.values();
        var localFace = directions[Math.floorMod(ref.localDirection(), directions.length)];
        float[] worldNormal = localToWorld(localFace.getStepX(), localFace.getStepY(), localFace.getStepZ(),
            new float[]{0f, 0f, 0f}, bodyRot);
        var worldFace = net.minecraft.core.Direction.getApproximateNearest(
            worldNormal[0], worldNormal[1], worldNormal[2]);
        float[] worldPoint = localToWorld(
            off[0] + ref.hitX(), off[1] + ref.hitY(), off[2] + ref.hitZ(), bodyPos, bodyRot);
        KontraGrid grid = data.grid(ref.kontraId());
        return new KoperPhysicsEvents.GridHit(ref.kontraId(), ref.localPos(), state,
            data.blockEntities.get(ref.localPos()), localFace,
            ref.hitX(), ref.hitY(), ref.hitZ(), grid, data,
            new Vec3(worldPoint[0], worldPoint[1], worldPoint[2]), worldFace,
            new Vec3(bodyPos[0], bodyPos[1], bodyPos[2]),
            bodyRot[0], bodyRot[1], bodyRot[2], bodyRot[3], level.dimension());
    }

    public static void useGridBlock(ServerPlayer player, com.koper.koper_lib.network.KenderHitRef ref) {
        KoperPhysicsEvents.GridHit resolved = resolveGridHit(player, ref);
        if (resolved == null) return;
        useGridBlock(player, resolved);
    }

    public static void useGridBlock(ServerPlayer player, long kontraId, BlockPos local, int directionOrdinal,
                                    float hitX, float hitY, float hitZ) {
        useGridBlock(player, new com.koper.koper_lib.network.KenderHitRef(
            kontraId, local, directionOrdinal, hitX, hitY, hitZ));
    }

    private static void useGridBlock(ServerPlayer player, KoperPhysicsEvents.GridHit detailedHit) {
        KontraEntry data = detailedHit.entry();
        KontraGrid grid = detailedHit.grid();
        BlockPos local = detailedHit.local();
        ServerLevel level = (ServerLevel) player.level();
        player.resetLastActionTime();
        BlockPos gridPos = grid.toGrid(local);
        if (!claimGridUse(player, gridPos)) return;
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] useGridBlock kontra={} local={} gridPos={} held={}",
                detailedHit.kontraId(), local, gridPos, player.getMainHandItem().getItem());
        var direction = detailedHit.face();
        float hitX = detailedHit.hitX(), hitY = detailedHit.hitY(), hitZ = detailedHit.hitZ();
        // addons get first dibs on the click — seats, connectors, gear GUIs eat it here
        if (KoperPhysicsEvents.fireDetailedGridUse(player, detailedHit)
                || KoperPhysicsEvents.fireGridUse(player, detailedHit.kontraId(), local, detailedHit.state())) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), true);
            return;
        }
        var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(
            gridPos.getX() + 0.5 + hitX, gridPos.getY() + 0.5 + hitY, gridPos.getZ() + 0.5 + hitZ),
            direction, gridPos, false);
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            var stack = player.getItemInHand(hand);
            if (!stack.isItemEnabled(level.enabledFeatures())) return;
            var result = gridPlacementTransaction(player, grid, stack, () ->
                KontraGridContext.call(grid, () -> {
                    GRID_PLACEMENT.set(true);
                    try { return player.gameMode.useItemOn(player, player.level(), stack, hand, hit); }
                    finally { GRID_PLACEMENT.set(false); }
                }));
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] useItemOn result={} hand={} dir={} placeCell(local)={}",
                    result, hand, direction, grid.toLocal(gridPos.relative(direction)));
            if (result instanceof net.minecraft.world.InteractionResult.Success success) {
                if (success.swingSource() != net.minecraft.world.InteractionResult.SwingSource.NONE)
                    player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), true);
                return;
            }
            if (result instanceof net.minecraft.world.InteractionResult.Fail) return;
            if (stack.isEmpty()) continue;
            result = wiadroNaPokladzie(player, grid, hand, stack, gridPos, direction);
            if (result instanceof net.minecraft.world.InteractionResult.Success) {
                player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), true);
                return;
            }
            // everything else that reaches useItem is still aimed at the kontra, so it runs in the
            // kontra's world. outside the context a placement item writes into the real world.
            result = gridPlacementTransaction(player, grid, stack, () ->
                KontraGridContext.call(grid, () -> {
                    GRID_PLACEMENT.set(true);
                    try { return player.gameMode.useItem(player, player.level(), stack, hand); }
                    finally { GRID_PLACEMENT.set(false); }
                }));
            if (result instanceof net.minecraft.world.InteractionResult.Success success) {
                if (success.swingSource() != net.minecraft.world.InteractionResult.SwingSource.NONE)
                    player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), true);
                return;
            }
            if (result instanceof net.minecraft.world.InteractionResult.Fail) return;
        }
    }

    // BucketItem.use finds its cell by raycasting the WORLD. on a kontra that ray sees nothing —
    // the blocks are 20M away in grid space — so the water went wherever the crosshair happened to
    // land in the real world. that is the static puddle at the click that never moved with the ship.
    // we already know the exact cell from the physics hit, so pour it ourselves and skip the ray.
    private static net.minecraft.world.InteractionResult wiadroNaPokladzie(
            ServerPlayer player, KontraGrid grid, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.item.ItemStack stack, BlockPos gridPos, net.minecraft.core.Direction face) {
        if (!(stack.getItem() instanceof net.minecraft.world.item.BucketItem bucket))
            return net.minecraft.world.InteractionResult.PASS;
        ServerLevel level = (ServerLevel) player.level();
        var hit = new net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(gridPos), face, gridPos, false);

        if (bucket.getContent() == net.minecraft.world.level.material.Fluids.EMPTY) {
            BlockState state = grid.getBlockState(level, gridPos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.BucketPickup pickup))
                return net.minecraft.world.InteractionResult.PASS;
            var filled = KontraGridContext.call(grid, () -> {
                GRID_PLACEMENT.set(true);
                try { return pickup.pickupBlock(player, level, gridPos, state); }
                finally { GRID_PLACEMENT.set(false); }
            });
            if (filled.isEmpty()) return net.minecraft.world.InteractionResult.PASS;
            player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(stack.getItem()));
            pickup.getPickupSound().ifPresent(sound -> KontraGridContext.run(grid, () ->
                level.playSound(null, gridPos, sound, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f)));
            player.setItemInHand(hand, net.minecraft.world.item.ItemUtils.createFilledResult(stack, player, filled));
            return net.minecraft.world.InteractionResult.SUCCESS;
        }

        // same choice vanilla makes: the clicked cell if that block holds fluid itself (waterloggable),
        // otherwise the one in front of the face we hit
        BlockState at = grid.getBlockState(level, gridPos);
        BlockPos target = at.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer
            ? gridPos : gridPos.relative(face);
        boolean poured = Boolean.TRUE.equals(KontraGridContext.call(grid, () -> {
            GRID_PLACEMENT.set(true);
            try { return bucket.emptyContents(player, level, target, hit); }
            finally { GRID_PLACEMENT.set(false); }
        }));
        // grid said no — cell doesn't touch the hull, or the kontra owns that block. keep the bucket
        if (!poured) return net.minecraft.world.InteractionResult.PASS;
        bucket.checkExtraContent(player, level, stack, target);
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(stack.getItem()));
        player.setItemInHand(hand, net.minecraft.world.item.BucketItem.getEmptySuccessItem(stack, player));
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    public static net.minecraft.world.InteractionResult gridPlacementTransaction(
            ServerPlayer player, KontraGrid grid, net.minecraft.world.item.ItemStack stack,
            java.util.function.Supplier<net.minecraft.world.InteractionResult> action) {
        int beforeCount = stack.getCount();
        int beforeSize = grid.entry().blocks.size();
        int beforeHash = grid.entry().blocks.hashCode();
        net.minecraft.world.InteractionResult result = action.get();
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem
                && stack.getCount() < beforeCount
                && beforeSize == grid.entry().blocks.size()
                && beforeHash == grid.entry().blocks.hashCode()) {
            stack.grow(beforeCount - stack.getCount());
            player.containerMenu.broadcastChanges();
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] refunded a rejected grid placement for {}", player.getScoreboardName());
        }
        return result;
    }

    public static GridUse gridUseAt(ServerLevel level, net.minecraft.world.phys.BlockHitResult physicalHit) {
        long cell = physicalHit.getBlockPos().asLong();
        var physicalIndex = PHYSICAL_CELL_INDEX.get(levelKey(level));
        var hitPoint = physicalHit.getLocation();
        GridCell indexed = physicalIndex != null ? nearestPhysicalCell(physicalIndex, cell,
            hitPoint.x, hitPoint.y, hitPoint.z) : null;
        if (indexed == null) return null;
        KontraEntry data = KONTRAS.get(indexed.kontraId());
        if (data != null) {
            BlockPos local = indexed.local();
            float[] bodyPos = CACHED_POS.get(indexed.kontraId()), rot = CACHED_ROT.get(indexed.kontraId());
            float[] off = data.blockOffsets.get(local);
            if (bodyPos == null || rot == null || off == null) return null;
            var p = physicalHit.getLocation();
            float[] localPoint = worldToLocalPoint((float)p.x, (float)p.y, (float)p.z, bodyPos, rot);
            KontraGrid grid = data.grid(indexed.kontraId());
            var anchor = grid.anchor();
            net.minecraft.world.phys.Vec3 gridPoint = new net.minecraft.world.phys.Vec3(
                anchor.getX() + local.getX() + 0.5 + localPoint[0] - off[0],
                anchor.getY() + local.getY() + 0.5 + localPoint[1] - off[1],
                anchor.getZ() + local.getZ() + 0.5 + localPoint[2] - off[2]);
            var d = physicalHit.getDirection();
            float[] localNormal = worldVectorToLocal(d.getStepX(), d.getStepY(), d.getStepZ(), rot);
            var localDirection = net.minecraft.core.Direction.getApproximateNearest(
                localNormal[0], localNormal[1], localNormal[2]);
            return new GridUse(grid, new net.minecraft.world.phys.BlockHitResult(gridPoint, localDirection,
                grid.toGrid(local), physicalHit.isInside(), physicalHit.isWorldBorderHit()));
        }
        return null;
    }

    // tried merging getUpdateTag() in here on a hunch — create tanks came back rendering as one giant
    // stretched box, so it's the plain save again. the multiblock problem was never the tag.
    public static net.minecraft.nbt.CompoundTag beSyncTag(BlockEntity be, ServerLevel level) {
        return be.saveWithFullMetadata(level.registryAccess());
    }

    public static net.minecraft.nbt.CompoundTag[] blockEntityTags(KontraEntry data, ServerLevel level) {
        var tags = new net.minecraft.nbt.CompoundTag[data.blocks.size()];
        int i = 0;
        for (BlockPos local : data.blocks.keySet()) {
            BlockEntity be = data.blockEntities.get(local);
            if (be != null) {
                try { tags[i] = beSyncTag(be, level); }
                catch (Throwable ex) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] couldn't sync BE {} on kontra: {}", be.getType(), ex.getMessage());
                }
            }
            tags[i] = com.koper.koper_lib.api.local.KoperLocalData.pack(tags[i], data.localData.get(local));
            i++;
        }
        return tags;
    }

    public static void localDataChanged(ServerLevel level, long kontraId, BlockPos local) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null || !data.blocks.containsKey(local)) return;
        pushMaterials(kontraId, data);
        // a micro plate that grew or lost a piece faces the air differently now
        if (KhysWeightBook.get(data.blocks.get(local)).aero()) pushAero(kontraId, data);
        var render = data.renderArrays();
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.get(kontraId);
        KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(kontraId,
            pos != null ? pos.clone() : KEEP_POSE.clone(),
            rot != null ? rot.clone() : KEEP_ROT.clone(),
            render.stateIds(), render.offsets(), render.locals(), blockEntityTags(data, level)));
    }

    private static void indexLogicalEntry(long kontraId, KontraEntry data) {
        for (BlockPos local : data.blocks.keySet()) putLogicalCell(kontraId, data, local);
    }

    private static void putLogicalCell(long kontraId, KontraEntry data, BlockPos local) {
        var index = LOGICAL_CELL_INDEX.computeIfAbsent(data.levelKey(),
            ignored -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        index.put(data.grid(kontraId).toGrid(local).asLong(), new GridCell(kontraId, local.immutable()));
    }

    private static void removeLogicalCell(long kontraId, KontraEntry data, BlockPos local) {
        var index = LOGICAL_CELL_INDEX.get(data.levelKey());
        if (index == null) return;
        long key = data.grid(kontraId).toGrid(local).asLong();
        GridCell cell = index.get(key);
        if (cell != null && cell.kontraId() == kontraId) index.remove(key);
    }

    public static void bootstrapGrid(ServerLevel level, long kontraId, KontraEntry data) {
        bootstrapGrid(level, kontraId, data, true);
    }

    // reshape=false for a body coming back from the save: its states were already shaped when it was
    // written. reshaping them again recounted leaf distance on a body with no log and the leaves rotted
    public static void bootstrapGrid(ServerLevel level, long kontraId, KontraEntry data, boolean reshape) {
        KontraGrid grid = data.grid(kontraId);
        grid.invalidateBoundaryContacts();
        KontraGridContext.run(grid, () -> {
            if (reshape) for (var e : new ArrayList<>(data.blocks.entrySet())) {
                BlockPos local = e.getKey();
                BlockPos gridPos = grid.toGrid(local);
                BlockState shaped = Block.updateFromNeighbourShapes(e.getValue(), level, gridPos);
                // a block that can't stand on its own (a torch, a plant) shapes to air. written into the
                // body that was a cell of air with a collider under it, so it keeps its own state
                if (shaped != e.getValue() && !shaped.isAir()) {
                    data.blocks.put(local, shaped);
                    BlockEntity be = data.blockEntities.get(local);
                    if (be != null) be.setBlockState(shaped);
                }
            }
            for (var e : new ArrayList<>(data.blocks.entrySet()))
                level.updateNeighborsAt(grid.toGrid(e.getKey()), e.getValue().getBlock(), (net.minecraft.world.level.redstone.Orientation)null);
        });
        var render = data.renderArrays();
        data.rebuildRenderData(render.offsets(), render.states());
        data.invalidateSolidCells();
        data.invalidateLogicCells();
        float[] pos = CACHED_POS.get(kontraId), rot = CACHED_ROT.get(kontraId);
        if (pos != null && rot != null) {
            if (LOGIC_REFRESH_ACTIVE.get()) {
                primePhysicalProjection(kontraId, data, pos, rot);
                DEFERRED_LOGIC_BOOTSTRAPS.get().addLast(kontraId);
            } else refreshLogicContacts(level, kontraId, data, pos, rot, false);
        }
    }

    private static void unindexEntry(long kontraId, KontraEntry data) {
        KontraGrid grid = data.grid(kontraId);
        var logical = LOGICAL_CELL_INDEX.get(data.levelKey());
        for (BlockPos local : data.blocks.keySet())
            if (logical != null) logical.remove(grid.toGrid(local).asLong());
        var physical = PHYSICAL_CELL_INDEX.get(data.levelKey());
        if (physical != null) physical.removeAll(kontraId);
    }

    private static GridCell physicalCell(long kontraId, long packed) {
        KontraEntry data=KONTRAS.get(kontraId);
        BlockPos local=data != null ? data.logicOwner(packed) : null;
        return local != null ? new GridCell(kontraId,local) : null;
    }

    private static GridCell nearestPhysicalCell(PhysicalIndex index, long packed, double x, double y, double z) {
        if (index == null) return null;
        GridCell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        long first=index.primary.get(packed);
        if (first != 0L) {
            GridCell cell=physicalCell(first,packed);
            if (cell != null) {
                best=cell;
                bestDistance=physicalDistanceSquared(cell,x,y,z);
            }
        }
        var more=index.overlaps.get(packed);
        if (more != null) for (int i=0;i<more.size();i++) {
            GridCell cell=physicalCell(more.getLong(i),packed);
            if (cell == null) continue;
            double distance = physicalDistanceSquared(cell, x, y, z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cell;
            }
        }
        return best;
    }

    private static double physicalDistanceSquared(GridCell cell, double x, double y, double z) {
        KontraEntry data = KONTRAS.get(cell.kontraId());
        float[] pos = CACHED_POS.get(cell.kontraId());
        float[] rot = CACHED_ROT.get(cell.kontraId());
        float[] off = data != null ? data.blockOffsets.get(cell.local()) : null;
        if (pos == null || rot == null || off == null) return Double.POSITIVE_INFINITY;
        float[] center = localToWorld(off[0], off[1], off[2], pos, rot);
        double dx = center[0] - x, dy = center[1] - y, dz = center[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // used to re-derive the client's key as round(offset). blocks placed on a LIVE grid get their
    // offset from a neighbour, so it carries the neighbour's fraction and rounds somewhere else than
    // the assembly local — every per-block payload for those blocks landed nowhere. the client is fed
    // this exact key in the spawn payload now, so there is nothing left to translate.
    public static BlockPos clientLocal(KontraEntry data, BlockPos serverLocal) {
        return serverLocal;
    }

    // pose that means "client, keep whatever pose you already have". a block-list change must reach
    // the client even when the Rust snapshot hasn't landed yet — the old code just dropped the packet
    // on the floor when CACHED_POS was empty and that block stayed drawn forever.
    private static final float[] KEEP_POSE = { Float.NaN, Float.NaN, Float.NaN };
    private static final float[] KEEP_ROT  = { Float.NaN, Float.NaN, Float.NaN, Float.NaN };

    // order-independent content hash, keyed the way the CLIENT stores blocks (rounded offsets) so
    // both sides can compare without agreeing on map iteration order. plus is on purpose: commutative.
    public static long blockStamp(KontraEntry data) {
        long sum = 0L;
        for (var e : data.blocks.entrySet())
            sum += mixCell(e.getKey().getX(), e.getKey().getY(), e.getKey().getZ(), Block.getId(e.getValue()));
        return sum ^ ((long) data.blocks.size() << 1);
    }

    // splitmix-ish, both sides MUST run the identical thing or every kontra resyncs forever
    public static long mixCell(int lx, int ly, int lz, int stateId) {
        long h = lx * 0x9E3779B97F4A7C15L ^ ly * 0xC2B2AE3D27D4EB4FL ^ lz * 0x165667B19E3779F9L;
        h ^= stateId * 0xD6E8FEB86659FD93L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h;
    }

    // uuid|kontraId -> tick we last honoured a resync ask. stops a broken client from asking us to
    // serialize a 400-block ship 20 times a second
    private static final Map<String, Long> RESYNC_COOLDOWN = new ConcurrentHashMap<>();

    public static void resyncToPlayer(ServerPlayer player, long kontraId) {
        String key = player.getUUID() + "|" + kontraId;
        Long last = RESYNC_COOLDOWN.get(key);
        if (last != null && tickCounter - last < 40) return;
        RESYNC_COOLDOWN.put(key, tickCounter);
        KontraEntry data = KONTRAS.get(kontraId);
        ServerLevel level = (ServerLevel) player.level();
        if (data == null || !data.levelKey().equals(levelKey(level))) {
            KenderSyncServer.cancel(player,kontraId);
            KoperNetworking.sendToPlayer(player, new KenderRemovePayload(kontraId));
            return;
        }
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
        var ra = data.renderArrays();
        KhysicsNetworking.sendSpawn(player, new KenderSpawnPayload(kontraId,
            pos != null ? pos.clone() : KEEP_POSE.clone(), rot.clone(),
            ra.stateIds(), ra.offsets(), ra.locals(), blockEntityTags(data, level)));
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] resync kontra={} -> {} ({} bloki)",
                kontraId, player.getName().getString(), ra.stateIds().length);
    }

    private static void broadcastStamps(MinecraftServer server) {
        RESYNC_COOLDOWN.values().removeIf(t -> tickCounter - t > 400);
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode && tickCounter % 200 == 13)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] stamp hb kontras={}", KONTRAS.size());
        Map<String, List<Long>> idsByLevel = new HashMap<>();
        Map<String, List<Long>> stampsByLevel = new HashMap<>();
        for (var e : KONTRAS.entrySet()) {
            KontraEntry data = e.getValue();
            idsByLevel.computeIfAbsent(data.levelKey(), k -> new ArrayList<>()).add(e.getKey());
            stampsByLevel.computeIfAbsent(data.levelKey(), k -> new ArrayList<>()).add(blockStamp(data));
        }
        // empty levels get an empty payload too — that's how a client learns its last kontra is gone
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            List<Long> ids = idsByLevel.getOrDefault(levelKey(level), List.of());
            List<Long> stamps = stampsByLevel.getOrDefault(levelKey(level), List.of());
            long[] idArr = new long[ids.size()];
            long[] stampArr = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) { idArr[i] = ids.get(i); stampArr[i] = stamps.get(i); }
            KoperNetworking.broadcastToLevel(level,
                new com.koper.koper_lib.network.KenderStampPayload(idArr, stampArr));
        }
    }

    public static void gridBlockChanged(ServerLevel level, long kontraId, KontraEntry data, BlockPos pos,
                                        BlockState oldState, BlockState newState, int flags) {
        data.grid(kontraId).invalidateBoundaryContacts();
        var logicalIndex = LOGICAL_CELL_INDEX.computeIfAbsent(data.levelKey(),
            ignored -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
        long logicalKey = data.grid(kontraId).toGrid(pos).asLong();
        if (newState.isAir()) logicalIndex.remove(logicalKey);
        else logicalIndex.put(logicalKey, new GridCell(kontraId, pos.immutable()));
        var ra = data.renderArrays();
        data.rebuildRenderData(ra.offsets(), ra.states());
        float[] bodyPos = CACHED_POS.get(kontraId);
        float[] bodyRot = CACHED_ROT.get(kontraId);
        if (!oldState.isAir() && !newState.isAir() && oldState.getBlock() == newState.getBlock()) {
            KoperNetworking.broadcastToLevel(level,
                new com.koper.koper_lib.network.KenderGridBlockUpdatePayload(
                    kontraId, clientLocal(data, pos), Block.getId(newState)));
        } else {
            // ONE block changed — send one block. this used to ship the entire body plus every block
            // entity's NBT on every placement, which is what made putting down a create tank hitch.
            float[] off = data.blockOffsets.get(pos);
            BlockEntity be = data.blockEntities.get(pos);
            net.minecraft.nbt.CompoundTag tag = null;
            if (be != null) {
                try { tag = beSyncTag(be, level); }
                catch (Throwable ex) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] couldn't sync BE {} on delta: {}", be.getType(), ex.getMessage());
                }
                tag = com.koper.koper_lib.api.local.KoperLocalData.pack(tag, data.localData.get(pos));
            }
            KoperNetworking.broadcastToLevel(level,
                new com.koper.koper_lib.network.KenderBlockDeltaPayload(kontraId, pos.immutable(),
                    Block.getId(newState),
                    off != null ? off[0] : pos.getX(), off != null ? off[1] : pos.getY(),
                    off != null ? off[2] : pos.getZ(),
                    newState.isAir(), tag));
        }

        float[] contactPos = CACHED_POS.get(kontraId), contactRot = CACHED_ROT.get(kontraId);
        if (contactPos != null && contactRot != null)
            refreshLogicContacts(level, kontraId, data, contactPos, contactRot, false);

        // same block, new state, new weight (a weigher said so) — reweigh just that collider
        if (!oldState.isAir() && !newState.isAir() && oldState.getBlock() == newState.getBlock()) {
            float before = KhysWeightBook.get(oldState).mass(), after = KhysWeightBook.get(newState).mass();
            float[] off = data.blockOffsets.get(pos);
            if (before != after && off != null)
                KoperPhysBridge.setBlockMass(data.worldHandle(), kontraId, off[0], off[1], off[2], after);
        }
        KoperPhysicsEvents.fireBlockChange(kontraId, pos, oldState, newState);
    }

    private static void refreshLogicContacts(ServerLevel level, long kontraId, KontraEntry data,
                                             float[] pos, float[] rot, boolean moving) {
        if (LOGIC_REFRESH_ACTIVE.get()) return;
        LOGIC_REFRESH_ACTIVE.set(true);
        try {
            if (moving) { refreshLogicContactsNow(level, kontraId, data, pos, rot, true); return; }
            for (int pass=0; pass<4; pass++) {
                refreshLogicContactsNow(level, kontraId, data, pos, rot, false);
                if (!data.logicCellsDirty()) break;
            }
        }
        finally {
            LOGIC_REFRESH_ACTIVE.set(false);
            drainLogicBootstraps();
        }
    }

    private static void primePhysicalProjection(long id, KontraEntry data, float[] pos, float[] rot) {
        var oldCells = data.logicCells();
        var cells = data.rebuildLogicCells(pos, rot);
        var index = PHYSICAL_CELL_INDEX.computeIfAbsent(data.levelKey(), ignored -> new PhysicalIndex());
        for (long packed : oldCells.keySet()) if (!cells.containsKey(packed)) index.remove(packed, id);
        for (long packed : cells.keySet()) index.add(packed, id);
        data.logicChangedCells().addAll(oldCells.keySet());
        data.logicChangedCells().addAll(cells.keySet());
    }

    private static void drainLogicBootstraps() {
        var pending = DEFERRED_LOGIC_BOOTSTRAPS.get();
        if (LOGIC_DRAIN_ACTIVE.get() || pending.isEmpty()) return;
        LOGIC_DRAIN_ACTIVE.set(true);
        try {
            // callbacks may spawn again; leave the next generation for the next outer refresh
            int remaining = pending.size();
            while (remaining-- > 0) {
                long id = pending.removeFirst();
                KontraEntry data = KONTRAS.get(id);
                ServerLevel level = data != null ? levelFor(data) : null;
                float[] pos = CACHED_POS.get(id), rot = CACHED_ROT.get(id);
                if (level != null && pos != null && rot != null)
                    refreshLogicContacts(level, id, data, pos, rot, false);
            }
        } finally { LOGIC_DRAIN_ACTIVE.set(false); }
    }

    private static void refreshLogicContactsNow(ServerLevel level, long kontraId, KontraEntry data,
                                                float[] pos, float[] rot, boolean moving) {
        var oldCells = data.logicCells();
        var oldOwners = data.logicOwners();
        var newCells = data.rebuildLogicCells(pos, rot);
        var newOwners = data.logicOwners();
        var changed = data.logicChangedCells();
        KontraGrid grid = data.grid(kontraId);
        Set<BlockPos> boundaryChanged = grid.refreshBoundaryContacts(level, pos, rot);
        boolean cellsChanged = oldCells != newCells;
        if (cellsChanged) {
            var index=PHYSICAL_CELL_INDEX.computeIfAbsent(data.levelKey(), ignored -> new PhysicalIndex());
            for (var oldEntry : oldCells.long2ObjectEntrySet()) {
                long packed=oldEntry.getLongKey();
                BlockState oldState=oldEntry.getValue(), now=newCells.get(packed);
                BlockPos oldOwner=oldOwners.get(packed), newOwner=newOwners.get(packed);
                boolean ownerMatters=!Objects.equals(oldOwner,newOwner) && ((oldOwner != null && data.blockEntities.containsKey(oldOwner))
                    || (newOwner != null && data.blockEntities.containsKey(newOwner))
                    || oldState.isSignalSource() || oldState.hasAnalogOutputSignal()
                    || now != null && (now.isSignalSource() || now.hasAnalogOutputSignal()));
                if (oldState != now || ownerMatters) changed.add(packed);
                if (now == null) index.remove(packed,kontraId);
            }
            for (var newEntry : newCells.long2ObjectEntrySet()) {
                long packed=newEntry.getLongKey();
                if (oldCells.containsKey(packed)) continue;
                index.add(packed,kontraId);
                changed.add(packed);
            }
        }
        // index is fresh now — but a moving kontra didn't EDIT any redstone, its cells just slid to new
        // world cells. firing neighbour updates for that trail is what prints ghost blocks and vomits the
        // wiring out as items. internal wiring still ticks via KontraGrid.tick(); this is world-contact only.
        if (moving) { changed.clear(); return; }
        cellsChanged = !changed.isEmpty();
        if (!cellsChanged && boundaryChanged.isEmpty()) return;
        for (long packed : changed) {
            BlockState old = oldCells.get(packed), now = newCells.get(packed);
            BlockPos worldPos = BlockPos.of(packed);
            Block source = now != null ? now.getBlock() : old != null ? old.getBlock() : Blocks.AIR;
            // poke only REAL world neighbours. a neighbour that's just another kontra cell reads world-air
            // here, so realBlockState skips it — otherwise a rotated deck block gets a world-context update,
            // its world-below is air, canSurvive fails and redstone/rails/torches drop off in flight.
            for (var dir : net.minecraft.core.Direction.values()) {
                BlockPos np = worldPos.relative(dir);
                BlockState npReal = realBlockState(level, np);
                if (npReal.isAir()) continue;
                withRealWorld(() -> level.neighborChanged(npReal, np, source, null, false));
            }
            BlockState real = realBlockState(level, worldPos);
            if (!real.isAir())
                withRealWorld(() -> level.neighborChanged(real, worldPos, source, null, false));
        }

        Set<BlockPos> touchedLocal = new HashSet<>();
        for (long packed : changed) {
            BlockPos oldLocal = oldOwners.get(packed), newLocal = newOwners.get(packed);
            if (oldLocal != null) touchedLocal.add(oldLocal);
            if (newLocal != null) touchedLocal.add(newLocal);
        }
        touchedLocal.addAll(boundaryChanged);
        KontraGridContext.run(grid, () -> {
            for (BlockPos local : touchedLocal) {
                BlockState state = data.blocks.get(local);
                if (state != null)
                    state.handleNeighborChanged(level, grid.toGrid(local), Blocks.AIR, null, false);
            }
        });
        changed.clear();
    }

    // ── destroy ───────────────────────────────────────────────────────────────

    // nukes a kontraktion — optionally restores blocks to world
    public static void selfRight(ServerLevel level, long kontraId) {
        long now = tickCounter;
        long last = SELF_RIGHT_TICK.getOrDefault(kontraId, -9999L);
        if (now - last < SELF_RIGHT_COOLDOWN) return;
        SELF_RIGHT_TICK.put(kontraId, now);
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return;
        KoperPhysBridge.selfRight(data.worldHandle(), kontraId);
    }

    public static void destroyKontraktion(MinecraftServer server, long kontraId) {
        KenderSyncServer.cancelBody(server,kontraId);
        SELF_RIGHT_TICK.remove(kontraId);
        KONTRA_SLEEP_STATE.remove(kontraId);
        dropJointsOf(kontraId);
        KontraEntry data = KONTRAS.remove(kontraId);
        if (data == null) return;
        var oldPhysicalCells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState>(data.logicCells());
        unindexEntry(kontraId, data);
        if (server != null) {
            ServerLevel level = findLevel(server, data.levelKey());
            if (level != null) for (var cell : oldPhysicalCells.long2ObjectEntrySet())
                level.updateNeighborsAt(BlockPos.of(cell.getLongKey()), cell.getValue().getBlock(), (net.minecraft.world.level.redstone.Orientation)null);
        }

        // F2 fix: eject entities that were riding — otherwise they freeze in mid-air forever.
        // players clear themselves off the KenderRemovePayload; minds of mobs get the ghost
        // momentum so the deck exploding under them doesn't just delete their speed
        float[] lastVel = getKontraVelocity(kontraId);
        if (server != null) {
            ServerLevel lvl = findLevel(server, data.levelKey());
            if (lvl != null) {
                float[] kp = CACHED_POS.get(kontraId);
                if (kp != null) {
                    float r = data.cachedRadius + 3;
                    AABB ejectBox = new AABB(kp[0]-r, kp[1]-r, kp[2]-r, kp[0]+r, kp[1]+r, kp[2]+r);
                    for (Entity ent : lvl.getEntitiesOfClass(Entity.class, ejectBox)) {
                        KontraGlue.Mind m = KontraGlue.mind(ent);
                        if (m.deckId != kontraId) continue;
                        KontraGlue.release(ent, m, "kontra-destroyed", true);
                        ent.setOnGround(false);
                        if (lastVel != null && !(ent instanceof ServerPlayer)) {
                            m.ghostX = lastVel[0]; m.ghostY = lastVel[1]; m.ghostZ = lastVel[2];
                        }
                    }
                }
            }
        }
        PREV_KONTRA_POS.remove(kontraId);
        PREV_KONTRA_ROT.remove(kontraId);
        LAST_KONTRA_DELTA.remove(kontraId);
        LAST_KONTRA_PREVIOUS_ROT.remove(kontraId);

        KoperPhysBridge.destroyKontraktion(data.worldHandle(), kontraId);
        CACHED_POS.remove(kontraId);
        CACHED_ROT.remove(kontraId);
        SAVE_TRANSFORM.remove(kontraId);
        if (server != null) {
            ServerLevel lvl = findLevel(server, data.levelKey());
            if (lvl != null) KoperNetworking.broadcastToLevel(lvl, new KenderRemovePayload(kontraId));
            else KoperNetworking.broadcastToAll(server, new KenderRemovePayload(kontraId));
        }
        KoperPhysicsEvents.fireDestroy(kontraId);
    }

    private record WorldRestoreCell(BlockPos local, BlockPos world, BlockState state, BlockEntity be) {}

    // Explicit disassembly must refuse collisions before placing or deleting anything.
    public static boolean tryRestoreToWorld(MinecraftServer server, long kontraId) {
        return tryRestoreToWorld(server, kontraId, CACHED_POS.get(kontraId), CACHED_ROT.get(kontraId));
    }

    // A proposed aligned pose is validated without teleporting a refused hull.
    public static boolean tryRestoreToWorld(MinecraftServer server, long kontraId, float[] proposedPos, float[] proposedRot) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (server == null || data == null || proposedPos == null || proposedRot == null) return false;
        float[] pos = proposedPos.clone(), rot = proposedRot.clone();
        if (pos.length < 3 || rot.length < 4) return false;
        for (float value : pos) if (!Float.isFinite(value)) return false;
        for (float value : rot) if (!Float.isFinite(value)) return false;
        double norm = 0;
        for (float value : rot) norm += value * value;
        if (Math.abs(norm - 1) > 1e-3) return false;
        int[] basis = new int[9];
        for (int axis = 0; axis < 3; axis++) {
            float[] vector = localToWorld(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0,
                new float[3], rot);
            int length = 0;
            for (int component = 0; component < 3; component++) {
                int snapped = Math.round(vector[component]);
                if (Math.abs(vector[component] - snapped) > 1e-3) return false;
                basis[axis * 3 + component] = snapped;
                length += Math.abs(snapped);
            }
            if (length != 1) return false;
        }
        // Arbitrary blocks cannot represent pitch/roll faithfully as static states.
        if (basis[4] != 1) return false;
        ServerLevel level = findLevel(server, data.levelKey());
        if (level == null) return false;
        return withRealWorld(() -> {
            boolean previous = ASSEMBLY_ACTIVE.get();
            int writeDepth = WORLD_WRITE_DEPTH.get()[0];
            ASSEMBLY_ACTIVE.set(true);
            try {
                var cells = new ArrayList<WorldRestoreCell>(data.blocks.size());
                var destinations = new java.util.HashSet<BlockPos>();
                for (var entry : data.blocks.entrySet()) {
                    BlockPos local = entry.getKey();
                    float[] offset = data.blockOffsets.get(local);
                    double ox = offset != null ? offset[0] : local.getX();
                    double oy = offset != null ? offset[1] : local.getY();
                    double oz = offset != null ? offset[2] : local.getZ();
                    // Signed cardinal axes and double addition keep adjacent distant cells distinct.
                    double x = pos[0] + ox * basis[0] + oy * basis[3] + oz * basis[6];
                    double y = pos[1] + ox * basis[1] + oy * basis[4] + oz * basis[7];
                    double z = pos[2] + ox * basis[2] + oy * basis[5] + oz * basis[8];
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                            || x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000)
                        return false;
                    BlockPos world = BlockPos.containing(x, y, z);
                    if (level.isOutsideBuildHeight(world) || !level.getWorldBorder().isWithinBounds(world)
                            || !destinations.add(world) || !level.getBlockState(world).isAir()) return false;
                    BlockState state = restoreState(entry.getValue(), basis);
                    BlockEntity oldBe = data.blockEntities.get(local);
                    BlockEntity moved = oldBe == null ? null : relocateBlockEntity(oldBe, world, state, level);
                    if (oldBe != null && moved == null) return false;
                    cells.add(new WorldRestoreCell(local, world, state, moved));
                }
                if (restorationOverlapsHull(data.levelKey(), kontraId, destinations)) return false;
                int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
                var placed = new ArrayList<WorldRestoreCell>(cells.size());
                try {
                    for (WorldRestoreCell cell : cells) {
                        // setBlock can mutate the chunk and then throw from a mod's onPlace.
                        placed.add(cell);
                        if (!level.setBlock(cell.world(), cell.state(), flags)) {
                            rollbackRestore(level, placed, flags);
                            return false;
                        }
                        if (cell.be() != null) {
                            level.removeBlockEntity(cell.world());
                            level.setBlockEntity(cell.be());
                        }
                    }
                } catch (RuntimeException error) {
                    rollbackRestore(level, placed, flags);
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KoperPhys] disassembly placement failed for {}", kontraId, error);
                    return false;
                }
                for (WorldRestoreCell cell : cells)
                    com.koper.koper_lib.api.local.KoperLocalData.restore(level, cell.world(), cell.state(),
                        data.localData.get(cell.local()));
                var pending = new java.util.HashMap<BlockPos, List<KontraGrid.SavedGridTick>>();
                for (var tick : data.grid(kontraId).savedTicks())
                    pending.computeIfAbsent(tick.localPos(), ignored -> new ArrayList<>()).add(tick);
                destroyKontraktion(server, kontraId);
                // Neighbor callbacks see the whole restored structure and its inventories.
                for (WorldRestoreCell cell : cells) {
                    level.updateNeighborsAt(cell.world(), cell.state().getBlock());
                    cell.state().updateNeighbourShapes(level, cell.world(), Block.UPDATE_ALL);
                }
                for (WorldRestoreCell cell : cells) {
                    var ticks = pending.get(cell.local());
                    if (ticks == null) continue;
                    for (var tick : ticks) {
                        int delay = (int)Math.min(Integer.MAX_VALUE, Math.max(0, tick.delay()));
                        if (tick.fluid()) {
                            var fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(tick.type()).orElse(null);
                            if (fluid != null) level.scheduleTick(cell.world(), fluid, delay, tick.priority());
                        } else {
                            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(tick.type()).orElse(null);
                            if (block != null) level.scheduleTick(cell.world(), block, delay, tick.priority());
                        }
                    }
                }
                return true;
            } finally {
                WORLD_WRITE_DEPTH.get()[0] = writeDepth;
                ASSEMBLY_ACTIVE.set(previous);
            }
        });
    }

    private static boolean restorationOverlapsHull(String levelKey, long restoringId, java.util.Set<BlockPos> cells) {
        net.minecraft.world.phys.AABB bounds = null;
        for (BlockPos cell : cells) {
            var box = new net.minecraft.world.phys.AABB(cell);
            bounds = bounds == null ? box : bounds.minmax(box);
        }
        if (bounds == null) return false;
        for (var hull : KONTRAS.entrySet()) {
            KontraEntry other = hull.getValue();
            if (hull.getKey() == restoringId || !other.levelKey().equals(levelKey)) continue;
            float[] pos = CACHED_POS.get(hull.getKey()), rot = CACHED_ROT.get(hull.getKey());
            if (pos == null || rot == null) return true;
            for (float value : pos) if (!Float.isFinite(value)) return true;
            for (float value : rot) if (!Float.isFinite(value)) return true;
            double norm = 0;
            for (float value : rot) norm += value * value;
            if (norm < 1e-6) return true;
            float radius = other.cachedRadius;
            if (!new net.minecraft.world.phys.AABB((double)pos[0] - radius, (double)pos[1] - radius, (double)pos[2] - radius,
                    (double)pos[0] + radius, (double)pos[1] + radius, (double)pos[2] + radius).intersects(bounds)) continue;
            // Use actual poses, including hulls whose first contact index has not arrived yet.
            for (float[] off : other.blockOffsets.values()) {
                var query = new KontraEntityQuery(new net.minecraft.world.phys.AABB(
                    off[0] - .5, off[1] - .5, off[2] - .5, off[0] + .5, off[1] + .5, off[2] + .5), pos, rot);
                var projected = query.bounds();
                if (!projected.intersects(bounds)) continue;
                int minX = (int)Math.floor(Math.max(projected.minX, bounds.minX));
                int minY = (int)Math.floor(Math.max(projected.minY, bounds.minY));
                int minZ = (int)Math.floor(Math.max(projected.minZ, bounds.minZ));
                int maxX = (int)Math.floor(Math.min(projected.maxX, bounds.maxX));
                int maxY = (int)Math.floor(Math.min(projected.maxY, bounds.maxY));
                int maxZ = (int)Math.floor(Math.min(projected.maxZ, bounds.maxZ));
                for (int x = minX; x <= maxX; x++)
                    for (int y = minY; y <= maxY; y++)
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos cell = new BlockPos(x, y, z);
                            if (cells.contains(cell) && query.intersects(new net.minecraft.world.phys.AABB(cell).deflate(1e-6)))
                                return true;
                        }
            }
        }
        return false;
    }

    private static BlockState restoreState(BlockState state, int[] basis) {
        // Block.rotate also handles signs, rails and mod-specific rotation properties.
        var facing = net.minecraft.core.Direction.getApproximateNearest(-basis[6], 0, -basis[8]);
        var rotation = switch (facing) {
            case EAST -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case SOUTH -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case WEST -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
        return state.rotate(rotation);
    }

    private static void rollbackRestore(ServerLevel level, List<WorldRestoreCell> cells, int flags) {
        for (WorldRestoreCell cell : cells) {
            level.removeBlockEntity(cell.world());
            level.setBlock(cell.world(), Blocks.AIR.defaultBlockState(), flags);
        }
    }

    // Legacy cleanup policy may move obstructed blocks upward or drop them. Explicit disassembly
    // uses tryRestoreToWorld so a blocked destination leaves the whole body intact.
    public static void restoreToWorld(MinecraftServer server, long kontraId) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return;
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f,0f,0f,1f});

        ServerLevel level = findLevel(server, data.levelKey());
        if (level == null) { destroyKontraktion(server, kontraId); return; }

        ASSEMBLY_ACTIVE.set(true);
        try {
            for (var e : data.blocks.entrySet()) {
                BlockPos local = e.getKey();
                if (rot == null) continue;
                // use float blockOffsets — integer local pos loses 0.5 precision and restores to wrong block
                float[] off = data.blockOffsets.get(local);
                float ox = off != null ? off[0] : local.getX();
                float oy = off != null ? off[1] : local.getY();
                float oz = off != null ? off[2] : local.getZ();
                float[] w = localToWorld(ox, oy, oz, pos, rot);
                // w is the block center in world space — floor gives the BlockPos
                BlockPos worldPos = new BlockPos((int)Math.floor(w[0]), (int)Math.floor(w[1]), (int)Math.floor(w[2]));
                BlockPos placedAt = null;
                if (level.getBlockState(worldPos).isAir()) {
                    level.setBlock(worldPos, e.getValue(), Block.UPDATE_ALL);
                    placedAt = worldPos;
                } else {
                    // occupied — try 1 block above before giving up
                    BlockPos above = worldPos.above();
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above, e.getValue(), Block.UPDATE_ALL);
                        placedAt = above;
                    } else
                        Block.dropResources(e.getValue(), level, worldPos, data.blockEntities.get(local));
                }
                BlockEntity oldBe = data.blockEntities.get(local);
                if (placedAt != null && oldBe != null) {
                    BlockEntity movedBe = relocateBlockEntity(oldBe, placedAt, e.getValue(), level);
                    if (movedBe != null) {
                        level.removeBlockEntity(placedAt);
                        level.setBlockEntity(movedBe);
                    }
                }
                if (placedAt != null)
                    com.koper.koper_lib.api.local.KoperLocalData.restore(
                        level, placedAt, e.getValue(), data.localData.get(local));
            }
        } finally {
            ASSEMBLY_ACTIVE.set(false);
        }
        destroyKontraktion(server, kontraId);
    }

    // sends kontraktions in the player's current dimension — call on join / dimension change
    public static void sendAllToPlayer(net.minecraft.server.level.ServerPlayer player) {
        String playerLevel = levelKey((ServerLevel) player.level());
        for (var e : KONTRAS.entrySet()) {
            long id = e.getKey();
            KontraEntry d = e.getValue();
            if (!d.levelKey().equals(playerLevel)) continue;
            float[] pos = CACHED_POS.get(id);
            float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f,0f,0f,1f});
            if (pos == null) continue;
            var ra = d.renderArrays();
            KhysicsNetworking.sendSpawn(player, new com.koper.koper_lib.network.KenderSpawnPayload(
                id, pos, rot, ra.stateIds(), ra.offsets(), ra.locals(), blockEntityTags(d, (ServerLevel)player.level())));
        }
    }

    // drop all blocks as item entities, destroy the physics body — used by /koperlib physics break
    public static int breakToDrops(MinecraftServer server, long kontraId) {
        KontraEntry data = KONTRAS.get(kontraId);
        if (data == null) return 0;
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f,0f,0f,1f});

        ServerLevel level = findLevel(server, data.levelKey());
        int count = data.blocks.size();

        if (level != null && pos != null) {
            for (var e : data.blocks.entrySet()) {
                BlockState bs = e.getValue();
                if (bs.isAir()) continue;
                float[] off = data.blockOffsets.get(e.getKey());
                float ox = off != null ? off[0] : e.getKey().getX();
                float oy = off != null ? off[1] : e.getKey().getY();
                float oz = off != null ? off[2] : e.getKey().getZ();
                float[] w = localToWorld(ox, oy, oz, pos, rot);
                BlockPos dropPos = new BlockPos((int)Math.floor(w[0]), (int)Math.floor(w[1]), (int)Math.floor(w[2]));
                Block.dropResources(bs, level, dropPos, data.blockEntities.get(e.getKey()));
            }
        }
        destroyKontraktion(server, kontraId);
        return count;
    }

    // called from persistence layer to re-insert a loaded kontraktion
    public static void restoreEntry(long kontraId, KontraEntry data, float[] pos, float[] rot) {
        KONTRAS.put(kontraId, data);
        indexLogicalEntry(kontraId, data);
        CACHED_POS.put(kontraId, pos);
        CACHED_ROT.put(kontraId, rot);
        pinSaveTransform(kontraId, pos, rot);
    }

    // ── clearAll (on server stop / reload) ────────────────────────────────────

    public static void clearAll() {
        KenderSyncServer.clear();
        // destroy Rapier worlds so the next world load starts with a clean physics state
        for (long wh : new HashSet<>(WORLD_HANDLES.values())) {
            if (wh > 0) KoperPhysBridge.destroyWorld(wh);
        }
        WORLD_HANDLES.clear();
        LEVEL_KEYS.clear();
        KONTRAS.clear();
        LOGICAL_CELL_INDEX.clear();
        PHYSICAL_CELL_INDEX.clear();
        DEFERRED_LOGIC_BOOTSTRAPS.get().clear();
        LOGIC_DRAIN_ACTIVE.set(false);
        LEVEL_TICKS.clear();
        CACHED_POS.clear();
        CACHED_ROT.clear();
        SAVE_TRANSFORM.clear();
        PREV_KONTRA_POS.clear();
        PREV_KONTRA_ROT.clear();
        LAST_KONTRA_DELTA.clear();
        KONTRA_SLEEP_STATE.clear();
        SELF_RIGHT_TICK.clear();
        RECENT_KONTRA_BREAKS.clear();
        DIAG_SETTLED.clear();
        BE_TICK_ERRORS.clear();
        JOINT_WORLDS.clear();
        JOINT_ENDS.clear();
        JOINT_SPECS.clear();
        JOINT_POSITION_MOTORS.clear();
    }

    // ── wand selections ───────────────────────────────────────────────────────

    // two point selection for worldedit-like wands. idk if players like it but kopers choice is law!
    private static final Map<UUID, BlockPos[]> TWO_POINT_SELECTIONS = new ConcurrentHashMap<>();

    public static BlockPos[] getTwoPointSelection(UUID playerUuid) {
        return TWO_POINT_SELECTIONS.computeIfAbsent(playerUuid, u -> new BlockPos[2]);
    }

    public static void clearTwoPointSelection(UUID playerUuid) {
        TWO_POINT_SELECTIONS.remove(playerUuid);
    }

    // every real world block in the cuboid spanned by a..b (inclusive) — selection wand + /koperlib physics make
    // a selection bigger than this is a typo (a corner at 0 0 0, a wand click a world away), not a
    // machine. walking it used to freeze the server for good: billions of cells, and every unloaded
    // chunk on the way got generated
    public static final long MAX_CUBOID_CELLS = 4_000_000L;

    // why a cuboid cannot be collected, in words for the player, or null when it can
    public static String cuboidProblem(ServerLevel level, BlockPos a, BlockPos b) {
        long cells = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (cells > MAX_CUBOID_CELLS)
            return "that selection is " + cells + " blocks, the limit is " + MAX_CUBOID_CELLS;
        int minCx = Math.min(a.getX(), b.getX()) >> 4, maxCx = Math.max(a.getX(), b.getX()) >> 4;
        int minCz = Math.min(a.getZ(), b.getZ()) >> 4, maxCz = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++)
                if (!level.hasChunk(cx, cz)) return "part of that selection is in chunks that are not loaded";
        return null;
    }

    // empty when cuboidProblem has an objection; callers that talk to a player ask it first for the reason
    public static List<BlockPos> collectCuboidBlocks(ServerLevel level, BlockPos a, BlockPos b) {
        List<BlockPos> list = new ArrayList<>();
        if (cuboidProblem(level, a, b) != null) return list;
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    // A moving projection may overlap a tiny real part, such as a wheel over its bearing.
                    // Selection owns real blocks only, so the projection must not hide that bearing.
                    if (!realBlockState(level, p).isAir()) list.add(p);
                }
        return list;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BlockState worldToLocal(long kontraId, BlockPos worldPos, KontraEntry data) {
        float[] pos = CACHED_POS.get(kontraId);
        float[] rot = CACHED_ROT.get(kontraId);
        if (pos == null || rot == null) return null;
        BlockPos local = worldPosToLocal(worldPos, pos, rot);
        return data.blocks.get(local);
    }

    // inverse rotate a world BlockPos center into kontraktion local space
    static BlockPos worldPosToLocal(BlockPos worldPos, float[] pos, float[] rot) {
        float dx = worldPos.getX() + 0.5f - pos[0];
        float dy = worldPos.getY() + 0.5f - pos[1];
        float dz = worldPos.getZ() + 0.5f - pos[2];
        float iqx=-rot[0], iqy=-rot[1], iqz=-rot[2];
        float tx=2*(iqy*dz-iqz*dy), ty=2*(iqz*dx-iqx*dz), tz=2*(iqx*dy-iqy*dx);
        float lx=dx+rot[3]*tx+iqy*tz-iqz*ty;
        float ly=dy+rot[3]*ty+iqz*tx-iqx*tz;
        float lz=dz+rot[3]*tz+iqx*ty-iqy*tx;
        return new BlockPos(Math.round(lx), Math.round(ly), Math.round(lz));
    }

    // world BlockPos of a kontra block from its local offset + the live transform — for light sampling
    // (KontraLight) and anything that needs the block's real host-dimension cell. null if no snapshot yet.
    public static BlockPos kontraBlockWorldPos(long id, float lx, float ly, float lz) {
        float[] pos = CACHED_POS.get(id);
        if (pos == null) return null;
        float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f, 0f, 0f, 1f});
        float[] w = localToWorld(lx, ly, lz, pos, rot);
        return new BlockPos((int)Math.floor(w[0]), (int)Math.floor(w[1]), (int)Math.floor(w[2]));
    }

    public static BlockPos gridToWorld(long id, KontraEntry data, BlockPos gridPos) {
        float[] pos = CACHED_POS.get(id);
        float[] rot = CACHED_ROT.get(id);
        if (pos == null || rot == null) return null;
        float[] off = data.grid(id).offsetFor(gridPos);
        float[] world = localToWorld(off[0], off[1], off[2], pos, rot);
        return BlockPos.containing(world[0], world[1], world[2]);
    }

    public static float[] cachedPos(long id) { return CACHED_POS.get(id); }
    public static float[] cachedRot(long id) { return CACHED_ROT.get(id); }

    public static BlockState realBlockState(ServerLevel level, BlockPos pos) {
        boolean old = REAL_WORLD_LOOKUP.get();
        REAL_WORLD_LOOKUP.set(true);
        try { return level.getBlockState(pos); }
        finally { REAL_WORLD_LOOKUP.set(old); }
    }

    public static BlockEntity realBlockEntity(ServerLevel level, BlockPos pos) {
        boolean old = REAL_WORLD_LOOKUP.get();
        REAL_WORLD_LOOKUP.set(true);
        try { return level.getBlockEntity(pos); }
        finally { REAL_WORLD_LOOKUP.set(old); }
    }

    private static void withRealWorld(Runnable action) {
        withRealWorld(() -> { action.run(); return null; });
    }

    private static <T> T withRealWorld(java.util.function.Supplier<T> action) {
        boolean old = REAL_WORLD_LOOKUP.get();
        REAL_WORLD_LOOKUP.set(true);
        try { return KontraGridContext.outside(action); }
        finally { REAL_WORLD_LOOKUP.set(old); }
    }

    public static net.minecraft.core.Direction gridDirectionToWorld(long id, net.minecraft.core.Direction local) {
        float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f,0f,0f,1f});
        float[] v = localToWorld(local.getStepX(), local.getStepY(), local.getStepZ(),
            new float[]{0f,0f,0f}, rot);
        return net.minecraft.core.Direction.getApproximateNearest(v[0], v[1], v[2]);
    }

    public static net.minecraft.core.Direction worldDirectionToGrid(long id, net.minecraft.core.Direction world) {
        float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f,0f,0f,1f});
        float[] v = worldVectorToLocal(world.getStepX(), world.getStepY(), world.getStepZ(), rot);
        return net.minecraft.core.Direction.getApproximateNearest(v[0], v[1], v[2]);
    }

    public static BlockState stateToWorld(BlockState state, float[] rot) {
        return rotateDirectionalState(state, rot, false);
    }

    public static BlockState stateToGrid(BlockState state, float[] rot) {
        return rotateDirectionalState(state, rot, true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BlockState gridEditToWorld(BlockState projectedOld, BlockState actualOld,
                                             BlockState editedGrid, float[] rot) {
        BlockState world = stateToWorld(editedGrid, rot);
        if (projectedOld.getBlock() != editedGrid.getBlock() || actualOld.getBlock() != editedGrid.getBlock())
            return world;
        for (net.minecraft.world.level.block.state.properties.Property property : editedGrid.getProperties()) {
            Comparable edited = editedGrid.getValue(property);
            if (!(edited instanceof net.minecraft.core.Direction)
                    && !(edited instanceof net.minecraft.core.Direction.Axis)
                    && propertyDirection(property.getName()) == null) continue;
            if (!edited.equals(projectedOld.getValue(property))) continue;
            Comparable actual = actualOld.getValue(property);
            if (property.getPossibleValues().contains(actual)) world = world.setValue(property, actual);
        }
        return world;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState rotateDirectionalState(BlockState state, float[] rot, boolean inverse) {
        BlockState result = state;
        float[] mappedVector = STATE_ROTATION_VECTOR.get();
        for (net.minecraft.world.level.block.state.properties.Property property : state.getProperties()) {
            Comparable value = state.getValue(property);
            if (value instanceof net.minecraft.core.Direction direction) {
                net.minecraft.core.Direction mapped = nearestAllowedDirection(property, direction, rot, inverse);
                if (mapped != direction) result = result.setValue(property, mapped);
            } else if (value instanceof net.minecraft.core.Direction.Axis axis) {
                float x = axis == net.minecraft.core.Direction.Axis.X ? 1f : 0f;
                float y = axis == net.minecraft.core.Direction.Axis.Y ? 1f : 0f;
                float z = axis == net.minecraft.core.Direction.Axis.Z ? 1f : 0f;
                rotateStateVector(x, y, z, rot, inverse, mappedVector);
                net.minecraft.core.Direction.Axis mappedAxis = net.minecraft.core.Direction.getApproximateNearest(
                    mappedVector[0], mappedVector[1], mappedVector[2]).getAxis();
                if (mappedAxis != axis && property.getPossibleValues().contains(mappedAxis))
                    result = result.setValue(property, mappedAxis);
            }
        }
        java.util.EnumMap<net.minecraft.core.Direction,
            net.minecraft.world.level.block.state.properties.Property> directional =
            new java.util.EnumMap<>(net.minecraft.core.Direction.class);
        for (net.minecraft.world.level.block.state.properties.Property property : state.getProperties()) {
            net.minecraft.core.Direction named = propertyDirection(property.getName());
            if (named != null) directional.put(named, property);
        }
        if (directional.size() > 1) {
            for (var targetEntry : directional.entrySet()) {
                net.minecraft.core.Direction target = targetEntry.getKey();
                net.minecraft.world.level.block.state.properties.Property targetProperty = targetEntry.getValue();
                net.minecraft.world.level.block.state.properties.Property bestSource = null;
                float bestDot = -Float.MAX_VALUE;
                for (var sourceEntry : directional.entrySet()) {
                    net.minecraft.core.Direction source = sourceEntry.getKey();
                    float dot = rotatedStateDot(source, target, rot, inverse);
                    if (dot > bestDot) { bestDot = dot; bestSource = sourceEntry.getValue(); }
                }
                if (bestSource != null) {
                    Comparable mappedValue = state.getValue(bestSource);
                    if (targetProperty.getPossibleValues().contains(mappedValue))
                        result = result.setValue(targetProperty, mappedValue);
                }
            }
        }
        return result;
    }

    private static net.minecraft.core.Direction propertyDirection(String name) {
        return switch (name) {
            case "down" -> net.minecraft.core.Direction.DOWN;
            case "up" -> net.minecraft.core.Direction.UP;
            case "north" -> net.minecraft.core.Direction.NORTH;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "west" -> net.minecraft.core.Direction.WEST;
            case "east" -> net.minecraft.core.Direction.EAST;
            default -> null;
        };
    }

    @SuppressWarnings("rawtypes")
    private static net.minecraft.core.Direction nearestAllowedDirection(
            net.minecraft.world.level.block.state.properties.Property property,
            net.minecraft.core.Direction direction, float[] rot, boolean inverse) {
        net.minecraft.core.Direction best = direction;
        float bestDot = -Float.MAX_VALUE;
        for (Object possible : property.getPossibleValues()) {
            if (!(possible instanceof net.minecraft.core.Direction candidate)) continue;
            float dot = rotatedStateDot(direction, candidate, rot, inverse);
            if (dot > bestDot) { bestDot = dot; best = candidate; }
        }
        return best;
    }

    private static final ThreadLocal<float[]> STATE_ROTATION_VECTOR =
        ThreadLocal.withInitial(() -> new float[3]);

    private static float rotatedStateDot(net.minecraft.core.Direction source,
                                         net.minecraft.core.Direction target,
                                         float[] rot, boolean inverse) {
        float[] mapped = STATE_ROTATION_VECTOR.get();
        rotateStateVector(source.getStepX(), source.getStepY(), source.getStepZ(), rot, inverse, mapped);
        return mapped[0] * target.getStepX() + mapped[1] * target.getStepY()
            + mapped[2] * target.getStepZ();
    }

    private static void rotateStateVector(float x, float y, float z, float[] rot,
                                          boolean inverse, float[] out) {
        float qx = inverse ? -rot[0] : rot[0];
        float qy = inverse ? -rot[1] : rot[1];
        float qz = inverse ? -rot[2] : rot[2];
        float tx = 2 * (qy * z - qz * y);
        float ty = 2 * (qz * x - qx * z);
        float tz = 2 * (qx * y - qy * x);
        out[0] = x + rot[3] * tx + qy * tz - qz * ty;
        out[1] = y + rot[3] * ty + qz * tx - qx * tz;
        out[2] = z + rot[3] * tz + qx * ty - qy * tx;
    }

    // local offset → world center
    public static float[] localToWorld(float lx, float ly, float lz, float[] pos, float[] rot) {
        float[] out = new float[3];
        localToWorld(lx, ly, lz, pos, rot, out);
        return out;
    }

    public static void localToWorld(float lx, float ly, float lz, float[] pos, float[] rot, float[] out) {
        float tx = 2*(rot[1]*lz-rot[2]*ly), ty = 2*(rot[2]*lx-rot[0]*lz), tz = 2*(rot[0]*ly-rot[1]*lx);
        float wx = lx+rot[3]*tx+rot[1]*tz-rot[2]*ty;
        float wy = ly+rot[3]*ty+rot[2]*tx-rot[0]*tz;
        float wz = lz+rot[3]*tz+rot[0]*ty-rot[1]*tx;
        out[0] = pos[0] + wx;
        out[1] = pos[1] + wy;
        out[2] = pos[2] + wz;
    }

    private static float[] worldToLocalPoint(float wx, float wy, float wz, float[] pos, float[] rot) {
        return worldVectorToLocal(wx-pos[0], wy-pos[1], wz-pos[2], rot);
    }

    private static float[] worldVectorToLocal(float x, float y, float z, float[] rot) {
        float iqx=-rot[0], iqy=-rot[1], iqz=-rot[2];
        float tx=2*(iqy*z-iqz*y), ty=2*(iqz*x-iqx*z), tz=2*(iqx*y-iqy*x);
        return new float[]{x+rot[3]*tx+iqy*tz-iqz*ty,
            y+rot[3]*ty+iqz*tx-iqx*tz, z+rot[3]*tz+iqx*ty-iqy*tx};
    }

    public static float[][] quaternionAxes(float[] q) {
        float x=q[0], y=q[1], z=q[2], w=q[3];
        return new float[][]{
            {1-2*(y*y+z*z), 2*(x*y+w*z), 2*(x*z-w*y)},
            {2*(x*y-w*z), 1-2*(x*x+z*z), 2*(y*z+w*x)},
            {2*(x*z+w*y), 2*(y*z-w*x), 1-2*(x*x+y*y)}
        };
    }

    public static boolean obbTouchesCell(float[] center, float[][] u, int x, int y, int z) {
        return new ObbCellTest(u).touches(center, x, y, z);
    }

    public static ObbCellTest obbCellTest(float[][] axes) { return new ObbCellTest(axes); }

    public static boolean axisAligned(float[][] axes) {
        for (int row=0; row<3; row++) {
            int cardinal=0;
            for (int column=0; column<3; column++) {
                float value=Math.abs(axes[row][column]);
                if (value > 0.999999f) cardinal++;
                else if (value > 0.000001f) return false;
            }
            if (cardinal != 1) return false;
        }
        return true;
    }

    public static final class ObbCellTest {
        private final float[] x = new float[15];
        private final float[] y = new float[15];
        private final float[] z = new float[15];
        private final float[] limit = new float[15];
        private int count;

        private ObbCellTest(float[][] u) {
            add(u,1,0,0); add(u,0,1,0); add(u,0,0,1);
            for (int j=0;j<3;j++) {
                float ux=u[j][0], uy=u[j][1], uz=u[j][2];
                add(u,ux,uy,uz);
                add(u,0,-uz,uy);
                add(u,uz,0,-ux);
                add(u,-uy,ux,0);
            }
        }

        private void add(float[][] u, float ax, float ay, float az) {
            if (ax*ax+ay*ay+az*az < 1e-10f) return;
            x[count]=ax; y[count]=ay; z[count]=az;
            float ra=.5f*(Math.abs(ax)+Math.abs(ay)+Math.abs(az));
            float rb=.5f*(Math.abs(u[0][0]*ax+u[0][1]*ay+u[0][2]*az)
                         +Math.abs(u[1][0]*ax+u[1][1]*ay+u[1][2]*az)
                         +Math.abs(u[2][0]*ax+u[2][1]*ay+u[2][2]*az));
            // Cross-product axes shrink near parallel edges; tolerance must shrink with them.
            limit[count++]=ra+rb-1e-4f*(float)Math.sqrt(ax*ax+ay*ay+az*az);
        }

        public boolean touches(float[] center, int bx, int by, int bz) {
            float dx=center[0]-(bx+.5f), dy=center[1]-(by+.5f), dz=center[2]-(bz+.5f);
            for (int i=0;i<count;i++)
                if (Math.abs(dx*x[i]+dy*y[i]+dz*z[i]) >= limit[i]) return false;
            return true;
        }
    }

    // world handle for this level, but only if a kontra actually lives there — block-change relay guard
    public static long worldHandleIfKontras(ServerLevel level) {
        if (KONTRAS.isEmpty()) return -1L;
        String key = levelKey(level);
        for (KontraEntry d : KONTRAS.values()) {
            if (d.levelKey().equals(key)) return d.worldHandle();
        }
        return -1L;
    }

    public static ServerLevel findLevel(MinecraftServer server, String key) {
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            if (levelKey(level).equals(key)) return level;
        }
        return null;
    }

    // buf layout per event: [parent_id_lo, parent_id_hi, comp_count, (block_count, lx,ly,lz...)...]
    private static void processSplits(MinecraftServer server) {
        for (var whEntry : WORLD_HANDLES.entrySet()) {
            String lk = whEntry.getKey();
            long wh   = whEntry.getValue();
            if (wh <= 0) continue;

            int[] buf = KoperPhysBridge.drainSplits(wh, 16384);
            if (buf == null) continue;

            ServerLevel level = findLevel(server, lk);

            int i = 0;
            while (i + 3 <= buf.length) {
                long parentId = ((long)buf[i] & 0xFFFFFFFFL) | (((long)buf[i+1] & 0xFFFFFFFFL) << 32);
                int  compCount = buf[i+2];
                i += 3;

                // parse all block lists upfront so i always advances correctly regardless of data validity
                List<List<BlockPos>> allComps = new ArrayList<>(compCount);
                for (int c = 0; c < compCount; c++) {
                    if (i >= buf.length) break;
                    int bc = buf[i++];
                    List<BlockPos> comp = new ArrayList<>(bc);
                    for (int b = 0; b < bc && i + 2 < buf.length; b++, i += 3)
                        comp.add(new BlockPos(buf[i], buf[i+1], buf[i+2]));
                    allComps.add(comp);
                }

                KontraEntry parentData = KONTRAS.get(parentId);
                float[] parentPos = CACHED_POS.get(parentId);
                float[] parentRot = CACHED_ROT.getOrDefault(parentId, new float[]{0f,0f,0f,1f});
                if (parentData == null || parentPos == null || level == null) continue;

                for (List<BlockPos> comp : allComps) {
                    int bc = comp.size();
                    if (bc == 0) continue;
                    if (splitReconnected(parentData, comp)) {
                        for (BlockPos local : comp) {
                            if (!parentData.blocks.containsKey(local)) continue;
                            float[] off = parentData.blockOffsets.get(local);
                            if (off != null) KoperPhysBridge.addBlockAtOffset(wh, parentId, off[0], off[1], off[2]);
                        }
                        parentData.invalidateSolidCells();
                        parentData.invalidateLogicCells();
                        parentData.grid(parentId).invalidateBoundaryContacts();
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys] stale split {} cancelled; the grid reconnected in time", parentId);
                        continue;
                    }

                    // centroid in PARENT LOCAL SPACE — avoids double rotation
                    // (if we'd convert to world first, then setTransform(parentRot) would rotate AGAIN)
                    float lcx = 0, lcy = 0, lcz = 0;
                    for (BlockPos lp : comp) {
                        float[] fOff = parentData.blockOffsets.get(lp);
                        lcx += fOff != null ? fOff[0] : lp.getX();
                        lcy += fOff != null ? fOff[1] : lp.getY();
                        lcz += fOff != null ? fOff[2] : lp.getZ();
                    }
                    lcx /= bc; lcy /= bc; lcz /= bc;
                    // new body world position = rotate local centroid by parentRot, translate by parentPos
                    float[] wc = localToWorld(lcx, lcy, lcz, parentPos, parentRot);
                    float cx = wc[0], cy = wc[1], cz = wc[2];

                    float[]       splitOffsets = new float[bc * 3];
                    int[]         blockCoords  = new int[bc * 3];
                    float[]       masses       = new float[bc];
                    int[]         stateIds     = new int[bc];
                    List<BlockState>  stateList = new ArrayList<>(bc);
                    List<BlockEntity> beList    = new ArrayList<>(bc);
                    Map<BlockPos, BlockPos> tickRemap = new LinkedHashMap<>(bc);
                    Map<BlockPos, BlockState> movedStates = new LinkedHashMap<>(bc);
                    Map<BlockPos, BlockPos> movedOrigins = new LinkedHashMap<>(bc);
                    Map<BlockPos, net.minecraft.nbt.CompoundTag> movedLocalData = new LinkedHashMap<>(bc);

                    int idx = 0;
                    for (int b = 0; b < bc; b++) {
                        BlockPos lp = comp.get(b);
                        BlockState st = parentData.blocks.get(lp);
                        if (st == null) {
                            removeLogicalCell(parentId, parentData, lp);
                            // block already gone from parent (double-break race or sync drift) — clean up, skip
                            parentData.blockEntities.remove(lp);
                            parentData.blockOffsets.remove(lp);
                            continue;
                        }
                        float[] fOff = parentData.blockOffsets.get(lp);
                        float lx = fOff != null ? fOff[0] : lp.getX();
                        float ly = fOff != null ? fOff[1] : lp.getY();
                        float lz = fOff != null ? fOff[2] : lp.getZ();
                        // offset stays in LOCAL space relative to local centroid
                        // when setTransform applies parentRot to the body, these rotate with it correctly
                        float ox = lx - lcx, oy = ly - lcy, oz = lz - lcz;
                        splitOffsets[idx*3]   = ox; splitOffsets[idx*3+1] = oy; splitOffsets[idx*3+2] = oz;
                        blockCoords[idx*3]    = Math.round(ox);
                        blockCoords[idx*3+1]  = Math.round(oy);
                        blockCoords[idx*3+2]  = Math.round(oz);
                        BlockPos newLocal = new BlockPos(blockCoords[idx*3], blockCoords[idx*3+1], blockCoords[idx*3+2]);
                        tickRemap.put(lp.immutable(), newLocal);
                        movedStates.put(lp.immutable(), st);
                        BlockPos origin = parentData.assembledFrom.remove(lp);
                        if (origin != null) movedOrigins.put(newLocal, origin);
                        net.minecraft.nbt.CompoundTag localPayload = parentData.localData.remove(lp);
                        if (localPayload != null) movedLocalData.put(newLocal, localPayload);
                        masses[idx] = KhysWeightBook.get(st).mass();
                        stateList.add(st);
                        stateIds[idx] = Block.getId(st);
                        beList.add(parentData.blockEntities.get(lp));

                        parentData.blocks.remove(lp);
                        removeLogicalCell(parentId, parentData, lp);
                        parentData.blockEntities.remove(lp);
                        parentData.blockOffsets.remove(lp);
                        idx++;
                    }
                    parentData.invalidateSolidCells();
                    parentData.invalidateLogicCells();
                    if (idx == 0) continue; // all blocks were missing, nothing to spawn
                    if (idx < bc) {
                        splitOffsets = Arrays.copyOf(splitOffsets, idx * 3);
                        blockCoords  = Arrays.copyOf(blockCoords,  idx * 3);
                        masses       = Arrays.copyOf(masses,       idx);
                        stateIds     = Arrays.copyOf(stateIds,     idx);
                        bc = idx;
                    }

                    int lightCount = 0;
                    for (BlockState st : stateList) if (st.is(LIGHT_BLOCKS)) lightCount++;

                    // splits happen inside the parent's OBB — the section cache already covers them
                    // float offsets for the same reason as assembly — no Rust-side centroid re-derivation
                    long newId = KoperPhysBridge.spawnKontraktionOffsets(wh, splitOffsets, masses, lightCount, cx, cy, cz);
                    if (newId <= 0) continue;
                    // apply parentRot — splits inherit the parent's rotation
                    KoperPhysBridge.setTransform(wh, newId, cx, cy, cz,
                        parentRot[0], parentRot[1], parentRot[2], parentRot[3]);

                    KontraEntry nd = new KontraEntry(lk, wh, stateList, splitOffsets);
                    nd.assembledFrom.putAll(movedOrigins);
                    nd.localData.putAll(movedLocalData);
                    pushMaterials(newId, nd);
                    nd.setAeroMode(parentData.aeroOverride());
                    parentData.grid(parentId).moveScheduledTicks(tickRemap, nd.grid(newId));
                    for (int b = 0; b < bc; b++) {
                        if (beList.get(b) != null) {
                            BlockPos nl = new BlockPos(blockCoords[b*3], blockCoords[b*3+1], blockCoords[b*3+2]);
                            BlockEntity be = relocateBlockEntity(beList.get(b), nd.grid(newId).toGrid(nl), stateList.get(b), level);
                            if (be != null) nd.blockEntities.put(nl, be);
                        }
                    }

                    KONTRAS.put(newId, nd);
                    indexLogicalEntry(newId, nd);
                    CACHED_POS.put(newId, new float[]{cx, cy, cz});
                    CACHED_ROT.put(newId, parentRot.clone());
                    if (level != null) bootstrapGrid(level, newId, nd);
                    pushAero(newId, nd);

                    if (server != null && level != null) {
                        var render = nd.renderArrays();
                        KhysicsNetworking.broadcastSpawn(level, new KenderSpawnPayload(newId,
                            new float[]{cx,cy,cz}, parentRot.clone(), render.stateIds(), render.offsets(), render.locals(),
                            blockEntityTags(nd, level)));
                    }
                    KoperPhysicsEvents.fireSpawn(newId, nd);
                    KoperPhysicsEvents.fireSplit(parentId, newId, java.util.Collections.unmodifiableMap(tickRemap));
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[KoperPhys] split {} → {} ({} bloki)", parentId, newId, bc);

                    KontraGrid parentGrid = parentData.grid(parentId);
                    parentGrid.invalidateBoundaryContacts();
                    parentGrid.invalidateExternalCache();
                    KontraGridContext.run(parentGrid, () -> {
                        for (var moved : movedStates.entrySet())
                            level.updateNeighborsAt(parentGrid.toGrid(moved.getKey()), moved.getValue().getBlock(), (net.minecraft.world.level.redstone.Orientation)null);
                    });
                }

                // re-broadcast parent so client drops the removed blocks
                float[] pp = CACHED_POS.get(parentId);
                float[] pr = CACHED_ROT.getOrDefault(parentId, new float[]{0f,0f,0f,1f});
                if (parentData.blocks.isEmpty())
                    destroyKontraktion(server, parentId);
                else if (server != null) {
                    // pp==null used to skip this entirely — the split-off blocks then stayed drawn on
                    // the parent as ghosts. send it anyway, the client just keeps its pose.
                    respawnWithNewBlocks(server, parentId, parentData, pp != null ? pp : KEEP_POSE.clone(), pr);
                    pushAero(parentId, parentData);
                    if (pp != null) refreshLogicContacts(level, parentId, parentData, pp, pr, false);
                }
            }
        }
    }

    static boolean splitReconnected(KontraEntry parent, List<BlockPos> reportedComponent) {
        Set<BlockPos> reported = new HashSet<>(reportedComponent);
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos pos : reportedComponent)
            if (parent.blocks.containsKey(pos) && visited.add(pos)) queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (!parent.blocks.containsKey(neighbor) || !visited.add(neighbor)) continue;
                if (!reported.contains(neighbor)) return true;
                queue.addLast(neighbor);
            }
        }
        return false;
    }

    // pose ledger — per-tick kontra deltas for steering/terrain lead/destroy release.
    // riding itself lives on the entities now (KontraGlue.Mind), not in here
    private static final Map<Long, float[]> PREV_KONTRA_POS = new ConcurrentHashMap<>();
    private static final Map<Long, float[]> PREV_KONTRA_ROT = new ConcurrentHashMap<>();
    private static final Map<Long, float[]> LAST_KONTRA_DELTA = new ConcurrentHashMap<>();
    private static final Map<Long, float[]> LAST_KONTRA_PREVIOUS_ROT = new ConcurrentHashMap<>();

    private static final double PILOT_CRUISE_BT = 0.80;
    private static final double PILOT_SPRINT_BT = 1.15;
    private static final double PILOT_VERTICAL_BT = 0.42;
    private static final double PILOT_HARD_BT = 1.55;
    // 2.35 was killing legit flight-stick runs mid-air ("pilot emergency stop speed/tick=2.51" =
    // the random freezes). koper decreed no speed limits — this only guards actual physics explosions,
    // clamp_runaway in rust catches the truly cursed stuff anyway
    private static final double PILOT_EMERGENCY_BT = 30.0;
    private static final double PILOT_YAW_TORQUE = 85.0;

    static long tickCount() { return tickCounter; }

    private static boolean holdsPilotItem(Player p) {
        var held = p.getMainHandItem();
        return held.getItem() instanceof KhysicsWand || held.is(HELM);
    }

    private static float massForPilot(KontraEntry data) {
        return Math.max(1.0f, KhysWeightBook.totalMass(data.states()));
    }

    private static Vec3 bodyForward(float[] rot) {
        double qx = rot[0], qy = rot[1], qz = rot[2], qw = rot[3];
        double x = 2.0 * (qw * qy + qx * qz);
        double z = 1.0 - 2.0 * (qx * qx + qy * qy);
        Vec3 f = new Vec3(x, 0.0, z);
        return f.lengthSqr() < 1.0e-5 ? new Vec3(0, 0, 1) : f.normalize();
    }

    private static double signedYawError(Vec3 from, Vec3 to) {
        double dot = from.x * to.x + from.z * to.z;
        double crossY = from.z * to.x - from.x * to.z;
        return Math.atan2(crossY, dot);
    }

    // ── piloting ──────────────────────────────────────────────────────────────
    private static final double STICK_CRUISE_BPS = 30.0;   // blocks/s
    private static final double STICK_SPRINT_BPS = 100.0;  // afterburner. no engine speed cap anymore
    private static final double STICK_LERP = 0.35;         // accel smoothing per tick

    // debug flight stick: hold it on deck and the kontra flies where you LOOK — full 3D
    // (look up + W = climb), space/shift = straight up/down, sprint = boost, no input =
    // hover brake. writes the body velocity DIRECTLY: forces got reset every physics step
    // and fought drag/aero/gravity, the result was "nic prawie sie nie dzieje". this is a
    // debug toy, it is ALLOWED to cheat.
    private static void flightStickLoop(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        for (ServerPlayer p : players) {
            if (!(p.getMainHandItem().getItem() instanceof KhysFlightStick)) continue;
            long kontraId = KontraGlue.mind(p).deckId;
            if (kontraId == 0L) continue;
            KontraEntry data = KONTRAS.get(kontraId);
            if (data == null) continue;
            net.minecraft.world.entity.player.Input in = p.getLastClientInput();
            if (in == null) continue;

            Vec3 look = p.getLookAngle(); // full 3D — pitch IS the climb control
            Vec3 fwd = look.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : look.normalize();
            Vec3 rgt = new Vec3(-fwd.z, 0, fwd.x);
            rgt = rgt.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : rgt.normalize();

            double dx = 0, dy = 0, dz = 0;
            if (in.forward())  { dx += fwd.x; dy += fwd.y; dz += fwd.z; }
            if (in.backward()) { dx -= fwd.x; dy -= fwd.y; dz -= fwd.z; }
            if (in.right())    { dx += rgt.x; dz += rgt.z; }
            if (in.left())     { dx -= rgt.x; dz -= rgt.z; }
            if (in.jump())     dy += 1.0;
            if (in.shift())    dy -= 1.0;

            // current velocity: per-tick pose delta → blocks/s
            float[] sv = getKontraVelocity(kontraId);
            double vx = (sv != null ? sv[0] : 0.0) * 20.0;
            double vy = (sv != null ? sv[1] : 0.0) * 20.0;
            double vz = (sv != null ? sv[2] : 0.0) * 20.0;

            double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double target = in.sprint() ? STICK_SPRINT_BPS : STICK_CRUISE_BPS;
            double wx = 0, wy = 0, wz = 0;
            if (dl > 1.0e-4) {
                wx = dx / dl * target;
                wy = dy / dl * target;
                wz = dz / dl * target;
            } // else hover: desired zero = brake in place, gravity included

            KoperPhysBridge.setVelocity(data.worldHandle(), kontraId,
                (float)(vx + (wx - vx) * STICK_LERP),
                (float)(vy + (wy - vy) * STICK_LERP),
                (float)(vz + (wz - vz) * STICK_LERP));

            // nose follows the look yaw — torque, but beefed up: forces reset per step so
            // the old helm gain barely nudged anything
            float mass = massForPilot(data);
            float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
            Vec3 flatLook = new Vec3(fwd.x, 0, fwd.z);
            if (flatLook.lengthSqr() > 1.0e-4) {
                double yawErr = signedYawError(bodyForward(rot), flatLook.normalize());
                if (Math.abs(yawErr) > 0.025) {
                    double ty = Math.max(-1.0, Math.min(1.0, yawErr)) * mass * PILOT_YAW_TORQUE * 3.0;
                    KoperPhysBridge.applyTorque(data.worldHandle(), kontraId, 0f, (float)ty, 0f);
                }
            }
        }
    }

    // hold the physics wand (or any #koperlib:helm item) while standing on a kontraktion and your WASD
    // steers it: forward/back along where you look, A/D strafe, jump = up, sneak = down, sprint = boost.
    // reads the real client input server-side via getLastClientInput() — no client mixin, no key hacks.
    // thrust scales with ship mass so a big galleon and a dinghy accelerate about the same.
    private static void pilotShips(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        for (ServerPlayer p : players) {
            if (!holdsPilotItem(p)) continue;
            long kontraId = KontraGlue.mind(p).deckId;
            if (kontraId == 0L) continue;
            KontraEntry data = KONTRAS.get(kontraId);
            if (data == null) continue;

            net.minecraft.world.entity.player.Input in = p.getLastClientInput();
            // CROUCH to take the helm — while sneaking you pilot the ship and can't walk off it
            if (in == null || !in.shift()) continue;

            // lock the pilot to the ship so they can't wander — their own walking is overridden with the
            // ship's velocity (the client pins itself via pilot lock, this just kills drift)
            float[] sv = getKontraVelocity(kontraId);
            double vx = sv != null ? sv[0] : 0.0;
            double vyNow = sv != null ? sv[1] : 0.0;
            double vz = sv != null ? sv[2] : 0.0;
            double hSpeed = Math.sqrt(vx * vx + vz * vz);
            double speed = Math.sqrt(vx * vx + vyNow * vyNow + vz * vz);
            if (speed > PILOT_EMERGENCY_BT) {
                float[] pos = CACHED_POS.get(kontraId);
                float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
                if (pos != null) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KoperPhys] pilot emergency stop kontra {} speed/tick={}", kontraId, String.format("%.2f", speed));
                    KoperPhysBridge.setTransform(data.worldHandle(), kontraId, pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3]);
                    LAST_KONTRA_DELTA.put(kontraId, new float[]{0f, 0f, 0f});
                }
                continue;
            }
            if (sv != null) p.setDeltaMovement(Vec3.ZERO); // rider is carried by anchor, not vanilla velocity

            // thrust in the player's horizontal look frame: WASD = move, jump = climb
            Vec3 look = p.getLookAngle();
            Vec3 fwd = new Vec3(look.x, 0, look.z);
            fwd = fwd.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : fwd.normalize();
            Vec3 rgt = new Vec3(-fwd.z, 0, fwd.x);
            float[] rot = CACHED_ROT.getOrDefault(kontraId, new float[]{0f, 0f, 0f, 1f});
            Vec3 bodyFwd = bodyForward(rot);
            double yawErr = signedYawError(bodyFwd, fwd);
            if (Math.abs(yawErr) > 0.025) {
                double ty = Math.max(-1.0, Math.min(1.0, yawErr)) * massForPilot(data) * PILOT_YAW_TORQUE;
                KoperPhysBridge.applyTorque(data.worldHandle(), kontraId, 0f, (float)ty, 0f);
            }

            double hx = 0, hz = 0, vy = 0;
            if (in.forward())  { hx += fwd.x; hz += fwd.z; }
            if (in.backward()) { hx -= fwd.x; hz -= fwd.z; }
            if (in.right())    { hx += rgt.x; hz += rgt.z; }
            if (in.left())     { hx -= rgt.x; hz -= rgt.z; }
            double hl = Math.sqrt(hx*hx + hz*hz);
            if (hl > 1.0e-4) { hx /= hl; hz /= hl; }      // no faster on the diagonal
            if (in.jump()) vy += 1;                        // sneak already = pilot, so jump-only for up

            if (hx == 0 && hz == 0 && vy == 0) continue;
            float mass  = massForPilot(data);
            double targetH = in.sprint() ? PILOT_SPRINT_BT : PILOT_CRUISE_BT;
            double desiredAlong = hx * vx + hz * vz;
            double hThrottle = hx == 0 && hz == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (targetH - desiredAlong) / Math.max(0.18, targetH)));
            if (hSpeed > PILOT_HARD_BT && desiredAlong > 0.0) hThrottle = 0.0;
            double vThrottle = vy == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (PILOT_VERTICAL_BT - vy * vyNow) / PILOT_VERTICAL_BT));
            double brake = hSpeed > PILOT_HARD_BT ? Math.min(1.0, (hSpeed - PILOT_HARD_BT) / 0.45) : 0.0;

            float th = mass * 34f;
            float tv = mass * 52f;
            float brakeForce = mass * 110f;
            KoperPhysBridge.applyForce(data.worldHandle(), kontraId,
                (float)(hx * th * hThrottle - vx * brakeForce * brake),
                (float)(vy * tv * vThrottle),
                (float)(hz * th * hThrottle - vz * brakeForce * brake));
        }
    }

    // per-tick pose ledger. the old velocityMatch carried every rider from HERE at 20Hz —
    // that whole circus moved into Entity.move (KontraGlue inherited motion), this just
    // remembers prev poses so getKontraVelocity has something to diff
    private static void poseLedger(MinecraftServer server) {
        for (var e : KONTRAS.entrySet()) {
            long id = e.getKey();
            float[] pos = CACHED_POS.get(id);
            if (pos == null || !Float.isFinite(pos[0]) || !Float.isFinite(pos[1]) || !Float.isFinite(pos[2])) continue;
            float[] rot = CACHED_ROT.getOrDefault(id, new float[]{0f,0f,0f,1f});
            float[] prev = PREV_KONTRA_POS.get(id);
            if (prev != null) {
                LAST_KONTRA_DELTA.put(id, new float[]{pos[0]-prev[0], pos[1]-prev[1], pos[2]-prev[2]});
            }
            float[] prevRot = PREV_KONTRA_ROT.get(id);
            if (prevRot != null) LAST_KONTRA_PREVIOUS_ROT.put(id, prevRot.clone());
            PREV_KONTRA_POS.put(id, pos.clone());
            PREV_KONTRA_ROT.put(id, rot.clone());
        }
    }

}
