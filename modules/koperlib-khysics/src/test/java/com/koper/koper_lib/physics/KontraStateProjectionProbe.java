package com.koper.koper_lib.physics;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.util.List;

public final class KontraStateProjectionProbe {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        float s = (float)Math.sqrt(0.5);
        float[] identity = {0, 0, 0, 1};
        float[] yaw90 = {0, s, 0, s};

        BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(RedstoneWireBlock.NORTH, RedstoneSide.SIDE)
            .setValue(RedstoneWireBlock.EAST, RedstoneSide.UP)
            .setValue(RedstoneWireBlock.SOUTH, RedstoneSide.NONE)
            .setValue(RedstoneWireBlock.WEST, RedstoneSide.SIDE)
            .setValue(RedstoneWireBlock.POWER, 11);
        require(KoperPhys.stateToWorld(dust, identity).equals(dust), "dust identity");
        BlockState worldDust = KoperPhys.stateToWorld(dust, yaw90);
        require(worldDust.getValue(RedstoneWireBlock.NORTH) == RedstoneSide.UP, "dust east -> world north");
        require(worldDust.getValue(RedstoneWireBlock.WEST) == RedstoneSide.SIDE, "dust north -> world west");
        require(worldDust.getValue(RedstoneWireBlock.POWER) == 11, "dust power preserved");
        require(KoperPhys.stateToGrid(worldDust, yaw90).equals(dust), "dust yaw roundtrip");

        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST);
        BlockState worldObserver = KoperPhys.stateToWorld(observer, yaw90);
        require(worldObserver.getValue(ObserverBlock.FACING) == Direction.NORTH, "facing east -> world north");
        require(KoperPhys.stateToGrid(worldObserver, yaw90).equals(observer), "facing yaw roundtrip");

        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState worldLog = KoperPhys.stateToWorld(log, yaw90);
        require(worldLog.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Z, "axis x -> world z");
        require(KoperPhys.stateToGrid(worldLog, yaw90).equals(log), "axis yaw roundtrip");

        float[][] identityAxes = KoperPhys.quaternionAxes(identity);
        float[] cellCenter = {0.5f, 0.5f, 0.5f};
        require(KoperPhys.obbTouchesCell(cellCenter, identityAxes, 0, 0, 0), "identity cell overlap");
        require(!KoperPhys.obbTouchesCell(cellCenter, identityAxes, 1, 0, 0), "face-only contact is disconnected");

        float[] yaw45 = {0, (float)Math.sin(Math.PI / 8), 0, (float)Math.cos(Math.PI / 8)};
        float[][] yaw45Axes = KoperPhys.quaternionAxes(yaw45);
        require(KoperPhys.obbTouchesCell(cellCenter, yaw45Axes, 1, 0, 0), "rotated OBB reaches side cell");
        require(!KoperPhys.obbTouchesCell(cellCenter, yaw45Axes, 1, 0, 1), "rotated OBB misses diagonal cell");

        KontraEntry pointEntry = new KontraEntry("probe", 1L,
            List.of(Blocks.STONE.defaultBlockState()), new float[]{2f, -1f, 4f});
        KontraGrid pointGrid = pointEntry.grid(44L);
        BlockPos pointAnchor = pointGrid.anchor();
        float[] centerOffset = pointGrid.offsetForGridPoint(pointAnchor.getX() + 2.5,
            pointAnchor.getY() - 0.5, pointAnchor.getZ() + 4.5);
        require(close(centerOffset, 2f, -1f, 4f), "grid block center maps to body offset");
        float[] innerOffset = pointGrid.offsetForGridPoint(pointAnchor.getX() + 2.75,
            pointAnchor.getY() - 0.25, pointAnchor.getZ() + 4.0);
        require(close(innerOffset, 2.25f, -0.75f, 3.5f), "continuous grid point keeps sub-block offset");
        float[] minCorner = pointGrid.offsetForGridPoint(pointAnchor.getX() + 2.0,
            pointAnchor.getY() - 1.0, pointAnchor.getZ() + 4.0);
        float[] maxCorner = pointGrid.offsetForGridPoint(pointAnchor.getX() + 3.0,
            pointAnchor.getY(), pointAnchor.getZ() + 5.0);
        require(close(minCorner, 1.5f, -1.5f, 3.5f)
                && close(maxCorner, 2.5f, -0.5f, 4.5f),
            "grid block AABB stays centered on its body offset");
        require(pointGrid.offsetForGridPoint(pointAnchor.getX() + 200.5,
            pointAnchor.getY() + 0.5, pointAnchor.getZ() + 200.5) == null,
            "already-world-space effect is not remapped");
        float[] rotatedVector = KoperPhys.localToWorld(1f, 0f, 0f, new float[]{0f,0f,0f}, yaw90);
        require(close(rotatedVector, 0f, 0f, -1f), "effect velocity follows body rotation");

        BlockState tiltedDust = KoperPhys.stateToWorld(dust,
            new float[]{0.31f, 0.22f, -0.17f, 0.9085152f});
        require(tiltedDust.getValue(RedstoneWireBlock.POWER) == 11, "arbitrary rotation preserves power");

        BlockState chestState = Blocks.CHEST.defaultBlockState();
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, chestState);
        require(chest.triggerEvent(1, 1), "chest accepts open event");
        ChestBlockEntity.lidAnimateTick(null, BlockPos.ZERO, chestState, chest);
        ChestBlockEntity.lidAnimateTick(null, BlockPos.ZERO, chestState, chest);
        float opened = chest.getOpenNess(1f);
        require(opened > 0f, "chest lid advances after open event");
        require(chest.triggerEvent(1, 0), "chest accepts close event");
        ChestBlockEntity.lidAnimateTick(null, BlockPos.ZERO, chestState, chest);
        require(chest.getOpenNess(1f) < opened, "chest lid advances after close event");

        KontraEntry buffered = new KontraEntry("probe", 1L,
            List.of(Blocks.REDSTONE_BLOCK.defaultBlockState()), new float[]{0f, 0f, 0f});
        var firstCells = buffered.rebuildLogicCells(new float[]{0.5f, 0.5f, 0.5f}, identity);
        var secondCells = buffered.rebuildLogicCells(new float[]{1.5f, 0.5f, 0.5f}, identity);
        require(firstCells != secondCells, "logic maps keep old and new snapshots apart");
        var thirdCells = buffered.rebuildLogicCells(new float[]{2.5f, 0.5f, 0.5f}, identity);
        require(thirdCells == firstCells, "logic maps reuse the old scratch buffer");
        KontraGrid tickGrid = buffered.grid(1L);
        BlockPos tickPos = tickGrid.toGrid(BlockPos.ZERO);
        require(!tickGrid.hasScheduledTick(tickPos, Blocks.REDSTONE_BLOCK), "grid tick starts empty");
        tickGrid.schedule(tickPos, Blocks.REDSTONE_BLOCK, 2);
        require(tickGrid.hasScheduledTick(tickPos, Blocks.REDSTONE_BLOCK), "grid tick query sees scheduled block");
        require(!tickGrid.willTickThisTick(tickPos, Blocks.REDSTONE_BLOCK), "future grid tick is not ticking now");
        var savedTicks = tickGrid.savedTicks();
        require(savedTicks.size() == 1 && savedTicks.getFirst().delay() == 2, "grid tick keeps remaining delay");
        KontraEntry restoredTicksEntry = new KontraEntry("probe", 1L,
            List.of(Blocks.REDSTONE_BLOCK.defaultBlockState()), new float[]{0f, 0f, 0f});
        KontraGrid restoredTicks = restoredTicksEntry.grid(2L);
        restoredTicks.restoreTicks(savedTicks);
        require(restoredTicks.hasScheduledTick(restoredTicks.toGrid(BlockPos.ZERO), Blocks.REDSTONE_BLOCK),
            "grid tick survives persistence roundtrip");
        KontraEntry splitTicksEntry = new KontraEntry("probe", 1L,
            List.of(Blocks.REDSTONE_BLOCK.defaultBlockState()), new float[]{0f, 0f, 0f});
        KontraGrid splitTicks = splitTicksEntry.grid(3L);
        restoredTicks.moveScheduledTicks(java.util.Map.of(BlockPos.ZERO, BlockPos.ZERO), splitTicks);
        require(!restoredTicks.hasScheduledTick(restoredTicks.toGrid(BlockPos.ZERO), Blocks.REDSTONE_BLOCK),
            "split removes tick from parent grid");
        require(splitTicks.hasScheduledTick(splitTicks.toGrid(BlockPos.ZERO), Blocks.REDSTONE_BLOCK),
            "split moves tick to child grid");
        KontraEntry reconnected = new KontraEntry("probe", 1L, List.of(
            Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()),
            new float[]{0f,0f,0f, 1f,0f,0f, 2f,0f,0f});
        require(KoperPhys.splitReconnected(reconnected, List.of(new BlockPos(1,0,0), new BlockPos(2,0,0))),
            "stale async split sees the new connection");
        KontraEntry detached = new KontraEntry("probe", 1L, List.of(
            Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState()),
            new float[]{0f,0f,0f, 5f,0f,0f});
        require(!KoperPhys.splitReconnected(detached, List.of(new BlockPos(5,0,0))),
            "real detached component still splits");
        System.out.println("KONTRA_STATE_PROJECTION_OK");
    }

    private static void require(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }

    private static boolean close(float[] value, float x, float y, float z) {
        return value != null && Math.abs(value[0] - x) < 0.0001f
            && Math.abs(value[1] - y) < 0.0001f && Math.abs(value[2] - z) < 0.0001f;
    }
}
