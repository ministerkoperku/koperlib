package com.koper.koper_lib.kodel;

/**
 * A model worn instead of the player's own, with the player's skin. Global: every player wears it.
 * Set from the Kodel config or by code; {@link KodelPlayerModelLayer} draws it and the vanilla
 * parts are hidden while it is active.
 */
public final class KodelPlayerModel {
    private KodelPlayerModel() {}

    private static volatile String model;
    private static volatile String clip;
    private static volatile float scale = 1f;
    private static volatile float[] offset = {0f, 1.5f, 0f};

    public static String model() { return model; }
    public static String clip() { return clip; }
    public static float scale() { return scale; }
    public static float[] offset() { return offset; }
    public static boolean active() { return model != null; }

    /** Blank model means vanilla; blank clip means the body alone drives it. Namespaces are dropped. */
    public static void set(String name, String loopingClip) {
        model = blank(name) ? null : bare(name);
        clip = blank(loopingClip) ? null : loopingClip;
    }

    public static void clear() { set(null, null); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String bare(String s) { return s.contains(":") ? s.substring(s.indexOf(':') + 1) : s; }
}
