package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxGraphRegistryTest {
    @Test
    void jsonLuaAndJavaFragmentsBecomeOneLinkedAndCompilableGraph() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, jsonBase());
        registry.declare(KfxOrigin.LUA, luaWrapper());
        registry.declare(KfxOrigin.JAVA, javaTop());

        KfxLinkedGraph linked = registry.linked("test:java_spell");
        KfxCompiledGraph compiled = KfxGraphCompiler.compile(linked.graph(), 77L, Map.of());

        assertEquals(List.of(KfxOrigin.JAVA, KfxOrigin.LUA, KfxOrigin.JSON),
            linked.sourceChain().stream().map(KfxSourceRef::origin).toList());
        assertTrue(linked.graph().nodes().containsKey("lua/json/json_shape"));
        assertTrue(linked.graph().nodes().containsKey("lua/lua_shape"));
        assertTrue(linked.graph().nodes().containsKey("java_shape"));
        assertEquals(3, JsonParser.parseString(compiled.programJson())
            .getAsJsonObject().getAsJsonArray("stages").size());
        assertEquals(250, compiled.totalParticles());
    }

    @Test
    void declarationOrderDoesNotChangeLinkedProgram() {
        KfxGraphRegistry forward = new KfxGraphRegistry();
        forward.declare(KfxOrigin.JSON, jsonBase());
        forward.declare(KfxOrigin.LUA, luaWrapper());
        forward.declare(KfxOrigin.JAVA, javaTop());

        KfxGraphRegistry reverse = new KfxGraphRegistry();
        reverse.declare(KfxOrigin.JAVA, javaTop());
        reverse.declare(KfxOrigin.LUA, luaWrapper());
        reverse.declare(KfxOrigin.JSON, jsonBase());

        String first = KfxGraphCompiler.compile(forward.linked("test:java_spell").graph(), 9L, Map.of()).programJson();
        String second = KfxGraphCompiler.compile(reverse.linked("test:java_spell").graph(), 9L, Map.of()).programJson();
        assertEquals(first, second);
    }

    @Test
    void includeCycleNamesTheCompleteSourceChain() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, wrapper("test:a", "b", "test:b", 50));
        registry.declare(KfxOrigin.LUA, wrapper("test:b", "a", "test:a", 50));

        KfxGraphException error = assertThrows(KfxGraphException.class, registry::linkAll);

        assertTrue(error.getMessage().contains("test:a -> test:b -> test:a"));
    }

    @Test
    void failedRelinkDoesNotReplaceTheLastValidSnapshot() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, jsonBase());
        KfxLinkedGraph valid = registry.linked("test:json_ring");
        registry.declare(KfxOrigin.JSON, wrapper("test:json_ring", "missing", "test:nope", 100));

        assertThrows(KfxGraphException.class, registry::linkAll);
        assertSame(valid, registry.lastLinked("test:json_ring"));
        assertSame(valid, registry.linked("test:json_ring"));
        assertNull(registry.runtimeLinked("test:legacy_effect"));
    }

    @Test
    void parentCanLinkToAnExplicitlyExportedChildNodeButNotPrivateNodes() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, KfxGraphJson.parse("""
            {"version":2,"id":"test:source_fragment",
             "nodes":{"shape":{"type":"koper_lib:source/ring","radius":2.0}},
             "exports":{"shape":"shape"},
             "outputs":[],"budget":{"max_particles":32,"lifetime":20}}
            """, "json:test/source_fragment.json"));
        registry.declare(KfxOrigin.LUA, KfxGraphJson.parse("""
            {"version":2,"id":"test:export_consumer",
             "include":[{"graph":"test:source_fragment","as":"json"}],
             "nodes":{"particles":{"type":"koper_lib:render/particles","source":"json/shape","count":16},
                      "root":{"type":"koper_lib:group","children":["particles"]}},
             "outputs":["root"],"budget":{"max_particles":32,"lifetime":20}}
            """, "lua:test/export_consumer.lua"));

        KfxLinkedGraph linked = registry.linked("test:export_consumer");

        assertEquals("json/shape", linked.graph().nodes().get("particles").links().get("source").getFirst());
        assertEquals(16, KfxGraphCompiler.compile(linked.graph(), 1L, Map.of()).totalParticles());

        KfxGraph privateConsumer = KfxGraphJson.parse("""
            {"version":2,"id":"test:private_consumer",
             "include":[{"graph":"test:source_fragment","as":"json"}],
             "nodes":{"particles":{"type":"koper_lib:render/particles","source":"json/private","count":16},
                      "root":{"type":"koper_lib:group","children":["particles"]}},
             "outputs":["root"],"budget":{"max_particles":32,"lifetime":20}}
            """, "lua:test/private_consumer.lua");
        registry.declare(KfxOrigin.LUA, privateConsumer);

        KfxGraphException error = assertThrows(KfxGraphException.class, registry::linkAll);
        assertTrue(error.getMessage().contains("export 'private'"));
    }

    @Test
    void abortedReloadCannotRemoveOrReplaceTheActiveSnapshot() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, jsonBase());
        KfxLinkedGraph active = registry.linked("test:json_ring");

        registry.beginReload(Set.of(KfxOrigin.JSON, KfxOrigin.LUA));
        registry.declare(KfxOrigin.JSON, wrapper("test:replacement", "missing", "test:nope", 100));
        registry.abortReload();

        assertSame(active, registry.runtimeLinked("test:json_ring"));
        assertNull(registry.runtimeLinked("test:replacement"));
    }

    @Test
    void successfulReloadAtomicallyCommitsAllStagedDeclarations() {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, jsonBase());
        registry.linkAll();

        registry.beginReload(Set.of(KfxOrigin.JSON, KfxOrigin.LUA));
        registry.declare(KfxOrigin.JSON, wrapper("test:replacement", "base", "test:base", 100));
        registry.declare(KfxOrigin.LUA, KfxGraphJson.parse("""
            {"version":2,"id":"test:base",
             "nodes":{"shape":{"type":"koper_lib:source/ring"},
                      "particles":{"type":"koper_lib:render/particles","source":"shape","count":8},
                      "root":{"type":"koper_lib:group","children":["particles"]}},
             "outputs":["root"],"budget":{"max_particles":16,"lifetime":20}}
            """, "lua:test/base.lua"));
        registry.commitReload();

        assertNull(registry.runtimeLinked("test:json_ring"));
        assertEquals(List.of(KfxOrigin.JSON, KfxOrigin.LUA), registry.runtimeLinked("test:replacement")
            .sourceChain().stream().map(KfxSourceRef::origin).toList());
    }

    private static KfxGraph jsonBase() {
        return KfxGraphJson.parse("""
            {
              "version":2,
              "id":"test:json_ring",
              "nodes":{
                "json_shape":{"type":"koper_lib:source/ring","radius":1.8,"spin":24},
                "json_particles":{"type":"koper_lib:render/particles","source":"json_shape","count":80,"color":"#55ccff"},
                "json_root":{"type":"koper_lib:group","children":["json_particles"]}
              },
              "outputs":["json_root"],
              "budget":{"max_particles":80,"lifetime":50}
            }
            """, "json:test/json_ring.json");
    }

    private static KfxGraph luaWrapper() {
        return KfxGraphJson.parse("""
            {
              "version":2,
              "id":"test:lua_wrap",
              "include":[{"graph":"test:json_ring","as":"json"}],
              "nodes":{
                "lua_shape":{"type":"koper_lib:source/spiral","radius":1.25,"depth":2.8,"spin":38},
                "lua_particles":{"type":"koper_lib:render/particles","source":"lua_shape","count":90,"color":"#bb66ff"},
                "lua_root":{"type":"koper_lib:group","children":["lua_particles"]}
              },
              "outputs":["lua_root"],
              "budget":{"max_particles":180,"lifetime":50}
            }
            """, "lua:test/spell.lua");
    }

    private static KfxGraph javaTop() {
        return KfxGraphBuilder.graph("test:java_spell")
            .source("java:test/SpellFx.java")
            .include("lua", "test:lua_wrap")
            .node("java_shape", "koper_lib:source/ring")
                .text("mode", "burst").number("radius", 0.25).number("radius_to", 4.0).number("spin", 18).end()
            .node("java_particles", "koper_lib:render/particles")
                .link("source", "java_shape").integer("count", 80).color("color", 0xEEFF5577).end()
            .node("java_root", "koper_lib:group")
                .links("children", "java_particles").end()
            .output("java_root")
            .budget(300, 50)
            .build();
    }

    private static KfxGraph wrapper(String id, String alias, String include, int budget) {
        return KfxGraphJson.parse("""
            {"version":2,"id":"%s","include":[{"graph":"%s","as":"%s"}],
             "nodes":{"shape":{"type":"koper_lib:source/ring"},
                      "particles":{"type":"koper_lib:render/particles","source":"shape","count":8},
                      "root":{"type":"koper_lib:group","children":["particles"]}},
             "outputs":["root"],"budget":{"max_particles":%d,"lifetime":20}}
            """.formatted(id, include, alias, budget), "test:" + id);
    }
}
