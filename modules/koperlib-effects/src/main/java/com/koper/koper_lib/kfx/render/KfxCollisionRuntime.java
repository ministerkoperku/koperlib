package com.koper.koper_lib.kfx.render;

import net.minecraft.world.phys.Vec3;

/** Purely visual response math. Authoritative KfxController/KfxSensor contacts are a separate system. */
public final class KfxCollisionRuntime {
    public Result applyCosmeticContact(Particle particle, Vec3 rawNormal, KfxParticleResponse response,
                                       double restitution, double friction) {
        if (particle == null || rawNormal == null || response == null) throw new IllegalArgumentException("missing KFX cosmetic contact");
        if (!Double.isFinite(restitution) || restitution < 0 || restitution > 1
            || !Double.isFinite(friction) || friction < 0 || friction > 1) {
            throw new IllegalArgumentException("KFX restitution/friction must be finite and within 0..1");
        }
        Vec3 normal = rawNormal.lengthSqr() < 1.0e-9 ? new Vec3(0, 1, 0) : rawNormal.normalize();
        Vec3 velocity = particle.velocity();
        double inward = velocity.dot(normal);
        Vec3 tangent = velocity.subtract(normal.scale(inward));
        Vec3 outgoing = switch (response) {
            case BOUNCE -> tangent.scale(1.0 - friction).add(normal.scale(-inward * restitution));
            case SLIDE -> tangent.scale(1.0 - friction);
            case STICK, DIE -> Vec3.ZERO;
        };
        boolean alive = particle.alive() && response != KfxParticleResponse.DIE;
        return new Result(particle.position(), outgoing, alive, false);
    }

    public record Particle(Vec3 position, Vec3 velocity, boolean alive) {
        public Particle {
            java.util.Objects.requireNonNull(position, "position");
            java.util.Objects.requireNonNull(velocity, "velocity");
        }
    }
    public record Result(Vec3 position, Vec3 velocity, boolean alive, boolean gameplayImpact) {}
}
