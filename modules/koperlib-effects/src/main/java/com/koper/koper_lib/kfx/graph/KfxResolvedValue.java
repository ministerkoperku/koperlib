package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public record KfxResolvedValue(KfxValueType type, Object value) {
    public KfxResolvedValue {
        if (type == null || value == null) throw new IllegalArgumentException("KFX value cannot be null");
    }

    public static KfxResolvedValue number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("KFX number must be finite");
        return new KfxResolvedValue(KfxValueType.NUMBER, value);
    }

    public static KfxResolvedValue integer(int value) {
        return new KfxResolvedValue(KfxValueType.INTEGER, value);
    }

    public static KfxResolvedValue color(int argb) {
        return new KfxResolvedValue(KfxValueType.COLOR, argb);
    }

    public static KfxResolvedValue text(String value) {
        return new KfxResolvedValue(KfxValueType.TEXT, value);
    }

    public double asNumber(String path) {
        if (type == KfxValueType.NUMBER) return (double)value;
        if (type == KfxValueType.INTEGER) return (int)value;
        throw new KfxGraphException(path, "expected a number, got " + type.name().toLowerCase());
    }

    public int asColor(String path) {
        if (type != KfxValueType.COLOR) {
            throw new KfxGraphException(path, "expected a color, got " + type.name().toLowerCase());
        }
        return (int)value;
    }

    JsonElement toJson() {
        return switch (type) {
            case NUMBER -> new JsonPrimitive((double)value);
            case INTEGER -> new JsonPrimitive((int)value);
            case COLOR -> new JsonPrimitive(String.format(Locale.ROOT, "#%08x", (int)value));
            case TEXT -> new JsonPrimitive((String)value);
        };
    }
}
