package com.koper.koper_lib.kfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

public record KfxDef(
    Identifier id,
    Kind kind,
    int color,
    int color2,
    float radius,
    float thickness,
    int lifetime,
    boolean loop,
    float spinY,
    float pulseSpeed,
    float pulseAmount,
    float fadeIn,
    float fadeOut,
    float emitterRate,
    int emitterBurst,
    int particleLifetime,
    float spread,
    float speed,
    float gravity,
    float drag,
    float sizeEnd,
    String emitterShape,
    String particleStyle,
    String particleMotion,
    float timelineWarmup,
    float timelineBeamTime,
    float ringScale,
    float sigilScale,
    String programJson,
    int maxParticles,
    float turbulence,
    String collisionResponse,
    int collisionRadius,
    boolean collisionFluids,
    float collisionRestitution,
    float collisionFriction,
    KfxLight light
) {
    public enum Kind {
        BEAM, RING, SPHERE, PARTICLE, VORTEX, EMITTER, CHARGE_BEAM
    }

    public static KfxDef fromJson(JsonObject json, Identifier fallbackId) {
        Identifier id = json.has("id") ? Identifier.parse(json.get("id").getAsString()) : fallbackId;
        String rawKind = str(json, "shape", str(json, "kind", str(json, "type", "ring")));
        Kind kind = switch (rawKind.replace("kfx:", "").replace("koperfx:", "").toLowerCase()) {
            case "beam", "laser", "line" -> Kind.BEAM;
            case "sphere", "ball", "orb" -> Kind.SPHERE;
            case "vortex", "portal", "spiral", "tunnel" -> Kind.VORTEX;
            case "emitter", "particles", "spray", "burst" -> Kind.EMITTER;
            case "charge_beam", "chargebeam", "charge", "charged_laser", "ritual_beam", "ritualbeam", "pentagram_beam" -> Kind.CHARGE_BEAM;
            case "particle", "spark" -> Kind.PARTICLE;
            default -> Kind.RING;
        };

        int color = parseColor(str(json, "color", "#55ccff"), 0xCC55CCFF);
        int color2 = parseColor(str(json, "color2", str(json, "core_color", "#ffffff")), brighten(color));
        float radius = flt(json, "radius", flt(json, "size", 1.0f));
        float thickness = flt(json, "thickness", 0.18f);
        int lifetime = lifetime(json);
        boolean loop = bool(json, "loop", lifetime < 0);
        float spinY = flt(json, "spin_y", flt(json, "spin", 45.0f));
        float pulseSpeed = flt(json, "pulse_speed", flt(json, "pulse", 1.2f));
        float pulseAmount = flt(json, "pulse_amount", 0.12f);
        float fadeIn = flt(json, "fade_in", 3.0f);
        float fadeOut = flt(json, "fade_out", 10.0f);
        JsonObject em = json.has("emitter") && json.get("emitter").isJsonObject() ? json.getAsJsonObject("emitter") : json;
        float emitterRate = flt(em, "rate", kind == Kind.EMITTER ? 36.0f : 0.0f);
        int emitterBurst = (int)flt(em, "burst", kind == Kind.EMITTER ? 80.0f : 0.0f);
        int particleLifetime = (int)flt(em, "particle_lifetime", 34.0f);
        float spread = flt(em, "spread", 0.45f);
        float speed = flt(em, "speed", 0.12f);
        float gravity = flt(em, "gravity", -0.006f);
        float drag = flt(em, "drag", 0.965f);
        float sizeEnd = flt(em, "size_end", radius * 0.15f);
        String emitterShape = str(em, "shape", str(em, "emitter_shape", "sphere"));
        String defaultParticleStyle = "beam".equalsIgnoreCase(emitterShape) ? "spark" : "star";
        String particleStyle = str(em, "particle_style", str(em, "style", defaultParticleStyle));
        String particleMotion = str(em, "motion", str(em, "particle_motion", "free"));
        JsonObject timeline = json.has("timeline") && json.get("timeline").isJsonObject()
            ? json.getAsJsonObject("timeline") : json;
        float timelineWarmup = flt(timeline, "warmup", flt(timeline, "charge_ticks", particleLifetime));
        float timelineBeamTime = flt(timeline, "beam_time", flt(timeline, "fire_ticks", 10.0f));
        float ringScale = flt(timeline, "ring_scale", 2.8f);
        float sigilScale = flt(timeline, "sigil_scale", 0.72f);
        String programJson = json.has("program") && json.get("program").isJsonObject()
            ? json.getAsJsonObject("program").toString() : "";
        int maxParticles = (int)flt(em, "max_particles", 700.0f);
        float turbulence = flt(em, "turbulence", 0.012f);
        JsonObject collision = em.has("collision") && em.get("collision").isJsonObject()
            ? em.getAsJsonObject("collision") : new JsonObject();
        String collisionResponse = em.has("collision") && em.get("collision").isJsonPrimitive()
            ? em.get("collision").getAsString() : str(collision, "response", "none");
        int collisionRadius = Math.clamp((int)flt(collision, "radius", 8), 1, 16);
        boolean collisionFluids = bool(collision, "fluids", false);
        float collisionRestitution = Math.clamp(flt(collision, "restitution", 0.65f), 0.0f, 1.0f);
        float collisionFriction = Math.clamp(flt(collision, "friction", 0.08f), 0.0f, 1.0f);
        KfxLight light = KfxLight.fromJson(json.has("light") && json.get("light").isJsonObject()
            ? json.getAsJsonObject("light") : null, color);
        return new KfxDef(id, kind, color, color2, radius, thickness, lifetime, loop, spinY,
            pulseSpeed, pulseAmount, fadeIn, fadeOut,
            emitterRate, emitterBurst, particleLifetime, spread, speed, gravity, drag, sizeEnd,
            emitterShape, particleStyle, particleMotion, timelineWarmup, timelineBeamTime, ringScale, sigilScale,
            programJson, maxParticles, turbulence, collisionResponse, collisionRadius, collisionFluids,
            collisionRestitution, collisionFriction, light);
    }

    private static int lifetime(JsonObject json) {
        if (!json.has("lifetime")) return 40;
        JsonElement e = json.get("lifetime");
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            if ("forever".equalsIgnoreCase(s) || "infinite".equalsIgnoreCase(s)) return -1;
        }
        return Math.max(-1, e.getAsInt());
    }

    static int parseColor(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return 0xCC000000 | Integer.parseUnsignedInt(s, 16);
            if (s.length() == 8) return (int)Long.parseLong(s, 16);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static int brighten(int color) {
        int a = color & 0xFF000000;
        int r = Math.min(255, (int)(((color >> 16) & 255) * 1.45f));
        int g = Math.min(255, (int)(((color >> 8) & 255) * 1.45f));
        int b = Math.min(255, (int)((color & 255) * 1.45f));
        return a | (r << 16) | (g << 8) | b;
    }

    static float[] vec3(JsonObject json, String name, float x, float y, float z) {
        if (!json.has(name) || !json.get(name).isJsonArray()) return new float[]{x, y, z};
        JsonArray a = json.getAsJsonArray(name);
        return new float[]{
            a.size() > 0 ? a.get(0).getAsFloat() : x,
            a.size() > 1 ? a.get(1).getAsFloat() : y,
            a.size() > 2 ? a.get(2).getAsFloat() : z
        };
    }

    static String str(JsonObject json, String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }

    static float flt(JsonObject json, String name, float fallback) {
        return json.has(name) ? json.get(name).getAsFloat() : fallback;
    }

    static boolean bool(JsonObject json, String name, boolean fallback) {
        return json.has(name) ? json.get(name).getAsBoolean() : fallback;
    }
}
