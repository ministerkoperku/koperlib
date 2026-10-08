package com.koper.koper_lib.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Color parsing shared by ordinary Fullpack models. */
public final class FullpackColors {
    public static final int NONE = 0xFFFFFFFF;

    private FullpackColors() {}

    public static int fromJson(JsonObject object) {
        if (object == null) return NONE;
        if (object.has("colors") && object.get("colors").isJsonArray()) {
            List<Integer> colors = new ArrayList<>();
            for (JsonElement value : object.getAsJsonArray("colors")) colors.add(parse(value));
            return colors.isEmpty() ? NONE : argb(mix(colors));
        }
        return object.has("color") ? argb(parse(object.get("color"))) : NONE;
    }

    public static int argb(int rgb) { return 0xFF000000 | (rgb & 0xFFFFFF); }

    public static int mix(List<Integer> colors) {
        long red = 0, green = 0, blue = 0;
        for (int color : colors) {
            red += color >> 16 & 0xFF;
            green += color >> 8 & 0xFF;
            blue += color & 0xFF;
        }
        int count = colors.size();
        return (int) ((red / count) << 16 | (green / count) << 8 | blue / count);
    }

    private static int parse(JsonElement value) {
        try {
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                if (array.size() < 3) return 0xFFFFFF;
                float red = array.get(0).getAsFloat();
                float green = array.get(1).getAsFloat();
                float blue = array.get(2).getAsFloat();
                boolean normalized = red <= 1f && green <= 1f && blue <= 1f;
                return clamp(normalized ? red * 255f : red) << 16
                    | clamp(normalized ? green * 255f : green) << 8
                    | clamp(normalized ? blue * 255f : blue);
            }
            String text = value.getAsString().trim().replace("#", "").replace("0x", "").replace("0X", "");
            return (int) (Long.parseLong(text, 16) & 0xFFFFFF);
        } catch (Throwable ignored) {
            return 0xFFFFFF;
        }
    }

    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
}
