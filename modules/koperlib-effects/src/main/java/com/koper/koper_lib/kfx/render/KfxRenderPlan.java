package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;

import java.util.List;

public record KfxRenderPlan(List<Node> nodes, KfxQuality quality, Cost cost) {
    public KfxRenderPlan { nodes = List.copyOf(nodes); }
    public record Node(String id, Identifier primitive, Identifier material, boolean decorative, int cost) {}
    public record Cost(int particles, int geometryUnits, int batches) {}
}
