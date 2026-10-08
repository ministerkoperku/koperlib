package com.koper.koper_lib.api.core;

import net.minecraft.client.renderer.rendertype.RenderType;

// render types the overlay drawers can't build themselves. kodel builds them and drops them here
// at client init, everyone else just asks
public final class KoperOverlayTypes {

    private static volatile RenderType seeThroughQuads;

    private KoperOverlayTypes() {}

    public static void installSeeThroughQuads(RenderType type) {
        seeThroughQuads = type;
    }

    // POSITION_COLOR quads, translucent, depth test always passes. null until kodel installs it
    public static RenderType seeThroughQuads() {
        return seeThroughQuads;
    }
}
