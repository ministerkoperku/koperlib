package com.koper.koper_lib.elpe;

import com.koper.koper_lib.coremod.KoperCore;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.IdentityHashMap;
import java.util.Map;

// one elpe world per server level, made lazily the first time somebody asks for it.
// levels nobody asked for cost nothing — not even the block change hook does work there
public final class ElpeLevelBoss {
    // hello whoever reads this. 20 tps, 2 substeps. if you know better numbers PLEASE HELP A SILLY LITTLE KOPERDEV
    public static final float TICK_DT = 0.05f;
    public static int substeps = 2;
    public static int startCapacity = 1 << 16;
    public static float maxRadius = 0.5f;
    // sections fed per tick, so a huge wake-up doesnt stall the server for a second
    public static int sectionBudget = 256;

    private static final Map<ServerLevel, KoperElpeSlot> WORLDS = new IdentityHashMap<>();

    private static final class KoperElpeSlot {
        final ElpeKoperWorld world;
        // sections elpe asked for whose chunk isnt loaded yet. packed SectionPos longs
        final LongArrayFIFOQueue waiting = new LongArrayFIFOQueue();
        final LongOpenHashSet waitingSet = new LongOpenHashSet();
        final long[] bits = new long[64];

        KoperElpeSlot(ElpeKoperWorld world) { this.world = world; }
    }

    private ElpeLevelBoss() {}

    public static ElpeKoperWorld of(ServerLevel level) {
        KoperElpeSlot slot = WORLDS.get(level);
        if (slot != null) return slot.world;
        if (!ElpeKoperWorld.available()) return null;
        ElpeKoperWorld w = new ElpeKoperWorld(startCapacity, maxRadius);
        // falling out of the world kills the point, same as entities
        w.tune(ElpeTuning.KOPER_DEFAULT.withKillY(level.getMinY() - 64));
        WORLDS.put(level, new KoperElpeSlot(w));
        KoperCore.LOGGER.info("[Elpe] world up for {}", level.dimension());
        return w;
    }

    public static ElpeKoperWorld peekExisting(ServerLevel level) {
        KoperElpeSlot slot = WORLDS.get(level);
        return slot == null ? null : slot.world;
    }

    public static void tick(ServerLevel level) {
        KoperElpeSlot slot = WORLDS.get(level);
        if (slot == null) return;
        feed(level, slot);
        slot.world.step(TICK_DT, substeps);
        ElpeRubble.tick(level, slot.world);
        ElpeMod.debugParticles(level, slot.world);
    }

    private static void feed(ServerLevel level, KoperElpeSlot slot) {
        int[] asked = slot.world.requests();
        for (int k = 0; k + 2 < asked.length; k += 3) {
            long key = SectionPos.asLong(asked[k], asked[k + 1], asked[k + 2]);
            if (slot.waitingSet.add(key)) slot.waiting.enqueue(key);
        }
        int budget = sectionBudget;
        int tries = slot.waiting.size();
        int minSy = level.getMinSectionY(), maxSy = level.getMaxSectionY();
        while (budget > 0 && tries-- > 0 && !slot.waiting.isEmpty()) {
            long key = slot.waiting.dequeueLong();
            int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
            if (sy < minSy || sy > maxSy) {
                // above or below the build limit is just air
                slot.world.section(sx, sy, sz, null);
                slot.waitingSet.remove(key);
                budget--;
                continue;
            }
            // never force a chunk load for physics. not loaded = the points stay frozen, try again later
            LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
            if (chunk == null) {
                slot.waiting.enqueue(key);
                continue;
            }
            var section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
            boolean any = ElpeTerrainSniffer.sniff(section, slot.bits);
            slot.world.section(sx, sy, sz, any ? slot.bits : null);
            slot.waitingSet.remove(key);
            budget--;
        }
    }

    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState state) {
        KoperElpeSlot slot = WORLDS.get(level);
        if (slot == null) return;
        slot.world.block(pos.getX(), pos.getY(), pos.getZ(), ElpeTerrainSniffer.solid(state));
    }

    public static void dropAll() {
        WORLDS.values().forEach(s -> s.world.close());
        WORLDS.clear();
    }

    public static void drop(ServerLevel level) {
        ElpeRubble.settleLevel(level);
        KoperElpeSlot slot = WORLDS.remove(level);
        if (slot != null) slot.world.close();
    }
}
