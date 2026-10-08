package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

// what the server told us about bedrock mobs (variant, flags, properties), by entity id
public final class BrStany {
    private static final Map<Integer, JsonObject> STANY = new HashMap<>();

    private BrStany() {}

    public static void przyjmij(int entity, String json) {
        try {
            STANY.put(entity, JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception ignored) {
        }
    }

    public static void wyczysc() {
        STANY.clear();
    }

    static JsonObject of(int entity) {
        return STANY.get(entity);
    }

    static Float num(int entity, String key) {
        JsonObject o = STANY.get(entity);
        return o != null && o.has(key) ? o.get(key).getAsFloat() : null;
    }

    static boolean flag(int entity, String name) {
        JsonObject o = STANY.get(entity);
        if (o == null || !o.has("flags")) return false;
        for (JsonElement f : o.getAsJsonArray("flags")) if (f.getAsString().equals(name)) return true;
        return false;
    }

    static boolean family(int entity, String name) {
        JsonObject o = STANY.get(entity);
        if (o == null || !o.has("family")) return false;
        for (JsonElement f : o.getAsJsonArray("family")) if (f.getAsString().equals(name)) return true;
        return false;
    }

    static JsonElement prop(int entity, String name) {
        JsonObject o = STANY.get(entity);
        return o == null || !o.has("props") ? null : o.getAsJsonObject("props").get(name);
    }
}
