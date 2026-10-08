package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.sulkan.SulkanHandshake;
import com.koper.koper_lib.config.KoperLibConfig;

import com.koper.koper_lib.kender.KenderVk;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hook B — the koperlib render path. MC's frame graph opens a dynamic-rendering pass, records its draws,
// then calls submitRenderPass() to close it. we slip in right before that close: the pass is still OPEN,
// its color+depth attachments + viewport are bound, and its live VkCommandBuffer is recording. we append
// our instanced geo draws there. drawing at LevelRenderer TAIL never worked (wrong buffer + guessed formats);
// this is the spot that actually composites.
@Mixin(VulkanCommandEncoder.class)
public class KenderPassInjectMixin {

    @Shadow private VulkanRenderPass currentRenderPass;

    private static final java.util.Set<String> SEEN_LABELS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // The cull has to run BEFORE the world pass opens (compute is illegal inside one) and AFTER Create
    // has filled the API — and Create fills it after LevelRenderer.render HEAD, which is where the cull
    // used to be recorded. So it culled last frame's transforms and Create geometry had to opt out of
    // culling entirely. Here the pass is not open yet, so a transient buffer is legal, and everything
    // that is going to write instances this frame already has.
    @Inject(method = "createRenderPass", at = @At("HEAD"), require = 0)
    private void koperlib$cullBeforeWorldPass(
            com.mojang.renderpearl.api.commands.RenderPassDescriptor desc,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Object> cir) {
        try {
            if (com.koper.koper_lib.kender.KenderConfig.get().renderMode()
                    != com.koper.koper_lib.kender.KenderConfig.RenderMode.KOPERLIB) return;
            if (desc == null || desc.depthAttachment() == null) return;   // GUI/2D passes carry no depth
            // attachment formats live ONLY on the descriptor — VulkanRenderPass doesn't keep them, and a
            // shader mod's cascade target isn't a format we get to guess. Stash them for submitRenderPass.
            koperlib$grabFormats(desc);
            var label = desc.label();
            if (label == null) return;
            if (!koperlib$worldPass(String.valueOf(label.get()).toLowerCase())) return;
            com.koper.koper_lib.kender.KenderFrame.prepassCull();
        } catch (RuntimeException broken) { koperlib$fail("the pre-pass cull", broken); }
    }

    @Inject(method = "submitRenderPass", at = @At("HEAD"), require = 0)
    private void koperlib$injectGeo(CallbackInfo ci) {
        try {
            if (com.koper.koper_lib.kender.KenderConfig.get().renderMode()
                    != com.koper.koper_lib.kender.KenderConfig.RenderMode.KOPERLIB) return;
            KenderVk.tryInit();
            if (!KenderVk.deviceShared()) return;

            VulkanRenderPass pass = this.currentRenderPass;
            if (pass == null) return;
            var acc = (VulkanRenderPassAccessor) (Object) pass;
            // one-time-per-label dump: when a renderer renames the world pass (sodium did), this is the
            // log line that tells us what to match instead of guessing
            if (KoperLibConfig.get().debugMode && SEEN_LABELS.size() < 64) {
                String seen = acc.koperlib$label() == null ? "?" : String.valueOf(acc.koperlib$label().get());
                if (SEEN_LABELS.add(seen))
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[kender-geo] pass label '{}' depth={} {}x{}",
                        seen, acc.koperlib$hasDepth(), acc.koperlib$width(), acc.koperlib$height());
            }
            if (!acc.koperlib$hasDepth()) return;                         // world passes carry depth; skip GUI/2D

            int w = acc.koperlib$width(), h = acc.koperlib$height();

            var rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            boolean mainSized = rt != null && w == rt.width && h == rt.height;

            // A shader mod's cascades are their own targets, so the main-size check below throws them
            // away — that's why nothing kender draws was ever in the shadow map. Handle them here, but
            // ONLY when this really isn't the main framebuffer, or we'd eat the normal world draw.
            if (!mainSized) {
                if (SulkanHandshake.inShadowMap()) koperlib$sulkanShadowPass(acc, w, h);
                return;
            }

            // solids go after terrain; translucent KFX waits until MC's own particle pass
            // (DefaultChunkRenderer.createRenderPass) — match both or with sodium we never inject at all.
            String lbl = acc.koperlib$label() == null ? "" : String.valueOf(acc.koperlib$label().get());
            String low = lbl.toLowerCase();
            VkCommandBuffer cmd = acc.koperlib$cmd();
            if (cmd == null) return;
            if (com.koper.koper_lib.kender.KenderFrame.frameArmed() && koperlib$worldPass(low))
                com.koper.koper_lib.kender.KenderFrame.drawVulkanInPass(cmd.address(), w, h, acc.koperlib$label());
            // 26.3 draws terrain, entities and particles as debug groups inside this one pass, so the
            // entity and particle passes below never open on their own: draw those here, after the
            // blocks, before the pass closes
            if (koperlib$singleWorldPass(low)) {
                com.koper.koper_lib.kender.KenderFrame.drawEntitiesInPass(cmd.address(), w, h,
                    com.koper.koper_lib.kender.KenderEntityBatch.OPAQUE_PASS);
                com.koper.koper_lib.kender.KenderFrame.drawEntitiesInPass(cmd.address(), w, h,
                    com.koper.koper_lib.kender.KenderEntityBatch.TRANSLUCENT_PASS);
                if (com.koper.koper_lib.kender.KenderFrame.kfxFrameArmed())
                    com.koper.koper_lib.kender.KenderFrame.drawKfxInPass(cmd.address(), w, h);
            }
            if (low.endsWith("minecraft:pipeline/entity_cutout"))
                com.koper.koper_lib.kender.KenderFrame.drawEntitiesInPass(cmd.address(), w, h,
                    com.koper.koper_lib.kender.KenderEntityBatch.OPAQUE_PASS);
            if (low.endsWith("minecraft:pipeline/entity_translucent"))
                com.koper.koper_lib.kender.KenderFrame.drawEntitiesInPass(cmd.address(), w, h,
                    com.koper.koper_lib.kender.KenderEntityBatch.TRANSLUCENT_PASS);
            if (com.koper.koper_lib.kender.KenderFrame.kfxFrameArmed() && low.contains("particles - translucent"))
                com.koper.koper_lib.kender.KenderFrame.drawKfxInPass(cmd.address(), w, h);
        } catch (RuntimeException broken) { koperlib$fail("drawing into the world pass", broken); }
    }

    // the pass MC draws the world's solid geometry into. 26.2 and sodium name it after terrain or the
    // opaque layer; 26.3 calls it "Main", or "Solid" when improved transparency is on
    private static boolean koperlib$worldPass(String low) {
        return low.contains("opaque") || low.contains("terrain") || koperlib$singleWorldPass(low);
    }

    private static boolean koperlib$singleWorldPass(String low) {
        return low.equals("main") || low.equals("solid");
    }

    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // once per kind: this runs every frame, and a silent catch here is how a dead render path hides
    private static void koperlib$fail(String what, RuntimeException broken) {
        if (FAILED.add(what))
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kender] {} failed, Kender geometry is missing this frame", what, broken);
    }

    private static int koperlib$colorFmt, koperlib$depthFmt;

    private static void koperlib$grabFormats(com.mojang.renderpearl.api.commands.RenderPassDescriptor desc) {
        koperlib$colorFmt = 0;
        koperlib$depthFmt = 0;
        try {
            var colors = desc.colorAttachments();
            if (colors != null && !colors.isEmpty()) {
                var view = colors.get(0).textureView();
                if (view != null)
                    koperlib$colorFmt = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(view.texture().getFormat());
            }
            var depth = desc.depthAttachment();
            if (depth != null && depth.textureView() != null)
                koperlib$depthFmt = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(depth.textureView().texture().getFormat());
        } catch (RuntimeException broken) { koperlib$fail("reading the pass formats", broken); }
    }

    private static final java.util.Set<String> SHADOW_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final float[] CASCADE_VP = new float[16];

    // Sulkan cascade pass. Everything kender draws goes through its own pipeline, so none of it was
    // ever in this depth target — that's why the player's shadow walked straight through kontraktions,
    // cogwheels and geo block entities alike. Re-draw the instance buffers already on the GPU, from
    // the light's point of view. Rust builds a pipeline matching this target's formats on first use.
    private static long koperlib$smapFrame = -1;
    private static int koperlib$smapDone;

    private static void koperlib$sulkanShadowPass(VulkanRenderPassAccessor acc, int w, int h) {
        if (!com.koper.koper_lib.kender.KenderConfig.get().kenderShadowCast) return;
        VkCommandBuffer cmd = acc.koperlib$cmd();
        if (cmd == null || koperlib$depthFmt == 0) return;
        int cascade = SulkanHandshake.cascade();
        if (cascade < 0) return;

        // Two targets per cascade, refreshed on different clocks: terrain every 3/8/16/32 frames,
        // entities every 1/2/4/8. Put a moving kontra in the terrain one and its shadow is up to 32
        // frames stale; alternate between them and it visibly flickers. So route by what the geometry
        // IS — kontras ride the entity map, world-static geo rides the terrain map.
        String lbl = acc.koperlib$label() == null ? "" : String.valueOf(acc.koperlib$label().get());
        int wantDynamic = lbl.toLowerCase().contains("entity") ? 1 : 0;

        // sodium opens several passes per cascade — feed each (cascade, kind) once a frame
        long frame = com.koper.koper_lib.kender.KenderFrame.frameNo();
        if (frame != koperlib$smapFrame) { koperlib$smapFrame = frame; koperlib$smapDone = 0; }
        int bit = 1 << Math.min(15, cascade * 2 + wantDynamic);
        if ((koperlib$smapDone & bit) != 0) return;
        koperlib$smapDone |= bit;

        float[] vp = SulkanHandshake.cascadeViewProj(CASCADE_VP);
        if (vp == null) return;

        int drawn = com.koper.koper_lib.kender.KenderBridge.geoDrawShadowMap(
            cmd.address(), w, h, vp, koperlib$colorFmt, koperlib$depthFmt, wantDynamic);

        if (!KoperLibConfig.get().debugMode) return;
        String key = lbl + "|" + w + "x" + h + "|c" + cascade;
        if (SHADOW_SEEN.add(key) && SHADOW_SEEN.size() <= 32)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[Sulkan] cascade {} '{}' {}x{} {} -> {} models",
                cascade, lbl, w, h, wantDynamic == 1 ? "kontra" : "static", drawn);
    }
}
