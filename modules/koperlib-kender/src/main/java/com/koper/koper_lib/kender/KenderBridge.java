package com.koper.koper_lib.kender;


import com.koper.koper_lib.coremod.KoperCore;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

// Panama bridge to koperlib-kender Rust geometry engine
// vertex data lives in Rust — Java reads it zero-copy via MemorySegment.ofAddress
// all geometry work (face culling, AO) happens in Rust, Java just renders the output
public final class KenderBridge {
    private static volatile boolean ENTITY_PASS_READY;
    private static volatile int LAST_DEBUG = -1;
    private static final ThreadLocal<FloatScratch> FLOAT_SCRATCH = ThreadLocal.withInitial(FloatScratch::new);
    private static final ThreadLocal<SkinnedFrameScratch> SKIN_FRAME_SCRATCH =
        ThreadLocal.withInitial(SkinnedFrameScratch::new);

    // pre-built descriptors — same naming convention as RustBridge
    private static final FunctionDescriptor
        RET_INT           = FunctionDescriptor.of(JAVA_INT),
        VOID_INT          = FunctionDescriptor.ofVoid(JAVA_INT),
        VOID_LONG         = FunctionDescriptor.ofVoid(JAVA_LONG),
        INT_LONG          = FunctionDescriptor.of(JAVA_INT, JAVA_LONG),
        LONG_LONG_PTR     = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS),
        INT_LONG_PTR_PTR  = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS),
        VOID_LONG_PTR_PTR_INT = FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT);

    private KenderBridge() {}

    private static MemorySegment scratch(float[] data, int count) {
        return count == 0 ? MemorySegment.NULL : FLOAT_SCRATCH.get().put(data, count);
    }

    private static final class FloatScratch {
        private Arena arena = Arena.ofConfined();
        private MemorySegment segment = MemorySegment.NULL;
        private int capacity;

        MemorySegment put(float[] data, int count) {
            if (count > capacity) {
                arena.close();
                arena = Arena.ofConfined();
                capacity = Integer.highestOneBit(count - 1) << 1;
                if (capacity <= 0) capacity = count;
                segment = arena.allocate(JAVA_FLOAT, capacity);
            }
            MemorySegment.copy(data, 0, segment, JAVA_FLOAT, 0, count);
            return segment;
        }
    }

    private static final class SkinnedFrameScratch {
        private Arena arena = Arena.ofConfined();
        private MemorySegment ids = MemorySegment.NULL;
        private MemorySegment meta = MemorySegment.NULL;
        private MemorySegment bones = MemorySegment.NULL;
        private MemorySegment instances = MemorySegment.NULL;
        private int idCapacity, metaCapacity, boneCapacity, instanceCapacity;

        void put(long[] idData, int[] metaData, int batchCount,
                 float[] boneData, int boneCount, float[] instanceData, int instanceCount) {
            int metaCount = batchCount * 4;
            if (batchCount > idCapacity || metaCount > metaCapacity
                    || boneCount > boneCapacity || instanceCount > instanceCapacity) {
                arena.close();
                arena = Arena.ofConfined();
                idCapacity = grow(idCapacity, batchCount);
                metaCapacity = grow(metaCapacity, metaCount);
                boneCapacity = grow(boneCapacity, boneCount);
                instanceCapacity = grow(instanceCapacity, instanceCount);
                ids = idCapacity == 0 ? MemorySegment.NULL : arena.allocate(JAVA_LONG, idCapacity);
                meta = metaCapacity == 0 ? MemorySegment.NULL : arena.allocate(JAVA_INT, metaCapacity);
                bones = boneCapacity == 0 ? MemorySegment.NULL : arena.allocate(JAVA_FLOAT, boneCapacity);
                instances = instanceCapacity == 0 ? MemorySegment.NULL : arena.allocate(JAVA_FLOAT, instanceCapacity);
            }
            if (batchCount != 0) {
                MemorySegment.copy(idData, 0, ids, JAVA_LONG, 0, batchCount);
                MemorySegment.copy(metaData, 0, meta, JAVA_INT, 0, metaCount);
            }
            if (boneCount != 0) MemorySegment.copy(boneData, 0, bones, JAVA_FLOAT, 0, boneCount);
            if (instanceCount != 0) MemorySegment.copy(instanceData, 0, instances, JAVA_FLOAT, 0, instanceCount);
        }

        private static int grow(int current, int needed) {
            if (needed <= current) return current;
            int next = Integer.highestOneBit(needed - 1) << 1;
            return next > 0 ? next : needed;
        }
    }

    // ── init / caps ───────────────────────────────────────────────────────────

    public static int init() {
        var h = fn("kender_init", RET_INT);
        if (h == null) return -1;
        try {
            int rc = (int) h.invoke();
            if (rc == 0) syncDebug();
            return rc;
        }
        catch (Throwable e) { KoperCore.LOGGER.error("[Kender] init fail", e); return -1; }
    }

    public static void syncDebug() {
        int enabled = Boolean.getBoolean("koperlib.kender.debug") ? 1 : 0;
        if (enabled == LAST_DEBUG) return;
        var h = fn("kender_set_debug", VOID_INT);
        if (h == null) return;
        try {
            h.invoke(enabled);
            LAST_DEBUG = enabled;
        } catch (Throwable e) {
            KoperCore.LOGGER.warn("[Kender] native debug toggle failed: {}", e.getMessage());
        }
    }

    private static Boolean vulkanProbed; // null = not probed yet

    /**
     * Does the machine expose a usable Vulkan loader. 26.3 ships LWJGL with SDL instead of GLFW, so this
     * asks SDL: load the Vulkan loader, check it reports the surface instance extensions, unload again.
     * SDL refcounts the loader, so this never tears down a Vulkan backend MC already runs on.
     */
    public static boolean vulkanSupported() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT)
            return false; // dedicated server: no window, no SDL, nothing to probe
        if (vulkanProbed == null) {
            boolean ok = false;
            try {
                if (org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_LoadLibrary((CharSequence) null)) {
                    try { ok = org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_GetInstanceExtensions() != null; }
                    finally { org.lwjgl.sdl.SDLVulkan.SDL_Vulkan_UnloadLibrary(); }
                }
            } catch (Throwable t) {
                // not fatal (GL still works) but the direct-Vulkan path is off, say so
                KoperCore.LOGGER.error("[Kender] Vulkan probe through SDL failed, direct Vulkan rendering is DISABLED", t);
            }
            if (!ok) KoperCore.LOGGER.warn("[Kender] no usable Vulkan loader found, direct Vulkan rendering is disabled");
            vulkanProbed = ok;
        }
        return vulkanProbed;
    }

    /** Probe for Vulkan support and push capability flags to Rust. */
    public static void detectAndUploadCaps() {
        int flags = 0;
        // bit 0: Vulkan present
        if (vulkanSupported()) {
            flags |= 1;
            // mesh shaders (VK_EXT_mesh_shader) + bindless detection happens in K1
            // when we have VkDevice — skip for now
        }
        var h = fn("kender_set_gpu_caps", VOID_INT);
        if (h == null) return;
        try { h.invoke(flags); }
        catch (Throwable e) { KoperCore.LOGGER.error("[Kender] setGpuCaps fail", e); }
    }

    public static boolean hasMeshShaders() {
        var h = fn("kender_has_mesh_shaders", RET_INT);
        if (h == null) return false;
        try { return (int) h.invoke() != 0; }
        catch (Throwable e) { return false; }
    }

    // ── geometry API ──────────────────────────────────────────────────────────

    /**
     * Send block layout to Rust — triggers async face-cull + AO rebuild on dirty flag.
     * offsets: block local positions as floats [ox,oy,oz per block]
     * blockIds: MC block state IDs (Block.BLOCK_STATE_REGISTRY.getId(state))
     */
    public static void setBlocks(long id, float[] offsets, int[] blockIds) {
        if (!KenderNative.isLoaded() || offsets == null || blockIds == null) return;
        var h = fn("kender_kontra_set_blocks", VOID_LONG_PTR_PTR_INT);
        if (h == null) return;
        int count = blockIds.length;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment offSeg = a.allocateFrom(JAVA_FLOAT, offsets);
            MemorySegment idsSeg = a.allocateFrom(JAVA_INT, blockIds);
            h.invoke(id, offSeg, idsSeg, count);
        } catch (Throwable e) {
            KoperCore.LOGGER.error("[Kender] setBlocks id={} fail: {}", id, e.getMessage());
        }
    }

    public static void markDirty(long id) {
        var h = fn("kender_kontra_mark_dirty", VOID_LONG);
        if (h == null) return;
        try { h.invoke(id); } catch (Throwable ignored) {}
    }

    /** Total visible faces after culling. -1 = unknown id. Call to size Java buffers. */
    public static int faceCount(long id) {
        var h = fn("kender_kontra_face_count", INT_LONG);
        if (h == null) return -1;
        try { return (int) h.invoke(id); }
        catch (Throwable e) { return -1; }
    }

    /**
     * Returns per-block face visibility masks — one byte per block, bits 0-5 = face visible.
     * mask == 0 → block is fully interior → skip submitMovingBlock for it.
     * Returns null if id unknown. Triggers rebuild if dirty.
     */
    public static byte[] getFaceMasks(long id, int blockCount) {
        if (!KenderNative.isLoaded() || blockCount <= 0) return null;
        var h = fn("kender_kontra_get_face_masks", INT_LONG_PTR_PTR);
        if (h == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment maskBuf  = a.allocate(blockCount);
            MemorySegment countSeg = a.allocate(JAVA_INT);
            int rc = (int) h.invoke(id, maskBuf, countSeg);
            if (rc != 0) return null;
            int n = countSeg.get(JAVA_INT, 0);
            if (n <= 0) return null;
            byte[] out = new byte[n];
            MemorySegment.copy(maskBuf, JAVA_BYTE, 0, out, 0, n);
            return out;
        } catch (Throwable e) {
            KoperCore.LOGGER.error("[Kender] getFaceMasks id={} fail: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Zero-copy: returns MemorySegment pointing directly into Rust's vertex Vec<f32>.
     * Layout per vertex (9 floats): pos(3) uv(2) ao(1) face_id(1) block_id(1) pad(1)
     * Valid until next setBlocks or remove for this id — don't store across frames.
     * Null if id unknown or no geometry.
     */
    public static MemorySegment getMeshDirect(long id) {
        if (!KenderNative.isLoaded()) return null;
        var h = fn("kender_kontra_get_mesh_ptr", LONG_LONG_PTR);
        if (h == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment lenSeg = a.allocate(JAVA_INT);
            long ptr = (long) h.invoke(id, lenSeg);
            int  len = lenSeg.get(JAVA_INT, 0);
            if (ptr == 0L || len <= 0) return null;
            // reinterpret: the Rust pointer is valid for the lifetime of the Vec
            // Java must not hold this MemorySegment past the next setBlocks/remove call
            return MemorySegment.ofAddress(ptr).reinterpret((long) len * Float.BYTES);
        } catch (Throwable e) {
            return null;
        }
    }

    public static void remove(long id) {
        var h = fn("kender_kontra_remove", VOID_LONG);
        if (h == null) return;
        try { h.invoke(id); } catch (Throwable ignored) {}
    }

    public static void clearAll() {
        var h = fn("kender_kontra_clear_all", FunctionDescriptor.ofVoid());
        if (h == null) return;
        try { h.invoke(); } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        var h = fn("kender_shutdown", FunctionDescriptor.ofVoid());
        if (h == null) return;
        try { h.invoke(); } catch (Throwable ignored) {}
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private static boolean READY = false;

    /** true after init() succeeded and Rust kender is loaded */
    public static boolean isOk() { return READY && KenderNative.isLoaded(); }

    // must be called explicitly (KenderRenderer.init does it)
    static void markReady(boolean val) { READY = val; }

    // ── Vulkan draw path ─────────────────────────────────────────────────────

    private static final FunctionDescriptor DESC_VK_INIT =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);

    /** Hand MC's live Vulkan handles + real attachment formats to Rust so it shares MC's device. 0 = ok.
     *  colorFmt/depthFmt = MC's main-target VkFormat (VulkanConst.toVk); the pipeline MUST match or nothing draws. */
    public static int vkInit(long instance, long device, long queue, int family, int colorFmt, int depthFmt) {
        if (!isOk()) return -1;
        var h = fn("kender_vk_init", DESC_VK_INIT);
        if (h == null) return -1;
        try {
            return (int) h.invoke(instance, device, queue, family, colorFmt, depthFmt);
        } catch (Throwable e) {
            KoperCore.LOGGER.error("[Kender] vkInit fail: {}", e.getMessage());
            return -1;
        }
    }

    // ── geo instancing (2b): upload a model's mesh once, set per-frame instances, instanced draw ──
    private static final FunctionDescriptor DESC_GEO_UPLOAD =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor DESC_GEO_INST =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor DESC_GEO_DRAW =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor DESC_KFX_UPLOAD =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    private static final FunctionDescriptor DESC_KFX_EVAL =
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_FLOAT, ADDRESS);
    private static final FunctionDescriptor DESC_KFX_DRAW =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
            ADDRESS, JAVA_FLOAT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final FunctionDescriptor DESC_KFX_DRAW_IN_PASS =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
            ADDRESS, JAVA_FLOAT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final FunctionDescriptor DESC_KFX_UPDATE =
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    private static final FunctionDescriptor DESC_KFX_GRAPH_UPLOAD =
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor DESC_KFX_GRAPH_SPAWN =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);

    /** Upload (or replace) a model's baked mesh (flat buffer, stride 8: x y z u v nx ny nz). 0 = ok. */
    public static int geoUploadModel(long id, float[] verts) {
        if (!isOk() || verts == null || verts.length == 0) return -1;
        var h = fn("kender_geo_upload_model", DESC_GEO_UPLOAD);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(id, a.allocateFrom(JAVA_FLOAT, verts), verts.length);
        } catch (Throwable e) { return -1; }
    }

    /** Set a model's live instances: [m0..m15, blockLight, skyLight, tintEnc] per instance (19 floats). 0 = ok. */
    public static int geoSetInstances(long id, float[] data) {
        return geoSetInstances(id, data, data == null ? 0 : data.length);
    }

    public static void geoRemove(long id) {
        var h = fn("kender_geo_remove", VOID_LONG);
        if (h == null) return;
        try { h.invoke(id); } catch (Throwable ignored) {}
    }

    public static int geoSetInstances(long id, float[] data, int floatCount) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_instances", DESC_GEO_INST);
        if (h == null) return -1;
        try {
            int count = data == null ? 0 : Math.min(Math.max(floatCount, 0), data.length);
            return (int) h.invoke(id, scratch(data, count), count);
        } catch (Throwable e) { return -1; }
    }

    /** Set instances as Rust-side TRS: [px,py,pz, qx,qy,qz,qw, sx,sy,sz, blockLight,skyLight]. */
    public static int geoSetInstancesTrs(long id, float[] data) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_instances_trs", DESC_GEO_INST);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = (data == null || data.length == 0) ? MemorySegment.NULL : a.allocateFrom(JAVA_FLOAT, data);
            return (int) h.invoke(id, seg, data == null ? 0 : data.length);
        } catch (Throwable e) { return -1; }
    }

    private static final FunctionDescriptor DESC_GEO_TEX =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG);

    private static final FunctionDescriptor DESC_GEO_LIGHTMAP =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG);

    private static final FunctionDescriptor DESC_GEO_DYN =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    private static final FunctionDescriptor DESC_GEO_MATERIAL =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT);
    private static final FunctionDescriptor DESC_GEO_DRAW_ENTITY_IN_PASS =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_INT);
    private static final FunctionDescriptor DESC_GEO_SKIN_FRAME =
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);

    private static final FunctionDescriptor DESC_GEO_PARENT = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor DESC_GEO_SMAP =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);

    /** Draw kender geometry into a shader mod's shadow cascade. >=0 = models recorded.
     *  wantDynamic: 1 = kontra/moving only, 0 = world-static only, 2 = everything. */
    public static int geoDrawShadowMap(long cmd, int width, int height, float[] viewProj,
                                       int colorFormat, int depthFormat, int wantDynamic) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_geo_draw_shadow_map", DESC_GEO_SMAP);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, width, height, a.allocateFrom(JAVA_FLOAT, viewProj),
                colorFormat, depthFormat, wantDynamic);
        } catch (Throwable e) { return -1; }
    }

    /** Ship pose folded in before every instance, so kontra instances can stay in ship-local space. */
    public static int geoSetParent(long id, float[] m16) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_parent", DESC_GEO_PARENT);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = (m16 == null || m16.length < 16)
                ? MemorySegment.NULL : a.allocateFrom(JAVA_FLOAT, m16);
            return (int) h.invoke(id, seg);
        } catch (Throwable e) { return -1; }
    }

    /** Mark a model's instances per-frame dynamic (kontra) — the GPU cull skips it (stale-cull jitter otherwise). */
    public static int geoSetDynamic(long id) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_dynamic", DESC_GEO_DYN);
        if (h == null) return -1;
        try { return (int) h.invoke(id); } catch (Throwable e) { return -1; }
    }

    /** 0 cutout, 1 translucent, 2 solid. */
    public static int geoSetMaterial(long id, int material) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_material", DESC_GEO_MATERIAL);
        if (h == null) return -1;
        try { return (int) h.invoke(id, material); } catch (Throwable e) { return -1; }
    }

    /** 0 world/block, 1 opaque entity, 2 translucent entity. */
    public static int geoSetPass(long id, int pass) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_pass", DESC_GEO_MATERIAL);
        if (h == null) return -1;
        try { return (int) h.invoke(id, pass); } catch (Throwable e) { return -1; }
    }

    public static int geoSetOrder(long id, int order) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_order", DESC_GEO_MATERIAL);
        if (h == null) return -1;
        try { return (int) h.invoke(id, order); } catch (Throwable e) { return -1; }
    }

    public static int geoSetBoneSource(long id, long source) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_bone_source", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        if (h == null) return -1;
        try { return (int) h.invoke(id, source); } catch (Throwable e) { return -1; }
    }

    // Don't hide the MC model when Java and the already-loaded DLL are from different builds.
    public static boolean geoEntityPassAvailable() {
        if (ENTITY_PASS_READY) return true;
        if (!isOk()) return false;
        ENTITY_PASS_READY = fn("kender_geo_upload_skinned", DESC_GEO_UPLOAD) != null
            && fn("kender_geo_set_instances_skinned", DESC_GEO_INST) != null
            && fn("kender_geo_set_bones", DESC_GEO_INST) != null
            && fn("kender_geo_set_material", DESC_GEO_MATERIAL) != null
            && fn("kender_geo_set_pass", DESC_GEO_MATERIAL) != null
            && fn("kender_geo_set_order", DESC_GEO_MATERIAL) != null
            && fn("kender_geo_set_bone_source", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG)) != null
            && fn("kender_geo_set_skinned_frame", DESC_GEO_SKIN_FRAME) != null
            && fn("kender_geo_draw_entity_in_pass", DESC_GEO_DRAW_ENTITY_IN_PASS) != null;
        return ENTITY_PASS_READY;
    }

    /** Bind a model's texture (raw VkImageView from MC). Called once per model (texture doesn't change). 0 = ok. */
    public static int geoSetTexture(long id, long imageView) {
        if (!isOk() || imageView == 0L) return -1;
        var h = fn("kender_geo_set_texture", DESC_GEO_TEX);
        if (h == null) return -1;
        try {
            return (int) h.invoke(id, imageView);
        } catch (Throwable e) { return -1; }
    }

    /** Kinetic model — instances carry axis/speed/offset/pivot, shader spins them. Before first instances. */
    public static int geoSetSpin(long id) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_spin", DESC_GEO_DYN);
        if (h == null) return -1;
        try {
            return (int) h.invoke(id);
        } catch (Throwable e) { return -1; }
    }

    /** True when the kinetic pipeline built. False = keep baking spin angles on the CPU. */
    public static boolean geoSpinAvailable() {
        if (!isOk()) return false;
        var h = fn("kender_geo_spin_available", DESC_NO_ARG_INT);
        if (h == null) return false;
        try {
            return (int) h.invoke() == 1;
        } catch (Throwable e) { return false; }
    }

    private static final FunctionDescriptor DESC_NO_ARG_INT = FunctionDescriptor.of(JAVA_INT);

    /** Flywheel entity shadow model — own shader + stride. Before first instances. */
    public static int geoSetShadow(long id) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_shadow", DESC_GEO_DYN);
        if (h == null) return -1;
        try { return (int) h.invoke(id); } catch (Throwable e) { return -1; }
    }

    /** Kinetic clock, seconds, session-relative. Once per frame. */
    public static int geoSetTime(float seconds) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_time", DESC_GEO_TIME);
        if (h == null) return -1;
        try {
            return (int) h.invoke(seconds);
        } catch (Throwable e) { return -1; }
    }

    private static final FunctionDescriptor DESC_GEO_TIME =
        FunctionDescriptor.of(JAVA_INT, JAVA_FLOAT);

    /** Hand the shader MC's level lightmap. Until this lands the frag falls back to the old approximation. */
    public static int geoSetLightmap(long imageView) {
        if (!isOk() || imageView == 0L) return -1;
        var h = fn("kender_geo_set_lightmap", DESC_GEO_LIGHTMAP);
        if (h == null) return -1;
        try {
            return (int) h.invoke(imageView);
        } catch (Throwable e) { return -1; }
    }

    /** Instanced-draw every uploaded model into MC's frame. viewProj = 16 floats col-major. 0 = ok. */
    public static int geoDraw(long cmd, long color, long depth, int width, int height, float[] viewProj) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_geo_draw", DESC_GEO_DRAW);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, color, depth, width, height, a.allocateFrom(JAVA_FLOAT, viewProj));
        } catch (Throwable e) { return -1; }
    }

    private static final FunctionDescriptor DESC_GEO_DRAW_IN_PASS =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_FLOAT);

    /** Hook B — draw geo INSIDE MC's open world pass. sky = day/night sky-light factor (1 day, ~0.2 night). */
    public static int geoDrawInPass(long cmd, int width, int height, float[] viewProj, float sky) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_geo_draw_in_pass", DESC_GEO_DRAW_IN_PASS);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, width, height, a.allocateFrom(JAVA_FLOAT, viewProj), sky);
        } catch (Throwable e) { return -1; }
    }

    public static int geoDrawEntityInPass(long cmd, int width, int height, float[] viewProj, float sky, int pass) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_geo_draw_entity_in_pass", DESC_GEO_DRAW_ENTITY_IN_PASS);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, width, height, a.allocateFrom(JAVA_FLOAT, viewProj), sky, pass);
        } catch (Throwable e) { return -1; }
    }

    /** Skinned mesh (stride 9: pos+uv+normal+boneId). 0 = ok, -1 = skin pipeline unavailable -> stay on CPU anim. */
    public static int geoUploadSkinned(long id, float[] verts) {
        if (!isOk() || verts == null || verts.length == 0) return -1;
        var h = fn("kender_geo_upload_skinned", DESC_GEO_UPLOAD);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(id, a.allocateFrom(JAVA_FLOAT, verts), verts.length);
        } catch (Throwable e) { return -1; }
    }

    /** Skinned instances: [m0..m15, blockLight, skyLight, boneBase, tintEnc, uOffset, vOffset] (22 floats). */
    public static int geoSetInstancesSkinned(long id, float[] data) {
        return geoSetInstancesSkinned(id, data, data == null ? 0 : data.length);
    }

    public static int geoSetInstancesSkinned(long id, float[] data, int floatCount) {
        if (!isOk()) return -1;
        var h = fn("kender_geo_set_instances_skinned", DESC_GEO_INST);
        if (h == null) return -1;
        try {
            int count = data == null ? 0 : Math.min(Math.max(floatCount, 0), data.length);
            return (int) h.invoke(id, scratch(data, count), count);
        } catch (Throwable e) { return -1; }
    }

    /** Per-frame bone matrices for a skinned model (16 floats per bone, all anim states concatenated). */
    public static int geoSetBones(long id, float[] mats) {
        return geoSetBones(id, mats, mats == null ? 0 : mats.length);
    }

    public static int geoSetBones(long id, float[] mats, int floatCount) {
        if (!isOk() || mats == null || floatCount <= 0) return -1;
        var h = fn("kender_geo_set_bones", DESC_GEO_INST);
        if (h == null) return -1;
        try {
            int count = Math.min(floatCount, mats.length);
            return (int) h.invoke(id, scratch(mats, count), count);
        } catch (Throwable e) { return -1; }
    }

    public static int geoSetSkinnedFrame(long[] ids, int[] meta, int batchCount,
                                         float[] bones, int boneCount,
                                         float[] instances, int instanceCount) {
        if (!isOk() || batchCount < 0 || boneCount < 0 || instanceCount < 0) return -1;
        var h = fn("kender_geo_set_skinned_frame", DESC_GEO_SKIN_FRAME);
        if (h == null) return -1;
        try {
            SkinnedFrameScratch s = SKIN_FRAME_SCRATCH.get();
            s.put(ids, meta, batchCount, bones, boneCount, instances, instanceCount);
            return (int)h.invoke(s.ids, s.meta, batchCount, s.bones, boneCount,
                s.instances, instanceCount);
        } catch (Throwable e) { return -1; }
    }

    private static final FunctionDescriptor DESC_GEO_CULL =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor DESC_GEO_CULL_WANTED =
        FunctionDescriptor.of(JAVA_INT);

    /** 1 = some model is big enough for the GPU cull; skip the transient-buffer dance otherwise. */
    public static int geoCullWanted() {
        if (!isOk()) return 0;
        var h = fn("kender_geo_cull_wanted", DESC_GEO_CULL_WANTED);
        if (h == null) return 0;
        try { return (int) h.invoke(); } catch (Throwable e) { return 0; }
    }

    /** Packed: high 32 = models past the cull threshold, low 32 = biggest model's instance count. */
    public static long geoCullStats() {
        if (!isOk()) return 0L;
        var h = fn("kender_geo_cull_stats", DESC_NO_ARG_LONG);
        if (h == null) return 0L;
        try { return (long) h.invoke(); } catch (Throwable e) { return 0L; }
    }

    private static final FunctionDescriptor DESC_NO_ARG_LONG = FunctionDescriptor.of(JAVA_LONG);

    /** Record the GPU frustum cull into a transient cmd buffer. 0 = recorded + buffer ENDED (hand to execute). */
    public static int geoRecordCull(long cmd, float[] viewProj) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_geo_record_cull", DESC_GEO_CULL);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, a.allocateFrom(JAVA_FLOAT, viewProj));
        } catch (Throwable e) { return -1; }
    }

    /** Upload a compiled KFX op buffer. Rust stores it for native GPU path; MC geometry remains fallback. */
    public static int kfxUploadProgram(long id, float[] ops,
                                       float sx, float sy, float sz, float ex, float ey, float ez, float bornTicks) {
        if (!isOk() || ops == null || ops.length == 0) return -1;
        var h = fn("kender_kfx_upload_program", DESC_KFX_UPLOAD);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(id, a.allocateFrom(JAVA_FLOAT, ops), ops.length, sx, sy, sz, ex, ey, ez, bornTicks);
        } catch (Throwable e) {
            KoperCore.LOGGER.warn("[Kender] kfxUploadProgram fail: {}", e.getMessage());
            return -1;
        }
    }

    /** Validates and caches one immutable KFX2 byte program in Rust. Returns its graph hash or zero. */
    public static long kfxGraphUpload(byte[] program) {
        if (!isOk() || program == null || program.length == 0) return 0L;
        var h = fn("kender_kfx_graph_upload", DESC_KFX_GRAPH_UPLOAD);
        if (h == null) return 0L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(program.length, 1);
            bytes.copyFrom(MemorySegment.ofArray(program));
            return (long)h.invoke(bytes, program.length);
        } catch (Throwable error) {
            KoperCore.LOGGER.warn("[Kender] kfxGraphUpload failed: {}", error.getMessage());
            return 0L;
        }
    }

    /** Spawns a lightweight handle+seed instance referencing an uploaded KFX2 graph hash. */
    public static boolean kfxGraphSpawn(long id, long graphHash, long seed, int particleBudget,
                                        float sx, float sy, float sz, float ex, float ey, float ez,
                                        float bornTicks) {
        if (!isOk() || id == 0L || graphHash == 0L || particleBudget < 1) return false;
        var h = fn("kender_kfx_graph_spawn", DESC_KFX_GRAPH_SPAWN);
        if (h == null) return false;
        try {
            return (int)h.invoke(id, graphHash, seed, particleBudget,
                sx, sy, sz, ex, ey, ez, bornTicks) == 0;
        } catch (Throwable error) {
            KoperCore.LOGGER.warn("[Kender] kfxGraphSpawn failed: {}", error.getMessage());
            return false;
        }
    }

    /** GPU instanced draw of every live KFX particle (program ops + emitter sim, all in Rust). Call at render TAIL. */
    public static int kfxDraw(long cmd, long color, long depth, int width, int height,
                              float[] viewProj, float nowTicks, double camX, double camY, double camZ) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_kfx_draw", DESC_KFX_DRAW);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, color, depth, width, height,
                a.allocateFrom(JAVA_FLOAT, viewProj), nowTicks, camX, camY, camZ);
        } catch (Throwable e) { return -1; }
    }

    /** Draw KFX while MC's world render pass is open. 1 = drew, 0 = empty, negative = use CPU next frame. */
    public static int kfxDrawInPass(long cmd, int width, int height, float[] viewProj,
                                    float nowTicks, double originX, double originY, double originZ) {
        if (!isOk() || viewProj == null || viewProj.length < 16) return -1;
        var h = fn("kender_kfx_draw_in_pass", DESC_KFX_DRAW_IN_PASS);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(cmd, width, height, a.allocateFrom(JAVA_FLOAT, viewProj),
                nowTicks, originX, originY, originZ);
        } catch (Throwable e) { return -1; }
    }

    public record VertBuf(MemorySegment data, int vertexCount) {}

    private static final FunctionDescriptor DESC_KFX_BUILD = FunctionDescriptor.of(JAVA_LONG,
        JAVA_FLOAT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS);

    /**
     * Build the full POSITION_COLOR vertex buffer (cam-facing billboards) for all live particles, in Rust.
     * Returns a segment over Rust memory (valid until the next call) + vertex count, or null if empty.
     * 16 bytes/vertex: pos 3×f32 + rgba 4×u8.
     */
    public static VertBuf kfxBuildVertices(float now, double camX, double camY, double camZ,
                                           float rightX, float rightY, float rightZ,
                                           float upX, float upY, float upZ) {
        if (!isOk()) return null;
        var h = fn("kender_kfx_build_vertices", DESC_KFX_BUILD);
        if (h == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cnt = a.allocate(JAVA_INT);
            long ptr = (long) h.invoke(now, camX, camY, camZ, rightX, rightY, rightZ, upX, upY, upZ, cnt);
            int vc = cnt.get(JAVA_INT, 0);
            if (ptr == 0L || vc <= 0) return null;
            return new VertBuf(MemorySegment.ofAddress(ptr).reinterpret((long) vc * 16L), vc);
        } catch (Throwable e) {
            KoperCore.LOGGER.warn("[kender-vk] kfxBuildVertices fail: {}", e.toString());
            return null;
        }
    }

    private static final FunctionDescriptor DESC_EMITTER_SPAWN =
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor DESC_EMITTER_COLLISION = FunctionDescriptor.of(JAVA_INT,
        JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT);

    /** Hand an emitter's def to Rust once on spawn — Rust then sims + draws it on the GPU path, zero per-frame Java. */
    public static boolean emitterSpawn(long id, int color, int color2, float[] parameters) {
        if (!isOk()) return false;
        var h = fn("kender_emitter_spawn", DESC_EMITTER_SPAWN);
        if (h == null || parameters == null) return false;
        try (Arena a = Arena.ofConfined()) {
            return (int) h.invoke(id, color, color2,
                a.allocateFrom(JAVA_FLOAT, parameters), parameters.length) == 0;
        } catch (Throwable ignored) { return false; }
    }

    /** Uploads a compact visual-only field. It cannot emit gameplay contacts. */
    public static boolean emitterCollision(long id, byte[] cells, int originX, int originY, int originZ,
                                           int side, int response, float restitution, float friction) {
        if (!isOk() || id == 0L || cells == null || cells.length == 0) return false;
        var h = fn("kender_emitter_collision", DESC_EMITTER_COLLISION);
        if (h == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(cells.length, 1);
            data.copyFrom(MemorySegment.ofArray(cells));
            return (int)h.invoke(id, data, cells.length, originX, originY, originZ, side, response,
                restitution, friction) == 0;
        } catch (Throwable error) {
            KoperCore.LOGGER.warn("[Kender] emitterCollision failed: {}", error.getMessage());
            return false;
        }
    }

    public static void emitterRemove(long id) {
        var h = fn("kender_emitter_remove", VOID_LONG);
        if (h == null) return;
        try { h.invoke(id); } catch (Throwable ignored) {}
    }

    public static void emitterClear() {
        var h = fn("kender_emitter_clear", FunctionDescriptor.ofVoid());
        if (h == null) return;
        try { h.invoke(); } catch (Throwable ignored) {}
    }

    public static int emitterCount() {
        var h = fn("kender_emitter_count", RET_INT);
        if (h == null) return -1;
        try { return (int) h.invoke(); } catch (Throwable ignored) { return -1; }
    }

    public static void kfxRemove(long id) {
        var h = fn("kender_kfx_remove", VOID_LONG);
        if (h == null) return;
        try { h.invoke(id); } catch (Throwable ignored) {}
    }

    public static void kfxUpdate(long id, float sx, float sy, float sz, float ex, float ey, float ez) {
        var h = fn("kender_kfx_update", DESC_KFX_UPDATE);
        if (h == null) return;
        try { h.invoke(id, sx, sy, sz, ex, ey, ez); } catch (Throwable ignored) {}
    }

    public static void kfxClear() {
        var h = fn("kender_kfx_clear", FunctionDescriptor.ofVoid());
        if (h == null) return;
        try { h.invoke(); } catch (Throwable ignored) {}
    }

    public static int kfxCount() {
        var h = fn("kender_kfx_count", RET_INT);
        if (h == null) return -1;
        try { return (int) h.invoke(); }
        catch (Throwable ignored) { return -1; }
    }

    /** Zero-copy KFX particle instance batch. Stride 10 floats: xyz,size,rgba,style,seed. */
    public static MemorySegment kfxEvalDirect(long id, float ageTicks) {
        if (!isOk()) return null;
        var h = fn("kender_kfx_eval_ptr", DESC_KFX_EVAL);
        if (h == null) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment lenSeg = a.allocate(JAVA_INT);
            long ptr = (long) h.invoke(id, ageTicks, lenSeg);
            int len = lenSeg.get(JAVA_INT, 0);
            if (ptr == 0L || len <= 0) return null;
            return MemorySegment.ofAddress(ptr).reinterpret((long) len * Float.BYTES);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final FunctionDescriptor DESC_VK_DRAW_FRAME =
        FunctionDescriptor.of(JAVA_INT,
            JAVA_LONG,  // cmd_buf
            JAVA_LONG,  // color_view
            JAVA_LONG,  // depth_view
            JAVA_INT,   // width
            JAVA_INT,   // height
            ADDRESS,    // kontra_ids  (*i64)
            ADDRESS,    // transforms  (*f32)
            JAVA_INT,   // kontra_count
            ADDRESS,    // view_proj   (*f32)
            JAVA_INT    // shadow flag
        );

    /**
     * Draw all kontraktions via Vulkan MDI. Call inside LevelRenderer.render() TAIL.
     * cmdBuf/colorView/depthView: raw VkCommandBuffer/ImageView handles from MC's VulkanStateManager.
     * ids:        kontraktion IDs (one per kontraktion).
     * transforms: 7 floats per kontraktion [px py pz  rx ry rz rw].
     * viewProj:   16 floats column-major 4×4 projection*view matrix.
     * shadow:     true = depth-only shadow pass.
     * Returns 0 on success, negative on error.
     */
    public static int drawFrame(
            long   cmdBuf, long colorView, long depthView,
            int    width,  int  height,
            long[] ids,    float[] transforms, float[] viewProj,
            boolean shadow) {
        if (!isOk()) return -1;
        var h = fn("kender_vk_draw_frame", DESC_VK_DRAW_FRAME);
        if (h == null) return -1;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment idsSeg = a.allocateFrom(JAVA_LONG,  ids);
            MemorySegment trsSeg = a.allocateFrom(JAVA_FLOAT, transforms);
            MemorySegment vpSeg  = a.allocateFrom(JAVA_FLOAT, viewProj);
            return (int) h.invoke(
                cmdBuf, colorView, depthView,
                width, height,
                idsSeg, trsSeg, ids.length,
                vpSeg, shadow ? 1 : 0
            );
        } catch (Throwable e) {
            KoperCore.LOGGER.error("[Kender] drawFrame fail: {}", e.getMessage());
            return -1;
        }
    }

    // ── Vulkan frame resource access via MC internals ─────────────────────────

    /**
     * Grabs current-frame Vulkan resources from MC's VulkanStateManager via reflection.
     * Returns [cmdBuf, colorImageView, depthImageView, width, height] as longs (last two are int cast).
     * All zeros if Vulkan mode is inactive or reflection fails.
     */
    public static long[] currentFrameResources() {
        long[] res = new long[5]; // [cmd, color, depth, w, h]
        try {
            Class<?> vsm = Class.forName("com.mojang.blaze3d.systems.VulkanStateManager");

            // command buffer
            res[0] = grabHandle(vsm, "currentCommandBuffer", "commandBuffer", "cmdBuffer");
            // color attachment image view (swapchain image view for current frame)
            res[1] = grabHandle(vsm, "currentColorAttachment", "colorImageView", "swapchainImageView");
            // depth attachment image view
            res[2] = grabHandle(vsm, "currentDepthAttachment", "depthImageView", "depthStencilView");

            // framebuffer size — try several field names
            Object w = grabField(vsm, "framebufferWidth",  "swapchainWidth",  "windowWidth");
            Object h = grabField(vsm, "framebufferHeight", "swapchainHeight", "windowHeight");
            if (w instanceof Number n) res[3] = n.intValue();
            if (h instanceof Number n) res[4] = n.intValue();

            // fallback: grab from Minecraft window
            if (res[3] == 0) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                res[3] = mc.getWindow().getWidth();
                res[4] = mc.getWindow().getHeight();
            }
        } catch (Throwable ignored) {
            // Vulkan mode not active or MC internals changed — caller checks zeros
        }
        return res;
    }

    // reflection helpers — try multiple field name candidates (MC internals vary between snapshots)
    private static long grabHandle(Class<?> cls, String... candidates) {
        for (String name : candidates) {
            try {
                Object obj = grabField(cls, name);
                if (obj == null) continue;
                // VkHandle types expose address() or handle() returning long
                try { return (long) obj.getClass().getMethod("address").invoke(obj); }
                catch (Throwable e2) {
                    try { return (long) obj.getClass().getMethod("handle").invoke(obj); }
                    catch (Throwable e3) {
                        if (obj instanceof Long l) return l;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    private static Object grabField(Class<?> cls, String... candidates) {
        for (String name : candidates) {
            try {
                var f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── internal ─────────────────────────────────────────────────────────────

    // delegate to RustBridge fn cache — same DLL, no double load
    private static MethodHandle fn(String name, FunctionDescriptor desc) {
        return KenderNative.function(name, desc);
    }
}
