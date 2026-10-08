package com.koper.koper_lib.kfx.graph;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public sealed interface KfxAnchor permits KfxAnchor.World, KfxAnchor.Entity, KfxAnchor.Bone, KfxAnchor.Between {
    KfxMissingPolicy missing();

    record World(Vec3 position, Vec3 normal, KfxMissingPolicy missing) implements KfxAnchor {
        public World {
            Objects.requireNonNull(position, "position");
            normal = normal == null || normal.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : normal.normalize();
            missing = missing == null ? KfxMissingPolicy.FREEZE : missing;
        }
    }

    record Entity(int entityId, KfxSocket socket, Vec3 offset, KfxMissingPolicy missing) implements KfxAnchor {
        public Entity {
            socket = socket == null ? KfxSocket.CENTER : socket;
            offset = offset == null ? Vec3.ZERO : offset;
            missing = missing == null ? KfxMissingPolicy.FREEZE : missing;
        }
    }

    record Bone(int entityId, String bone, Entity fallback, KfxMissingPolicy missing) implements KfxAnchor {
        public Bone {
            if (bone == null || bone.isBlank()) throw new IllegalArgumentException("KFX bone anchor needs a bone name");
            Objects.requireNonNull(fallback, "fallback");
            missing = missing == null ? KfxMissingPolicy.FREEZE : missing;
        }
    }

    record Between(KfxAnchor start, KfxAnchor end, float mix, KfxMissingPolicy missing) implements KfxAnchor {
        public Between {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            mix = Math.clamp(mix, 0.0f, 1.0f);
            missing = missing == null ? KfxMissingPolicy.FREEZE : missing;
        }
    }

    static World world(Vec3 position) {
        return new World(position, new Vec3(0, 1, 0), KfxMissingPolicy.FREEZE);
    }

}
