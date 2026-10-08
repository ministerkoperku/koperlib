package com.koper.koper_lib.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.api.LuaModuleContext;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.server.level.ServerPlayer;

// koper.quest.* — same synchronous addon path pstate uses, so a status read right after a start
// sees the new value instead of lagging a tick
public final class QuestLua {
    private QuestLua() {}

    public static void register() {
        LuaAddonRegistry.register("quest", m -> m
            .function("start", QuestLua::start)
            .function("complete", QuestLua::complete)
            .function("reset", QuestLua::reset)
            .function("status", QuestLua::status)
            .function("done", QuestLua::isDone)
            .function("active", QuestLua::isActive)
            .function("bump", QuestLua::bump)
            .function("progress", QuestLua::progress)
            .function("list", QuestLua::list)
        );
    }

    private static JsonElement start(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        if (player == null) return new JsonPrimitive(false);
        return new JsonPrimitive(QuestChase.start(player, str(a, 1)));
    }

    private static JsonElement complete(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        if (player == null) return new JsonPrimitive(false);
        QuestChase.complete(player, str(a, 1));
        return new JsonPrimitive(true);
    }

    private static JsonElement reset(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        if (player == null) return new JsonPrimitive(false);
        QuestChase.reset(player, str(a, 1));
        return new JsonPrimitive(true);
    }

    private static JsonElement status(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        return new JsonPrimitive(player == null ? QuestChase.NONE : QuestChase.status(player, str(a, 1)));
    }

    private static JsonElement isDone(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        return new JsonPrimitive(player != null && QuestChase.done(player, str(a, 1)));
    }

    private static JsonElement isActive(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        return new JsonPrimitive(player != null && QuestChase.ACTIVE.equals(QuestChase.status(player, str(a, 1))));
    }

    private static JsonElement bump(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        if (player == null) return new JsonPrimitive(false);
        int by = a.size() > 3 && a.get(3).isJsonPrimitive() ? a.get(3).getAsInt() : 1;
        QuestChase.bump(player, str(a, 1), str(a, 2), by);
        return new JsonPrimitive(true);
    }

    private static JsonElement progress(LuaModuleContext ctx, JsonArray a) {
        ServerPlayer player = ctx.player(at(a, 0));
        return new JsonPrimitive(player == null ? 0 : QuestChase.progress(player, str(a, 1), str(a, 2)));
    }

    private static JsonElement list(LuaModuleContext ctx, JsonArray a) {
        JsonArray out = new JsonArray();
        QuestBook.ids().forEach(out::add);
        return out;
    }

    private static JsonElement at(JsonArray a, int i) {
        return a != null && i < a.size() ? a.get(i) : null;
    }

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
