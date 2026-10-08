package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KodelKenderBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

// draws a kodel mob. this used to live inside kopermod, which meant nothing else could
// use it; it is the same code, just somewhere the other mods can reach.
//
// kender first, blaze3d when kender says no. the pose is built in one pass: the clip
// being faded out of, the one being faded into, and the overlay on top
public final class KodelEntityRender {

    // one full limb cycle in vanilla's limbSwing units. 0.6662 is the constant every
    // vanilla model puts inside its cos, so 2*PI over it is one stride
    public static final float GAIT_CYCLE = (float) (Math.PI * 2 / 0.6662);

    private KodelEntityRender() {}

    // one bone matrix buffer per render thread instead of one per frame
    private static final ThreadLocal<Scratch> MATS = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        private float[] data = new float[0];

        float[] take(int need) {
            if (data.length < need) data = new float[need];
            return data;
        }
    }

    /// what to draw this frame. fill it from whatever render state you already have
    public static final class Poza {
        public String model;
        public Identifier texture;

        public String clip;
        public double clipTime;
        public boolean gait;

        /// the clip still fading out, null when there is no transition running
        public String prevClip;
        public double prevClipTime;
        public boolean prevGait;
        /// 0 is all prevClip, 1 is all clip
        public float clipMix = 1f;

        /// laid over the bones it owns, not faded by clipMix
        public String overlay;
        public double overlayTime;
        public float overlayWeight = 1f;

        /// how far the mob has actually walked, drives any clip marked gait
        public float limbSwing;

        public float bodyYaw;
        public float scale = 1f;
        public int light = 0x00F000F0;
        public int tint = 0xFFFFFFFF;

        /// the drawn entity, so bones others asked about (anchors, damage boxes) get published.
        /// -1 publishes nothing. x/y/z is where it is drawn this frame
        public int entityId = -1;
        public double x, y, z;
        /// bones whose world position combat needs every frame, null for none
        public java.util.Set<String> trackedBones;
    }

    /// false when this model has no kodel, so a caller can fall through to whatever
    /// it used before
    public static boolean submit(Poza poza, PoseStack poseStack, SubmitNodeCollector tasks,
                                 CameraRenderState camera) {
        if (poza == null || poza.model == null || poza.texture == null) return false;
        KodelBook.Entry entry = KodelBook.get(poza.model);
        if (entry == null || entry.model().bones.isEmpty()) return false;

        KodelModel model = entry.model();
        boolean fading = poza.prevClip != null && poza.clipMix < 1f;
        String base = fading ? poza.prevClip : poza.clip;
        float tBase = clipTime(poza.model, base, fading ? poza.prevGait : poza.gait,
            poza.limbSwing, fading ? poza.prevClipTime : poza.clipTime);
        String fadeTo = fading ? poza.clip : null;
        float tFade = clipTime(poza.model, fadeTo, poza.gait, poza.limbSwing, poza.clipTime);

        // TAKE THE RETURN. when a mob has no clip at all -- kapoka standing still has
        // none, it only has a walk -- poseLayered hands back the cached bind pose and
        // never touches the buffer, so using the buffer drew every bone as a zero
        // matrix and the whole model collapsed into a point. that was the flicker
        float[] pose = KodelBook.poseLayered(poza.model, base, tBase, fadeTo, tFade,
            fading ? poza.clipMix : 0f,
            poza.overlay, (float) poza.overlayTime, poza.overlayWeight, null);
        if (pose == null) return false;
        if (poza.entityId >= 0) publishBones(poza, model, pose);

        poseStack.pushPose();
        poseStack.rotate(Axis.YP.rotationDegrees(180.0f - poza.bodyYaw));
        poseStack.scale(poza.scale, poza.scale, poza.scale);
        poseStack.translate(0, 0.01f, 0);
        RenderType renderType = RenderTypes.entityCutout(poza.texture);

        if (!kender(entry, model, pose, poseStack, tasks, renderType, poza, camera)) {
            tasks.submitCustomGeometry(poseStack, renderType, (snapshot, consumer) ->
                KodelModelRender.render(model, pose, snapshot.pose(), consumer,
                    poza.light, OverlayTexture.NO_OVERLAY, poza.tint));
        }
        poseStack.popPose();
        return true;
    }

    /// the drawn pose, in the world, for whoever asked: bone anchors and combat's damage bones
    private static void publishBones(Poza poza, KodelModel model, float[] pose) {
        boolean anchors = KodelBoneAnchors.wants(poza.entityId);
        boolean tracked = poza.trackedBones != null && !poza.trackedBones.isEmpty();
        if (!anchors && !tracked) return;
        // the same chain the draw applies: body yaw, scale, the lift off the floor, pixels to blocks
        Matrix4f toWorld = new Matrix4f()
            .rotateY((float) Math.toRadians(180.0f - poza.bodyYaw))
            .scale(poza.scale)
            .translate(0, 0.01f, 0)
            .scale(1f / 16f);
        if (anchors) KodelBoneAnchors.capture(poza.entityId, poza.x, poza.y, poza.z, toWorld, model, pose);
        if (!tracked) return;
        Matrix4f bone = new Matrix4f();
        org.joml.Vector3f at = new org.joml.Vector3f();
        for (int i = 0; i < model.bones.size(); i++) {
            KodelModel.KodelBone b = model.bones.get(i);
            if (!poza.trackedBones.contains(b.name)) continue;
            bone.set(pose, i * KodelSampler.MAT4_FLOATS);
            new Matrix4f(toWorld).mul(bone).transformPosition(b.pivot[0], b.pivot[1], b.pivot[2], at);
            com.koper.koper_lib.api.core.KoperBonePositions.update(poza.entityId, b.name,
                new net.minecraft.world.phys.Vec3(poza.x + at.x, poza.y + at.y, poza.z + at.z));
        }
    }

    /// a pose somebody else already computed (bedrock actors, anything with its own animation
    /// system). same two lanes as submit: kender when it can, custom geometry when it can't.
    /// key names the mesh for kender's vram cache, one key per distinct model
    public static void submitPosed(KodelModel model, String key, float[] pose, Identifier texture,
                                   RenderType renderType, PoseStack poseStack, SubmitNodeCollector tasks,
                                   CameraRenderState camera, int light, int overlay, int tint) {
        var collector = tasks.order(0);
        if (overlay == OverlayTexture.NO_OVERLAY && KodelKenderBridge.available(collector)) {
            Matrix4f matrix = KodelKenderBridge.originRelative(poseStack.last().pose(), camera, new Matrix4f());
            float[] mats = KodelModelRender.boneMatricesForKender(pose, MATS.get().take(pose.length));
            boolean ok = KodelKenderBridge.submit(model, key, () -> KodelModelRender.bakeSkinned(model), texture,
                renderType, matrix, light, tint, KodelModelRender.boneCount(model), mats);
            if (ok) {
                KodelKenderBridge.ensurePass(collector, poseStack, renderType, texture);
                return;
            }
        }
        tasks.submitCustomGeometry(poseStack, renderType, (snapshot, consumer) ->
            KodelModelRender.render(model, pose, snapshot.pose(), consumer, light, overlay, tint));
    }

    private static boolean kender(KodelBook.Entry entry, KodelModel model, float[] pose,
                                  PoseStack poseStack, SubmitNodeCollector tasks,
                                  RenderType renderType, Poza poza, CameraRenderState camera) {
        var collector = tasks.order(0);
        if (!KodelKenderBridge.available(collector)) return false;
        Matrix4f matrix = KodelKenderBridge.originRelative(poseStack.last().pose(), camera, new Matrix4f());
        float[] mats = KodelModelRender.boneMatricesForKender(pose, MATS.get().take(pose.length));
        // the model object, not the Entry. Entry is a record, so using it as a map key
        // hashes every field of it on every submit; the model is a plain class and its
        // identity is exactly what "same mesh" means here
        boolean ok = KodelKenderBridge.submit(model, "kodel:" + poza.model,
            () -> KodelModelRender.bakeSkinned(model), poza.texture, renderType, matrix,
            poza.light, poza.tint, KodelModelRender.boneCount(model), mats);
        if (ok) KodelKenderBridge.ensurePass(collector, poseStack, renderType, poza.texture);
        return ok;
    }

    /// locomotion runs off how far the mob actually walked; everything else off the
    /// clock. driving a walk cycle off the clock makes it walk in place
    public static float clipTime(String model, String clip, boolean gait,
                                 float limbSwing, double wallClock) {
        if (clip == null) return 0f;
        if (!gait) return (float) wallClock;
        float length = clipLength(model, clip);
        if (length <= 0f) return (float) wallClock;
        return limbSwing / GAIT_CYCLE * length;
    }

    /// seconds, or 0 when this model has no kodel or no such clip
    public static float clipLength(String model, String clip) {
        KodelBook.Entry entry = KodelBook.get(model);
        if (entry == null) return 0f;
        KodelAnimation anim = entry.clip(clip);
        return anim == null ? 0f : anim.length;
    }

    /// a clip whose name reads like locomotion. override the guess at the call site
    /// when a clip moves the mob but is not named for it
    public static boolean looksLikeGait(String clip) {
        if (clip == null) return false;
        String lower = clip.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("walk") || lower.contains("run") || lower.contains("swim")
            || lower.contains("fly") || lower.contains("crawl");
    }

    /// how far past its own hitbox this model reaches, in blocks: sideways, up, down
    public static float[] cullingReach(String model) {
        return KodelBook.cullingReach(model);
    }
}
