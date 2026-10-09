package com.koper.koper_lib.physics.koperer;

import com.koper.koper_lib.api.core.KoperModuleNative;
import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.physics.body.KhysBodyState;
import com.koper.koper_lib.physics.body.KhysPushers;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

// the real one. rapier living in rust, reached over panama, one dll load and a handle cache.
// this used to BE KoperPhysBridge — the bridge is a facade over the picked engine now, the code
// itself didn't change.
public final class RapierKoperer implements PhysKoperer {

    private static final KoperModuleNative NATIVE = KoperModuleNative.load(
        "khysics", "koperlib_khysics_engine", RapierKoperer.class);

    // descriptors
    private static final FunctionDescriptor
        RET_LONG                        = FunctionDescriptor.of(JAVA_LONG),
        VOID_LONG                       = FunctionDescriptor.ofVoid(JAVA_LONG),
        INT_LONG_PTR_INT                = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT),
        VOID_LONG_LONG_FFF              = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        VOID_LONG_LONG                  = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        VOID_LONG_LONG_F                = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT),
        INT_LONG_LONG_FFFFFFF           = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG,
                                            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                                            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        INT_LONG_LONG_FFF               = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG,
                                            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT),
        INT_LONG_LONG_FFFF              = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG,
                                            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);

    @Override
    public String name() { return "rapier"; }


    @Override
    public boolean isLoaded() { return NATIVE.loaded(); }
    @Override public boolean syncCommands(long worldId) {
        var h = fn("koper_khysics_sync_commands", FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT));
        if (h==null || worldId==0) return false;
        try { return (int)h.invoke(worldId,250)==1; }
        catch (Throwable e) {err("sync_commands",e);return false;}
    }
    @Override public boolean configureAtmosphere(long worldId,float drag) {
        var h=fn("koper_khysics_set_atmosphere",FunctionDescriptor.ofVoid(JAVA_LONG,JAVA_FLOAT));
        if(h==null || worldId==0) return false;
        try {h.invoke(worldId,drag);return true;} catch(Throwable e) {err("set_atmosphere",e);return false;}
    }
    @Override public boolean configureFlight(long worldId, boolean enabled, float maxSpeed, int minSectionY, int maxSectionY) {
        var h = fn("koper_khysics_set_flight_policy", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_INT));
        if (h == null || worldId == 0) return false;
        try { h.invoke(worldId, enabled ? 1 : 0, maxSpeed, minSectionY, maxSectionY); return true; }
        catch (Throwable e) { err("set_flight_policy", e); return false; }
    }
    @Override public boolean setAngularVelocity(long worldId, long bodyId, float x, float y, float z) {
        var h = fn("koper_khysics_set_angular_velocity", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return false;
        try { h.invoke(worldId,bodyId,x,y,z); return true; }
        catch (Throwable e) { err("set_angular_velocity",e); return false; }
    }

    // ── world lifecycle ───────────────────────────────────────────────────────

    @Override
    public long createWorld() {
        var h = fn("koper_khysics_create_world", RET_LONG);
        if (h == null) return 0L;
        try { return (long) h.invoke(); }
        catch (Throwable e) { err("create_world", e); return 0L; }
    }

    @Override
    public void destroyWorld(long worldId) {
        var h = fn("koper_khysics_destroy_world", VOID_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId); }
        catch (Throwable e) { err("destroy_world", e); }
    }

    // ── kontraktion lifecycle ─────────────────────────────────────────────────

    // blockCoords: flat [x,y,z per block], masses: one float per block, lightCount: light tag count
    // spawnX/Y/Z: Java-computed world centroid — body spawns here from tick 1, no setTransform needed
    @Override
    public long spawnKontraktion(long worldId, int[] blockCoords, float[] masses, int lightCount,
                                        float spawnX, float spawnY, float spawnZ) {
        var h = fn("koper_khysics_spawn_kontraktion",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return 0L;
        int count = blockCoords.length / 3;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment blockSeg = a.allocateFrom(JAVA_INT, blockCoords);
            MemorySegment massSeg  = a.allocateFrom(JAVA_FLOAT, masses);
            return (long) h.invoke(worldId, blockSeg, massSeg, count, lightCount, spawnX, spawnY, spawnZ);
        } catch (Throwable e) { err("spawn_kontraktion", e); return 0L; }
    }

    // restore path — spawn straight from float local offsets, terrain comes from the section cache
    @Override
    public long spawnKontraktionOffsets(long worldId, float[] offsets, float[] masses,
                                               int lightCount, float spawnX, float spawnY, float spawnZ) {
        var h = fn("koper_khysics_spawn_kontraktion_offsets",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return 0L;
        int blockCount = offsets.length / 3;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment offSeg  = a.allocateFrom(JAVA_FLOAT, offsets);
            MemorySegment massSeg = a.allocateFrom(JAVA_FLOAT, masses);
            return (long) h.invoke(worldId, offSeg, massSeg, blockCount, lightCount, spawnX, spawnY, spawnZ);
        } catch (Throwable e) { err("spawn_kontraktion_offsets", e); return 0L; }
    }

    // per entry in outBuf: [id_lo_i32, id_hi_i32, cx, cy, cz, qx, qy, qz, qw, flags] = 10 floats
    // flags bit 0 = aligned (resting on the world grid). returns number of kontraktions written
    @Override
    public int getAllTransforms(long worldId, float[] outBuf, int capacity) {
        var h = fn("koper_khysics_get_all_transforms", INT_LONG_PTR_INT);
        if (h == null || worldId == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(JAVA_FLOAT, capacity * (long) TRANSFORM_STRIDE);
            int count = (int) h.invoke(worldId, buf, capacity);
            if (count > 0) {
                int floats = Math.min(count * TRANSFORM_STRIDE, outBuf.length);
                for (int i = 0; i < floats; i++) outBuf[i] = buf.getAtIndex(JAVA_FLOAT, i);
            }
            return count;
        } catch (Throwable e) { err("get_all_transforms", e); return 0; }
    }

    // [total, sections, fluid+aero, solver, joint projection, post] in ms, averaged over 60 steps
    @Override
    public float[] profile(long worldId) {
        var h = fn("koper_khysics_profile", INT_LONG_PTR_INT);
        if (h == null || worldId == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(JAVA_FLOAT, 6);
            int count = (int) h.invoke(worldId, buf, 6);
            if (count < 6) return null;
            float[] out = new float[6];
            for (int i = 0; i < 6; i++) out[i] = buf.getAtIndex(JAVA_FLOAT, i);
            return out;
        } catch (Throwable e) { err("profile", e); return null; }
    }

    @Override
    public void applyForce(long worldId, long kontraId, float fx, float fy, float fz) {
        var h = fn("koper_khysics_apply_force", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, fx, fy, fz); }
        catch (Throwable e) { err("apply_force", e); }
    }

    @Override
    public void applyTorque(long worldId, long kontraId, float tx, float ty, float tz) {
        var h = fn("koper_khysics_apply_torque", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, tx, ty, tz); }
        catch (Throwable e) { err("apply_torque", e); }
    }

    @Override
    public void applyImpulse(long worldId, long kontraId, float ix, float iy, float iz) {
        var h = fn("koper_khysics_apply_impulse", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, ix, iy, iz); }
        catch (Throwable e) { err("apply_impulse", e); }
    }

    // carries every body jointed to this one, so a machine flies as one piece instead of shredding
    @Override
    public void applyImpulseGroup(long worldId, long kontraId, float ix, float iy, float iz) {
        var h = fn("koper_khysics_apply_impulse_group", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, ix, iy, iz); }
        catch (Throwable e) { err("apply_impulse_group", e); }
    }

    @Override
    public void applyImpulseAtPoint(long worldId, long kontraId,
                                           float ix, float iy, float iz,
                                           float px, float py, float pz) {
        var h = fn("koper_khysics_apply_impulse_at_point",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, ix, iy, iz, px, py, pz); }
        catch (Throwable e) { err("apply_impulse_at_point", e); }
    }

    @Override
    public void setBlockMaterials(long worldId, long kontraId, float[] materials) {
        var h = fn("koper_khysics_set_block_materials",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0 || materials.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            h.invoke(worldId, kontraId, a.allocateFrom(JAVA_FLOAT, materials), materials.length / 15);
        } catch (Throwable e) { err("set_block_materials", e); }
    }

    // [block index, center xyz, half xyz, quaternion xyzw] per Rapier compound child.
    @Override
    public void setBlockShapes(long worldId, long kontraId, float[] shapes) {
        var h = fn("koper_khysics_set_block_shapes",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0 || shapes.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            h.invoke(worldId, kontraId, a.allocateFrom(JAVA_FLOAT, shapes), shapes.length / 11);
        } catch (Throwable e) { err("set_block_shapes", e); }
    }

    @Override
    public void selfRight(long worldId, long kontraId) {
        var h = fn("koper_khysics_self_right", VOID_LONG_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId); }
        catch (Throwable e) { err("self_right", e); }
    }

    @Override
    public void destroyKontraktion(long worldId, long kontraId) {
        var h = fn("koper_khysics_destroy_kontraktion", VOID_LONG_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId); }
        catch (Throwable e) { err("destroy_kontraktion", e); }
    }

    @Override
    public int setTransform(long worldId, long kontraId,
                                   float px, float py, float pz,
                                   float qx, float qy, float qz, float qw) {
        var h = fn("koper_khysics_set_transform", INT_LONG_LONG_FFFFFFF);
        if (h == null || worldId == 0) return -1;
        try { return (int) h.invoke(worldId, kontraId, px, py, pz, qx, qy, qz, qw); }
        catch (Throwable e) { err("set_transform", e); return -1; }
    }

    @Override
    public int addBlockAtOffset(long worldId, long kontraId,
                                       float ox, float oy, float oz, float mass) {
        var h = fn("koper_khysics_add_block_at_offset", INT_LONG_LONG_FFFF);
        if (h == null || worldId == 0) return -1;
        try { return (int) h.invoke(worldId, kontraId, ox, oy, oz, mass); }
        catch (Throwable e) { err("add_block_at_offset", e); return -1; }
    }

    @Override
    public int removeBlockAtOffset(long worldId, long kontraId, int lx, int ly, int lz) {
        var h = fn("koper_khysics_remove_block_at_offset",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
        if (h == null || worldId == 0) return -1;
        try { return (int) h.invoke(worldId, kontraId, lx, ly, lz); }
        catch (Throwable e) { err("remove_block_at_offset", e); return -1; }
    }

    // sections physics wants but never got — [sx,sy,sz] triples. returns triple count
    @Override
    public int wantedSections(long worldId, int[] outBuf, int maxSections) {
        var h = fn("koper_khysics_wanted_sections", INT_LONG_PTR_INT);
        if (h == null || worldId == 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(JAVA_INT, maxSections * 3L);
            int count = (int) h.invoke(worldId, seg, maxSections);
            int ints = Math.min(count * 3, outBuf.length);
            for (int i = 0; i < ints; i++) outBuf[i] = seg.getAtIndex(JAVA_INT, i);
            return count;
        } catch (Throwable e) { err("wanted_sections", e); return 0; }
    }

    // full 16^3 occupancy of one section — 64 longs, bit idx = (ly*16+lz)*16+lx
    @Override
    public void uploadSection(long worldId, int sx, int sy, int sz, long[] bits) {
        var h = fn("koper_khysics_upload_section",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        if (h == null || worldId == 0 || bits == null || bits.length != 64) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocateFrom(JAVA_LONG, bits);
            h.invoke(worldId, sx, sy, sz, seg);
        } catch (Throwable e) { err("upload_section", e); }
    }

    // one world cell flipped — physics updates the bit if it caches that section
    @Override
    public void setTerrainBlock(long worldId, int x, int y, int z, boolean solid) {
        var h = fn("koper_khysics_set_terrain_block",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, x, y, z, solid ? 1 : 0); }
        catch (Throwable e) { err("set_terrain_block", e); }
    }

    // returns kontraId on hit, -1 on miss — hitOut[3] populated with world hit pos
    @Override
    public long raycast(long worldId, float ox, float oy, float oz,
                               float dx, float dy, float dz, float maxDist, float[] hitOut) {
        var h = fn("koper_khysics_raycast",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
                JAVA_FLOAT, ADDRESS, ADDRESS));
        if (h == null || worldId == 0) return -1L;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment hitSeg = a.allocate(JAVA_FLOAT, 3);
            MemorySegment idSeg  = a.allocate(JAVA_LONG,  1);
            int rc = (int) h.invoke(worldId, ox, oy, oz, dx, dy, dz, maxDist, hitSeg, idSeg);
            if (rc == 1) {
                if (hitOut != null && hitOut.length >= 3) {
                    hitOut[0] = hitSeg.getAtIndex(JAVA_FLOAT, 0);
                    hitOut[1] = hitSeg.getAtIndex(JAVA_FLOAT, 1);
                    hitOut[2] = hitSeg.getAtIndex(JAVA_FLOAT, 2);
                }
                return idSeg.getAtIndex(JAVA_LONG, 0);
            }
            return -1L;
        } catch (Throwable e) { err("raycast", e); return -1L; }
    }

    // buf layout: [parent_id_lo, parent_id_hi, comp_count, (block_count, lx,ly,lz...) per comp]
    // returns int values written, 0 if no splits, -1 on error
    @Override
    public int[] drainSplits(long worldId, int bufCapacity) {
        var h = fn("koper_khysics_drain_splits",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(JAVA_INT, bufCapacity);
            int written = (int) h.invoke(worldId, seg, bufCapacity);
            if (written <= 0) return null;
            int[] out = new int[written];
            for (int i = 0; i < written; i++) out[i] = seg.getAtIndex(JAVA_INT, i);
            return out;
        } catch (Throwable e) { err("drain_splits", e); return null; }
    }

    @Override
    public void setGravity(long worldId, float gx, float gy, float gz) {
        var h = fn("koper_khysics_set_gravity",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, gx, gy, gz); }
        catch (Throwable e) { err("set_gravity", e); }
    }

    // aero surfaces for one kontraktion — one per aero-tagged block, 10 floats each:
    // [ox,oy,oz, nx,ny,nz, area, cd, cl, buoy]. resend on every block change. empty = no aero (clears it).
    @Override
    public void setAero(long worldId, long kontraId, float[] surfaces) {
        var h = fn("koper_khysics_set_aero",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0) return;
        int count = surfaces.length / 10;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = count > 0 ? a.allocateFrom(JAVA_FLOAT, surfaces) : MemorySegment.NULL;
            h.invoke(worldId, kontraId, seg, count);
        } catch (Throwable e) { err("set_aero", e); }
    }

    @Override
    public void setAeroMode(long worldId, long kontraId, int mode) {
        var h = fn("koper_khysics_set_aero_mode",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, mode); }
        catch (Throwable e) { err("set_aero_mode", e); }
    }

    // blocks/s, overwrites the body's velocity outright — flight stick control
    @Override
    public void setVelocity(long worldId, long kontraId, float vx, float vy, float vz) {
        var h = fn("koper_khysics_set_velocity",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, vx, vy, vz); }
        catch (Throwable e) { err("set_velocity", e); }
    }

    @Override
    public void setDamping(long worldId, long kontraId, float linear, float angular) {
        var h = fn("koper_khysics_set_damping",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, linear, angular); }
        catch (Throwable e) { err("set_damping", e); }
    }

    // ping the physics thread so it keeps stepping — call every server tick. no ping (game paused) → it
    // freezes, so contraptions don't drift around while you sit in the pause menu
    @Override
    public void heartbeat(long worldId) {
        var h = fn("koper_khysics_heartbeat", VOID_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId); }
        catch (Throwable e) { err("heartbeat", e); }
    }

    // global air velocity for a physics world — relative wind on every body = its velocity minus this
    @Override
    public void setWind(long worldId, float wx, float wy, float wz) {
        var h = fn("koper_khysics_set_wind",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, wx, wy, wz); }
        catch (Throwable e) { err("set_wind", e); }
    }

    // ── joints — bearing = revolute + motor, piston = prismatic + motor ──────

    private static final FunctionDescriptor JOINT_CREATE_DESC = FunctionDescriptor.of(JAVA_LONG,
        JAVA_LONG, JAVA_LONG, JAVA_LONG,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);

    // anchors in each kontra's local block-offset space, axis shared by both local frames.
    // returns the joint id immediately, the joint itself spawns async on the physics thread
    @Override
    public long createRevoluteJoint(long worldId, long kontraA, long kontraB,
                                           float ax, float ay, float az,
                                           float bx, float by, float bz,
                                           float axisX, float axisY, float axisZ) {
        var h = fn("koper_khysics_create_revolute_joint", JOINT_CREATE_DESC);
        if (h == null || worldId == 0) return -1L;
        try { return (long) h.invoke(worldId, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ); }
        catch (Throwable e) { err("create_revolute_joint", e); return -1L; }
    }

    @Override
    public long createPrismaticJoint(long worldId, long kontraA, long kontraB,
                                            float ax, float ay, float az,
                                            float bx, float by, float bz,
                                            float axisX, float axisY, float axisZ) {
        var h = fn("koper_khysics_create_prismatic_joint", JOINT_CREATE_DESC);
        if (h == null || worldId == 0) return -1L;
        try { return (long) h.invoke(worldId, kontraA, kontraB, ax, ay, az, bx, by, bz, axisX, axisY, axisZ); }
        catch (Throwable e) { err("create_prismatic_joint", e); return -1L; }
    }

    // revolute: rad/s + max torque. prismatic: blocks/s + max force. vel 0 + big force = brake
    @Override
    public void jointSetMotor(long worldId, long jointId, float targetVel, float maxForce) {
        var h = fn("koper_khysics_joint_set_motor",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId, targetVel, maxForce); }
        catch (Throwable e) { err("joint_set_motor", e); }
    }

    @Override
    public void jointSetMotorPosition(long worldId, long jointId, float targetPos,
                                             float stiffness, float damping, float maxForce) {
        var h = fn("koper_khysics_joint_set_motor_position",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId, targetPos, stiffness, damping, maxForce); }
        catch (Throwable e) { err("joint_set_motor_position", e); }
    }

    @Override
    public void jointSetMotorPositionForceBased(long worldId, long jointId, float targetPos,
                                                       float stiffness, float damping, float maxForce) {
        var h = fn("koper_khysics_joint_set_motor_position_force_based",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId, targetPos, stiffness, damping, maxForce); }
        catch (Throwable e) { err("joint_set_motor_position_force_based", e); }
    }

    @Override
    public void jointSetLimits(long worldId, long jointId, float min, float max) {
        var h = fn("koper_khysics_joint_set_limits",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId, min, max); }
        catch (Throwable e) { err("joint_set_limits", e); }
    }

    @Override
    public void jointClearLimits(long worldId, long jointId) {
        var h = fn("koper_khysics_joint_clear_limits", VOID_LONG_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId); }
        catch (Throwable e) { err("joint_clear_limits", e); }
    }

    @Override
    public void destroyJoint(long worldId, long jointId) {
        var h = fn("koper_khysics_destroy_joint", VOID_LONG_LONG);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, jointId); }
        catch (Throwable e) { err("destroy_joint", e); }
    }

    // out[0] = angle rad (revolute) / slide blocks (prismatic), out[1] = velocity along the axis.
    // null until the async create landed — poll again next tick
    @Override
    public float[] jointState(long worldId, long jointId) {
        var h = fn("koper_khysics_joint_state",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
        if (h == null || worldId == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(JAVA_FLOAT, 3);
            int rc = (int) h.invoke(worldId, jointId, seg);
            if (rc != 1) return null;
            return new float[]{ seg.getAtIndex(JAVA_FLOAT, 0), seg.getAtIndex(JAVA_FLOAT, 1), seg.getAtIndex(JAVA_FLOAT, 2) };
        } catch (Throwable e) { err("joint_state", e); return null; }
    }

    // ── water ────────────────────────────────────────────────────────────────

    // fluid occupancy of one section — send RIGHT AFTER uploadSection of the same section (order matters)
    @Override
    public void uploadSectionFluids(long worldId, int sx, int sy, int sz, long[] bits) {
        var h = fn("koper_khysics_upload_section_fluids",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        if (h == null || worldId == 0 || bits == null || bits.length != 64) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocateFrom(JAVA_LONG, bits);
            h.invoke(worldId, sx, sy, sz, seg);
        } catch (Throwable e) { err("upload_section_fluids", e); }
    }

    @Override
    public void setFluidBlock(long worldId, int x, int y, int z, boolean fluid) {
        var h = fn("koper_khysics_set_fluid_block",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, x, y, z, fluid ? 1 : 0); }
        catch (Throwable e) { err("set_fluid_block", e); }
    }

    // per-block water displacement, 4 floats per entry [lx,ly,lz,volume] — resend like aero
    @Override
    public void setBuoyancy(long worldId, long kontraId, float[] entries) {
        var h = fn("koper_khysics_set_buoyancy",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0) return;
        int count = entries.length / 4;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = count > 0 ? a.allocateFrom(JAVA_FLOAT, entries) : MemorySegment.NULL;
            h.invoke(worldId, kontraId, seg, count);
        } catch (Throwable e) { err("set_buoyancy", e); }
    }

    @Override
    public void setWaterDensity(long worldId, float density) {
        var h = fn("koper_khysics_set_water_density",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, density); }
        catch (Throwable e) { err("set_water_density", e); }
    }

    // allowed=true → kontraktion can sleep (unloaded chunks), false → never sleeps (loaded)
    @Override
    public void setKontraSleepAllowed(long worldId, long kontraId, boolean allowed) {
        var h = fn("koper_khysics_set_sleep_allowed",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, allowed ? 1 : 0); }
        catch (Throwable e) { err("set_sleep_allowed", e); }
    }

    @Override
    public void setParked(long worldId, long kontraId, boolean parked) {
        var h = fn("koper_khysics_set_parked",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, parked ? 1 : 0); }
        catch (Throwable e) { err("set_parked", e); }
    }

    // ── the vehicle toolkit ───────────────────────────────────────────────────

    @Override
    public void setHeldPush(long worldId, long kontraId,
                            float fx, float fy, float fz, float tx, float ty, float tz) {
        var h = fn("koper_khysics_set_held_push", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, fx, fy, fz, tx, ty, tz); }
        catch (Throwable e) { err("set_held_push", e); }
    }

    @Override
    public void applyAngularImpulse(long worldId, long kontraId, float ix, float iy, float iz) {
        var h = fn("koper_khysics_apply_angular_impulse", VOID_LONG_LONG_FFF);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, ix, iy, iz); }
        catch (Throwable e) { err("apply_angular_impulse", e); }
    }

    @Override
    public void setGravityScale(long worldId, long kontraId, float scale) {
        var h = fn("koper_khysics_set_gravity_scale", VOID_LONG_LONG_F);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, scale); }
        catch (Throwable e) { err("set_gravity_scale", e); }
    }

    @Override
    public void setBuoyancyScale(long worldId, long kontraId, float scale) {
        var h = fn("koper_khysics_set_buoyancy_scale", VOID_LONG_LONG_F);
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, scale); }
        catch (Throwable e) { err("set_buoyancy_scale", e); }
    }

    @Override
    public void setBlockMass(long worldId, long kontraId, float ox, float oy, float oz, float mass) {
        var h = fn("koper_khysics_set_block_mass", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        if (h == null || worldId == 0) return;
        try { h.invoke(worldId, kontraId, ox, oy, oz, mass); }
        catch (Throwable e) { err("set_block_mass", e); }
    }

    @Override
    public boolean setPushed(long worldId, long kontraId, boolean pushed) {
        if (pushed && !plugPusherIn()) return false;
        var h = fn("koper_khysics_set_pushed", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT));
        if (h == null || worldId == 0) return false;
        try { h.invoke(worldId, kontraId, pushed ? 1 : 0); return true; }
        catch (Throwable e) { err("set_pushed", e); return false; }
    }

    @Override
    public float[] bodyState(long worldId, long kontraId) {
        var h = fn("koper_khysics_body_state", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
        if (h == null || worldId == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(JAVA_FLOAT, KhysBodyState.LEN);
            int n = (int) h.invoke(worldId, kontraId, buf, KhysBodyState.LEN);
            return n < KhysBodyState.LEN ? null : buf.toArray(JAVA_FLOAT);
        } catch (Throwable e) { err("body_state", e); return null; }
    }

    // the pusher upcall stub, made once and handed to rust. Arena.global because rust keeps the
    // pointer forever — a freed stub would be a jump into garbage from the physics thread
    private static volatile boolean pusherPlugged;

    private static synchronized boolean plugPusherIn() {
        if (pusherPlugged) return true;
        var set = fn("koper_khysics_set_pusher", FunctionDescriptor.ofVoid(ADDRESS));
        if (set == null) return false;
        try {
            MethodHandle target = java.lang.invoke.MethodHandles.lookup().findStatic(RapierKoperer.class,
                "pushFromRust", java.lang.invoke.MethodType.methodType(
                    void.class, long.class, MemorySegment.class, MemorySegment.class));
            MemorySegment stub = Linker.nativeLinker().upcallStub(target,
                FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, ADDRESS), Arena.global());
            set.invoke(stub);
            pusherPlugged = true;
            return true;
        } catch (Throwable e) { err("set_pusher", e); return false; }
    }

    private static final long STATE_BYTES = KhysBodyState.LEN * 4L;

    // runs on the rust physics thread. an exception escaping an upcall kills the JVM outright, so
    // nothing gets out of here — KhysPushers already swallows, this is the second net
    private static void pushFromRust(long kontraId, MemorySegment state, MemorySegment out) {
        try {
            float[] s = state.reinterpret(STATE_BYTES).toArray(JAVA_FLOAT);
            float[] o = new float[6];
            KhysPushers.runNative(kontraId, s, o);
            MemorySegment dst = out.reinterpret(24);
            for (int i = 0; i < 6; i++) dst.setAtIndex(JAVA_FLOAT, i, o[i]);
        } catch (Throwable ignored) {}
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private static MethodHandle fn(String name, FunctionDescriptor desc) {
        return NATIVE.function(name, desc);
    }

    private static void err(String fn, Throwable e) {
        KoperCore.LOGGER.error("[Khysics] {} blew up: {}", fn, e.getMessage());
    }
}
