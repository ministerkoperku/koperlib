package com.koper.koper_lib.api.attachment;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.impl.client.rendering.level.LevelRenderContextImpl;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KoperAttachmentRendererTest {
    private static LevelRenderContextImpl context() {
        var context = new LevelRenderContextImpl();
        context.prepare(null, null, new LevelRenderState(), new SubmitNodeStorage());
        context.setPoseStack(new PoseStack());
        return context;
    }

    // A depth-disabled draw in Main is still covered by Kender's later Vulkan draw.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void portReachesTheFinalOverlayPassWithoutKodel(boolean improvedTransparency) throws Exception {
        var context = context();
        var gizmos = new SimpleGizmoCollector();
        var method = KoperAttachmentRenderer.class.getDeclaredMethod("point",
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.class,
            double.class, double.class, double.class, Vector3f.class, int.class);
        method.setAccessible(true);
        try (var ignored = Gizmos.withCollector(gizmos)) {
            method.invoke(null, context, 12.0, 30.0, -5.0, new Vector3f(1, 0, 0), 0xff40c8ff);
        }
        assertFalse(gizmos.getGizmos().isEmpty(), "port never reaches the pass after Kender geometry");
        assertTrue(gizmos.getGizmos().stream().allMatch(SimpleGizmoCollector.GizmoInstance::isAlwaysOnTop));
        var primitives = new DrawableGizmoPrimitives();
        gizmos.getGizmos().forEach(g -> g.gizmo().emit(primitives, 1f));
        var late = new SubmitNodeStorage();
        late.setUseImprovedTransparency(improvedTransparency);
        primitives.submit(late, context.levelState().cameraRenderState, true);
        assertFalse(late.order(0).alwaysOnTopGizmos.isEmpty());
        assertTrue(late.order(0).translucentGizmos.isEmpty());
        var vertices = new ArrayList<Vec3>();
        late.order(0).alwaysOnTopGizmos.sortInto((submit, ordered) -> {
            var group = ((GizmoFeatureRenderer.Submit) submit).group();
            for (var quad : group.quads()) vertices.addAll(List.of(quad.a(), quad.b(), quad.c(), quad.d()));
        });
        assertFalse(vertices.isEmpty());
        for (Vec3 vertex : vertices) assertEquals(12.006, vertex.x, 1e-5, "rotated port left its mounting face");
        assertEquals(29.895, vertices.stream().mapToDouble(v -> v.y).min().orElseThrow(), 1e-5);
        assertEquals(30.105, vertices.stream().mapToDouble(v -> v.y).max().orElseThrow(), 1e-5);
        assertEquals(-5.105, vertices.stream().mapToDouble(v -> v.z).min().orElseThrow(), 1e-5);
        assertEquals(-4.895, vertices.stream().mapToDouble(v -> v.z).max().orElseThrow(), 1e-5);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wireReachesTheFinalOverlayPassWithoutKodel(boolean improvedTransparency) throws Exception {
        var context = context();
        var gizmos = new SimpleGizmoCollector();
        var method = KoperAttachmentRenderer.class.getDeclaredMethod("segment",
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.class,
            Vec3.class, Vec3.class, int.class, double.class);
        method.setAccessible(true);
        try (var ignored = Gizmos.withCollector(gizmos)) {
            method.invoke(null, context, new Vec3(10, 20, 30), new Vec3(12, 24, 31), 0xff40c8ff, 0.025);
        }
        assertFalse(gizmos.getGizmos().isEmpty(), "wire never reaches the pass after Kender geometry");
        assertTrue(gizmos.getGizmos().stream().allMatch(SimpleGizmoCollector.GizmoInstance::isAlwaysOnTop));
        var primitives = new DrawableGizmoPrimitives();
        gizmos.getGizmos().forEach(g -> g.gizmo().emit(primitives, 1f));
        var late = new SubmitNodeStorage();
        late.setUseImprovedTransparency(improvedTransparency);
        primitives.submit(late, context.levelState().cameraRenderState, true);
        assertFalse(late.order(0).alwaysOnTopGizmos.isEmpty());
        assertTrue(late.order(0).translucentGizmos.isEmpty());
        var lines = new ArrayList<DrawableGizmoPrimitives.Line>();
        late.order(0).alwaysOnTopGizmos.sortInto((submit, ordered) ->
            lines.addAll(((GizmoFeatureRenderer.Submit) submit).group().lines()));
        assertEquals(1, lines.size());
        assertEquals(new Vec3(10, 20, 30), lines.getFirst().start());
        assertEquals(new Vec3(12, 24, 31), lines.getFirst().end());
    }
}
