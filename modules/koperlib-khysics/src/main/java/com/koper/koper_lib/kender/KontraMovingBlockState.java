package com.koper.koper_lib.kender;

import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

// MovingBlockRenderState subclass that returns actual neighbor states for face culling
// blockPos = WORLD position (for correct light lookup), localPos kept separately for neighbor culling
// called by ModelBlockRenderer when cull=true (enabled via MovingBlockCullMixin)
class KontraMovingBlockState extends MovingBlockRenderState
        implements com.koper.koper_lib.api.core.KenderMovingBlockContext {

    private Map<BlockPos, BlockState> localMap;
    private Map<BlockPos, BlockEntity> localBlockEntities = Map.of();
    private BlockPos localPos; // local offset inside kontraktion
    private long kontraId = -1L;

    private net.minecraft.nbt.CompoundTag localData;

    KontraMovingBlockState kontra(long id) { this.kontraId = id; return this; }
    KontraMovingBlockState data(net.minecraft.nbt.CompoundTag tag) { this.localData = tag; return this; }

    @Override
    public net.minecraft.nbt.CompoundTag kenderLocalData() { return localData; }
    long kontraId()   { return kontraId; }
    BlockPos localPos() { return localPos; }

    KontraMovingBlockState() {
        reset(BlockPos.ZERO, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), Map.of(), Map.of());
    }

    KontraMovingBlockState(BlockPos localPos, BlockPos worldPos, BlockState state,
                           Map<BlockPos, BlockState> localMap, Map<BlockPos, BlockEntity> blockEntities) {
        reset(localPos, worldPos, state, localMap, blockEntities);
    }

    private final BlockPos.MutableBlockPos ownLocal = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos ownWorld = new BlockPos.MutableBlockPos();
    // lookup scratch — map keys compare by coords, so a mutable is a fine probe as long as we never
    // hand it out. create's connected textures ask about a dozen cells per block per frame.
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    // pooled variant — copies coords into positions this instance owns, so the caller's scratch
    // pos is free to move on. saves two BlockPos allocs per block per frame on the CPU fallback.
    KontraMovingBlockState resetOwned(BlockPos local, BlockPos world, BlockState state,
                                      Map<BlockPos, BlockState> localMap, Map<BlockPos, BlockEntity> blockEntities) {
        ownLocal.set(local);
        ownWorld.set(world);
        return reset(ownLocal, ownWorld, state, localMap, blockEntities);
    }

    KontraMovingBlockState reset(BlockPos localPos, BlockPos worldPos, BlockState state,
                                 Map<BlockPos, BlockState> localMap, Map<BlockPos, BlockEntity> blockEntities) {
        this.blockPos      = worldPos;  // MC uses blockPos for world light/sky queries — must be world
        this.randomSeedPos = localPos;  // random seed stays local for consistent AO
        this.blockState    = state;
        this.localMap      = localMap;
        this.localBlockEntities = blockEntities == null ? Map.of() : blockEntities;
        this.localPos      = localPos;
        this.localData     = null; // pooled, last frame's cell must not leak in
        return this;
    }

    // world pos the caller asked about → the cell it actually is in our grid
    private BlockPos.MutableBlockPos toLocal(BlockPos world) {
        return probe.set(
            localPos.getX() + world.getX() - blockPos.getX(),
            localPos.getY() + world.getY() - blockPos.getY(),
            localPos.getZ() + world.getZ() - blockPos.getZ());
    }

    // raw neighbours, no occlusion filter — this identifies the SHAPE CONTEXT a model bakes against.
    // two tanks with the same six neighbours produce identical geometry, so they can share one mesh.
    public long kenderNeighbourSignature() {
        long h = 0xcbf29ce484222325L;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            probe.set(localPos.getX() + d.getStepX(), localPos.getY() + d.getStepY(), localPos.getZ() + d.getStepZ());
            BlockState n = localMap.get(probe);
            h ^= n == null ? 0 : net.minecraft.world.level.block.Block.getId(n);
            h *= 0x100000001b3L;
            // ...except a create tank picks its walls from WHICH multiblock it joined, not from what
            // block sits next to it. same six neighbours, different controller = different geometry,
            // and without this the mesh cache hands the old lid to a tank that just got merged.
            h ^= com.koper.koper_lib.compat.create.KontraMultiSniffer.controllerHash(localBlockEntities.get(probe));
            h *= 0x100000001b3L;
        }
        h ^= com.koper.koper_lib.compat.create.KontraMultiSniffer.controllerHash(localBlockEntities.get(localPos));
        return h * 0x100000001b3L;
    }

    @Override
    public BlockPos kenderWorldPos() { return blockPos; }

    @Override
    public BlockState getBlockState(BlockPos neighborWorld) {
        BlockPos.MutableBlockPos neighborLocal = toLocal(neighborWorld);
        if (neighborLocal.equals(localPos)) return blockState;
        BlockState n = localMap.get(neighborLocal);
        if (n == null) return Blocks.AIR.defaultBlockState();
        // same block next door: hand it over untouched. this is the query create's tank uses to decide
        // it merged, and the one skipRendering uses to hide the shared wall — exactly like glass does
        // in the world. blanking it to air is why a stack of tanks stayed a stack of separate tanks.
        if (n.getBlock() == blockState.getBlock()) return n;
        // F6 fix: non-occluding blocks (flowers, glass, fences, leaves) can't hide faces
        // returning them as-is makes shouldRenderFace cull the face even though it's see-through
        return n.canOcclude() ? n : Blocks.AIR.defaultBlockState();
    }

    // MovingBlockRenderState hardcodes `return null` here — pistons push dumb blocks, so vanilla
    // never needed it. create asks exactly this to find out whether the tank next door is part of
    // its own multiblock (ConnectivityHandler.isConnected → getBlockEntity → getController), got
    // null every single time, and concluded every tank stands alone. that is the four separate
    // lids on a 2x2 and the wrong wall sprite. it costs one map lookup to tell it the truth.
    @Override
    public BlockEntity getBlockEntity(BlockPos neighborWorld) {
        return localBlockEntities.get(toLocal(neighborWorld));
    }
}
