package com.koper.koper_lib.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.api.LuaModuleContext;
import com.koper.koper_lib.block.KoperBlockBrain;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

// koper.bstate.* — the per-position store packs never had. pstate is per player, this is per block.
//
//   koper.bstate.set(pos, "mechanics_dream", "fuel", 40)
//   local f = koper.bstate.get(pos, "mechanics_dream", "fuel", 0)
//   koper.bstate.add(pos, "mechanics_dream", "ticks_run", 1)   -- returns the new count
//   koper.bstate.has(pos, "mechanics_dream", "lit")
//   koper.bstate.remove(pos, "mechanics_dream", "fuel")
//   koper.bstate.all(pos, "mechanics_dream")
//
// pos is the lua {x=,y=,z=} table event handlers already get. optional last-but-one arg is a
// dimension id if you're poking a block in another world.
//
// only works on blocks that have a brain — that's any block with a gui, an on_tick, or
// "block_entity": true in its json. plain decoration has nowhere to put this and says so.
public final class KoperBrainLua {

    private KoperBrainLua() {}

    public static void register() {
        LuaAddonRegistry.register("bstate", m -> m
            .function("get", KoperBrainLua::get)
            .function("set", KoperBrainLua::set)
            .function("add", KoperBrainLua::add)
            .function("has", KoperBrainLua::has)
            .function("remove", KoperBrainLua::remove)
            .function("all", KoperBrainLua::all)
        );
    }

    private static JsonElement get(LuaModuleContext ctx, JsonArray a) {
        KoperBlockBrain brain = brain(ctx, a);
        if (brain == null) return def(a, 3);
        CompoundTag ns = brain.namespace(str(a, 1));
        String key = str(a, 2);
        return ns.contains(key) ? KoperSoulCodec.decode(ns.getString(key).orElse(null)) : def(a, 3);
    }

    private static JsonElement set(LuaModuleContext ctx, JsonArray a) {
        KoperBlockBrain brain = brain(ctx, a);
        if (brain == null) return new JsonPrimitive(false);
        brain.namespace(str(a, 1)).putString(str(a, 2), KoperSoulCodec.encode(at(a, 3)));
        brain.dirty();
        return new JsonPrimitive(true);
    }

    private static JsonElement add(LuaModuleContext ctx, JsonArray a) {
        KoperBlockBrain brain = brain(ctx, a);
        if (brain == null) return new JsonPrimitive(0);
        double amt = a.size() > 3 && a.get(3).isJsonPrimitive() ? a.get(3).getAsDouble() : 1.0;

        CompoundTag ns = brain.namespace(str(a, 1));
        String key = str(a, 2);
        double now = 0;
        if (ns.contains(key)) {
            JsonElement cur = KoperSoulCodec.decode(ns.getString(key).orElse(null));
            if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isNumber()) now = cur.getAsDouble();
        }
        double next = now + amt;
        ns.putString(key, KoperSoulCodec.encode(new JsonPrimitive(next)));
        brain.dirty();
        // hand back a clean int when it lands whole so lua counters don't read 5.0
        return next == Math.floor(next) && Double.isFinite(next) ? new JsonPrimitive((long) next) : new JsonPrimitive(next);
    }

    private static JsonElement has(LuaModuleContext ctx, JsonArray a) {
        KoperBlockBrain brain = brain(ctx, a);
        return new JsonPrimitive(brain != null && brain.namespace(str(a, 1)).contains(str(a, 2)));
    }

    private static JsonElement remove(LuaModuleContext ctx, JsonArray a) {
        KoperBlockBrain brain = brain(ctx, a);
        if (brain == null) return new JsonPrimitive(false);
        brain.namespace(str(a, 1)).remove(str(a, 2));
        brain.dirty();
        return new JsonPrimitive(true);
    }

    private static JsonElement all(LuaModuleContext ctx, JsonArray a) {
        JsonObject out = new JsonObject();
        KoperBlockBrain brain = brain(ctx, a);
        if (brain == null) return out;
        CompoundTag ns = brain.namespace(str(a, 1));
        for (String key : ns.keySet()) {
            JsonElement v = KoperSoulCodec.decode(ns.getString(key).orElse(null));
            out.add(key, v == null ? JsonNull.INSTANCE : v);
        }
        return out;
    }

    // ── arg plumbing ──────────────────────────────────────────────────────────

    private static KoperBlockBrain brain(LuaModuleContext ctx, JsonArray a) {
        MinecraftServer server = ctx.server();
        if (server == null) return null;
        BlockPos pos = pos(at(a, 0));
        if (pos == null) return null;

        ServerLevel level = level(server, at(a, 0));
        if (level == null) return null;
        return level.getBlockEntity(pos) instanceof KoperBlockBrain brain ? brain : null;
    }

    private static BlockPos pos(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (!o.has("x") || !o.has("y") || !o.has("z")) return null;
        try {
            return BlockPos.containing(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
        } catch (Exception bad) { return null; }
    }

    // pos table may carry "dim"/"dimension"; without it we use whatever the script's world is
    private static ServerLevel level(MinecraftServer server, JsonElement e) {
        String want = null;
        if (e != null && e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("dim")) want = o.get("dim").getAsString();
            else if (o.has("dimension")) want = o.get("dimension").getAsString();
        }
        if (want == null) return server.overworld();
        Identifier id = Identifier.tryParse(want);
        if (id == null) return server.overworld();
        for (ServerLevel l : server.getAllLevels())
            if (l.dimension().identifier().equals(id)) return l;
        return server.overworld();
    }

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonElement at(JsonArray a, int i) {
        return a != null && i < a.size() ? a.get(i) : null;
    }

    private static JsonElement def(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e == null ? JsonNull.INSTANCE : e;
    }
}
