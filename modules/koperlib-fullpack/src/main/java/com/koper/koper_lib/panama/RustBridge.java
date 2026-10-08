package com.koper.koper_lib.panama;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.core.KoperModuleNative;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.*;

// all rust calls in one place — add method = done, no handle field bullshit
public final class RustBridge {

    private static final KoperModuleNative NATIVE = KoperModuleNative.load(
        "fullpack", "koperlib_fullpack_engine", RustBridge.class);
    public static final boolean LOADED = NATIVE.loaded();
    private static final AtomicBoolean FLAT_SAMPLE_RAW_ERROR_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean FLAT_SAMPLE_BATCH_ERROR_LOGGED = new AtomicBoolean();

    // reused descriptors, named as retType_paramTypes so i dont go insane
    private static final FunctionDescriptor
        RET_INT           = FunctionDescriptor.of(JAVA_INT),
        RET_LONG          = FunctionDescriptor.of(JAVA_LONG),
        VOID_LONG         = FunctionDescriptor.ofVoid(JAVA_LONG),
        INT_PTR_LONG      = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG),
        INT_LONG_PTR      = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS),
        INT_LONG          = FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
        LONG_PTR          = FunctionDescriptor.of(JAVA_LONG, ADDRESS),
        INT_LONG_PTR_LONG = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG);

    private RustBridge() {}

    public static boolean isLoaded() { return LOADED; }

    // look up rust symbol on first call, cache it — null if DLL missing or symbol not found
    public static MethodHandle fn(String name, FunctionDescriptor desc) {
        return NATIVE.function(name, desc);
    }

    // ── Core ─────────────────────────────────────────────────────────────────

    public static int init() {
        var h = fn("koper_init", RET_INT);
        if (h == null) return 0;
        try { return (int) h.invoke(); }
        catch (Throwable e) { KoperLib.LOGGER.error("[KoperLib] init failed", e); return -1; }
    }

    public static String version() {
        var h = fn("koper_version", INT_PTR_LONG);
        if (h == null) return "java-only";
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(32);
            return (int) h.invoke(buf, 32L) == 0 ? buf.getString(0) : "unknown";
        } catch (Throwable e) { return "error"; }
    }

    // ── Scripting ────────────────────────────────────────────────────────────

    // built once, lives in global arena — never freed, that's fine
    private static final MemorySegment CMD_UPCALL_STUB = buildCmdUpcallStub();
    private static final MemorySegment QUERY_UPCALL_STUB = buildQueryUpcallStub();

    private static MemorySegment buildCmdUpcallStub() {
        try {
            var mh = MethodHandles.lookup().findStatic(
                com.koper.koper_lib.scripting.ScriptCommandDispatcher.class,
                "dispatchUpcall",
                MethodType.methodType(void.class, MemorySegment.class, int.class));
            return Linker.nativeLinker().upcallStub(
                mh, FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), Arena.global());
        } catch (Exception e) {
            // this failing means all script commands go through drain instead — slower but works
            KoperLib.LOGGER.error("[Bridge] upcall stub failed, falling back to queue drain", e);
            return MemorySegment.NULL;
        }
    }

    private static MemorySegment buildQueryUpcallStub() {
        try {
            var mh = MethodHandles.lookup().findStatic(
                com.koper.koper_lib.scripting.LuaAddonRegistry.class,
                "dispatchQuery",
                MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class, int.class));
            return Linker.nativeLinker().upcallStub(
                mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), Arena.global());
        } catch (Exception e) {
            KoperLib.LOGGER.error("[Bridge] lua addon query upcall stub failed", e);
            return MemorySegment.NULL;
        }
    }

    // true = Rust calls dispatchUpcall synchronously per command, no drain needed
    public static boolean isUpcallActive() { return CMD_UPCALL_STUB != MemorySegment.NULL; }

    public static long scriptCreateVm() {
        var h = fn("koper_script_create_vm", RET_LONG);
        if (h == null) return 0L;
        try {
            long handle = (long) h.invoke();
            if (handle != 0L) {
                scriptSetTimeout(handle, com.koper.koper_lib.fullpack.config.FullpackConfig.get().scriptTimeoutMs);
            }
            if (handle != 0L && CMD_UPCALL_STUB != MemorySegment.NULL) {
                scriptSetUpcall(handle, CMD_UPCALL_STUB);
            }
            if (handle != 0L && QUERY_UPCALL_STUB != MemorySegment.NULL) {
                scriptSetQueryUpcall(handle, QUERY_UPCALL_STUB);
            }
            return handle;
        } catch (Throwable e) { KoperLib.LOGGER.error("[Script] create_vm failed", e); return 0L; }
    }

    // wall-clock kill switch enforced by an instruction hook inside the VM. 0 = unlimited
    public static void scriptSetTimeout(long handle, long ms) {
        var h = fn("koper_script_set_timeout", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        if (h == null || handle == 0L) return;
        try { h.invoke(handle, ms); }
        catch (Throwable e) { KoperLib.LOGGER.warn("[Script] set_timeout failed: {}", e.getMessage()); }
    }

    public static void scriptSetUpcall(long handle, MemorySegment stub) {
        var h = fn("koper_script_set_upcall", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        if (h == null || handle == 0L) return;
        try { h.invoke(handle, stub.address()); }
        catch (Throwable e) { KoperLib.LOGGER.warn("[Script] set_upcall failed: {}", e.getMessage()); }
    }

    public static void scriptSetQueryUpcall(long handle, MemorySegment stub) {
        var h = fn("koper_script_set_query_upcall", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        if (h == null || handle == 0L) return;
        try { h.invoke(handle, stub.address()); }
        catch (Throwable e) { KoperLib.LOGGER.warn("[Script] set_query_upcall failed: {}", e.getMessage()); }
    }

    public static void scriptDestroyVm(long handle) {
        var h = fn("koper_script_destroy_vm", VOID_LONG);
        if (h == null || handle == 0L) return;
        try { h.invoke(handle); }
        catch (Throwable e) { KoperLib.LOGGER.error("[Script] destroy_vm failed", e); }
    }

    public static boolean scriptLoad(long handle, String path) {
        var h = fn("koper_script_load", INT_LONG_PTR);
        if (h == null || handle == 0L) return false;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(handle, a.allocateFrom(path)) == 0;
        } catch (Throwable e) { KoperLib.LOGGER.error("[Script] load failed: {}", path, e); return false; }
    }

    // per-thread exec buffer: one allocation per thread for the whole game session
    // grows if a script somehow produces a multi-MB Lua snippet (shouldn't happen but whatever)
    private static final ThreadLocal<MemorySegment> EXEC_BUF =
        ThreadLocal.withInitial(() -> Arena.global().allocate(64 * 1024));

    public static boolean scriptExec(long handle, String code) {
        var h = fn("koper_script_exec", INT_LONG_PTR);
        if (h == null || handle == 0L) return false;
        try {
            byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
            int needed = bytes.length + 1;
            MemorySegment buf = EXEC_BUF.get();
            if (needed > buf.byteSize()) {
                // grow — happens basically never in practice
                buf = Arena.global().allocate(needed + 8192);
                EXEC_BUF.set(buf);
            }
            MemorySegment.copy(bytes, 0, buf, JAVA_BYTE, 0, bytes.length);
            buf.set(JAVA_BYTE, bytes.length, (byte) 0);
            return (int) h.invoke(handle, buf) == 0;
        } catch (Throwable e) { KoperLib.LOGGER.error("[Script] exec failed", e); return false; }
    }

    // fallback for when upcall stub couldn't be built — drains the command queue manually
    // normally not called at all (upcall fires synchronously during scriptExec)
    public static String scriptDrainCommands(long handle) {
        var h = fn("koper_script_drain_commands", INT_LONG_PTR_LONG);
        if (h == null || handle == 0L) return "";
        try (Arena a = Arena.ofConfined()) {
            int bufLen = 65536;
            MemorySegment buf = a.allocate(bufLen);
            int written = (int) h.invoke(handle, buf, (long) bufLen);
            if (written <= 0) return "";
            return new String(buf.asSlice(0, written).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
        } catch (Throwable e) { KoperLib.LOGGER.error("[Script] drain_commands failed", e); return ""; }
    }

    // ── Bedrock addon scripts (koper_js_*, quickjs) ──────────────────────────

    private static final MemorySegment JS_QUERY_STUB = buildJsQueryStub();

    private static MemorySegment buildJsQueryStub() {
        try {
            var mh = MethodHandles.lookup().findStatic(
                com.koper.koper_lib.bedrock.BedrockPytajnik.class,
                "dispatchQuery",
                MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class, int.class));
            return Linker.nativeLinker().upcallStub(
                mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), Arena.global());
        } catch (Exception e) {
            KoperLib.LOGGER.error("[Bridge] bedrock js query stub failed, addon scripts will be deaf", e);
            return MemorySegment.NULL;
        }
    }

    public static long jsCreate(String scriptsRoot) {
        var h = fn("koper_js_create", LONG_PTR);
        if (h == null) return 0L;
        try (Arena a = Arena.ofConfined()) { return (long) h.invoke(a.allocateFrom(scriptsRoot)); }
        catch (Throwable e) { KoperLib.LOGGER.error("[Bedrock] js create failed", e); return 0L; }
    }

    public static void jsDestroy(long handle) {
        var h = fn("koper_js_destroy", VOID_LONG);
        if (h == null || handle == 0L) return;
        try { h.invoke(handle); }
        catch (Throwable e) { KoperLib.LOGGER.error("[Bedrock] js destroy failed", e); }
    }

    public static void jsSetTimeout(long handle, long ms) {
        var h = fn("koper_js_set_timeout", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        if (h == null || handle == 0L) return;
        try { h.invoke(handle, ms); }
        catch (Throwable e) { KoperLib.LOGGER.warn("[Bedrock] js set_timeout failed: {}", e.getMessage()); }
    }

    public static void jsSetQuery(long handle) {
        var h = fn("koper_js_set_query", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        if (h == null || handle == 0L || JS_QUERY_STUB == MemorySegment.NULL) return;
        try { h.invoke(handle, JS_QUERY_STUB.address()); }
        catch (Throwable e) { KoperLib.LOGGER.warn("[Bedrock] js set_query failed: {}", e.getMessage()); }
    }

    public static boolean jsLoad(long handle, String entry) {
        var h = fn("koper_js_load", INT_LONG_PTR);
        if (h == null || handle == 0L) return false;
        try (Arena a = Arena.ofConfined()) { return (int) h.invoke(handle, a.allocateFrom(entry)) == 0; }
        catch (Throwable e) { KoperLib.LOGGER.error("[Bedrock] js load failed: {}", entry, e); return false; }
    }

    private static final ThreadLocal<MemorySegment> JS_OUT =
        ThreadLocal.withInitial(() -> Arena.global().allocate(16 * 1024));

    // answer comes back in the same call; a big one gets fetched again through koper_js_last
    public static String jsCall(long handle, String json) {
        var h = fn("koper_js_call", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
        if (h == null || handle == 0L) return "";
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = JS_OUT.get();
            int n = (int) h.invoke(handle, a.allocateFrom(json), out, out.byteSize());
            if (n == -3) {
                KoperLib.LOGGER.error("[Bedrock] a script was called into while it was already running, the call was dropped: {}",
                    json.length() > 160 ? json.substring(0, 160) : json);
                return "";
            }
            if (n <= 0) return "";
            if (n > out.byteSize()) {
                out = Arena.global().allocate(n + 4096L);
                JS_OUT.set(out);
                var last = fn("koper_js_last", INT_LONG_PTR_LONG);
                if (last == null) return "";
                n = (int) last.invoke(handle, out, out.byteSize());
                if (n <= 0 || n > out.byteSize()) return "";
            }
            return new String(out.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
        } catch (Throwable e) { KoperLib.LOGGER.error("[Bedrock] js call failed", e); return ""; }
    }

    // ── behavior pack animations and controllers (koper_bp_*) ───────────────

    public static long bpDefine(String json) {
        var h = fn("koper_bp_define", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        if (h == null) return 0L;
        try (Arena a = Arena.ofConfined()) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            MemorySegment in = a.allocate(Math.max(1, b.length));
            MemorySegment.copy(b, 0, in, JAVA_BYTE, 0, b.length);
            return (long) h.invoke(in, (long) b.length);
        } catch (Throwable e) { KoperLib.LOGGER.warn("[Bedrock] bp define failed: {}", e.toString()); return 0L; }
    }

    // {"queries": [..], "strings": [..], "fired": [..], "errors": [..]}
    public static com.google.gson.JsonObject bpDescribe(long def) {
        var h = fn("koper_bp_describe", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG));
        if (h == null || def == 0L) return null;
        try (Arena a = Arena.ofConfined()) {
            long cap = 16 * 1024;
            for (int tries = 0; tries < 2; tries++) {
                MemorySegment out = a.allocate(cap);
                int n = (int) h.invoke(def, out, cap);
                if (n < 0) return null;
                if (n <= cap) return com.google.gson.JsonParser.parseString(new String(out.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8)).getAsJsonObject();
                cap = n;
            }
        } catch (Throwable e) { KoperLib.LOGGER.warn("[Bedrock] bp describe failed: {}", e.toString()); }
        return null;
    }

    public static void bpUndefine(long def) {
        var h = fn("koper_bp_undefine", VOID_LONG);
        if (h == null || def == 0L) return;
        try { h.invoke(def); } catch (Throwable ignored) {}
    }

    public static long bpSpawn(long def, int seed) {
        var h = fn("koper_bp_spawn", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        if (h == null || def == 0L) return 0L;
        try { return (long) h.invoke(def, seed); } catch (Throwable e) { return 0L; }
    }

    public static void bpFree(long inst) {
        var h = fn("koper_bp_free", VOID_LONG);
        if (h == null || inst == 0L) return;
        try { h.invoke(inst); } catch (Throwable ignored) {}
    }

    private static final ThreadLocal<MemorySegment[]> BP_BUF = ThreadLocal.withInitial(() -> new MemorySegment[] {
        Arena.global().allocate(256 * 4L, 4), Arena.global().allocate(256 * 4L, 4), Arena.global().allocate(64 * 4L, 4)});

    // one tick; fired gets indices into describe's "fired", returns how many (capped at fired.length)
    public static int bpTick(long inst, float[] q, int[] qstr, float dt, int[] fired) {
        var h = fn("koper_bp_tick", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT, ADDRESS, JAVA_INT));
        if (h == null || inst == 0L) return 0;
        MemorySegment[] buf = BP_BUF.get();
        int n = q.length;
        if (buf[0].byteSize() < n * 4L) { buf[0] = Arena.global().allocate(n * 4L + 64, 4); buf[1] = Arena.global().allocate(n * 4L + 64, 4); }
        if (buf[2].byteSize() < fired.length * 4L) buf[2] = Arena.global().allocate(fired.length * 4L, 4);
        MemorySegment.copy(q, 0, buf[0], JAVA_FLOAT, 0, n);
        MemorySegment.copy(qstr, 0, buf[1], JAVA_INT, 0, n);
        try {
            int got = (int) h.invoke(inst, buf[0], buf[1], n, dt, buf[2], fired.length);
            int k = Math.max(0, Math.min(got, fired.length));
            MemorySegment.copy(buf[2], JAVA_INT, 0, fired, 0, k);
            return k;
        } catch (Throwable e) { return 0; }
    }

    // ── server molang (koper_molang_*) ───────────────────────────────────────

    // {"expr","q":{..},"v":{..}} -> {"n"|"s", "v":{written vars}}; null when the native is missing
    public static com.google.gson.JsonObject molangEval(com.google.gson.JsonObject req) {
        var h = fn("koper_molang_eval", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        if (h == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocateFrom(req.toString());
            long cap = 4096;
            for (int tries = 0; tries < 2; tries++) {
                MemorySegment out = a.allocate(cap);
                int n = (int) h.invoke(in, out, cap);
                if (n < 0) return null;
                if (n <= cap) return com.google.gson.JsonParser.parseString(new String(out.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8)).getAsJsonObject();
                cap = n;
            }
        } catch (Throwable e) { KoperLib.LOGGER.debug("[Molang] eval failed", e); }
        return null;
    }

    public static java.util.List<String> molangQueries(String expr) {
        var h = fn("koper_molang_queries", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        if (h == null) return java.util.List.of();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(16384);
            int n = (int) h.invoke(a.allocateFrom(expr), out, 16384L);
            if (n <= 0 || n > 16384) return java.util.List.of();
            java.util.List<String> list = new java.util.ArrayList<>();
            com.google.gson.JsonParser.parseString(new String(out.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8))
                .getAsJsonArray().forEach(x -> list.add(x.getAsString()));
            return list;
        } catch (Throwable e) { return java.util.List.of(); }
    }
}
