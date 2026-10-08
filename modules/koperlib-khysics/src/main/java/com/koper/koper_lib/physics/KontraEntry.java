package com.koper.koper_lib.physics;

import com.koper.koper_lib.physics.shape.KhysShapeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

// all data for one live kontraktion — physics + render state
public class KontraEntry {

    private final String levelKey;
    private final long worldHandle;

    // resting on the world grid (Rust align-assist finished) — while true this kontra is
    // exposed to vanilla as REAL solid blocks instead of the SAT pushout
    public volatile boolean aligned = false;
    // null = follow the global default from khysics.json, set only by /koperlib physics aero <id>
    private volatile AeroMode aeroOverride = null;

    // local BlockPos (rounded offset from centroid) → state
    public final Map<BlockPos, BlockState> blocks;
    // local BlockPos → exact float offset [ox,oy,oz] (sub-block precision for restore/render)
    public final Map<BlockPos, float[]> blockOffsets;

    // render arrays — flat [ox,oy,oz per block], states parallel
    private float[] currentOffsets;
    private final List<BlockState> currentStates;

    public final Map<BlockPos, BlockEntity> blockEntities;
    public final Map<BlockPos, net.minecraft.nbt.CompoundTag> localData;
    // whatever addons want remembered about the whole body (KhysBody.stash). saved in kontras.bin
    public final net.minecraft.nbt.CompoundTag stash = new net.minecraft.nbt.CompoundTag();
    // no stored shape map anymore. it used to be filled at every write site and every new local-data
    // write site was one more place to forget, so micro blocks kept a stale shape. resolve it live.

    // THE world→kontra remap guarantee: local BlockPos → the world BlockPos this block was
    // assembled from. addons use it to move their own connection graphs onto the body. it is
    // persisted and carried through splits because a connector endpoint must survive restarts.
    public final Map<BlockPos, BlockPos> assembledFrom = new LinkedHashMap<>();

    // world pos this block came from at assembly, or null for a block added on the live grid
    public BlockPos originWorldPos(BlockPos local) { return assembledFrom.get(local); }
    // reverse lookup — which local slot did this world block become
    public BlockPos localForWorld(BlockPos worldPos) {
        for (var e : assembledFrom.entrySet()) if (e.getValue().equals(worldPos)) return e.getKey();
        return null;
    }

    // bounding sphere radius from centroid — used for fast coarse collision reject
    // updated whenever blocks are added/removed so we don't recompute every tick
    public float cachedRadius = 2f;

    // world-cell(asLong) → state, valid only for the exact transform it was built with. a resting
    // aligned kontra rebuilds this once and every collision query after that is a hashmap hit
    private volatile Map<Long, BlockState> solidCells;
    private volatile float[] solidStamp;
    private volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> logicCells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private volatile it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockPos> logicOwners = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> logicCellScratch = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockPos> logicOwnerScratch = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet logicChanged = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final float[] logicStamp = new float[7];
    private volatile boolean logicStampValid;

    public it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> logicCells() { return logicCells; }
    public BlockPos logicOwner(long cell) { return logicOwners.get(cell); }
    public it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockPos> logicOwners() { return logicOwners; }
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet logicChangedCells() { return logicChanged; }
    public void invalidateLogicCells() { logicStampValid = false; }
    public boolean logicCellsDirty() { return !logicStampValid; }

    public it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> rebuildLogicCells(float[] pos, float[] rot) {
        float[] stamp = logicStamp;
        if (logicStampValid && stamp[0]==pos[0] && stamp[1]==pos[1] && stamp[2]==pos[2]
                && stamp[3]==rot[0] && stamp[4]==rot[1] && stamp[5]==rot[2] && stamp[6]==rot[3])
            return logicCells;
        var cells = logicCellScratch;
        var owners = logicOwnerScratch;
        cells.clear();
        owners.clear();
        float[][] axes = KoperPhys.quaternionAxes(rot);
        KoperPhys.ObbCellTest cellTest = KoperPhys.obbCellTest(axes);
        float ex = .5f * (Math.abs(axes[0][0]) + Math.abs(axes[1][0]) + Math.abs(axes[2][0]));
        float ey = .5f * (Math.abs(axes[0][1]) + Math.abs(axes[1][1]) + Math.abs(axes[2][1]));
        float ez = .5f * (Math.abs(axes[0][2]) + Math.abs(axes[1][2]) + Math.abs(axes[2][2]));
        var projectedStates = new IdentityHashMap<BlockState, BlockState>();
        boolean axisAligned = KoperPhys.axisAligned(axes);
        float[] center = new float[3];
        for (var e : blockOffsets.entrySet()) {
            BlockState state = blocks.get(e.getKey());
            if (state == null || state.isAir()) continue;
            BlockState worldState = projectedStates.get(state);
            if (worldState == null) {
                worldState = KoperPhys.stateToWorld(state, rot);
                projectedStates.put(state, worldState);
            }
            float[] off = e.getValue();
            KoperPhys.localToWorld(off[0], off[1], off[2], pos, rot, center);
            int minX=(int)Math.floor(center[0]-ex+(axisAligned ? 1e-4f : 0f));
            int minY=(int)Math.floor(center[1]-ey+(axisAligned ? 1e-4f : 0f));
            int minZ=(int)Math.floor(center[2]-ez+(axisAligned ? 1e-4f : 0f));
            int maxX=(int)Math.floor(center[0]+ex-(axisAligned ? 1e-4f : 0f));
            int maxY=(int)Math.floor(center[1]+ey-(axisAligned ? 1e-4f : 0f));
            int maxZ=(int)Math.floor(center[2]+ez-(axisAligned ? 1e-4f : 0f));
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++)
                        if (axisAligned || cellTest.touches(center, x, y, z)) {
                            long cell = BlockPos.asLong(x, y, z);
                            if (cells.putIfAbsent(cell, worldState) == null) {
                                owners.put(cell, e.getKey());
                            }
                        }
        }
        logicOwnerScratch = logicOwners;
        logicCellScratch = logicCells;
        logicOwners = owners;
        logicCells = cells;
        stamp[0]=pos[0]; stamp[1]=pos[1]; stamp[2]=pos[2];
        stamp[3]=rot[0]; stamp[4]=rot[1]; stamp[5]=rot[2]; stamp[6]=rot[3];
        logicStampValid = true;
        return cells;
    }

    public Map<Long, BlockState> solidCells(float[] pos, float[] rot) {
        Map<Long, BlockState> c = solidCells;
        float[] s = solidStamp;
        if (c != null && s != null
                && s[0] == pos[0] && s[1] == pos[1] && s[2] == pos[2]
                && s[3] == rot[0] && s[4] == rot[1] && s[5] == rot[2] && s[6] == rot[3]) {
            return c;
        }
        Map<Long, BlockState> m = new HashMap<>(blocks.size() * 2);
        for (var e : blockOffsets.entrySet()) {
            BlockState st = blocks.get(e.getKey());
            if (st == null) continue;
            float[] off = e.getValue();
            float[] w = KoperPhys.localToWorld(off[0], off[1], off[2], pos, rot);
            m.put(BlockPos.asLong((int)Math.floor(w[0]), (int)Math.floor(w[1]), (int)Math.floor(w[2])), st);
        }
        solidStamp = new float[]{ pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3] };
        solidCells = m;
        return m;
    }

    public void invalidateSolidCells() { solidCells = null; solidStamp = null; }

    private volatile KontraGrid grid;
    private BlockEntity[] blockEntityTickScratch = new BlockEntity[0];

    public BlockEntity[] snapshotBlockEntities() {
        int size = blockEntities.size();
        if (blockEntityTickScratch.length < size)
            blockEntityTickScratch = new BlockEntity[Math.max(size, blockEntityTickScratch.length * 2 + 1)];
        int i = 0;
        for (BlockEntity be : blockEntities.values()) blockEntityTickScratch[i++] = be;
        java.util.Arrays.fill(blockEntityTickScratch, i, blockEntityTickScratch.length, null);
        return blockEntityTickScratch;
    }

    public KontraGrid grid(long kontraId) {
        KontraGrid g = grid;
        if (g == null || g.kontraId() != kontraId) grid = g = new KontraGrid(kontraId, this);
        return g;
    }

    public KontraEntry(String levelKey, long worldHandle,
                           List<BlockState> states, float[] offsets) {
        this.levelKey = levelKey;
        this.worldHandle = worldHandle;
        this.currentOffsets = offsets.clone();
        this.currentStates = new ArrayList<>(states);
        this.blocks = new KoperNoAirMap();
        this.blockEntities = new LinkedHashMap<>();
        this.localData = new LinkedHashMap<>();
        this.blockOffsets = new LinkedHashMap<>();

        int n = states.size();
        float maxR = 0;
        for (int i = 0; i < n; i++) {
            int lx = Math.round(offsets[i * 3]);
            int ly = Math.round(offsets[i * 3 + 1]);
            int lz = Math.round(offsets[i * 3 + 2]);
            BlockPos lp = new BlockPos(lx, ly, lz);
            blocks.put(lp, states.get(i));
            blockOffsets.put(lp, new float[]{offsets[i * 3], offsets[i * 3 + 1], offsets[i * 3 + 2]});
            float r = (float)Math.sqrt(offsets[i*3]*offsets[i*3] + offsets[i*3+1]*offsets[i*3+1] + offsets[i*3+2]*offsets[i*3+2]);
            if (r > maxR) maxR = r;
        }
        // +1 covers the block half-diagonal (sqrt(3)/2 ≈ 0.87), +0.5 extra margin
        this.cachedRadius = maxR + 1.5f;
    }

    /** boxes for one local block, 0-1 space, local-data geometry included */
    public List<AABB> shapeAt(BlockPos local) {
        BlockState state = blocks.get(local);
        if (state == null) return KhysShapeCache.FULL_CUBE;
        return KhysShapeCache.get(state, localData.get(local));
    }

    public String levelKey()    { return levelKey; }
    public long   worldHandle() { return worldHandle; }
    public float[] offsets()    { return currentOffsets; }
    public int    blockCount()  { return blocks.size(); }
    public AeroMode aeroMode()  { return aeroOverride != null ? aeroOverride : KoperPhys.defaultAeroMode(); }
    public AeroMode aeroOverride() { return aeroOverride; }
    public void setAeroMode(AeroMode mode) { aeroOverride = mode; }

    public List<BlockState> states() {
        return Collections.unmodifiableList(currentStates);
    }

    // appends one block — extends the flat offset array
    public void addBlock(BlockPos local, BlockState state, float ox, float oy, float oz) {
        invalidateSolidCells();
        invalidateLogicCells();
        blocks.put(local, state);
        blockOffsets.put(local, new float[]{ox, oy, oz});
        currentStates.add(state);
        float[] grown = new float[currentOffsets.length + 3];
        System.arraycopy(currentOffsets, 0, grown, 0, currentOffsets.length);
        int idx = currentOffsets.length;
        grown[idx]     = ox;
        grown[idx + 1] = oy;
        grown[idx + 2] = oz;
        currentOffsets = grown;
        // expand bounding sphere if this block is farther than current radius
        float r = (float)Math.sqrt(ox*ox + oy*oy + oz*oz) + 1.5f;
        if (r > cachedRadius) cachedRadius = r;
    }

    // parallel arrays built from the live blocks map — feeds KenderSpawnPayload (respawn + player join).
    // locals carries OUR key for every block. the client used to re-derive it as round(offset) and the
    // two drifted apart the moment the centroid moved: dropped state updates, neighbours not found so
    // no face culling, block entities looked up under a name nobody had. it ships the key now.
    public record RenderArrays(float[] offsets, int[] stateIds, int[] locals, List<BlockState> states) {}
    public RenderArrays renderArrays() {
        int n = blocks.size();
        float[] offs = new float[n * 3];
        int[] ids = new int[n];
        int[] locals = new int[n * 3];
        List<BlockState> sl = new ArrayList<>(n);
        int i = 0;
        for (var e : blocks.entrySet()) {
            BlockPos lp = e.getKey();
            float[] off = blockOffsets.get(lp);
            offs[i*3]   = off != null ? off[0] : lp.getX();
            offs[i*3+1] = off != null ? off[1] : lp.getY();
            offs[i*3+2] = off != null ? off[2] : lp.getZ();
            locals[i*3] = lp.getX(); locals[i*3+1] = lp.getY(); locals[i*3+2] = lp.getZ();
            ids[i] = Block.getId(e.getValue());
            sl.add(e.getValue());
            i++;
        }
        return new RenderArrays(offs, ids, locals, sl);
    }

    // call after a block is removed — rebuilds from the current blocks map
    public void rebuildRenderData(float[] newOffsets, List<BlockState> newStates) {
        invalidateSolidCells();
        invalidateLogicCells();
        currentOffsets = newOffsets.clone();
        currentStates.clear();
        currentStates.addAll(newStates);
        // recompute radius since blocks may have been removed (radius can shrink)
        float maxR = 0;
        for (int i = 0; i < newOffsets.length / 3; i++) {
            float ox = newOffsets[i*3], oy = newOffsets[i*3+1], oz = newOffsets[i*3+2];
            float r = (float)Math.sqrt(ox*ox + oy*oy + oz*oz);
            if (r > maxR) maxR = r;
        }
        this.cachedRadius = maxR + 1.5f;
    }

    // air in a body is a collider with nothing in it, and it rode along into every save. whoever tries
    // it gets told once with a trace and the write is dropped
    static final class KoperNoAirMap extends LinkedHashMap<BlockPos, BlockState> {
        private static final java.util.concurrent.atomic.AtomicInteger TOLD = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public BlockState put(BlockPos key, BlockState value) {
            if (value != null && value.isAir()) {
                if (TOLD.getAndIncrement() < 20)
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KontraEntry] refused air at local {}",
                        key, new Throwable("air written into a body"));
                return get(key);
            }
            return super.put(key, value);
        }

        @Override
        public void putAll(Map<? extends BlockPos, ? extends BlockState> all) {
            for (var e : all.entrySet()) put(e.getKey(), e.getValue());
        }
    }
}
