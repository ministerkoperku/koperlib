package com.koper.koper_lib.kodel;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Panama bridge to the native Kodel engine. Owns the fast path: binary parse,
 * animation keyframe solve and bone sampling all happen in Rust, Java reads the
 * matrices back into its own buffers. When the native library is missing the
 * callers fall back to {@link KodelSampler}, so a missing .so never breaks the
 * renderer — it just gets a bit slower.
 */
public final class KodelBridge {
    private static final FunctionDescriptor LOAD_MODEL =
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor LOAD_ANIMS =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor SAMPLE =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_FLOAT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor COUNT =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    private static final FunctionDescriptor FREE =
        FunctionDescriptor.ofVoid(JAVA_LONG);

    private KodelBridge() {}

    public static boolean available() {
        return KodelNative.isLoaded();
    }

    /** Loads a raw model.bin into the native store. Returns a handle or 0. */
    public static long loadModel(byte[] model) {
        if (!available() || model == null || model.length == 0) return 0;
        MethodHandle fn = KodelNative.function("kodel_load_model", LOAD_MODEL);
        if (fn == null) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(model.length, 1);
            data.copyFrom(MemorySegment.ofArray(model));
            return (long) fn.invoke(data, model.length);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Binds a raw animation.anim.bin to a loaded model handle. 0 = ok. */
    public static int loadAnimations(long handle, byte[] animations) {
        if (!available() || handle == 0 || animations == null || animations.length == 0) return -1;
        MethodHandle fn = KodelNative.function("kodel_load_animations", LOAD_ANIMS);
        if (fn == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(animations.length, 1);
            data.copyFrom(MemorySegment.ofArray(animations));
            return (int) fn.invoke(handle, data, animations.length);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int boneCount(long handle) {
        if (!available() || handle == 0) return 0;
        MethodHandle fn = KodelNative.function("kodel_bone_count", COUNT);
        if (fn == null) return 0;
        try {
            return (int) fn.invoke(handle);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Samples clip {@code animIdx} at {@code t} (already loop-folded) into {@code outMats}. */
    public static int sample(long handle, int animIdx, float t, float[] outMats) {
        if (!available() || handle == 0 || outMats == null || outMats.length == 0) return -1;
        MethodHandle fn = KodelNative.function("kodel_sample", SAMPLE);
        if (fn == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_FLOAT, outMats.length);
            int rc = (int) fn.invoke(handle, animIdx, t, out, outMats.length);
            if (rc != 0) return rc;
            MemorySegment.copy(out, JAVA_FLOAT, 0, outMats, 0, outMats.length);
            return 0;
        } catch (Throwable e) {
            return -1;
        }
    }

    public static void free(long handle) {
        if (!available() || handle == 0) return;
        MethodHandle fn = KodelNative.function("kodel_free", FREE);
        if (fn == null) return;
        try {
            fn.invoke(handle);
        } catch (Throwable ignored) {}
    }

    public static void clear() {
        if (!available()) return;
        MethodHandle fn = KodelNative.function("kodel_clear",
            FunctionDescriptor.ofVoid());
        if (fn == null) return;
        try {
            fn.invoke();
        } catch (Throwable ignored) {}
    }
}