package com.koper.koper_lib.elpe;

import com.koper.koper_lib.elpe.mixin.ElpeBlockDisplayPoker;
import com.koper.koper_lib.elpe.mixin.ElpeDisplayPoker;
import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

// blocks as physical rubble. flies as an elpe point with a vanilla block display on top (no client mod needed),
// and the moment it falls asleep it turns back into a real block. a settled pile costs literally nothing
public final class ElpeRubble {
    public static final String TAG = "koper_elpe_rubble";
    // cube of side 1 pretending to be a ball. a bit under 0.5 so piles dont wedge between blocks
    static final float RADIUS = 0.45f;
    static final int GROUP = 0;

    public static boolean explosions = false;
    // share of the blocks an explosion eats that turn into rubble instead of vanishing
    public static float explosionShare = 0.35f;
    public static int maxLive = 3000;
    // give up after 30s and just place it wherever it is
    public static int maxAgeTicks = 600;
    // rubble snaps to the block grid anyway, so it doesnt need elpe's fussy sleep. under 1 block/s for half a second = landed.
    // without this a crater full of rubble keeps nudging itself awake forever
    public static float landSpeed = 1f;
    public static int landTicks = 10;

    private static final class KoperRubbleBit {
        final BlockState state;
        final Display.BlockDisplay display;
        final Vector3f spinAxis;
        final float spinSpeed;
        final long born;
        float spin;
        int slow;

        KoperRubbleBit(BlockState state, Display.BlockDisplay display, Vector3f spinAxis, float spinSpeed, long born) {
            this.state = state; this.display = display; this.spinAxis = spinAxis; this.spinSpeed = spinSpeed; this.born = born;
        }
    }

    // lifetime counters, /koperlib elpe stats shows them
    public static long landed, dropped, lost;

    private static final Map<ServerLevel, Int2ObjectOpenHashMap<KoperRubbleBit>> LIVE = new IdentityHashMap<>();

    private ElpeRubble() {}

    public static String whyAwake(ServerLevel level, ElpeKoperWorld w) {
        var bits = LIVE.get(level);
        if (bits == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (int id : bits.keySet()) {
            var p = w.peek(id);
            if (p != null) sb.append(String.format("[%.2f %.2f %.2f v=%.2f,%.2f,%.2f s%d] ", p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.state()));
        }
        return sb.toString();
    }

    public static int live(ServerLevel level) {
        var m = LIVE.get(level);
        return m == null ? 0 : m.size();
    }

    // throw one block. the world block is NOT touched here — caller removes it first if it was real
    public static boolean fling(ServerLevel level, Vec3 at, BlockState state, Vec3 velocity) {
        if (state.isAir() || state.hasBlockEntity()) return false;
        var bits = LIVE.computeIfAbsent(level, l -> new Int2ObjectOpenHashMap<>());
        if (bits.size() >= maxLive) return false;
        ElpeKoperWorld w = ElpeLevelBoss.of(level);
        if (w == null) return false;
        int id = w.spawn(at.x, at.y, at.z, RADIUS, 1f, GROUP);
        if (id == ElpeKoperWorld.NONE) return false;
        w.push(id, (float) velocity.x, (float) velocity.y, (float) velocity.z);

        Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
        if (d == null) { w.despawn(id); return false; }
        ((ElpeBlockDisplayPoker) d).koperSetBlockState(state);
        ((ElpeDisplayPoker) d).koperSetPosRotInterpolation(2);
        d.addTag(TAG);
        d.snapTo(at.x, at.y, at.z);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector3f axis = new Vector3f(rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f).normalize();
        var bit = new KoperRubbleBit(state, d, axis, 0.15f + rng.nextFloat() * 0.35f, level.getGameTime());
        spinTo(bit, 0f, 0);
        level.addFreshEntity(d);
        bits.put(id, bit);
        return true;
    }

    // rip a real block out of the world and throw it
    public static boolean flingBlock(ServerLevel level, BlockPos pos, Vec3 velocity) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0) return false;
        if (live(level) >= maxLive) return false;
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        return fling(level, Vec3.atCenterOf(pos), state, velocity);
    }

    // called from the explosion mixin with the positions vanilla is about to blow up.
    // returns the ones vanilla should still handle (the rest became rubble)
    public static List<BlockPos> explosion(ServerLevel level, Vec3 center, float radius, List<BlockPos> doomed) {
        if (!explosions || doomed.isEmpty() || !ElpeKoperWorld.available()) return doomed;
        List<BlockPos> leftovers = new ArrayList<>(doomed.size());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float kick = 6f + radius * 3f;
        for (BlockPos pos : doomed) {
            if (rng.nextFloat() >= explosionShare) { leftovers.add(pos); continue; }
            Vec3 c = Vec3.atCenterOf(pos);
            Vec3 d = c.subtract(center);
            double len = Math.max(0.5, d.length());
            double falloff = Math.max(0.2, 1.0 - len / (radius * 2.0));
            Vec3 v = d.scale(kick * falloff / len).add(
                (rng.nextFloat() - 0.5f) * 3f, 4f + rng.nextFloat() * 4f, (rng.nextFloat() - 0.5f) * 3f);
            if (!flingBlock(level, pos, v)) leftovers.add(pos);
        }
        return leftovers;
    }

    public static void tick(ServerLevel level, ElpeKoperWorld w) {
        long now = level.getGameTime();
        var bits = LIVE.get(level);
        if (bits == null || bits.isEmpty()) return;
        MemorySegment pos = w.positions();
        MemorySegment states = w.states();
        List<Integer> settle = null;
        for (var e : bits.int2ObjectEntrySet()) {
            int id = e.getIntKey();
            KoperRubbleBit bit = e.getValue();
            byte st = states.get(ValueLayout.JAVA_BYTE, id);
            float x = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L);
            float y = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L + 1);
            float z = pos.getAtIndex(ValueLayout.JAVA_FLOAT, id * 3L + 2);
            boolean old = now - bit.born > maxAgeTicks;
            if (st == ElpeKoperWorld.DEAD || st == ElpeKoperWorld.ASLEEP || (old && st != ElpeKoperWorld.FROZEN)
                || bit.display.isRemoved()) {
                if (settle == null) settle = new ArrayList<>();
                settle.add(id);
                continue;
            }
            if (st != ElpeKoperWorld.AWAKE) continue;
            double dx = x - bit.display.getX(), dy = y - bit.display.getY(), dz = z - bit.display.getZ();
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            bit.slow = moved < landSpeed * 0.05 ? bit.slow + 1 : 0;
            if (bit.slow >= landTicks) {
                if (settle == null) settle = new ArrayList<>();
                settle.add(id);
                continue;
            }
            if (moved < 1e-3) continue;
            bit.display.setPos(x, y, z);
            // fake tumble: elpe has no rotation, so spin the display by distance travelled. looks right, costs nothing
            if ((now & 1) == 0) spinTo(bit, bit.spin + (float) moved * bit.spinSpeed * 2f, 2);
        }
        if (settle != null) for (int id : settle) settleOne(level, w, bits, id);
    }

    private static void spinTo(KoperRubbleBit bit, float angle, int lerpTicks) {
        bit.spin = angle;
        Quaternionf q = new Quaternionf().fromAxisAngleRad(bit.spinAxis, angle);
        // rotate around the block centre: the 0.5 corner offset gets rotated too
        Vector3f t = q.transform(new Vector3f(-0.5f, -0.5f, -0.5f));
        ElpeDisplayPoker p = (ElpeDisplayPoker) bit.display;
        p.koperSetTransformation(new Transformation(t, q, new Vector3f(1f), new Quaternionf()));
        p.koperSetTransformationInterpolation(lerpTicks);
        p.koperSetTransformationDelay(0);
    }

    private static void settleOne(ServerLevel level, ElpeKoperWorld w, Int2ObjectOpenHashMap<KoperRubbleBit> bits, int id) {
        KoperRubbleBit bit = bits.remove(id);
        if (bit == null) return;
        var peek = w.peek(id);
        w.despawn(id);
        bit.display.discard();
        // null = elpe killed it below the world, nothing to put down
        if (peek == null) { lost++; return; }
        placeOrDrop(level, BlockPos.containing(peek.x(), peek.y(), peek.z()), bit.state);
    }

    private static void placeOrDrop(ServerLevel level, BlockPos p, BlockState state) {
        if (!level.isLoaded(p)) { lost++; return; }
        for (BlockPos tryAt : new BlockPos[] {p, p.above()}) {
            if (level.getBlockState(tryAt).canBeReplaced()) {
                level.setBlock(tryAt, state, 3);
                landed++;
                return;
            }
        }
        Block.dropResources(state, level, p);
        dropped++;
    }

    // server stopping: put everything down where it is, so no block gets lost to a restart
    public static void settleAll() {
        for (ServerLevel level : new ArrayList<>(LIVE.keySet())) settleLevel(level);
    }

    public static void settleLevel(ServerLevel level) {
        var bits = LIVE.remove(level);
        if (bits == null) return;
        ElpeKoperWorld w = ElpeLevelBoss.peekExisting(level);
        for (int id : bits.keySet().toIntArray()) {
            if (w != null) settleOne(level, w, bits, id);
            else bits.remove(id).display.discard();
        }
    }
}
