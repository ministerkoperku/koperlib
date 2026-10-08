package com.koper.koper_lib.kfx;

import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.graph.KfxGraph;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import com.koper.koper_lib.kfx.graph.KfxGraphs;
import com.koper.koper_lib.kfx.graph.KfxOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class KfxAuthoringSurfaceTest {
    @AfterEach
    void clearDeclarations() {
        KfxGraphs.clearOrigin(KfxOrigin.JSON);
        KfxGraphs.clearOrigin(KfxOrigin.LUA);
        KfxGraphs.clearOrigin(KfxOrigin.JAVA);
    }

    @Test
    void realJsonLuaAndJavaEntryPointsShareOneRegistry() {
        KfxBook.register(JsonParser.parseString(graphJson(
            "test:json", "json", "#55ccff", ""
        )).getAsJsonObject(), "data/test/kfx/json.json");
        KfxLuaGraphs.declare(JsonParser.parseString(graphJson(
            "test:lua", "lua", "#bb66ff", "{\"graph\":\"test:json\",\"as\":\"json\"}"
        )), "lua:test.lua");
        KfxGraph java = KfxApi.graph("test:java")
            .source("java:test.java")
            .include("lua", "test:lua")
            .node("java_shape", "koper_lib:source/ring").text("mode", "burst").number("radius_to", 3).end()
            .node("java_fx", "koper_lib:render/particles").link("source", "java_shape").integer("count", 8).end()
            .node("java_root", "koper_lib:group").links("children", "java_fx").end()
            .exportNode("shape", "java_shape")
            .output("java_root").budget(64, 30).build();
        KfxApi.declareGraph(java);

        assertEquals(List.of(KfxOrigin.JAVA, KfxOrigin.LUA, KfxOrigin.JSON),
            KfxApi.linkedGraph("test:java").sourceChain().stream().map(source -> source.origin()).toList());
    }

    @Test
    void luaDeclarationAlsoAcceptsAJsonString() {
        String json = graphJson("test:lua_string", "lua", "#bb66ff", "");

        KfxLuaGraphs.declare(new com.google.gson.JsonPrimitive(json), "lua:string_test.lua");

        assertEquals(KfxOrigin.LUA,
            KfxApi.linkedGraph("test:lua_string").sourceChain().getFirst().origin());
    }

    @Test
    void abortedContentReloadKeepsTheActiveLegacyBook() {
        KfxDef active = KfxBook.get("koper_lib:demo_ring");

        KfxBook.clear();
        KfxBook.finishReload(false);

        assertSame(active, KfxBook.get("koper_lib:demo_ring"));
    }

    @Test
    void controlledVisualHasAnAtomicPublicLaunchSurface() {
        assertDoesNotThrow(() -> KfxApi.class.getMethod("spawnControlled",
            net.minecraft.server.level.ServerLevel.class, KfxDef.class,
            com.koper.koper_lib.kfx.runtime.KfxController.class));
    }

    private static KfxGraph graph(String id, String prefix, String color, String include) {
        return KfxGraphJson.parse(graphJson(id, prefix, color, include), "json:" + id);
    }

    private static String graphJson(String id, String prefix, String color, String include) {
        String includes = include.isBlank() ? "" : "\"include\":[" + include + "],";
        return """
            {"version":2,"id":"%s",%s
             "nodes":{"%s_shape":{"type":"koper_lib:source/ring"},
                      "%s_fx":{"type":"koper_lib:render/particles","source":"%s_shape","count":8,"color":"%s"},
                      "%s_root":{"type":"koper_lib:group","children":["%s_fx"]}},
             "outputs":["%s_root"],"budget":{"max_particles":32,"lifetime":30}}
            """.formatted(id, includes, prefix, prefix, prefix, color, prefix, prefix, prefix);
    }
}
