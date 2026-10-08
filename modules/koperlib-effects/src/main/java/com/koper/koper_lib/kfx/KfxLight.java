package com.koper.koper_lib.kfx;

import com.google.gson.JsonObject;

public record KfxLight(float radius, float intensity, int color) {
    public static final KfxLight NONE = new KfxLight(0.0f, 0.0f, 0);

    public static KfxLight fromJson(JsonObject json, int fallbackColor) {
        if (json == null) return NONE;
        float radius = json.has("radius") ? json.get("radius").getAsFloat() : 0.0f;
        float intensity = json.has("intensity") ? json.get("intensity").getAsFloat() : 1.0f;
        int color = json.has("color")
            ? KfxDef.parseColor(json.get("color").getAsString(), fallbackColor)
            : fallbackColor;
        if (radius <= 0.0f || intensity <= 0.0f) return NONE;
        return new KfxLight(radius, intensity, color);
    }
}
