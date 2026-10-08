package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class KfxController {
    public static final double MAX_RADIUS = 1.0;
    public static final double MAX_SPEED = 8.0;
    public static final double MAX_ACCELERATION = 4.0;
    public static final int MAX_LIFETIME = 20 * 60;
    public static final int MAX_BOUNCES = 64;
    public static final int MAX_HITS = 256;
    public static final int MAX_HIT_COOLDOWN = 20 * 60;
    public static final int MAX_INPUTS = 64;
    private static final double MAX_COORDINATE = 30_000_000.0;
    private final long handle;
    private final int nodeId;
    private final UUID owner;
    private final double radius;
    private final int lifetime;
    private final Response response;
    private final int maxBounces;
    private final int maxHits;
    private final int hitCooldown;
    private final double restitution;
    private final double surfaceFriction;
    private final long seed;
    private final Map<String, KfxResolvedValue> inputs;
    private final Predicate<Entity> entityFilter;
    private final Map<UUID, Integer> ignoredUntil = new HashMap<>();
    private Vec3 previous;
    private Vec3 position;
    private Vec3 velocity;
    private final Vec3 acceleration;
    private State state = State.RUNNING;
    private int age;
    private int bounces;
    private int hits;
    private long sequence;

    private KfxController(Builder it) {
        validate(it);
        handle = it.handle;
        nodeId = it.nodeId;
        owner = it.owner;
        position = it.position;
        previous = it.position;
        velocity = it.velocity;
        acceleration = it.acceleration;
        radius = it.radius;
        lifetime = it.lifetime;
        response = it.response;
        maxBounces = it.maxBounces;
        maxHits = it.maxHits;
        hitCooldown = it.hitCooldown;
        restitution = it.restitution;
        surfaceFriction = it.surfaceFriction;
        seed = it.seed;
        inputs = Map.copyOf(it.inputs);
        entityFilter = it.entityFilter;
    }

    public static Builder projectile(long handle, UUID owner, Vec3 position, Vec3 velocity) {
        return new Builder(handle, owner, position, velocity);
    }

    public Step step(KfxSensor sensor) {
        if (state == State.STOPPED) return new Step(handle, null, state, previous, position, velocity);
        if (state == State.STUCK) {
            previous = position;
            age++;
            if (age >= lifetime) state = State.STOPPED;
            return new Step(handle, null, state, previous, position, velocity);
        }
        previous = position;
        velocity = velocity.add(acceleration);
        if (velocity.lengthSqr() > MAX_SPEED * MAX_SPEED) velocity = velocity.normalize().scale(MAX_SPEED);
        Vec3 end = position.add(velocity);
        ignoredUntil.entrySet().removeIf(entry -> entry.getValue() <= age);
        KfxSensor.Contact contact = sensor.sweep(position, end, radius, Set.copyOf(ignoredUntil.keySet()));
        KfxImpact impact = null;
        if (contact == null) {
            position = end;
        } else if (contact.unavailable()) {
            position = previous;
            velocity = Vec3.ZERO;
            state = State.STOPPED;
        } else {
            impact = accept(contact);
            if (response == Response.PASS) position = end;
        }
        age++;
        if (age >= lifetime && state == State.RUNNING) state = State.STOPPED;
        return new Step(handle, impact, state, previous, position, velocity);
    }

    public KfxImpact accept(KfxSensor.Contact contact) {
        Vec3 incoming = velocity;
        Vec3 outgoing = switch (response) {
            case BOUNCE -> bounce(incoming, contact.normal());
            case SLIDE -> incoming.subtract(contact.normal().scale(incoming.dot(contact.normal())));
            case STOP, STICK, SPLIT -> Vec3.ZERO;
            case PASS -> incoming;
        };
        hits++;
        if (contact.entity() != null) ignoredUntil.put(contact.entity(), age + hitCooldown);
        if (response == Response.BOUNCE) bounces++;
        if (response == Response.STICK) state = State.STUCK;
        if (response == Response.STOP || response == Response.SPLIT || hits >= maxHits
            || response == Response.BOUNCE && bounces >= maxBounces) {
            state = State.STOPPED;
            if (response == Response.BOUNCE && bounces >= maxBounces) outgoing = Vec3.ZERO;
        }
        position = response == Response.BOUNCE || response == Response.SLIDE
            ? contact.position().add(contact.normal().scale(1.0e-4)) : contact.position();
        velocity = outgoing;
        return new KfxImpact(handle, nodeId, ++sequence, owner, contact.entity(), contact.block(),
            contact.position(), contact.normal(), incoming, outgoing, bounces, seed, inputs);
    }

    // steer a running controller. same speed cap as the constructor so nobody cheats the terminal speed
    public boolean steer(Vec3 value) {
        if (state != State.RUNNING || value == null || !Double.isFinite(value.lengthSqr())) return false;
        double length = value.length();
        velocity = length > MAX_SPEED ? value.scale(MAX_SPEED / length) : value;
        return true;
    }

    public long handle() { return handle; }
    public UUID owner() { return owner; }
    public Vec3 previous() { return previous; }
    public Vec3 position() { return position; }
    public Vec3 velocity() { return velocity; }
    public double radius() { return radius; }
    public int maxBounces() { return maxBounces; }
    public State state() { return state; }
    public int age() { return age; }
    public boolean canHit(Entity entity) { return entity != null && entityFilter.test(entity); }

    private Vec3 bounce(Vec3 incoming, Vec3 normal) {
        Vec3 normalVelocity = normal.scale(incoming.dot(normal));
        Vec3 tangentVelocity = incoming.subtract(normalVelocity);
        return tangentVelocity.scale(1.0 - surfaceFriction).subtract(normalVelocity.scale(restitution));
    }

    private static void validate(Builder it) {
        requirePosition(it.position);
        requireVector("velocity", it.velocity, MAX_SPEED);
        requireVector("acceleration", it.acceleration, MAX_ACCELERATION);
        if (!Double.isFinite(it.radius) || it.radius < 0 || it.radius > MAX_RADIUS) {
            throw new IllegalArgumentException("KFX radius must be finite and between 0 and " + MAX_RADIUS);
        }
        if (it.lifetime < 1 || it.lifetime > MAX_LIFETIME) {
            throw new IllegalArgumentException("KFX lifetime must be between 1 and " + MAX_LIFETIME);
        }
        if (it.maxBounces < 1 || it.maxBounces > MAX_BOUNCES) {
            throw new IllegalArgumentException("KFX maxBounces must be between 1 and " + MAX_BOUNCES);
        }
        if (it.maxHits < 1 || it.maxHits > MAX_HITS) {
            throw new IllegalArgumentException("KFX maxHits must be between 1 and " + MAX_HITS);
        }
        if (it.hitCooldown < 1 || it.hitCooldown > MAX_HIT_COOLDOWN) {
            throw new IllegalArgumentException("KFX hitCooldown must be between 1 and " + MAX_HIT_COOLDOWN);
        }
        requireUnitInterval("restitution", it.restitution);
        requireUnitInterval("surfaceFriction", it.surfaceFriction);
        if (it.response == null || it.entityFilter == null) {
            throw new IllegalArgumentException("KFX response and entity filter cannot be null");
        }
        if (it.inputs == null || it.inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("KFX inputs cannot exceed " + MAX_INPUTS);
        }
    }

    private static void requireVector(String name, Vec3 value, double maxLength) {
        if (value == null || !Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)
            || value.lengthSqr() > maxLength * maxLength) {
            throw new IllegalArgumentException("KFX " + name + " must be finite and within " + maxLength);
        }
    }

    private static void requirePosition(Vec3 value) {
        if (value == null || !Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)
            || Math.abs(value.x) > MAX_COORDINATE || Math.abs(value.y) > MAX_COORDINATE
            || Math.abs(value.z) > MAX_COORDINATE) {
            throw new IllegalArgumentException("KFX position must be finite and inside the world coordinate limit");
        }
    }

    private static void requireUnitInterval(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("KFX " + name + " must be finite and between 0 and 1");
        }
    }

    public enum State { RUNNING, STUCK, STOPPED }
    public enum Response { STOP, BOUNCE, SLIDE, STICK, SPLIT, PASS }

    public record Step(long handle, KfxImpact impact, State state, Vec3 previous, Vec3 position, Vec3 velocity) {}

    public static final class Builder {
        private final long handle;
        private final UUID owner;
        private final Vec3 position;
        private final Vec3 velocity;
        private Vec3 acceleration = Vec3.ZERO;
        private double radius = 0.125;
        private int lifetime = 200;
        private Response response = Response.STOP;
        private int maxBounces = 1;
        private int maxHits = 32;
        private int hitCooldown = 5;
        private double restitution = 1.0;
        private double surfaceFriction;
        private int nodeId;
        private long seed;
        private Map<String, KfxResolvedValue> inputs = Map.of();
        private Predicate<Entity> entityFilter = entity -> true;

        private Builder(long handle, UUID owner, Vec3 position, Vec3 velocity) {
            if (handle == 0 || owner == null || position == null || velocity == null) {
                throw new IllegalArgumentException("KFX projectile needs handle, owner, position and velocity");
            }
            this.handle = handle;
            this.owner = owner;
            this.position = position;
            this.velocity = velocity;
            this.seed = handle;
        }

        public Builder acceleration(Vec3 value) { acceleration = value; return this; }
        public Builder radius(double value) { radius = value; return this; }
        public Builder lifetime(int ticks) { lifetime = ticks; return this; }
        public Builder response(Response value) { response = value; return this; }
        public Builder maxBounces(int value) { maxBounces = value; return this; }
        public Builder maxHits(int value) { maxHits = value; return this; }
        public Builder hitCooldown(int ticks) { hitCooldown = ticks; return this; }
        public Builder restitution(double value) { restitution = value; return this; }
        public Builder surfaceFriction(double value) { surfaceFriction = value; return this; }
        public Builder nodeId(int value) { nodeId = value; return this; }
        public Builder seed(long value) { seed = value; return this; }
        public Builder inputs(Map<String, KfxResolvedValue> value) { inputs = value == null ? Map.of() : value; return this; }
        public Builder entityFilter(Predicate<Entity> value) { entityFilter = value; return this; }
        public KfxController build() { return new KfxController(this); }
    }
}
