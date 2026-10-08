package com.koper.koper_lib.kfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

// additive soft halos around particles on the portable path. vanilla's lightning pipeline
// (POSITION_COLOR, src_alpha + one) minus its depth write, so overlapping halos add up instead of
// clipping each other into hard squares
public final class KfxGlow {
    private static final int SEGMENTS = 10;
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];
    private static RenderType type;
    private static boolean broken;

    // halo strength for the light around the camera: full in the dark, toned down in daylight where
    // additive light only washes the scene out
    static float ambient = 1.0f;

    // billboard axes of the current frame, set before the glow pass draws
    static float lx, ly, lz, ux, uy, uz;

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2 * i / SEGMENTS;
            COS[i] = (float)Math.cos(a);
            SIN[i] = (float)Math.sin(a);
        }
    }

    private KfxGlow() {}

    // null when the pipeline could not be built; the error is logged once and particles still draw
    static RenderType type() {
        if (type != null || broken) return type;
        try {
            RenderPipeline src = RenderPipelines.LIGHTNING;
            var b = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("koper_lib", "pipeline/kfx_glow"));
            b.withVertexShader(src.getShaders().get(ShaderType.VERTEX));
            b.withFragmentShader(src.getShaders().get(ShaderType.FRAGMENT));
            for (var e : src.getShaderDefines().values().entrySet()) {
                String v = e.getValue();
                try { b.withShaderDefine(e.getKey(), Integer.parseInt(v)); }
                catch (NumberFormatException notInt) { b.withShaderDefine(e.getKey(), Float.parseFloat(v)); }
            }
            for (String flag : src.getShaderDefines().flags()) b.withShaderDefine(flag);
            for (var layout : src.getBindGroupLayouts()) b.withBindGroupLayout(layout);
            var formats = src.getVertexFormatBindings();
            for (int i = 0; i < formats.size(); i++) if (formats.get(i) != null) b.withVertexBinding(i, formats.get(i));
            b.withPrimitiveTopology(src.getPrimitiveTopology());
            b.withPolygonMode(src.getPolygonMode());
            b.withCull(false);
            b.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));
            var targets = src.getColorTargetStates();
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i) != null) b.withColorTargetState(i, targets.get(i));
                else b.withUnusedColorTargetState(i);
            }
            b.withPushConstantSize(src.pushConstantSize());
            RenderSetup setup = RenderSetup.builder(b.build()).sortOnUpload().createRenderSetup();
            var create = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            create.setAccessible(true);
            type = (RenderType) create.invoke(null, "koper_kfx_glow", setup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                "[KFX] glow halos do not work: can't build the additive glow render type, particles draw without halos", e);
        }
        return type;
    }

    static void setCamera(org.joml.Vector3fc left, org.joml.Vector3fc up) {
        lx = left.x(); ly = left.y(); lz = left.z();
        ux = up.x(); uy = up.y(); uz = up.z();
    }

    // round camera-facing halo: bright middle, a soft shoulder, zero at the rim
    static void halo(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float radius, int color) {
        int a = Math.round(((color >>> 24) & 255) * ambient);
        if (a == 0 || radius <= 0) return;
        color = (color & 0x00FFFFFF) | (a << 24);
        int rgb = color & 0x00FFFFFF;
        int mid = rgb | (Math.round(a * 0.38f) << 24);
        float r1 = radius * 0.38f;
        for (int i = 0; i < SEGMENTS; i++) {
            float ax0 = (lx * COS[i] + ux * SIN[i]), ay0 = (ly * COS[i] + uy * SIN[i]), az0 = (lz * COS[i] + uz * SIN[i]);
            float ax1 = (lx * COS[i + 1] + ux * SIN[i + 1]), ay1 = (ly * COS[i + 1] + uy * SIN[i + 1]), az1 = (lz * COS[i + 1] + uz * SIN[i + 1]);
            vertex(pose, c, x, y, z, color);
            vertex(pose, c, x + ax0 * r1, y + ay0 * r1, z + az0 * r1, mid);
            vertex(pose, c, x + ax1 * r1, y + ay1 * r1, z + az1 * r1, mid);
            vertex(pose, c, x, y, z, color);
            vertex(pose, c, x + ax0 * r1, y + ay0 * r1, z + az0 * r1, mid);
            vertex(pose, c, x + ax0 * radius, y + ay0 * radius, z + az0 * radius, rgb);
            vertex(pose, c, x + ax1 * radius, y + ay1 * radius, z + az1 * radius, rgb);
            vertex(pose, c, x + ax1 * r1, y + ay1 * r1, z + az1 * r1, mid);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, int color) {
        c.addVertex(pose, x, y, z).setColor(color);
    }
}
