package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperBoneAnchors;
import com.koper.koper_lib.api.core.KoperBonePositions;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a bone of a drawn Kodel mob is in the world, for anything pinned to it (effects, attachments).
 *
 * <p>Only bones somebody asked for get published: a resolve this frame marks the bone wanted, the
 * renderer publishes wanted bones as it draws, and the next frame reads them. A pose older than
 * one frame is gone, so something pinned to a mob that stopped rendering lets go.
 */
public final class KodelBoneAnchors {
    private static volatile Map<Integer, Set<String>> wanted = Map.of();
    private static final ConcurrentHashMap<Integer, Set<String>> NEXT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Key, Stamped> POSES = new ConcurrentHashMap<>();
    private static volatile long frame;

    private static final KoperBoneAnchors.Provider PROVIDER = new KoperBoneAnchors.Provider() {
        @Override public Optional<KoperBoneAnchors.TransformData> resolve(int entityId, String bone, float partialTick) {
            NEXT.computeIfAbsent(entityId, ignored -> ConcurrentHashMap.newKeySet()).add(bone);
            Stamped pose = POSES.get(new Key(entityId, bone));
            return pose == null || frame - pose.frame > 1 ? Optional.empty() : Optional.of(pose.transform);
        }

        @Override public void beginFrame() {
            frame++;
            NEXT.clear();
        }

        @Override public void endFrame() {
            var next = new java.util.HashMap<Integer, Set<String>>();
            NEXT.forEach((entity, bones) -> next.put(entity, Set.copyOf(bones)));
            wanted = Map.copyOf(next);
            POSES.keySet().removeIf(key -> !wanted.getOrDefault(key.entityId, Set.of()).contains(key.bone));
        }

        @Override public void clear() {
            KodelBoneAnchors.clear();
        }
    };

    private KodelBoneAnchors() {}

    public static KoperBoneAnchors.Provider provider() {
        return PROVIDER;
    }

    public static boolean wants(int entityId) {
        return wanted.containsKey(entityId);
    }

    static void publish(int entityId, String bone, KoperBoneAnchors.TransformData pose) {
        POSES.put(new Key(entityId, bone), new Stamped(frame, pose));
        KoperBonePositions.update(entityId, bone, new net.minecraft.world.phys.Vec3(pose.x(), pose.y(), pose.z()));
    }

    /**
     * Publishes the wanted bones of one drawn mob. {@code modelToWorld} takes model pixels to world
     * blocks relative to {@code (x, y, z)}: body yaw, scale and the 1/16 already in it.
     */
    public static void capture(int entityId, double x, double y, double z, Matrix4f modelToWorld,
                               KodelModel model, float[] pose) {
        Set<String> bones = wanted.get(entityId);
        if (bones == null || pose == null) return;
        Matrix4f bone = new Matrix4f();
        for (int i = 0; i < model.bones.size(); i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            if (!bones.contains(b.name)) continue;
            bone.set(pose, i * KodelSampler.MAT4_FLOATS);
            Matrix4f local = new Matrix4f(modelToWorld).mul(bone);
            publish(entityId, b.name, transform(local, b.pivot, x, y, z));
        }
    }

    static KoperBoneAnchors.TransformData transform(Matrix4f local, float[] pivot, double x, double y, double z) {
        Vector3f at = local.transformPosition(new Vector3f(pivot[0], pivot[1], pivot[2]));
        Vector3f forward = local.transformDirection(new Vector3f(0, 0, 1)).normalize();
        Vector3f normal = local.transformDirection(new Vector3f(0, 1, 0)).normalize();
        return new KoperBoneAnchors.TransformData(
            (float) (x + at.x), (float) (y + at.y), (float) (z + at.z),
            forward.x, forward.y, forward.z, normal.x, normal.y, normal.z);
    }

    public static void clear() {
        wanted = Map.of();
        NEXT.clear();
        POSES.clear();
        frame = 0;
        KoperBonePositions.clear();
    }

    private record Key(int entityId, String bone) {}
    private record Stamped(long frame, KoperBoneAnchors.TransformData transform) {}
}
