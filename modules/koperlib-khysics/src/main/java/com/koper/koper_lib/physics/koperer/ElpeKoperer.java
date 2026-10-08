package com.koper.koper_lib.physics.koperer;

import com.koper.koper_lib.api.core.KoperModuleNative;
import com.koper.koper_lib.coremod.KoperCore;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

// khysics running on elpe instead of rapier. talks to the elpe .so straight over panama, so this
// module never imports koperlib-elpe — no elpe installed just means the native isn't there and
// KopererPicker keeps you on rapier.
//
// what you give up: aero, wind, water, buoyancy, per block materials and shapes, splitting, and
// rotation. a kloc slides, it does not turn. wheels on a hinge turn. that's the deal.
public final class ElpeKoperer implements PhysKoperer {

    private static final KoperModuleNative NATIVE = KoperModuleNative.load(
        "elpe", "koperlib_elpe_engine", ElpeKoperer.class);

    // mc tick. khysics pings heartbeat once a tick and that ping is what steps elpe
    private static final float TICK = 0.05f;
    private static final int SUBSTEPS = 2;

    private static final FunctionDescriptor
        WORLD_NEW  = FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_FLOAT),
        VOID_ADDR  = FunctionDescriptor.ofVoid(ADDRESS),
        STEP       = FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_INT),
        CONFIGURE  = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT),
        SPAWN      = FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        FREE       = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG),
        TRANSFORMS = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        SET_TRANS  = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        SET_VEL    = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        IMPULSE    = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT),
        KLOC_BLOCK = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        FLAGS      = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT),
        JOINT      = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        UNJOINT    = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG),
        MOTOR      = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT),
        LIMITS     = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT),
        SPRING     = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        JOINT_ST   = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS),
        RAYCAST    = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, ADDRESS),
        SECTION    = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS),
        SET_BLOCK  = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        REQUESTS   = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);

    @Override
    public String name() { return "elpe"; }

    @Override
    public boolean isLoaded() { return NATIVE.loaded(); }

    private static MethodHandle fn(String name, FunctionDescriptor d) { return NATIVE.function(name, d); }

    private static void err(String fn, Throwable e) {
        KoperCore.LOGGER.error("[Elpe] {} blew up: {}", fn, e.getMessage());
    }

    // khysics passes worlds around as a long. ours is a pointer, so it rides in the same long
    private static MemorySegment ptr(long worldId) { return MemorySegment.ofAddress(worldId); }

    // ── world ────────────────────────────────────────────────────────────────

    @Override
    public long createWorld() {
        var h = fn("koper_elpe_world_new", WORLD_NEW);
        if (h == null) return 0L;
        try {
            MemorySegment w = (MemorySegment) h.invoke(1 << 16, 1.0f);
            long id = w.address();
            if (id != 0) setGravity(id, 0f, -28f, 0f);
            return id;
        } catch (Throwable e) { err("world_new", e); return 0L; }
    }

    @Override
    public void destroyWorld(long worldId) {
        var h = fn("koper_elpe_world_free", VOID_ADDR);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId)); }
        catch (Throwable e) { err("world_free", e); }
    }

    // khysics pings every server tick; rapier runs its own thread, we just step right here
    @Override
    public void heartbeat(long worldId) {
        var h = fn("koper_elpe_step", STEP);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), TICK, SUBSTEPS); }
        catch (Throwable e) { err("step", e); }
    }

    @Override
    public void setGravity(long worldId, float gx, float gy, float gz) {
        var h = fn("koper_elpe_configure", CONFIGURE);
        if (h == null || worldId == 0) return;
        // cfg layout, 15 floats. same defaults elpe ships with, only gravity moves
        float[] cfg = { gx, gy, gz, 0.999f, 0.4f, 0.08f, 20f, 1.5f, 2f, 0.45f, 1f, Float.NaN, -2048f, 0f, 4f };
        try (Arena a = Arena.ofConfined()) {
            h.invoke(ptr(worldId), a.allocateFrom(JAVA_FLOAT, cfg), cfg.length);
        } catch (Throwable e) { err("configure", e); }
    }

    // ── kontraktions ─────────────────────────────────────────────────────────

    @Override
    public long spawnKontraktion(long worldId, int[] blockCoords, float[] masses, int lightCount,
                                 float spawnX, float spawnY, float spawnZ) {
        float[] offsets = new float[blockCoords.length];
        for (int i = 0; i < blockCoords.length; i++) offsets[i] = blockCoords[i];
        return spawnKontraktionOffsets(worldId, offsets, masses, lightCount, spawnX, spawnY, spawnZ);
    }

    @Override
    public long spawnKontraktionOffsets(long worldId, float[] offsets, float[] masses,
                                        int lightCount, float spawnX, float spawnY, float spawnZ) {
        var h = fn("koper_elpe_kloc_spawn", SPAWN);
        if (h == null || worldId == 0 || offsets.length < 3) return -1L;
        int count = offsets.length / 3;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment off = a.allocateFrom(JAVA_FLOAT, offsets);
            MemorySegment ms = masses != null && masses.length > 0
                ? a.allocateFrom(JAVA_FLOAT, masses) : MemorySegment.NULL;
            return (long) h.invoke(ptr(worldId), off, ms, count, spawnX, spawnY, spawnZ);
        } catch (Throwable e) { err("kloc_spawn", e); return -1L; }
    }

    @Override
    public void destroyKontraktion(long worldId, long kontraId) {
        var h = fn("koper_elpe_kloc_free", FREE);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), kontraId); }
        catch (Throwable e) { err("kloc_free", e); }
    }

    @Override
    public int getAllTransforms(long worldId, float[] outBuf, int capacity) {
        var h = fn("koper_elpe_kloc_transforms", TRANSFORMS);
        if (h == null || worldId == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(JAVA_FLOAT, capacity * (long) TRANSFORM_STRIDE);
            int n = (int) h.invoke(ptr(worldId), buf, capacity);
            if (n > 0) {
                int floats = Math.min(n * TRANSFORM_STRIDE, outBuf.length);
                for (int i = 0; i < floats; i++) outBuf[i] = buf.getAtIndex(JAVA_FLOAT, i);
            }
            return n;
        } catch (Throwable e) { err("kloc_transforms", e); return 0; }
    }

    @Override
    public int setTransform(long worldId, long kontraId,
                            float px, float py, float pz,
                            float qx, float qy, float qz, float qw) {
        var h = fn("koper_elpe_kloc_set_transform", SET_TRANS);
        if (h == null || worldId == 0) return -1;
        // the quaternion goes nowhere. elpe holds no orientation and the native drops it too
        try { return (int) h.invoke(ptr(worldId), kontraId, px, py, pz, qx, qy, qz, qw); }
        catch (Throwable e) { err("kloc_set_transform", e); return -1; }
    }

    @Override
    public int addBlockAtOffset(long worldId, long kontraId, float ox, float oy, float oz, float mass) {
        return block(worldId, kontraId, Math.round(ox), Math.round(oy), Math.round(oz), 1);
    }

    @Override
    public int removeBlockAtOffset(long worldId, long kontraId, int lx, int ly, int lz) {
        return block(worldId, kontraId, lx, ly, lz, 0);
    }

    private int block(long worldId, long kontraId, int lx, int ly, int lz, int add) {
        var h = fn("koper_elpe_kloc_block", KLOC_BLOCK);
        if (h == null || worldId == 0) return -1;
        try { return (int) h.invoke(ptr(worldId), kontraId, lx, ly, lz, add); }
        catch (Throwable e) { err("kloc_block", e); return -1; }
    }

    @Override
    public void setVelocity(long worldId, long kontraId, float vx, float vy, float vz) {
        var h = fn("koper_elpe_kloc_set_velocity", SET_VEL);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), kontraId, vx, vy, vz); }
        catch (Throwable e) { err("kloc_set_velocity", e); }
    }

    // angular damping means nothing here, linear rides along
    @Override
    public void setDamping(long worldId, long kontraId, float linear, float angular) {
        flags(worldId, kontraId, 1, 0, Math.max(0f, 1f - linear * TICK));
    }

    @Override
    public void setKontraSleepAllowed(long worldId, long kontraId, boolean allowed) {
        flags(worldId, kontraId, allowed ? 1 : 0, 0, 0f);
    }

    @Override
    public void setParked(long worldId, long kontraId, boolean parked) {
        flags(worldId, kontraId, 1, parked ? 1 : 0, 0f);
    }

    private void flags(long worldId, long kontraId, int sleepOk, int parked, float damping) {
        var h = fn("koper_elpe_kloc_flags", FLAGS);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), kontraId, sleepOk, parked, damping); }
        catch (Throwable e) { err("kloc_flags", e); }
    }

    // ── shoving ──────────────────────────────────────────────────────────────

    @Override
    public void applyImpulse(long worldId, long kontraId, float ix, float iy, float iz) {
        impulse(worldId, kontraId, ix, iy, iz, 0);
    }

    @Override
    public void applyImpulseGroup(long worldId, long kontraId, float ix, float iy, float iz) {
        impulse(worldId, kontraId, ix, iy, iz, 1);
    }

    // no real force integration, a tick's worth of impulse is close enough for a shove
    @Override
    public void applyForce(long worldId, long kontraId, float fx, float fy, float fz) {
        impulse(worldId, kontraId, fx * TICK, fy * TICK, fz * TICK, 0);
    }

    private void impulse(long worldId, long kontraId, float ix, float iy, float iz, int group) {
        var h = fn("koper_elpe_kloc_impulse", IMPULSE);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), kontraId, ix, iy, iz, group); }
        catch (Throwable e) { err("kloc_impulse", e); }
    }

    // ── terrain ──────────────────────────────────────────────────────────────

    @Override
    public int wantedSections(long worldId, int[] outBuf, int maxSections) {
        var h = fn("koper_elpe_requests", REQUESTS);
        if (h == null || worldId == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(JAVA_INT, maxSections * 3L);
            int n = (int) h.invoke(ptr(worldId), seg, maxSections);
            int ints = Math.min(n * 3, outBuf.length);
            for (int i = 0; i < ints; i++) outBuf[i] = seg.getAtIndex(JAVA_INT, i);
            return n;
        } catch (Throwable e) { err("requests", e); return 0; }
    }

    @Override
    public void uploadSection(long worldId, int sx, int sy, int sz, long[] bits) {
        var h = fn("koper_elpe_section", SECTION);
        if (h == null || worldId == 0 || bits == null || bits.length != 64) return;
        try (Arena a = Arena.ofConfined()) {
            h.invoke(ptr(worldId), sx, sy, sz, a.allocateFrom(JAVA_LONG, bits));
        } catch (Throwable e) { err("section", e); }
    }

    @Override
    public void setTerrainBlock(long worldId, int x, int y, int z, boolean solid) {
        var h = fn("koper_elpe_block", SET_BLOCK);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), x, y, z, solid ? 1 : 0); }
        catch (Throwable e) { err("block", e); }
    }

    @Override
    public long raycast(long worldId, float ox, float oy, float oz,
                        float dx, float dy, float dz, float maxDist, float[] hitOut) {
        var h = fn("koper_elpe_kloc_raycast", RAYCAST);
        if (h == null || worldId == 0) return -1L;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hit = a.allocate(JAVA_FLOAT, 3);
            MemorySegment id = a.allocate(JAVA_LONG, 1);
            int rc = (int) h.invoke(ptr(worldId), ox, oy, oz, dx, dy, dz, maxDist, hit, id);
            if (rc != 1) return -1L;
            if (hitOut != null && hitOut.length >= 3)
                for (int i = 0; i < 3; i++) hitOut[i] = hit.getAtIndex(JAVA_FLOAT, i);
            return id.getAtIndex(JAVA_LONG, 0);
        } catch (Throwable e) { err("kloc_raycast", e); return -1L; }
    }

    // ── joints ───────────────────────────────────────────────────────────────

    @Override
    public long createRevoluteJoint(long worldId, long kontraA, long kontraB,
                                    float ax, float ay, float az,
                                    float bx, float by, float bz,
                                    float axisX, float axisY, float axisZ) {
        return joint(worldId, 0, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ);
    }

    @Override
    public long createPrismaticJoint(long worldId, long kontraA, long kontraB,
                                     float ax, float ay, float az,
                                     float bx, float by, float bz,
                                     float axisX, float axisY, float axisZ) {
        return joint(worldId, 1, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ);
    }

    private long joint(long worldId, int kind, long a, long b,
                       float ax, float ay, float az, float bx, float by, float bz,
                       float axisX, float axisY, float axisZ) {
        var h = fn("koper_elpe_kloc_joint", JOINT);
        if (h == null || worldId == 0) return -1L;
        try {
            long id = (long) h.invoke(ptr(worldId), kind, a, b, ax, ay, az, bx, by, bz, axisX, axisY, axisZ);
            return id == -1L ? -1L : id;
        } catch (Throwable e) { err("kloc_joint", e); return -1L; }
    }

    @Override
    public void jointSetMotor(long worldId, long jointId, float targetVel, float maxForce) {
        var h = fn("koper_elpe_kloc_motor", MOTOR);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), jointId, targetVel, maxForce); }
        catch (Throwable e) { err("kloc_motor", e); }
    }

    @Override
    public void jointSetLimits(long worldId, long jointId, float min, float max) {
        limits(worldId, jointId, min, max, 1);
    }

    @Override
    public void jointClearLimits(long worldId, long jointId) {
        limits(worldId, jointId, 0f, 0f, 0);
    }

    private void limits(long worldId, long jointId, float min, float max, int on) {
        var h = fn("koper_elpe_kloc_limits", LIMITS);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), jointId, min, max, on); }
        catch (Throwable e) { err("kloc_limits", e); }
    }

    @Override
    public void destroyJoint(long worldId, long jointId) {
        var h = fn("koper_elpe_kloc_unjoint", UNJOINT);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), jointId); }
        catch (Throwable e) { err("kloc_unjoint", e); }
    }

    @Override
    public float[] jointState(long worldId, long jointId) {
        var h = fn("koper_elpe_kloc_joint_state", JOINT_ST);
        if (h == null || worldId == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(JAVA_FLOAT, 2);
            if ((int) h.invoke(ptr(worldId), jointId, seg) != 1) return null;
            return new float[]{ seg.getAtIndex(JAVA_FLOAT, 0), seg.getAtIndex(JAVA_FLOAT, 1) };
        } catch (Throwable e) { err("kloc_joint_state", e); return null; }
    }

    // a khysics position motor IS a spring, so hand it straight to the slider spring.
    // this is what suspension drives — SuspensionManager calls the force based one with the
    // profile's stiffness/damping and a rest of 0
    @Override
    public void jointSetMotorPosition(long worldId, long jointId, float targetPos,
                                      float stiffness, float damping, float maxForce) {
        var h = fn("koper_elpe_kloc_spring", SPRING);
        if (h == null || worldId == 0) return;
        try { h.invoke(ptr(worldId), jointId, targetPos, stiffness, damping, maxForce); }
        catch (Throwable e) { err("kloc_spring", e); }
    }
}
