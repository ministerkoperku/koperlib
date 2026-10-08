package com.koper.koper_lib.kfx.graph;

public enum KfxValueType {
    NUMBER,
    INTEGER,
    COLOR,
    TEXT;

    static KfxValueType parse(String raw, String path) {
        if (raw == null) throw new KfxGraphException(path, "missing value type");
        return switch (raw.toLowerCase()) {
            case "number", "scalar", "float" -> NUMBER;
            case "integer", "int" -> INTEGER;
            case "color", "argb" -> COLOR;
            case "text", "string" -> TEXT;
            default -> throw new KfxGraphException(path, "unknown value type '" + raw + "'");
        };
    }
}
