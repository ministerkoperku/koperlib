package com.koper.koper_lib.kfx.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One quality decision shared by the native and portable render paths.
 *
 * <p>The graph compiler emits every stage a graph declared. This lowers that program for one client
 * quality tier so both backends drop and thin the same nodes: a tier must stay visually recognisable,
 * so core stages are never removed and only their particle load scales.
 */
public final class KfxQualityPlan {
    private static final double DECORATIVE_SCALE = 0.5;

    private KfxQualityPlan() {}

    /** True when a stage was authored as decorative and may be thinned or dropped for a lower tier. */
    public static boolean decorative(JsonObject stage) {
        if (stage.has("priority")) return stage.get("priority").getAsString().equalsIgnoreCase("decorative");
        if (stage.has("decorative")) return stage.get("decorative").getAsBoolean();
        String node = stage.has("node") ? stage.get("node").getAsString() : "";
        return node.contains("spark") || node.contains("decor");
    }

    /**
     * Returns the program a client of this tier should render. Applying an already lowered program
     * again is a no-op, so a program may pass through both the client spawn path and a backend.
     */
    public static String apply(String programJson, KfxQuality quality) {
        if (programJson == null || programJson.isBlank()) return programJson;
        JsonObject root = JsonParser.parseString(programJson).getAsJsonObject();
        if (root.has("quality")) return programJson;

        JsonArray kept = new JsonArray();
        for (var raw : root.getAsJsonArray("stages")) {
            JsonObject stage = raw.getAsJsonObject().deepCopy();
            boolean decorative = decorative(stage);
            if (decorative && quality == KfxQuality.LOW) continue;
            if (stage.has("count")) {
                double scale = quality.particleScale();
                if (decorative && quality != KfxQuality.HIGH) scale *= DECORATIVE_SCALE;
                stage.addProperty("count", scaledCount(stage, scale));
            }
            kept.add(stage);
        }
        root.add("stages", kept);
        root.addProperty("quality", quality.name().toLowerCase(java.util.Locale.ROOT));
        return root.toString();
    }

    private static int scaledCount(JsonObject stage, double scale) {
        int declared = stage.get("count").getAsInt();
        int minimum = backendMinimum(stage.has("op") ? stage.get("op").getAsString() : "");
        return Math.max(minimum, (int)Math.floor(declared * scale));
    }

    private static int backendMinimum(String op) {
        return switch (op) {
            case "pentagram_particles" -> 15;
            case "burst_ring", "spiral" -> 8;
            case "stream" -> 2;
            default -> 1;
        };
    }
}
