package com.koper.koper_lib.kender;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import com.koper.koper_lib.physics.KoperPhys;

import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Set;

// client physics state — double-buffered prev/curr so renderer can lerp between ticks
// netty thread writes, render thread reads — volatile refs = close enough for a game
public class KenderClientState {
    public static final float TICK_NS = 50_000_000f;

    public static class KontraRenderData {
        public final long         id;
        public volatile float[]   prevPos;
        public volatile float[]   prevRot;
        public volatile float[]   currPos;
        public volatile float[]   currRot;
        private final float[]     renderPosScratch = new float[3];
        private final float[]     renderRotScratch = new float[4];
        public volatile long      prevServerTick;
        public volatile long      currServerTick;
        public volatile long      lastUpdateNanos;
        public volatile long      renderStartNanos;
        private volatile float[]  authoritativePrevPos;
        private volatile float[]  authoritativePrevRot;
        private volatile float[]  authoritativePos;
        private volatile float[]  authoritativeRot;
        private volatile long     authoritativePrevTick;
        private volatile long     authoritativeTick;
        // server says this kontra rests on the world grid → it's real solid blocks client-side too
        public volatile boolean   aligned;
        // packet pose parked here by the netty thread, goes live at the next client tick START
        public volatile float[]   pendingPos;
        public volatile float[]   pendingRot;
        public volatile long      pendingServerTick;
        public volatile boolean   pendingAligned;
        public volatile boolean   hasPending;
        public final float[]      offsets;
        // server-side key per block, flat [lx,ly,lz,...]. parallel to states/offsets.
        public final int[]        locals;
        public final BlockState[] states;
        public final byte[]       renderMasks;
        // same idea as renderMasks but for the fluid pass. a pool's interior cells have water on all
        // six sides and draw literally nothing, yet every one of them still cost a pooled state, a
        // pose push and a submit node EVERY FRAME. computed on block change, read per frame.
        public final byte[]       fluidMasks;
        // block ids in the shape Rust wants them, kept in step instead of rebuilt. create merging a
        // tank fires a state change for EVERY tank in the multiblock, and each one used to rebuild
        // this whole array, copy the whole hull into native memory and make Rust re-cull all of it.
        // sixteen times, in one tick, for one placed block. now it's one write and one flag.
        public final int[]        rustIds;
        public volatile boolean   rustDirty;
        // packed light per block index. -1 = never sampled. refreshed 1/8 per frame instead of
        // hammering the light engine twice for every block every frame — see KontraLightBook.
        public int[]              lightCache;
        // light the kontra makes for itself (torches/lamps riding on it), BFS'd over the local grid
        public byte[]             ownLight;
        public volatile boolean   ownLightDirty = true;
        // local integer pos → state — for fast getBlockState lookup by ClientLevelAccessMixin
        // key = the SERVER's local, shipped in the spawn payload. never re-derive it from offsets.
        public final Map<BlockPos, BlockState> localMap;
        public final Map<BlockPos, BlockEntity> blockEntities;
        public final Map<BlockPos, CompoundTag> localData;
        private BlockEntity[] blockEntityTickScratch = new BlockEntity[0];
        public final Map<BlockPos, float[]> offsetsByLocal;
        public final Map<BlockPos, Integer> indicesByLocal;
        private final Map<BlockPos, it.unimi.dsi.fastutil.ints.IntArrayList> offsetBuckets;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ClientExternal> externalCache =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private final BlockPos.MutableBlockPos renderMaskPosScratch = new BlockPos.MutableBlockPos();
        public final BlockPos gridAnchor;

        // bounding OBB half-extents — mirrors Rust sync_obb() logic, cached at spawn
        public final float obbHalfX, obbHalfY, obbHalfZ;

        KontraRenderData(long id, float[] pos, float[] rot, float[] offsets, int[] stateIds, int[] locals,
                CompoundTag[] blockEntityTags) {
            this.id = id;
            long slot = Math.floorMod(id, 1_600_000_000L);
            this.gridAnchor = new BlockPos(-20_000_000 + (int)(slot % 40_000L) * 1000, 0,
                -20_000_000 + (int)(slot / 40_000L) * 1000);
            this.prevPos = pos.clone();
            this.prevRot = rot.clone();
            this.currPos = pos.clone();
            this.currRot = rot.clone();
            long nowNs = System.nanoTime();
            this.lastUpdateNanos = nowNs;
            this.renderStartNanos = nowNs;
            this.prevServerTick = 0L;
            this.currServerTick = 0L;
            this.authoritativePrevPos = pos.clone();
            this.authoritativePrevRot = rot.clone();
            this.authoritativePos = pos.clone();
            this.authoritativeRot = rot.clone();
            this.offsets = offsets;
            this.locals  = locals != null && locals.length == offsets.length ? locals : roundedKeys(offsets);
            this.offsetBuckets = offsetBuckets(offsets, this.locals);
            this.states  = new BlockState[stateIds.length];
            this.renderMasks = new byte[stateIds.length];
            this.fluidMasks  = new byte[stateIds.length];
            this.rustIds = stateIds.clone();
            for (int i = 0; i < stateIds.length; i++) this.states[i] = Block.stateById(stateIds[i]);

            int n = offsets.length / 3;
            this.localMap = new HashMap<>(n * 2);
            this.blockEntities = new HashMap<>();
            this.localData = new HashMap<>();
            this.offsetsByLocal = new HashMap<>(n * 2);
            this.indicesByLocal = new HashMap<>(n * 2);
            float mx = 0, my = 0, mz = 0;
            for (int i = 0; i < n; i++) {
                // the server's own key, straight off the wire. round(offset) is what used to drift.
                BlockPos local = new BlockPos(this.locals[i*3], this.locals[i*3+1], this.locals[i*3+2]);
                this.localMap.put(local, this.states[i]);
                this.offsetsByLocal.put(local, new float[]{offsets[i*3], offsets[i*3+1], offsets[i*3+2]});
                this.indicesByLocal.put(local, i);
                if (i < blockEntityTags.length && blockEntityTags[i] != null) {
                    CompoundTag localPayload = com.koper.koper_lib.api.local.KoperLocalData.unpack(blockEntityTags[i]);
                    if (localPayload != null) this.localData.put(local, localPayload);
                    var level = Minecraft.getInstance().level;
                    if (level != null && blockEntityTags[i].contains("id")) {
                        BlockEntity be = BlockEntity.loadStatic(toGrid(local), this.states[i], blockEntityTags[i], level.registryAccess());
                        if (be != null) {
                            // two blocks rounding to the same local would silently eat one another's BE
                            BlockEntity clash = this.blockEntities.put(local, be);
                            if (clash != null && com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[GridDbg-C] BE CLASH kontra={} local={} {} zjadl {}",
                                    id, local, be.getType(), clash.getType());
                            be.setLevel(level);
                        } else if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
                            // create's steam engine NPEs when its shaft BE is missing here — this names why
                            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[GridDbg-C] BE DROP kontra={} local={} state={} (loadStatic null)",
                                id, local, this.states[i].getBlock());
                        }
                    } else if (level != null && this.states[i].hasBlockEntity()
                            && com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[GridDbg-C] BE NO-ID kontra={} local={} state={} — serwer nie dal tagu",
                            id, local, this.states[i].getBlock());
                    }
                }
                mx = Math.max(mx, Math.abs(offsets[i*3])     + 0.5f);
                my = Math.max(my, Math.abs(offsets[i*3 + 1]) + 0.5f);
                mz = Math.max(mz, Math.abs(offsets[i*3 + 2]) + 0.5f);
            }
            this.obbHalfX = mx == 0 ? 0.5f : mx;
            this.obbHalfY = my == 0 ? 0.5f : my;
            this.obbHalfZ = mz == 0 ? 0.5f : mz;
            rebuildRenderMasks();
        }

        // must hash to the exact same long as KoperPhys.blockStamp on the server or we'd beg for a
        // resync every second forever. rounded offsets + state id, summed so order can't matter.
        long pieczatka() {
            long sum = 0L;
            for (int i = 0; i < states.length; i++) {
                if (states[i] == null) continue;
                sum += com.koper.koper_lib.physics.KoperPhys.mixCell(
                    locals[i*3], locals[i*3+1], locals[i*3+2], Block.getId(states[i]));
            }
            return sum ^ ((long) states.length << 1);
        }

        public BlockPos toGrid(BlockPos local) { return gridAnchor.offset(local); }
        public BlockPos toLocal(BlockPos gridPos) { return gridPos.subtract(gridAnchor); }
        public void invalidateExternalCache() { externalCache.clear(); }
        void rebuildRenderMasks() {
            for (int i = 0; i < states.length; i++) rebuildRenderMask(i);
        }

        void rebuildRenderMasksAround(BlockPos local) {
            Integer own = indicesByLocal.get(local);
            if (own != null) rebuildRenderMask(own);
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                renderMaskPosScratch.set(local.getX() + direction.getStepX(), local.getY() + direction.getStepY(),
                    local.getZ() + direction.getStepZ());
                Integer neighbour = indicesByLocal.get(renderMaskPosScratch);
                if (neighbour != null) rebuildRenderMask(neighbour);
            }
        }

        private static int[] roundedKeys(float[] offsets) {
            int[] out = new int[offsets.length];
            for (int i = 0; i < offsets.length; i++) out[i] = Math.round(offsets[i]);
            return out;
        }

        // vanilla's own early-out, precomputed: no visible face means tesselate would walk all six
        // neighbours and return without emitting anything. we skip the whole submit instead.
        // deliberately looser than FluidRenderer (no isFaceOccludedByNeighbor refinement) — this may
        // only ever say "visible" more often than the real thing, never less, so no water goes missing.
        private void rebuildFluidMask(int i) {
            BlockState state = states[i];
            var fluid = state == null ? null : state.getFluidState();
            if (fluid == null || fluid.isEmpty()) { fluidMasks[i] = 0; return; }
            int lx = locals[i * 3], ly = locals[i * 3 + 1], lz = locals[i * 3 + 2];
            int mask = 0;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                renderMaskPosScratch.set(lx + direction.getStepX(), ly + direction.getStepY(), lz + direction.getStepZ());
                BlockState neighbour = localMap.get(renderMaskPosScratch);
                if (neighbour == null) neighbour = Blocks.AIR.defaultBlockState();
                var neighbourFluid = neighbour.getFluidState();
                boolean visible = direction == net.minecraft.core.Direction.UP
                    ? !fluid.getType().isSame(neighbourFluid.getType())
                    : net.minecraft.client.renderer.block.FluidRenderer.shouldRenderFace(
                        fluid, state, direction, neighbourFluid);
                if (visible) mask |= 1 << faceBit(direction);
            }
            fluidMasks[i] = (byte) mask;
        }

        private void rebuildRenderMask(int i) {
            rebuildFluidMask(i);
            BlockState state = states[i];
            if (state == null || state.isAir()) { renderMasks[i] = 0; return; }
            // neighbours must be looked up under the SERVER key or the lookup misses, the neighbour reads
            // as air and we draw a face that should be culled. that's the boiler you can see inside of.
            int lx = locals[i * 3];
            int ly = locals[i * 3 + 1];
            int lz = locals[i * 3 + 2];
            int mask = 0;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                renderMaskPosScratch.set(lx + direction.getStepX(), ly + direction.getStepY(), lz + direction.getStepZ());
                BlockState neighbour = localMap.get(renderMaskPosScratch);
                if (neighbour == null) neighbour = Blocks.AIR.defaultBlockState();
                if (Block.shouldRenderFace(state, neighbour, direction)) mask |= 1 << faceBit(direction);
            }
            renderMasks[i] = (byte)mask;
        }

        private static int faceBit(net.minecraft.core.Direction direction) {
            return switch (direction) {
                case EAST -> 0; case WEST -> 1; case UP -> 2;
                case DOWN -> 3; case SOUTH -> 4; case NORTH -> 5;
            };
        }
        private BlockEntity[] snapshotBlockEntities() {
            int size = blockEntities.size();
            if (blockEntityTickScratch.length < size)
                blockEntityTickScratch = new BlockEntity[Math.max(size, blockEntityTickScratch.length * 2 + 1)];
            int i = 0;
            for (BlockEntity be : blockEntities.values()) blockEntityTickScratch[i++] = be;
            java.util.Arrays.fill(blockEntityTickScratch, i, blockEntityTickScratch.length, null);
            return blockEntityTickScratch;
        }
    }

    private record ClientExternal(BlockPos pos, BlockState state) {}

    private static final Map<Long, KontraRenderData> KONTRAS = new ConcurrentHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> LOGICAL_STATES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockEntity> LOGICAL_BLOCK_ENTITIES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockEntity> PHYSICAL_BLOCK_ENTITIES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    // ghost hunt bookkeeping — how many stamp heartbeats in a row this kontra hashed wrong / went
    // unlisted, and when we last bothered the server about it
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap STAMP_MISS =
        new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap GONE_MISS =
        new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap RESYNC_ASKED =
        new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static long stampBeat;

    // bounding sphere radius per kontraktion — coarse-reject before doing rotation math
    private static final Map<Long, Float> RADII = new ConcurrentHashMap<>();
    private static final Set<String> CLIENT_TICK_ERRORS = ConcurrentHashMap.newKeySet();

    public static void spawn(long id, float[] pos, float[] rot, int[] stateIds, float[] offsets, int[] locals,
            CompoundTag[] blockEntityTags) {
        KontraRenderData standing = KONTRAS.get(id);
        boolean keepPose = !Float.isFinite(pos[0]) || !Float.isFinite(rot[0]);
        // NaN pose = "block list changed, pose unknown server-side, keep yours". without this the
        // server had to DROP the whole packet whenever the Rust snapshot hadn't landed = ghost blocks
        if (keepPose) {
            if (standing == null) return; // nothing to keep a pose from — wait for a real spawn
            pos = standing.currPos.clone();
            rot = standing.currRot.clone();
        }
        KontraRenderData d = new KontraRenderData(id, pos, rot, offsets, stateIds, locals, blockEntityTags);
        if (keepPose) carryPose(standing, d);
        KontraRenderData previous = KONTRAS.put(id, d);
        STAMP_MISS.remove(id);
        if (previous != null) {
            // keep the LIVING BE instances — chest lid / press animation state lives in fields the save
            // tag never carries, so a rebuilt BE snaps frozen. pour the fresh tag into the old instance.
            var lvl = Minecraft.getInstance().level;
            if (lvl != null) {
                for (var e : d.blockEntities.entrySet()) {
                    BlockEntity fresh = e.getValue();
                    BlockEntity old = previous.blockEntities.get(e.getKey());
                    if (old == null || old.getType() != fresh.getType() || old.isRemoved()) continue;
                    Integer idx = d.indicesByLocal.get(e.getKey());
                    CompoundTag tag = idx != null && idx >= 0 && idx < blockEntityTags.length
                        ? blockEntityTags[idx] : null;
                    try {
                        if (tag != null)
                            old.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                                net.minecraft.util.ProblemReporter.DISCARDING, lvl.registryAccess(), tag));
                        BlockState freshState = d.localMap.get(e.getKey());
                        if (freshState != null) old.setBlockState(freshState);
                        e.setValue(old);
                    } catch (Throwable ignored) {} // modded BE hated the reload — keep the fresh one
                }
            }
            for (BlockPos local : previous.localMap.keySet())
                LOGICAL_STATES.remove(previous.toGrid(local).asLong());
            for (BlockPos local : previous.blockEntities.keySet())
                LOGICAL_BLOCK_ENTITIES.remove(previous.toGrid(local).asLong());
        }
        for (var e : d.localMap.entrySet())
            LOGICAL_STATES.put(d.toGrid(e.getKey()).asLong(), e.getValue());
        for (var e : d.blockEntities.entrySet())
            LOGICAL_BLOCK_ENTITIES.put(d.toGrid(e.getKey()).asLong(), e.getValue());
        float maxR = 0f;
        for (int i = 0; i < offsets.length/3; i++) {
            float ox=offsets[i*3], oy=offsets[i*3+1], oz=offsets[i*3+2];
            float r = (float)Math.sqrt(ox*ox + oy*oy + oz*oz);
            if (r > maxR) maxR = r;
        }
        RADII.put(id, maxR + 1.5f);

        // feed block data to Rust geometry engine for face culling + AO
        // without this setBlocks call: getFaceMasks returns null → 100% overdraw
        KenderBridge.setBlocks(id, offsets, d.rustIds);
        KenderBridge.markDirty(id);

    }

    public static void updateTransform(long id, float[] newPos, float[] newRot) {
        updateTransform(id, 0L, newPos, newRot, false);
    }

    public static void spawnSnapshot(long serverTick, long id, float[] pos, float[] rot, int[] stateIds,
            float[] offsets, int[] locals, CompoundTag[] tags) {
        KontraRenderData previous = KONTRAS.get(id);
        boolean finitePose = Float.isFinite(pos[0]) && Float.isFinite(rot[0]);
        // Edits keep this body's reference frame; a split receives a different ID.
        spawn(id, previous == null ? pos : new float[]{Float.NaN, Float.NaN, Float.NaN},
            rot, stateIds, offsets, locals, tags);
        KontraRenderData d = KONTRAS.get(id);
        if (d == null || !finitePose) return;
        if (previous == null) {
            d.prevServerTick = d.currServerTick = serverTick;
            d.authoritativePrevTick = d.authoritativeTick = serverTick;
        } else if (serverTick > d.authoritativeTick && (!d.hasPending || serverTick > d.pendingServerTick)) {
            updateTransform(id, serverTick, pos, rot, d.hasPending ? d.pendingAligned : d.aligned);
        }
    }

    public static void updateTransform(long id, long serverTick, float[] newPos, float[] newRot, boolean aligned) {
        KontraRenderData d = KONTRAS.get(id);
        if (d == null) return;
        // netty thread only STASHES the pose — it goes live at the next client tick start
        // (latchTick). promoting it mid-tick had two evils: the ride glue and the collision
        // clamp could see two different poses inside one player tick, and the render lerp
        // started out of phase with the player's own xo->x lerp = 1st person micro-jitter
        d.pendingPos = newPos;
        d.pendingRot = newRot;
        d.pendingServerTick = serverTick;
        d.pendingAligned = aligned;
        d.hasPending = true;
    }

    public static void seedSnapshotMotion(long id,long previousTick,float[] previousPos,float[] previousRot,
                                         long currentTick,float[] currentPos,float[] currentRot,boolean aligned) {
        KontraRenderData d=KONTRAS.get(id);
        if (d == null || previousTick > currentTick) return;
        d.prevServerTick=d.authoritativePrevTick=previousTick;
        d.currServerTick=d.authoritativeTick=currentTick;
        d.prevPos=previousPos.clone(); d.prevRot=previousRot.clone();
        d.currPos=currentPos.clone(); d.currRot=currentRot.clone();
        d.authoritativePrevPos=previousPos.clone(); d.authoritativePrevRot=previousRot.clone();
        d.authoritativePos=currentPos.clone(); d.authoritativeRot=currentRot.clone();
        d.aligned=aligned;
        d.hasPending=false;
        d.lastUpdateNanos=d.renderStartNanos=System.nanoTime();
    }

    // client tick START — both the kontra render lerp and the player's interpolation now
    // run over the same tick window, and everything in this tick agrees on ONE pose
    public static void latchTick() {
        if (KONTRAS.isEmpty()) return;
        long nowNs = System.nanoTime();
        for (KontraRenderData d : KONTRAS.values()) {
            d.invalidateExternalCache();
            float[] newPos = d.hasPending ? d.pendingPos : null;
            float[] newRot = d.hasPending ? d.pendingRot : null;
            d.hasPending = false;
            if (newPos == null || newRot == null) {
                long targetTick = Math.min(d.currServerTick + 1L, d.authoritativeTick + 2L);
                if (!d.aligned && targetTick > d.currServerTick
                        && d.authoritativeTick > d.authoritativePrevTick) {
                    d.prevPos = d.currPos;
                    d.prevRot = d.currRot;
                    d.prevServerTick = d.currServerTick;
                    d.currPos = predictPosition(d, targetTick);
                    d.currRot = predictRotation(d, targetTick);
                    d.currServerTick = targetTick;
                } else {
                    d.prevPos = d.currPos;
                    d.prevRot = d.currRot;
                }
                continue;
            }
            d.aligned = d.pendingAligned;
            if (d.pendingServerTick > d.authoritativeTick) {
                d.authoritativePrevPos = d.authoritativePos;
                d.authoritativePrevRot = d.authoritativeRot;
                d.authoritativePrevTick = d.authoritativeTick;
                d.authoritativePos = newPos;
                d.authoritativeRot = newRot;
                d.authoritativeTick = d.pendingServerTick;
            }
            d.prevPos = d.currPos;
            d.prevRot = d.currRot;
            d.prevServerTick = d.currServerTick;
            long targetTick = d.pendingServerTick <= d.currServerTick && !d.aligned
                    ? Math.min(d.currServerTick + 1L, d.pendingServerTick + 2L)
                    : d.pendingServerTick;
            d.currServerTick = targetTick;
            d.currPos = targetTick == d.pendingServerTick ? newPos : predictPosition(d, targetTick);
            d.currRot = targetTick == d.pendingServerTick ? newRot : predictRotation(d, targetTick);
            d.lastUpdateNanos = nowNs;
            d.renderStartNanos = nowNs;
        }
        tickBlockEntities();
        tickBlockAnimations();
        refreshPhysicalBlockEntities();
    }

    private static float[] predictPosition(KontraRenderData d, long targetTick) {
        long ticks = d.authoritativeTick - d.authoritativePrevTick;
        if (ticks <= 0L) return d.authoritativePos.clone();
        float ahead = (targetTick - d.authoritativeTick) / (float)ticks;
        return new float[]{
            d.authoritativePos[0] + (d.authoritativePos[0] - d.authoritativePrevPos[0]) * ahead,
            d.authoritativePos[1] + (d.authoritativePos[1] - d.authoritativePrevPos[1]) * ahead,
            d.authoritativePos[2] + (d.authoritativePos[2] - d.authoritativePrevPos[2]) * ahead
        };
    }

    private static float[] predictRotation(KontraRenderData d, long targetTick) {
        long ticks = d.authoritativeTick - d.authoritativePrevTick;
        if (ticks <= 0L) return d.authoritativeRot.clone();
        float ahead = (targetTick - d.authoritativeTick) / (float)ticks;
        float[] previous = d.authoritativePrevRot;
        float[] current = d.authoritativeRot;
        float dot = previous[0] * current[0] + previous[1] * current[1]
                + previous[2] * current[2] + previous[3] * current[3];
        float sign = dot < 0f ? -1f : 1f;
        float x = current[0] + (current[0] - previous[0] * sign) * ahead;
        float y = current[1] + (current[1] - previous[1] * sign) * ahead;
        float z = current[2] + (current[2] - previous[2] * sign) * ahead;
        float w = current[3] + (current[3] - previous[3] * sign) * ahead;
        float length = (float)Math.sqrt(x * x + y * y + z * z + w * w);
        if (!Float.isFinite(length) || length < 1.0e-6f) return current.clone();
        return new float[]{x / length, y / length, z / length, w / length};
    }

    private static void refreshPhysicalBlockEntities() {
        PHYSICAL_BLOCK_ENTITIES.clear();
        java.util.List<BlockPos> stale = new java.util.ArrayList<>();
        for (KontraRenderData grid : KONTRAS.values()) {
            float[] pos = grid.currPos, rot = grid.currRot;
            if (pos == null || rot == null) continue;
            for (var e : grid.blockEntities.entrySet()) {
                float[] off = grid.offsetsByLocal.get(e.getKey());
                if (off == null) continue;
                float tx=2*(rot[1]*off[2]-rot[2]*off[1]);
                float ty=2*(rot[2]*off[0]-rot[0]*off[2]);
                float tz=2*(rot[0]*off[1]-rot[1]*off[0]);
                float x=pos[0]+off[0]+rot[3]*tx+rot[1]*tz-rot[2]*ty;
                float y=pos[1]+off[1]+rot[3]*ty+rot[2]*tx-rot[0]*tz;
                float z=pos[2]+off[2]+rot[3]*tz+rot[0]*ty-rot[1]*tx;
                BlockPos worldPos = BlockPos.containing(x,y,z);
                PHYSICAL_BLOCK_ENTITIES.put(worldPos.asLong(), e.getValue());
                stale.add(worldPos);
            }
        }
        // flywheel keeps drawing whatever it visualised before the block joined the kontra.
        // we draw it too, hence the static cog inside the spinning one. tell flywheel to let go.
        if (!stale.isEmpty()) com.koper.koper_lib.compat.create.KenderFlywheelEvict.evictAt(stale);
    }

    private static void tickBlockEntities() {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        for (KontraRenderData grid : KONTRAS.values()) {
            for (BlockEntity be : grid.snapshotBlockEntities()) {
                if (be == null) break;
                BlockPos local = grid.toLocal(be.getBlockPos());
                BlockState state = grid.localMap.get(local);
                if (state == null || grid.blockEntities.get(local) != be || be.isRemoved()) continue;
                @SuppressWarnings("unchecked")
                BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>)
                    state.getTicker(level, (BlockEntityType<BlockEntity>)be.getType());
                if (ticker == null) {
                    // no client ticker = no lid/press animation, full stop. name it once per type.
                    if (CLIENT_TICK_ERRORS.add("noticker:" + be.getType()))
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] NO client ticker for {} ({})",
                            be.getType(), state.getBlock());
                }
                if (ticker != null) try {
                    inGrid(grid, () -> {
                        ticker.tick(level, grid.toGrid(local), state, be);
                        return null;
                    });
                } catch (Throwable ex) {
                    String key=be.getType()+":"+ex.getClass().getName();
                    if (CLIENT_TICK_ERRORS.add(key))
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender] kontra client BE tick failed for {}", be.getType(), ex);
                }
            }
        }
    }

    private static void tickBlockAnimations() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null) return;
        var random = level.getRandom();
        int budget = 256;
        for (KontraRenderData grid : KONTRAS.values()) {
            float[] pos=grid.currPos;
            Float radius=RADII.get(grid.id);
            if (pos == null || radius == null) continue;
            double dx=player.getX()-pos[0], dy=player.getY()-pos[1], dz=player.getZ()-pos[2];
            double reach=radius+64.0;
            if (dx*dx+dy*dy+dz*dz > reach*reach) continue;
            int n=grid.states.length;
            int trials=n/8;
            if (random.nextInt(8) < n%8) trials++;
            trials=Math.min(trials, Math.min(64, budget));
            for (int i=0; i<trials; i++) {
                int index=random.nextInt(n);
                BlockState state=grid.states[index];
                if (state == null || state.isAir()) continue;
                BlockPos local=new BlockPos(grid.locals[index*3],
                    grid.locals[index*3+1], grid.locals[index*3+2]);
                try {
                    inGrid(grid, () -> {
                        BlockPos gridPos=grid.toGrid(local);
                        state.getBlock().animateTick(state, level, gridPos, random);
                        var fluid=state.getFluidState();
                        if (!fluid.isEmpty()) fluid.animateTick(level, gridPos, random);
                        return null;
                    });
                } catch (Throwable ex) {
                    String key=state.getBlock()+":"+ex.getClass().getName();
                    if (CLIENT_TICK_ERRORS.add(key))
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender] kontra animate tick failed for {}", state.getBlock(), ex);
                }
                if (--budget <= 0) return;
            }
        }
    }

    // server heartbeat landed: does our copy still hash the same? two bad beats in a row and we ask
    // for the real block list. one bad beat is normal — a spawn payload can be mid-flight when the
    // stamp was taken, and then we'd spam resyncs for nothing.
    public static void checkStamps(long[] ids, long[] stamps) {
        stampBeat++;
        java.util.HashSet<Long> listed = new java.util.HashSet<>(ids.length * 2);
        for (int i = 0; i < ids.length; i++) {
            long id = ids[i];
            listed.add(id);
            if (KenderSyncClient.pending(id)) { STAMP_MISS.remove(id); GONE_MISS.remove(id); continue; }
            // k == null counts as a miss too — the mirror bug: a spawn payload that never landed
            // leaves a kontra the server has and we don't. same two-strike cure.
            KontraRenderData k = KONTRAS.get(id);
            if (k != null && k.pieczatka() == stamps[i]) { STAMP_MISS.remove(id); continue; }
            if (STAMP_MISS.addTo(id, 1) + 1 < 2) continue;
            long asked = RESYNC_ASKED.get(id);
            if (asked != 0L && stampBeat - asked < 5) continue;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender] kontra {} desynced (ghost bloki?) — proszę o resync", id);
            askResync(id);
        }
        // whole kontra the server never mentions = its KenderRemovePayload got eaten. two strikes.
        for (long id : KONTRAS.keySet().stream().mapToLong(Long::longValue).toArray()) {
            if (KenderSyncClient.pending(id) || listed.contains(id)) { GONE_MISS.remove(id); continue; }
            if (GONE_MISS.addTo(id, 1) + 1 < 2) continue;
            GONE_MISS.remove(id);
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender] kontra {} nie istnieje na serwerze — sprzątam", id);
            KontraRideClient.onKontraRemoved(id);
            remove(id);
        }
    }

    // one block in or out. rebuilds the flat arrays (an arraycopy) and CARRIES THE BLOCK ENTITIES
    // OVER — the old full-respawn path ran loadStatic on every chest on board for a single placement.
    public static void applyDelta(long id, BlockPos local, int stateId, float ox, float oy, float oz,
                                  boolean removed, CompoundTag tag) {
        KontraRenderData old = KONTRAS.get(id);
        if (old == null) return;                       // no body yet — the spawn payload will carry it
        Integer at = old.indicesByLocal.get(local);
        if (removed && at == null) return;             // already gone, nothing to do
        int n = old.states.length;
        int size = removed ? n - 1 : (at != null ? n : n + 1);
        if (size <= 0) { remove(id); return; }

        float[] offsets = new float[size * 3];
        int[] locals = new int[size * 3];
        int[] stateIds = new int[size];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (at != null && i == at) continue;       // dropped or about to be rewritten at the end
            System.arraycopy(old.offsets, i * 3, offsets, w * 3, 3);
            System.arraycopy(old.locals, i * 3, locals, w * 3, 3);
            stateIds[w] = Block.getId(old.states[i]);
            w++;
        }
        if (!removed) {
            offsets[w*3] = ox; offsets[w*3+1] = oy; offsets[w*3+2] = oz;
            locals[w*3] = local.getX(); locals[w*3+1] = local.getY(); locals[w*3+2] = local.getZ();
            stateIds[w] = stateId;
        }

        // null tags everywhere = the constructor loads no block entities at all, we hand ours over
        KontraRenderData fresh = new KontraRenderData(id, old.currPos.clone(), old.currRot.clone(),
            offsets, stateIds, locals, new CompoundTag[size]);
        fresh.blockEntities.putAll(old.blockEntities);
        fresh.localData.putAll(old.localData);
        fresh.blockEntities.remove(local);
        fresh.localData.remove(local);
        carryPose(old, fresh);
        KONTRAS.put(id, fresh);

        LOGICAL_STATES.remove(old.toGrid(local).asLong());
        LOGICAL_BLOCK_ENTITIES.remove(old.toGrid(local).asLong());
        if (!removed) {
            BlockState state = Block.stateById(stateId);
            LOGICAL_STATES.put(fresh.toGrid(local).asLong(), state);
            if (tag != null) {
                CompoundTag payload = com.koper.koper_lib.api.local.KoperLocalData.unpack(tag);
                if (payload != null) fresh.localData.put(local, payload);
                var lvl = Minecraft.getInstance().level;
                if (lvl != null && tag.contains("id")) {
                    BlockEntity be = BlockEntity.loadStatic(fresh.toGrid(local), state, tag, lvl.registryAccess());
                    if (be != null) {
                        be.setLevel(lvl);
                        fresh.blockEntities.put(local, be);
                        LOGICAL_BLOCK_ENTITIES.put(fresh.toGrid(local).asLong(), be);
                    }
                }
            }
        }

        float maxR = 0f;
        for (int i = 0; i < size; i++) {
            float x = offsets[i*3], y = offsets[i*3+1], z = offsets[i*3+2];
            float r = (float)Math.sqrt(x*x + y*y + z*z);
            if (r > maxR) maxR = r;
        }
        RADII.put(id, maxR + 1.5f);
        fresh.rustDirty = true;
        KontraLightSpill.markDirty(fresh);
        STAMP_MISS.remove(id);
    }

    private static void carryPose(KontraRenderData from, KontraRenderData to) {
        to.prevPos = from.prevPos; to.prevRot = from.prevRot;
        to.currPos = from.currPos; to.currRot = from.currRot;
        to.prevServerTick = from.prevServerTick;
        to.currServerTick = from.currServerTick;
        to.lastUpdateNanos = from.lastUpdateNanos;
        to.renderStartNanos = from.renderStartNanos;
        to.aligned = from.aligned;
        // Geometry changed, not the authority or the next client tick's queued pose.
        to.authoritativePrevPos = from.authoritativePrevPos;
        to.authoritativePrevRot = from.authoritativePrevRot;
        to.authoritativePos = from.authoritativePos;
        to.authoritativeRot = from.authoritativeRot;
        to.authoritativePrevTick = from.authoritativePrevTick;
        to.authoritativeTick = from.authoritativeTick;
        to.pendingPos = from.pendingPos;
        to.pendingRot = from.pendingRot;
        to.pendingServerTick = from.pendingServerTick;
        to.pendingAligned = from.pendingAligned;
        to.hasPending = from.hasPending;
    }

    // one ask per kontra per ~5 heartbeats, whoever noticed the drift
    private static void askResync(long id) {
        long asked = RESYNC_ASKED.get(id);
        if (asked != 0L && stampBeat - asked < 5) return;
        RESYNC_ASKED.put(id, stampBeat);
        STAMP_MISS.remove(id);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new com.koper.koper_lib.network.KenderResyncPayload(id));
    }

    public static KontraRenderData getById(long id) { return KONTRAS.get(id); }

    public static KontraRenderData getByLogical(BlockPos gridPos) {
        for (KontraRenderData grid : KONTRAS.values()) {
            BlockPos local = grid.toLocal(gridPos);
            if (grid.localMap.containsKey(local) || grid.blockEntities.containsKey(local))
                return grid;
        }
        return null;
    }

    public static void remove(long id) {
        KontraRenderData removed = KONTRAS.remove(id);
        if (removed != null) {
            for (BlockPos local : removed.localMap.keySet())
                LOGICAL_STATES.remove(removed.toGrid(local).asLong());
            for (BlockPos local : removed.blockEntities.keySet())
                LOGICAL_BLOCK_ENTITIES.remove(removed.toGrid(local).asLong());
        }
        RADII.remove(id);
        STAMP_MISS.remove(id);
        GONE_MISS.remove(id);
        RESYNC_ASKED.remove(id);
        com.koper.koper_lib.api.core.KenderGeoBridge.drop(id);
        KenderBridge.remove(id); // free Rust geometry data
    }
    public static void clear() {
        KONTRAS.clear();
        LOGICAL_STATES.clear();
        LOGICAL_BLOCK_ENTITIES.clear();
        PHYSICAL_BLOCK_ENTITIES.clear();
        com.koper.koper_lib.compat.create.KenderFlywheelEvict.forget();
        RADII.clear();
        STAMP_MISS.clear();
        GONE_MISS.clear();
        RESYNC_ASKED.clear();
        KenderBridge.clearAll();
        com.koper.koper_lib.api.core.KenderGeoBridge.clear();
    }
    public static boolean isEmpty()              { return KONTRAS.isEmpty(); }
    public static Collection<KontraRenderData> all() { return KONTRAS.values(); }

    private static final ThreadLocal<KontraRenderData> ACTIVE_GRID = new ThreadLocal<>();

    public static KontraRenderData activeGrid() { return ACTIVE_GRID.get(); }

    // is this block entity one of ours, riding a kontra? identity check, not position: a grid BE
    // lives at coordinates that mean nothing in the world, so comparing positions finds nothing.
    // flywheel asks this before instancing so it does not draw a second copy on top of ours
    public static boolean ownsBlockEntity(BlockEntity be) {
        if (be == null || KONTRAS.isEmpty()) return false;
        for (KontraRenderData k : KONTRAS.values()) {
            if (k.blockEntities.containsValue(be)) return true;
        }
        return false;
    }

    public static BlockEntity logicalBlockEntityAt(BlockPos gridPos) {
        return LOGICAL_BLOCK_ENTITIES.get(gridPos.asLong());
    }

    public static BlockEntity physicalBlockEntityAt(BlockPos worldPos) {
        return PHYSICAL_BLOCK_ENTITIES.get(worldPos.asLong());
    }

    public static BlockState logicalBlockStateAt(BlockPos gridPos) {
        return LOGICAL_STATES.get(gridPos.asLong());
    }

    public static void blockEvent(long id, BlockPos local, int eventId, int eventData) {
        KontraRenderData grid = KONTRAS.get(id);
        var level = Minecraft.getInstance().level;
        if (grid == null || level == null) return;
        BlockState state = grid.localMap.get(local);
        BlockEntity be = grid.blockEntities.get(local);
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] blockEvent kontra={} local={} id={} state={} be={}",
                id, local, eventId, state != null ? state.getBlock() : "MISS", be != null ? be.getType() : "MISS");
        if (state != null) inGrid(grid, () -> {
            if (be == null || !be.triggerEvent(eventId, eventData))
                state.triggerEvent(level, grid.toGrid(local), eventId, eventData);
            return null;
        });
    }

    public static void updateBlockEntity(long id, BlockPos local, CompoundTag tag) {
        KontraRenderData grid = KONTRAS.get(id);
        var level = Minecraft.getInstance().level;
        if (grid == null || level == null || tag == null) return;
        BlockEntity be = grid.blockEntities.get(local);
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] beUpd kontra={} local={} be={}",
                id, local, be != null ? be.getType() : "MISS");
        if (be == null) {
            // brand-new BE at this cell (piston move spawns PistonMovingBlockEntity into a cell that
            // never had one) — loadStatic reads the type from the tag, EntityBlock path returns null here
            BlockState state = grid.localMap.get(local);
            if (state == null) return;
            BlockEntity created = BlockEntity.loadStatic(grid.toGrid(local), state, tag, level.registryAccess());
            if (created == null) return;
            created.setLevel(level);
            grid.blockEntities.put(local.immutable(), created);
            return;
        }
        be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
    }

    // replayed placement must keep the instance that already received lid/press events
    static void reuseStreamBlockEntity(long id, BlockPos local, BlockEntity live, CompoundTag tag) {
        KontraRenderData grid = KONTRAS.get(id);
        if (grid == null || live == null || live.isRemoved()) return;
        BlockEntity fresh = grid.blockEntities.get(local);
        BlockState state = grid.localMap.get(local);
        var level = Minecraft.getInstance().level;
        if (fresh == null || fresh == live || fresh.getType() != live.getType()
                || state == null || !live.isValidBlockState(state) || level == null) return;
        try {
            if (tag != null) live.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
            live.setBlockState(state);
            grid.blockEntities.put(local, live);
            LOGICAL_BLOCK_ENTITIES.put(grid.toGrid(local).asLong(), live);
        } catch (RuntimeException failure) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[Kender] cannot retain streamed block entity at {}", local, failure);
        }
    }

    public static void updateBlockState(long id, BlockPos local, int stateId) {
        KontraRenderData grid = KONTRAS.get(id);
        if (grid == null) return;
        Integer index = grid.indicesByLocal.get(local);
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg-C] stateUpd kontra={} local={} idx={} -> {}",
                id, local, index, Block.stateById(stateId).getBlock());
        if (index == null || index < 0 || index >= grid.states.length) {
            // THE boiler bug: create merges fluid tanks by swapping their blockstate, same block, so it
            // rides the cheap per-block payload. our local key missed and we just returned — that tank
            // kept the unmerged texture forever while its neighbours merged. never drop it silently.
            // bugopis chcial wiedziec CZY to klucz obok, czy komorka ktorej klient w ogole nie dostal.
            // sasiedzi odpowiadaja na to od razu: sa = zgubiony jeden delta, nie ma = zgubiony caly blok
            StringBuilder znani = new StringBuilder();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values())
                if (grid.indicesByLocal.containsKey(local.relative(d))) znani.append(d).append(' ');
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                "[Kender] kontra {} nie zna local={} ({}), sasiedzi ktorych zna: [{}], blokow={}, prosze o resync",
                id, local, Block.stateById(stateId).getBlock(),
                znani.isEmpty() ? "ZADNYCH" : znani.toString().trim(), grid.states.length);
            askResync(id);
            return;
        }
        BlockState state = Block.stateById(stateId);
        grid.states[index] = state;
        grid.localMap.put(local, state);
        grid.rebuildRenderMasksAround(local);
        // a lamp went in or out — the whole own-light flood has to be redone, it's cheap and rare
        KontraLightSpill.markDirty(grid);
        LOGICAL_STATES.put(grid.toGrid(local).asLong(), state);
        BlockEntity be = grid.blockEntities.get(local);
        if (be != null) {
            // same predicate the server grid uses — a finished piston move swaps moving_piston for the
            // real block, the stale moving BE would keep ghost-rendering the head forever
            if (be.isValidBlockState(state)) be.setBlockState(state);
            else { grid.blockEntities.remove(local); be.setRemoved(); }
        }
        grid.rustIds[index] = stateId;
        grid.rustDirty = true;
    }

    // one upload per frame instead of one per changed block. sixteen tanks merging in the same tick
    // is sixteen identical full-hull uploads otherwise, and Rust re-culls every face each time.
    static void flushRustBlocks() {
        for (KontraRenderData k : KONTRAS.values()) {
            if (!k.rustDirty) continue;
            k.rustDirty = false;
            KenderBridge.setBlocks(k.id, k.offsets, k.rustIds);
            KenderBridge.markDirty(k.id);
        }
    }

    public static <T> T inGrid(KontraRenderData grid, Supplier<T> action) {
        KontraRenderData old = ACTIVE_GRID.get();
        ACTIVE_GRID.set(grid);
        try { return action.get(); }
        finally {
            if (old == null) ACTIVE_GRID.remove();
            else ACTIVE_GRID.set(old);
        }
    }

    public static <T> T outsideGrid(Supplier<T> action) {
        KontraRenderData old = ACTIVE_GRID.get();
        ACTIVE_GRID.remove();
        boolean bypass = KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get();
        KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
        try { return action.get(); }
        finally {
            KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(bypass);
            if (old != null) ACTIVE_GRID.set(old);
        }
    }

    public static double[] gridPointToWorld(KontraRenderData grid, double x, double y, double z) {
        double lx=x-grid.gridAnchor.getX()-0.5, ly=y-grid.gridAnchor.getY()-0.5, lz=z-grid.gridAnchor.getZ()-0.5;
        double best=Double.POSITIVE_INFINITY;
        BlockPos nearest=null;
        float[] nearestOffset=null;
        for (var entry : grid.offsetsByLocal.entrySet()) {
            double dx=lx-entry.getKey().getX(), dy=ly-entry.getKey().getY(), dz=lz-entry.getKey().getZ();
            double distance=dx*dx+dy*dy+dz*dz;
            if (distance < best) { best=distance; nearest=entry.getKey(); nearestOffset=entry.getValue(); }
        }
        if (nearest == null || nearestOffset == null || best > 64.0 || grid.currPos == null || grid.currRot == null)
            return null;
        float ox=nearestOffset[0]+(float)(lx-nearest.getX());
        float oy=nearestOffset[1]+(float)(ly-nearest.getY());
        float oz=nearestOffset[2]+(float)(lz-nearest.getZ());
        float[] world=KoperPhys.localToWorld(ox, oy, oz, grid.currPos, grid.currRot);
        return new double[]{world[0],world[1],world[2]};
    }

    public static double[] boundGridPointToWorld(KontraRenderData grid, double x, double y, double z) {
        double lx=x-grid.gridAnchor.getX()-0.5, ly=y-grid.gridAnchor.getY()-0.5, lz=z-grid.gridAnchor.getZ()-0.5;
        double best=Double.POSITIVE_INFINITY;
        BlockPos nearest=null;
        float[] nearestOffset=null;
        for (var entry : grid.offsetsByLocal.entrySet()) {
            double dx=lx-entry.getKey().getX(), dy=ly-entry.getKey().getY(), dz=lz-entry.getKey().getZ();
            double distance=dx*dx+dy*dy+dz*dz;
            if (distance < best) { best=distance; nearest=entry.getKey(); nearestOffset=entry.getValue(); }
        }
        if (nearest == null || nearestOffset == null || grid.currPos == null || grid.currRot == null)
            return null;
        float ox=nearestOffset[0]+(float)(lx-nearest.getX());
        float oy=nearestOffset[1]+(float)(ly-nearest.getY());
        float oz=nearestOffset[2]+(float)(lz-nearest.getZ());
        float[] world=KoperPhys.localToWorld(ox, oy, oz, grid.currPos, grid.currRot);
        return new double[]{world[0],world[1],world[2]};
    }

    public static double[] worldPointToBoundGrid(KontraRenderData grid, double x, double y, double z) {
        if (grid.currPos == null || grid.currRot == null) return null;
        float dx=(float)x-grid.currPos[0], dy=(float)y-grid.currPos[1], dz=(float)z-grid.currPos[2];
        float[] q=grid.currRot;
        float iqx=-q[0], iqy=-q[1], iqz=-q[2];
        float tx=2*(iqy*dz-iqz*dy), ty=2*(iqz*dx-iqx*dz), tz=2*(iqx*dy-iqy*dx);
        float lx=dx+q[3]*tx+iqy*tz-iqz*ty;
        float ly=dy+q[3]*ty+iqz*tx-iqx*tz;
        float lz=dz+q[3]*tz+iqx*ty-iqy*tx;
        return new double[]{
            grid.gridAnchor.getX()+0.5+lx,
            grid.gridAnchor.getY()+0.5+ly,
            grid.gridAnchor.getZ()+0.5+lz
        };
    }

    public static double[] gridVectorToWorld(KontraRenderData grid, double x, double y, double z) {
        if (grid.currRot == null) return null;
        float[] world=KoperPhys.localToWorld((float)x,(float)y,(float)z,
            new float[]{0f,0f,0f},grid.currRot);
        return new double[]{world[0],world[1],world[2]};
    }

    public static BlockState gridBlockState(KontraRenderData grid, Level level, BlockPos gridPos) {
        BlockPos local = grid.toLocal(gridPos);
        BlockState own = grid.localMap.get(local);
        return own != null ? own : externalCell(grid, level, local).state();
    }

    public static BlockEntity gridBlockEntity(KontraRenderData grid, Level level, BlockPos gridPos) {
        BlockPos local = grid.toLocal(gridPos);
        BlockEntity own = grid.blockEntities.get(local);
        if (own != null) return own;
        ClientExternal external = externalCell(grid, level, local);
        return external.state().isAir() ? null : outsideGrid(() -> level.getBlockEntity(external.pos()));
    }

    private static ClientExternal externalCell(KontraRenderData grid, Level level, BlockPos local) {
        ClientExternal cached = grid.externalCache.get(local.asLong());
        if (cached != null) return cached;
        float[] pos = grid.currPos, rot = grid.currRot;
        if (pos == null || rot == null) return new ClientExternal(BlockPos.ZERO, Blocks.AIR.defaultBlockState());
        float[] off = gridOffsetFor(grid, local);
        float[] center = KoperPhys.localToWorld(off[0], off[1], off[2], pos, rot);
        float[][] axes = KoperPhys.quaternionAxes(rot);
        float ex=.5f*(Math.abs(axes[0][0])+Math.abs(axes[1][0])+Math.abs(axes[2][0]));
        float ey=.5f*(Math.abs(axes[0][1])+Math.abs(axes[1][1])+Math.abs(axes[2][1]));
        float ez=.5f*(Math.abs(axes[0][2])+Math.abs(axes[1][2])+Math.abs(axes[2][2]));
        BlockPos bestPos = BlockPos.containing(center[0], center[1], center[2]);
        BlockState best = Blocks.AIR.defaultBlockState();
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x=(int)Math.floor(center[0]-ex); x<=(int)Math.floor(center[0]+ex); x++)
            for (int y=(int)Math.floor(center[1]-ey); y<=(int)Math.floor(center[1]+ey); y++)
                for (int z=(int)Math.floor(center[2]-ez); z<=(int)Math.floor(center[2]+ez); z++) {
                    if (!KoperPhys.obbTouchesCell(center, axes, x, y, z)) continue;
                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockState state = outsideGrid(() -> level.getBlockState(candidate));
                    if (state.isAir()) continue;
                    double dx=x+.5-center[0], dy=y+.5-center[1], dz=z+.5-center[2];
                    double distance=dx*dx+dy*dy+dz*dz;
                    if (distance < bestDistance) {
                        bestDistance=distance;
                        bestPos=candidate;
                        best=state;
                    }
                }
        ClientExternal result = new ClientExternal(bestPos, KoperPhys.stateToGrid(best, rot));
        grid.externalCache.put(local.asLong(), result);
        return result;
    }

    private static float[] gridOffsetFor(KontraRenderData grid, BlockPos local) {
        float[] exact = grid.offsetsByLocal.get(local);
        if (exact != null) return exact;
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbour = local.relative(direction);
            float[] off = grid.offsetsByLocal.get(neighbour);
            if (off != null) return new float[]{off[0]+local.getX()-neighbour.getX(),
                off[1]+local.getY()-neighbour.getY(), off[2]+local.getZ()-neighbour.getZ()};
        }
        float best = Float.POSITIVE_INFINITY;
        BlockPos nearest = null;
        float[] nearestOffset = null;
        for (var entry : grid.offsetsByLocal.entrySet()) {
            float dx=local.getX()-entry.getKey().getX(), dy=local.getY()-entry.getKey().getY();
            float dz=local.getZ()-entry.getKey().getZ(), distance=dx*dx+dy*dy+dz*dz;
            if (distance < best) { best=distance; nearest=entry.getKey(); nearestOffset=entry.getValue(); }
        }
        if (nearest == null) return new float[]{local.getX(), local.getY(), local.getZ()};
        return new float[]{nearestOffset[0]+local.getX()-nearest.getX(),
            nearestOffset[1]+local.getY()-nearest.getY(), nearestOffset[2]+local.getZ()-nearest.getZ()};
    }

    // fraction through the current tick. pose is latched at client-tick START, so partialTick IS this
    // exact tick window — the same clock the player + camera interpolate on. no nanoTime drift against
    // the tick grid means a falling kontra glides in lockstep with everything else instead of shimmering.
    public static float renderAlpha(KontraRenderData k, long nowNs) {
        if (k == null) return 1.0f;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return pt < 0f ? 0f : (pt > 1f ? 1f : pt);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float[] renderPos(KontraRenderData k, long nowNs) {
        if (k == null || k.currPos == null) return null;
        if (k.prevPos == null) return k.currPos;
        float a = renderAlpha(k, nowNs);
        float[] out = k.renderPosScratch;
        out[0] = lerp(k.prevPos[0], k.currPos[0], a);
        out[1] = lerp(k.prevPos[1], k.currPos[1], a);
        out[2] = lerp(k.prevPos[2], k.currPos[2], a);
        return out;
    }

    public static float[] renderRot(KontraRenderData k, long nowNs) {
        if (k == null || k.currRot == null) return null;
        if (k.prevRot == null) return k.currRot;
        float a = renderAlpha(k, nowNs);
        float px = k.prevRot[0], py = k.prevRot[1], pz = k.prevRot[2], pw = k.prevRot[3];
        float cx = k.currRot[0], cy = k.currRot[1], cz = k.currRot[2], cw = k.currRot[3];
        double dot = px * cx + py * cy + pz * cz + pw * cw;
        if (dot < 0.0) {
            dot = -dot;
            cx = -cx; cy = -cy; cz = -cz; cw = -cw;
        }
        double s0;
        double s1;
        if (dot > 0.9995) {
            s0 = 1.0 - a;
            s1 = a;
        } else {
            dot = Math.max(0.0, Math.min(1.0, dot));
            double theta0 = Math.acos(dot);
            double theta = theta0 * a;
            double sinTheta = Math.sin(theta);
            double sinTheta0 = Math.sin(theta0);
            s0 = Math.cos(theta) - dot * sinTheta / sinTheta0;
            s1 = sinTheta / sinTheta0;
        }
        float x = (float)(s0 * px + s1 * cx);
        float y = (float)(s0 * py + s1 * cy);
        float z = (float)(s0 * pz + s1 * cz);
        float w = (float)(s0 * pw + s1 * cw);
        float len = (float)Math.sqrt(x * x + y * y + z * z + w * w);
        float[] out = k.renderRotScratch;
        if (len > 1.0e-6f) {
            out[0] = x / len; out[1] = y / len; out[2] = z / len; out[3] = w / len;
        } else {
            out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f;
        }
        return out;
    }

    // used by ClientLevelAccessMixin — returns physics block state at worldPos or null
    // mirrors SuperStateManager.getBlockStateAt() but client-side
    public static BlockState getClientBlockStateAt(BlockPos worldPos) {
        return getClientBlockStateAt(worldPos, System.nanoTime());
    }

    private static BlockState getClientBlockStateAt(BlockPos worldPos, long nowNs) {
        if (KONTRAS.isEmpty()) return null;
        for (KontraRenderData k : KONTRAS.values()) {
            float[] p = renderPos(k, nowNs);
            float[] r = renderRot(k, nowNs);
            if (p == null || r == null) continue;

            BlockPos local = localAtWorldCell(k, worldPos, p, r);
            BlockState bs = local != null ? k.localMap.get(local) : null;
            if (bs != null) return bs;
        }
        return null;
    }

    // solid-block lookup for BlockCollisions (KontraSolidBook.CLIENT). uses the CURRENT server
    // transform, not the render lerp — collision has to agree with the server, not the eye candy
    public static BlockState alignedSolidAt(BlockPos worldPos) {
        if (KONTRAS.isEmpty()) return null;
        for (KontraRenderData k : KONTRAS.values()) {
            if (!k.aligned) continue;
            float[] p = k.currPos;
            float[] r = k.currRot;
            if (p == null || r == null) continue;
            BlockPos local = localAtWorldCell(k, worldPos, p, r);
            BlockState bs = local != null ? k.localMap.get(local) : null;
            if (bs != null) return bs;
        }
        return null;
    }

    // same walk as above but answering with the SHAPE. has to exist or the client keeps using the
    // state shape (empty for a micro block) while the server uses the real cells, and you get
    // rubber-banded off a parked lift
    public static java.util.List<net.minecraft.world.phys.AABB> alignedShapeAt(BlockPos worldPos) {
        if (KONTRAS.isEmpty()) return null;
        for (KontraRenderData k : KONTRAS.values()) {
            if (!k.aligned) continue;
            float[] p = k.currPos;
            float[] r = k.currRot;
            if (p == null || r == null) continue;
            BlockPos local = localAtWorldCell(k, worldPos, p, r);
            BlockState bs = local != null ? k.localMap.get(local) : null;
            if (bs == null) continue;
            return com.koper.koper_lib.physics.shape.KhysShapeCache.get(bs, k.localData.get(local));
        }
        return null;
    }

    private static BlockPos localAtWorldCell(KontraRenderData k, BlockPos worldPos, float[] p, float[] r) {
        if (k.offsets.length < 3) return null;
        double dx = worldPos.getX() + 0.5 - p[0];
        double dy = worldPos.getY() + 0.5 - p[1];
        double dz = worldPos.getZ() + 0.5 - p[2];
        Float radius = RADII.get(k.id);
        if (radius != null && dx*dx + dy*dy + dz*dz > (radius+1.0)*(radius+1.0)) return null;
        double norm = Math.sqrt((double)r[0]*r[0] + (double)r[1]*r[1] + (double)r[2]*r[2] + (double)r[3]*r[3]);
        if (!Double.isFinite(norm) || norm < 1e-12) return null;
        double qx=-r[0]/norm, qy=-r[1]/norm, qz=-r[2]/norm, qw=r[3]/norm;
        double tx=2*(qy*dz-qz*dy), ty=2*(qz*dx-qx*dz), tz=2*(qx*dy-qy*dx);
        double lx=dx+qw*tx+qy*tz-qz*ty;
        double ly=dy+qw*ty+qz*tx-qx*tz;
        double lz=dz+qw*tz+qx*ty-qy*tx;
        if (!Double.isFinite(lx) || !Double.isFinite(ly) || !Double.isFinite(lz)) return null;
        if (k.offsetBuckets != null) return localAtIrregularOffset(k, lx, ly, lz);
        // logical keys survive recentering; a centroid offset is not that key
        long x=Math.round(lx + k.locals[0] - (double)k.offsets[0]);
        long y=Math.round(ly + k.locals[1] - (double)k.offsets[1]);
        long z=Math.round(lz + k.locals[2] - (double)k.offsets[2]);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return null;
        return new BlockPos((int)x, (int)y, (int)z);
    }

    private static Map<BlockPos, it.unimi.dsi.fastutil.ints.IntArrayList> offsetBuckets(float[] offsets, int[] locals) {
        if (offsets.length < 3) return null;
        double bx=locals[0]-(double)offsets[0], by=locals[1]-(double)offsets[1], bz=locals[2]-(double)offsets[2];
        boolean uniform = true;
        for (int i=0; i<offsets.length; i+=3) {
            if (Math.abs(locals[i]-(double)offsets[i]-bx) > 1e-4
                || Math.abs(locals[i+1]-(double)offsets[i+1]-by) > 1e-4
                || Math.abs(locals[i+2]-(double)offsets[i+2]-bz) > 1e-4) {
                uniform = false;
                break;
            }
        }
        if (uniform) return null;
        // addon bodies may have non-grid offsets; bucket those once, never scan the hull per query
        var buckets = new HashMap<BlockPos, it.unimi.dsi.fastutil.ints.IntArrayList>();
        for (int i=0; i<offsets.length; i+=3) {
            if (!Float.isFinite(offsets[i]) || !Float.isFinite(offsets[i+1]) || !Float.isFinite(offsets[i+2])) continue;
            BlockPos cell = BlockPos.containing(offsets[i], offsets[i+1], offsets[i+2]);
            buckets.computeIfAbsent(cell, ignored -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i/3);
        }
        return buckets;
    }

    private static BlockPos localAtIrregularOffset(KontraRenderData k, double x, double y, double z) {
        double minX=Math.floor(x-0.5), maxX=Math.floor(x+0.5);
        double minY=Math.floor(y-0.5), maxY=Math.floor(y+0.5);
        double minZ=Math.floor(z-0.5), maxZ=Math.floor(z+0.5);
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE
            || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) return null;
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (long bx=(long)minX; bx<=maxX; bx++) for (long by=(long)minY; by<=maxY; by++) for (long bz=(long)minZ; bz<=maxZ; bz++) {
            var candidates = k.offsetBuckets.get(new BlockPos((int)bx, (int)by, (int)bz));
            if (candidates == null) continue;
            for (int j=0; j<candidates.size(); j++) {
                int i=candidates.getInt(j)*3;
                double dx=x-k.offsets[i], dy=y-k.offsets[i+1], dz=z-k.offsets[i+2];
                if (Math.abs(dx)>0.5 || Math.abs(dy)>0.5 || Math.abs(dz)>0.5) continue;
                double distance=dx*dx+dy*dy+dz*dz;
                if (distance < best) { best=distance; nearest=i; }
            }
        }
        return nearest < 0 ? null : new BlockPos(k.locals[nearest], k.locals[nearest+1], k.locals[nearest+2]);
    }

    // client mirror of KoperPhys.eyeBoxHasPhysicsBlock — kills fake suffocation from ClientLevelAccessMixin.
    // vanilla isInWall samples a width*0.8 box, so we check that whole footprint not just the eye cell
    public static boolean eyeBoxHasPhysicsBlock(double eyeX, double eyeY, double eyeZ, double width) {
        if (KONTRAS.isEmpty()) return false;
        long nowNs = System.nanoTime();
        double f = width * 0.4;
        int minX = (int)Math.floor(eyeX - f), maxX = (int)Math.floor(eyeX + f);
        int minZ = (int)Math.floor(eyeZ - f), maxZ = (int)Math.floor(eyeZ + f);
        // 0.06 covers isInWall (1e-6 plane) AND the screen-overlay 8-corner sampling (eye ± 0.05*scale)
        int minY = (int)Math.floor(eyeY - 0.06), maxY = (int)Math.floor(eyeY + 0.06);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (getClientBlockStateAt(new BlockPos(x, y, z), nowNs) != null) return true;
        return false;
    }
}
