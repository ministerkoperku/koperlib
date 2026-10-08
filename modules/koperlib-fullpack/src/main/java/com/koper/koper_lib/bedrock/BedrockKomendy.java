package com.koper.koper_lib.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// /scriptevent, script registered /commands, and a best effort bedrock -> java command rewrite
// so runCommand("effect @s speed 10 1") and old .mcfunction files keep working
public final class BedrockKomendy {

    // command name -> addon that registered it last. looked up at run time so a reload never
    // leaves a command pointing at a dead vm
    private static final Map<String, BedrockSkrypciarz.Addon> WLASCICIEL = new HashMap<>();

    private BedrockKomendy() {}

    // called from KoperScriptCommands' registration pass
    public static void registerStatic(CommandDispatcher<CommandSourceStack> d) {
        // ids always carry a namespace (toss_lab:stop) and brigadier's plain string stops at ':', so it is the whole
        // rest of the line, split at the first space: <id> [message]
        d.register(Commands.literal("scriptevent")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String all = StringArgumentType.getString(ctx, "args").strip();
                    int sp = all.indexOf(' ');
                    scriptEvent(sp < 0 ? all : all.substring(0, sp), sp < 0 ? "" : all.substring(sp + 1), ctx.getSource());
                    return 1;
                })));
        // bedrock's /event entity <who> <event>: fires a behavior pack event on each (functions, runCommand, queue_command use it a lot)
        d.register(Commands.literal("event")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("entity")
                .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.entities())
                    .then(Commands.argument("event", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String ev = StringArgumentType.getString(ctx, "event").trim();
                            int n = 0;
                            for (Entity e : net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "who")) {
                                if (!BedrockZachowanie.ma(e)) continue;
                                BedrockZachowanie.event(e, ev, ctx.getSource().getEntity());
                                n++;
                            }
                            return n;
                        })))));
        // what BedrockSkladnia turns bedrock's /particle and /summon-with-spawn-event into
        d.register(Commands.literal("bparticle")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> czastka(ctx, StringArgumentType.getString(ctx, "args")))));
        // bedrock's /camera (java has none): set / clear / fade for the players named, BedrockKamerzysta parses the rest
        d.register(Commands.literal("camera")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                    var o = BedrockKamerzysta.parse(StringArgumentType.getString(ctx, "args").trim().split("\\s+"), ctx.getSource());
                    if (o == null) return 0;
                    int n = 0;
                    for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) { BedrockKamerzysta.wyslij(p, o); n++; }
                    return n;
                }))));
        // tag names java's /tag refuses (bedrock packs love "ns:name" tags), BedrockSkladnia sends those here
        d.register(Commands.literal("btag")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.entities())
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> tag(ctx, true))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> tag(ctx, false))))));
        // bedrock's /structure load|save|delete, java has nothing like it
        d.register(Commands.literal("structure")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> structure(ctx, StringArgumentType.getString(ctx, "args")))));
        // bedrock's /camerashake add <who> [intensity] [seconds] [positional|rotational] | stop [who]
        d.register(Commands.literal("camerashake")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("add").then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players())
                .executes(ctx -> shake(ctx, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> shake(ctx, StringArgumentType.getString(ctx, "args"))))))
            .then(Commands.literal("stop")
                .executes(ctx -> { var p = ctx.getSource().getPlayer(); if (p != null) BedrockKamerzysta.wyslij(p, stopShake()); return 1; })
                .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players()).executes(ctx -> {
                    int n = 0;
                    for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) { BedrockKamerzysta.wyslij(p, stopShake()); n++; }
                    return n;
                }))));
        // bedrock's /inputpermission set <who> camera|movement enabled|disabled (and query)
        d.register(Commands.literal("inputpermission")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("set").then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("permission", StringArgumentType.word())
                    .then(Commands.argument("state", StringArgumentType.word()).executes(ctx -> {
                        String perm = StringArgumentType.getString(ctx, "permission");
                        boolean on = StringArgumentType.getString(ctx, "state").equals("enabled");
                        int n = 0;
                        for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) if (setInputPermission(p, perm, on)) n++;
                        return n;
                    })))))
            .then(Commands.literal("query").then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("permission", StringArgumentType.word()).executes(ctx -> {
                    String perm = StringArgumentType.getString(ctx, "permission");
                    int n = 0;
                    for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) if (inputPermission(p, perm)) n++;
                    return n;
                })))));
        // bedrock's /clearspawnpoint <who>: back to the world spawn
        d.register(Commands.literal("clearspawnpoint")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .executes(ctx -> { var p = ctx.getSource().getPlayer(); if (p != null) p.setRespawnPosition(null, false); return p != null ? 1 : 0; })
            .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.players()).executes(ctx -> {
                int n = 0;
                for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) { p.setRespawnPosition(null, false); n++; }
                return n;
            })));
        // bedrock's /fog <who> push|pop|remove <fog id> <user name>: java has no fog definitions to switch to yet.
        // registered so a function that uses it still loads, and says so once instead of failing the whole function
        d.register(Commands.literal("fog")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> {
                BedrockZachowanie.once("/fog " + StringArgumentType.getString(ctx, "args") + ": pack fogs are not drawn on java yet, the command does nothing");
                return 0;
            })));
        d.register(Commands.literal("bsummon")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> przywolaj(ctx, StringArgumentType.getString(ctx, "args")))));
        // bedrock's /playanimation <who> <animation> [next_state] [blend_out_time] [stop_expression] [controller]
        d.register(Commands.literal("playanimation")
            .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.argument("who", net.minecraft.commands.arguments.EntityArgument.entities())
                .then(Commands.argument("animation", StringArgumentType.string())
                    .executes(ctx -> graj(ctx, false, false, false, false))
                    .then(Commands.argument("next_state", StringArgumentType.string())
                        .executes(ctx -> graj(ctx, true, false, false, false))
                        .then(Commands.argument("blend_out_time", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0))
                            .executes(ctx -> graj(ctx, true, true, false, false))
                            .then(Commands.argument("stop_expression", StringArgumentType.string())
                                .executes(ctx -> graj(ctx, true, true, true, false))
                                .then(Commands.argument("controller", StringArgumentType.string())
                                    .executes(ctx -> graj(ctx, true, true, true, true)))))))));
    }

    private static int tag(CommandContext<CommandSourceStack> ctx, boolean add) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.length() > 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
        int n = 0;
        for (Entity e : net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "who")) if (add ? e.addTag(name) : e.removeTag(name)) n++;
        return n;
    }

    private static JsonObject stopShake() {
        JsonObject o = new JsonObject();
        o.addProperty("a", "shake_stop");
        return o;
    }

    // bedrock defaults: intensity 0.5, 1 second, positional
    private static int shake(CommandContext<CommandSourceStack> ctx, String args) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String[] w = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        JsonObject o = new JsonObject();
        o.addProperty("a", "shake");
        try {
            o.addProperty("strength", w.length > 0 ? Math.clamp(Float.parseFloat(w[0]), 0f, 4f) : 0.5f);
            o.addProperty("seconds", w.length > 1 ? Float.parseFloat(w[1]) : 1f);
        } catch (NumberFormatException bad) {
            ctx.getSource().sendFailure(Component.literal("camerashake: bad number in " + args));
            return 0;
        }
        o.addProperty("type", w.length > 2 ? w[2].toLowerCase(Locale.ROOT) : "positional");
        int n = 0;
        for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "who")) { BedrockKamerzysta.wyslij(p, o); n++; }
        return n;
    }

    // structure load <name> <x y z> [rotation] [mirror] [animationMode animationSeconds] [includeEntities] [includeBlocks] [waterlogged] [integrity] [seed]
    // structure save <name> <from> <to> [includeEntities] [disk|memory] [includeBlocks]
    // structure delete <name>
    private static int structure(CommandContext<CommandSourceStack> ctx, String args) {
        String[] w = args.trim().split("\\s+");
        var src = ctx.getSource();
        var at = src.getPosition();
        try {
            switch (w[0].toLowerCase(Locale.ROOT)) {
                case "load" -> {
                    if (w.length < 5) break;
                    var pos = net.minecraft.core.BlockPos.containing(wsp(w[2], at.x), wsp(w[3], at.y), wsp(w[4], at.z));
                    int i = 5;
                    var rot = net.minecraft.world.level.block.Rotation.NONE;
                    if (i < w.length && w[i].endsWith("_degrees")) {
                        rot = switch (w[i]) {
                            case "90_degrees" -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                            case "180_degrees" -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                            case "270_degrees" -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                            default -> net.minecraft.world.level.block.Rotation.NONE;
                        };
                        i++;
                    }
                    var mir = net.minecraft.world.level.block.Mirror.NONE;
                    if (i < w.length && w[i].matches("none|x|z|xz")) {
                        switch (w[i]) {
                            case "x" -> mir = net.minecraft.world.level.block.Mirror.FRONT_BACK;
                            case "z" -> mir = net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
                            case "xz" -> rot = rot.getRotated(net.minecraft.world.level.block.Rotation.CLOCKWISE_180);
                            default -> {}
                        }
                        i++;
                    }
                    String anim = "none";
                    float seconds = 0;
                    if (i < w.length && (w[i].equals("block_by_block") || w[i].equals("layer_by_layer"))) {
                        anim = w[i++];
                        if (i < w.length && w[i].matches("[0-9.]+")) seconds = Float.parseFloat(w[i++]);
                    }
                    boolean entities = i >= w.length || Boolean.parseBoolean(w[i++]);
                    boolean blocks = i >= w.length || Boolean.parseBoolean(w[i++]);
                    boolean waterlogged = i < w.length && Boolean.parseBoolean(w[i++]);
                    float integrity = i < w.length ? Float.parseFloat(w[i++]) / 100f : 1f;
                    long seed = i < w.length ? w[i].hashCode() : System.nanoTime();
                    var o = new BedrockStruktury.Opcje(rot, mir, blocks, entities, waterlogged, integrity, seed, anim.replace("_", ""), seconds);
                    if (!BedrockStruktury.postaw(src.getLevel(), w[1], pos, o)) {
                        src.sendFailure(Component.literal("no structure named " + w[1]));
                        return 0;
                    }
                    return 1;
                }
                case "save" -> {
                    if (w.length < 8) break;
                    var a = net.minecraft.core.BlockPos.containing(wsp(w[2], at.x), wsp(w[3], at.y), wsp(w[4], at.z));
                    var b = net.minecraft.core.BlockPos.containing(wsp(w[5], at.x), wsp(w[6], at.y), wsp(w[7], at.z));
                    boolean entities = w.length <= 8 || Boolean.parseBoolean(w[8]);
                    boolean disk = w.length > 9 && w[9].equals("disk");
                    boolean blocks = w.length <= 10 || Boolean.parseBoolean(w[10]);
                    BedrockStruktury.zapisz(src.getLevel(), w[1], a, b, blocks, entities, disk);
                    return 1;
                }
                case "delete" -> { return w.length >= 2 && BedrockStruktury.usun(w[1]) ? 1 : 0; }
                default -> {}
            }
        } catch (NumberFormatException bad) {
            src.sendFailure(Component.literal("structure: bad number in " + args));
            return 0;
        }
        src.sendFailure(Component.literal("structure load <name> <x y z> ... | save <name> <from> <to> ... | delete <name>"));
        return 0;
    }

    // player uuid -> categories switched off. bedrock keeps this on the player, java has nowhere to put it
    private static final Map<java.util.UUID, java.util.Set<String>> INPUT_OFF = new HashMap<>();

    // camera and movement are what the client can lock, the rest bedrock has (jump, sneak, mount...) count as movement
    static boolean setInputPermission(ServerPlayer p, String perm, boolean on) {
        String cat = perm.toLowerCase(Locale.ROOT).equals("camera") ? "camera" : "movement";
        var off = INPUT_OFF.computeIfAbsent(p.getUUID(), k -> new java.util.HashSet<>());
        if (on) off.remove(cat); else off.add(cat);
        JsonObject o = new JsonObject();
        o.addProperty("a", "input");
        o.addProperty("camera", !off.contains("camera"));
        o.addProperty("movement", !off.contains("movement"));
        BedrockKamerzysta.wyslij(p, o);
        return true;
    }

    static boolean inputPermission(ServerPlayer p, String perm) {
        var off = INPUT_OFF.get(p.getUUID());
        String cat = perm.toLowerCase(Locale.ROOT).equals("camera") ? "camera" : "movement";
        return off == null || !off.contains(cat);
    }

    // "~", "~2", "^1" (taken as relative), "12.5"
    private static double wsp(String w, double base) {
        if (w.startsWith("~") || w.startsWith("^")) return base + (w.length() > 1 ? Double.parseDouble(w.substring(1)) : 0);
        return Double.parseDouble(w);
    }

    // bparticle <effect> [x y z]
    private static int czastka(CommandContext<CommandSourceStack> ctx, String args) {
        String[] w = args.trim().split("\\s+");
        var src = ctx.getSource();
        var at = src.getPosition();
        double x = at.x, y = at.y, z = at.z;
        try {
            if (w.length >= 4) { x = wsp(w[1], at.x); y = wsp(w[2], at.y); z = wsp(w[3], at.z); }
        } catch (NumberFormatException bad) {
            src.sendFailure(Component.literal("bparticle: bad position " + args));
            return 0;
        }
        String effect = w[0].contains(":") ? w[0] : "minecraft:" + w[0];
        var pkt = new com.koper.koper_lib.api.core.BedrockCzastkaPayload(effect, x, y, z);
        for (ServerPlayer p : src.getLevel().players())
            if (p.distanceToSqr(x, y, z) < 128 * 128) com.koper.koper_lib.api.core.KoperNetwork.send(p, pkt);
        return 1;
    }

    // bsummon <type> <x y z> <spawn event> [name...]
    private static int przywolaj(CommandContext<CommandSourceStack> ctx, String args) {
        String[] w = args.trim().split("\\s+");
        var src = ctx.getSource();
        if (w.length < 5) { src.sendFailure(Component.literal("bsummon <type> <x y z> <event> [name]")); return 0; }
        var at = src.getPosition();
        double x, y, z;
        try { x = wsp(w[1], at.x); y = wsp(w[2], at.y); z = wsp(w[3], at.z); }
        catch (NumberFormatException bad) { src.sendFailure(Component.literal("bsummon: bad position " + args)); return 0; }
        var rl = net.minecraft.resources.Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.doJavy(w[0]));
        var type = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        if (type == null) { src.sendFailure(Component.literal("unknown entity " + w[0])); return 0; }
        var level = src.getLevel();
        Entity e = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (e == null) return 0;
        e.snapTo(x, y, z, level.getRandom().nextFloat() * 360f, 0f);
        if (w.length > 5) e.setCustomName(Component.literal(String.join(" ", java.util.Arrays.copyOfRange(w, 5, w.length))));
        boolean ours = BedrockZachowanie.ma(e);
        if (ours) BedrockZachowanie.overrideSpawnEvent(e, w[4]);
        if (e instanceof net.minecraft.world.entity.Mob m)
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), net.minecraft.world.entity.EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(e);
        if (!ours && !w[4].equals("minecraft:entity_spawned")) BedrockZachowanie.event(e, w[4], src.getEntity());
        return 1;
    }

    private static int graj(CommandContext<CommandSourceStack> ctx, boolean next, boolean blend, boolean stop, boolean ctrl)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var who = net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "who");
        String anim = StringArgumentType.getString(ctx, "animation");
        for (Entity e : who) BedrockPytajnik.zagraj(e, anim,
            next ? StringArgumentType.getString(ctx, "next_state") : "",
            blend ? com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "blend_out_time") : 0f,
            stop ? StringArgumentType.getString(ctx, "stop_expression") : "",
            ctrl ? StringArgumentType.getString(ctx, "controller") : "", null);
        return who.size();
    }

    static void scriptEvent(String id, String message, CommandSourceStack src) {
        if (id == null || !id.contains(":")) {
            if (src != null) src.sendFailure(Component.literal("scriptevent id needs a namespace, like koper:hello"));
            return;
        }
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("message", message == null ? "" : message);
        Entity e = src == null ? null : src.getEntity();
        d.addProperty("sourceType", e != null ? "Entity" : "Server");
        if (e != null) {
            d.add("sourceEntity", BedrockPytajnik.entRef(e));
            d.add("initiator", BedrockPytajnik.entRef(e));
        }
        BedrockSkrypciarz.fireAfter("scriptEventReceive", d);
    }

    static void register(BedrockSkrypciarz.Addon who, JsonObject q) {
        MinecraftServer server = BedrockSkrypciarz.server();
        if (who == null || server == null) return;
        String name = q.get("name").getAsString().toLowerCase(Locale.ROOT);
        int perm = q.has("perm") ? q.get("perm").getAsInt() : 0;
        WLASCICIEL.put(name, who);
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        List<String> names = new ArrayList<>();
        names.add(name);
        // bedrock lets you drop the namespace when nobody else took the short name
        String bare = name.contains(":") ? name.substring(name.indexOf(':') + 1) : null;
        if (bare != null && (d.getRoot().getChild(bare) == null || WLASCICIEL.containsKey("~" + bare))) {
            names.add(bare);
            WLASCICIEL.put("~" + bare, who);
        }
        for (String n : names) {
            LiteralArgumentBuilder<CommandSourceStack> lit = Commands.literal(n)
                .requires(src -> perm <= 0 || src.permissions().hasPermission(perm >= 2 ? Permissions.COMMANDS_ADMIN : Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> run(name, "", ctx))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> run(name, StringArgumentType.getString(ctx, "args"), ctx)));
            d.register(lit);
        }
    }

    private static int run(String name, String args, CommandContext<CommandSourceStack> ctx) {
        BedrockSkrypciarz.Addon who = WLASCICIEL.get(name);
        if (who == null) {
            ctx.getSource().sendFailure(Component.literal("the addon behind /" + name + " is not loaded"));
            return 0;
        }
        JsonObject m = new JsonObject();
        m.addProperty("t", "cmd");
        m.addProperty("name", name);
        m.addProperty("args", args);
        Entity e = ctx.getSource().getEntity();
        if (e != null) m.add("src", BedrockPytajnik.ref(e));
        String ans = BedrockSkrypciarz.call(who, m.toString());
        int status = 0;
        String msg = null;
        if (ans != null && !ans.isEmpty()) {
            JsonObject r = JsonParser.parseString(ans).getAsJsonObject();
            status = r.has("status") ? r.get("status").getAsInt() : 0;
            msg = r.has("message") && !r.get("message").isJsonNull() ? r.get("message").getAsString() : null;
        }
        if (msg != null) {
            if (status == 0) { String out = msg; ctx.getSource().sendSuccess(() -> Component.literal(out), false); }
            else ctx.getSource().sendFailure(Component.literal(msg));
        }
        return status == 0 ? 1 : 0;
    }

    static void resendTree(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(p);
    }

    // ── bedrock -> java command syntax: BedrockSkladnia, which has no minecraft in it and runs in plain tests ─

    static {
        BedrockSkladnia.addonNs = () -> {
            String tu = BedrockSkladnia.PACK_NAMESPACE.get();
            if (tu != null) return tu;
            var a = BedrockSkrypciarz.teraz();
            return a != null ? a.ns : null;
        };
        BedrockSkladnia.soundNs = () -> {
            var a = BedrockSkrypciarz.teraz();
            return a != null ? BedrockTlumacz.str(a.sidecar, "sound_namespace", null) : null;
        };
        BedrockSkladnia.warn = BedrockZachowanie::once;
        BedrockSkladnia.javaDzwiek = id -> {
            var rl = net.minecraft.resources.Identifier.tryParse(id);
            return rl != null && net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.containsKey(rl);
        };
    }

    // a java command run the way the server runs one (performCommand: /function and the like only work that way,
    // dispatcher.execute throws "This function should not run" on them). nothing printed. gives the summed result,
    // throws on a command that does not parse or fails, with java's own message
    static int runQuietly(MinecraftServer srv, CommandSourceStack src, String cmd) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        StringBuilder error = new StringBuilder();
        int[] result = {0};
        boolean[] ok = {false};
        net.minecraft.commands.CommandSource listener = new net.minecraft.commands.CommandSource() {
            @Override public void sendSystemMessage(Component c) { if (error.isEmpty()) error.append(c.getString()); }
            @Override public boolean acceptsSuccess() { return false; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack s = src.withSource(listener).withCallback((success, r) -> { if (success) { ok[0] = true; result[0] += r; } });
        var parsed = srv.getCommands().getDispatcher().parse(cmd, s);
        var zla = Commands.getParseException(parsed);
        if (zla != null) throw zla;
        srv.getCommands().performCommand(parsed, cmd);
        if (!ok[0] && !error.isEmpty())
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal(error.toString())).create();
        return result[0];
    }

    // what runQuietly throws for a command java can't even read, as opposed to one that ran and found nobody
    static boolean isUnparseable(com.mojang.brigadier.exceptions.CommandSyntaxException e) {
        return e.getInput() != null;
    }

    static String przetlumaczDla(String ns, String raw) {
        return BedrockSkladnia.przetlumaczDla(ns, raw);
    }

    public static String przetlumacz(String raw) {
        return BedrockSkladnia.przetlumacz(raw);
    }

    static String typ(String id) {
        return BedrockSkladnia.typ(id);
    }
}
