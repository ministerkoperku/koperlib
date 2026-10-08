package com.koper.koper_lib.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import com.koper.koper_lib.physics.weight.KhysWeightBook;

// tiny logical block space for one kontra. positions stay put while Rust moves the body.
public final class KontraGrid {
    private final long kontraId;
    private final KontraEntry entry;
    private final BlockPos anchor;
    private long gameTick;
    private long sequence;
    private final PriorityQueue<GridTick> scheduled = new PriorityQueue<>();
    private final Set<GridTickKey> scheduledKeys = new HashSet<>();
    private GridTickKey tickingNow;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ExternalLookup> externalCache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private record BoundaryKey(BlockPos local, net.minecraft.core.Direction direction) {}
    private java.util.HashMap<BoundaryKey, Long> boundaryContacts = new java.util.HashMap<>();
    private java.util.HashMap<BoundaryKey, Long> boundaryScratch = new java.util.HashMap<>();
    private java.util.List<BoundaryKey> boundaryFaces;
    private java.util.List<GridSection> randomTickSections;
    private final float[] boundaryPose = new float[7];
    private boolean boundaryPoseValid;
    private net.minecraft.world.level.redstone.CollectingNeighborUpdater koperNeighborKolejka;

    private record GridTickKey(BlockPos pos, Object type) {}
    private static final java.util.Set<String> TICK_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public record SavedGridTick(BlockPos localPos, boolean fluid, Identifier type, long delay,
                                TickPriority priority) {}
    private record GridSection(int x, int y, int z) {}
    private record GridTick(long due, TickPriority priority, long sequence, BlockPos pos, Object type) implements Comparable<GridTick> {
        @Override public int compareTo(GridTick other) {
            int time = Long.compare(due, other.due);
            if(time!=0)return time;
            int prio=Integer.compare(priority.getValue(),other.priority.getValue());
            return prio!=0?prio:Long.compare(sequence,other.sequence);
        }
    }

    KontraGrid(long kontraId, KontraEntry entry) {
        this.kontraId = kontraId;
        this.entry = entry;
        long slot = Math.floorMod(kontraId, 1_600_000_000L);
        int sx = (int)(slot % 40_000L);
        int sz = (int)(slot / 40_000L);
        this.anchor = new BlockPos(-20_000_000 + sx * 1000, 0, -20_000_000 + sz * 1000);
    }

    // every anchor sits at z = -20,000,000 + slot / 40,000 * 1000, so all the grids of any real game
    // live beyond z = -19,000,000. nothing there is a real chunk anybody should be generating
    public static final int REGION_MAX_Z = -19_000_000;

    public static boolean inGridRegion(BlockPos pos) {
        return pos.getZ() <= REGION_MAX_Z;
    }

    public long kontraId() { return kontraId; }
    public KontraEntry entry() { return entry; }
    public BlockPos anchor() { return anchor; }
    public BlockPos toGrid(BlockPos local) { return anchor.offset(local); }
    public BlockPos toLocal(BlockPos gridPos) { return gridPos.subtract(anchor); }

    public boolean ownsGridPos(BlockPos pos) {
        BlockPos local = toLocal(pos);
        return entry.blocks.containsKey(local) || entry.blockEntities.containsKey(local);
    }

    public boolean hasLocalBlock(BlockPos gridPos) { return entry.blocks.containsKey(toLocal(gridPos)); }

    // each kontra runs its own collector — grid cascades queued into the WORLD collector could get
    // drained inside someone else's update with zero grid ctx → whole wire evals ran on world-space
    // projections (rotated repeater FACING never matches → reads 0 → 15→13→...→0 stepping death).
    // entry is ctx-wrapped, and the drain happens at the FIRST entry, so the entire cascade keeps grid reads.
    public void routeNeighborUpdate(ServerLevel level,
            java.util.function.Consumer<net.minecraft.world.level.redstone.NeighborUpdater> op) {
        // NOT the vanilla 1M cap — a kontra is a few hundred blocks and every unowned-cell read here
        // is an OBB scan. one water-on-the-seam feedback loop with the vanilla cap = server frozen
        // for minutes ("0 tps"). 8k drains any sane redstone cascade and cuts runaway loops short.
        if (koperNeighborKolejka == null)
            koperNeighborKolejka = new net.minecraft.world.level.redstone.CollectingNeighborUpdater(level, 8192);
        KontraGridContext.run(this, () -> op.accept(koperNeighborKolejka));
    }

    public int externalSignal(ServerLevel level, BlockPos gridPos, net.minecraft.core.Direction localDirection,
                              boolean direct) {
        ExternalLookup lookup = externalLookup(level, toLocal(gridPos));
        if (lookup.contacts().isEmpty()) return 0;
        net.minecraft.core.Direction worldDirection = KoperPhys.gridDirectionToWorld(kontraId, localDirection);
        return KontraGridContext.outside(() -> {
            int strongest = 0;
            for (ExternalCell external : lookup.contacts()) {
                int signal = direct ? level.getDirectSignal(external.pos(), worldDirection)
                    : level.getSignal(external.pos(), worldDirection);
                strongest = Math.max(strongest, signal);
                if (strongest >= 15) break;
            }
            return strongest;
        });
    }

    public BlockState getBlockState(BlockPos pos) {
        return entry.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    public BlockState getBlockState(ServerLevel level, BlockPos pos) {
        BlockPos local = toLocal(pos);
        BlockState own = entry.blocks.get(local);
        if (own != null) return own;
        // building on the grid: an unowned cell IS air in grid space — world litter must not veto canBeReplaced
        if (KoperPhys.GRID_PLACEMENT.get()) return Blocks.AIR.defaultBlockState();
        return externalLookup(level, local).primary().state();
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        return entry.blockEntities.get(pos);
    }

    public BlockEntity getBlockEntity(ServerLevel level, BlockPos pos) {
        BlockPos local = toLocal(pos);
        BlockEntity own = entry.blockEntities.get(local);
        if (own != null) return own;
        ExternalCell external = externalLookup(level, local).primary();
        return external.state().isAir() ? null : KoperPhys.realBlockEntity(level, external.pos());
    }

    private record ExternalCell(BlockPos pos, BlockState state) {}
    private record ExternalLookup(ExternalCell primary, BlockState actualPrimary,
                                  java.util.List<ExternalCell> contacts) {}

    private ExternalLookup externalLookup(ServerLevel level, BlockPos local) {
        ExternalLookup cached=externalCache.get(local.asLong());
        if(cached!=null)return cached;
        float[] bodyPos=KoperPhys.cachedPos(kontraId), rot=KoperPhys.cachedRot(kontraId);
        if(bodyPos==null||rot==null) {
            ExternalCell air = new ExternalCell(BlockPos.ZERO,Blocks.AIR.defaultBlockState());
            return new ExternalLookup(air, air.state(), java.util.List.of());
        }
        float[] off=offsetFor(local);
        float[] center=KoperPhys.localToWorld(off[0],off[1],off[2],bodyPos,rot);
        float[][] axes=KoperPhys.quaternionAxes(rot);
        var cellTest=KoperPhys.obbCellTest(axes);
        float ex=.5f*(Math.abs(axes[0][0])+Math.abs(axes[1][0])+Math.abs(axes[2][0]));
        float ey=.5f*(Math.abs(axes[0][1])+Math.abs(axes[1][1])+Math.abs(axes[2][1]));
        float ez=.5f*(Math.abs(axes[0][2])+Math.abs(axes[1][2])+Math.abs(axes[2][2]));
        BlockPos bestPos=BlockPos.containing(center[0],center[1],center[2]);
        BlockState best=Blocks.AIR.defaultBlockState();
        double bestDist=Double.POSITIVE_INFINITY;
        java.util.ArrayList<ExternalCell> contacts = new java.util.ArrayList<>(4);
        for(int x=(int)Math.floor(center[0]-ex);x<=(int)Math.floor(center[0]+ex);x++)
            for(int y=(int)Math.floor(center[1]-ey);y<=(int)Math.floor(center[1]+ey);y++)
                for(int z=(int)Math.floor(center[2]-ez);z<=(int)Math.floor(center[2]+ez);z++) {
                    if(!cellTest.touches(center,x,y,z)) continue;
                    BlockPos candidate=new BlockPos(x,y,z);
                    BlockState state=KoperPhys.realBlockState(level,candidate);
                    if(state.isAir()) continue;
                    contacts.add(new ExternalCell(candidate, state));
                    double dx=x+.5-center[0],dy=y+.5-center[1],dz=z+.5-center[2];
                    double dist=dx*dx+dy*dy+dz*dz;
                    if(dist<bestDist){bestDist=dist;bestPos=candidate;best=state;}
                }
        ExternalLookup result=new ExternalLookup(new ExternalCell(bestPos,KoperPhys.stateToGrid(best,rot)), best, contacts);
        externalCache.put(local.asLong(),result);
        return result;
    }

    public void invalidateExternalCache() { externalCache.clear(); }

    public void invalidateBoundaryContacts() {
        boundaryPoseValid = false;
        boundaryFaces = null;
        randomTickSections = null;
    }

    public java.util.Set<BlockPos> refreshBoundaryContacts(ServerLevel level, float[] pos, float[] rot) {
        float[] stamp = boundaryPose;
        if (boundaryPoseValid && stamp[0]==pos[0] && stamp[1]==pos[1] && stamp[2]==pos[2]
                && stamp[3]==rot[0] && stamp[4]==rot[1] && stamp[5]==rot[2] && stamp[6]==rot[3])
            return java.util.Set.of();
        externalCache.clear();
        java.util.List<BoundaryKey> faces = boundaryFaces;
        if (faces == null) {
            var rebuilt = new java.util.ArrayList<BoundaryKey>();
            for (BlockPos local : entry.blocks.keySet())
                for (var direction : net.minecraft.core.Direction.values())
                    if (!entry.blocks.containsKey(local.relative(direction)))
                        rebuilt.add(new BoundaryKey(local.immutable(), direction));
            boundaryFaces = faces = java.util.List.copyOf(rebuilt);
        }
        java.util.HashMap<BoundaryKey, Long> next = boundaryScratch;
        next.clear();
        for (BoundaryKey face : faces) {
                BlockPos local = face.local();
                var direction = face.direction();
                BlockPos missing = local.relative(direction);
                ExternalLookup lookup = externalLookup(level, missing);
                long signature = 0xcbf29ce484222325L;
                for (ExternalCell contact : lookup.contacts()) {
                    signature ^= contact.pos().asLong();
                    signature *= 0x100000001b3L;
                    signature ^= Block.getId(contact.state());
                    signature *= 0x100000001b3L;
                }
                signature ^= Block.getId(lookup.primary().state());
                next.put(face, signature);
        }
        java.util.HashSet<BlockPos> changed = null;
        for (var contact : next.entrySet()) {
            if (java.util.Objects.equals(boundaryContacts.get(contact.getKey()), contact.getValue())) continue;
            if (changed == null) changed = new java.util.HashSet<>();
            changed.add(contact.getKey().local());
        }
        for (BoundaryKey old : boundaryContacts.keySet()) {
            if (next.containsKey(old)) continue;
            if (changed == null) changed = new java.util.HashSet<>();
            changed.add(old.local());
        }
        boundaryScratch = boundaryContacts;
        boundaryContacts = next;
        stamp[0]=pos[0]; stamp[1]=pos[1]; stamp[2]=pos[2];
        stamp[3]=rot[0]; stamp[4]=rot[1]; stamp[5]=rot[2]; stamp[6]=rot[3];
        boundaryPoseValid = true;
        return changed != null ? changed : java.util.Set.of();
    }

    public void setBlockEntity(ServerLevel level, BlockEntity be) {
        BlockPos local = toLocal(be.getBlockPos());
        if (!entry.blocks.containsKey(local)) return;
        be.setLevel(level);
        entry.blockEntities.put(local, be);
        syncBlockEntity(level, be.getBlockPos());
    }

    public void removeBlockEntity(BlockPos gridPos) {
        BlockEntity removed = entry.blockEntities.remove(toLocal(gridPos));
        if (removed != null) removed.setRemoved();
    }

    public float[] offsetFor(BlockPos pos) {
        float[] exact = entry.blockOffsets.get(pos);
        if (exact != null) return exact.clone();
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            float[] off = entry.blockOffsets.get(neighbor);
            if (off != null) return new float[]{off[0]+pos.getX()-neighbor.getX(),
                off[1]+pos.getY()-neighbor.getY(), off[2]+pos.getZ()-neighbor.getZ()};
        }
        float best = Float.MAX_VALUE;
        BlockPos nearest = null;
        float[] nearestOffset = null;
        for (var e : entry.blockOffsets.entrySet()) {
            float dx = pos.getX() - e.getKey().getX();
            float dy = pos.getY() - e.getKey().getY();
            float dz = pos.getZ() - e.getKey().getZ();
            float d = dx*dx + dy*dy + dz*dz;
            if (d < best) { best = d; nearest = e.getKey(); nearestOffset = e.getValue(); }
        }
        if (nearest == null || nearestOffset == null)
            return new float[]{pos.getX(), pos.getY(), pos.getZ()};
        return new float[]{
            nearestOffset[0] + pos.getX() - nearest.getX(),
            nearestOffset[1] + pos.getY() - nearest.getY(),
            nearestOffset[2] + pos.getZ() - nearest.getZ()
        };
    }

    private float[] offsetFor(double x, double y, double z) {
        float best = Float.MAX_VALUE;
        BlockPos nearest = null;
        float[] nearestOffset = null;
        for (var e : entry.blockOffsets.entrySet()) {
            float dx = (float)x - e.getKey().getX();
            float dy = (float)y - e.getKey().getY();
            float dz = (float)z - e.getKey().getZ();
            float d = dx*dx + dy*dy + dz*dz;
            if (d < best) { best = d; nearest = e.getKey(); nearestOffset = e.getValue(); }
        }
        if (nearest == null || nearestOffset == null) return new float[]{(float)x,(float)y,(float)z};
        return new float[]{nearestOffset[0]+(float)x-nearest.getX(), nearestOffset[1]+(float)y-nearest.getY(),
            nearestOffset[2]+(float)z-nearest.getZ()};
    }

    public float[] offsetForGridPoint(double x, double y, double z) {
        double lx = x - anchor.getX() - 0.5;
        double ly = y - anchor.getY() - 0.5;
        double lz = z - anchor.getZ() - 0.5;
        double nearest = Double.POSITIVE_INFINITY;
        for (BlockPos local : entry.blocks.keySet()) {
            double dx=lx-local.getX(), dy=ly-local.getY(), dz=lz-local.getZ();
            nearest = Math.min(nearest, dx*dx+dy*dy+dz*dz);
        }
        // active grid code can still intentionally spawn something at an already-world-space position
        if (nearest > 64.0) return null;
        return offsetFor(lx, ly, lz);
    }

    // A bound child can travel far away from its controller (long pulley ropes are the obvious case).
    // The guarded variant above is for detecting fresh grid-space calls; once ownership is known we
    // must not drop the transform just because Create moved the child more than eight blocks away.
    public float[] offsetForBoundGridPoint(double x, double y, double z) {
        return offsetFor(x - anchor.getX() - 0.5,
            y - anchor.getY() - 0.5, z - anchor.getZ() - 0.5);
    }

    public AABB toWorld(AABB gridBox) {
        float[] bodyPos = KoperPhys.cachedPos(kontraId);
        float[] bodyRot = KoperPhys.cachedRot(kontraId);
        if (bodyPos == null || bodyRot == null) return gridBox;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
        double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
        double[] xs={gridBox.minX-anchor.getX()-0.5,gridBox.maxX-anchor.getX()-0.5};
        double[] ys={gridBox.minY-anchor.getY()-0.5,gridBox.maxY-anchor.getY()-0.5};
        double[] zs={gridBox.minZ-anchor.getZ()-0.5,gridBox.maxZ-anchor.getZ()-0.5};
        for(double x:xs) for(double y:ys) for(double z:zs) {
            float[] off=offsetFor(x,y,z);
            float[] w=KoperPhys.localToWorld(off[0],off[1],off[2],bodyPos,bodyRot);
            minX=Math.min(minX,w[0]); minY=Math.min(minY,w[1]); minZ=Math.min(minZ,w[2]);
            maxX=Math.max(maxX,w[0]); maxY=Math.max(maxY,w[1]); maxZ=Math.max(maxZ,w[2]);
        }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }

    public BlockPos toWorldPos(BlockPos gridPos) {
        return KoperPhys.gridToWorld(kontraId, entry, toLocal(gridPos));
    }


    // a fluid cell has to lean on the ship somewhere. bez tego wiadro na pokladzie rozlewa wode w niebo
    //
    // and it has to lean on something SOLID. counting a fluid neighbour as hull is how one bucket
    // became a sea: every new cell leaned on the cell that spread it, so the condition was true
    // forever, in all six directions, all the way down to the void. that was the tps.
    private boolean touchesHull(BlockPos local) {
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockState n = entry.blocks.get(local.relative(direction));
            if (n != null && !n.isAir() && !(n.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock))
                return true;
        }
        return false;
    }

    // a part mounted flush against something thin (a bearing's turning disc) doesn't sit on the cell
    // grid, it sits where it touches. only a NEW cell takes it; everything built on it later follows
    private float[] forcedOffset;

    public boolean setBlockAtOffset(ServerLevel level, BlockPos pos, BlockState state, int flags, float[] offset) {
        forcedOffset = offset;
        try { return setBlock(level, pos, state, flags); }
        finally { forcedOffset = null; }
    }

    public boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        return setBlock(level, pos, state, flags, Block.UPDATE_LIMIT);
    }

    public boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
        boolean fluid = !state.isAir() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        BlockPos local = toLocal(pos);
        // fluids ride the grid now. two rules keep them from being the disaster they were before:
        // they never replace a block the kontra owns, and they only appear in a cell that already
        // touches the hull. without the second one a bucket on deck grows water into open sky forever.
        if (fluid) {
            // ONLY source blocks ride. a flowing state is exactly what FlowingFluid.spread produces,
            // so refusing them is what stops the spread at the door instead of chasing it afterwards.
            // water on a kontra is cargo that sits where you poured it, not a simulated ocean.
            if (!state.getFluidState().isSource()) return false;
            BlockState here = entry.blocks.get(local);
            if (here != null && !(here.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock))
                return false;
            if (here == null && !touchesHull(local)) return false;
        }
        BlockState old = entry.blocks.get(local);
        if (old == null) {
            if (!KoperPhys.GRID_PLACEMENT.get()) {
            ExternalLookup lookup = externalLookup(level, local);
            ExternalCell external = lookup.primary();
            // a fluid must never escape the hull into the world, whatever the projection touches
            if (fluid && !external.state().isAir()) return false;
            if (!external.state().isAir()) {
                // grid logic writing AIR at a cell it doesn't own must NOT nuke the real world block the
                // OBB projection happens to touch (log caught it deleting ground wire/plates). no-op it.
                if (state.isAir()) return false;
                if (state.getBlock() != external.state().getBlock()) return false;
                float[] rot = KoperPhys.cachedRot(kontraId);
                BlockState worldState = rot != null
                    ? KoperPhys.gridEditToWorld(external.state(), lookup.actualPrimary(), state, rot) : state;
                if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
                    var trace = new Throwable().getStackTrace();
                    StringBuilder via = new StringBuilder();
                    for (int i = 1; i < Math.min(trace.length, 10); i++)
                        via.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
                           .append(':').append(trace[i].getLineNumber()).append(" <- ");
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                        "[GridDbg] setBlock->WORLD local={} extPos={} put={} overWorld={} via {}",
                        local, external.pos(), state.getBlock(), external.state().getBlock(), via);
                }
                boolean changed = KontraGridContext.outside(() ->
                    level.setBlock(external.pos(), worldState, flags, recursionLeft));
                invalidateExternalCache();
                return changed;
            }
            }
            if (state.isAir()) return false;
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] setBlock ADD local={} state={}", local, state.getBlock());
            float[] off = forcedOffset != null ? forcedOffset.clone() : offsetFor(local);
            forcedOffset = null; // one cell only — neighbour updates below must not inherit it
            entry.addBlock(local, state, off[0], off[1], off[2]);
            // keep native and logical slot order equal; materials disable a liquid slot's collision
            com.koper.koper_lib.panama.KoperPhysBridge.addBlockAtOffset(
                entry.worldHandle(), kontraId, off[0], off[1], off[2],
                KhysWeightBook.get(state).mass());
            if (state.hasBlockEntity() && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                BlockEntity be = entityBlock.newBlockEntity(pos, state);
                if (be != null) { be.setLevel(level); entry.blockEntities.put(local, be); }
            }
            finishBlockChange(level, local, Blocks.AIR.defaultBlockState(), state, flags, recursionLeft);
            KoperPhys.gridBlockChanged(level, kontraId, entry, local, Blocks.AIR.defaultBlockState(), state, flags);
            KoperPhys.pushMaterials(kontraId, entry);
            KoperPhys.pushAero(kontraId, entry);
            return true;
        }
        if (old == state) return false;
        if (state.isAir()) {
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
                var trace = new Throwable().getStackTrace();
                StringBuilder via = new StringBuilder();
                for (int i = 1; i < Math.min(trace.length, 12); i++)
                    via.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
                       .append(':').append(trace[i].getLineNumber()).append(" <- ");
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] setBlock REMOVE local={} old={} flags={} via {}",
                    local, old.getBlock(), flags, via);
            }
            float[] off = entry.blockOffsets.remove(local);
            entry.blocks.remove(local);
            entry.assembledFrom.remove(local);
            entry.localData.remove(local);
            BlockEntity removed = entry.blockEntities.remove(local);
            if (removed != null) {
                // in ctx: container spill spawns items that remap onto the kontra — not 20M blocks away
                if ((flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) == 0)
                    KontraGridContext.run(this, () -> removed.preRemoveSideEffects(pos, old));
                removed.setRemoved();
            }
            if (off != null)
                com.koper.koper_lib.panama.KoperPhysBridge.removeBlockAtOffset(entry.worldHandle(), kontraId,
                    Math.round(off[0]), Math.round(off[1]), Math.round(off[2]));
            entry.invalidateSolidCells();
            entry.invalidateLogicCells();
            finishBlockChange(level, local, old, state, flags, recursionLeft);
            KoperPhys.gridBlockChanged(level, kontraId, entry, local, old, state, flags);
            KoperPhys.pushMaterials(kontraId, entry);
            KoperPhys.pushAero(kontraId, entry);
            if (entry.blocks.isEmpty()) KoperPhys.emptied(kontraId);
            return true;
        }
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode
                && (old.isSignalSource() || state.isSignalSource()
                    || old.getBlock() == Blocks.REDSTONE_WIRE || state.getBlock() == Blocks.REDSTONE_WIRE))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] setBlock STATE local={} {} -> {}", local, old, state);
        entry.blocks.put(local.immutable(), state);
        if (old.getBlock() != state.getBlock()) entry.localData.remove(local);
        // the existing native slot is enabled or disabled by the material update below
        BlockEntity be = entry.blockEntities.get(local);
        // vanilla keeps the BE when its TYPE still matches the new state (LevelChunk.setBlockState).
        // shouldChangedStateKeepBlockEntity defaults to FALSE — using it swapped the BE on EVERY state
        // change: furnace LIT toggle spilled its slots + kicked you from the GUI, dispenser TRIGGERED
        // vomited its whole inventory, sculk PHASE churn, lectern losing its book. all one bug.
        boolean keepBlockEntity = be != null && be.isValidBlockState(state);
        if (be != null && !keepBlockEntity) {
            entry.blockEntities.remove(local);
            if ((flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS) == 0) {
                BlockEntity spilling = be;
                KontraGridContext.run(this, () -> spilling.preRemoveSideEffects(pos, old));
            }
            be.setRemoved();
            be = null;
        }
        if (be != null) {
            be.setBlockState(state);
        } else if (state.hasBlockEntity() && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            BlockEntity created = entityBlock.newBlockEntity(pos, state);
            if (created != null) { created.setLevel(level); entry.blockEntities.put(local, created); }
        }
        entry.invalidateSolidCells();
        entry.invalidateLogicCells();
        finishBlockChange(level, local, old, state, flags, recursionLeft);
        KoperPhys.gridBlockChanged(level, kontraId, entry, local, old, state, flags);
        KoperPhys.pushMaterials(kontraId, entry);
        return true;
    }

    public void finishBlockChange(ServerLevel level, BlockPos local, BlockState oldState, BlockState newState,
                                  int flags, int recursionLeft) {
        KontraGridContext.run(this, () -> {
            BlockPos gridPos = toGrid(local);
            boolean moved = (flags & Block.UPDATE_MOVE_BY_PISTON) != 0;
            boolean changedBlock = oldState.getBlock() != newState.getBlock();
            if (changedBlock && ((flags & Block.UPDATE_NEIGHBORS) != 0 || moved))
                oldState.affectNeighborsAfterRemoval(level, gridPos, moved);
            // vanilla LevelChunk.setBlockState fires onPlace on EVERY server state change (only flag
            // 512 skips it). gating on changedBlock muted DiodeBlock.onPlace → a repeater flip
            // (flag 2, same block) never poked the wire in front of it. clocks died of this.
            if ((flags & Block.UPDATE_SKIP_ON_PLACE) == 0)
                newState.onPlace(level, gridPos, oldState, moved);
            if ((flags & Block.UPDATE_NEIGHBORS) != 0) {
                // MUST be the 3-arg — the 2-arg LevelAccessor default is literally `return` (no-op).
                // every repeater/wire state change on the grid was notifying NOBODY since the 26.2 port.
                level.updateNeighborsAt(gridPos, oldState.getBlock(),
                    (net.minecraft.world.level.redstone.Orientation)null);
                if (newState.hasAnalogOutputSignal())
                    level.updateNeighbourForOutputSignal(gridPos, newState.getBlock());
            }
            if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
                // vanilla strips NEIGHBORS too (& -34), not just suppress-drops
                int shapeFlags = flags & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_SUPPRESS_DROPS);
                oldState.updateIndirectNeighbourShapes(level, gridPos, shapeFlags, recursionLeft - 1);
                newState.updateNeighbourShapes(level, gridPos, shapeFlags, recursionLeft - 1);
                newState.updateIndirectNeighbourShapes(level, gridPos, shapeFlags, recursionLeft - 1);
            }
        });
    }

    public void schedule(BlockPos pos, Object type, int delay) {
        schedule(pos,type,delay,TickPriority.NORMAL);
    }

    public void schedule(BlockPos pos, Object type, int delay, TickPriority priority) {
        if (!(type instanceof Block) && !(type instanceof Fluid)) return;
        // a grid fluid never spreads (setBlock only takes sources), so its tick can only ever
        // re-attempt a spread that gets refused. one pool of water was re-queueing a tick per cell
        // per tick for nothing — dropping them here is most of the tps back.
        if (type instanceof Fluid) return;
        BlockPos immutable=pos.immutable();
        GridTickKey key=new GridTickKey(immutable,type);
        if(!scheduledKeys.add(key)) {
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] schedule DROP local={} type={} delay={}",
                    toLocal(immutable), type, delay);
            return;
        }
        scheduled.add(new GridTick(gameTick + Math.max(0, delay), priority, sequence++, immutable, type));
    }

    public void scheduleFromWorldTick(BlockPos pos, Object type, long triggerTick, TickPriority priority,
                                      long worldGameTick) {
        long delay = Math.max(0L, triggerTick - worldGameTick);
        schedule(pos, type, delay > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)delay, priority);
    }

    public boolean hasScheduledTick(BlockPos pos, Object type) {
        return scheduledKeys.contains(new GridTickKey(pos, type));
    }

    public boolean willTickThisTick(BlockPos pos, Object type) {
        return tickingNow != null && tickingNow.equals(new GridTickKey(pos, type));
    }

    public java.util.List<SavedGridTick> savedTicks() {
        var ordered = new java.util.ArrayList<>(scheduled);
        java.util.Collections.sort(ordered);
        var result = new java.util.ArrayList<SavedGridTick>(ordered.size());
        for (GridTick tick : ordered) {
            boolean isFluid = tick.type instanceof Fluid;
            Identifier id = isFluid
                ? BuiltInRegistries.FLUID.getKey((Fluid)tick.type)
                : BuiltInRegistries.BLOCK.getKey((Block)tick.type);
            if (id != null)
                result.add(new SavedGridTick(toLocal(tick.pos), isFluid, id,
                    Math.max(0L, tick.due - gameTick), tick.priority));
        }
        return result;
    }

    public void restoreTicks(java.util.List<SavedGridTick> ticks) {
        for (SavedGridTick tick : ticks) {
            Object type = tick.fluid
                ? BuiltInRegistries.FLUID.getOptional(tick.type).orElse(null)
                : BuiltInRegistries.BLOCK.getOptional(tick.type).orElse(null);
            if (type == null) continue;
            long delay = Math.max(0L, tick.delay);
            schedule(toGrid(tick.localPos), type,
                delay > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)delay, tick.priority);
        }
    }

    public void moveScheduledTicks(Map<BlockPos, BlockPos> localRemap, KontraGrid target) {
        if (localRemap.isEmpty() || scheduled.isEmpty()) return;
        var moved = new java.util.ArrayList<GridTick>();
        var it = scheduled.iterator();
        while (it.hasNext()) {
            GridTick tick = it.next();
            BlockPos newLocal = localRemap.get(toLocal(tick.pos));
            if (newLocal == null) continue;
            it.remove();
            scheduledKeys.remove(new GridTickKey(tick.pos, tick.type));
            moved.add(new GridTick(Math.max(0L, tick.due - gameTick), tick.priority,
                tick.sequence, newLocal.immutable(), tick.type));
        }
        moved.sort(null);
        for (GridTick tick : moved) {
            long delay = tick.due;
            target.schedule(target.toGrid(tick.pos), tick.type,
                delay > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)delay, tick.priority);
        }
    }

    public void tick(ServerLevel level) {
        invalidateExternalCache();
        gameTick++;
        // self-heal: a dedup key without its queue entry blocks that block's scheduling FOREVER —
        // log showed pending=0 with schedule DROPs raining = orphaned keys = the frozen clock.
        // rebuild keys from the queue whenever they disagree; the orphan source hunt continues.
        if (scheduledKeys.size() != scheduled.size()) {
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] orphan heal kontra={} keys={} queue={}",
                    kontraId, scheduledKeys.size(), scheduled.size());
            scheduledKeys.clear();
            for (GridTick pending : scheduled) scheduledKeys.add(new GridTickKey(pending.pos, pending.type));
        }
        // frozen-clock heartbeat: pending=0 → scheduling died; pending>0 stale due → drain died;
        // no heartbeat at all → tick() itself stopped being called
        if (gameTick % 100 == 0 && com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] hb kontra={} gameTick={} pending={} nextDue={}",
                kontraId, gameTick, scheduled.size(), scheduled.isEmpty() ? "-" : scheduled.peek().due);
        int budget = 65536;
        while (budget-- > 0 && !scheduled.isEmpty() && scheduled.peek().due <= gameTick) {
            GridTick tick = scheduled.poll();
            GridTickKey key = new GridTickKey(tick.pos,tick.type);
            scheduledKeys.remove(key);
            tickingNow = key;
            BlockPos local = toLocal(tick.pos);
            BlockState state = getBlockState(local);
            try {
                if (tick.type instanceof Block block) {
                    if (state.is(block)) {
                        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] tick FIRE local={} type={}", local, block);
                        state.tick(level, tick.pos, level.getRandom());
                    } else if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode) {
                        // a swallowed tick kills an oscillator chain dead — this names it
                        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] tick SKIP local={} expected={} actual={}",
                            local, block, state.getBlock());
                    }
                } else if (tick.type instanceof Fluid fluid) {
                    var fluidState = state.getFluidState();
                    if (fluidState.is(fluid)) fluidState.tick(level, tick.pos, state);
                }
            } catch (Throwable ex) {
                // one puking block must not abort the whole drain (and the rest of tickAll with it)
                if (TICK_ERRORS.add(state.getBlock().getClass().getName() + ":" + ex.getClass().getName()))
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[KontraGrid] scheduled tick failed at {} for {}",
                        local, state.getBlock(), ex);
            } finally {
                tickingNow = null;
            }
        }

        int randomTickSpeed = level.getGameRules().get(
            net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
        if (randomTickSpeed > 0) {
            java.util.List<GridSection> sections = randomTickSections;
            if (sections == null) {
                var unique = new java.util.LinkedHashSet<GridSection>();
                for (BlockPos local : entry.blocks.keySet())
                    unique.add(new GridSection(local.getX() >> 4, local.getY() >> 4, local.getZ() >> 4));
                randomTickSections = sections = java.util.List.copyOf(unique);
            }
            var random = level.getRandom();
            for (GridSection section : sections) {
                for (int i = 0; i < randomTickSpeed; i++) {
                    BlockPos local = new BlockPos((section.x() << 4) + random.nextInt(16),
                        (section.y() << 4) + random.nextInt(16), (section.z() << 4) + random.nextInt(16));
                    BlockState state = entry.blocks.get(local);
                    if (state == null) continue;
                    BlockPos gridPos = toGrid(local);
                    if (state.isRandomlyTicking()) state.randomTick(level, gridPos, random);
                    var fluidState = state.getFluidState();
                    if (fluidState.isRandomlyTicking()) fluidState.randomTick(level, gridPos, random);
                }
            }
        }

        runBlockEvents(level);
    }

    private record GridEvent(BlockPos gridPos, Block block, int id, int data) {}
    private final java.util.LinkedHashSet<GridEvent> pendingEvents = new java.util.LinkedHashSet<>();

    // vanilla QUEUES block events and drains them later in the tick. running them inline mid
    // neighborChanged made pistons extend+retract in one call (checkIfExtend fired both) — wire got
    // eaten by moveBlocks instantly and chest lids never animated. so: queue here, drain in tick().
    public void queueBlockEvent(BlockPos gridPos, Block block, int eventId, int eventData) {
        pendingEvents.add(new GridEvent(gridPos.immutable(), block, eventId, eventData));
    }

    private void runBlockEvents(ServerLevel level) {
        int budget = 4096;
        while (!pendingEvents.isEmpty() && budget-- > 0) {
            var it = pendingEvents.iterator();
            GridEvent ev = it.next();
            it.remove();
            BlockPos local = toLocal(ev.gridPos());
            BlockState state = entry.blocks.get(local);
            if (state == null || !state.is(ev.block())) continue;
            boolean handled = state.triggerEvent(level, ev.gridPos(), ev.id(), ev.data());
            if (handled)
                com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                    new com.koper.koper_lib.network.KenderBlockEventPayload(kontraId,
                        KoperPhys.clientLocal(entry, local), ev.id(), ev.data()));
        }
    }

    public void syncBlockEntity(ServerLevel level, BlockPos gridPos) {
        BlockPos local = toLocal(gridPos);
        BlockEntity be = entry.blockEntities.get(local);
        if (be == null) return;
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KenderBlockEntityUpdatePayload(kontraId,
                KoperPhys.clientLocal(entry, local),
                KoperPhys.beSyncTag(be, level)));
    }
}
