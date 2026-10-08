package com.koper.koper_lib.kender;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

// 2a spike — pulls MC's live Vulkan handles so the kender Rust renderer can SHARE MC's device/allocator
// instead of standing up its own instance. only valid when MC runs its Vulkan backend (device is a VulkanDevice).
// MC exposes these publicly in 26.2 (vkDevice()/vma()/graphicsQueue()), so no reflection needed.
public final class KenderVk {

    private KenderVk() {}

    // GpuDevice hides its backend in a private field; reach it once via reflection (cached), cast to VulkanDevice
    private static java.lang.reflect.Field BACKEND_FIELD;
    private static java.lang.reflect.Field ENCODER_FIELD;
    private static java.lang.reflect.Field CMDBUF_FIELD;

    // real per-frame Vulkan handles for the geo draw: [cmdBuffer, colorImageView, depthImageView, width, height].
    // from the LIVE classes (VulkanCommandEncoder + main render target views), NOT the dead VulkanStateManager
    // the old currentFrameResources reflected (that class doesn't exist in 26.2 -> always returned 0 -> never drew).
    public static long[] frameResources() {
        long[] r = new long[5];
        try {
            VulkanDevice vd = device();
            if (vd == null) return r;
            // active command buffer from the device's command encoder (private fields, cached reflection)
            if (ENCODER_FIELD == null) {
                ENCODER_FIELD = VulkanDevice.class.getDeclaredField("commandEncoder");
                ENCODER_FIELD.setAccessible(true);
            }
            Object enc = ENCODER_FIELD.get(vd);
            if (enc != null) {
                if (CMDBUF_FIELD == null) {
                    CMDBUF_FIELD = enc.getClass().getDeclaredField("currentCommandBuffer");
                    CMDBUF_FIELD.setAccessible(true);
                }
                Object cmd = CMDBUF_FIELD.get(enc);
                if (cmd instanceof org.lwjgl.vulkan.VkCommandBuffer vkcb) r[0] = vkcb.address();
            }
            // main render target's color + depth views (the WORLD framebuffer). it lives on GameRenderer in 26.2,
            // not Minecraft. these are the real VkImageViews the world was just rendered into -> we draw on top.
            var rt = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (rt != null) {
                var cv = rt.getColorTextureView();
                var dv = rt.getDepthTextureView();
                if (cv instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView vcv) r[1] = vcv.vkImageView();
                if (dv instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView vdv) r[2] = vdv.vkImageView();
                r[3] = rt.width;
                r[4] = rt.height;
            }
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] frameResources failed: {}", t.toString());
        }
        return r;
    }

    // ── transient cmd buffer for the GPU cull ─────────────────────────────────
    // compute can't be recorded inside an open rendering pass, so the cull rides its own transient
    // buffer that MC's encoder appends to the submission BEFORE the world passes. barriers inside it
    // synchronize against the later draws (submission order). MC's own texture-init does the same dance.
    private static java.lang.reflect.Field PASS_FIELD;

    public static org.lwjgl.vulkan.VkCommandBuffer beginTransientCmd() {
        try {
            VulkanDevice vd = device();
            if (vd == null) return null;
            if (ENCODER_FIELD == null) {
                ENCODER_FIELD = VulkanDevice.class.getDeclaredField("commandEncoder");
                ENCODER_FIELD.setAccessible(true);
            }
            Object enc = ENCODER_FIELD.get(vd);
            if (!(enc instanceof com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder vce)) return null;
            // execute() throws while a pass is open — check first so we never half-record a cull
            if (PASS_FIELD == null) {
                PASS_FIELD = com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder.class.getDeclaredField("currentRenderPass");
                PASS_FIELD.setAccessible(true);
            }
            if (PASS_FIELD.get(vce) != null) return null;
            return vce.allocateAndBeginTransientCommandBuffer();
        } catch (Throwable t) { koperBroke("transient cmd buffer (gpu cull)", t); return null; }
    }

    // one loud line per broken spot instead of a silent "not today"
    private static final java.util.Set<String> BROKE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void koperBroke(String what, Throwable t) {
        if (BROKE.add(what))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kender-vk] {} is BROKEN, that part of kender is off: {}", what, t.toString(), t);
    }

    public static boolean executeCmd(org.lwjgl.vulkan.VkCommandBuffer cmd) {
        try {
            VulkanDevice vd = device();
            if (vd == null || ENCODER_FIELD == null) return false;
            Object enc = ENCODER_FIELD.get(vd);
            if (!(enc instanceof com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder vce)) return false;
            vce.execute(cmd);
            return true;
        } catch (Throwable t) { koperBroke("submitting kender's transient cmd buffer", t); return false; }
    }

    public static VulkanDevice device() {
        try {
            var gpu = RenderSystem.tryGetDevice();
            if (gpu == null) return null;
            if (BACKEND_FIELD == null) {
                // 26.3 renderpearl: GpuDevice is an interface, the backend sits in FrontendGpuDevice
                BACKEND_FIELD = com.mojang.renderpearl.frontend.FrontendGpuDevice.class.getDeclaredField("backend");
                BACKEND_FIELD.setAccessible(true);
            }
            if (!(gpu instanceof com.mojang.renderpearl.frontend.FrontendGpuDevice)) return null;
            Object backend = BACKEND_FIELD.get(gpu);
            return backend instanceof VulkanDevice vd ? vd : null;
        } catch (Throwable t) {
            // a broken lookup used to read as "not on vulkan" and the whole direct path went quiet
            if (!deviceLookupFailed) {
                deviceLookupFailed = true;
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kender-vk] can't reach MC's Vulkan device (reflection broke), "
                    + "kender's direct Vulkan path is OFF even if MC runs on Vulkan", t);
            }
            return null;
        }
    }
    private static boolean deviceLookupFailed;

    public static boolean onVulkan() { return device() != null; }

    // raw VkImageView of an MC texture (by id), for the geo shader to sample. 0 = not loaded / not on Vulkan
    public static long textureImageView(net.minecraft.resources.Identifier id) {
        try {
            var tex = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id);
            if (tex == null) return 0L;
            var view = tex.getTextureView();
            if (view instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView v) return v.vkImageView();
        } catch (Throwable ignored) {}
        return 0L;
    }

    // MC's level lightmap — the 16x16 (block,sky) -> rgb table it rebuilds every tick from time of day,
    // dimension, night vision, gamma. sampling THIS is the only way our colors match vanilla; the old
    // hand-rolled curve in geo.rs was gray and wrong at night.
    public static long levelLightmapImageView() {
        try {
            var view = net.minecraft.client.Minecraft.getInstance().gameRenderer.levelLightmap();
            if (view instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView v) return v.vkImageView();
        } catch (Throwable ignored) {}
        return 0L;
    }

    // raw LWJGL pointers to hand to Rust (Panama). 0 = not on Vulkan / not ready
    public static long vkDeviceHandle() {
        VulkanDevice vd = device();
        try { return vd == null ? 0L : vd.vkDevice().address(); } catch (Throwable t) { return 0L; }
    }

    public static long vmaHandle() {
        VulkanDevice vd = device();
        try { return vd == null ? 0L : vd.vma(); } catch (Throwable t) { return 0L; }
    }

    public static long graphicsQueueHandle() {
        VulkanDevice vd = device();
        try { return vd == null ? 0L : vd.graphicsQueue().vkQueue().address(); } catch (Throwable t) { return 0L; }
    }

    public static int graphicsQueueFamily() {
        VulkanDevice vd = device();
        try { return vd == null ? -1 : vd.graphicsQueue().queueFamilyIndex(); } catch (Throwable t) { return -1; }
    }

    public static long vkInstanceHandle() {
        VulkanDevice vd = device();
        try { return vd == null ? 0L : vd.instance().vkInstance().address(); } catch (Throwable t) { return 0L; }
    }

    // device-sharing handshake: hand MC's instance/device/queue to the Rust renderer, once
    private static int initState; // 0 = not tried, 1 = shared ok, -1 = failed/not-vulkan
    public static boolean deviceShared() { return initState == 1; }

    // Anything that needs the shared device but may ask before it exists. Flywheel's backend pick is
    // the case that matters: it happens at level load, before the main render target is up, so Kender
    // honestly answers "not yet", Flywheel drops to off and never asks again.
    private static final java.util.List<Runnable> READY_HOOKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void onDeviceShared(Runnable hook) {
        if (hook == null) return;
        if (initState == 1) { hook.run(); return; }
        READY_HOOKS.add(hook);
    }

    private static void fireReady() {
        if (READY_HOOKS.isEmpty()) return;
        for (Runnable h : READY_HOOKS) {
            try { h.run(); } catch (Throwable t) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] device-shared hook blew up", t);
            }
        }
        READY_HOOKS.clear();
    }

    public static void tryInit() {
        if (initState != 0) return;
        logHandlesOnce();
        long inst = vkInstanceHandle(), dev = vkDeviceHandle(), q = graphicsQueueHandle();
        int fam = graphicsQueueFamily();
        if (inst == 0 || dev == 0 || q == 0 || fam < 0) {
            initState = -1; // not on Vulkan / not ready
            // no VkDevice to borrow -> the raw path is off and everything falls back to collect_submits.
            // say it once, with the handles, because "my blocks draw but slowly" always starts here.
            // NOT a Sodium thing, whatever older comments claimed: Sodium runs on MC's Vulkan backend too
            // since 0.9 and fires COLLECT_SUBMITS normally. Check the backend before blaming a mod.
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] NOT on the Vulkan backend — koperlib raw path OFF, "
                + "falling back to collect_submits. instance=0x{} device=0x{} queue=0x{} family={}. for the Vulkan path MC must "
                + "run its Vulkan backend (Video Settings > graphics backend).",
                Long.toHexString(inst), Long.toHexString(dev), Long.toHexString(q), fam);
            return;
        }
        int colorFmt = mainColorFormat(), depthFmt = mainDepthFormat();
        if (colorFmt == 0) { initState = 0; return; } // render target not ready yet — retry next frame, don't burn init
        try {
            int r = com.koper.koper_lib.kender.KenderBridge.vkInit(inst, dev, q, fam, colorFmt, depthFmt);
            initState = r == 0 ? 1 : -1;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-vk] kender_vk_init -> {} ({}) colorFmt={} depthFmt={}",
                r, initState == 1 ? "device shared" : "failed", colorFmt, depthFmt);
            if (initState == 1) fireReady();
        } catch (Throwable t) {
            initState = -1;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] init threw: {}", t.toString());
        }
    }

    // MC's real main-target VkFormat (via VulkanConst.toVk). the raw pipeline MUST match these or nothing rasterizes.
    // color at true, depth at false. 0 = target not ready / not on Vulkan.
    public static int mainColorFormat() { return targetFormat(true); }
    public static int mainDepthFormat() { return targetFormat(false); }

    private static int targetFormat(boolean color) {
        try {
            var rt = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (rt == null) return 0;
            var view = color ? rt.getColorTextureView() : rt.getDepthTextureView();
            if (view == null) return 0;
            return com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(view.texture().getFormat());
        } catch (Throwable t) { return 0; }
    }

    // dump the REAL field/method names of MC's Vulkan classes so we stop guessing where the command buffer lives
    public static void dumpVulkanInternals() {
        try {
            VulkanDevice vd = device();
            if (vd != null) {
                dumpClass("VulkanDevice", vd.getClass());
                // chase the ACTUAL command encoder object — that's where the live VkCommandBuffer lives
                Object enc = fieldValue(vd, "commandEncoder");
                if (enc != null) dumpClass("VulkanDevice.commandEncoder", enc.getClass());
            }
            // MC's main framebuffer — color/depth GpuTextureView (wrapping VkImageView) + size
            var mc = net.minecraft.client.Minecraft.getInstance();
            Object rt = tryCall(mc, "getMainRenderTarget");
            if (rt == null) rt = fieldValueDeep(mc, "mainRenderTarget");
            if (rt != null) {
                dumpFull("MainRenderTarget", rt);
                Object cv = tryCall(rt, "getColorTextureView", "getColorTexture");
                Object dv = tryCall(rt, "getDepthTextureView", "getDepthTexture");
                if (cv != null) dumpFull("MainRenderTarget.color", cv);
                if (dv != null) dumpFull("MainRenderTarget.depth", dv);
            } else {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] no main render target found on {}", mc.getClass().getName());
            }
            // the live render pass at this point (if any) carries the attachments MC is drawing into
            VulkanDevice vd2 = device();
            Object enc2 = vd2 == null ? null : fieldValue(vd2, "commandEncoder");
            Object rp = enc2 == null ? null : fieldValue(enc2, "currentRenderPass");
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-vk] currentRenderPass = {}", rp == null ? "null" : rp.getClass().getName());
            if (rp != null) dumpFull("VulkanRenderPass", rp);
        } catch (Throwable t) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender-vk] dumpVulkanInternals failed: {}", t.toString());
        }
    }

    private static Object fieldValue(Object obj, String name) {
        try { var f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
    }

    private static Object fieldValueDeep(Object obj, String name) {
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try { var f = k.getDeclaredField(name); f.setAccessible(true); return f.get(obj); } catch (Throwable ignored) {}
        }
        return null;
    }

    // dump every field WITH its value + every no-arg method — so we can spot which field holds the VkImageView/handle
    private static void dumpFull(String label, Object obj) {
        Class<?> c = obj.getClass();
        StringBuilder sb = new StringBuilder("[kender-vk] FULL ").append(label).append(" = ").append(c.getName());
        sb.append("\n  FIELDS:");
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (var f : k.getDeclaredFields()) {
                String val = "?";
                try { f.setAccessible(true); Object v = f.get(obj); val = v == null ? "null"
                        : (v.getClass().isArray() ? v.getClass().getSimpleName() + "[]" : String.valueOf(v)); }
                catch (Throwable ignored) {}
                if (val.length() > 90) val = val.substring(0, 90);
                sb.append("\n    ").append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(" = ").append(val);
            }
        }
        sb.append("\n  METHODS:");
        for (var m : c.getMethods()) {
            if (m.getParameterCount() == 0) sb.append("\n    ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append("()");
        }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info(sb.toString());
    }

    private static Object tryCall(Object obj, String... methods) {
        for (String m : methods) {
            try { var mm = obj.getClass().getMethod(m); mm.setAccessible(true); return mm.invoke(obj); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static void dumpClass(String label, Class<?> c) {
        StringBuilder sb = new StringBuilder("[kender-vk] DUMP ").append(label).append(" = ").append(c.getName());
        sb.append("\n  FIELDS:");
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (var f : k.getDeclaredFields()) {
                sb.append("\n    ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
            }
        }
        sb.append("\n  METHODS:");
        for (var m : c.getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("command") || n.contains("buffer") || n.contains("swap") || n.contains("image")
                || n.contains("view") || n.contains("depth") || n.contains("color") || n.contains("frame")
                || n.contains("width") || n.contains("height") || n.contains("attach")) {
                sb.append("\n    ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append("()");
            }
        }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info(sb.toString());
    }

    // one-shot proof that we can share MC's device with the Rust side — logged when F3 first asks
    private static boolean logged;
    public static void logHandlesOnce() {
        if (logged) return;
        logged = true;
        VulkanDevice vd = device();
        if (vd == null) { com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-vk] MC is NOT on the Vulkan backend (OpenGL) — direct path unavailable"); return; }
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-vk] shared MC Vulkan handles: VkDevice=0x{} vma=0x{} gfxQueue=0x{} family={}",
            Long.toHexString(vkDeviceHandle()), Long.toHexString(vmaHandle()),
            Long.toHexString(graphicsQueueHandle()), graphicsQueueFamily());
    }
}
