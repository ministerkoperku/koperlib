package com.koper.koper_lib.kender;

import com.koper.koper_lib.mixin.ModelPartAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

// vanilla ModelPart trees use the same one-bone-per-vertex Vulkan skinning as Kodel models.
public final class VanillaEntityKender {
    private static final ThreadLocal<Matrix4f> MATRIX = ThreadLocal.withInitial(Matrix4f::new);

    private VanillaEntityKender() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean submit(EntityModel model, Object rawState, PoseStack poseStack,
                                 OrderedSubmitNodeCollector collector, Identifier texture,
                                 RenderType renderType, int light, int overlay, int tint,
                                 int outlineColor, Object crumblingOverlay) {
        return submitModel(model, rawState, poseStack, collector, texture, renderType,
            light, overlay, tint, outlineColor, crumblingOverlay);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean submitModel(Model model, Object rawState, PoseStack poseStack,
                                      OrderedSubmitNodeCollector collector, Identifier texture,
                                      RenderType renderType, int light, int overlay, int tint,
                                      int outlineColor, Object crumblingOverlay) {
        if (!(rawState instanceof EntityRenderState) || overlay != OverlayTexture.NO_OVERLAY
                || outlineColor != 0 || crumblingOverlay != null || model == null
                || !KenderEntityBatch.worldCollector(collector)) { KenderEntityBatch.reject(); return false; }

        try { model.setupAnim(rawState); }
        catch (Throwable t) { KenderEntityBatch.reject(); return false; }

        var mc = Minecraft.getInstance();
        var camera = mc.levelRenderer == null ? null : mc.levelRenderer.levelRenderState.cameraRenderState;
        Matrix4f transform = KenderEntityBatch.originRelative(poseStack.last().pose(), camera, MATRIX.get());
        int bones = model.allParts().size();
        boolean ok = KenderEntityBatch.submit(model, model.getClass().getName(),
            () -> bake(model.root()), texture, renderType, transform, light, tint, bones,
            out -> appendBones(model.root(), out), 0, rawState);
        if (ok) {
            KenderEntityBatch.ensurePass(collector, poseStack, renderType, texture);
        }
        return ok;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean submitLayer(Model model, Object rawState, PoseStack poseStack,
                                      OrderedSubmitNodeCollector collector, Identifier texture,
                                      RenderType renderType, int light, int tint, int order,
                                      float uOffset, float vOffset) {
        if (!(rawState instanceof EntityRenderState) || model == null
                || !KenderEntityBatch.hasGpuState(rawState)
                || !KenderEntityBatch.worldCollector(collector)) { KenderEntityBatch.reject(); return false; }

        var mc = Minecraft.getInstance();
        var camera = mc.levelRenderer == null ? null : mc.levelRenderer.levelRenderState.cameraRenderState;
        Matrix4f transform = KenderEntityBatch.originRelative(poseStack.last().pose(), camera, MATRIX.get());
        boolean shared = KenderEntityBatch.submitSharedLayer(rawState, model,
            model.getClass().getName() + "#layer" + order, () -> bake(model.root()), texture,
            renderType, transform, light, tint, order, uOffset, vOffset);
        if (shared) {
            KenderEntityBatch.ensurePass(collector, poseStack, renderType, texture);
            return true;
        }

        try { model.setupAnim(rawState); }
        catch (Throwable t) { KenderEntityBatch.reject(); return false; }
        boolean ok = KenderEntityBatch.submit(model, model.getClass().getName() + "#layer" + order,
            () -> bake(model.root()), texture, renderType, transform, light, tint, model.allParts().size(),
            out -> appendBones(model.root(), out), order, null, uOffset, vOffset);
        if (ok) KenderEntityBatch.ensurePass(collector, poseStack, renderType, texture);
        return ok;
    }

    private static float[] bake(ModelPart root) {
        FloatArrayList out = new FloatArrayList();
        bakePart(root, out, new int[]{0});
        return out.toFloatArray();
    }

    private static void bakePart(ModelPart part, FloatArrayList out, int[] ordinal) {
        int bone = ordinal[0]++;
        ModelPartAccessor access = (ModelPartAccessor)(Object)part;
        for (ModelPart.Cube cube : access.koperlib$cubes()) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                float nx = polygon.normal().x(), ny = polygon.normal().y(), nz = polygon.normal().z();
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    out.add(vertex.worldX()); out.add(vertex.worldY()); out.add(vertex.worldZ());
                    out.add(vertex.u()); out.add(vertex.v());
                    out.add(nx); out.add(ny); out.add(nz); out.add(bone);
                }
            }
        }
        for (ModelPart child : access.koperlib$children().values()) bakePart(child, out, ordinal);
    }

    private static void appendBones(ModelPart root, FloatArrayList out) {
        appendPart(root, new PoseStack(), out, false, new float[16]);
    }

    private static void appendPart(ModelPart part, PoseStack poseStack, FloatArrayList out,
                                   boolean hiddenByParent, float[] scratch) {
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        boolean hidden = hiddenByParent || !part.visible;
        if (hidden || part.skipDraw) java.util.Arrays.fill(scratch, 0f);
        else poseStack.last().pose().get(scratch);
        out.addElements(out.size(), scratch);
        for (ModelPart child : ((ModelPartAccessor)(Object)part).koperlib$children().values())
            appendPart(child, poseStack, out, hidden, scratch);
        poseStack.popPose();
    }
}
