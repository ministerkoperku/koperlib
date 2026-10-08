package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxFragmentBindingTest {
    private static final String FRAGMENT = """
        {"version":2,"id":"test:fragments/halo",
         "inputs":{"tint":{"type":"color","default":"#ffffffff"}},
         "nodes":{
           "halo_shape":{"type":"koper_lib:source/ring","radius":1.5},
           "halo":{"type":"koper_lib:render/particles","source":"halo_shape","count":24,
                   "color":{"input":"tint"},"priority":"decorative"}},
         "exports":{"root":"halo"},"outputs":["halo"],
         "budget":{"max_particles":24,"lifetime":40}}
        """;

    private static String spell(String binding) {
        return """
            {"version":2,"id":"test:spell",
             "inputs":{"accent":{"type":"color","default":"#ff2288ff"}},
             "include":[{"graph":"test:fragments/halo","as":"halo"%s}],
             "nodes":{
               "core_shape":{"type":"koper_lib:source/sigil","points":5},
               "core":{"type":"koper_lib:render/particles","source":"core_shape","count":60,
                       "color":{"input":"accent"}}},
             "outputs":["core"],"budget":{"max_particles":120,"lifetime":40}}
            """.formatted(binding);
    }

    private static KfxCompiledGraph compile(String spellJson, Map<String, KfxResolvedValue> arguments) {
        KfxGraphRegistry registry = new KfxGraphRegistry();
        registry.declare(KfxOrigin.JSON, KfxGraphJson.parse(FRAGMENT));
        registry.declare(KfxOrigin.LUA, KfxGraphJson.parse(spellJson));
        return KfxGraphCompiler.compile(registry.linked("test:spell").graph(), 5L, arguments);
    }

    private static String colourOf(KfxCompiledGraph compiled, String node) {
        for (var raw : JsonParser.parseString(compiled.programJson()).getAsJsonObject().getAsJsonArray("stages")) {
            JsonObject stage = raw.getAsJsonObject();
            if (stage.get("node").getAsString().equals(node)) return stage.get("color").getAsString();
        }
        throw new AssertionError("no stage named " + node);
    }

    @Test
    void aFragmentBoundToAParentInputFollowsTheSpellColour() {
        KfxCompiledGraph compiled = compile(spell(",\"bind\":{\"tint\":{\"input\":\"accent\"}}"),
            Map.of("accent", KfxResolvedValue.color(0xFF33CC77)));

        assertEquals(colourOf(compiled, "core"), colourOf(compiled, "halo/halo"),
            "a bound fragment must share the caster's accent, not its own default");
    }

    @Test
    void anUnboundFragmentStillKeepsItsOwnDefault() {
        KfxCompiledGraph compiled = compile(spell(""),
            Map.of("accent", KfxResolvedValue.color(0xFF33CC77)));

        assertNotEquals(colourOf(compiled, "core"), colourOf(compiled, "halo/halo"));
    }

    @Test
    void aConstantBindingStillWorksAlongsideInputBindings() {
        KfxCompiledGraph compiled = compile(
            spell(",\"bind\":{\"tint\":{\"type\":\"color\",\"value\":\"#ff112233\"}}"), Map.of());

        assertEquals("#ff112233", colourOf(compiled, "halo/halo"));
    }

    @Test
    void bindingAnInputTheSpellDoesNotDeclareIsRejected() {
        KfxGraphException error = assertThrows(KfxGraphException.class,
            () -> compile(spell(",\"bind\":{\"tint\":{\"input\":\"missing\"}}"), Map.of()));

        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    void bindingAnInputOfTheWrongTypeIsRejected() {
        String mistyped = spell(",\"bind\":{\"tint\":{\"input\":\"power\"}}")
            .replace("\"accent\":{\"type\":\"color\",\"default\":\"#ff2288ff\"}",
                "\"accent\":{\"type\":\"color\",\"default\":\"#ff2288ff\"},\"power\":{\"type\":\"number\",\"default\":1.0}");

        assertThrows(KfxGraphException.class, () -> compile(mistyped, Map.of()));
    }
}
