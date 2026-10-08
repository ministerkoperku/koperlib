package com.koper.koper_lib.physics;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;

public final class KontraGridPerfProbe {
    private static final int X = 100;
    private static final int Y = 30;
    private static final int Z = 20;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int count = X * Y * Z;
        float[] offsets = new float[count * 3];
        int i = 0;
        for (int y = 0; y < Y; y++)
            for (int z = 0; z < Z; z++)
                for (int x = 0; x < X; x++) {
                    offsets[i++] = x - (X - 1) * 0.5f;
                    offsets[i++] = y - (Y - 1) * 0.5f;
                    offsets[i++] = z - (Z - 1) * 0.5f;
                }
        KontraEntry entry = new KontraEntry("perf", 1L,
            Collections.nCopies(count, Blocks.STONE.defaultBlockState()), offsets);
        float[] identity = {0f, 0f, 0f, 1f};
        float angle = (float)Math.toRadians(23.0);
        float[] rotated = {0f, (float)Math.sin(angle * 0.5f), 0f, (float)Math.cos(angle * 0.5f)};

        var empty = entry.logicCells();
        long t0 = System.nanoTime();
        var first = entry.rebuildLogicCells(new float[]{0.5f, 80.5f, 0.5f}, identity);
        long t1 = System.nanoTime();
        KoperPhys.PhysicalIndex index = new KoperPhys.PhysicalIndex();
        updateIndex(empty,first,index);
        long tIndex1 = System.nanoTime();
        var stable = entry.rebuildLogicCells(new float[]{0.5f, 80.5f, 0.5f}, identity);
        long t2 = System.nanoTime();
        var moved = entry.rebuildLogicCells(new float[]{0.75f, 80.5f, 0.5f}, identity);
        long t3 = System.nanoTime();
        updateIndex(first,moved,index);
        long tIndex2 = System.nanoTime();
        var spun = entry.rebuildLogicCells(new float[]{0.75f, 80.5f, 0.5f}, rotated);
        long t4 = System.nanoTime();
        updateIndex(moved,spun,index);
        long tIndex3 = System.nanoTime();
        if (first.isEmpty() || moved.isEmpty() || spun.isEmpty() || stable != first)
            throw new AssertionError("logic-cell cache invariant failed");
        if (index.primary.size() != spun.size() || !index.overlaps.isEmpty())
            throw new AssertionError("physical index invariant failed");
        System.out.printf("KONTRA_GRID_PERF blocks=%d cells=%d/%d/%d build=%.2fms index=%.2fms stable=%.4fms move=%.2f+%.2fms rotate=%.2f+%.2fms%n",
            count, first.size(), moved.size(), spun.size(), ms(t1-t0), ms(tIndex1-t1), ms(t2-tIndex1),
            ms(t3-t2), ms(tIndex2-t3), ms(t4-tIndex2), ms(tIndex3-t4));
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static void updateIndex(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?> oldCells,
                                    it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?> newCells,
                                    KoperPhys.PhysicalIndex index) {
        for (long cell : oldCells.keySet()) if (!newCells.containsKey(cell)) index.remove(cell,1L);
        for (long cell : newCells.keySet()) if (!oldCells.containsKey(cell)) index.add(cell,1L);
    }
}
