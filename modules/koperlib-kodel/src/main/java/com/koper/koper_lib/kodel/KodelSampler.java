package com.koper.koper_lib.kodel;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import static com.koper.koper_lib.kodel.KodelFormat.EASE_BEZIER;
import static com.koper.koper_lib.kodel.KodelFormat.EASE_LINEAR;
import static com.koper.koper_lib.kodel.KodelFormat.EASE_STEP;

/**
 * Pose sampler. Resolves an animation at time t to per-bone world matrices
 * (16 floats per bone, column-major GL convention). Handles the three binary
 * eases plus Catmull-Rom smoothing for bezier keyframes that carry no real
 * control points, and replaces a bone's rotation/position/scale only when the
 * clip owns that channel — the static pose otherwise. Pivot always comes from
 * the model bone, matching Blockbench / Bedrock transform order.
 */
public final class KodelSampler {
    public static final int MAT4_FLOATS = 16;

    private KodelSampler() {}

    /** Returns the sampler mode for a stored easing byte. */
    public static int mode(int easing, int keyIndex, KodelAnimation.KodelChannel ch) {
        switch (easing) {
            case EASE_LINEAR:
                return 0;
            case EASE_STEP:
                return 1;
            case EASE_BEZIER:
                if (keyIndex < ch.count && keyIndex >= 0) {
                    int i = keyIndex * 3;
                    if (ch.ctrlA[i] == 0f && ch.ctrlA[i + 1] == 0f && ch.ctrlA[i + 2] == 0f
                            && ch.ctrlB[i] == 0f && ch.ctrlB[i + 1] == 0f && ch.ctrlB[i + 2] == 0f) {
                        return 2; // smooth
                    }
                }
                return 3; // real bezier
            default:
                return 0;
        }
    }

    /**
     * Samples channel {@code ch} at clip time {@code t} into {@code out[3]}.
     * Caller folds t into the clip's loop domain first; this handles keys that
     * stretch past length by treating them as the last value.
     */
    public static void sample(KodelAnimation.KodelChannel ch, float t, float[] out) {
        if (ch == null || ch.count == 0) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        if (ch.count == 1 || t <= ch.times[0]) {
            copyValue(ch, 0, out);
            return;
        }
        int last = ch.count - 1;
        if (t >= ch.times[last]) {
            copyValue(ch, last, out);
            return;
        }
        int k = findSegment(ch, t);
        if (k < 0) {
            copyValue(ch, 0, out);
            return;
        }
        int k1 = k + 1;
        float span = ch.times[k1] - ch.times[k];
        float u = span <= 0f ? 0f : (t - ch.times[k]) / span;
        int mode = mode(ch.easing[k], k, ch);
        switch (mode) {
            case 0: {
                float w = 1f - u;
                for (int i = 0; i < 3; i++) {
                    int j = k * 3 + i;
                    out[i] = ch.values[j] * w + ch.values[j + 3] * u;
                }
                break;
            }
            case 1:
                copyValue(ch, k, out);
                break;
            case 2:
                catmull(ch, k, u, out);
                break;
            default:
                bezier(ch, k, u, out);
        }
    }

    private static int findSegment(KodelAnimation.KodelChannel ch, float t) {
        int lo = 0;
        int hi = ch.count - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ch.times[mid] <= t) {
                if (mid + 1 >= ch.count || ch.times[mid + 1] > t) return mid;
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static void copyValue(KodelAnimation.KodelChannel ch, int key, float[] out) {
        int i = key * 3;
        out[0] = ch.values[i];
        out[1] = ch.values[i + 1];
        out[2] = ch.values[i + 2];
    }

    /** Catmull-Rom across (k-1, k, k+1, k+2), neighbouring keys clamped at borders. */
    private static void catmull(KodelAnimation.KodelChannel ch, int k, float u, float[] out) {
        int p0 = Math.max(0, k - 1);
        int p1 = k;
        int p2 = k + 1;
        int p3 = Math.min(ch.count - 1, k + 2);
        float u2 = u * u;
        float u3 = u2 * u;
        for (int i = 0; i < 3; i++) {
            float v0 = ch.values[p0 * 3 + i];
            float v1 = ch.values[p1 * 3 + i];
            float v2 = ch.values[p2 * 3 + i];
            float v3 = ch.values[p3 * 3 + i];
            out[i] = 0.5f * ((2f * v1)
                + (-v0 + v2) * u
                + (2f * v0 - 5f * v1 + 4f * v2 - v3) * u2
                + (-v0 + 3f * v1 - 3f * v2 + v3) * u3);
        }
    }

    /** Cubic bezier; control points offset from values per the spec. */
    private static void bezier(KodelAnimation.KodelChannel ch, int k, float u, float[] out) {
        int k1 = k + 1;
        for (int i = 0; i < 3; i++) {
            float p0 = ch.values[k * 3 + i];
            float p3 = ch.values[k1 * 3 + i];
            float c0 = p0 + ch.ctrlB[k * 3 + i];
            float c1 = p3 + ch.ctrlA[k1 * 3 + i];
            float u2 = u * u;
            float u3 = u2 * u;
            float w0 = 1f - 3f * u + 3f * u2 - u3;
            float w1 = 3f * u - 6f * u2 + 3f * u3;
            float w2 = 3f * u2 - 3f * u3;
            float w3 = u3;
            out[i] = w0 * p0 + w1 * c0 + w2 * c1 + w3 * p3;
        }
    }

    /** Quaternion from ZYX euler radians — X pitch, Y yaw, Z roll (Blockbench order). */
    public static void quatFromEuler(float x, float y, float z, Quaternionf out) {
        float cx = (float) Math.cos(x / 2), sx = (float) Math.sin(x / 2);
        float cy = (float) Math.cos(y / 2), sy = (float) Math.sin(y / 2);
        float cz = (float) Math.cos(z / 2), sz = (float) Math.sin(z / 2);
        out.set(
            sx * cy * cz - cx * sy * sz,
            cx * sy * cz + sx * cy * sz,
            cx * cy * sz - sx * sy * cz,
            cx * cy * cz + sx * sy * sz);
    }

    /** Precomputed per-bone animation tracks, indexed by bone. */
    public static final class ResolvedTracks {
        public final KodelAnimation.KodelChannel[] rotation;
        public final KodelAnimation.KodelChannel[] position;
        public final KodelAnimation.KodelChannel[] scale;

        ResolvedTracks(int boneCount, KodelAnimation anim) {
            rotation = new KodelAnimation.KodelChannel[boneCount];
            position = new KodelAnimation.KodelChannel[boneCount];
            scale = new KodelAnimation.KodelChannel[boneCount];
        }

        public static ResolvedTracks of(KodelModel model, KodelAnimation anim) {
            ResolvedTracks r = new ResolvedTracks(model.bones.size(), anim);
            // two tracks on one bone is malformed but happens. merge per channel so a
            // later rotation-only track can't wipe an earlier position one, and so
            // the rust side lands on the same answer
            for (KodelAnimation.KodelTrack track : anim.tracks) {
                int bi = model.boneIndex(track.bone);
                if (bi < 0 || !track.hasChannels()) continue;
                if (track.rotation != null) r.rotation[bi] = track.rotation;
                if (track.position != null) r.position[bi] = track.position;
                if (track.scale != null) r.scale[bi] = track.scale;
            }
            return r;
        }
    }

    /**
     * Samples every bone of {@code model} at track-local time {@code t} into
     * {@code world}, which must hold MAT4_FLOATS * boneCount floats. Bone index
     * 0 is the root. Columns are column-major GL matrices.
     */
    /**
     * Cross-fades two clips into one pose. {@code mix} runs 0 (all {@code from}) to
     * 1 (all {@code to}).
     *
     * <p>Blending happens on the bone's own translation, rotation and scale, not on
     * the finished matrices. Lerping two matrices shears anything that is rotating,
     * and a leg swapping from walk to idle is exactly that case.
     */
    public static void samplePoseBlended(KodelModel model,
                                         ResolvedTracks from, float tFrom,
                                         ResolvedTracks to, float tTo,
                                         float mix, float[] world) {
        float m = mix < 0f ? 0f : mix > 1f ? 1f : mix;
        int n = model.bones.size();
        float[] scratch = new float[3];
        Quaternionf qa = new Quaternionf();
        Quaternionf qb = new Quaternionf();
        Matrix4f local = new Matrix4f();
        Matrix4f tmp = new Matrix4f();
        float[] posA = new float[3], posB = new float[3];
        float[] sclA = new float[3], sclB = new float[3];

        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            rotationOf(b, from, i, tFrom, scratch, qa);
            rotationOf(b, to, i, tTo, scratch, qb);
            // shortest arc, or a half turn blends the long way round
            qa.nlerp(qb, m).normalize();

            positionOf(b, from, i, tFrom, posA);
            positionOf(b, to, i, tTo, posB);
            scaleOf(b, from, i, tFrom, sclA);
            scaleOf(b, to, i, tTo, sclB);

            compose(local, tmp, b,
                lerp(posA[0], posB[0], m), lerp(posA[1], posB[1], m), lerp(posA[2], posB[2], m),
                qa.x, qa.y, qa.z, qa.w,
                lerp(sclA[0], sclB[0], m), lerp(sclA[1], sclB[1], m), lerp(sclA[2], sclB[2], m));
            store(model, world, i, b, local);
        }
    }

    /**
     * One clip laid over another. Per channel, per bone: if the overlay animates it,
     * the overlay wins, otherwise the base does. Sampled in one pass so a bone under
     * an overlaid parent inherits the overlaid pose.
     */
    public static void samplePoseLayered(KodelModel model,
                                         ResolvedTracks base, float tBase,
                                         ResolvedTracks over, float tOver,
                                         float[] world) {
        samplePoseLayered(model, base, tBase, null, 0f, 0f, over, tOver, world);
    }

    /**
     * Everything a renderer needs in one pass: a base clip optionally cross-fading
     * into another, with a third laid over the bones it owns.
     *
     * <p>{@code fade} is the clip being faded in; {@code mix} 0 keeps {@code base},
     * 1 lands on {@code fade}. The overlay is not faded, it simply wins wherever it
     * animates, because an attack that only moves a jaw should take the jaw
     * immediately and leave the legs walking.
     */
    public static void samplePoseLayered(KodelModel model,
                                         ResolvedTracks base, float tBase,
                                         ResolvedTracks fade, float tFade, float mix,
                                         ResolvedTracks over, float tOver,
                                         float[] world) {
        samplePoseLayered(model, base, tBase, fade, tFade, mix, over, tOver, 1f, world);
    }

    /**
     * As above, with the overlay's own weight. 1 is the hard takeover, 0 leaves the
     * base alone. Ramping it lets an attack arrive and leave without a snap.
     */
    public static void samplePoseLayered(KodelModel model,
                                         ResolvedTracks base, float tBase,
                                         ResolvedTracks fade, float tFade, float mix,
                                         ResolvedTracks over, float tOver, float overWeight,
                                         float[] world) {
        // a null side is the bind pose, not "do not fade". a mob stopping has nothing
        // to fade INTO, and refusing the fade there is what made it hold the walk and
        // then snap to standing
        float m = mix < 0f ? 0f : mix > 1f ? 1f : mix;
        float ow = overWeight < 0f ? 0f : overWeight > 1f ? 1f : overWeight;
        int n = model.bones.size();
        float[] scratch = new float[3];
        float[] pos = new float[3];
        float[] scl = new float[3];
        Quaternionf q = new Quaternionf();
        Matrix4f local = new Matrix4f();
        Matrix4f tmp = new Matrix4f();

        Quaternionf q2 = new Quaternionf();
        float[] pos2 = new float[3];
        float[] scl2 = new float[3];

        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);

            rotationOf(b, base, i, tBase, scratch, q);
            if (m > 0f) {
                rotationOf(b, fade, i, tFade, scratch, q2);
                q.nlerp(q2, m).normalize();
            }
            if (ow > 0f && over != null && over.rotation[i] != null) {
                rotationOf(b, over, i, tOver, scratch, q2);
                q.nlerp(q2, ow).normalize();
            }

            positionOf(b, base, i, tBase, pos);
            if (m > 0f) {
                positionOf(b, fade, i, tFade, pos2);
                for (int c = 0; c < 3; c++) pos[c] = lerp(pos[c], pos2[c], m);
            }
            if (ow > 0f && over != null && over.position[i] != null) {
                positionOf(b, over, i, tOver, pos2);
                for (int c = 0; c < 3; c++) pos[c] = lerp(pos[c], pos2[c], ow);
            }

            scaleOf(b, base, i, tBase, scl);
            if (m > 0f) {
                scaleOf(b, fade, i, tFade, scl2);
                for (int c = 0; c < 3; c++) scl[c] = lerp(scl[c], scl2[c], m);
            }
            if (ow > 0f && over != null && over.scale[i] != null) {
                scaleOf(b, over, i, tOver, scl2);
                for (int c = 0; c < 3; c++) scl[c] = lerp(scl[c], scl2[c], ow);
            }

            compose(local, tmp, b, pos[0], pos[1], pos[2], q.x, q.y, q.z, q.w,
                scl[0], scl[1], scl[2]);
            store(model, world, i, b, local);
        }
    }

    /**
     * Poses every bone by a delta from its bind pose rather than from a clip.
     *
     * <p>This is how worn armour follows a body: the wearer's limb has moved some
     * amount away from where it rests, and the bone on the armour has to move by the
     * same amount. {@code delta} answers six floats per bone name, rotation in
     * radians then position in pixels, or null for a bone that stays put.
     *
     * <p>The rotation is composed onto the bind rotation rather than replacing it, so
     * a bone authored at an angle keeps that angle and turns further from it.
     */
    public static void samplePoseFollowing(KodelModel model,
                                           java.util.function.Function<String, float[]> delta,
                                           float[] world) {
        samplePoseFollowing(model, null, 0f, delta, world);
    }

    /// the same, on top of a clip instead of the rest pose: the clip moves a bone, the body
    /// then moves it further by its limb's delta. null tracks is the rest pose
    public static void samplePoseFollowing(KodelModel model, ResolvedTracks tracks, float t,
                                           java.util.function.Function<String, float[]> delta,
                                           float[] world) {
        int n = model.bones.size();
        Quaternionf q = new Quaternionf();
        Quaternionf d = new Quaternionf();
        Matrix4f local = new Matrix4f();
        Matrix4f tmp = new Matrix4f();
        float[] sampled = new float[3];

        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            float[] move = delta == null ? null : delta.apply(b.name);
            if (tracks != null && tracks.rotation[i] != null) {
                sample(tracks.rotation[i], t, sampled);
                quatFromEuler(sampled[0], sampled[1], sampled[2], q);
            } else {
                q.set(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
            }
            float px = b.position[0], py = b.position[1], pz = b.position[2];
            if (tracks != null && tracks.position[i] != null) {
                sample(tracks.position[i], t, sampled);
                px = sampled[0]; py = sampled[1]; pz = sampled[2];
            }
            if (move != null && move.length >= 6) {
                quatFromEuler(move[0], move[1], move[2], d);
                q.mul(d).normalize();
                px += move[3];
                py += move[4];
                pz += move[5];
            }
            compose(local, tmp, b, px, py, pz, q.x, q.y, q.z, q.w,
                b.scale[0], b.scale[1], b.scale[2]);
            store(model, world, i, b, local);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void rotationOf(KodelModel.KodelBone b, ResolvedTracks tracks, int i, float t,
                                   float[] scratch, Quaternionf out) {
        if (tracks != null && tracks.rotation[i] != null) {
            sample(tracks.rotation[i], t, scratch);
            quatFromEuler(scratch[0], scratch[1], scratch[2], out);
        } else {
            out.set(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
        }
    }

    private static void positionOf(KodelModel.KodelBone b, ResolvedTracks tracks, int i, float t, float[] out) {
        if (tracks != null && tracks.position[i] != null) {
            sample(tracks.position[i], t, out);
        } else {
            System.arraycopy(b.position, 0, out, 0, 3);
        }
    }

    private static void scaleOf(KodelModel.KodelBone b, ResolvedTracks tracks, int i, float t, float[] out) {
        if (tracks != null && tracks.scale[i] != null) {
            sample(tracks.scale[i], t, out);
        } else {
            System.arraycopy(b.scale, 0, out, 0, 3);
        }
    }

    private static void compose(Matrix4f local, Matrix4f tmp, KodelModel.KodelBone b,
                                float px, float py, float pz,
                                float qx, float qy, float qz, float qw,
                                float sx, float sy, float sz) {
        local.translation(px, py, pz);
        tmp.translation(b.pivot[0], b.pivot[1], b.pivot[2]);
        local.mul(tmp);
        tmp.rotation(safeQuat(qx, qy, qz, qw));
        local.mul(tmp);
        tmp.scaling(sx, sy, sz);
        local.mul(tmp);
        tmp.translation(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
        local.mul(tmp);
    }

    // a quaternion of zero length normalises to NaN, and one NaN bone takes every bone
    // under it with it. a corrupt file should cost that bone its rotation, nothing more
    private static Quaternionf safeQuat(float x, float y, float z, float w) {
        float len = x * x + y * y + z * z + w * w;
        if (len < 1e-12f || Float.isNaN(len)) return new Quaternionf();
        return new Quaternionf(x, y, z, w).normalize();
    }

    private static void store(KodelModel model, float[] world, int i, KodelModel.KodelBone b, Matrix4f local) {
        int at = i * MAT4_FLOATS;
        if (b.parent >= 0) {
            mult(world, b.parent * MAT4_FLOATS, local, world, at);
        } else {
            local.get(world, at);
        }
    }

    public static void samplePose(KodelModel model, ResolvedTracks tracks, float t,
                                  float[] bonePos, float[] boneRot, float[] boneScale,
                                  float[] world) {
        int n = model.bones.size();
        Matrix4f tmpPivot = new Matrix4f();
        Matrix4f tmpRot = new Matrix4f();
        Matrix4f tmpScale = new Matrix4f();
        for (int i = 0; i < n; i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            float qx, qy, qz, qw;
            if (tracks != null && tracks.rotation[i] != null) {
                sample(tracks.rotation[i], t, boneRot);
                Quaternionf q = new Quaternionf();
                quatFromEuler(boneRot[0], boneRot[1], boneRot[2], q);
                qx = q.x;
                qy = q.y;
                qz = q.z;
                qw = q.w;
            } else {
                qx = b.rotation[0];
                qy = b.rotation[1];
                qz = b.rotation[2];
                qw = b.rotation[3];
            }
            float px, py, pz;
            if (tracks != null && tracks.position[i] != null) {
                sample(tracks.position[i], t, bonePos);
                px = bonePos[0];
                py = bonePos[1];
                pz = bonePos[2];
            } else {
                px = b.position[0];
                py = b.position[1];
                pz = b.position[2];
            }
            float sx, sy, sz;
            if (tracks != null && tracks.scale[i] != null) {
                sample(tracks.scale[i], t, boneScale);
                sx = boneScale[0];
                sy = boneScale[1];
                sz = boneScale[2];
            } else {
                sx = b.scale[0];
                sy = b.scale[1];
                sz = b.scale[2];
            }
            Matrix4f local = new Matrix4f();
            local.translation(px, py, pz);
            tmpPivot.translation(b.pivot[0], b.pivot[1], b.pivot[2]);
            local.mul(tmpPivot);
            tmpRot.rotation(safeQuat(qx, qy, qz, qw));
            local.mul(tmpRot);
            // scaling() SETS, scale() multiplies into whatever is already there. this
            // was scale() on a matrix reused across bones, so every bone inherited the
            // previous one's scale and a scaled bone squared its own. rust was right
            tmpScale.scaling(sx, sy, sz);
            local.mul(tmpScale);
            tmpPivot.translation(-b.pivot[0], -b.pivot[1], -b.pivot[2]);
            local.mul(tmpPivot);
            int at = i * MAT4_FLOATS;
            if (b.parent >= 0) {
                int pa = b.parent * MAT4_FLOATS;
                mult(world, pa, local, world, at);
            } else {
                local.get(world, at);
            }
        }
    }

    private static void mult(float[] a, int aAt, Matrix4f b, float[] out, int outAt) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float acc = 0;
                for (int k = 0; k < 4; k++) {
                    acc += a[aAt + k * 4 + j] * b.get(i, k);
                }
                out[outAt + i * 4 + j] = acc;
            }
        }
    }
}