package com.koper.koper_lib.physics.koperer;

import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.physics.KhysicsConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

// picks which engine khysics runs on. config says what you want, the command flips it live,
// and if the one you asked for has no native on this box you get the other one instead of nothing.
//
// hello whoever reads this — adding an engine is: implement PhysKoperer, register it here, done.
public final class KopererPicker {

    // suppliers, not instances. building a koperer loads its native, and dragging a second
    // physics engine into the process when you are not using it is asking for trouble — rapier
    // should run in exactly the process it always did
    private static final Map<String, Supplier<PhysKoperer>> STABLE = new LinkedHashMap<>();
    private static final Map<String, PhysKoperer> AWAKE = new LinkedHashMap<>();
    private static volatile PhysKoperer current;

    static {
        put("rapier", RapierKoperer::new);
        put("elpe", ElpeKoperer::new);
    }

    private KopererPicker() {}

    public static void put(String name, Supplier<PhysKoperer> maker) {
        STABLE.put(name.toLowerCase(), maker);
    }

    public static PhysKoperer now() {
        PhysKoperer s = current;
        if (s == null) {
            synchronized (KopererPicker.class) {
                if (current == null) current = pick(wantedName());
                s = current;
            }
        }
        return s;
    }

    public static java.util.Set<String> names() { return STABLE.keySet(); }

    // has this one been built (and its native loaded) yet
    public static boolean isUp(String name) { return AWAKE.containsKey(name.trim().toLowerCase()); }

    // no config yet (dead early, or a unit test with no game dir) just means rapier
    private static String wantedName() {
        try { return KhysicsConfig.get().physicsBackend; }
        catch (Throwable ignored) { return "rapier"; }
    }

    // returns null when that name is unknown or its native is missing — caller decides what to say
    // builds it on first ask, which is when its native gets loaded
    public static PhysKoperer find(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase();
        PhysKoperer up = AWAKE.get(key);
        if (up != null) return up;
        Supplier<PhysKoperer> maker = STABLE.get(key);
        if (maker == null) return null;
        PhysKoperer made = maker.get();
        AWAKE.put(key, made);
        return made;
    }

    // config value -> a running engine. falls through to anything that actually loaded.
    private static PhysKoperer pick(String wanted) {
        PhysKoperer found = find(wanted);
        if (found != null && found.isLoaded()) return found;

        if (found != null)
            KoperCore.LOGGER.warn("[Khysics] physics_backend={} has no native here, looking for another one", wanted);
        else
            KoperCore.LOGGER.warn("[Khysics] physics_backend={} is not an engine i know about", wanted);

        for (String key : STABLE.keySet()) {
            PhysKoperer s = find(key);
            if (s != null && s.isLoaded()) {
                KoperCore.LOGGER.warn("[Khysics] falling back to {}", s.name());
                return s;
            }
        }
        // nothing loaded at all. rapier still answers every call with a shrug, so hand that back
        // instead of a null that would nuke every call site
        return find(STABLE.keySet().iterator().next());
    }

    // /koperlib engine <name> — swap at runtime. worlds already spawned stay on the old one,
    // so this is a "restart the world" kind of switch, not a hot swap. don't pretend otherwise.
    public static boolean swap(String name) {
        PhysKoperer s = find(name);
        if (s == null || !s.isLoaded()) return false;
        current = s;
        try {
            KhysicsConfig.get().physicsBackend = s.name();
            KhysicsConfig.save();
        } catch (Throwable e) {
            KoperCore.LOGGER.warn("[Khysics] switched to {} but could not write it to the config: {}",
                s.name(), e.getMessage());
        }
        KoperCore.LOGGER.info("[Khysics] physics backend is now {}", s.name());
        return true;
    }
}
