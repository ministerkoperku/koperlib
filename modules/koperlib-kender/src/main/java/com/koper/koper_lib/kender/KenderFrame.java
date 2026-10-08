package com.koper.koper_lib.kender;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.koper.koper_lib.config.KoperLibConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * One Kender frame on the Vulkan path: armed at the start of the level render, culled before the
 * world pass opens, drawn inside it. Whatever puts geometry on the GPU (model blocks, the public
 * render API, entity batches) registers a {@link Contributor} and is asked to prepare its instances
 * when the frame is armed.
 *
 * <p>The floating render origin also lives here: instance matrices are stored relative to a point
 * near the camera, quantised so a full re-upload happens once per 256 blocks walked.
 */
public final class KenderFrame {
    private KenderFrame() {}

    /** Something that feeds geometry to the Vulkan frame. */
    public interface Contributor {
        /** CPU-side work for this frame: instance uploads, bone matrices. */
        void prepareFrame();

        /** The render origin moved; every origin-relative instance has to be rebuilt. */
        default void originChanged() {}

        /** A shader pack took over or let go; GPU-owned geometry must hand back to vanilla. */
        default void shaderFallback(boolean on) {}

        /** Drop everything, for a disconnect or reload. */
        default void reset() {}
    }

    private static final List<Contributor> CONTRIBUTORS = new CopyOnWriteArrayList<>();

    public static void register(Contributor contributor) {
        CONTRIBUTORS.add(contributor);
    }

    // MC's own camera cull frustum, captured each frame by the render mixin
    private static volatile net.minecraft.client.renderer.culling.Frustum FRUSTUM;
    public static void setFrustum(net.minecraft.client.renderer.culling.Frustum f) { FRUSTUM = f; }
    public static net.minecraft.client.renderer.culling.Frustum frustum() { return FRUSTUM; }

    // safety net: if no world pass consumed the armed frame for 60 frames straight, the label match is
    // dead (renamed pass, unknown renderer); latch to the CPU path so blocks stay visible, not gone
    private static int missedDraws;
    private static volatile boolean passLost;

    // resolved once per frame in armFrame: vulkanActive() runs per block, and asking a shader mod
    // over reflection that often would cost more than it saves
    private static volatile boolean SHADER_FALLBACK;

    public static boolean vulkanActive() {
        return !passLost
            && !SHADER_FALLBACK
            && KenderConfig.get().renderMode() == KenderConfig.RenderMode.KOPERLIB
            && KenderVk.deviceShared();
    }

    /** True while rendering is deliberately handed back to MC so a shader pack can see it. */
    public static boolean shaderFallback() { return SHADER_FALLBACK; }

    private static volatile boolean FRAME_ARMED;
    private static volatile boolean KFX_FRAME_ARMED;
    private static volatile float[] FRAME_VP; // MC's world->clip for this frame, from the HEAD mixin
    private static volatile float FRAME_SKY = 1f;
    private static long FRAME_NO;
    private static volatile boolean CULL_DONE;
    private static final java.util.Set<String> LOG_PASS = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Integer> LOG_ENTITY_PASS = ConcurrentHashMap.newKeySet();

    // armed at LevelRenderer.render HEAD. every CPU-side job for this frame happens here; the cull is
    // recorded later (prepassCull) and the draw itself happens inside the world pass
    public static void armFrame(float[] viewProj, float sky) {
        KenderBridge.syncDebug();
        boolean fallback = KenderConfig.get().kenderShaderCompat
            && com.koper.koper_lib.compat.sulkan.SulkanHandshake.shadersOn();
        if (fallback != SHADER_FALLBACK) {
            SHADER_FALLBACK = fallback;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Kender] shader pack {}: block rendering handed {}",
                fallback ? "on" : "off", fallback ? "back to MC so shaders can see it" : "to the Vulkan path");
            // instances already on the GPU would keep drawing under the shader path: doubled geometry
            if (fallback) VanillaBlockKender.clear();
            for (Contributor c : CONTRIBUTORS) c.shaderFallback(fallback);
        }
        if (FRAME_ARMED) {
            if (++missedDraws >= 60 && !passLost) {
                passLost = true;
                com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew(false);
                com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[kender] no world pass matched for 60 frames, falling back to the MC path "
                    + "(pass label changed? unknown renderer?). Blocks stay visible, just slower.");
            }
        } else missedDraws = 0;
        FRAME_VP = viewProj;
        FRAME_SKY = sky;
        FRAME_ARMED = true;
        if (KFX_FRAME_ARMED)
            com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew(false);
        KFX_FRAME_ARMED = com.koper.koper_lib.api.core.KenderEffectsBridge.gpuEnabled();
        FRAME_NO++;
        // MC can swap the lightmap texture on resource reload, so re-feed the view instead of caching it
        if (vulkanActive()) {
            long lm = KenderVk.levelLightmapImageView();
            if (lm != 0L) KenderBridge.geoSetLightmap(lm);
            KenderBridge.geoSetTime(com.koper.koper_lib.api.core.KenderEffectsBridge.nowTicks() / 20f);
        }
        KenderEntityBench.frame();
        KenderEntityBatch.beginFrame();
        for (Contributor c : CONTRIBUTORS) {
            try { c.prepareFrame(); }
            catch (Throwable t) {
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender] a frame contributor failed; its geometry is missing this frame", t);
            }
        }
        CULL_DONE = false;
    }

    public static boolean frameArmed() { return FRAME_ARMED; }
    public static long frameNo() { return FRAME_NO; }
    public static boolean kfxFrameArmed() { return KFX_FRAME_ARMED; }

    /**
     * Flushes pending instance writes, then records the GPU frustum cull. Called from the
     * createRenderPass mixin, after Create/Flywheel filled the API and before the world pass opens.
     */
    public static void prepassCull() {
        if (CULL_DONE || !FRAME_ARMED) return;
        CULL_DONE = true;
        float[] vp = FRAME_VP;
        if (vp == null) return;
        try { com.koper.koper_lib.api.render.KenderRenderAPI.flush(ORIG_X, ORIG_Y, ORIG_Z); }
        catch (Throwable t) { com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[kender-api] prepass flush failed", t); }
        try { tryRecordCull(vp); } catch (Throwable ignored) {}
    }

    private static void tryRecordCull(float[] vp) {
        // small scenes skip the transient buffer: direct draws are cheaper than the cull below ~512 per model
        if (KenderBridge.geoCullWanted() != 1) return;
        var cmd = KenderVk.beginTransientCmd();
        if (cmd == null) return;
        int r = KenderBridge.geoRecordCull(cmd.address(), vp);
        if (r != 0) org.lwjgl.vulkan.VK10.vkEndCommandBuffer(cmd); // rust ends it on success
        KenderVk.executeCmd(cmd);
    }

    // floating origin, quantised to a 256 grid by the caller
    static volatile long ORIG_X, ORIG_Y, ORIG_Z;

    public static void setRenderOrigin(long x, long y, long z) {
        if (x != ORIG_X || y != ORIG_Y || z != ORIG_Z) {
            ORIG_X = x; ORIG_Y = y; ORIG_Z = z;
            for (Contributor c : CONTRIBUTORS) c.originChanged();
            com.koper.koper_lib.api.render.KenderRenderAPI.originChanged();
        }
    }

    public static long renderOriginX() { return ORIG_X; }
    public static long renderOriginY() { return ORIG_Y; }
    public static long renderOriginZ() { return ORIG_Z; }

    // world pass entry for instanced geometry. KFX waits for MC's translucent particle pass so
    // entities and block entities have already populated depth
    public static void drawVulkanInPass(long cmd, int w, int h, java.util.function.Supplier<String> label) {
        float[] viewProj = FRAME_VP;
        FRAME_ARMED = false; // consumed: one draw per frame whatever number of world passes follow
        if (viewProj == null) return;
        if (KoperLibConfig.get().debugMode && label != null && LOG_PASS.add(safeLabel(label)))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender] injecting into world pass '{}' {}x{}", safeLabel(label), w, h);
        // moving blocks and Flywheel fill the public API after render HEAD; flush here or they trail a frame
        try { com.koper.koper_lib.api.render.KenderRenderAPI.flush(ORIG_X, ORIG_Y, ORIG_Z); }
        catch (Throwable t) { com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[kender-api] frame flush failed", t); }
        KenderBridge.geoDrawInPass(cmd, w, h, viewProj, FRAME_SKY);
    }

    public static void drawEntitiesInPass(long cmd, int w, int h, int pass) {
        float[] viewProj = FRAME_VP;
        if (viewProj == null || !KenderEntityBatch.consumePass(pass)) return;
        try { KenderEntityBatch.flush(); }
        catch (Throwable t) { com.koper.koper_lib.coremod.KoperCore.LOGGER.debug("[kender-entity] frame flush failed", t); }
        int rc = KenderBridge.geoDrawEntityInPass(cmd, w, h, viewProj, FRAME_SKY, pass);
        if (rc == 0) KenderEntityBatch.markDrew(pass);
        if (KoperLibConfig.get().debugMode && LOG_ENTITY_PASS.add(pass))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-entity] draw pass={} rc={} {}x{} stats={}",
                pass, rc, w, h, KenderEntityBatch.stats());
    }

    public static void drawKfxInPass(long cmd, int w, int h) {
        float[] viewProj = FRAME_VP;
        KFX_FRAME_ARMED = false;
        if (viewProj == null || !com.koper.koper_lib.api.core.KenderEffectsBridge.gpuEnabled()) {
            com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew(false);
            return;
        }
        // KFX instances are origin-relative too; the real camera here would subtract movement twice
        int kfx = KenderBridge.kfxDrawInPass(cmd, w, h, viewProj,
            com.koper.koper_lib.api.core.KenderEffectsBridge.nowTicks(), ORIG_X, ORIG_Y, ORIG_Z);
        com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew(kfx > 0);
    }

    /** Back to a clean frame: after a disconnect or a reload drops every GPU instance. */
    public static void reset() {
        passLost = false;
        missedDraws = 0;
        FRAME_ARMED = false;
        KFX_FRAME_ARMED = false;
        com.koper.koper_lib.api.core.KenderEffectsBridge.gpuDrew(false);
        VanillaBlockKender.clear();
        KenderEntityBatch.clear();
        for (Contributor c : CONTRIBUTORS) c.reset();
    }

    private static String safeLabel(java.util.function.Supplier<String> s) {
        try { String v = s.get(); return v == null ? "?" : v; } catch (Throwable t) { return "?"; }
    }

    // tint as VALUE r + g*256 + b*65536: exact in a float's 24-bit mantissa, never bitcast rgba8 into a float
    public static float tintEnc(int argb) {
        return (argb >> 16 & 255) | (argb >> 8 & 255) << 8 | (argb & 255) << 16;
    }

    // packed light at the block (sky<<20 | block<<4), sampled at the pos AND above it: an opaque block
    // zeroes the light inside itself, which made model blocks read patchy darkness
    public static int packedLight(Level level, BlockPos pos) {
        var le = level.getLightEngine();
        var bll = le.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        var sll = le.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        BlockPos up = pos.above();
        int bl = Math.max(bll.getLightValue(pos), bll.getLightValue(up));
        int sl = Math.max(sll.getLightValue(pos), sll.getLightValue(up));
        return (sl << 20) | (bl << 4);
    }
}
