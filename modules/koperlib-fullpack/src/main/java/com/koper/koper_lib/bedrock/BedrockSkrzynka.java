package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;

// dynamic properties. world ones and entity ones, split per addon namespace like bedrock does.
// "<ns>|world" or "<ns>|<uuid>" -> key -> json of the value. lives in data/koper_lib_bedrock_dyn.dat
public final class BedrockSkrzynka extends SavedData {

    private static final Codec<Map<String, Map<String, String>>> RAW =
        Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.STRING));
    private static final Codec<BedrockSkrzynka> CODEC = RecordCodecBuilder.create(b -> b.group(
        RAW.fieldOf("props").forGetter(s -> s.props)
    ).apply(b, BedrockSkrzynka::new));
    @SuppressWarnings("DataFlowIssue")
    private static final SavedDataType<BedrockSkrzynka> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "bedrock_dyn"), BedrockSkrzynka::new, CODEC, null);

    private final Map<String, Map<String, String>> props;

    private BedrockSkrzynka() { this.props = new LinkedHashMap<>(); }

    private BedrockSkrzynka(Map<String, Map<String, String>> raw) {
        this.props = new LinkedHashMap<>();
        raw.forEach((k, v) -> props.put(k, new LinkedHashMap<>(v)));
    }

    private static BedrockSkrzynka of(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    static JsonElement handle(MinecraftServer s, BedrockSkrypciarz.Addon who, JsonObject q) {
        String ns = who == null ? "?" : who.ns;
        String owner = ns + "|" + q.get("o").getAsString();
        BedrockSkrzynka box = of(s);
        Map<String, String> mine = box.props.get(owner);
        switch (q.get("a").getAsString()) {
            case "get": {
                String raw = mine == null ? null : mine.get(q.get("k").getAsString());
                return raw == null ? null : JsonParser.parseString(raw);
            }
            case "set": {
                String k = q.get("k").getAsString();
                JsonElement v = q.get("v");
                if (v == null || v.isJsonNull()) {
                    if (mine != null && mine.remove(k) != null) box.setDirty();
                    return null;
                }
                if (!v.isJsonPrimitive() && !(v.isJsonObject() && v.getAsJsonObject().has("x")))
                    throw new BedrockPytajnik.Oops("dynamic property " + k + " must be a number, string, boolean or vector");
                String raw = v.toString();
                // bedrock caps a string property at 32k, keep it the same so a pack can't balloon the save
                if (raw.length() > 32767) throw new BedrockPytajnik.Oops("dynamic property " + k + " is over 32767 chars");
                box.props.computeIfAbsent(owner, x -> new LinkedHashMap<>()).put(k, raw);
                box.setDirty();
                return null;
            }
            case "ids": {
                JsonArray out = new JsonArray();
                if (mine != null) mine.keySet().stream().filter(k -> !k.startsWith("§prop:")).forEach(out::add);
                return out;
            }
            case "clear": {
                if (box.props.remove(owner) != null) box.setDirty();
                return null;
            }
            case "bytes": {
                int n = 0;
                if (mine != null) for (var e : mine.entrySet()) n += e.getKey().length() + e.getValue().length();
                return new JsonPrimitive(n);
            }
            default: throw new BedrockPytajnik.Oops("dynamic properties can't " + q.get("a").getAsString());
        }
    }

    // a killed mob takes its properties with it, otherwise the file only ever grows
    public static void forget(MinecraftServer s, String uuid) {
        BedrockSkrzynka box = of(s);
        if (box.props.keySet().removeIf(k -> k.endsWith("|" + uuid))) box.setDirty();
    }
}
