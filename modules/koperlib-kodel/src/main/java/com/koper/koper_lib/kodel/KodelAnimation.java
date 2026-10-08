package com.koper.koper_lib.kodel;

import java.util.ArrayList;
import java.util.List;

import static com.koper.koper_lib.kodel.KodelFormat.EASE_BEZIER;
import static com.koper.koper_lib.kodel.KodelFormat.EASE_LINEAR;
import static com.koper.koper_lib.kodel.KodelFormat.EASE_STEP;

/**
 * .kodel animation container: named clips with per-bone tracks. Rotation keys
 * are radians, position/scale in model units. Easing is linear/step/bezier; a
 * bezier keyframe with zero control points reads as automatic Catmull-Rom
 * smoothing in {@link KodelSampler}, matching Blockbench "smooth" keyframes.
 */
public final class KodelAnimation {
    public String name = "animation";
    public float length = 1f;
    public boolean loop;
    public final List<KodelTrack> tracks = new ArrayList<>();

    public static final class KodelTrack {
        public String bone;
        public KodelChannel rotation;
        public KodelChannel position;
        public KodelChannel scale;

        public boolean hasChannels() {
            return rotation != null || position != null || scale != null;
        }
    }

    /** One animatable channel. Flat parallel arrays, sorted by time. */
    public static final class KodelChannel {
        public int count;
        public float[] times;
        public float[] values; // 3 per key
        public int[] easing;   // EASE_*
        public float[] ctrlA;  // 3 per key, meaningful only for bezier
        public float[] ctrlB;

        public static KodelChannel empty() {
            return new KodelChannel();
        }

        public KodelChannel set(float[] times, float[] values, int[] easing, float[] ctrlA, float[] ctrlB) {
            this.count = times.length;
            this.times = times;
            this.values = values;
            this.easing = easing;
            this.ctrlA = ctrlA;
            this.ctrlB = ctrlB;
            return this;
        }
    }

    // smallest on-disk size of each record, used to reject impossible counts
    private static final int ANIM_MIN_BYTES = 12;
    private static final int TRACK_MIN_BYTES = 3;
    private static final int KEY_MIN_BYTES = 17;

    public KodelTrack track(String bone) {
        for (KodelTrack t : tracks) {
            if (t.bone.equals(bone)) return t;
        }
        return null;
    }

    public static KodelAnimation read(byte[] data) {
        return readAll(data).get(0);
    }

    /** Reads the whole .anim.bin, which can hold several clips. */
    public static List<KodelAnimation> readAll(byte[] data) {
        KodelFormat.Reader r = new KodelFormat.Reader(data);
        KodelFormat.header(r);
        List<KodelAnimation> out = new ArrayList<>();
        int animCount = r.count(ANIM_MIN_BYTES, "animations");
        for (int i = 0; i < animCount; i++) {
            KodelAnimation anim = new KodelAnimation();
            anim.name = r.string();
            anim.length = r.f32();
            anim.loop = r.u8() != 0;
            r.u8(); // flags
            int trackCount = r.count(TRACK_MIN_BYTES, "tracks");
            for (int t = 0; t < trackCount; t++) {
                KodelTrack track = new KodelTrack();
                track.bone = r.string();
                int channels = r.u8();
                if ((channels & 1) != 0) track.rotation = readChannel(r);
                if ((channels & 2) != 0) track.position = readChannel(r);
                if ((channels & 4) != 0) track.scale = readChannel(r);
                if (track.hasChannels()) anim.tracks.add(track);
            }
            out.add(anim);
        }
        if (r.u32() != KodelFormat.ENDK) throw new KodelFormat.Corruption("animation.anim.bin corrupt (no ENDK)");
        return out;
    }

    private static KodelChannel readChannel(KodelFormat.Reader r) {
        int n = r.count(KEY_MIN_BYTES, "keyframes");
        KodelChannel c = new KodelChannel();
        c.count = n;
        c.times = new float[n];
        c.values = new float[n * 3];
        c.easing = new int[n];
        c.ctrlA = new float[n * 3];
        c.ctrlB = new float[n * 3];
        for (int i = 0; i < n; i++) {
            c.times[i] = r.f32();
            c.easing[i] = r.u8();
            r.vec3(c.values, i * 3);
            if (c.easing[i] == EASE_BEZIER) {
                r.vec3(c.ctrlA, i * 3);
                r.vec3(c.ctrlB, i * 3);
            }
        }
        return c;
    }

    /** Full anim file with N clips — the .anim.bin format holds a list. */
    public static byte[] write(List<KodelAnimation> anims) {
        KodelFormat.Writer w = new KodelFormat.Writer();
        KodelFormat.header(w);
        w.u32(anims.size());
        for (KodelAnimation anim : anims) {
            w.string(anim.name);
            w.f32(anim.length);
            w.u8(anim.loop ? 1 : 0);
            w.u8(0);
            w.u32(anim.tracks.size());
            for (KodelTrack track : anim.tracks) {
                w.string(track.bone);
                int ch = 0;
                if (track.rotation != null) ch |= 1;
                if (track.position != null) ch |= 2;
                if (track.scale != null) ch |= 4;
                w.u8(ch);
                if (track.rotation != null) writeChannel(w, track.rotation);
                if (track.position != null) writeChannel(w, track.position);
                if (track.scale != null) writeChannel(w, track.scale);
            }
        }
        w.u32(KodelFormat.ENDK);
        return w.bytes();
    }

    private static void writeChannel(KodelFormat.Writer w, KodelChannel c) {
        w.u32(c.count);
        for (int i = 0; i < c.count; i++) {
            w.f32(c.times[i]);
            w.u8(c.easing[i] & 3);
            w.vec3(c.values, i * 3);
            if (c.easing[i] == EASE_BEZIER) {
                w.vec3(c.ctrlA, i * 3);
                w.vec3(c.ctrlB, i * 3);
            }
        }
    }
}