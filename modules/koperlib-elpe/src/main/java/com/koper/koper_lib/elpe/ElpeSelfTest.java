package com.koper.koper_lib.elpe;

import com.koper.koper_lib.coremod.KoperCore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

// dev only: ELPE_SELFTEST=1 gradlew :koperlib-elpe:runServer. drops stuff on the real overworld and logs what happened
public final class ElpeSelfTest {
    public static final boolean ON = System.getenv("ELPE_SELFTEST") != null;
    private static int tick;
    private static int ball, ropeEnd, groundY;
    private static BlockPos under;
    private static int placedBefore;

    // rubble that landed on top of the flat ground shows up as blocks above groundY
    private static int countAbove(ServerLevel level) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(-30, groundY, -30), new BlockPos(29, groundY + 6, 29)))
            if (!level.getBlockState(p).isAir()) n++;
        return n;
    }

    private static int displays(ServerLevel level) {
        int n = 0;
        for (var e : level.getAllEntities()) if (e.entityTags().contains(ElpeRubble.TAG)) n++;
        return n;
    }

    private ElpeSelfTest() {}

    public static void tick(ServerLevel level) {
        if (!ON || level.dimension() != Level.OVERWORLD) return;
        tick++;
        // no players = no loaded chunks = elpe correctly freezes everything. so hold the test area loaded
        if (tick == 1) for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
        if (tick < 20) return;
        ElpeKoperWorld w = ElpeLevelBoss.of(level);
        if (w == null) { if (tick == 20) KoperCore.LOGGER.error("[ElpeSelfTest] no native"); return; }
        if (tick == 20) {
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
            groundY = top.getY();
            under = top.below();
            ball = w.spawn(0.5, groundY + 10, 0.5, 0.3f, 1f, 0);
            float[] pile = new float[3 * 1000];
            for (int i = 0; i < 1000; i++) {
                pile[i * 3] = 5 + (i % 10) * 0.55f;
                pile[i * 3 + 1] = groundY + 3 + (i / 100) * 0.55f;
                pile[i * 3 + 2] = 5 + ((i / 10) % 10) * 0.55f;
            }
            w.spawnMany(pile, 0.25f, 1f, 0);
            int prev = ElpeKoperWorld.NONE;
            for (int i = 0; i < 20; i++) {
                int id = w.spawn(-5 + i * 0.4, groundY + 30, -5, 0.1f, 1f, 777);
                if (prev == ElpeKoperWorld.NONE) w.pin(id, -5, groundY + 30, -5, 0f, 1f, 0f);
                else w.joint(prev, id, 0f, 0.4f, 1f, 0f);
                prev = id;
            }
            ropeEnd = prev;
            KoperCore.LOGGER.info("[ElpeSelfTest] ground y {} under {}, spawned", groundY, level.getBlockState(under));
        }
        if (tick == 120) {
            var p = w.peek(ball);
            KoperCore.LOGGER.info("[ElpeSelfTest] ball y {} state {} (expect ~{} asleep=2) | rope end y {} (pin {}) | {}",
                p.y(), p.state(), groundY + 0.3f, w.peek(ropeEnd).y(), groundY + 30, w.stats());
            level.setBlock(under, Blocks.AIR.defaultBlockState(), 3);
        }
        if (tick == 160) {
            var p = w.peek(ball);
            KoperCore.LOGGER.info("[ElpeSelfTest] block under it broken: ball y {} state {} | {}", p.y(), p.state(), w.stats());
        }
        if (tick == 170) {
            ElpeRubble.explosions = true;
            placedBefore = countAbove(level);
            level.explode(null, 8.5, groundY - 1, 8.5, 4f, Level.ExplosionInteraction.TNT);
            KoperCore.LOGGER.info("[ElpeSelfTest] tnt went off: rubble live {} displays {} | blocks above ground before {}",
                ElpeRubble.live(level), displays(level), placedBefore);
        }
        if (tick == 200) {
            KoperCore.LOGGER.info("[ElpeSelfTest] 1.5s later: rubble live {} displays {} | {}", ElpeRubble.live(level), displays(level), w.stats());
        }
        if (tick == 500) {
            KoperCore.LOGGER.info("[ElpeSelfTest] 20s later: rubble live {} displays {} | blocks above ground now {} (was {}) | {}",
                ElpeRubble.live(level), displays(level), countAbove(level), placedBefore, w.stats());
            KoperCore.LOGGER.info("[ElpeSelfTest] landed {} dropped {} lost {} | still flying: {}",
                ElpeRubble.landed, ElpeRubble.dropped, ElpeRubble.lost, ElpeRubble.whyAwake(level, w));
            KoperCore.LOGGER.info("[ElpeSelfTest] DONE");
        }
    }
}
