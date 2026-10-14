package com.koper.koper_lib.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Bodies in a space dimension: a drawn sphere, and optionally a planet dimension behind it. Flying into
 * the sphere (radius plus atmosphere) moves entities and whole physics assemblies to the planet
 * dimension; climbing above {@code leaveY} there moves them back out over the same spot of the sphere.
 * Pack bodies come from {@code space_bodies/*.json}; bodies registered here stay across pack reloads.
 */
public final class KoperSpace {
    private KoperSpace() {}

    /**
     * @param texture  texture id drawn on the sphere, e.g. {@code mypack:textures/space/moon.png}
     * @param color    ARGB tint of the sphere
     * @param glow     ARGB colour of the atmosphere shell, alpha 0 draws none
     * @param spin     degrees per second around the body's Y axis
     * @param target   planet dimension id, or null for a body you cannot land on
     * @param landing  half size in blocks of the planet area the sphere maps onto
     */
    public record Body(String id, String space, Vec3 center, double radius, double atmosphere,
                       String texture, int color, int glow, float spin,
                       String target, double landing, double arriveY, double leaveY) {
        public Body {
            if (id == null || space == null || center == null || texture == null)
                throw new IllegalArgumentException("a space body needs id, space, pos and texture");
            if (!(radius > 0) || !(atmosphere >= 0) || !Double.isFinite(radius + atmosphere)
                    || !Double.isFinite(center.x + center.y + center.z) || !Float.isFinite(spin))
                throw new IllegalArgumentException("space body " + id + ": radius > 0, atmosphere >= 0, finite position");
            if (target != null && (!(landing > 0) || !Double.isFinite(landing) || !(arriveY < leaveY)
                    || !Double.isFinite(arriveY) || !Double.isFinite(leaveY)))
                throw new IllegalArgumentException("space body " + id + ": landing_size > 0 and arrive_y below leave_y");
        }
        /** where crossing into the planet starts */
        public double reach() { return radius + atmosphere; }
        public boolean landable() { return target != null; }
        public Body at(Vec3 position) {
            return new Body(id, space, position, radius, atmosphere, texture, color, glow, spin, target, landing, arriveY, leaveY);
        }
    }

    /**
     * One crossing about to happen. {@code entity} is set for a single entity, {@code assembly} (a root
     * body id) for a physics assembly with everyone aboard.
     */
    public record Crossing(Body body, boolean arriving, Entity entity, long assembly, Vec3 destination) {}

    public static void register(Body body) { com.koper.koper_lib.fullpack.SpaceBodies.register(body, false); }
    public static void remove(String id) { com.koper.koper_lib.fullpack.SpaceBodies.remove(id); }
    /** moves a body, for orbits; clients get the new position on the next tick */
    public static void move(String id, Vec3 center) { com.koper.koper_lib.fullpack.SpaceBodies.move(id, center); }
    public static Body get(String id) { return com.koper.koper_lib.fullpack.SpaceBodies.get(id); }
    public static Collection<Body> all() { return com.koper.koper_lib.fullpack.SpaceBodies.all(); }
    /** every listener must return true or the crossing does not happen this tick (it is asked again) */
    public static void onCrossing(String owner, Predicate<Crossing> listener) {
        com.koper.koper_lib.fullpack.SpaceBodies.onCrossing(owner, listener);
    }

    // ── mapping between the sphere and the planet ──────────────────────────

    /** Planet position for a point at or inside the sphere: its direction picks x/z, height is arriveY. */
    public static Vec3 landingFor(Body body, Vec3 point) {
        Vec3 d = point.subtract(body.center());
        double length = d.length();
        Vec3 n = length < 1e-9 ? new Vec3(0, 1, 0) : d.scale(1 / length);
        return new Vec3(n.x * body.landing(), body.arriveY(), n.z * body.landing());
    }

    /** Space position for a planet point: x/z pick a direction on the upper half, placed just outside the reach. */
    public static Vec3 departureFor(Body body, Vec3 planetPoint) {
        double u = planetPoint.x / body.landing(), v = planetPoint.z / body.landing();
        double flat = u * u + v * v;
        if (flat > 1) { double s = 1 / Math.sqrt(flat); u *= s; v *= s; flat = 1; }
        Vec3 n = new Vec3(u, Math.sqrt(Math.max(0, 1 - flat)), v);
        return body.center().add(n.scale(body.reach() + margin(body)));
    }

    /** gap left outside the sphere after leaving a planet, so the next tick does not pull you back in */
    public static double margin(Body body) { return Math.max(16, body.radius() * 0.05); }

    /** Turns a velocity so that falling into the sphere along its surface normal becomes falling down. */
    public static Vec3 arrivalVelocity(Body body, Vec3 point, Vec3 velocity) {
        Vec3 d = point.subtract(body.center());
        if (d.lengthSqr() < 1e-12) return velocity;
        Vector3d inward = new Vector3d(-d.x, -d.y, -d.z).normalize();
        Vector3d v = new Quaterniond().rotationTo(inward, new Vector3d(0, -1, 0)).transform(new Vector3d(velocity.x, velocity.y, velocity.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** The reverse turn for leaving: going up becomes going away from the sphere at the departure point. */
    public static Vec3 departureVelocity(Body body, Vec3 spacePoint, Vec3 velocity) {
        Vec3 d = spacePoint.subtract(body.center());
        if (d.lengthSqr() < 1e-12) return velocity;
        Vector3d outward = new Vector3d(d.x, d.y, d.z).normalize();
        Vector3d v = new Quaterniond().rotationTo(new Vector3d(0, 1, 0), outward).transform(new Vector3d(velocity.x, velocity.y, velocity.z));
        return new Vec3(v.x, v.y, v.z);
    }
}
