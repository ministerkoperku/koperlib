package com.koper.koper_lib.physics.body;

import org.joml.Vector3d;
import org.joml.Vector3dc;

// what a pusher hands back for one step: a force through the centre of mass and a torque, world
// frame. every helper here just folds into those two, so push from wherever is convenient —
// a thruster at a block, a local "forward", a wanted acceleration — and the maths stays in here.
//
// forces are khysics units (weight-book mass × blocks/s²). they last exactly the step they were
// pushed in, which is fine because the pusher runs again next step
public final class KhysPush {

    private final KhysBodyState state;
    private final Vector3d force = new Vector3d();
    private final Vector3d torque = new Vector3d();

    public KhysPush(KhysBodyState state) { this.state = state; }

    public KhysPush force(double x, double y, double z) {
        if (Double.isFinite(x + y + z)) force.add(x, y, z);
        return this;
    }

    public KhysPush force(Vector3dc f) { return force(f.x(), f.y(), f.z()); }

    public KhysPush torque(double x, double y, double z) {
        if (Double.isFinite(x + y + z)) torque.add(x, y, z);
        return this;
    }

    public KhysPush torque(Vector3dc t) { return torque(t.x(), t.y(), t.z()); }

    // a world force at a world point: the force itself plus (p - com) × f of spin
    public KhysPush forceAt(Vector3dc f, Vector3dc worldPoint) {
        force(f);
        Vector3d arm = new Vector3d(worldPoint).sub(state.centerOfMass());
        return torque(arm.cross(f));
    }

    // a force in the body frame, "along the hull" — rotates with the ship
    public KhysPush localForce(Vector3dc f) { return force(state.directionToWorld(f)); }

    // a body-frame force at a body-frame point, e.g. an engine block at its offset
    public KhysPush localForceAt(Vector3dc f, Vector3dc localPoint) {
        return forceAt(state.directionToWorld(f), state.toWorld(localPoint));
    }

    public KhysPush localTorque(Vector3dc t) { return torque(state.directionToWorld(t)); }

    // "i want the body to accelerate like this" — mass times it, no unit guessing
    public KhysPush acceleration(Vector3dc a) {
        return force(new Vector3d(a).mul(state.mass()));
    }

    // "i want it to spin up like this" — world inertia times it
    public KhysPush angularAcceleration(Vector3dc alpha) {
        return torque(state.inertia().transform(new Vector3d(alpha)));
    }

    // cancel gravity for this step. balloons, antigrav, a hover pad
    public KhysPush cancelGravity() {
        return force(state.gravity().mul(-state.mass()));
    }

    public Vector3dc force() { return force; }

    public Vector3dc torque() { return torque; }

    public KhysBodyState state() { return state; }
}
