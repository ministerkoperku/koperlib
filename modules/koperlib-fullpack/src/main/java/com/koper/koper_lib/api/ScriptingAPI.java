package com.koper.koper_lib.api;

import com.koper.koper_lib.scripting.ScriptEvent;

// call into the lua script system from java — useful if you want to trigger pack scripts from your own events
public interface ScriptingAPI {

    // fire a script event — scriptId format: "namespace:scripts/foo.lua" or bare filename
    void call(String scriptId, ScriptEvent event, Object... args);

    // load a script into its vm without firing any event
    void preload(String scriptId);

    // wipe all lua vms — same as what reload does internally
    void clearAll();

    void registerLuaModule(String name, LuaModuleFactory factory);

    default void registerAddon(String name, LuaAddon addon) {
        registerLuaModule(name, addon::register);
    }

    // true if the rust engine is present; false in java-only mode
    boolean isRustEngineLoaded();
}
