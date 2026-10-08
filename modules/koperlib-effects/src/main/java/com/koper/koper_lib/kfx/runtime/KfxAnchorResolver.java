package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class KfxAnchorResolver {
    public Result resolve(KfxAnchor anchor, PoseSource source, float partialTick, KfxTransform last) {
        if (anchor instanceof KfxAnchor.World world) {
            return active(new KfxTransform(world.position(), perpendicular(world.normal()), world.normal()));
        }
        if (anchor instanceof KfxAnchor.Entity entity) {
            EntityPose pose = source.entity(entity.entityId());
            return pose == null ? missing(entity.missing(), last) : active(entityTransform(entity, pose, partialTick));
        }
        if (anchor instanceof KfxAnchor.Bone bone) {
            Optional<KfxTransform> transform = source.bone(bone.entityId(), bone.bone(), partialTick);
            if (transform.isPresent()) return active(transform.get());
            Result fallback = resolve(bone.fallback(), source, partialTick, last);
            return fallback.state == KfxAnchorState.ACTIVE ? fallback : missing(bone.missing(), last);
        }
        if (anchor instanceof KfxAnchor.Between between) {
            Result a = resolve(between.start(), source, partialTick, last);
            Result b = resolve(between.end(), source, partialTick, last);
            if (a.state != KfxAnchorState.ACTIVE || b.state != KfxAnchorState.ACTIVE) {
                return missing(between.missing(), last);
            }
            Vec3 delta = b.transform.position().subtract(a.transform.position());
            Vec3 forward = delta.lengthSqr() < 1.0e-8 ? a.transform.forward() : delta.normalize();
            Vec3 position = a.transform.position().lerp(b.transform.position(), between.mix());
            return active(new KfxTransform(position, forward, a.transform.normal().lerp(b.transform.normal(), between.mix())));
        }
        throw new IllegalArgumentException("unknown KFX anchor " + anchor);
    }

    public Result missing(KfxMissingPolicy policy, KfxTransform last) {
        KfxTransform frozen = last == null ? KfxTransform.at(Vec3.ZERO) : last;
        return new Result(KfxAnchorState.valueOf(policy.name()), frozen);
    }

    private static Result active(KfxTransform transform) {
        return new Result(KfxAnchorState.ACTIVE, transform);
    }

    private static KfxTransform entityTransform(KfxAnchor.Entity anchor, EntityPose pose, float partialTick) {
        double partial = Math.clamp(partialTick, 0.0f, 1.0f);
        Vec3 base = pose.oldPosition.lerp(pose.position, partial);
        float bodyYaw = Mth.rotLerp((float)partial, pose.oldBodyYaw, pose.bodyYaw);
        float viewYaw = Mth.rotLerp((float)partial, pose.oldViewYaw, pose.viewYaw);
        float pitch = Mth.lerp((float)partial, pose.oldPitch, pose.pitch);
        double yawRad = Math.toRadians(viewYaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3 forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)).normalize();
        double bodyYawRad = Math.toRadians(bodyYaw);
        Vec3 flatForward = new Vec3(-Math.sin(bodyYawRad), 0, Math.cos(bodyYawRad));
        // Facing south (yaw 0) the player's right hand points west, so right is -X, not +X.
        // The old sign put every MAIN_HAND anchor on the off hand and made a positive x offset go left.
        Vec3 right = new Vec3(-flatForward.z, 0, flatForward.x);
        Vec3 socket = switch (anchor.socket()) {
            case FEET -> Vec3.ZERO;
            case CENTER -> new Vec3(0, pose.height * 0.5, 0);
            case EYES -> new Vec3(0, pose.eyeHeight, 0);
            case MAIN_HAND -> hand(pose, right, flatForward, pose.mainArmRight);
            case OFF_HAND -> hand(pose, right, flatForward, !pose.mainArmRight);
        };
        Vec3 offsetForward = anchor.socket() == KfxSocket.EYES ? forward : flatForward;
        Vec3 local = right.scale(anchor.offset().x)
            .add(0, anchor.offset().y, 0)
            .add(offsetForward.scale(anchor.offset().z));
        return new KfxTransform(base.add(socket).add(local), forward, new Vec3(0, 1, 0));
    }

    private static Vec3 hand(EntityPose pose, Vec3 right, Vec3 forward, boolean rightSide) {
        // Where a held item sits, not where the wrist is: out to the side, forward, and just under eye level.
        double side = rightSide ? 0.38 : -0.38;
        return new Vec3(0, pose.eyeHeight * 0.84, 0).add(right.scale(side)).add(forward.scale(0.22));
    }

    private static Vec3 perpendicular(Vec3 normal) {
        Vec3 axis = Math.abs(normal.y) > 0.95 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 forward = axis.cross(normal);
        return forward.lengthSqr() < 1.0e-8 ? new Vec3(0, 0, 1) : forward.normalize();
    }

    public interface PoseSource {
        EntityPose entity(int id);
        Optional<KfxTransform> bone(int id, String bone, float partialTick);
    }

    public record EntityPose(
        Vec3 oldPosition,
        Vec3 position,
        float oldBodyYaw,
        float bodyYaw,
        float oldViewYaw,
        float viewYaw,
        float oldPitch,
        float pitch,
        double height,
        double eyeHeight,
        boolean mainArmRight
    ) {
        public EntityPose(Vec3 oldPosition, Vec3 position, float oldBodyYaw, float bodyYaw,
                          float oldPitch, float pitch, double height, double eyeHeight, boolean mainArmRight) {
            this(oldPosition, position, oldBodyYaw, bodyYaw, oldBodyYaw, bodyYaw,
                oldPitch, pitch, height, eyeHeight, mainArmRight);
        }
    }

    public record Result(KfxAnchorState state, KfxTransform transform) {}
}
