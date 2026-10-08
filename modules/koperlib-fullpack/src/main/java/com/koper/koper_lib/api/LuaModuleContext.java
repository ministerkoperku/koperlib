package com.koper.koper_lib.api;

import com.google.gson.JsonElement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public interface LuaModuleContext {
    MinecraftServer server();
    ServerPlayer player(JsonElement luaValue);
    LivingEntity entity(JsonElement luaValue);
    ServerLevel level(JsonElement luaValue);
}
