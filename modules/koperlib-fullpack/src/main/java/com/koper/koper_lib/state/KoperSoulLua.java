package com.koper.koper_lib.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

// wires koper.pstate.* in lua onto KoperSoulVault. all reads/writes go through the synchronous addon-query
// path so a get right after a set sees the new value (the _cmds queue would lag a tick).
//
//   koper.pstate.set(player, "manalib", "shroom_body", 1)
//   local v = koper.pstate.get(player, "manalib", "shroom_body", 0)
//   koper.pstate.add(player, "manalib", "shroom_tries", 1)   -- returns the new count
//   koper.pstate.has(player, "manalib", "antishroom_body")
//   koper.pstate.remove(player, "manalib", "shroom_body")
//   koper.pstate.all(player, "manalib")                       -- table of every key in that namespace
//
// player can be the lua entity table OR a raw uuid string. ns is your pack name so two packs never collide.
public final class KoperSoulLua {

    private KoperSoulLua() {}

    public static void register() {
        LuaAddonRegistry.register("pstate", m -> m
            .function("get", KoperSoulLua::get)
            .function("set", KoperSoulLua::set)
            .function("add", KoperSoulLua::add)
            .function("has", KoperSoulLua::has)
            .function("remove", KoperSoulLua::remove)
            .function("all", KoperSoulLua::all)
        );
    }

    private static JsonElement get(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        if (s == null || p == null) return def(a, 3);
        String raw = KoperSoulVault.getRaw(s, p, str(a, 1), str(a, 2));
        if (raw == null) return def(a, 3);
        JsonElement v = KoperSoulCodec.decode(raw);
        return v == null ? def(a, 3) : v;
    }

    private static JsonElement set(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        if (s == null || p == null) return new JsonPrimitive(false);
        KoperSoulVault.setRaw(s, p, str(a, 1), str(a, 2), KoperSoulCodec.encode(at(a, 3)));
        return new JsonPrimitive(true);
    }

    private static JsonElement add(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        if (s == null || p == null) return new JsonPrimitive(0);
        double amt = a.size() > 3 && a.get(3).isJsonPrimitive() ? a.get(3).getAsDouble() : 1.0;
        double next = KoperSoulVault.add(s, p, str(a, 1), str(a, 2), amt);
        // hand back a clean int when it lands whole so lua counters don't read 5.0
        return next == Math.floor(next) && Double.isFinite(next) ? new JsonPrimitive((long) next) : new JsonPrimitive(next);
    }

    private static JsonElement has(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        return new JsonPrimitive(s != null && p != null && KoperSoulVault.has(s, p, str(a, 1), str(a, 2)));
    }

    private static JsonElement remove(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        if (s != null && p != null) KoperSoulVault.remove(s, p, str(a, 1), str(a, 2));
        return new JsonPrimitive(true);
    }

    // all(player [, ns]) — with ns, returns bare keys for that pack; without, every "ns:key" the player has
    private static JsonElement all(com.koper.koper_lib.api.LuaModuleContext ctx, JsonArray a) {
        MinecraftServer s = ctx.server();
        UUID p = uuid(a, 0);
        JsonObject out = new JsonObject();
        if (s == null || p == null) return out;
        String prefix = a.size() > 1 && a.get(1).isJsonPrimitive() ? KoperSoulCodec.clean(str(a, 1)) + ":" : null;
        for (var e : KoperSoulVault.snapshot(p, s).entrySet()) {
            String k = e.getKey();
            if (prefix != null) {
                if (!k.startsWith(prefix)) continue;
                k = k.substring(prefix.length());
            }
            JsonElement v = KoperSoulCodec.decode(e.getValue());
            out.add(k, v == null ? JsonNull.INSTANCE : v);
        }
        return out;
    }

    // ── arg plumbing ──────────────────────────────────────────────────────────────

    private static UUID uuid(JsonArray a, int i) {
        JsonElement e = at(a, i);
        String raw;
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) raw = e.getAsString();
        else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            raw = o.has("_uuid_str") ? o.get("_uuid_str").getAsString()
                : o.has("uuid") ? o.get("uuid").getAsString() : "";
        } else return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException bad) { return null; }
    }

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonElement def(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e == null ? JsonNull.INSTANCE : e;
    }

    private static JsonElement at(JsonArray a, int i) {
        return i >= 0 && i < a.size() ? a.get(i) : null;
    }
}
