package com.koper.koper_lib.physics.koperer;

// what khysics needs from a physics engine. rapier does all of it, elpe does the cheap half.
// everything khysics actually touches goes through here, so swapping the engine swaps nothing else —
// render, sync, seats, grids all keep reading KoperPhys like they always did.
//
// the defaults below are the "engine doesn't do this" answers. an engine that can't do aero just
// doesn't override it and nothing crashes, it simply has no aero.
public interface PhysKoperer {

    // the engine for a world nobody owns. every call is a no-op, so a stale or bogus world id
    // dies here instead of being handed to a native as a pointer
    PhysKoperer NOBODY = new PhysKoperer() {
        @Override public String name() { return "none"; }
        @Override public boolean isLoaded() { return false; }
        @Override public long createWorld() { return 0L; }
        @Override public void destroyWorld(long w) {}
        @Override public void heartbeat(long w) {}
        @Override public void setGravity(long w, float gx, float gy, float gz) {}
        @Override public long spawnKontraktion(long w, int[] b, float[] m, int l, float x, float y, float z) { return -1L; }
        @Override public long spawnKontraktionOffsets(long w, float[] o, float[] m, int l, float x, float y, float z) { return -1L; }
        @Override public void destroyKontraktion(long w, long k) {}
        @Override public int getAllTransforms(long w, float[] out, int cap) { return 0; }
        @Override public int setTransform(long w, long k, float a, float b, float c, float d, float e, float f, float g) { return -1; }
        @Override public int addBlockAtOffset(long w, long k, float a, float b, float c, float m) { return -1; }
        @Override public int removeBlockAtOffset(long w, long k, int a, int b, int c) { return -1; }
        @Override public void setVelocity(long w, long k, float a, float b, float c) {}
        @Override public void setDamping(long w, long k, float a, float b) {}
        @Override public void setKontraSleepAllowed(long w, long k, boolean a) {}
        @Override public void setParked(long w, long k, boolean p) {}
        @Override public void applyForce(long w, long k, float a, float b, float c) {}
        @Override public void applyImpulse(long w, long k, float a, float b, float c) {}
        @Override public void applyImpulseGroup(long w, long k, float a, float b, float c) {}
        @Override public int wantedSections(long w, int[] out, int max) { return 0; }
        @Override public void uploadSection(long w, int a, int b, int c, long[] bits) {}
        @Override public void setTerrainBlock(long w, int a, int b, int c, boolean s) {}
        @Override public long raycast(long w, float a, float b, float c, float d, float e, float f, float g, float[] h) { return -1L; }
        @Override public long createRevoluteJoint(long w, long a, long b, float c, float d, float e, float f, float g, float h, float i, float j, float k) { return -1L; }
        @Override public long createPrismaticJoint(long w, long a, long b, float c, float d, float e, float f, float g, float h, float i, float j, float k) { return -1L; }
        @Override public void jointSetMotor(long w, long j, float a, float b) {}
        @Override public void jointSetLimits(long w, long j, float a, float b) {}
        @Override public void jointClearLimits(long w, long j) {}
        @Override public void destroyJoint(long w, long j) {}
        @Override public float[] jointState(long w, long j) { return null; }
    };

    // per entry in the transform buffer: [id_lo, id_hi, cx,cy,cz, qx,qy,qz,qw, flags]
    int TRANSFORM_STRIDE = 10;

    String name();

    boolean isLoaded();

    // ── world ────────────────────────────────────────────────────────────────

    long createWorld();

    void destroyWorld(long worldId);

    void heartbeat(long worldId);

    void setGravity(long worldId, float gx, float gy, float gz);

    // [total, sections, fluid+aero, solver, joint projection, post] ms. null = engine keeps no numbers
    default float[] profile(long worldId) { return null; }

    // ── kontraktions ─────────────────────────────────────────────────────────

    long spawnKontraktion(long worldId, int[] blockCoords, float[] masses, int lightCount,
                          float spawnX, float spawnY, float spawnZ);

    long spawnKontraktionOffsets(long worldId, float[] offsets, float[] masses,
                                 int lightCount, float spawnX, float spawnY, float spawnZ);

    void destroyKontraktion(long worldId, long kontraId);

    // [id_lo, id_hi, cx,cy,cz, qx,qy,qz,qw, flags] per body. flags bit 0 = aligned to the world grid
    int getAllTransforms(long worldId, float[] outBuf, int capacity);

    int setTransform(long worldId, long kontraId,
                     float px, float py, float pz,
                     float qx, float qy, float qz, float qw);

    int addBlockAtOffset(long worldId, long kontraId, float ox, float oy, float oz, float mass);

    int removeBlockAtOffset(long worldId, long kontraId, int lx, int ly, int lz);

    void setVelocity(long worldId, long kontraId, float vx, float vy, float vz);

    void setDamping(long worldId, long kontraId, float linear, float angular);

    void setKontraSleepAllowed(long worldId, long kontraId, boolean allowed);

    void setParked(long worldId, long kontraId, boolean parked);

    // ── pushing things around ────────────────────────────────────────────────

    void applyForce(long worldId, long kontraId, float fx, float fy, float fz);

    void applyImpulse(long worldId, long kontraId, float ix, float iy, float iz);

    // carries everything jointed to this body, so a machine flies as one piece
    void applyImpulseGroup(long worldId, long kontraId, float ix, float iy, float iz);

    // torque and off-centre impulses only mean something to an engine that spins bodies
    default void applyTorque(long worldId, long kontraId, float tx, float ty, float tz) {}

    default void applyImpulseAtPoint(long worldId, long kontraId,
                                     float ix, float iy, float iz,
                                     float px, float py, float pz) {
        applyImpulse(worldId, kontraId, ix, iy, iz);
    }

    default void selfRight(long worldId, long kontraId) {}

    // ── terrain ──────────────────────────────────────────────────────────────

    int wantedSections(long worldId, int[] outBuf, int maxSections);

    void uploadSection(long worldId, int sx, int sy, int sz, long[] bits);

    void setTerrainBlock(long worldId, int x, int y, int z, boolean solid);

    long raycast(long worldId, float ox, float oy, float oz,
                 float dx, float dy, float dz, float maxDist, float[] hitOut);

    // ── joints ───────────────────────────────────────────────────────────────

    long createRevoluteJoint(long worldId, long kontraA, long kontraB,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float axisX, float axisY, float axisZ);

    long createPrismaticJoint(long worldId, long kontraA, long kontraB,
                              float ax, float ay, float az,
                              float bx, float by, float bz,
                              float axisX, float axisY, float axisZ);

    void jointSetMotor(long worldId, long jointId, float targetVel, float maxForce);

    void jointSetLimits(long worldId, long jointId, float min, float max);

    void jointClearLimits(long worldId, long jointId);

    void destroyJoint(long worldId, long jointId);

    // out[0] = angle rad / slide blocks, out[1] = velocity along the axis. null until it exists
    float[] jointState(long worldId, long jointId);

    // position motors are a spring on the axis — an engine without them just does nothing
    default void jointSetMotorPosition(long worldId, long jointId, float targetPos,
                                       float stiffness, float damping, float maxForce) {}

    default void jointSetMotorPositionForceBased(long worldId, long jointId, float targetPos,
                                                 float stiffness, float damping, float maxForce) {
        jointSetMotorPosition(worldId, jointId, targetPos, stiffness, damping, maxForce);
    }

    // ── the flashy half. skip all of it and you still have a working vehicle ──

    // 15 floats per block: friction, restitution and the rest of the material table
    default void setBlockMaterials(long worldId, long kontraId, float[] materials) {}

    // [index, centre xyz, half xyz, quat xyzw] per compound child — non-cube collision shapes
    default void setBlockShapes(long worldId, long kontraId, float[] shapes) {}

    // [ox,oy,oz, nx,ny,nz, area, cd, cl, buoy] per aero-tagged block
    default void setAero(long worldId, long kontraId, float[] surfaces) {}

    default void setAeroMode(long worldId, long kontraId, int mode) {}

    default void setWind(long worldId, float wx, float wy, float wz) {}

    default void uploadSectionFluids(long worldId, int sx, int sy, int sz, long[] bits) {}

    default void setFluidBlock(long worldId, int x, int y, int z, boolean fluid) {}

    default void setBuoyancy(long worldId, long kontraId, float[] entries) {}

    default void setWaterDensity(long worldId, float density) {}

    // bodies that fell apart into disconnected islands this tick. null = engine never splits anything
    default int[] drainSplits(long worldId, int bufCapacity) { return null; }

    // ── the vehicle toolkit. physics/body/KhysBody is the friendly face of all of this ──

    // force + torque held on the body EVERY physics step until replaced (zeros = let go). an engine
    // that can't hold gets one step's worth, which is all applyForce ever was
    default void setHeldPush(long worldId, long kontraId,
                             float fx, float fy, float fz, float tx, float ty, float tz) {
        applyForce(worldId, kontraId, fx, fy, fz);
        applyTorque(worldId, kontraId, tx, ty, tz);
    }

    // a spin kick. an engine whose bodies don't rotate has nothing to spin
    default void applyAngularImpulse(long worldId, long kontraId, float ix, float iy, float iz) {}

    default void setGravityScale(long worldId, long kontraId, float scale) {}

    // what water pushes up on this body, times this
    default void setBuoyancyScale(long worldId, long kontraId, float scale) {}

    // one block got heavier or lighter in place. ox/oy/oz = its float offset, same key as spawn
    default void setBlockMass(long worldId, long kontraId, float ox, float oy, float oz, float mass) {}

    // hand the body to KhysPushers on the PHYSICS thread every step. false = this engine can't, and
    // KhysPushers falls back to running the pusher on the server tick
    default boolean setPushed(long worldId, long kontraId, boolean pushed) { return false; }

    // latest published KhysBodyState floats (KhysBodyState.LEN), null = engine keeps none
    default float[] bodyState(long worldId, long kontraId) { return null; }
}
