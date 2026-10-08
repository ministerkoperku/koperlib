package com.koper.koper_lib.scripting;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.LuaModuleBuilder;
import com.koper.koper_lib.api.LuaModuleContext;
import com.koper.koper_lib.api.LuaModuleFactory;
import com.koper.koper_lib.api.LuaModuleFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LuaAddonRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<String, Module> MODULES = new LinkedHashMap<>();

    private LuaAddonRegistry() {}

    public static synchronized void register(String name, LuaModuleFactory factory) {
        if (name == null || name.isBlank() || factory == null) return;
        Module module = new Module(cleanName(name));
        factory.register(module);
        MODULES.put(module.name, module);
        UniversalScriptEngine.installLuaModule(module.name);
    }

    public static synchronized String installScript(String name) {
        Module module = MODULES.get(cleanName(name));
        if (module == null || module.functions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("koper.").append(module.name).append(" = koper.").append(module.name).append(" or {}\n");
        for (String fn : module.functions.keySet()) {
            sb.append("function koper.").append(module.name).append('.').append(fn).append("(...)\n")
              .append("  return koper._addon_call(\"").append(module.name).append("\", \"").append(fn).append("\", {...})\n")
              .append("end\n");
        }
        if ("calls".equals(module.name)) {
            sb.append("function koper.call(id, data)\n")
              .append("  local p = koper._ctx and koper._ctx.player or nil\n")
              .append("  return koper.calls.run(id, data or {}, koper._ctx or p)\n")
              .append("end\n")
              .append("function koper.action(data)\n")
              .append("  local p = koper._ctx and koper._ctx.player or nil\n")
              .append("  return koper.calls.action(data or {}, koper._ctx or p)\n")
              .append("end\n")
              .append("koper.java = koper.java or {}\n")
              .append("function koper.java.call(id)\n")
              .append("  local p = koper._ctx and koper._ctx.player or nil\n")
              .append("  return koper.calls.java(id, koper._ctx or p)\n")
              .append("end\n");
        }
        if ("items".equals(module.name)) {
            sb.append("koper.items = koper.items or {}\n")
              .append("function koper.items.state(state, ctx)\n")
              .append("  return koper._addon_call(\"items\", \"state\", {state, ctx or koper._ctx})\n")
              .append("end\n")
              .append("function koper.items.animation(name, ctx)\n")
              .append("  return koper._addon_call(\"items\", \"animation\", {name, ctx or koper._ctx})\n")
              .append("end\n");
        }
        if ("blocks".equals(module.name)) {
            sb.append("koper.blocks = koper.blocks or {}\n")
              .append("function koper.blocks.set_state(prop, value, ctx)\n")
              .append("  return koper._addon_call(\"blocks\", \"set_state\", {prop, value, ctx or koper._ctx})\n")
              .append("end\n")
              .append("function koper.blocks.toggle(prop, ctx)\n")
              .append("  return koper._addon_call(\"blocks\", \"toggle\", {prop, ctx or koper._ctx})\n")
              .append("end\n")
              .append("function koper.blocks.connected(ctx)\n")
              .append("  return koper._addon_call(\"blocks\", \"connected\", {ctx or koper._ctx})\n")
              .append("end\n")
              .append("function koper.blocks.connected_count(ctx)\n")
              .append("  return koper._addon_call(\"blocks\", \"connected_count\", {ctx or koper._ctx})\n")
              .append("end\n");
        }
        return sb.toString();
    }

    public static synchronized String installAllScript() {
        StringBuilder sb = new StringBuilder();
        for (String name : MODULES.keySet()) sb.append(installScript(name));
        return sb.toString();
    }

    public static int dispatchQuery(MemorySegment inPtr, int inLen, MemorySegment outPtr, int outLen) {
        try {
            if (inLen <= 0 || outLen <= 0) return 0;
            String text = new String(inPtr.reinterpret(inLen).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            JsonObject req = JsonParser.parseString(text).getAsJsonObject();
            String moduleName = cleanName(req.get("module").getAsString());
            String functionName = cleanName(req.get("function").getAsString());
            JsonArray args = req.has("args") && req.get("args").isJsonArray() ? req.getAsJsonArray("args") : new JsonArray();
            Module module;
            synchronized (LuaAddonRegistry.class) {
                module = MODULES.get(moduleName);
            }
            JsonElement result = JsonNull.INSTANCE;
            if (module != null) {
                LuaModuleFunction fn = module.functions.get(functionName);
                if (fn != null) result = fn.call(new Context(), args);
            }
            byte[] bytes = GSON.toJson(result == null ? JsonNull.INSTANCE : result).getBytes(StandardCharsets.UTF_8);
            int n = Math.min(bytes.length, outLen);
            // rust hands us a raw pointer, which arrives as a zero length segment. without the
            // reinterpret every write blew up and every query quietly returned nothing
            MemorySegment.copy(bytes, 0, outPtr.reinterpret(outLen), java.lang.foreign.ValueLayout.JAVA_BYTE, 0, n);
            if (bytes.length > outLen)
                KoperLib.LOGGER.warn("[LuaAddon] {}.{} answered {} bytes but the buffer is {} — result truncated",
                    moduleName, functionName, bytes.length, outLen);
            return n;
        } catch (Throwable t) {
            KoperLib.LOGGER.warn("[LuaAddon] query failed: {}", t.getMessage());
            return 0;
        }
    }

    private static String cleanName(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') out.append(c);
        }
        return out.toString();
    }

    private static final class Module implements LuaModuleBuilder {
        final String name;
        final Map<String, LuaModuleFunction> functions = new LinkedHashMap<>();

        Module(String name) {
            this.name = name;
        }

        @Override
        public LuaModuleBuilder function(String name, LuaModuleFunction function) {
            String clean = cleanName(name);
            if (!clean.isBlank() && function != null) functions.put(clean, function);
            return this;
        }
    }

    private static final class Context implements LuaModuleContext {
        @Override
        public MinecraftServer server() {
            return UniversalScriptEngine.getCurrentServer();
        }

        @Override
        public ServerPlayer player(JsonElement luaValue) {
            LivingEntity entity = entity(luaValue);
            return entity instanceof ServerPlayer player ? player : null;
        }

        @Override
        public LivingEntity entity(JsonElement luaValue) {
            String uuid = uuidOf(luaValue);
            if (uuid.isBlank() || server() == null) return null;
            try {
                UUID id = UUID.fromString(uuid);
                for (ServerLevel level : server().getAllLevels()) {
                    var e = level.getEntity(id);
                    if (e instanceof LivingEntity living) return living;
                }
            } catch (IllegalArgumentException ignored) {
            }
            return null;
        }

        @Override
        public ServerLevel level(JsonElement luaValue) {
            LivingEntity entity = entity(luaValue);
            if (entity != null && entity.level() instanceof ServerLevel level) return level;
            return server() != null ? server().overworld() : null;
        }

        private static String uuidOf(JsonElement value) {
            if (value == null || value.isJsonNull()) return "";
            if (value instanceof JsonPrimitive primitive && primitive.isString()) return primitive.getAsString();
            if (!value.isJsonObject()) return "";
            JsonObject o = value.getAsJsonObject();
            if (o.has("_uuid_str")) return o.get("_uuid_str").getAsString();
            if (o.has("uuid")) return o.get("uuid").getAsString();
            return "";
        }
    }
}
