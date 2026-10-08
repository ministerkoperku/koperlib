package com.koper.koper_lib.physics;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// event bus for fullpacks + other mods — register listeners here, fired by KoperPhys
// CopyOnWriteArrayList: safe for concurrent iterate + rare add/remove
public final class KoperPhysicsEvents {

    private KoperPhysicsEvents() {}

    @FunctionalInterface public interface KontraTick     { void tick(long id, float[] pos, float[] rot, float[] vel); }
    @FunctionalInterface public interface BlockBreakHook { void onBreak(long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state); }
    @FunctionalInterface public interface BlockPlaceHook { void onPlace(long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state); }
    // fired when connectivity carved a child kontra off a parent. remap = parent-local -> child-local
    // for every block that moved; addon connection graphs re-key their nodes off this
    @FunctionalInterface public interface SplitHook      { void onSplit(long parentId, long childId, java.util.Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> remap); }
    // player right-clicked block `local` on a live kontra. return true = you ate the click,
    // vanilla use never runs (seats, connectors, gear switches live here)
    @FunctionalInterface public interface GridUseHook    { boolean onUse(net.minecraft.server.level.ServerPlayer player, long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state); }
    // player is about to punch out block `local`. return true = cancel the break entirely
    @FunctionalInterface public interface GridAttackHook { boolean onAttack(net.minecraft.server.level.ServerPlayer player, long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state); }
    public record GridHit(long kontraId, net.minecraft.core.BlockPos local,
                          net.minecraft.world.level.block.state.BlockState state,
                          net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                          net.minecraft.core.Direction face,
                          float hitX, float hitY, float hitZ,
                          KontraGrid grid, KontraEntry entry,
                          net.minecraft.world.phys.Vec3 worldPoint,
                          net.minecraft.core.Direction worldFace,
                          net.minecraft.world.phys.Vec3 bodyPosition,
                          float bodyRotX, float bodyRotY, float bodyRotZ, float bodyRotW,
                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        public GridHit(long kontraId, net.minecraft.core.BlockPos local,
                       net.minecraft.world.level.block.state.BlockState state,
                       net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                       net.minecraft.core.Direction face,
                       float hitX, float hitY, float hitZ,
                       KontraGrid grid, KontraEntry entry) {
            this(kontraId, local, state, blockEntity, face, hitX, hitY, hitZ, grid, entry,
                net.minecraft.world.phys.Vec3.ZERO, face, net.minecraft.world.phys.Vec3.ZERO,
                0f, 0f, 0f, 1f, null);
        }

        public net.minecraft.core.BlockPos logicalPos() {
            return grid.toGrid(local);
        }

        public net.minecraft.world.phys.Vec3 blockPoint() {
            return new net.minecraft.world.phys.Vec3(hitX + 0.5, hitY + 0.5, hitZ + 0.5);
        }
    }
    @FunctionalInterface public interface DetailedGridUseHook {
        boolean onUse(net.minecraft.server.level.ServerPlayer player, GridHit hit);
    }
    @FunctionalInterface public interface DetailedGridAttackHook {
        boolean onAttack(net.minecraft.server.level.ServerPlayer player, GridHit hit);
    }

    // ANY block on a live kontra changed: placed (old is air), broken (new is air), swapped, or just a
    // state flip — a lever, a powered anchor, a filled tank. the one hook to keep per-block counts
    // honest without re-sweeping the hull on a timer
    @FunctionalInterface public interface BlockChangeHook {
        void onChange(long id, net.minecraft.core.BlockPos local,
                      net.minecraft.world.level.block.state.BlockState oldState,
                      net.minecraft.world.level.block.state.BlockState newState);
    }
    public static final List<BlockChangeHook>                   ON_BLOCK_CHANGE = new CopyOnWriteArrayList<>();

    public static final List<BiConsumer<Long, KontraEntry>> ON_SPAWN       = new CopyOnWriteArrayList<>();
    public static final List<Consumer<Long>>                    ON_DESTROY     = new CopyOnWriteArrayList<>();
    // a joint going away, by its id. fired before it is forgotten, so jointSpecs() still has it
    public static final List<Consumer<Long>>                    ON_JOINT_DESTROY = new CopyOnWriteArrayList<>();
    public static final List<KontraTick>                        ON_TICK        = new CopyOnWriteArrayList<>();
    public static final List<BlockBreakHook>                    ON_BLOCK_BREAK = new CopyOnWriteArrayList<>();
    public static final List<BlockPlaceHook>                    ON_BLOCK_PLACE = new CopyOnWriteArrayList<>();
    public static final List<SplitHook>                         ON_SPLIT       = new CopyOnWriteArrayList<>();
    public static final List<GridUseHook>                       ON_GRID_USE    = new CopyOnWriteArrayList<>();
    public static final List<GridAttackHook>                    ON_GRID_ATTACK = new CopyOnWriteArrayList<>();
    public static final List<DetailedGridUseHook>               ON_DETAILED_GRID_USE = new CopyOnWriteArrayList<>();
    public static final List<DetailedGridAttackHook>            ON_DETAILED_GRID_ATTACK = new CopyOnWriteArrayList<>();
    // a kontra came back from kontras.bin under a NEW id — addons re-key their vehicle data off this
    // A joint anchored to the WORLD (b == 0) is a gameplay attachment: a bearing plate, a lift rail.
    // Physics has no idea whether the block that justified it still stands, and reviving one blind
    // nails its body to a point in mid air forever — the "it spawned and now it levitates" bug.
    // So on load we ask whoever created them. No listeners = old behaviour, revive everything.
    @FunctionalInterface public interface WorldJointClaim {
        boolean claims(net.minecraft.server.level.ServerLevel level,
                       float[] anchor, float[] axis, boolean prismatic);
    }
    public static final List<WorldJointClaim> WORLD_JOINT_CLAIM = new CopyOnWriteArrayList<>();

    public static boolean worldJointClaimed(net.minecraft.server.level.ServerLevel level,
                                            float[] anchor, float[] axis, boolean prismatic) {
        if (WORLD_JOINT_CLAIM.isEmpty() || level == null) return true;
        for (WorldJointClaim claim : WORLD_JOINT_CLAIM) {
            try {
                if (claim.claims(level, anchor, axis, prismatic)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    @FunctionalInterface public interface RestoreHook { void onRestore(long savedId, long newId); }
    public static final List<RestoreHook>                       ON_RESTORE     = new CopyOnWriteArrayList<>();
    @FunctionalInterface public interface TransferHook {
        void onTransfer(java.util.Map<Long, Long> bodies,
                        net.minecraft.server.level.ServerLevel from,
                        net.minecraft.server.level.ServerLevel to);
    }
    public static final List<TransferHook>                      ON_TRANSFER    = new CopyOnWriteArrayList<>();

    public static void fireBlockChange(long id, net.minecraft.core.BlockPos local,
                                       net.minecraft.world.level.block.state.BlockState oldState,
                                       net.minecraft.world.level.block.state.BlockState newState) {
        for (var l : ON_BLOCK_CHANGE) { try { l.onChange(id, local, oldState, newState); } catch (Throwable ignored) {} }
    }
    public static void fireSpawn(long id, KontraEntry data) {
        for (var l : ON_SPAWN) { try { l.accept(id, data); } catch (Throwable ignored) {} }
    }
    public static void fireDestroy(long id) {
        for (var l : ON_DESTROY) { try { l.accept(id); } catch (Throwable ignored) {} }
    }
    public static void fireTick(long id, float[] pos, float[] rot, float[] vel) {
        for (var l : ON_TICK) { try { l.tick(id, pos, rot, vel); } catch (Throwable ignored) {} }
    }
    public static void fireBlockBreak(long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state) {
        for (var l : ON_BLOCK_BREAK) { try { l.onBreak(id, local, state); } catch (Throwable ignored) {} }
    }
    public static void fireBlockPlace(long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state) {
        for (var l : ON_BLOCK_PLACE) { try { l.onPlace(id, local, state); } catch (Throwable ignored) {} }
    }
    public static void fireSplit(long parentId, long childId, java.util.Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos> remap) {
        for (var l : ON_SPLIT) { try { l.onSplit(parentId, childId, remap); } catch (Throwable ignored) {} }
    }
    // true = somebody consumed the click, skip vanilla use
    public static boolean fireGridUse(net.minecraft.server.level.ServerPlayer player, long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state) {
        for (var l : ON_GRID_USE) {
            try {
                if (l.onUse(player, id, local, state)) return true;
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KoperPhys] grid use hook failed at kontra {} local {}", id, local, error);
            }
        }
        return false;
    }
    // true = somebody cancelled the break
    public static boolean fireGridAttack(net.minecraft.server.level.ServerPlayer player, long id, net.minecraft.core.BlockPos local, net.minecraft.world.level.block.state.BlockState state) {
        for (var l : ON_GRID_ATTACK) {
            try {
                if (l.onAttack(player, id, local, state)) return true;
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KoperPhys] grid attack hook failed at kontra {} local {}", id, local, error);
            }
        }
        return false;
    }
    public static boolean fireDetailedGridUse(net.minecraft.server.level.ServerPlayer player, GridHit hit) {
        for (var l : ON_DETAILED_GRID_USE) {
            try {
                if (l.onUse(player, hit)) return true;
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KoperPhys] detailed grid use hook failed at kontra {} local {}",
                    hit.kontraId(), hit.local(), error);
            }
        }
        return false;
    }
    public static boolean fireDetailedGridAttack(net.minecraft.server.level.ServerPlayer player, GridHit hit) {
        for (var l : ON_DETAILED_GRID_ATTACK) {
            try {
                if (l.onAttack(player, hit)) return true;
            } catch (Throwable error) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KoperPhys] detailed grid attack hook failed at kontra {} local {}",
                    hit.kontraId(), hit.local(), error);
            }
        }
        return false;
    }
    public static void fireRestore(long savedId, long newId) {
        for (var l : ON_RESTORE) { try { l.onRestore(savedId, newId); } catch (Throwable ignored) {} }
    }
    public static void fireTransfer(java.util.Map<Long, Long> bodies,
                                    net.minecraft.server.level.ServerLevel from,
                                    net.minecraft.server.level.ServerLevel to) {
        for (var l : ON_TRANSFER) {
            try { l.onTransfer(bodies, from, to); } catch (Throwable ignored) {}
        }
    }
}
