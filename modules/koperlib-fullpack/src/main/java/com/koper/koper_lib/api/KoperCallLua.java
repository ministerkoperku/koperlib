package com.koper.koper_lib.api;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

public final class KoperCallLua {
    private KoperCallLua() {}

    public static void register() {
        LuaAddonRegistry.register("calls", b -> b
            .function("run", (ctx, args) -> {
                if (args.size() < 1 || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                String call = args.get(0).getAsString();
                JsonObject data = args.size() > 1 && args.get(1).isJsonObject() ? args.get(1).getAsJsonObject() : new JsonObject();
                KoperContext kctx = contextFrom(ctx, args.size() > 2 ? args.get(2) : JsonNull.INSTANCE);
                InteractionResult result = KoperCalls.run(call, new KoperCallContext(kctx, data, "lua", "lua"));
                return new JsonPrimitive(result != InteractionResult.FAIL);
            })
            .function("java", (ctx, args) -> {
                if (args.size() < 1 || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 1 ? args.get(1) : JsonNull.INSTANCE);
                InteractionResult result = KoperCalls.run("java", new KoperCallContext(kctx, hookJson(args.get(0).getAsString()), "lua", "lua"));
                return new JsonPrimitive(result != InteractionResult.FAIL);
            })
            .function("action", (ctx, args) -> {
                if (args.isEmpty()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 1 ? args.get(1) : JsonNull.INSTANCE);
                InteractionResult result = KoperActions.run(args.get(0), kctx, "lua", "lua");
                return new JsonPrimitive(result != InteractionResult.FAIL);
            }));
        LuaAddonRegistry.register("items", b -> b
            .function("state", (ctx, args) -> {
                if (args.isEmpty() || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 1 ? args.get(1) : JsonNull.INSTANCE);
                if (kctx == null || kctx.stack().isEmpty()) return new JsonPrimitive(false);
                KoperActions.setItemState(kctx.stack(), args.get(0).getAsString());
                return new JsonPrimitive(true);
            })
            .function("animation", (ctx, args) -> {
                if (args.isEmpty() || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 1 ? args.get(1) : JsonNull.INSTANCE);
                if (kctx == null || kctx.stack().isEmpty()) return new JsonPrimitive(false);
                KoperActions.playItemAnimation(kctx.stack(), args.get(0).getAsString());
                return new JsonPrimitive(true);
            }));
        LuaAddonRegistry.register("blocks", b -> b
            .function("set_state", (ctx, args) -> {
                if (args.size() < 2 || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 2 ? args.get(2) : JsonNull.INSTANCE);
                if (kctx == null || !(kctx.world() instanceof net.minecraft.server.level.ServerLevel level) || kctx.pos() == null) return new JsonPrimitive(false);
                var next = com.koper.koper_lib.factory.KoperBlockStates.set(level.getBlockState(kctx.pos()),
                        args.get(0).getAsString(), args.get(1).getAsString());
                level.setBlockAndUpdate(kctx.pos(), next);
                return new JsonPrimitive(true);
            })
            .function("toggle", (ctx, args) -> {
                if (args.isEmpty() || !args.get(0).isJsonPrimitive()) return new JsonPrimitive(false);
                KoperContext kctx = contextFrom(ctx, args.size() > 1 ? args.get(1) : JsonNull.INSTANCE);
                if (kctx == null || !(kctx.world() instanceof net.minecraft.server.level.ServerLevel level) || kctx.pos() == null) return new JsonPrimitive(false);
                var next = com.koper.koper_lib.factory.KoperBlockStates.toggle(level.getBlockState(kctx.pos()),
                        args.get(0).getAsString());
                level.setBlockAndUpdate(kctx.pos(), next);
                return new JsonPrimitive(true);
            })
            .function("connected", (ctx, args) -> {
                KoperContext kctx = contextFrom(ctx, args.isEmpty() ? JsonNull.INSTANCE : args.get(0));
                if (kctx == null || !(kctx.world() instanceof net.minecraft.server.level.ServerLevel level) || kctx.pos() == null) {
                    JsonObject out = new JsonObject();
                    out.addProperty("valid", false);
                    return out;
                }
                return com.koper.koper_lib.factory.KoperBlockConnections.info(level, kctx.pos());
            })
            .function("connected_count", (ctx, args) -> {
                KoperContext kctx = contextFrom(ctx, args.isEmpty() ? JsonNull.INSTANCE : args.get(0));
                if (kctx == null || !(kctx.world() instanceof net.minecraft.server.level.ServerLevel level) || kctx.pos() == null) {
                    return new JsonPrimitive(0);
                }
                return new JsonPrimitive(com.koper.koper_lib.factory.KoperBlockConnections.count(level, kctx.pos()));
            }));
    }

    private static KoperContext contextFrom(LuaModuleContext ctx, com.google.gson.JsonElement playerValue) {
        if (playerValue != null && playerValue.isJsonObject()) {
            JsonObject o = playerValue.getAsJsonObject();
            if (o.has("player") || o.has("pos")) {
                var player = ctx.player(o.get("player"));
                BlockPos pos = posFrom(o.has("pos") ? o.get("pos") : null,
                        player != null ? player.blockPosition() : BlockPos.ZERO);
                ItemStack stack = player != null ? player.getMainHandItem() : ItemStack.EMPTY;
                return new KoperContext(player, player, null,
                        player != null ? player.level() : ctx.level(o.get("player")),
                        stack, pos);
            }
        }
        var player = ctx.player(playerValue);
        if (player != null) return KoperContext.ofUse(player, player.getMainHandItem(), player.blockPosition());
        return null;
    }

    private static BlockPos posFrom(com.google.gson.JsonElement value, BlockPos fallback) {
        if (value == null || !value.isJsonObject()) return fallback;
        JsonObject o = value.getAsJsonObject();
        if (!o.has("x") || !o.has("y") || !o.has("z")) return fallback;
        return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private static JsonObject hookJson(String hook) {
        JsonObject json = new JsonObject();
        json.addProperty("hook", hook);
        return json;
    }
}
