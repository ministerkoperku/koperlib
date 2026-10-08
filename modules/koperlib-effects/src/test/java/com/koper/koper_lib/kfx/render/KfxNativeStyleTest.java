package com.koper.koper_lib.kfx.render;

import com.koper.koper_lib.kfx.KfxStyles;
import com.koper.koper_lib.kfx.graph.KfxGraphCompiler;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxNativeStyleTest {
    // the graph writes the style by name. the native IR read it as a number, got nothing and drew
    // every style as the same shape
    @ParameterizedTest
    @ValueSource(strings = {"sprite", "spark", "star", "ring", "shard", "cube", "tetra", "orb3d", "gem"})
    void nativeProgramCarriesTheAuthoredStyle(String style) {
        String json = """
            {"version":2,"id":"test:style","nodes":{
              "shape":{"type":"koper_lib:source/ring","radius":1.0},
              "fx":{"type":"koper_lib:render/particles","source":"shape","count":12,"style":"%s"}},
             "outputs":["fx"],"budget":{"max_particles":12,"lifetime":20}}
            """.formatted(style);
        var compiled = KfxGraphCompiler.compile(KfxGraphJson.parse(json), 7L, Map.of());
        float[] properties = KfxNativeProgram.from(compiled).nodes().get(0).properties();
        assertEquals(KfxStyles.codeFor(style), Math.round(properties[11]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ring:1", "sigil:2", "stream:7", "spiral:8", "orb:4"})
    void everyParticleSourceReachesItsOwnNativeOpcode(String pair) {
        String source = pair.split(":")[0];
        int opcode = Integer.parseInt(pair.split(":")[1]);
        String json = """
            {"version":2,"id":"test:op","nodes":{
              "shape":{"type":"koper_lib:source/%s"},
              "fx":{"type":"koper_lib:render/particles","source":"shape","count":20}},
             "outputs":["fx"],"budget":{"max_particles":20,"lifetime":20}}
            """.formatted(source);
        var compiled = KfxGraphCompiler.compile(KfxGraphJson.parse(json), 7L, Map.of());
        assertEquals(opcode, KfxNativeProgram.from(compiled).nodes().get(0).opcode());
    }
}
