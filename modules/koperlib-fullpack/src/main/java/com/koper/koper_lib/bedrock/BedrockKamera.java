package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// bedrock's camera on the client: /camera and player.camera from scripts arrive as a BedrockKameraPayload.
// "set minecraft:free" pins the view somewhere (pos, rot, facing a spot or an entity, eased there from
// wherever the view was), the first/third person presets just switch java's view, "clear" gives the
// camera back, "fade" draws a colour over the screen (in, hold, out). BedrockKameraMixin asks it every frame
@Environment(EnvType.CLIENT)
public final class BedrockKamera {

    private BedrockKamera() {}

    private static boolean wolna;
    // where the view was when the last set arrived, and where it is going
    private static Vec3 odPos, doPos;
    private static float odYaw, odPitch, doYaw, doPitch;
    private static long easeStart;
    private static float easeCzas;
    private static String easeTyp = "linear";
    private static Vec3 patrzNa;
    private static int patrzNaEntity = -1;
    // what the view was last frame, the start of the next ease
    private static Vec3 terazPos;
    private static float terazYaw, terazPitch;
    private static CameraType przed;

    // fade: colour, and when each phase ends (ms)
    private static long fadeStart;
    private static float fadeIn, fadeHold, fadeOut;
    private static int fadeRgb;

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "bedrock_camera_fade"), BedrockKamera::rysujFade);
    }

    public static void odbierz(String json) {
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (Exception bad) { return; }
        Minecraft mc = Minecraft.getInstance();
        switch (BedrockTlumacz.str(o, "a", "")) {
            case "clear" -> puscic(mc);
            case "fade" -> {
                fadeIn = f(o, "in", 0);
                fadeHold = f(o, "hold", 0);
                fadeOut = f(o, "out", 0);
                fadeRgb = o.has("rgb") ? o.get("rgb").getAsInt() : 0;
                fadeStart = System.currentTimeMillis();
            }
            case "set" -> ustaw(mc, o);
            // bedrock stacks shakes up to intensity 4, the longest one decides when it ends
            case "shake" -> {
                long now = System.currentTimeMillis();
                if (now > shakeEnd) shakeStrength = 0;
                shakeStrength = Math.min(4f, shakeStrength + f(o, "strength", 0.5f));
                shakeEnd = Math.max(shakeEnd, now + (long) (f(o, "seconds", 1f) * 1000));
                shakeRotational = "rotational".equals(BedrockTlumacz.str(o, "type", "positional"));
            }
            case "shake_stop" -> shakeStrength = 0;
            case "input" -> {
                cameraLocked = o.has("camera") && !o.get("camera").getAsBoolean();
                movementLocked = o.has("movement") && !o.get("movement").getAsBoolean();
            }
            default -> {}
        }
    }

    private static void ustaw(Minecraft mc, JsonObject o) {
        String preset = BedrockTlumacz.str(o, "preset", "minecraft:free");
        switch (preset) {
            case "minecraft:first_person" -> { puscic(mc); mc.options.setCameraType(CameraType.FIRST_PERSON); return; }
            case "minecraft:third_person", "minecraft:follow_orbit", "minecraft:control_scheme_camera" -> {
                puscic(mc); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); return;
            }
            case "minecraft:third_person_front" -> { puscic(mc); mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); return; }
            default -> {}
        }
        Entity gracz = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (gracz == null) return;
        // the ease starts from what is on screen now: the pinned view, or the player's own eyes
        if (wolna && terazPos != null) { odPos = terazPos; odYaw = terazYaw; odPitch = terazPitch; }
        else { odPos = gracz.getEyePosition(); odYaw = gracz.getYRot(); odPitch = gracz.getXRot(); }
        doPos = o.has("pos") ? v3(o.getAsJsonArray("pos")) : odPos;
        doYaw = odYaw;
        doPitch = odPitch;
        if (o.has("rot")) {
            JsonArray r = o.getAsJsonArray("rot");
            doPitch = r.get(0).getAsFloat();
            doYaw = r.get(1).getAsFloat();
        }
        patrzNa = o.has("facing") ? v3(o.getAsJsonArray("facing")) : null;
        patrzNaEntity = o.has("facingEntity") ? o.get("facingEntity").getAsInt() : -1;
        easeCzas = f(o, "ease", 0);
        easeTyp = BedrockTlumacz.str(o, "easeType", "linear");
        easeStart = System.currentTimeMillis();
        if (!wolna) przed = mc.options.getCameraType();
        // java draws the first person hand and crosshair for a first person view, wherever the camera really is
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        wolna = true;
    }

    private static void puscic(Minecraft mc) {
        if (wolna && przed != null) mc.options.setCameraType(przed);
        wolna = false;
        patrzNa = null;
        patrzNaEntity = -1;
    }

    private static float shakeStrength;
    private static long shakeEnd;
    private static boolean shakeRotational;

    // this frame's shake: {yaw, pitch, x, y, z} to add, null when nothing shakes. a few sines that never line up, good enough noise
    public static double[] shake() {
        long now = System.currentTimeMillis();
        if (shakeStrength <= 0 || now >= shakeEnd) return null;
        double t = now / 1000.0;
        // fades out over the last half second instead of stopping dead
        double strength = shakeStrength * Math.min(1.0, (shakeEnd - now) / 500.0);
        double a = Math.sin(t * 37.0) * 0.6 + Math.sin(t * 61.0 + 1.3) * 0.4;
        double b = Math.sin(t * 43.0 + 2.1) * 0.6 + Math.sin(t * 71.0 + 0.4) * 0.4;
        double c = Math.sin(t * 53.0 + 4.2) * 0.6 + Math.sin(t * 29.0 + 3.3) * 0.4;
        if (shakeRotational) return new double[] {a * strength * 4.0, b * strength * 4.0, 0, 0, 0};
        return new double[] {0, 0, a * strength * 0.12, b * strength * 0.12, c * strength * 0.12};
    }

    // /inputpermission and player.inputPermissions: the mouse and the keys stop turning and moving the player
    private static volatile boolean cameraLocked, movementLocked;

    public static boolean cameraLocked() { return cameraLocked; }

    public static boolean movementLocked() { return movementLocked; }

    public static boolean wolna() { return wolna; }

    // this frame's view: [x, y, z, yaw, pitch]
    public static double[] widok() {
        float t = easeCzas <= 0 ? 1f : Mth.clamp((System.currentTimeMillis() - easeStart) / (easeCzas * 1000f), 0f, 1f);
        float k = ease(easeTyp, t);
        Vec3 p = odPos.lerp(doPos, k);
        float yaw = odYaw + Mth.wrapDegrees(doYaw - odYaw) * k;
        float pitch = Mth.lerp(k, odPitch, doPitch);
        Vec3 cel = patrzNa;
        if (patrzNaEntity >= 0 && Minecraft.getInstance().level != null) {
            Entity e = Minecraft.getInstance().level.getEntity(patrzNaEntity);
            if (e != null) cel = e.getEyePosition();
        }
        if (cel != null) {
            Vec3 d = cel.subtract(p);
            float cy = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
            float cp = (float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
            // facing overrides rot, eased in the same way
            yaw = odYaw + Mth.wrapDegrees(cy - odYaw) * k;
            pitch = Mth.lerp(k, odPitch, cp);
        }
        terazPos = p;
        terazYaw = yaw;
        terazPitch = pitch;
        return new double[] {p.x, p.y, p.z, yaw, pitch};
    }

    private static void rysujFade(GuiGraphicsExtractor g, DeltaTracker tick) {
        if (fadeStart == 0) return;
        float s = (System.currentTimeMillis() - fadeStart) / 1000f;
        float a;
        if (s < fadeIn) a = fadeIn <= 0 ? 1 : s / fadeIn;
        else if (s < fadeIn + fadeHold) a = 1;
        else if (s < fadeIn + fadeHold + fadeOut) a = 1 - (s - fadeIn - fadeHold) / fadeOut;
        else { fadeStart = 0; return; }
        int alpha = Mth.clamp((int) (a * 255), 0, 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (alpha << 24) | (fadeRgb & 0xFFFFFF));
    }

    // bedrock's EasingType, the same curves as easings.net
    static float ease(String typ, float t) {
        String n = typ.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        return switch (n) {
            case "inquad" -> t * t;
            case "outquad" -> 1 - (1 - t) * (1 - t);
            case "inoutquad" -> t < .5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
            case "incubic" -> t * t * t;
            case "outcubic" -> 1 - (float) Math.pow(1 - t, 3);
            case "inoutcubic" -> t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
            case "inquart" -> t * t * t * t;
            case "outquart" -> 1 - (float) Math.pow(1 - t, 4);
            case "inoutquart" -> t < .5f ? 8 * t * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 4) / 2;
            case "inquint" -> t * t * t * t * t;
            case "outquint" -> 1 - (float) Math.pow(1 - t, 5);
            case "inoutquint" -> t < .5f ? 16 * t * t * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 5) / 2;
            case "insine" -> 1 - (float) Math.cos(t * Math.PI / 2);
            case "outsine" -> (float) Math.sin(t * Math.PI / 2);
            case "inoutsine" -> -((float) Math.cos(Math.PI * t) - 1) / 2;
            case "inexpo" -> t == 0 ? 0 : (float) Math.pow(2, 10 * t - 10);
            case "outexpo" -> t == 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
            case "inoutexpo" -> t == 0 || t == 1 ? t : t < .5f ? (float) Math.pow(2, 20 * t - 10) / 2 : (2 - (float) Math.pow(2, -20 * t + 10)) / 2;
            case "incirc" -> 1 - (float) Math.sqrt(1 - t * t);
            case "outcirc" -> (float) Math.sqrt(1 - (t - 1) * (t - 1));
            case "inoutcirc" -> t < .5f ? (1 - (float) Math.sqrt(1 - 4 * t * t)) / 2 : ((float) Math.sqrt(1 - (float) Math.pow(-2 * t + 2, 2)) + 1) / 2;
            case "inback" -> 2.70158f * t * t * t - 1.70158f * t * t;
            case "outback" -> 1 + 2.70158f * (float) Math.pow(t - 1, 3) + 1.70158f * (float) Math.pow(t - 1, 2);
            case "inoutback" -> t < .5f ? (float) (Math.pow(2 * t, 2) * ((2.5949095 + 1) * 2 * t - 2.5949095)) / 2
                : (float) (Math.pow(2 * t - 2, 2) * ((2.5949095 + 1) * (t * 2 - 2) + 2.5949095) + 2) / 2;
            case "inbounce" -> 1 - odbij(1 - t);
            case "outbounce" -> odbij(t);
            case "inoutbounce" -> t < .5f ? (1 - odbij(1 - 2 * t)) / 2 : (1 + odbij(2 * t - 1)) / 2;
            case "inelastic" -> t == 0 || t == 1 ? t : (float) (-Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * (2 * Math.PI / 3)));
            case "outelastic", "spring" -> t == 0 || t == 1 ? t : (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
            case "inoutelastic" -> t == 0 || t == 1 ? t : t < .5f
                ? (float) (-(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * (2 * Math.PI / 4.5))) / 2)
                : (float) ((Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * (2 * Math.PI / 4.5))) / 2 + 1);
            default -> t;
        };
    }

    private static float odbij(float t) {
        float n = 7.5625f, d = 2.75f;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) { t -= 1.5f / d; return n * t * t + .75f; }
        if (t < 2.5 / d) { t -= 2.25f / d; return n * t * t + .9375f; }
        t -= 2.625f / d;
        return n * t * t + .984375f;
    }

    private static float f(JsonObject o, String k, float def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
    }

    private static Vec3 v3(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
}
