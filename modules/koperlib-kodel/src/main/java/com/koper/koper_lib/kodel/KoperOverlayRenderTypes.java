package com.koper.koper_lib.kodel;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

// overlay render types for the rest of koperlib. has to live here — RenderType.create and the
// private DEBUG_FILLED snippet are only widened by this module
public final class KoperOverlayRenderTypes {

    private static RenderType seeThroughQuads;

    private KoperOverlayRenderTypes() {}

    public static void install() {
        try {
            com.koper.koper_lib.api.core.KoperOverlayTypes.installSeeThroughQuads(seeThroughQuads());
        } catch (Throwable e) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn(
                "[kender] see-through overlay type unavailable: {}", e.toString());
        }
    }

    // debug filled box setup (POSITION_COLOR quads, translucent, no cull) but the depth test always
    // passes, so a connection point behind a wheel or a wall still shows. no depth write, obviously
    public static RenderType seeThroughQuads() {
        if (seeThroughQuads != null) return seeThroughQuads;
        RenderPipeline pipeline = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    "koper_lib", "pipeline/koper_see_through"))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withCull(false)
                .build());
        seeThroughQuads = RenderType.create("koperlib_see_through",
            RenderSetup.builder(pipeline).createRenderSetup());
        return seeThroughQuads;
    }
}
