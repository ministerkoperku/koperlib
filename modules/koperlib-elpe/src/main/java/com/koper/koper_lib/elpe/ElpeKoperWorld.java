package com.koper.koper_lib.elpe;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.koper.koper_lib.elpe.ElpeNativeKoperer.*;

// one native elpe world. ids are plain ints (u32 on the rust side), NONE means "nope".
// not thread safe — call it from the thread that ticks it, the server thread for ElpeLevelBoss worlds
public final class ElpeKoperWorld implements AutoCloseable {
    public static final int NONE = -1;
    public static final int DEAD = 0, AWAKE = 1, ASLEEP = 2, FROZEN = 3;

    public record PointPeek(float x, float y, float z, float vx, float vy, float vz, int state) {}
    public record Stats(int live, int awake, int asleep, int frozen, int joints, int sections, int stepMicros, int snapped) {}

    private final Arena arena = Arena.ofShared();
    private final MemorySegment scratch = arena.allocate(4096L * 3 * 4, 8);
    private final MemorySegment sectionBits = arena.allocate(64 * 8, 8);
    private MemorySegment ptr;

    public ElpeKoperWorld(int capacity, float maxRadius) {
        if (!ElpeNativeKoperer.ready()) throw new IllegalStateException("elpe native is not loaded");
        try {
            ptr = (MemorySegment) WORLD_NEW.invokeExact(capacity, maxRadius);
        } catch (Throwable t) {
            throw new IllegalStateException("elpe world_new blew up", t);
        }
        if (ptr.equals(MemorySegment.NULL)) throw new IllegalStateException("elpe world_new returned null");
    }

    public static boolean available() {
        return ElpeNativeKoperer.ready();
    }

    public void tune(ElpeTuning t) {
        float[] f = t.packed();
        MemorySegment.copy(f, 0, scratch, ValueLayout.JAVA_FLOAT, 0, f.length);
        try { CONFIGURE.invokeExact(ptr, scratch, f.length); } catch (Throwable e) { throw koperOops(e); }
    }

    public void step(float dt, int substeps) {
        try { STEP.invokeExact(ptr, dt, substeps); } catch (Throwable e) { throw koperOops(e); }
    }

    public int spawn(double x, double y, double z, float radius, float invMass, int group) {
        try {
            return (int) SPAWN.invokeExact(ptr, (float) x, (float) y, (float) z, radius, invMass, group);
        } catch (Throwable e) { throw koperOops(e); }
    }

    // xyz packed, returns ids (NONE where a spawn was refused)
    public int[] spawnMany(float[] xyz, float radius, float invMass, int group) {
        return spawnMany(xyz, radius, invMass, group, false);
    }

    // asleep = restoring a save, they stay put until something pokes them
    public int[] spawnMany(float[] xyz, float radius, float invMass, int group, boolean asleep) {
        int n = xyz.length / 3;
        int[] ids = new int[n];
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment src = tmp.allocateFrom(ValueLayout.JAVA_FLOAT, xyz);
            MemorySegment out = tmp.allocate(ValueLayout.JAVA_INT, Math.max(1, n));
            int ignored = (int) SPAWN_BULK.invokeExact(ptr, src, n, radius, invMass, group, asleep ? 1 : 0, out);
            MemorySegment.copy(out, ValueLayout.JAVA_INT, 0, ids, 0, n);
        } catch (Throwable e) { throw koperOops(e); }
        return ids;
    }

    public void despawn(int id) {
        try { DESPAWN.invokeExact(ptr, id); } catch (Throwable e) { throw koperOops(e); }
    }

    public void teleport(int id, double x, double y, double z, boolean keepVelocity) {
        try { SET_POS.invokeExact(ptr, id, (float) x, (float) y, (float) z, keepVelocity ? 1 : 0); } catch (Throwable e) { throw koperOops(e); }
    }

    // blocks per second
    public void push(int id, float vx, float vy, float vz) {
        try { ADD_VELOCITY.invokeExact(ptr, id, vx, vy, vz); } catch (Throwable e) { throw koperOops(e); }
    }

    public PointPeek peek(int id) {
        try {
            int ok = (int) GET.invokeExact(ptr, id, scratch);
            if (ok == 0) return null;
        } catch (Throwable e) { throw koperOops(e); }
        float[] o = scratch.asSlice(0, 7 * 4).toArray(ValueLayout.JAVA_FLOAT);
        return new PointPeek(o[0], o[1], o[2], o[3], o[4], o[5], (int) o[6]);
    }

    public void wake(int id) {
        try { WAKE.invokeExact(ptr, id); } catch (Throwable e) { throw koperOops(e); }
    }

    // min..max allowed distance. min==max stick, min=0 rope, stiffness<1 spring, snap<=0 unbreakable
    public int joint(int a, int b, float min, float max, float stiffness, float snap) {
        try {
            return (int) JOINT.invokeExact(ptr, a, b, min, max, stiffness, snap, 0f, 0f, 0f);
        } catch (Throwable e) { throw koperOops(e); }
    }

    public int pin(int a, double x, double y, double z, float maxDistance, float stiffness, float snap) {
        try {
            return (int) JOINT.invokeExact(ptr, a, NONE, 0f, maxDistance, stiffness, snap, (float) x, (float) y, (float) z);
        } catch (Throwable e) { throw koperOops(e); }
    }

    // stick at whatever distance they are right now
    public int weld(int a, int b, float stiffness, float snap) {
        try { return (int) JOINT_HERE.invokeExact(ptr, a, b, stiffness, snap); } catch (Throwable e) { throw koperOops(e); }
    }

    public void unjoint(int joint) {
        try { UNJOINT.invokeExact(ptr, joint); } catch (Throwable e) { throw koperOops(e); }
    }

    public boolean jointAlive(int joint) {
        try { return (int) JOINT_ALIVE.invokeExact(ptr, joint) != 0; } catch (Throwable e) { throw koperOops(e); }
    }

    // bits: 64 longs, index (y<<8)|(z<<4)|x. null = all air
    public void section(int sx, int sy, int sz, long[] bits) {
        try {
            if (bits == null) {
                SECTION.invokeExact(ptr, sx, sy, sz, MemorySegment.NULL);
            } else {
                MemorySegment.copy(bits, 0, sectionBits, ValueLayout.JAVA_LONG, 0, 64);
                SECTION.invokeExact(ptr, sx, sy, sz, sectionBits);
            }
        } catch (Throwable e) { throw koperOops(e); }
    }

    public void forgetSection(int sx, int sy, int sz) {
        try { FORGET_SECTION.invokeExact(ptr, sx, sy, sz); } catch (Throwable e) { throw koperOops(e); }
    }

    public boolean block(int x, int y, int z, boolean solid) {
        try { return (int) BLOCK.invokeExact(ptr, x, y, z, solid ? 1 : 0) != 0; } catch (Throwable e) { throw koperOops(e); }
    }

    // sections the engine is waiting for, sx sy sz triples
    public int[] requests() {
        try {
            int n = (int) REQUESTS.invokeExact(ptr, scratch, 4096);
            return n == 0 ? new int[0] : scratch.asSlice(0, n * 12L).toArray(ValueLayout.JAVA_INT);
        } catch (Throwable e) { throw koperOops(e); }
    }

    public int[] querySphere(double x, double y, double z, float r, int max) {
        max = Math.min(max, 4096 * 3);
        try {
            int n = (int) QUERY_SPHERE.invokeExact(ptr, (float) x, (float) y, (float) z, r, scratch, max);
            return scratch.asSlice(0, n * 4L).toArray(ValueLayout.JAVA_INT);
        } catch (Throwable e) { throw koperOops(e); }
    }

    public int wakeSphere(double x, double y, double z, float r) {
        try { return (int) WAKE_SPHERE.invokeExact(ptr, (float) x, (float) y, (float) z, r); } catch (Throwable e) { throw koperOops(e); }
    }

    public int blast(double x, double y, double z, float r, float speed) {
        try { return (int) BLAST.invokeExact(ptr, (float) x, (float) y, (float) z, r, speed); } catch (Throwable e) { throw koperOops(e); }
    }

    // zero copy, xyz floats per id up to highWater(). only valid until the next spawn, grab it fresh every tick
    public MemorySegment positions() {
        try {
            MemorySegment p = (MemorySegment) POSITIONS.invokeExact(ptr);
            return p.reinterpret(highWater() * 12L);
        } catch (Throwable e) { throw koperOops(e); }
    }

    public MemorySegment states() {
        try {
            MemorySegment p = (MemorySegment) STATES.invokeExact(ptr);
            return p.reinterpret(highWater());
        } catch (Throwable e) { throw koperOops(e); }
    }

    // ids that moved last tick. this is what you sync to clients, the rest didnt budge
    public MemorySegment awakeIds() {
        try {
            MemorySegment p = (MemorySegment) AWAKE_IDS.invokeExact(ptr);
            return p.reinterpret(stats().awake() * 4L);
        } catch (Throwable e) { throw koperOops(e); }
    }

    public int highWater() {
        try { return (int) HIGH_WATER.invokeExact(ptr); } catch (Throwable e) { throw koperOops(e); }
    }

    public Stats stats() {
        try { STATS.invokeExact(ptr, scratch); } catch (Throwable e) { throw koperOops(e); }
        int[] s = scratch.asSlice(0, 32).toArray(ValueLayout.JAVA_INT);
        return new Stats(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]);
    }

    @Override
    public void close() {
        if (ptr == null) return;
        try { WORLD_FREE.invokeExact(ptr); } catch (Throwable e) { throw koperOops(e); }
        ptr = null;
        arena.close();
    }

    private static RuntimeException koperOops(Throwable e) {
        return e instanceof RuntimeException r ? r : new IllegalStateException("elpe native call failed", e);
    }
}
