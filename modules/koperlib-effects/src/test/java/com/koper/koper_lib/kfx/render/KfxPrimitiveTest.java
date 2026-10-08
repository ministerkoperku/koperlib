package com.koper.koper_lib.kfx.render;

import net.minecraft.resources.Identifier;
import com.koper.koper_lib.kfx.graph.KfxGraphCompiler;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxPrimitiveTest {
    @ParameterizedTest
    @ValueSource(strings = {"particles", "beam", "ribbon", "trail", "mesh", "decal", "light", "group"})
    void everyBuiltinHasNativeAndPortableLowering(String name) {
        KfxPrimitive primitive = KfxPrimitiveRegistry.builtin().get(Identifier.parse("koper_lib:" + name));
        assertNotNull(primitive);
        assertNotNull(primitive.nativeCompiler());
        assertNotNull(primitive.portableCompiler());
    }

    @Test
    void lowQualityKeepsCoreBeamAndDropsDecorativeSparks() {
        KfxRenderCompiler compiler = new KfxRenderCompiler(KfxPrimitiveRegistry.builtin());
        KfxRenderPlan plan = compiler.lower(List.of(
            new KfxRenderCompiler.NodeSpec("core_beam", "beam", "koper_lib:additive", false, 1),
            new KfxRenderCompiler.NodeSpec("tiny_sparks", "particles", "koper_lib:additive", true, 80)
        ), KfxQuality.LOW);

        assertTrue(plan.nodes().stream().anyMatch(node -> node.id().equals("core_beam")));
        assertFalse(plan.nodes().stream().anyMatch(node -> node.id().equals("tiny_sparks")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"beam", "ribbon", "trail", "mesh", "decal", "light"})
    void graphSchemaCompilesEveryNonParticleRenderPrimitive(String primitive) {
        String properties = switch (primitive) {
            case "mesh" -> ",\"mesh\":\"koper_lib:icosphere\"";
            case "decal" -> ",\"texture\":\"koper_lib:kfx/rune\"";
            default -> "";
        };
        String json = """
            {"version":2,"id":"test:%s","nodes":{
              "visual":{"type":"koper_lib:render/%s"%s}},
             "outputs":["visual"],"budget":{"max_particles":1,"lifetime":30}}
            """.formatted(primitive, primitive, properties);

        var compiled = KfxGraphCompiler.compile(KfxGraphJson.parse(json), 17L, java.util.Map.of());
        assertTrue(compiled.programJson().contains("\"primitive\":\"" + primitive + "\""));
    }
}
