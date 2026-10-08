package com.koper.koper_lib.core;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.panama.RustBridge;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// tracks what works — broken stuff gets turned off instead of yeeting the whole game
public final class KoperRuntime {

    public enum Feature {
        ENGINE("engine",        ModuleTier.WORKING),
        SCRIPTING("scripting",  ModuleTier.WORKING),
        PACK_JAVA("pack_java",  ModuleTier.WORKING),
        KENDER("kender",        ModuleTier.EXPERIMENTAL),
        KODEL("kodel",          ModuleTier.EXPERIMENTAL),
        PHYSICS("physics",      ModuleTier.EXPERIMENTAL);

        public final String id;
        public final ModuleTier tier;
        Feature(String id, ModuleTier tier) { this.id = id; this.tier = tier; }
    }
    private static final EnumSet<Feature> OFF = EnumSet.noneOf(Feature.class);
    private static final List<String> NOTES = new CopyOnWriteArrayList<>();

    private KoperRuntime() {}

    public static void boot() {
        if (!RustBridge.LOADED) {
            off(Feature.ENGINE, "koperlib_engine native lib missing or failed to load");
            off(Feature.SCRIPTING, "needs engine");
            off(Feature.PHYSICS, "needs engine");
        } else {
            try {
                int rc = RustBridge.init();
                if (rc != 0) off(Feature.ENGINE, "koper_init returned " + rc);
            } catch (Throwable t) {
                off(Feature.ENGINE, "koper_init threw: " + t.getMessage());
            }
        }
        note("boot java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    }

    // Reports feature maturity after Fullpack's own config has loaded. Feature mods own their
    // enable/disable settings; Fullpack must not reach into Khysics config.
    public static void applyConfig() {
        for (Feature f : Feature.values()) {
            if (on(f) && f.tier.warning != null) {
                KoperLib.LOGGER.warn("[KoperLib] {} ({}): {}", f.id, f.tier.id, f.tier.warning);
            }
        }
    }

    public static void off(Feature f, String why) {
        if (OFF.add(f)) {
            KoperLib.LOGGER.warn("[KoperLib] disabled {} — {}", f.id, why);
            NOTES.add("OFF " + f.id + ": " + why);
        }
        if (f == Feature.PHYSICS && com.koper.koper_lib.api.core.KoperModules.get("khysics") != null)
            com.koper.koper_lib.api.core.KoperModules.state(
                "khysics", com.koper.koper_lib.api.core.KoperModules.State.DISABLED, why);
    }

    // config said yes again. the engine itself can't come back this way — if the native lib never
    // loaded, boot() turned it off for a reason and no config flip fixes that
    public static void back(Feature f, String why) {
        if (f != Feature.ENGINE && !RustBridge.LOADED) return;
        if (OFF.remove(f)) {
            KoperLib.LOGGER.info("[KoperLib] re-enabled {} — {}", f.id, why);
            NOTES.add("ON " + f.id + ": " + why);
        }
        if (f == Feature.PHYSICS && com.koper.koper_lib.api.core.KoperModules.get("khysics") != null)
            com.koper.koper_lib.api.core.KoperModules.state(
                "khysics", com.koper.koper_lib.api.core.KoperModules.State.LOADED, "");
    }

    public static void note(String line) {
        NOTES.add(line);
        if (NOTES.size() > 200) NOTES.remove(0);
    }

    public static boolean on(Feature f) {
        return !OFF.contains(f);
    }

    public static List<String> snapshot() {
        List<String> out = new ArrayList<>();
        out.add("koperlib v" + KoperLib.VERSION + "  (rust engine v" + RustBridge.version() + ", loaded=" + RustBridge.LOADED + ")");
        out.add("upcall=" + RustBridge.isUpcallActive());
        for (Feature f : Feature.values()) {
            out.add(f.id + " [" + f.tier.id + "] = " + (on(f) ? "ON" : "OFF"));
        }
        out.addAll(NOTES);
        return out;
    }

}
