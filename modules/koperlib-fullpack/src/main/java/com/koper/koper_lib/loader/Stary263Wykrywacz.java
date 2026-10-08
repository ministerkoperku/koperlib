package com.koper.koper_lib.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 26.3 just IGNORES old loot/predicate/number fields, file loads fine and silently loses drops/counts.
// this sniffs fullpack data for the old shapes and screams once per file where exactly it is
public final class Stary263Wykrywacz {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Stary263Wykrywacz() {}

    public static void check(String source, JsonElement json) {
        List<String> hits = new ArrayList<>();
        scan(json, "$", hits);
        if (hits.isEmpty() || !REPORTED.add(source)) return;
        KoperLib.LOGGER.error("[26.3] {} is in a pre-26.3 data format, Minecraft will IGNORE these parts: {}{}",
            source, String.join("; ", hits.subList(0, Math.min(hits.size(), 8))),
            hits.size() > 8 ? " (+" + (hits.size() - 8) + " more)" : "");
    }

    // checks the file the moment the game reads it
    public static IoSupplier<InputStream> watch(PackType type, Identifier id, String pack, IoSupplier<InputStream> supplier) {
        if (supplier == null || type != PackType.SERVER_DATA || !id.getPath().endsWith(".json")) return supplier;
        return () -> {
            byte[] bytes;
            try (InputStream in = supplier.get()) {
                bytes = in.readAllBytes();
            }
            try {
                check(pack + " data/" + id.getNamespace() + "/" + id.getPath(),
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)));
            } catch (RuntimeException parseError) {
                // not our job to report malformed JSON, the game does that with the real parser
            }
            return new ByteArrayInputStream(bytes);
        };
    }

    private static void scan(JsonElement e, String at, List<String> hits) {
        if (e == null) return;
        if (e.isJsonArray()) {
            int i = 0;
            for (JsonElement x : e.getAsJsonArray()) scan(x, at + "[" + (i++) + "]", hits);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("function") && o.get("function").isJsonPrimitive())
            hits.add(at + ": loot function uses \"function\" (now \"type\")");
        if (o.has("functions") && o.get("functions").isJsonArray() && !o.has("type"))
            hits.add(at + ": \"functions\" list (now \"modifier\")");
        if (o.has("functions") && o.has("type") && !o.get("type").getAsString().endsWith("sequence"))
            hits.add(at + ": \"functions\" list (now \"modifier\")");
        if (o.has("conditions") && o.get("conditions").isJsonArray())
            hits.add(at + ": \"conditions\" list (now one \"condition\", all_of for several)");
        if (o.has("condition") && o.get("condition").isJsonPrimitive() && !o.has("type")
                && o.get("condition").getAsString().contains(":") && o.size() > 1)
            hits.add(at + ": predicate uses \"condition\" (now \"type\")");
        if (o.has("Name") && o.get("Name").isJsonPrimitive())
            hits.add(at + ": block state uses \"Name\"/\"Properties\" (now \"id\"/\"properties\")");
        if (o.has("config") && o.has("type") && o.size() == 2)
            hits.add(at + ": configured feature/carver with \"config\" (now inline)");
        for (String old : new String[]{"surface_rule", "aquifers_enabled", "ore_veins_enabled", "argument1", "spawners", "given_item_modifiers"})
            if (o.has(old)) hits.add(at + ": field \"" + old + "\" no longer exists");
        if ((o.has("min") || o.has("max")) && !o.has("type") && (at.endsWith(".count") || at.endsWith(".rolls")))
            hits.add(at + ": number provider without \"type\" (no longer defaults to uniform)");
        for (var entry : o.entrySet()) scan(entry.getValue(), at + "." + entry.getKey(), hits);
    }
}
