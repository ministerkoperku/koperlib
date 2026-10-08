package com.koper.koper_lib.api;

public interface LuaModuleBuilder {
    LuaModuleBuilder function(String name, LuaModuleFunction function);
}
