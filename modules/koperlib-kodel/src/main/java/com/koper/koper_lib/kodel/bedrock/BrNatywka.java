package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kodel.KodelNative;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

// panama side of kodel_br_*. every buffer lives in the global arena and grows, a mob costs no
// allocation per frame. render thread only, which is the only place these get called from
final class BrNatywka {

    private static final MethodHandle DEFINE = KodelNative.function("kodel_br_define",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle DESCRIBE = KodelNative.function("kodel_br_describe",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle UNDEFINE = KodelNative.function("kodel_br_undefine",
        FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle SPAWN = KodelNative.function("kodel_br_spawn",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle FREE = KodelNative.function("kodel_br_free",
        FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle TICK = KodelNative.function("kodel_br_tick",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT,
            ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    private static final MethodHandle OWNER = KodelNative.function("kodel_br_owner",
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));

    // holder of an attachable for the next tick, c.owning_entity->v.x reads its variables
    static void owner(long inst, long owner) {
        if (OWNER == null || inst == 0) return;
        try { OWNER.invoke(inst, owner); } catch (Throwable ignored) {}
    }

    private static final MethodHandle VARS = KodelNative.function("kodel_br_vars",
        FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    private static MemorySegment vIds = Arena.global().allocate(64, 8), vVals = Arena.global().allocate(64, 8);

    // engine side variables for the next tick (v.attack_time and friends)
    static void vars(long inst, int[] ids, float[] vals, int n) {
        if (VARS == null || inst == 0 || n == 0) return;
        vIds = grow(vIds, n * 4L);
        vVals = grow(vVals, n * 4L);
        MemorySegment.copy(ids, 0, vIds, JAVA_INT, 0, n);
        MemorySegment.copy(vals, 0, vVals, JAVA_FLOAT, 0, n);
        try { VARS.invoke(inst, vIds, vVals, n); } catch (Throwable ignored) {}
    }

    private static final MethodHandle PARENT = KodelNative.function("kodel_br_parent_setup",
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));

    // an attachable's parent_setup onto its holder, right after the attachable ticked
    static void parentSetup(long inst, long owner) {
        if (PARENT == null || inst == 0 || owner == 0) return;
        try { PARENT.invoke(inst, owner); } catch (Throwable ignored) {}
    }

    private static final MethodHandle RC_MATS = KodelNative.function("kodel_br_rc_materials",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static MemorySegment rcm = Arena.global().allocate(256, 8);

    // what render controller rc's materials entries picked last tick, string ids in list order
    static int rcMaterials(long inst, int rc, int[] out) {
        if (RC_MATS == null || inst == 0) return 0;
        rcm = grow(rcm, out.length * 4L);
        try {
            int n = (int) RC_MATS.invoke(inst, rc, rcm, out.length);
            MemorySegment.copy(rcm, JAVA_INT, 0, out, 0, Math.min(n, out.length));
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final MethodHandle VAR_GET = KodelNative.function("kodel_br_var_get",
        FunctionDescriptor.of(JAVA_FLOAT, JAVA_LONG, JAVA_INT));

    static float varGet(long inst, int i) {
        try { return VAR_GET == null ? Float.NaN : (float) VAR_GET.invoke(inst, i); } catch (Throwable t) { return Float.NaN; }
    }

    private static final MethodHandle RC_TEX = KodelNative.function("kodel_br_rc_textures",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static MemorySegment rct = Arena.global().allocate(256, 8);

    // every texture render controller rc picked last tick, def texture indices
    static int rcTextures(long inst, int rc, int[] out) {
        if (RC_TEX == null || inst == 0) return 0;
        rct = grow(rct, out.length * 4L);
        try {
            int n = (int) RC_TEX.invoke(inst, rc, rct, out.length);
            MemorySegment.copy(rct, JAVA_INT, 0, out, 0, Math.min(n, out.length));
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final MethodHandle PLAY = KodelNative.function("kodel_br_play",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_FLOAT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    // entity.playAnimation on one actor: true when its def has that animation and it started
    static boolean play(long inst, String anim, float blendOut, String stop, String ctrl) {
        if (PLAY == null || inst == 0 || anim == null) return false;
        try (Arena a = Arena.ofConfined()) {
            byte[] an = anim.getBytes(StandardCharsets.UTF_8);
            byte[] st = stop == null ? new byte[0] : stop.getBytes(StandardCharsets.UTF_8);
            byte[] ct = ctrl == null ? new byte[0] : ctrl.getBytes(StandardCharsets.UTF_8);
            MemorySegment ma = a.allocate(Math.max(1, an.length)), ms = a.allocate(Math.max(1, st.length)), mc = a.allocate(Math.max(1, ct.length));
            MemorySegment.copy(an, 0, ma, JAVA_BYTE, 0, an.length);
            MemorySegment.copy(st, 0, ms, JAVA_BYTE, 0, st.length);
            MemorySegment.copy(ct, 0, mc, JAVA_BYTE, 0, ct.length);
            return (int) PLAY.invoke(inst, ma, an.length, blendOut, ms, st.length, mc, ct.length) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean ready() {
        return KodelNative.isLoaded() && DEFINE != null && TICK != null;
    }

    static long define(String json) {
        try (Arena a = Arena.ofConfined()) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            MemorySegment seg = a.allocate(b.length);
            MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
            return (long) DEFINE.invoke(seg, b.length);
        } catch (Throwable t) {
            return 0L;
        }
    }

    static JsonObject describe(long def) {
        try (Arena a = Arena.ofConfined()) {
            int cap = 1 << 16;
            for (int tries = 0; tries < 2; tries++) {
                MemorySegment out = a.allocate(cap);
                int n = (int) DESCRIBE.invoke(def, out, cap);
                if (n < 0) return null;
                if (n <= cap) return JsonParser.parseString(new String(out.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8)).getAsJsonObject();
                cap = n;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static void undefine(long def) {
        try { UNDEFINE.invoke(def); } catch (Throwable ignored) {}
    }

    static long spawn(long def, int seed) {
        try { return (long) SPAWN.invoke(def, seed); } catch (Throwable t) { return 0L; }
    }

    static void free(long inst) {
        try { FREE.invoke(inst); } catch (Throwable ignored) {}
    }

    // grown on demand, reused for every mob every frame
    private static MemorySegment cxs = MemorySegment.NULL, cx = MemorySegment.NULL, q = MemorySegment.NULL, qs = MemorySegment.NULL, mats = MemorySegment.NULL,
        vis = MemorySegment.NULL, local = MemorySegment.NULL, info = MemorySegment.NULL, events = MemorySegment.NULL;

    private static MemorySegment grow(MemorySegment seg, long bytes) {
        if (seg.byteSize() >= bytes) return seg;
        return Arena.global().allocate(Math.max(bytes, seg.byteSize() * 2), 8);
    }

    private static final MethodHandle LAYERS = KodelNative.function("kodel_br_layers",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static MemorySegment lay = Arena.global().allocate(96, 8);

    // render controllers that drew last tick into out as {controller, texture, geometry} triples
    static int layers(long inst, int[] out) {
        int max = out.length / 3;
        lay = grow(lay, Math.max(12, max * 12L));
        try {
            int n = (int) LAYERS.invoke(inst, lay, max);
            MemorySegment.copy(lay, JAVA_INT, 0, out, 0, Math.min(n, max) * 3);
            return Math.min(n, max);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final MethodHandle LAYER_VIS = KodelNative.function("kodel_br_layer_vis",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static MemorySegment lvis = Arena.global().allocate(256, 8);

    // one render controller's own part_visibility (a byte per bone, 1 shown), the bone count or 0
    static int layerVis(long inst, int rc, byte[] out) {
        if (LAYER_VIS == null) return 0;
        lvis = grow(lvis, Math.max(16, out.length));
        try {
            int n = (int) LAYER_VIS.invoke(inst, rc, lvis, out.length);
            MemorySegment.copy(lvis, JAVA_BYTE, 0, out, 0, Math.min(n, out.length));
            return Math.min(n, out.length);
        } catch (Throwable t) {
            return 0;
        }
    }

    // fills out.* from the native tick, false when the native said no
    static boolean tick(long inst, float[] queries, int[] qstr, float dt, BrKlatka out, boolean wantLocal) {
        return tick(inst, queries, qstr, new float[0], new int[0], dt, out, wantLocal);
    }

    static boolean tick(long inst, float[] queries, int[] qstr, float[] context, int[] cstr, float dt, BrKlatka out, boolean wantLocal) {
        int nq = queries.length;
        cx = grow(cx, Math.max(4, context.length * 4L));
        cxs = grow(cxs, Math.max(4, context.length * 4L));
        if (context.length > 0) {
            MemorySegment.copy(context, 0, cx, JAVA_FLOAT, 0, context.length);
            MemorySegment.copy(cstr, 0, cxs, JAVA_INT, 0, context.length);
        }
        int bones = out.bones;
        q = grow(q, Math.max(4, nq * 4L));
        qs = grow(qs, Math.max(4, nq * 4L));
        mats = grow(mats, Math.max(64, bones * 64L));
        vis = grow(vis, Math.max(8, bones));
        local = grow(local, Math.max(36, bones * 36L));
        info = grow(info, 32);
        events = grow(events, 64);
        MemorySegment.copy(queries, 0, q, JAVA_FLOAT, 0, nq);
        MemorySegment.copy(qstr, 0, qs, JAVA_INT, 0, nq);
        try {
            int n = (int) TICK.invoke(inst, q, qs, nq, cx, cxs, context.length, dt, mats, bones * 16, vis, bones,
                wantLocal ? local : MemorySegment.NULL, bones * 9, info, events, 16);
            if (n < 0) return false;
        } catch (Throwable t) {
            return false;
        }
        MemorySegment.copy(mats, JAVA_FLOAT, 0, out.mats, 0, bones * 16);
        MemorySegment.copy(vis, JAVA_BYTE, 0, out.vis, 0, bones);
        if (wantLocal) MemorySegment.copy(local, JAVA_FLOAT, 0, out.local, 0, bones * 9);
        MemorySegment.copy(info, JAVA_INT, 0, out.info, 0, 8);
        int ev = Math.min(out.info[3], 16);
        MemorySegment.copy(events, JAVA_INT, 0, out.events, 0, ev);
        return true;
    }
}
