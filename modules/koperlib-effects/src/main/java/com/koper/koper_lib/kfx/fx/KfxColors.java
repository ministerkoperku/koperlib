package com.koper.koper_lib.kfx.fx;

/** ARGB helpers for effects. */
public final class KfxColors {
    private KfxColors() {}

    public static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0.0f, 1.0f);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
            | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    public static int alpha(int color, float mul) {
        int a = Math.clamp(Math.round((color >>> 24) * mul), 0, 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.clamp(a, 0, 255) << 24);
    }

    /** Multiplies the RGB channels, for embers that cool down to a darker version of themselves. */
    public static int scale(int color, float mul) {
        int r = Math.min(255, Math.round(((color >> 16) & 255) * mul));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * mul));
        int b = Math.min(255, Math.round((color & 255) * mul));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Turns the hue by {@code degrees}, keeping saturation and brightness. Alpha is kept. */
    public static int hue(int color, float degrees) {
        float r = ((color >> 16) & 255) / 255.0f, g = ((color >> 8) & 255) / 255.0f, b = (color & 255) / 255.0f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 1.0e-5f) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        float s = max <= 0 ? 0 : d / max;
        return hsv(h + degrees, s, max, color >>> 24);
    }

    public static int hsv(float h, float s, float v, int a) {
        h = ((h % 360) + 360) % 360;
        float c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return (Math.clamp(a, 0, 255) << 24) | (Math.round((r + m) * 255) << 16)
            | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /**
     * Colour over a particle's life: white hot at birth, the spell colour through most of it and a dim,
     * darker tail. This is what makes sparks read as light instead of coloured confetti.
     */
    public static int life(int hot, int color, float t) {
        if (t < 0.18f) return mix(hot, color, t / 0.18f);
        if (t < 0.7f) return color;
        return mix(color, scale(color, 0.45f), (t - 0.7f) / 0.3f);
    }
}
