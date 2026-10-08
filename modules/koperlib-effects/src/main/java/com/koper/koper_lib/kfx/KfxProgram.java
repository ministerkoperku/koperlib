package com.koper.koper_lib.kfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class KfxProgram {
    public final List<Op> ops = new ArrayList<>();

    public static KfxProgram parse(String raw) {
        KfxProgram p = new KfxProgram();
        if (raw == null || raw.isBlank()) return p;
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray stages = root.has("stages") && root.get("stages").isJsonArray()
                ? root.getAsJsonArray("stages") : new JsonArray();
            int remainingParticles = KfxLimits.HARD_MAX_PARTICLES_PER_GRAPH;
            for (var e : stages) {
                if (e == null || !e.isJsonObject()) continue;
                Op op = Op.parse(e.getAsJsonObject());
                int desired = particleCount(op);
                if (desired == 0 && op.count > 0) desired = op.count;
                if (desired > 0) {
                    int minimum = minimumParticleCount(op.op);
                    if (remainingParticles < minimum) continue;
                    op.count = Math.min(desired, remainingParticles);
                    remainingParticles -= op.count;
                }
                p.ops.add(op);
            }
        } catch (Exception e) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[KFX] Bad program json: {}", e.getMessage());
        }
        return p;
    }

    private static int particleCount(Op op) {
        int fallback = switch (op.op) {
            case "ring_particles" -> 64;
            case "pentagram_particles" -> 90;
            case "orb" -> 1;
            case "burst_ring" -> 96;
            case "stream" -> 64;
            case "spiral" -> 96;
            default -> 0;
        };
        if (fallback == 0) return 0;
        return Math.max(minimumParticleCount(op.op), op.count > 0 ? op.count : fallback);
    }

    private static int minimumParticleCount(String op) {
        return switch (op) {
            case "pentagram_particles" -> 15;
            case "burst_ring", "spiral" -> 8;
            case "stream" -> 2;
            default -> 1;
        };
    }

    public static final class Op {
        public String op = "ring_particles";
        public String style = "orb3d";
        public String ease = "smooth";
        public String build = "all";
        public String dim = "auto";
        public boolean hold = true;
        public float from, to = 20.0f;
        public float radius, radiusTo, thickness, size, alpha = 1.0f, spin = 0.2f, wobble = 0.0f, depth = 0.0f;
        public float x, y, z, seed, speed = 1.0f;
        public int count, color;
        public int points = 5, skip = 2;
        // beam look, see KfxBeams
        public int coreColor, segments = 12;
        public float core = 0.4f, glow = 1.0f, flicker = 0.12f, taper, noise;
        public String caps = "both";

        static Op parse(JsonObject json) {
            Op op = new Op();
            op.op = KfxOps.canonical(KfxDef.str(json, "op", KfxDef.str(json, "type", "ring_particles")));
            op.style = KfxDef.str(json, "style", KfxDef.str(json, "particle_style", op.style));
            op.ease = KfxDef.str(json, "ease", op.ease);
            op.build = KfxDef.str(json, "build", op.build);
            op.dim = KfxDef.str(json, "dim", op.dim);
            op.hold = KfxDef.bool(json, "hold", op.hold);
            op.from = KfxDef.flt(json, "from", KfxDef.flt(json, "start", 0.0f));
            op.to = KfxDef.flt(json, "to", KfxDef.flt(json, "end", op.from + KfxDef.flt(json, "duration", 20.0f)));
            op.radius = KfxDef.flt(json, "radius", KfxDef.flt(json, "size", 0.0f));
            op.radiusTo = KfxDef.flt(json, "radius_to", KfxDef.flt(json, "end_radius", op.radius));
            op.thickness = KfxDef.flt(json, "thickness", 0.0f);
            op.size = KfxDef.flt(json, "size", KfxDef.flt(json, "scale", 0.0f));
            op.alpha = KfxDef.flt(json, "alpha", 1.0f);
            op.spin = KfxDef.flt(json, "spin", op.spin);
            op.wobble = KfxDef.flt(json, "wobble", 0.0f);
            op.depth = KfxDef.flt(json, "depth", 0.0f);
            op.x = KfxDef.flt(json, "x", 0.0f);
            op.y = KfxDef.flt(json, "y", 0.0f);
            op.z = KfxDef.flt(json, "z", 0.0f);
            op.seed = KfxDef.flt(json, "seed", 0.0f);
            op.speed = KfxDef.flt(json, "speed", 1.0f);
            op.count = (int)KfxDef.flt(json, "count", 0.0f);
            op.color = KfxDef.parseColor(KfxDef.str(json, "color", ""), 0);
            op.points = Math.clamp((int)KfxDef.flt(json, "points", 5.0f), 3, 16);
            op.skip = Math.clamp((int)KfxDef.flt(json, "skip", 2.0f), 1, Math.max(1, op.points / 2));
            op.coreColor = KfxDef.parseColor(KfxDef.str(json, "core_color", ""), 0);
            op.core = Math.clamp(KfxDef.flt(json, "core", op.core), 0.0f, 1.0f);
            op.glow = Math.clamp(KfxDef.flt(json, "glow", op.glow), 0.0f, 4.0f);
            op.flicker = Math.clamp(KfxDef.flt(json, "flicker", op.flicker), 0.0f, 1.0f);
            op.taper = Math.clamp(KfxDef.flt(json, "taper", 0.0f), 0.0f, 1.0f);
            op.noise = Math.clamp(KfxDef.flt(json, "noise", 0.0f), 0.0f, 8.0f);
            op.segments = Math.clamp((int)KfxDef.flt(json, "segments", 12.0f), 4, 64);
            op.caps = KfxDef.str(json, "caps", op.caps).toLowerCase(java.util.Locale.ROOT);
            return op;
        }

        public float progress(float age) {
            if (to <= from) return age >= from ? 1.0f : 0.0f;
            return Math.max(0.0f, Math.min(1.0f, (age - from) / (to - from)));
        }
    }
}
