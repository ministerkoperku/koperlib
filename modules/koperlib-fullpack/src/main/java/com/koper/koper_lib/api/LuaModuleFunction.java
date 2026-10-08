package com.koper.koper_lib.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

@FunctionalInterface
public interface LuaModuleFunction {
    JsonElement call(LuaModuleContext ctx, JsonArray args);
}
