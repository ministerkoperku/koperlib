package com.koper.koper_lib.elpe;

import com.koper.koper_lib.api.core.KoperCommands;
import com.koper.koper_lib.api.core.KoperModules;
import com.koper.koper_lib.coremod.KoperCore;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class ElpeMod implements ModInitializer {
    // levels where /koperlib elpe show is on
    static final Set<ServerLevel> SHOWING = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance().getModContainer("koperlib_elpe")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        KoperModules.register("elpe", version, KoperModules.Environment.COMMON, "physics", "points", "joints");
        if (!ElpeKoperWorld.available()) {
            KoperCore.LOGGER.warn("[Elpe] native missing, elpe worlds will not start");
        }
        KoperCommands.register("elpe", root -> root.then(Commands.literal("elpe")
            .then(Commands.literal("stats").executes(ElpeMod::stats))
            .then(Commands.literal("spawn")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 5_000_000))
                    .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "count")))))
            .then(Commands.literal("rope")
                .then(Commands.argument("links", IntegerArgumentType.integer(2, 4096))
                    .executes(c -> rope(c, IntegerArgumentType.getInteger(c, "links")))))
            .then(Commands.literal("cube")
                .then(Commands.argument("size", IntegerArgumentType.integer(2, 24))
                    .executes(c -> cube(c, IntegerArgumentType.getInteger(c, "size")))))
            .then(Commands.literal("blast")
                .then(Commands.argument("radius", FloatArgumentType.floatArg(1f, 256f))
                    .executes(c -> blast(c, FloatArgumentType.getFloat(c, "radius")))))
            .then(Commands.literal("rubble").executes(ElpeMod::rubble))
            .then(Commands.literal("crumble")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 12))
                    .executes(c -> crumble(c, IntegerArgumentType.getInteger(c, "radius")))))
            .then(Commands.literal("show").executes(ElpeMod::show))
            .then(Commands.literal("clear").executes(ElpeMod::clear))));
    }

    private static ElpeKoperWorld worldOrYell(CommandContext<CommandSourceStack> c) {
        ElpeKoperWorld w = ElpeLevelBoss.of(c.getSource().getLevel());
        if (w == null) c.getSource().sendFailure(Component.literal("elpe native is not loaded, check the log"));
        return w;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        ElpeKoperWorld w = ElpeLevelBoss.peekExisting(c.getSource().getLevel());
        if (w == null) { say(c, "[elpe] no world here yet"); return 0; }
        var s = w.stats();
        say(c, "[elpe] live " + s.live() + " | awake " + s.awake() + " | asleep " + s.asleep() + " | frozen " + s.frozen()
            + " | joints " + s.joints() + " | sections " + s.sections() + " | step " + (s.stepMicros() / 1000f) + " ms"
            + " | rubble " + ElpeRubble.live(c.getSource().getLevel()) + " flying, " + ElpeRubble.landed + " landed, "
            + ElpeRubble.dropped + " dropped");
        return 1;
    }

    // a column of loose balls above you. the fun one is 1000000
    private static int spawn(CommandContext<CommandSourceStack> c, int count) {
        ElpeKoperWorld w = worldOrYell(c);
        if (w == null) return 0;
        Vec3 at = c.getSource().getPosition();
        int side = (int) Math.ceil(Math.cbrt(count));
        float[] xyz = new float[count * 3];
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int x = i % side, z = (i / side) % side, y = i / (side * side);
            xyz[i * 3] = (float) (at.x - side * 0.3 + x * 0.6 + rng.nextFloat() * 0.05);
            xyz[i * 3 + 1] = (float) (at.y + 4 + y * 0.6);
            xyz[i * 3 + 2] = (float) (at.z - side * 0.3 + z * 0.6 + rng.nextFloat() * 0.05);
        }
        int[] ids = w.spawnMany(xyz, 0.25f, 1f, 0);
        say(c, "[elpe] spawned " + ids.length + " points");
        return ids.length;
    }

    private static int rope(CommandContext<CommandSourceStack> c, int links) {
        ElpeKoperWorld w = worldOrYell(c);
        if (w == null) return 0;
        Vec3 at = c.getSource().getPosition().add(0, 6, 0);
        int prev = ElpeKoperWorld.NONE;
        // every rope gets its own group so its links dont shove each other apart
        int group = 0x10000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 20);
        for (int i = 0; i < links; i++) {
            int id = w.spawn(at.x + i * 0.4, at.y, at.z, 0.15f, 1f, group);
            if (prev == ElpeKoperWorld.NONE) w.pin(id, at.x, at.y, at.z, 0f, 1f, 0f);
            else w.joint(prev, id, 0f, 0.4f, 1f, 0f);
            prev = id;
        }
        say(c, "[elpe] rope with " + links + " links hanging above you");
        return 1;
    }

    // a jelly cube: lattice points welded to every neighbour incl. diagonals. no rotation code anywhere,
    // it still tumbles because the points do
    private static int cube(CommandContext<CommandSourceStack> c, int size) {
        ElpeKoperWorld w = worldOrYell(c);
        if (w == null) return 0;
        Vec3 at = c.getSource().getPosition().add(0, 5, 0);
        float gap = 0.5f;
        int group = 0x20000000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24);
        int[][][] ids = new int[size][size][size];
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++)
            ids[x][y][z] = w.spawn(at.x + x * gap, at.y + y * gap, at.z + z * gap, 0.25f, 1f, group);
        int joints = 0;
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++)
            for (int dx = 0; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && (dy < 0 || (dy == 0 && dz <= 0))) continue;
                int nx = x + dx, ny = y + dy, nz = z + dz;
                if (nx >= size || ny < 0 || ny >= size || nz < 0 || nz >= size) continue;
                if (w.weld(ids[x][y][z], ids[nx][ny][nz], 0.9f, 1.5f) != ElpeKoperWorld.NONE) joints++;
            }
        say(c, "[elpe] cube " + size + "³ = " + size * size * size + " points, " + joints + " joints");
        return 1;
    }

    private static int blast(CommandContext<CommandSourceStack> c, float radius) {
        ElpeKoperWorld w = worldOrYell(c);
        if (w == null) return 0;
        Vec3 at = c.getSource().getPosition();
        int n = w.blast(at.x, at.y, at.z, radius, 25f);
        say(c, "[elpe] blasted " + n + " points");
        return n;
    }

    private static int rubble(CommandContext<CommandSourceStack> c) {
        ElpeRubble.explosions = !ElpeRubble.explosions;
        say(c, "[elpe] explosion rubble " + (ElpeRubble.explosions ? "on — go blow something up" : "off"));
        return 1;
    }

    // rips a ball of ground out under your feet and throws it up. mostly for looking at rubble without tnt
    private static int crumble(CommandContext<CommandSourceStack> c, int radius) {
        ServerLevel level = c.getSource().getLevel();
        if (worldOrYell(c) == null) return 0;
        Vec3 at = c.getSource().getPosition();
        net.minecraft.core.BlockPos center = net.minecraft.core.BlockPos.containing(at.x, at.y - radius - 1, at.z);
        var rng = java.util.concurrent.ThreadLocalRandom.current();
        int n = 0;
        for (var p : net.minecraft.core.BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (p.distSqr(center) > radius * radius) continue;
            Vec3 out = Vec3.atCenterOf(p).subtract(Vec3.atCenterOf(center));
            Vec3 v = out.scale(1.5).add((rng.nextFloat() - 0.5f) * 4f, 12f + rng.nextFloat() * 8f, (rng.nextFloat() - 0.5f) * 4f);
            if (ElpeRubble.flingBlock(level, p.immutable(), v)) n++;
        }
        say(c, "[elpe] " + n + " blocks airborne, they turn back into blocks when they settle");
        return n;
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        boolean on = SHOWING.add(level);
        if (!on) SHOWING.remove(level);
        say(c, "[elpe] debug particles " + (on ? "on" : "off"));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        ElpeLevelBoss.drop(c.getSource().getLevel());
        say(c, "[elpe] world dropped");
        return 1;
    }

    // cheap viz for testing: end rods at awake points near players, capped so the network survives
    static void debugParticles(ServerLevel level, ElpeKoperWorld w) {
        if (!SHOWING.contains(level) || level.players().isEmpty()) return;
        MemorySegment pos = w.positions();
        MemorySegment awake = w.awakeIds();
        long n = awake.byteSize() / 4;
        int budget = 1500;
        for (ServerPlayer p : level.players()) {
            for (long k = 0; k < n && budget > 0; k++) {
                int id = awake.getAtIndex(ValueLayout.JAVA_INT, k);
                float x = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L);
                float y = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L + 1);
                float z = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L + 2);
                if (p.distanceToSqr(x, y, z) > 48 * 48) continue;
                level.sendParticles(p, ParticleTypes.END_ROD, true, false, x, y, z, 1, 0, 0, 0, 0);
                budget--;
            }
        }
    }
}
