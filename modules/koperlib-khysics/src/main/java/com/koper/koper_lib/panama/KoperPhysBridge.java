package com.koper.koper_lib.panama;

import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.physics.koperer.PhysKoperer;
import com.koper.koper_lib.physics.koperer.KopererPicker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// khysics' door to whatever physics engine is running. used to be the rapier panama code itself;
// that moved to physics/koperer/RapierKoperer and this just forwards, so every call site in
// koperlib and in the mods keeps working and doesn't care which engine answered.
//
// the engine is bound to the WORLD, not global. a rapier world id is an index into a rapier map,
// an elpe one is a raw pointer — hand one to the other engine and you get a segfault, which is
// exactly what flipping the backend mid game used to do. KopererPicker only decides what NEW
// worlds get; everything after that follows the world.
public final class KoperPhysBridge {

    public static final int TRANSFORM_STRIDE = PhysKoperer.TRANSFORM_STRIDE;

    private static final Map<Long, PhysKoperer> OWNER = new ConcurrentHashMap<>();

    private KoperPhysBridge() {}

    public static PhysKoperer koperer() { return KopererPicker.now(); }

    // which engine owns this world. unknown id = something we never made, so touch nothing
    public static PhysKoperer of(long worldId) {
        PhysKoperer k = OWNER.get(worldId);
        return k != null ? k : PhysKoperer.NOBODY;
    }

    private static long born(PhysKoperer engine) {
        long id = engine.createWorld();
        if (id != 0) {
            OWNER.put(id, engine);
            // say it out loud. "why does my car drive like garbage" is usually "you are on the
            // other engine and did not notice"
            KoperCore.LOGGER.info("[Khysics] world {} opened on {}", id, engine.name());
        } else {
            KoperCore.LOGGER.error("[Khysics] {} could not open a world", engine.name());
        }
        return id;
    }

    // omni only: the last commands each body got, with the wall time, so a body that flew off can say
    // what was done to it just before. off in normal play, costs one boolean check
    public static final boolean TRAIL = Boolean.getBoolean("omni");
    private static final java.util.Map<Long, java.util.ArrayDeque<String>> KOPER_TRAILS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void koperTrail(long kontraId, String what) {
        var trail = KOPER_TRAILS.computeIfAbsent(kontraId, k -> new java.util.ArrayDeque<>());
        synchronized (trail) {
            trail.addLast((System.currentTimeMillis() % 100_000) + "ms " + what);
            if (trail.size() > 12) trail.removeFirst();
        }
    }

    public static java.util.List<String> trailOf(long kontraId) {
        var trail = KOPER_TRAILS.get(kontraId);
        if (trail == null) return java.util.List.of();
        synchronized (trail) { return java.util.List.copyOf(trail); }
    }

    public static void forgetTrail(long kontraId) { KOPER_TRAILS.remove(kontraId); }

    public static boolean isLoaded() { return KopererPicker.now().isLoaded(); }

    // convenience overload, mass 1
    public static int addBlockAtOffset(long worldId, long kontraId, float ox, float oy, float oz) {
        return addBlockAtOffset(worldId, kontraId, ox, oy, oz, 1f);
    }

    // ── world lifecycle ───────────────────────────────────────────────────────
    public static long createWorld() { return born(KopererPicker.now()); }

    public static void destroyWorld(long worldId) { OWNER.remove(worldId); of(worldId).destroyWorld(worldId); }

    // blockCoords: flat [x,y,z per block], masses: one float per block, lightCount: light tag count
    // spawnX/Y/Z: Java-computed world centroid — body spawns here from tick 1, no setTransform needed
    public static long spawnKontraktion(long worldId, int[] blockCoords, float[] masses, int lightCount, float spawnX, float spawnY, float spawnZ) { return of(worldId).spawnKontraktion(worldId, blockCoords, masses, lightCount, spawnX, spawnY, spawnZ); }

    // restore path — spawn straight from float local offsets, terrain comes from the section cache
    public static long spawnKontraktionOffsets(long worldId, float[] offsets, float[] masses, int lightCount, float spawnX, float spawnY, float spawnZ) {
        long id = of(worldId).spawnKontraktionOffsets(worldId, offsets, masses, lightCount, spawnX, spawnY, spawnZ);
        if (TRAIL) koperTrail(id, "spawn " + offsets.length / 3 + " blocks at " + spawnX + "," + spawnY + "," + spawnZ);
        return id;
    }

    public static int getAllTransforms(long worldId, float[] outBuf, int capacity) { return of(worldId).getAllTransforms(worldId, outBuf, capacity); }

    // [total, sections, fluid+aero, solver, joint projection, post] in ms, averaged over 60 steps
    public static float[] profile(long worldId) { return of(worldId).profile(worldId); }

    public static void applyForce(long worldId, long kontraId, float fx, float fy, float fz) { of(worldId).applyForce(worldId, kontraId, fx, fy, fz); }

    public static void applyTorque(long worldId, long kontraId, float tx, float ty, float tz) { of(worldId).applyTorque(worldId, kontraId, tx, ty, tz); }

    public static void applyImpulse(long worldId, long kontraId, float ix, float iy, float iz) { if (TRAIL) koperTrail(kontraId, "applyImpulse" + java.util.Arrays.toString(new Object[]{ix, iy, iz})); of(worldId).applyImpulse(worldId, kontraId, ix, iy, iz); }

    // carries every body jointed to this one, so a machine flies as one piece instead of shredding
    public static void applyImpulseGroup(long worldId, long kontraId, float ix, float iy, float iz) { if (TRAIL) koperTrail(kontraId, "applyImpulseGroup" + java.util.Arrays.toString(new Object[]{ix, iy, iz})); of(worldId).applyImpulseGroup(worldId, kontraId, ix, iy, iz); }

    public static void applyImpulseAtPoint(long worldId, long kontraId, float ix, float iy, float iz, float px, float py, float pz) { if (TRAIL) koperTrail(kontraId, "applyImpulseAtPoint" + java.util.Arrays.toString(new Object[]{ix, iy, iz})); of(worldId).applyImpulseAtPoint(worldId, kontraId, ix, iy, iz, px, py, pz); }

    public static void setBlockMaterials(long worldId, long kontraId, float[] materials) { of(worldId).setBlockMaterials(worldId, kontraId, materials); }

    // [block index, center xyz, half xyz, quaternion xyzw] per Rapier compound child.
    public static void setBlockShapes(long worldId, long kontraId, float[] shapes) { of(worldId).setBlockShapes(worldId, kontraId, shapes); }

    public static void selfRight(long worldId, long kontraId) { if (TRAIL) koperTrail(kontraId, "selfRight"); of(worldId).selfRight(worldId, kontraId); }

    public static void destroyKontraktion(long worldId, long kontraId) { if (TRAIL) koperTrail(kontraId, "destroyKontraktion"); of(worldId).destroyKontraktion(worldId, kontraId); }

    public static int setTransform(long worldId, long kontraId, float px, float py, float pz, float qx, float qy, float qz, float qw) { if (TRAIL) koperTrail(kontraId, "setTransform" + java.util.Arrays.toString(new Object[]{px, py, pz})); return of(worldId).setTransform(worldId, kontraId, px, py, pz, qx, qy, qz, qw); }

    public static int addBlockAtOffset(long worldId, long kontraId, float ox, float oy, float oz, float mass) { if (TRAIL) koperTrail(kontraId, "addBlockAtOffset" + java.util.Arrays.toString(new Object[]{ox, oy, oz})); return of(worldId).addBlockAtOffset(worldId, kontraId, ox, oy, oz, mass); }

    public static int removeBlockAtOffset(long worldId, long kontraId, int lx, int ly, int lz) { if (TRAIL) koperTrail(kontraId, "removeBlockAtOffset" + java.util.Arrays.toString(new Object[]{lx, ly, lz})); return of(worldId).removeBlockAtOffset(worldId, kontraId, lx, ly, lz); }

    // sections physics wants but never got — [sx,sy,sz] triples. returns triple count
    public static int wantedSections(long worldId, int[] outBuf, int maxSections) { return of(worldId).wantedSections(worldId, outBuf, maxSections); }

    // full 16^3 occupancy of one section — 64 longs, bit idx = (ly*16+lz)*16+lx
    public static void uploadSection(long worldId, int sx, int sy, int sz, long[] bits) { of(worldId).uploadSection(worldId, sx, sy, sz, bits); }

    // one world cell flipped — physics updates the bit if it caches that section
    public static void setTerrainBlock(long worldId, int x, int y, int z, boolean solid) { of(worldId).setTerrainBlock(worldId, x, y, z, solid); }

    // returns kontraId on hit, -1 on miss — hitOut[3] populated with world hit pos
    public static long raycast(long worldId, float ox, float oy, float oz, float dx, float dy, float dz, float maxDist, float[] hitOut) { return of(worldId).raycast(worldId, ox, oy, oz, dx, dy, dz, maxDist, hitOut); }

    // buf layout: [parent_id_lo, parent_id_hi, comp_count, (block_count, lx,ly,lz...) per comp]
    // returns int values written, 0 if no splits, -1 on error
    public static int[] drainSplits(long worldId, int bufCapacity) { return of(worldId).drainSplits(worldId, bufCapacity); }

    public static void setGravity(long worldId, float gx, float gy, float gz) { of(worldId).setGravity(worldId, gx, gy, gz); }

    // aero surfaces for one kontraktion — one per aero-tagged block, 10 floats each:
    // [ox,oy,oz, nx,ny,nz, area, cd, cl, buoy]. resend on every block change. empty = no aero (clears it).
    public static void setAero(long worldId, long kontraId, float[] surfaces) { of(worldId).setAero(worldId, kontraId, surfaces); }

    public static void setAeroMode(long worldId, long kontraId, int mode) { of(worldId).setAeroMode(worldId, kontraId, mode); }

    // blocks/s, overwrites the body's velocity outright — flight stick control
    public static void setVelocity(long worldId, long kontraId, float vx, float vy, float vz) { if (TRAIL) koperTrail(kontraId, "setVelocity" + java.util.Arrays.toString(new Object[]{vx, vy, vz})); of(worldId).setVelocity(worldId, kontraId, vx, vy, vz); }

    public static void setDamping(long worldId, long kontraId, float linear, float angular) { of(worldId).setDamping(worldId, kontraId, linear, angular); }
    public static boolean configureAtmosphere(long worldId,float drag) {return of(worldId).configureAtmosphere(worldId,drag);}
    public static boolean configureFlight(long worldId, boolean enabled, float maxSpeed, int minSectionY, int maxSectionY) { return of(worldId).configureFlight(worldId,enabled,maxSpeed,minSectionY,maxSectionY); }
    public static boolean setAngularVelocity(long worldId, long bodyId, float x, float y, float z) { return of(worldId).setAngularVelocity(worldId,bodyId,x,y,z); }
    public static boolean syncCommands(long worldId) { return of(worldId).syncCommands(worldId); }

    // ping the physics thread so it keeps stepping — call every server tick. no ping (game paused) → it
    // freezes, so contraptions don't drift around while you sit in the pause menu
    public static void heartbeat(long worldId) { of(worldId).heartbeat(worldId); }

    // global air velocity for a physics world — relative wind on every body = its velocity minus this
    public static void setWind(long worldId, float wx, float wy, float wz) { of(worldId).setWind(worldId, wx, wy, wz); }

    // anchors in each kontra's local block-offset space, axis shared by both local frames.
    // returns the joint id immediately, the joint itself spawns async on the physics thread
    public static long createRevoluteJoint(long worldId, long kontraA, long kontraB, float ax, float ay, float az, float bx, float by, float bz, float axisX, float axisY, float axisZ) { if (TRAIL) { String koperAnchors = " a@" + ax + "," + ay + "," + az + " b@" + bx + "," + by + "," + bz; koperTrail(kontraA, "createRevoluteJoint to " + kontraB + koperAnchors); koperTrail(kontraB, "createRevoluteJoint to " + kontraA + koperAnchors); } return of(worldId).createRevoluteJoint(worldId, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ); }

    public static long createPrismaticJoint(long worldId, long kontraA, long kontraB, float ax, float ay, float az, float bx, float by, float bz, float axisX, float axisY, float axisZ) { if (TRAIL) { String koperAnchors = " a@" + ax + "," + ay + "," + az + " b@" + bx + "," + by + "," + bz; koperTrail(kontraA, "createPrismaticJoint to " + kontraB + koperAnchors); koperTrail(kontraB, "createPrismaticJoint to " + kontraA + koperAnchors); } return of(worldId).createPrismaticJoint(worldId, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ); }

    // revolute: rad/s + max torque. prismatic: blocks/s + max force. vel 0 + big force = brake
    public static void jointSetMotor(long worldId, long jointId, float targetVel, float maxForce) {
        if (TRAIL) koperMotorTrail(jointId, "motor " + targetVel + " rad/s force " + maxForce);
        of(worldId).jointSetMotor(worldId, jointId, targetVel, maxForce);
    }

    // motors get re-sent every tick by vehicles, only a change goes into the trail or it drowns the rest
    private static final java.util.Map<Long, String> KOPER_LAST_MOTOR = new java.util.concurrent.ConcurrentHashMap<>();

    private static void koperMotorTrail(long jointId, String what) {
        if (what.equals(KOPER_LAST_MOTOR.put(jointId, what))) return;
        var spec = com.koper.koper_lib.physics.KoperPhys.jointSpecs().get(jointId);
        if (spec == null) return;
        koperTrail(spec.a(), "joint " + jointId + " " + what);
        if (spec.b() != 0L) koperTrail(spec.b(), "joint " + jointId + " " + what);
    }

    public static void jointSetMotorPosition(long worldId, long jointId, float targetPos, float stiffness, float damping, float maxForce) { of(worldId).jointSetMotorPosition(worldId, jointId, targetPos, stiffness, damping, maxForce); }

    public static void jointSetMotorPositionForceBased(long worldId, long jointId, float targetPos, float stiffness, float damping, float maxForce) { of(worldId).jointSetMotorPositionForceBased(worldId, jointId, targetPos, stiffness, damping, maxForce); }

    public static void jointSetLimits(long worldId, long jointId, float min, float max) { of(worldId).jointSetLimits(worldId, jointId, min, max); }

    public static void jointClearLimits(long worldId, long jointId) { of(worldId).jointClearLimits(worldId, jointId); }

    public static void destroyJoint(long worldId, long jointId) { of(worldId).destroyJoint(worldId, jointId); }

    // out[0] = angle rad (revolute) / slide blocks (prismatic), out[1] = velocity along the axis.
    // null until the async create landed — poll again next tick
    public static float[] jointState(long worldId, long jointId) { return of(worldId).jointState(worldId, jointId); }

    // fluid occupancy of one section — send RIGHT AFTER uploadSection of the same section (order matters)
    public static void uploadSectionFluids(long worldId, int sx, int sy, int sz, long[] bits) { of(worldId).uploadSectionFluids(worldId, sx, sy, sz, bits); }

    public static void setFluidBlock(long worldId, int x, int y, int z, boolean fluid) { of(worldId).setFluidBlock(worldId, x, y, z, fluid); }

    // per-block water displacement, 4 floats per entry [lx,ly,lz,volume] — resend like aero
    public static void setBuoyancy(long worldId, long kontraId, float[] entries) { of(worldId).setBuoyancy(worldId, kontraId, entries); }

    public static void setWaterDensity(long worldId, float density) { of(worldId).setWaterDensity(worldId, density); }

    // allowed=true → kontraktion can sleep (unloaded chunks), false → never sleeps (loaded)
    public static void setKontraSleepAllowed(long worldId, long kontraId, boolean allowed) { of(worldId).setKontraSleepAllowed(worldId, kontraId, allowed); }

    public static void setParked(long worldId, long kontraId, boolean parked) { if (TRAIL) koperTrail(kontraId, "setParked" + java.util.Arrays.toString(new Object[]{parked})); of(worldId).setParked(worldId, kontraId, parked); }

    // ── the vehicle toolkit, see physics/body/KhysBody ───────────────────────

    public static void setHeldPush(long worldId, long kontraId, float fx, float fy, float fz, float tx, float ty, float tz) { of(worldId).setHeldPush(worldId, kontraId, fx, fy, fz, tx, ty, tz); }

    public static void applyAngularImpulse(long worldId, long kontraId, float ix, float iy, float iz) { if (TRAIL) koperTrail(kontraId, "applyAngularImpulse" + java.util.Arrays.toString(new Object[]{ix, iy, iz})); of(worldId).applyAngularImpulse(worldId, kontraId, ix, iy, iz); }

    public static void setGravityScale(long worldId, long kontraId, float scale) { if (TRAIL) koperTrail(kontraId, "setGravityScale" + java.util.Arrays.toString(new Object[]{scale})); of(worldId).setGravityScale(worldId, kontraId, scale); }

    public static void setBuoyancyScale(long worldId, long kontraId, float scale) { of(worldId).setBuoyancyScale(worldId, kontraId, scale); }

    public static void setBlockMass(long worldId, long kontraId, float ox, float oy, float oz, float mass) { of(worldId).setBlockMass(worldId, kontraId, ox, oy, oz, mass); }

    // false = the engine can't run pushers on its own thread
    public static boolean setPushed(long worldId, long kontraId, boolean pushed) { return of(worldId).setPushed(worldId, kontraId, pushed); }

    public static float[] bodyState(long worldId, long kontraId) { return of(worldId).bodyState(worldId, kontraId); }
}
