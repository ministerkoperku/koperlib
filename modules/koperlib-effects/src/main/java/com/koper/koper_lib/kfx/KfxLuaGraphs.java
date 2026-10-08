package com.koper.koper_lib.kfx;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.graph.KfxGraph;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import com.koper.koper_lib.kfx.graph.KfxGraphs;
import com.koper.koper_lib.kfx.graph.KfxOrigin;

/** Exact Java boundary used by koper.kfx.declare(tableOrJson). */
public final class KfxLuaGraphs {
    private KfxLuaGraphs() {}

    public static KfxGraph declare(JsonElement specification, String source) {
        if (specification == null || specification.isJsonNull()) {
            throw new IllegalArgumentException("koper.kfx.declare needs a graph table or JSON string");
        }
        JsonElement graphJson = specification.isJsonPrimitive() && specification.getAsJsonPrimitive().isString()
            ? JsonParser.parseString(specification.getAsString())
            : specification;
        KfxGraph graph = KfxGraphJson.parse(graphJson.toString(), source == null ? "lua:kfx.declare" : source);
        KfxGraphs.declare(KfxOrigin.LUA, graph);
        return graph;
    }
}
