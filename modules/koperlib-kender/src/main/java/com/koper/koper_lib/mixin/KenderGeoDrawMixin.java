package com.koper.koper_lib.mixin;


import com.koper.koper_lib.kender.KenderBackend;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// at world-render HEAD: capture MC's cull-frustum (for COLLECT_SUBMITS) AND arm this frame's viewProj for
// the koperlib Vulkan path. the actual geo draw happens later, INSIDE MC's open world pass, from
// KenderPassInjectMixin (submitRenderPass) — that's the only place raw commands actually composite.
@Mixin(LevelRenderer.class)
public class KenderGeoDrawMixin {

    // 26.3 render(allocator, outline, camera, fog, fogColor, sky, consistentDepth): no delta tracker or model-view anymore
    @Inject(method = "render", at = @At("HEAD"))
    private void koperlib$captureFrame(GraphicsResourceAllocator alloc, boolean outline,
                                       CameraRenderState cam, GpuBufferSlice fog,
                                       Vector4f fogColor, boolean sky, boolean consistentDepth, CallbackInfo ci) {
        com.koper.koper_lib.kender.KenderFrame.setFrustum(cam.cullFrustum);
        try {
            if (com.koper.koper_lib.kender.KenderConfig.get().renderMode()
                    != com.koper.koper_lib.kender.KenderConfig.RenderMode.KOPERLIB) return;
            com.koper.koper_lib.kender.KenderVk.tryInit(); // share MC's device here so vulkanActive() can turn true
            if (!com.koper.koper_lib.kender.KenderFrame.vulkanActive()) return;
            KenderBackend.set(KenderBackend.VULKAN);
            // MC's view matrices on CameraRenderState freeze during rotation (they update on move, not on look),
            // so geometry stayed glued to the camera. build the view rotation OURSELVES from the live camera the
            // exact way MC's GameRenderer does: rotateX(pitch) then rotateY(yaw+180). live yaw/pitch off the camera.
            // EXACT match to MC's terrain: use the render state's own projection, view-rotation, AND position -- all
            // three the same objects LevelRenderer renders the world with. any other source (live camera pos/rotation)
            // drifts by a hair and, being angular, that hair grows with distance -> far blocks jittered while walking.
            var camPos = cam.pos;
            // floating origin QUANTIZED to a 256 grid: offsets stay small enough for float precision, but the
            // full instance re-upload happens once per 256 blocks walked instead of on EVERY block crossing
            // (that per-block-crossing rebuild + light re-query of the whole world was a walking fps eater)
            long ox = Math.floorDiv((long) Math.floor(camPos.x), 256L) * 256L;
            long oy = Math.floorDiv((long) Math.floor(camPos.y), 256L) * 256L;
            long oz = Math.floorDiv((long) Math.floor(camPos.z), 256L) * 256L;
            com.koper.koper_lib.kender.KenderFrame.setRenderOrigin(ox, oy, oz);
            Matrix4f vp = new Matrix4f(cam.projectionMatrix);
            // view bobbing: MC sways the world modelview while walking (Accessibility > View Bobbing) AFTER the view
            // rotation. it's not in cam.viewRotationMatrix, so replicate the exact GameRenderer.bobView here or our
            // geometry doesn't sway with the terrain -> angular drift that grows with distance while moving.
            var mc = Minecraft.getInstance();
            // damage tilt: MC swings the whole modelview when you get hit, and like bobView it is
            // NOT in cam.viewRotationMatrix. skipping it meant our geometry stayed put while the
            // terrain tilted, so geo mobs floated off their hitboxes and kontraption blocks tore
            // away from the ship for the length of the hurt. same order GameRenderer uses: hurt then bob
            koper$bobHurt(vp, cam, mc);

            if (mc.options.bobView().get() && cam.entityRenderState != null && cam.entityRenderState.isPlayer) {
                float wd = cam.entityRenderState.backwardsInterpolatedWalkDistance, bob = cam.entityRenderState.bob;
                float pi = (float) Math.PI;
                vp.translate(net.minecraft.util.Mth.sin(wd * pi) * bob * 0.5f, -Math.abs(net.minecraft.util.Mth.cos(wd * pi) * bob), 0f);
                vp.rotateZ((float) Math.toRadians(net.minecraft.util.Mth.sin(wd * pi) * bob * 3.0f));
                vp.rotateX((float) Math.toRadians(Math.abs(net.minecraft.util.Mth.cos(wd * pi - 0.2f) * bob) * 5.0f));
            }
            vp.mul(cam.viewRotationMatrix);
            vp.translate((float) (ox - camPos.x), (float) (oy - camPos.y), (float) (oz - camPos.z));
            float[] vpArr = new float[16];
            vp.get(vpArr);
            // day/night: stored sky-light is ALWAYS 15 on the surface — the actual darkness comes from
            // skyDarken (0 day .. 11 night, rain raises it). without this a night scene renders fullbright.
            // how many light LEVELS night takes off the sky, in 0..1 space. the shader subtracts
            // this the way vanilla does instead of scaling, which used to crush outdoor geometry
            float skyFactor = 0f;
            if (mc.level != null) skyFactor = mc.level.getSkyDarken() / 15f;
            com.koper.koper_lib.kender.KenderFrame.armFrame(vpArr, skyFactor);
        } catch (Throwable t) {
            if (!koper$frameFailed) {
                koper$frameFailed = true;
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kender] per-frame camera capture FAILED, Vulkan geo rendering has no matrices this session", t);
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean koper$frameFailed;

    // straight port of GameRenderer.bobHurt so our matrix moves with the world, not against it
    @org.spongepowered.asm.mixin.Unique
    private static void koper$bobHurt(Matrix4f vp,
            net.minecraft.client.renderer.state.level.CameraRenderState cam, Minecraft mc) {
        var e = cam.entityRenderState;
        if (e == null || !e.isLiving) return;

        float hurt = e.hurtTime;

        if (e.isDeadOrDying) {
            float dead = Math.min(e.deathTime, 20.0f);
            vp.rotateZ((float) Math.toRadians(40.0f - 8000.0f / (dead + 200.0f)));
        }
        if (hurt < 0.0f) return;

        hurt = hurt / e.hurtDuration;
        hurt = net.minecraft.util.Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
        float dir = e.hurtDir;

        vp.rotateY((float) Math.toRadians(-dir));
        float tilt = (float) (-hurt * 14.0 * mc.options.damageTiltStrength().get());
        vp.rotateZ((float) Math.toRadians(tilt));
        vp.rotateY((float) Math.toRadians(dir));
    }
}
