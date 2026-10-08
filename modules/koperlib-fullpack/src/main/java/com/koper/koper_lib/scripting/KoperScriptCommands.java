package com.koper.koper_lib.scripting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.LuaModuleContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// koper.commands.register(id, opts) — a pack declaring its own /command.
//
//   koper.commands.register("mypack:heal", { description = "top me up" })
//   koper.events.on("command:mypack:heal", function(d) ... end)
//
// brigadier wants every command present when the dispatcher is built, and a reload happens long
// after that. so we register one real node per declared id the first time the dispatcher is built,
// and later declarations are remembered for the next dispatcher rebuild (/reload or relog).
public final class KoperScriptCommands {

    private KoperScriptCommands() {}

    private record Declared(String id, String description) {}

    private static final Map<String, Declared> WANTED = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LIVE = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    public static void init() {
        if (hooked) return;
        hooked = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            com.koper.koper_lib.bedrock.BedrockKomendy.registerStatic(dispatcher);
            LIVE.clear();
            for (Declared d : WANTED.values()) build(dispatcher, d);
        });
    }

    public static void register() {
        LuaAddonRegistry.register("commands", m -> m
            .function("register", KoperScriptCommands::declare)
            .function("declared", KoperScriptCommands::declared)
        );
    }

    // a reload wipes pack scripts, so their declarations go with them
    public static void clear() { WANTED.clear(); }

    private static JsonElement declare(LuaModuleContext ctx, JsonArray a) {
        String id = str(a, 0);
        if (id.isBlank()) return new JsonPrimitive(false);

        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (!name.matches("[a-z0-9_]+")) {
            KoperLib.LOGGER.warn("[ScriptCmd] '{}' is not a usable command name (a-z, 0-9, underscore)", id);
            return new JsonPrimitive(false);
        }

        String desc = "";
        JsonElement opts = at(a, 1);
        if (opts != null && opts.isJsonObject()) {
            JsonObject o = opts.getAsJsonObject();
            if (o.has("description")) desc = o.get("description").getAsString();
        }

        WANTED.put(name, new Declared(id, desc));
        boolean live = LIVE.contains(name);
        if (!live) {
            KoperLib.LOGGER.info("[ScriptCmd] /{} declared by {} — active after the next /reload or relog",
                name, id);
        }
        return new JsonPrimitive(live);
    }

    private static JsonElement declared(LuaModuleContext ctx, JsonArray a) {
        JsonArray out = new JsonArray();
        for (String n : WANTED.keySet()) out.add(n);
        return out;
    }

    private static void build(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, Declared d) {
        String name = d.id().contains(":") ? d.id().substring(d.id().indexOf(':') + 1) : d.id();
        try {
            dispatcher.register(Commands.literal(name)
                .executes(c -> fire(c.getSource(), d, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .executes(c -> fire(c.getSource(), d, StringArgumentType.getString(c, "args")))));
            LIVE.add(name);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[ScriptCmd] could not build /{}: {}", name, e.getMessage());
        }
    }

    // the command fires on the bus, so a pack answers it the same way it answers anything else
    private static int fire(CommandSourceStack source, Declared d, String args) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("/" + d.id() + " needs a player"));
            return 0;
        }
        var ctx = com.koper.koper_lib.api.KoperContext.ofUse(player, player.getMainHandItem(), player.blockPosition());
        com.koper.koper_lib.api.KoperEventBus.fire("command:" + d.id(), ctx);
        UniversalScriptEngine.fireCommandEvent(d.id(), player, args);
        return 1;
    }

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonElement at(JsonArray a, int i) {
        return a != null && i < a.size() ? a.get(i) : null;
    }
}
