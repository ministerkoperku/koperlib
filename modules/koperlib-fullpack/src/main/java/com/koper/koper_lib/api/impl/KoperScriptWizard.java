package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.ScriptingAPI;
import com.koper.koper_lib.api.LuaModuleFactory;
import com.koper.koper_lib.panama.RustBridge;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;

// ScriptingAPI impl — your magic wand for poking the lua vms
public class KoperScriptWizard implements ScriptingAPI {

    @Override
    public void call(String scriptId, ScriptEvent event, Object... args) {
        UniversalScriptEngine.call(scriptId, event, args);
    }

    @Override
    public void preload(String scriptId) {
        UniversalScriptEngine.loadScript(scriptId);
    }

    @Override
    public void clearAll() {
        UniversalScriptEngine.clearCache();
    }

    @Override
    public void registerLuaModule(String name, LuaModuleFactory factory) {
        LuaAddonRegistry.register(name, factory);
    }

    @Override
    public boolean isRustEngineLoaded() {
        return RustBridge.isLoaded();
    }
}
