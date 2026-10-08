package com.koper.koper_lib.physics.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// BlockState → collision shape AABBs — coordinates in 0-1 block-local space
// cached per unique BlockState to avoid recomputing every tick
public final class KhysShapeCache {

    private KhysShapeCache() {}

    public static final List<AABB> FULL_CUBE = List.of(new AABB(0, 0, 0, 1, 1, 1));
    public static final List<AABB> EMPTY = List.of();

    private static final Map<BlockState, List<AABB>> CACHE = new ConcurrentHashMap<>();
    // tracks which block types we already logged a fallback for — no spam
    private static final Set<String> LOGGED_FALLBACKS = ConcurrentHashMap.newKeySet();

    private static final BlockGetter EMPTY_GETTER = new BlockGetter() {
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    };

    public static List<AABB> get(BlockState state) {
        return CACHE.computeIfAbsent(state, KhysShapeCache::compute);
    }

    private static List<AABB> compute(BlockState state) {
        if (state.isAir()) return EMPTY;
        try {
            // 3-arg with empty context — correct modern API, actually works for slabs/stairs/doors
            // 2-arg silently returned full cube for some blocks in older builds
            VoxelShape shape = state.getCollisionShape(EMPTY_GETTER, BlockPos.ZERO, CollisionContext.empty());
            if (shape.isEmpty()) {
                // door/tall-plant upper halves return empty because getCollisionShape
                // checks the lower half via context (which EMPTY_GETTER returns as AIR)
                // fix: if block has DOUBLE_BLOCK_HALF=UPPER, fall back to lower half's shape
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                    BlockState lower = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    return compute(lower); // will return EMPTY for tall grass (lower also empty), correct shape for doors
                }
                return EMPTY;
            }
            List<AABB> aabbs = shape.toAabbs();
            if (aabbs.isEmpty()) return EMPTY;
            if (aabbs.size() == 1) {
                AABB a = aabbs.get(0);
                if (a.minX <= 0.001 && a.minY <= 0.001 && a.minZ <= 0.001 &&
                    a.maxX >= 0.999 && a.maxY >= 0.999 && a.maxZ >= 0.999)
                    return FULL_CUBE;
            }
            return List.copyOf(aabbs);
        } catch (Throwable t) {
            // Throwable not Exception — catches NoSuchMethodError etc too
            String key = state.getBlock().getClass().getSimpleName();
            if (LOGGED_FALLBACKS.add(key))
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KhysShapeCache] FULL_CUBE fallback for {} ({}): {}", state, key, t.toString());
            return FULL_CUBE;
        }
    }

    // micro grids keep their shape in LOCAL DATA, not the state, so the cache above is useless for
    // them — and EMPTY_GETTER can't see local data at all, so it just says Shapes.empty(). that
    // empty box is what you collide with, outline and raycast, hence micro blocks you fall through
    // and can't hit. key on the geometry instead of the state.
    private static final Map<Long, List<AABB>> LOCAL_CACHE = new ConcurrentHashMap<>();

    /** boxes for one block, sub-cell geometry from its local data included */
    public static List<AABB> get(BlockState state, net.minecraft.nbt.CompoundTag localData) {
        List<AABB> local = localCells(state, localData);
        return local != null ? local : get(state);
    }

    /** same thing as a VoxelShape, for the raycast paths */
    public static VoxelShape voxel(BlockState state, net.minecraft.nbt.CompoundTag localData) {
        List<AABB> local = localCells(state, localData);
        if (local == null) return koperPickOutline(state);
        VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.empty();
        for (AABB box : local)
            shape = net.minecraft.world.phys.shapes.Shapes.or(shape,
                net.minecraft.world.phys.shapes.Shapes.box(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        return shape;
    }

    // an invisible technical block (suspension head plate etc) fell through to KGeoBook's full cube,
    // so an unbreakable ghost cube sat on top of the real block, jiggling with the spring and
    // eating every click and every break. invisible blocks pick with their OWN outline, usually none
    private static VoxelShape koperPickOutline(BlockState state) {
        if (state != null && state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE
                && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
            try {
                return state.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    net.minecraft.core.BlockPos.ZERO);
            } catch (RuntimeException wantsARealLevel) {
                // fine, old behaviour
            }
        }
        return com.koper.koper_lib.api.core.KoperBlockShapes.shape(state);
    }

    // null = nothing sub-cell here, caller falls back to the state shape
    private static List<AABB> localCells(BlockState state, net.minecraft.nbt.CompoundTag localData) {
        if (state == null || localData == null || localData.isEmpty()) return null;
        var physics = com.koper.koper_lib.api.local.KoperLocalData.physics(state, localData, 1.0f);
        if (physics == null) return null;
        int resolution = physics.resolution();
        if (resolution != 2 && resolution != 4) return null;
        long cells = physics.occupiedCells();
        if (cells == 0L) return null;
        // resolution fits beside the mask: 64 cells max, so the top bits are free
        long key = cells ^ ((long)resolution << 60);
        return LOCAL_CACHE.computeIfAbsent(key, ignored -> buildCells(resolution, cells));
    }

    private static List<AABB> buildCells(int resolution, long cells) {
        double size = 1.0 / resolution;
        java.util.ArrayList<AABB> boxes = new java.util.ArrayList<>();
        for (int y = 0; y < resolution; y++) {
            for (int z = 0; z < resolution; z++) {
                for (int x = 0; x < resolution; x++) {
                    // same bit order the Rust side uses when it builds the compound collider —
                    // if these two ever disagree, Rapier and the player collide with different shapes
                    int bit = x + resolution * (z + resolution * y);
                    if ((cells & (1L << bit)) == 0L) continue;
                    boxes.add(new AABB(x * size, y * size, z * size,
                        (x + 1) * size, (y + 1) * size, (z + 1) * size));
                }
            }
        }
        return boxes.isEmpty() ? EMPTY : List.copyOf(boxes);
    }

    public static void invalidate() {
        CACHE.clear();
        LOCAL_CACHE.clear();
        LOGGED_FALLBACKS.clear();
    }
}
