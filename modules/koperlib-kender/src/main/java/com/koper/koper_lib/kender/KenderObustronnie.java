package com.koper.koper_lib.kender;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 26.3 lights the back of a face from behind (PER_FACE_LIGHTING + gl_FrontFacing) in entity_cutout and
// entity_translucent. bedrock and 26.2 light both sides the same. a bedrock model with culling off shows
// its own inside through every see-through pixel, and that inside came out pitch black: the A&S helmet,
// the chestplate, anything with an alpha hole. same pipelines as vanilla's, minus that one define
public final class KenderObustronnie {

    private static RenderPipeline cutout, translucent;
    private static final Map<Identifier, RenderType> CUTOUT = new ConcurrentHashMap<>(), TRANSLUCENT = new ConcurrentHashMap<>();

    private KenderObustronnie() {}

    public static RenderType cutout(Identifier tex) {
        if (cutout == null) cutout = bezPerFace(RenderPipelines.ENTITY_CUTOUT, "entity_cutout_two_sided");
        return CUTOUT.computeIfAbsent(tex, t -> typ("koper_entity_cutout_two_sided", cutout, t));
    }

    public static RenderType translucent(Identifier tex) {
        if (translucent == null) translucent = bezPerFace(RenderPipelines.ENTITY_TRANSLUCENT, "entity_translucent_two_sided");
        return TRANSLUCENT.computeIfAbsent(tex, t -> typ("koper_entity_translucent_two_sided", translucent, t));
    }

    private static RenderType typ(String name, RenderPipeline p, Identifier tex) {
        RenderSetup setup = RenderSetup.builder(p).withTexture("Sampler0", tex).useLightmap().useOverlay()
            .affectsCrumbling().setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE).createRenderSetup();
        try {
            if (STWORZ == null) {
                STWORZ = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
                STWORZ.setAccessible(true);
            }
            return (RenderType) STWORZ.invoke(null, name, setup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!zepsute) {
                zepsute = true;
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kender] can't make two sided entity render types, models with see-through pixels get BLACK insides again", e);
            }
            return p == cutout ? net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(tex)
                : net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(tex);
        }
    }

    private static java.lang.reflect.Method STWORZ;
    private static boolean zepsute;

    // the vanilla pipeline field by field, so a later mojang change to entity_cutout comes along for free
    static RenderPipeline bezPerFace(RenderPipeline src, String path) {
        var b = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("koper_lib", "pipeline/" + path));
        b.withVertexShader(src.getShaders().get(ShaderType.VERTEX));
        b.withFragmentShader(src.getShaders().get(ShaderType.FRAGMENT));
        for (var e : src.getShaderDefines().values().entrySet()) {
            String v = e.getValue();
            try { b.withShaderDefine(e.getKey(), Integer.parseInt(v)); }
            catch (NumberFormatException notInt) { b.withShaderDefine(e.getKey(), Float.parseFloat(v)); }
        }
        for (String flag : src.getShaderDefines().flags()) if (!flag.equals("PER_FACE_LIGHTING")) b.withShaderDefine(flag);
        for (var layout : src.getBindGroupLayouts()) b.withBindGroupLayout(layout);
        var formats = src.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) if (formats.get(i) != null) b.withVertexBinding(i, formats.get(i));
        b.withPrimitiveTopology(src.getPrimitiveTopology());
        b.withPolygonMode(src.getPolygonMode());
        b.withCull(src.isCull());
        b.withDepthStencilState(java.util.Optional.ofNullable(src.getDepthStencilState()));
        var targets = src.getColorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) != null) b.withColorTargetState(i, targets.get(i));
            else b.withUnusedColorTargetState(i);
        }
        b.withPushConstantSize(src.pushConstantSize());
        return b.build();
    }
}
