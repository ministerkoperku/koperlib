package com.koper.koper_lib.kodel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A model block's pose: its clip sampled at the block's own time, with the binding's procedural
 * ops (spin, sway, bob, hold, shift, stretch) stacked on top.
 *
 * <p>The ops speak the old block convention: per bone {rx, ry, rz, tx, ty, tz, sx, sy, sz}, rotation
 * in radians added to the bone's euler angles, translation in blocks added to its position, scale
 * multiplied into its own. A zero scale slot means "leave it".
 */
public final class KodelBlockPose {
    private KodelBlockPose() {}

    private static final Map<String, KodelSampler.ResolvedTracks> TRACKS = new ConcurrentHashMap<>();

    public static void clear() {
        TRACKS.clear();
    }

    /**
     * World matrices for every bone, {@link KodelSampler#MAT4_FLOATS} floats each, in model pixels.
     * A null clip and null ops is the rest pose.
     */
    public static float[] world(KodelBook.Entry entry, String clip, double clipTime, Function<String, float[]> ops) {
        KodelModel model = entry.model();
        KodelAnimation anim = clip == null ? null : entry.clip(clip);
        if (anim == null && ops == null) return entry.restPose();

        KodelSampler.ResolvedTracks tracks = anim == null ? null
            : TRACKS.computeIfAbsent(entry.name() + '\u0000' + clip, key -> KodelSampler.ResolvedTracks.of(model, anim));
        float t = anim == null ? 0f : KodelBook.fold(anim, (float) clipTime);

        int n = model.bones.size();
        float[] world = new float[n * KodelSampler.MAT4_FLOATS];
        float[] sampled = new float[3];
        Quaternionf q = new Quaternionf();
        Vector3f euler = new Vector3f();
        Matrix4f local = new Matrix4f();
        Matrix4f parent = new Matrix4f();
        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);

            if (tracks != null && tracks.rotation[i] != null) {
                KodelSampler.sample(tracks.rotation[i], t, sampled);
                KodelSampler.quatFromEuler(sampled[0], sampled[1], sampled[2], q);
            } else {
                q.set(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
            }
            float px, py, pz;
            if (tracks != null && tracks.position[i] != null) {
                KodelSampler.sample(tracks.position[i], t, sampled);
                px = sampled[0]; py = sampled[1]; pz = sampled[2];
            } else {
                px = b.position[0]; py = b.position[1]; pz = b.position[2];
            }
            float sx, sy, sz;
            if (tracks != null && tracks.scale[i] != null) {
                KodelSampler.sample(tracks.scale[i], t, sampled);
                sx = sampled[0]; sy = sampled[1]; sz = sampled[2];
            } else {
                sx = b.scale[0]; sy = b.scale[1]; sz = b.scale[2];
            }

            float[] ov = ops == null ? null : ops.apply(b.name);
            if (ov != null) {
                px += ov[3] * 16f;
                py += ov[4] * 16f;
                pz += ov[5] * 16f;
                if (ov[0] != 0 || ov[1] != 0 || ov[2] != 0) {
                    q.getEulerAnglesZYX(euler);
                    q.rotationZYX(euler.z + ov[2], euler.y + ov[1], euler.x + ov[0]);
                }
                if (ov.length >= 9) {
                    if (ov[6] != 0) sx *= ov[6];
                    if (ov[7] != 0) sy *= ov[7];
                    if (ov[8] != 0) sz *= ov[8];
                }
            }

            local.translation(px, py, pz)
                .translate(b.pivot[0], b.pivot[1], b.pivot[2])
                .rotate(q.normalize())
                .scale(sx, sy, sz)
                .translate(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
            int at = i * KodelSampler.MAT4_FLOATS;
            if (b.parent >= 0) {
                parent.set(world, b.parent * KodelSampler.MAT4_FLOATS).mul(local).get(world, at);
            } else {
                local.get(world, at);
            }
        }
        return world;
    }
}
