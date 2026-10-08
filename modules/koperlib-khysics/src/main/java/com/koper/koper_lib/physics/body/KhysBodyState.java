package com.koper.koper_lib.physics.body;

import net.minecraft.core.Direction;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

// one body, frozen at one physics step. everything a vehicle brain wants to read, in one place:
// pose, velocities, mass, centre of mass, inertia, the gravity it actually feels, how wet it is.
//
// a pusher gets a fresh one every 60Hz step on the physics thread. KhysBody.state() hands out the
// last published one on the server thread. the numbers come straight from rapier, so nobody has to
// differentiate poses or re-add block masses by hand anymore.
//
// units are khysics units: blocks, seconds, and mass in weight-book units (default block = 1.0).
//
// hello person reading this: the float layout is BODY_STATE_LEN in engine/koperlib-khysics/src/world.rs.
// if you move one number there, move it here, or the numbers mean something else and nothing crashes
public final class KhysBodyState {

    public static final int LEN = 40;

    private static final int FLAG_SLEEPING = 1, FLAG_ALIGNED = 2, FLAG_PARKED = 4;

    private final long id;
    private final float[] s;

    public KhysBodyState(long id, float[] raw) {
        if (raw == null || raw.length < LEN) throw new IllegalArgumentException("need " + LEN + " floats");
        this.id = id;
        this.s = raw;
    }

    public long id() { return id; }

    // the body origin — what getAllTransforms/CACHED_POS report and what block offsets hang off
    public Vector3d position() { return new Vector3d(s[0], s[1], s[2]); }

    public Quaterniond rotation() { return new Quaterniond(s[3], s[4], s[5], s[6]).normalize(); }

    // velocity of the centre of mass, blocks/s
    public Vector3d velocity() { return new Vector3d(s[7], s[8], s[9]); }

    // angular velocity, world frame, rad/s
    public Vector3d omega() { return new Vector3d(s[10], s[11], s[12]); }

    public Vector3d centerOfMass() { return new Vector3d(s[13], s[14], s[15]); }

    // centre of mass in the body frame, relative to the origin
    public Vector3d localCenterOfMass() { return new Vector3d(s[16], s[17], s[18]); }

    public double mass() { return s[19]; }

    // world-frame inertia tensor about the centre of mass. torque = inertia * angular acceleration
    public Matrix3d inertia() {
        Matrix3d m = new Matrix3d();
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++) m.set(col, row, s[20 + row * 3 + col]);
        return m;
    }

    // gravity this body falls under, gravity scale included, blocks/s²
    public Vector3d gravity() { return new Vector3d(s[29], s[30], s[31]); }

    // length of the step this state belongs to. 0 in a published (server-thread) state
    public double dt() { return s[32]; }

    // share of the blocks sitting in water, 0..1
    public double submerged() { return s[33]; }

    public boolean sleeping() { return ((int) s[34] & FLAG_SLEEPING) != 0; }

    // resting on the world grid — the deck is real solid blocks to vanilla while this is true
    public boolean aligned() { return ((int) s[34] & FLAG_ALIGNED) != 0; }

    public boolean parked() { return ((int) s[34] & FLAG_PARKED) != 0; }

    // what khysics itself already pushed this step: water, aero, the held push. a pusher that wants
    // to cancel the world instead of guessing at it reads this
    public Vector3d khysicsForce() { return new Vector3d(s[35], s[36], s[37]); }

    public double gravityScale() { return s[38]; }

    public double buoyancyScale() { return s[39]; }

    // ── frame helpers ────────────────────────────────────────────────────────

    // body-frame point (block offset space) → world
    public Vector3d toWorld(Vector3dc local) {
        return rotation().transform(new Vector3d(local)).add(s[0], s[1], s[2]);
    }

    public Vector3d toLocal(Vector3dc world) {
        return rotation().transformInverse(new Vector3d(world).sub(s[0], s[1], s[2]));
    }

    // body-frame direction → world, no translation
    public Vector3d directionToWorld(Vector3dc local) { return rotation().transform(new Vector3d(local)); }

    public Vector3d directionToLocal(Vector3dc world) { return rotation().transformInverse(new Vector3d(world)); }

    // how fast a world point riding on the body moves: v + ω × (p - com)
    public Vector3d velocityAt(Vector3dc worldPoint) {
        Vector3d arm = new Vector3d(worldPoint).sub(s[13], s[14], s[15]);
        return omega().cross(arm).add(s[7], s[8], s[9]);
    }

    // the horizontal hull axis that points closest to a world direction — "which way is the bow if the
    // pilot is looking there". what a helm needs the moment someone sits down
    public Direction hullFacing(Vector3dc worldDir) {
        Quaterniond q = rotation();
        Direction best = Direction.NORTH;
        double bestDot = -Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vector3d w = q.transform(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ()));
            double dot = w.x * worldDir.x() + w.z * worldDir.z();
            if (dot > bestDot) { bestDot = dot; best = d; }
        }
        return best;
    }

    // raw floats, if you really want them. a copy, the state stays frozen
    public float[] raw() { return s.clone(); }
}
