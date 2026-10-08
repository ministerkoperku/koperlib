package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.core.KoperCommands;
import com.koper.koper_lib.factory.DimensionFactory;
import com.koper.koper_lib.factory.EntityFactory;
import com.koper.koper_lib.network.KoperNetworking;
import com.koper.koper_lib.network.ReloadResourcesPayload;
import com.koper.koper_lib.panama.RustBridge;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// registers all /koperlib subcommands
public class CommandRegistry {

    // suggest pack folder names from both loaded map and disk
    private static final SuggestionProvider<CommandSourceStack> PACK_SUGGESTIONS = (ctx, builder) -> {
        for (String name : FullPackLoader.getLoadedPacks()) builder.suggest(name);
        File dir = KoperLibDirectories.FULLPACKS.toFile();
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files)
                    if (f.isDirectory() && !f.getName().startsWith(".")) builder.suggest(f.getName());
        }
        return builder.buildFuture();
    };

    public static void register() {
        KoperCommands.register("legacy-bundled-modules", root -> {

            root.then(Commands.literal("reload")
                .executes(ctx -> executeReload(ctx.getSource(), false))
                .then(Commands.literal("deep")
                    .executes(ctx -> executeReload(ctx.getSource(), true))));

            root.then(packSubcmds("fullpack"));

            root.then(Commands.literal("status")
                .executes(ctx -> executeStatus(ctx.getSource())));

            root.then(Commands.literal("calls")
                .executes(ctx -> {
                    var ids = new java.util.ArrayList<>(com.koper.koper_lib.api.KoperCalls.ids());
                    java.util.Collections.sort(ids);
                    ctx.getSource().sendSystemMessage(Component.literal("[KoperCalls] " + ids.size() + " registered: " + String.join(", ", ids)));
                    return 1;
                }));

            root.then(Commands.literal("itemtypes")
                .executes(ctx -> {
                    var ids = new java.util.ArrayList<>(com.koper.koper_lib.api.KoperItemTypes.ids());
                    java.util.Collections.sort(ids);
                    ctx.getSource().sendSystemMessage(Component.literal("[KoperItemTypes] " + ids.size() + " registered: " + String.join(", ", ids)));
                    return 1;
                }));

            root.then(Commands.literal("stress")
                .requires(src -> src.permissions().hasPermission(
                    net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> executeStress(ctx.getSource(), "all"))
                .then(Commands.argument("what", StringArgumentType.word())
                    .suggests((c, b) -> {
                        b.suggest("all");
                        for (String id : com.koper.koper_lib.core.KoperTorturer.ids()) b.suggest(id);
                        return b.buildFuture();
                    })
                    .executes(ctx -> executeStress(ctx.getSource(), StringArgumentType.getString(ctx, "what")))));

            root.then(Commands.literal("obb")
                .executes(ctx -> {
                    boolean s = com.koper.koper_lib.kodel.KodelHitboxDebug.toggle();
                    ctx.getSource().sendSystemMessage(Component.literal(
                        s ? "§a[KoperLib] OBB hitbox debug ON" : "§c[KoperLib] OBB hitbox debug OFF"));
                    return 1;
                }));

            root.then(Commands.literal("config")
                .executes(ctx -> executeConfig(ctx.getSource()))
                .then(Commands.literal("reload")
                    .executes(ctx -> {
                        com.koper.koper_lib.api.core.KoperConfigs.reloadAll();
                        // without this a module flipped back on in the file stayed off till restart
                        com.koper.koper_lib.core.KoperRuntime.applyConfig();
                        ctx.getSource().sendSystemMessage(Component.literal("§a[KoperLib] Config reloaded. "
                            + "Modules: " + moduleLine()));
                        return 1;
                    })));

            root.then(Commands.literal("debug")
                .executes(ctx -> {
                    var cfg = com.koper.koper_lib.config.KoperLibConfig.get();
                    cfg.debugMode = !cfg.debugMode;
                    com.koper.koper_lib.config.KoperLibConfig.save();
                    ctx.getSource().sendSystemMessage(Component.literal(
                        "§6[KoperLib] Debug mode: " + (cfg.debugMode ? "§aON" : "§cOFF")));
                    if (cfg.debugMode) com.koper.koper_lib.core.KoperDebug.dumpToLog();
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    com.koper.koper_lib.config.KoperLibConfig.get().debugMode = true;
                    com.koper.koper_lib.config.KoperLibConfig.save();
                    com.koper.koper_lib.core.KoperDebug.dumpToLog();
                    ctx.getSource().sendSystemMessage(Component.literal("§a[KoperLib] Debug ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    com.koper.koper_lib.config.KoperLibConfig.get().debugMode = false;
                    com.koper.koper_lib.config.KoperLibConfig.save();
                    ctx.getSource().sendSystemMessage(Component.literal("§c[KoperLib] Debug OFF"));
                    return 1;
                }))
                .then(Commands.literal("dump").executes(ctx -> {
                    com.koper.koper_lib.core.KoperDebug.dumpToChat(ctx.getSource());
                    return 1;
                })));

            root.then(Commands.literal("give")
                .then(Commands.argument("item", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        String prefix = builder.getRemainingLowerCase();
                        // only suggest items from koper_lib itself + enabled pack namespaces
                        Set<String> enabledNs = new HashSet<>(FullPackLoader.getEnabledNamespaces());
                        enabledNs.add(KoperLib.MOD_ID);
                        BuiltInRegistries.ITEM.keySet().stream()
                            .filter(id -> enabledNs.contains(id.getNamespace()))
                            .map(Identifier::toString)
                            .filter(s -> s.startsWith(prefix))
                            .limit(50)
                            .forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> executeGive(ctx.getSource(), StringArgumentType.getString(ctx, "item"), 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> executeGive(ctx.getSource(),
                            StringArgumentType.getString(ctx, "item"),
                            IntegerArgumentType.getInteger(ctx, "count"))))));

            // physics commands
            root.then(Commands.literal("physics")
                .then(Commands.literal("make")
                    .executes(ctx -> executePhysicsMake(ctx.getSource()))
                    // wand-free variant: two corners straight from the command line, so scripts
                    // and anything driving the game remotely can build one without clicking
                    .then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(ctx -> executePhysicsMakeAt(ctx.getSource(),
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "to"))))))
                .then(Commands.literal("pos1")
                    .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> executePhysicsSetPos(ctx.getSource(), 0,
                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("pos2")
                    .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> executePhysicsSetPos(ctx.getSource(), 1,
                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("list")
                    .executes(ctx -> executePhysicsList(ctx.getSource())))
                .then(Commands.literal("props")
                    .executes(ctx -> executePhysicsProps(ctx.getSource())))
                .then(Commands.literal("joints")
                    .executes(ctx -> executePhysicsJoints(ctx.getSource())))
                .then(idCmd("destroy", CommandRegistry::executePhysicsDestroy))
                .then(idCmd("tp", CommandRegistry::executePhysicsTp))
                .then(idCmd("selfright", CommandRegistry::executePhysicsSelfRight))
                .then(Commands.literal("aero")
                    .then(Commands.literal("default")
                        .then(Commands.argument("mode", StringArgumentType.word())
                            .suggests((ctx, builder) -> { builder.suggest("low"); builder.suggest("correct"); builder.suggest("extreme"); return builder.buildFuture(); })
                            .executes(ctx -> executePhysicsAeroDefault(ctx.getSource(),
                                StringArgumentType.getString(ctx, "mode")))))
                    .then(Commands.argument("id", LongArgumentType.longArg())
                        .then(Commands.argument("mode", StringArgumentType.word())
                            .suggests((ctx, builder) -> { builder.suggest("low"); builder.suggest("correct"); builder.suggest("extreme"); return builder.buildFuture(); })
                            .executes(ctx -> executePhysicsAero(ctx.getSource(), LongArgumentType.getLong(ctx, "id"),
                                StringArgumentType.getString(ctx, "mode"))))))
                .then(Commands.literal("pause")
                    .executes(ctx -> {
                        com.koper.koper_lib.physics.KoperPhys.setPaused(true);
                        ctx.getSource().sendSystemMessage(Component.literal("§6[KoperLib] Physics §cPAUSED"));
                        return 1;
                    }))
                .then(Commands.literal("resume")
                    .executes(ctx -> {
                        com.koper.koper_lib.physics.KoperPhys.setPaused(false);
                        ctx.getSource().sendSystemMessage(Component.literal("§6[KoperLib] Physics §aRESUMED"));
                        return 1;
                    }))
                .then(Commands.literal("step")
                    .executes(ctx -> {
                        if (!com.koper.koper_lib.physics.KoperPhys.isPaused()) {
                            ctx.getSource().sendSystemMessage(Component.literal("§c[KoperLib] Not paused — use §e/koperlib physics pause§c first"));
                            return 0;
                        }
                        com.koper.koper_lib.physics.KoperPhys.requestStep();
                        ctx.getSource().sendSystemMessage(Component.literal("§6[KoperLib] Stepped one tick"));
                        return 1;
                    }))
                .then(idCmd("facecull", CommandRegistry::executePhysicsFaceCull))
                .then(idCmd("land", CommandRegistry::executePhysicsLand))
                .then(idCmd("break", CommandRegistry::executePhysicsBreak))
                // seat/joint testery — sanity-check the khys primitives without any addon in the way
                .then(Commands.literal("seat")
                    .then(Commands.argument("id", LongArgumentType.longArg())
                        .executes(ctx -> executePhysicsSeat(ctx.getSource(), LongArgumentType.getLong(ctx, "id"), 0f, 1f, 0f))
                        .then(Commands.argument("lx", FloatArgumentType.floatArg())
                            .then(Commands.argument("ly", FloatArgumentType.floatArg())
                                .then(Commands.argument("lz", FloatArgumentType.floatArg())
                                    .executes(ctx -> executePhysicsSeat(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "id"),
                                        FloatArgumentType.getFloat(ctx, "lx"),
                                        FloatArgumentType.getFloat(ctx, "ly"),
                                        FloatArgumentType.getFloat(ctx, "lz"))))))))
                .then(Commands.literal("joint")
                    .then(Commands.argument("a", LongArgumentType.longArg())
                        .then(Commands.argument("b", LongArgumentType.longArg())
                            .executes(ctx -> executePhysicsJoint(ctx.getSource(),
                                LongArgumentType.getLong(ctx, "a"), LongArgumentType.getLong(ctx, "b"))))))
                .then(Commands.literal("motor")
                    .then(Commands.argument("jointId", LongArgumentType.longArg())
                        .then(Commands.argument("vel", FloatArgumentType.floatArg())
                            .then(Commands.argument("torque", FloatArgumentType.floatArg())
                                .executes(ctx -> executePhysicsMotor(ctx.getSource(),
                                    LongArgumentType.getLong(ctx, "jointId"),
                                    FloatArgumentType.getFloat(ctx, "vel"),
                                    FloatArgumentType.getFloat(ctx, "torque")))))))
                .then(Commands.literal("jstate")
                    .then(Commands.argument("jointId", LongArgumentType.longArg())
                        .executes(ctx -> executePhysicsJointState(ctx.getSource(), LongArgumentType.getLong(ctx, "jointId")))))
            );

            // gui — open a registered kui screen on the caller's client
            root.then(Commands.literal("gui")
                .executes(ctx -> executeGuiList(ctx.getSource()))
                .then(Commands.literal("list")
                    .executes(ctx -> executeGuiList(ctx.getSource())))
                .then(Commands.literal("open")
                    .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((c, b) -> { com.koper.koper_lib.kui.KuiBook.ids().forEach(b::suggest); return b.buildFuture(); })
                        .executes(ctx -> executeGuiOpen(ctx.getSource(), StringArgumentType.getString(ctx, "id").trim()))))
                .then(Commands.literal("bake")
                    .executes(ctx -> {
                        com.koper.koper_lib.kui.KuiBaker.syncAll(true);
                        ctx.getSource().sendSystemMessage(Component.literal("§a[Kui] Force-baked all gui textures. See pack/textures/gui/"));
                        return 1;
                    })));

            // fullpack has the same one, whichever tree is live gets it
            root.then(Commands.literal("snitch").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayer();
                if (player == null) return 0;
                boolean on = com.koper.koper_lib.scripting.KoperSnitch.nosy(player);
                ctx.getSource().sendSystemMessage(Component.literal(on
                    ? "§a[Snitch] on. every player:* event lands in chat, heartbeat muted."
                    : "§7[Snitch] off."));
                return 1;
            }));

            root.then(Commands.literal("kfx")
                .executes(ctx -> executeKfxList(ctx.getSource()))
                .then(Commands.literal("list")
                    .executes(ctx -> executeKfxList(ctx.getSource())))
                .then(Commands.literal("clear")
                    .executes(ctx -> executeKfxClear(ctx.getSource())))
                .then(Commands.literal("inspect")
                    .then(Commands.argument("handle", LongArgumentType.longArg())
                        .executes(ctx -> executeKfxInspect(ctx.getSource(),
                            LongArgumentType.getLong(ctx, "handle")))))
                .then(Commands.literal("spawn")
                    .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((c, b) -> {
                            com.koper.koper_lib.kfx.KfxBook.all().forEach(f -> b.suggest(f.id().toString()));
                            return b.buildFuture();
                        })
                        .executes(ctx -> executeKfxSpawn(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 8.0f))))
                .then(Commands.literal("cast")
                    .then(Commands.argument("range", FloatArgumentType.floatArg(0.25f, 128.0f))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                            .suggests((c, b) -> {
                                com.koper.koper_lib.kfx.KfxBook.all().forEach(f -> b.suggest(f.id().toString()));
                                return b.buildFuture();
                            })
                            .executes(ctx -> executeKfxSpawn(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                FloatArgumentType.getFloat(ctx, "range"))))))
                .then(Commands.literal("beam")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.BEAM)))
                .then(Commands.literal("ring")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.RING)))
                .then(Commands.literal("sphere")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.SPHERE)))
                .then(Commands.literal("vortex")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.VORTEX)))
                .then(Commands.literal("emitter")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER)))
                .then(Commands.literal("chargebeam")
                    .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.CHARGE_BEAM)))
                .then(Commands.literal("fountain")
                    .executes(ctx -> executeKfxEmitterShape(ctx.getSource(), "cone")))
                .then(Commands.literal("nova")
                    .executes(ctx -> executeKfxEmitterShape(ctx.getSource(), "sphere")))
                .then(Commands.literal("sparkline")
                    .executes(ctx -> executeKfxEmitterShape(ctx.getSource(), "beam")))
                .then(Commands.literal("ringspray")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "ring", "ring")))
                .then(Commands.literal("shards")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "sphere", "shard")))
                .then(Commands.literal("stars")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "cone", "star")))
                .then(Commands.literal("orbit")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "ring", "orb3d", "orbit")))
                .then(Commands.literal("implode")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "sphere", "spark", "inward")))
                .then(Commands.literal("swirl")
                    .executes(ctx -> executeKfxEmitterPreset(ctx.getSource(), "sphere", "shard", "swirl")))
                .then(Commands.literal("showcase")
                    .executes(ctx -> executeKfxShowcase(ctx.getSource())))
                .then(Commands.literal("demo")
                    .then(Commands.literal("beam")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.BEAM)))
                    .then(Commands.literal("ring")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.RING)))
                    .then(Commands.literal("sphere")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.SPHERE)))
                    .then(Commands.literal("vortex")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.VORTEX)))
                    .then(Commands.literal("emitter")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER)))
                    .then(Commands.literal("chargebeam")
                        .executes(ctx -> executeKfxDemo(ctx.getSource(), com.koper.koper_lib.kfx.KfxDef.Kind.CHARGE_BEAM)))));

        });
    }

    // builds enable/disable/list subcommands under the given literal name
    private static LiteralArgumentBuilder<CommandSourceStack> packSubcmds(String literal) {
        return Commands.literal(literal)
            .executes(ctx -> executeList(ctx.getSource()))
            .then(Commands.literal("list")
                .executes(ctx -> executeList(ctx.getSource())))
            .then(Commands.literal("enable")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(PACK_SUGGESTIONS)
                    .executes(ctx -> executeEnable(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("disable")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(PACK_SUGGESTIONS)
                    .executes(ctx -> executeDisable(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            // higher packs own what packs both define (FullPackLoader.pack_order)
            .then(Commands.literal("priority")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(PACK_SUGGESTIONS)
                    .then(Commands.argument("where", StringArgumentType.word())
                        .suggests((c, b) -> { for (String w : new String[] {"top", "up", "down", "bottom"}) b.suggest(w); return b.buildFuture(); })
                        .executes(ctx -> executePriority(ctx.getSource(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "where"))))));
    }

    private static int executePriority(CommandSourceStack source, String pack, String where) {
        if (!java.util.List.of("top", "up", "down", "bottom").contains(where)) {
            source.sendFailure(Component.literal("§c[KoperLib] where is top, up, down or bottom"));
            return 0;
        }
        if (!FullPackLoader.move(pack, where)) {
            source.sendFailure(Component.literal("§c[KoperLib] Unknown fullpack: " + pack));
            return 0;
        }
        executeList(source);
        source.sendSystemMessage(Component.literal("§7restart or /koperlib reload to apply"));
        return 1;
    }

    // /koperlib physics <name> <id> → handler(source, id) — collapses the 6 identical id subcommands
    private static LiteralArgumentBuilder<CommandSourceStack> idCmd(
            String name, java.util.function.BiFunction<CommandSourceStack, Long, Integer> handler) {
        return Commands.literal(name).then(Commands.argument("id", LongArgumentType.longArg())
            .executes(ctx -> handler.apply(ctx.getSource(), LongArgumentType.getLong(ctx, "id"))));
    }

    // phases 0-3 shared by executeReload and executeReloadFromCode
    private static String moduleLine() {
        var sb = new StringBuilder();
        for (var module : com.koper.koper_lib.api.core.KoperModules.snapshot()) {
            boolean on = module.state() == com.koper.koper_lib.api.core.KoperModules.State.LOADED;
            sb.append(on ? "§a" : "§c").append(module.id()).append("§r ");
        }
        return sb.toString();
    }

    // hammers the non-physics subsystems and prints what folded. blocks the server thread on purpose —
    // half of these bugs only show up when they're competing with the tick
    private static int executeStress(CommandSourceStack source, String what) {
        var known = com.koper.koper_lib.core.KoperTorturer.ids();
        if (!"all".equals(what) && !known.contains(what)) {
            source.sendFailure(Component.literal("§c[Torturer] no such torture. have: all, " + String.join(", ", known)));
            return 0;
        }

        source.sendSystemMessage(Component.literal("§e[Torturer] beating on §f" + what + "§e — game will stutter, that's the point"));
        var wyniki = com.koper.koper_lib.core.KoperTorturer.run(what,
            line -> source.sendSystemMessage(Component.literal(line)));

        long zle = wyniki.stream().filter(w -> w.stan() == com.koper.koper_lib.core.KoperTorturer.Stan.ZLE).count();
        source.sendSystemMessage(Component.literal(zle == 0
            ? "§a[Torturer] " + wyniki.size() + " ran, nothing folded"
            : "§c[Torturer] §l" + zle + "§r§c of " + wyniki.size() + " folded — see above"));
        if ("all".equals(what) || "reloadsztorm".equals(what))
            source.sendSystemMessage(Component.literal("§8  caches were rebuilt without a client sync — run /koperlib reload"));
        return wyniki.isEmpty() ? 0 : 1;
    }

    // public so KoperTorturer can spam it without going through the whole resource-reload dance
    public static void koperReloadCaches() {
        com.koper.koper_lib.api.FullpackAddons.prepareReload();
        try {
        com.koper.koper_lib.physics.weight.KhysWeightBook.loadAll();
        com.koper.koper_lib.physics.dim.KhysDimensions.loadAll();
        com.koper.koper_lib.scripting.JavaHookRegistry.clearAll();
        com.koper.koper_lib.scripting.ScriptCommandDispatcher.clearBacklog(); // stale commands from dead scripts
        com.koper.koper_lib.scripting.KoperScriptCommands.clear(); // pack command declarations die with the pack
        UniversalScriptEngine.clearCache();
        KoperLib.VIRTUAL_PACK.clearAssets();
        CreativeTabRegistry.clearDeferredItems();
        ContentRegistry.clearAll();
        com.koper.koper_lib.block.KoperBrainRegistry.clear(); // block objects get rebuilt, the old refs are dead
        EntityFactory.clearReloadData();
        DimensionFactory.clearReloadData();
        com.koper.koper_lib.kui.KuiBook.clear();

        FullPackLoader.loadFullPacks();

        ContentRegistry.prepareRegistriesForNewContent();
        FullPackLoader.getAllPacks().forEach((folder, meta) -> {
            if (!FullPackLoader.isEnabled(folder)) return;
            String ns = meta != null ? meta.getEffectiveNamespace(folder) : folder;
            java.nio.file.Path packRoot = KoperLibDirectories.FULLPACKS.resolve(folder);
            com.koper.koper_lib.scripting.JavaHookRegistry.loadPackJava(packRoot, ns);
        });
        new UniversalLoader().loadExternalContent();

        CreativeTabRegistry.removeDisabledPackTabs();
        CreativeTabRegistry.processTabs(KoperLib.MOD_ID);

        // bake any json-mode gui whose layout changed into an editable png (+ regions), backing up hand edits
        com.koper.koper_lib.kui.KuiBaker.syncAll(false);
        } catch (RuntimeException | Error error) {
            com.koper.koper_lib.api.FullpackAddons.abortReload();
            throw error;
        }
    }

    // re-open any kui container menu that's open, so layout edits show after a reload (items persist)
    private static void refreshOpenKuiMenus(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof com.koper.koper_lib.kui.KuiMenu km) {
                String gid = km.data.id();
                server.execute(() -> com.koper.koper_lib.kui.KuiOpen.open(p, gid));
            }
        }
    }

    private static int executeReload(CommandSourceStack source, boolean deep) {
        long t0 = System.currentTimeMillis();
        source.sendSystemMessage(Component.literal("§e[KoperLib] Reloading" + (deep ? " (deep)..." : "...")));

        DevPackWatcher.beginInternalReload();
        try {
            koperReloadCaches();
        } catch (Throwable error) {
            com.koper.koper_lib.api.FullpackAddons.abortReload();
            DevPackWatcher.endInternalReload();
            throw error;
        }

        Set<String> ns = new HashSet<>(FullPackLoader.getAllPacks().keySet());
        ns.add(KoperLib.MOD_ID);
        final long items    = BuiltInRegistries.ITEM.keySet().stream().filter(id -> ns.contains(id.getNamespace())).count();
        final long blocks   = BuiltInRegistries.BLOCK.keySet().stream().filter(id -> ns.contains(id.getNamespace())).count();
        final long entities = EntityFactory.getRegisteredEntities().size();

        // phase 4 — reload server resources + ping clients
        source.getServer().getPackRepository().reload();
        Collection<String> selectedPacks = source.getServer().getPackRepository().getSelectedIds();
        source.getServer().reloadResources(selectedPacks).whenComplete((ignored, reloadError) -> {
            try {
                if (reloadError != null) {
                    KoperLib.LOGGER.error("[Reload] resource reload failed", reloadError);
                    source.sendFailure(Component.literal("§c[KoperLib] Reload failed: " + reloadError.getMessage()));
                    return;
                }
            if (deep) {
                try {
                    source.getServer().getCommands().getDispatcher()
                        .execute("reload", source.getServer().createCommandSourceStack());
                } catch (Exception e) {
                    KoperLib.LOGGER.warn("[Reload] vanilla /reload failed: {}", e.getMessage());
                }
            }
            for (var p : source.getServer().getPlayerList().getPlayers())
                ServerPlayNetworking.send(p, new ReloadResourcesPayload());
            refreshOpenKuiMenus(source.getServer());

            long ms = System.currentTimeMillis() - t0;
            source.sendSystemMessage(Component.literal("§a[KoperLib] §lDone§r§a in §f" + ms + "ms"));
            source.sendSystemMessage(Component.literal(
                "  §7Items: §f" + items + " §7| Blocks: §f" + blocks + " §7| Entities: §f" + entities));
            if (!deep) source.sendSystemMessage(Component.literal("  §8/koperlib reload deep for full vanilla datapack reload"));
            KoperLib.LOGGER.info("[KoperLib] Reload {}ms items={} blocks={} entities={}", ms, items, blocks, entities);
            } finally {
                DevPackWatcher.endInternalReload();
            }
        });
        return 1;
    }

    // called from KoperPackShelf (API) — same phases as executeReload but no chat feedback
    public static void executeReloadFromCode(net.minecraft.server.MinecraftServer server) {
        DevPackWatcher.beginInternalReload();
        try {
            koperReloadCaches();
        } catch (Throwable error) {
            DevPackWatcher.endInternalReload();
            throw error;
        }
        server.getPackRepository().reload();
        Collection<String> selected = server.getPackRepository().getSelectedIds();
        server.reloadResources(selected).whenComplete((ignored, reloadError) -> {
            try {
                if (reloadError != null) {
                    KoperLib.LOGGER.error("[KoperLib] API-triggered reload failed", reloadError);
                    return;
                }
                for (var p : server.getPlayerList().getPlayers())
                    ServerPlayNetworking.send(p, new ReloadResourcesPayload());
                KoperLib.LOGGER.info("[KoperLib] API-triggered reload complete.");
            } finally {
                DevPackWatcher.endInternalReload();
            }
        });
    }

    // ── list ─────────────────────────────────────────────────────────────────

    private static int executeList(CommandSourceStack source) {
        Map<String, KoperMeta> packs = FullPackLoader.getAllPacks();
        if (packs.isEmpty()) {
            source.sendSystemMessage(Component.literal("§e[KoperLib] No fullpacks. Place in: run/koperlib/fullpacks/"));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§6[KoperLib] FullPacks (" + packs.size() + "), highest priority first:"));
        for (String name : FullPackLoader.ordered()) {
            KoperMeta meta = packs.get(name);
            boolean on     = FullPackLoader.isEnabled(name);
            String info    = meta != null
                ? " §7v" + meta.version + (!meta.author.isEmpty() ? " by " + meta.author : "")
                    + (meta.isLegacy ? " §8(legacy)" : " §8[" + meta.packFormat + "]")
                : "";
            source.sendSystemMessage(Component.literal("  " + (on ? "§a[ON] " : "§c[OFF]") + "§f" + name + info));
        }
        return 1;
    }

    // ── enable / disable ──────────────────────────────────────────────────────

    private static int executeEnable(CommandSourceStack source, String name) {
        FullPackLoader.setDisabled(name, false);
        source.sendSystemMessage(Component.literal("§a[KoperLib] §f" + name + "§a enabled. Run §e/koperlib reload§a to apply."));
        return 1;
    }

    private static int executeDisable(CommandSourceStack source, String name) {
        FullPackLoader.setDisabled(name, true);
        source.sendSystemMessage(Component.literal("§c[KoperLib] §f" + name + "§c disabled. Run §e/koperlib reload§c to apply."));
        return 1;
    }

    // ── status ────────────────────────────────────────────────────────────────

    private static int executeStatus(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("§6[KoperLib] ══ Status ══"));
        source.sendSystemMessage(Component.literal("  §7Version: §fv" + KoperLib.VERSION));

        StringBuilder mods = new StringBuilder("  §7Modules: ");
        for (var module : com.koper.koper_lib.api.core.KoperModules.snapshot()) {
            boolean on = module.state() == com.koper.koper_lib.api.core.KoperModules.State.LOADED;
            mods.append(on ? "§a" : "§c").append(module.id());
            if (!module.note().isBlank()) mods.append("§8(").append(module.note()).append(")");
            mods.append(' ');
        }
        source.sendSystemMessage(Component.literal(mods.toString()));

        boolean rustOk = RustBridge.isLoaded();
        source.sendSystemMessage(Component.literal("  §7Rust: "
            + (rustOk ? "§av" + RustBridge.version() : "§cjava-only")));

        Map<String, KoperMeta> packs = FullPackLoader.getAllPacks();
        long on  = packs.keySet().stream().filter(FullPackLoader::isEnabled).count();
        long off = packs.size() - on;
        source.sendSystemMessage(Component.literal(
            "  §7Packs: §f" + packs.size() + " §7(§a" + on + " on§7, §c" + off + " off§7)"));
        packs.forEach((name, meta) -> {
            boolean enabled = FullPackLoader.isEnabled(name);
            source.sendSystemMessage(Component.literal(
                "    " + (enabled ? "§a[ON]" : "§c[OFF]") + " §f" + name
                    + (meta != null ? " §8v" + meta.version : "")));
        });

        Set<String> ns = new HashSet<>(packs.keySet());
        ns.add(KoperLib.MOD_ID);
        long items    = BuiltInRegistries.ITEM.keySet().stream().filter(id -> ns.contains(id.getNamespace())).count();
        long blocks   = BuiltInRegistries.BLOCK.keySet().stream().filter(id -> ns.contains(id.getNamespace())).count();
        long entities = EntityFactory.getRegisteredEntities().size();
        source.sendSystemMessage(Component.literal("  §7Items: §f" + items + "  §7Blocks: §f" + blocks + "  §7Entities: §f" + entities));

        var cfg = com.koper.koper_lib.config.KoperLibConfig.get();
        source.sendSystemMessage(Component.literal("  §7Debug: " + (cfg.debugMode ? "§aON" : "§cOFF")
            + "  §7AutoReload: " + (com.koper.koper_lib.fullpack.config.FullpackConfig.get().autoReloadScripts ? "§aON" : "§cOFF")));
        source.sendSystemMessage(Component.literal("§8  /koperlib debug on|off  /koperlib give <id>  /koperlib reload"));
        return 1;
    }

    // ── config ────────────────────────────────────────────────────────────────

    private static int executeConfig(CommandSourceStack source) {
        var cfg = com.koper.koper_lib.config.KoperLibConfig.get();
        source.sendSystemMessage(Component.literal("§6[KoperLib] Config:"));
        source.sendSystemMessage(Component.literal("  §7Debug: "        + (cfg.debugMode ? "§aON" : "§cOFF")));
        source.sendSystemMessage(Component.literal("  §7Script timeout: §f" + com.koper.koper_lib.fullpack.config.FullpackConfig.get().scriptTimeoutMs + "ms"));
        source.sendSystemMessage(Component.literal("  §7Damage mult:    §f" + String.format("%.1fx", com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalDamageMultiplier)));
        source.sendSystemMessage(Component.literal("  §7Health mult:    §f" + String.format("%.1fx", com.koper.koper_lib.fullpack.config.FullpackConfig.get().globalHealthMultiplier)));
        source.sendSystemMessage(Component.literal("  §7Custom mobs:    " + (!com.koper.koper_lib.fullpack.config.FullpackConfig.get().disableCustomMobs ? "§aON" : "§cOFF")));
        return 1;
    }

    // ── give ──────────────────────────────────────────────────────────────────

    private static int executeGive(CommandSourceStack source, String itemId, int count) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Player only."));
            return 0;
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Unknown item: §f" + itemId));
            Set<String> ns = new HashSet<>(FullPackLoader.getAllPacks().keySet());
            ns.add(KoperLib.MOD_ID);
            source.sendSystemMessage(Component.literal("§7  Your namespaces:"));
            BuiltInRegistries.ITEM.keySet().stream()
                .filter(i -> ns.contains(i.getNamespace()))
                .limit(10)
                .forEach(i -> source.sendSystemMessage(Component.literal("§8    " + i)));
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        player.getInventory().add(new ItemStack(item, count));
        source.sendSystemMessage(Component.literal(
            "§a[KoperLib] Gave §f" + count + "x " + itemId + "§a to §f" + player.getScoreboardName()));
        return 1;
    }

    // ── gui (kui) ───────────────────────────────────────────────────────────────

    private static int executeGuiList(CommandSourceStack source) {
        var ids = com.koper.koper_lib.kui.KuiBook.ids();
        if (ids.isEmpty()) {
            source.sendSystemMessage(Component.literal("§e[Kui] No guis registered. Drop one in pack/gui/ and §e/koperlib reload"));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§6[Kui] Guis (" + ids.size() + "):"));
        for (var p : com.koper.koper_lib.kui.KuiBook.all())
            source.sendSystemMessage(Component.literal("  §f" + p.id + " §8" + p.mode + " " + p.w + "x" + p.h));
        return 1;
    }

    private static int executeGuiOpen(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("§c[Kui] Player only."));
            return 0;
        }
        if (!com.koper.koper_lib.kui.KuiOpen.open(player, id)) {
            source.sendSystemMessage(Component.literal("§c[Kui] Unknown gui: §f" + id));
            return 0;
        }
        return 1;
    }

    // kfx lab-ish commands: quick in-world preview until the real Kui editor lands
    private static int executeKfxList(CommandSourceStack source) {
        var all = com.koper.koper_lib.kfx.KfxBook.all();
        if (all.isEmpty()) {
            source.sendSystemMessage(Component.literal("[KFX] No effects loaded. Drop JSON in kfx/ or particles/ and /koperlib reload."));
            return 1;
        }
        source.sendSystemMessage(Component.literal("[KFX] Effects (" + all.size() + "):"));
        for (var fx : all) {
            source.sendSystemMessage(Component.literal("  " + fx.id() + " " + fx.kind().name().toLowerCase()
                + " r=" + fx.radius() + " life=" + (fx.lifetime() < 0 ? "forever" : fx.lifetime())
                + (fx.kind() == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? " style=" + fx.particleStyle() : "")));
        }
        return 1;
    }

    private static int executeKfxClear(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("[KFX] Player only."));
            return 0;
        }
        KoperNetworking.broadcastToLevel((ServerLevel) player.level(), new com.koper.koper_lib.network.KfxClearPayload());
        com.koper.koper_lib.kfx.KfxDiagnostics.clear();
        source.sendSystemMessage(Component.literal("[KFX] Cleared preview effects in this dimension."));
        return 1;
    }

    private static int executeKfxInspect(CommandSourceStack source, long handle) {
        for (String line : com.koper.koper_lib.kfx.KfxDiagnostics.describe(handle)) {
            source.sendSystemMessage(Component.literal(line));
        }
        return com.koper.koper_lib.kfx.KfxDiagnostics.snapshot(handle) == null ? 0 : 1;
    }

    private static int executeKfxSpawn(CommandSourceStack source, String id, float range) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("[KFX] Player only."));
            return 0;
        }
        var def = com.koper.koper_lib.kfx.KfxBook.get(id);
        if (def == null) {
            source.sendSystemMessage(Component.literal("[KFX] Unknown effect: " + id));
            return 0;
        }
        long handle = spawnLookFx(player, def, range);
        source.sendSystemMessage(Component.literal("[KFX] Spawned " + def.id() + " handle=" + handle
            + " (/koperlib kfx inspect " + handle + ")."));
        return 1;
    }

    private static int executeKfxDemo(CommandSourceStack source, com.koper.koper_lib.kfx.KfxDef.Kind kind) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("[KFX] Player only."));
            return 0;
        }
        var def = kfxDef("demo_" + kind.name().toLowerCase(), kind, 0xDD55CCFF, 0xEEFFFFFF,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.BEAM ? 0.4f : kind == com.koper.koper_lib.kfx.KfxDef.Kind.CHARGE_BEAM ? 0.95f : 1.25f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.CHARGE_BEAM ? 0.22f : 0.16f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.RING ? -1 : 80, kind == com.koper.koper_lib.kfx.KfxDef.Kind.RING);
        spawnLookFx(player, def, 8.0f);
        source.sendSystemMessage(Component.literal("[KFX] Demo " + kind.name().toLowerCase() + " spawned."));
        return 1;
    }

    private static int executeKfxEmitterShape(CommandSourceStack source, String shape) {
        return executeKfxEmitterPreset(source, shape, kfxParticleStyleForShape(shape));
    }

    private static int executeKfxEmitterPreset(CommandSourceStack source, String shape, String style) {
        return executeKfxEmitterPreset(source, shape, style, "free");
    }

    private static int executeKfxEmitterPreset(CommandSourceStack source, String shape, String style, String motion) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("[KFX] Player only."));
            return 0;
        }
        var def = kfxEmitterDef("demo_" + shape + "_" + style + "_" + motion, shape, style, motion);
        spawnLookFx(player, def, shape.equals("beam") ? 12.0f : 8.0f);
        source.sendSystemMessage(Component.literal("[KFX] Emitter " + shape + "/" + style + "/" + motion + " spawned."));
        return 1;
    }

    private static int executeKfxShowcase(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("[KFX] Player only."));
            return 0;
        }
        var center = kfxTarget(player, 14.0f);
        var level = (ServerLevel) player.level();

        var sphere = kfxDef("showcase_sphere", com.koper.koper_lib.kfx.KfxDef.Kind.SPHERE,
            0xAA35D7FF, 0xEEFFFFFF, 2.0f, 0.22f, 140, false);
        spawnWorldFx(level, sphere, center.x, center.y + 1.4, center.z, center.x, center.y + 1.4, center.z);

        var ringA = kfxDef("showcase_ring_a", com.koper.koper_lib.kfx.KfxDef.Kind.RING,
            0xCCFF4FD8, 0xFFFFFFFF, 2.7f, 0.22f, 160, false);
        spawnWorldFx(level, ringA, center.x, center.y + 1.4, center.z, center.x, center.y + 1.4, center.z);

        var ringB = kfxDef("showcase_ring_b", com.koper.koper_lib.kfx.KfxDef.Kind.RING,
            0xCC4DFF88, 0xFFFFFFFF, 1.65f, 0.18f, 160, false);
        spawnWorldFx(level, ringB, center.x, center.y + 0.65, center.z, center.x, center.y + 0.65, center.z);

        var vortex = kfxDef("showcase_vortex", com.koper.koper_lib.kfx.KfxDef.Kind.VORTEX,
            0xCCB24DFF, 0xFFFFFFFF, 1.45f, 0.18f, 170, false);
        spawnWorldFx(level, vortex, center.x, center.y + 1.15, center.z, center.x, center.y + 1.15, center.z);

        var emitter = kfxDef("showcase_emitter", com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER,
            0xDDA5F7FF, 0xFFFFFFFF, 0.24f, 0.08f, 150, false);
        spawnWorldFx(level, emitter, center.x, center.y + 1.35, center.z, center.x, center.y + 1.35, center.z);

        var beam = kfxDef("showcase_beam", com.koper.koper_lib.kfx.KfxDef.Kind.BEAM,
            0xCC55CCFF, 0xFFFFFFFF, 0.5f, 0.26f, 120, false);
        double[][] dirs = {
            { 3.2, 0.0, 0.0 }, { -3.2, 0.0, 0.0 }, { 0.0, 0.0, 3.2 }, { 0.0, 0.0, -3.2 },
            { 2.25, 1.2, 2.25 }, { -2.25, 1.2, 2.25 }, { 2.25, 1.2, -2.25 }, { -2.25, 1.2, -2.25 }
        };
        for (double[] d : dirs) {
            spawnWorldFx(level, beam,
                center.x, center.y + 1.4, center.z,
                center.x + d[0], center.y + 1.4 + d[1], center.z + d[2]);
        }

        source.sendSystemMessage(Component.literal("[KFX] Showcase spawned where you look."));
        return 1;
    }

    private static com.koper.koper_lib.kfx.KfxDef kfxDef(String name, com.koper.koper_lib.kfx.KfxDef.Kind kind,
            int color, int color2, float radius, float thickness, int lifetime, boolean loop) {
        return new com.koper.koper_lib.kfx.KfxDef(
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, name), kind, color, color2, radius, thickness,
            lifetime, loop, kind == com.koper.koper_lib.kfx.KfxDef.Kind.BEAM ? 0.0f : 72.0f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.VORTEX ? 2.6f : 1.8f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.BEAM ? 0.08f : 0.18f, 3.0f, 18.0f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? 54.0f : 0.0f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? 120 : 0,
            38, 0.72f, 0.16f, -0.0045f, 0.965f, radius * 0.12f,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? "sphere" : "point",
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? "star" : "sprite",
            "free", 38.0f, 10.0f, 2.8f, 0.72f,
            "",
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? 720 : 0,
            kind == com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER ? 0.014f : 0.0f,
            "none", 8, false, 0.65f, 0.08f,
            new com.koper.koper_lib.kfx.KfxLight(kind == com.koper.koper_lib.kfx.KfxDef.Kind.BEAM ? 12.0f : 10.0f, 1.0f, color));
    }

    private static com.koper.koper_lib.kfx.KfxDef kfxEmitterDef(String name, String shape, String style, String motion) {
        return new com.koper.koper_lib.kfx.KfxDef(
            Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, name), com.koper.koper_lib.kfx.KfxDef.Kind.EMITTER,
            0xDDA5F7FF, 0xFFFFFFFF, 0.24f, 0.08f,
            shape.equals("beam") ? 120 : 150, false, 0.0f, 1.6f, 0.12f, 2.0f, 18.0f,
            shape.equals("beam") ? 80.0f : 58.0f,
            shape.equals("beam") ? 160 : 140,
            shape.equals("beam") ? 26 : 40,
            shape.equals("beam") ? 0.24f : 0.76f,
            shape.equals("beam") ? 0.06f : 0.17f,
            shape.equals("cone") ? -0.006f : -0.0025f,
            0.965f, 0.035f, shape, style, motion, 40.0f, 10.0f, 2.8f, 0.72f, "", 850, 0.018f,
            "none", 8, false, 0.65f, 0.08f,
            new com.koper.koper_lib.kfx.KfxLight(10.0f, 1.0f, 0xDDA5F7FF));
    }

    private static String kfxParticleStyleForShape(String shape) {
        return switch (shape) {
            case "beam" -> "spark";
            case "sphere" -> "shard";
            case "cone" -> "star";
            default -> "sprite";
        };
    }

    private static net.minecraft.world.phys.Vec3 kfxTarget(ServerPlayer player, float range) {
        var hit = player.pick(range, 1.0f, false);
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            return hit.getLocation();
        }
        return player.getEyePosition().add(player.getLookAngle().scale(range));
    }

    private static long spawnLookFx(ServerPlayer player, com.koper.koper_lib.kfx.KfxDef def, float range) {
        var eye = player.getEyePosition();
        var look = player.getLookAngle();
        var start = eye.add(look.scale(0.8));
        var end = eye.add(look.scale(range));
        long id = System.nanoTime();
        com.koper.koper_lib.kfx.KfxApi.spawn((ServerLevel)player.level(), id, def,
            start.x, start.y, start.z, end.x, end.y, end.z);
        return id;
    }

    private static void spawnWorldFx(ServerLevel level, com.koper.koper_lib.kfx.KfxDef def,
                                     double sx, double sy, double sz, double ex, double ey, double ez) {
        long id = System.nanoTime() ^ Double.doubleToLongBits(sx + sy + sz + ex + ey + ez);
        com.koper.koper_lib.kfx.KfxApi.spawn(level, id, def, sx, sy, sz, ex, ey, ez);
    }

    // same two-point selection the wand fills, set from coordinates instead
    private static int executePhysicsSetPos(CommandSourceStack source, int corner, BlockPos pos) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Player only."));
            return 0;
        }
        com.koper.koper_lib.physics.KoperPhys.getTwoPointSelection(player.getUUID())[corner] = pos;
        source.sendSystemMessage(Component.literal(
            "§a[KoperLib] Pos " + (corner + 1) + " set to " + pos.toShortString()));
        return 1;
    }

    private static int executePhysicsMakeAt(CommandSourceStack source, BlockPos from, BlockPos to) {
        ServerLevel level = source.getLevel();
        String problem = com.koper.koper_lib.physics.KoperPhys.cuboidProblem(level, from, to);
        if (problem != null) {
            source.sendFailure(Component.literal("[KoperLib] " + problem + "."));
            return 0;
        }
        List<BlockPos> list = com.koper.koper_lib.physics.KoperPhys.collectCuboidBlocks(level, from, to);
        if (list.isEmpty()) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Nothing solid between those corners."));
            return 0;
        }
        long id = com.koper.koper_lib.physics.KoperPhys.makeKontraktion(level, list);
        if (id >= 0) {
            source.sendSystemMessage(Component.literal(
                "§a[KoperLib] Kontraktion spawned! (" + list.size() + " blocks, id=" + id + ")"));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§c[KoperLib] Spawn failed — check logs."));
        return 0;
    }

    private static int executePhysicsMake(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Player only."));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        BlockPos[] sel = com.koper.koper_lib.physics.KoperPhys.getTwoPointSelection(player.getUUID());

        if (sel != null && sel[0] != null && sel[1] != null) {
            String problem = com.koper.koper_lib.physics.KoperPhys.cuboidProblem(level, sel[0], sel[1]);
            if (problem != null) {
                source.sendFailure(Component.literal("[KoperLib] " + problem + "."));
                return 0;
            }
            List<BlockPos> list = com.koper.koper_lib.physics.KoperPhys.collectCuboidBlocks(level, sel[0], sel[1]);

            if (list.isEmpty()) {
                source.sendSystemMessage(Component.literal("§c[KoperLib] Selected cuboid has no blocks to turn into physics!"));
                return 0;
            }

            com.koper.koper_lib.physics.KoperPhys.clearTwoPointSelection(player.getUUID());
            long id = com.koper.koper_lib.physics.KoperPhys.makeKontraktion(level, list);
            if (id >= 0) {
                source.sendSystemMessage(Component.literal("§a[KoperLib] Kontraktion spawned! (" + list.size() + " blocks, id=" + id + ")"));
            } else {
                source.sendSystemMessage(Component.literal("§c[KoperLib] Spawn failed — check logs. (id=-2 = block limit, edit maxKontraktionBlocks in config)"));
            }
            return 1;
        }

        // raycast
        net.minecraft.world.phys.HitResult hit = player.pick(20.0, 1.0f, false);
        if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos clickedPos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
            if (!level.getBlockState(clickedPos).isAir() && com.koper.koper_lib.physics.KoperPhys.getBlockStateAt(level, clickedPos) == null) {
                List<BlockPos> list = List.of(clickedPos);
                source.sendSystemMessage(Component.literal("§a[KoperLib] Spawning kontraktion from block: " + clickedPos.toShortString()));
                long id = com.koper.koper_lib.physics.KoperPhys.makeKontraktion(level, list);
                if (id >= 0) {
                    source.sendSystemMessage(Component.literal("§a[KoperLib] Kontraktion spawned! (1 block, id=" + id + ")"));
                } else {
                    source.sendSystemMessage(Component.literal("§c[KoperLib] Spawn failed — check logs. (id=-2 = block limit, edit maxKontraktionBlocks in config)"));
                }
                return 1;
            }
        }

        source.sendSystemMessage(Component.literal("§c[KoperLib] No selection or looked-at block found! Use Selection Wand or look at a block."));
        return 0;
    }

    // spawn the official seat on a kontra and plop the caller onto it — sitting testable in isolation
    private static int executePhysicsSeat(CommandSourceStack source, long kontraId, float lx, float ly, float lz) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendSystemMessage(Component.literal("§c[KoperLib] Player only.")); return 0; }
        var seat = com.koper.koper_lib.physics.KoperPhys.spawnSeat((ServerLevel) player.level(), kontraId, lx, ly, lz);
        if (seat == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] spawnSeat failed — kontra " + kontraId + " not in this dimension?"));
            return 0;
        }
        boolean mounted = player.startRiding(seat, true, true); // force — sneak/canRide must not veto a test
        source.sendSystemMessage(Component.literal("§6[KoperLib] seat on kontra " + kontraId + " @ local "
            + lx + "," + ly + "," + lz + " → riding=" + mounted + " (shift to get off)"));
        return mounted ? 1 : 0;
    }

    // revolute joint between two kontras, anchors auto-halfway between their centers, axis = world X
    private static int executePhysicsJoint(CommandSourceStack source, long a, long b) {
        float[] pa = com.koper.koper_lib.physics.KoperPhys.getCachedPos(a);
        float[] pb = com.koper.koper_lib.physics.KoperPhys.getCachedPos(b);
        if (pa == null || pb == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] kontra " + (pa == null ? a : b) + " has no pose yet"));
            return 0;
        }
        // midpoint in each body's local frame; axis picked along the biggest separation axis
        float mx = (pa[0]+pb[0])*0.5f, my = (pa[1]+pb[1])*0.5f, mz = (pa[2]+pb[2])*0.5f;
        float dx = pb[0]-pa[0], dy = pb[1]-pa[1], dz = pb[2]-pa[2];
        float axx = 1f, axy = 0f, axz = 0f;
        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) { axx = 0f; axy = 1f; }
        else if (Math.abs(dz) >= Math.abs(dx)) { axx = 0f; axz = 1f; }
        long jid = com.koper.koper_lib.physics.KoperPhys.createRevoluteJoint(a, b,
            mx-pa[0], my-pa[1], mz-pa[2], mx-pb[0], my-pb[1], mz-pb[2], axx, axy, axz);
        if (jid <= 0) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] joint failed (different worlds? bad ids?)"));
            return 0;
        }
        source.sendSystemMessage(Component.literal("§6[KoperLib] joint §e" + jid + "§6 " + a + "↔" + b
            + " axis " + axx + "," + axy + "," + axz + " — try §e/koperlib physics motor " + jid + " 6 500"));
        return 1;
    }

    private static int executePhysicsMotor(CommandSourceStack source, long jointId, float vel, float torque) {
        boolean ok = com.koper.koper_lib.physics.KoperPhys.setJointMotor(jointId, vel, torque);
        source.sendSystemMessage(Component.literal(ok
            ? "§6[KoperLib] motor on joint " + jointId + ": vel=" + vel + " rad/s, max=" + torque
            : "§c[KoperLib] joint " + jointId + " unknown"));
        return ok ? 1 : 0;
    }

    private static int executePhysicsJointState(CommandSourceStack source, long jointId) {
        float[] st = com.koper.koper_lib.physics.KoperPhys.jointState(jointId);
        source.sendSystemMessage(Component.literal(st == null
            ? "§e[KoperLib] joint " + jointId + " — no state yet (async create pending or dead)"
            : "§6[KoperLib] joint " + jointId + " angle=" + String.format("%.3f", st[0])
                + " vel=" + String.format("%.3f", st[1])));
        return st != null ? 1 : 0;
    }

    private static int executePhysicsList(CommandSourceStack source) {
        var kontras = com.koper.koper_lib.physics.KoperPhys.all();
        // get caller's level key if available — show only that dimension
        String callerLevel = null;
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            callerLevel = com.koper.koper_lib.physics.KoperPhys.levelKey((net.minecraft.server.level.ServerLevel) p.level());
        }
        final String filterKey = callerLevel;
        var visible = kontras.entrySet().stream()
            .filter(e -> com.koper.koper_lib.physics.KoperPhys.getCachedPos(e.getKey()) != null) // skip orphaned
            .filter(e -> filterKey == null || e.getValue().levelKey().equals(filterKey))
            .toList();
        if (visible.isEmpty()) {
            int total = kontras.size();
            source.sendSystemMessage(Component.literal("§e[KoperLib] No kontraktions in this dimension" + (total > 0 ? " (§7" + total + " total across all dims§e)" : "") + "."));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§6[KoperLib] Kontraktions in " + (filterKey != null ? filterKey : "all") + " (" + visible.size() + "):"));
        for (var entry : visible) {
            long id = entry.getKey();
            var data = entry.getValue();
            float[] pos = com.koper.koper_lib.physics.KoperPhys.getCachedPos(id);
            String posStr = pos != null ? String.format("%.1f %.1f %.1f", pos[0], pos[1], pos[2]) : "?";
            source.sendSystemMessage(Component.literal("  §eID:§f" + id + " §7blk:§f" + data.blocks.size()
                + " §7aero:§f" + data.aeroMode().key() + " §7@§f" + posStr));
        }
        return 1;
    }

    // resolve a kontraktion by id, or message the source and return null
    private static com.koper.koper_lib.physics.KontraEntry requireKontra(CommandSourceStack source, long id) {
        var data = com.koper.koper_lib.physics.KoperPhys.all().get(id);
        if (data == null)
            source.sendSystemMessage(Component.literal("§c[KoperLib] Unknown kontraktion ID: " + id));
        return data;
    }

    private static int executePhysicsDestroy(CommandSourceStack source, long id) {
        if (requireKontra(source, id) == null) return 0;
        com.koper.koper_lib.physics.KoperPhys.destroyKontraktion(source.getServer(), id);
        source.sendSystemMessage(Component.literal("§a[KoperLib] Destroyed kontraktion ID: " + id));
        return 1;
    }

    private static int executePhysicsTp(CommandSourceStack source, long id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Player only."));
            return 0;
        }
        if (requireKontra(source, id) == null) return 0;
        float[] pos = com.koper.koper_lib.physics.KoperPhys.getCachedPos(id);
        if (pos == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Position unknown for kontraktion ID: " + id));
            return 0;
        }
        player.teleportTo(pos[0], pos[1] + 1.0, pos[2]);
        source.sendSystemMessage(Component.literal("§a[KoperLib] Teleported to kontraktion ID: " + id));
        return 1;
    }

    private static int executePhysicsSelfRight(CommandSourceStack source, long id) {
        if (requireKontra(source, id) == null) return 0;
        ServerLevel level = null;
        if (source.getEntity() instanceof ServerPlayer p)
            level = (ServerLevel) p.level();
        if (level == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Player only."));
            return 0;
        }
        com.koper.koper_lib.physics.KoperPhys.selfRight(level, id);
        source.sendSystemMessage(Component.literal("§a[KoperLib] SelfRight applied to kontraktion " + id));
        return 1;
    }

    private static int executePhysicsAeroDefault(CommandSourceStack source, String value) {
        var mode = com.koper.koper_lib.physics.AeroMode.parse(value);
        if (mode == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Aero mode: low, correct or extreme (1/2/3)."));
            return 0;
        }
        int moved = com.koper.koper_lib.physics.KoperPhys.setDefaultAeroMode(mode);
        source.sendSystemMessage(Component.literal("§a[KoperLib] Default aero → §f" + mode.key()
            + " §a(" + moved + " kontraktions follow it, ones set by id keep theirs)"));
        return 1;
    }

    private static int executePhysicsAero(CommandSourceStack source, long id, String value) {
        if (requireKontra(source, id) == null) return 0;
        if (value.equalsIgnoreCase("default")) {
            com.koper.koper_lib.physics.KoperPhys.setAeroMode(id, null);
            source.sendSystemMessage(Component.literal("§a[KoperLib] Kontraktion §f" + id + " §afollows the default aero again"));
            return 1;
        }
        var mode = com.koper.koper_lib.physics.AeroMode.parse(value);
        if (mode == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] Aero mode: low, correct or extreme (1/2/3)."));
            return 0;
        }
        com.koper.koper_lib.physics.KoperPhys.setAeroMode(id, mode);
        source.sendSystemMessage(Component.literal("§a[KoperLib] Kontraktion §f" + id + " §aaero → §f" + mode.key()));
        return 1;
    }

    private static int executePhysicsFaceCull(CommandSourceStack source, long id) {
        var data = requireKontra(source, id);
        if (data == null) return 0;
        // re-broadcast spawn payload — client will re-call setBlocks+markDirty on receive
        float[] pos = com.koper.koper_lib.physics.KoperPhys.getCachedPos(id);
        float[] rot = com.koper.koper_lib.physics.KoperPhys.getCachedRot(id);
        if (pos == null || rot == null) {
            source.sendSystemMessage(Component.literal("§c[KoperLib] No transform cached for ID: " + id));
            return 0;
        }
        var ra = data.renderArrays();
        var level = com.koper.koper_lib.physics.KoperPhys.findLevel(source.getServer(), data.levelKey());
        com.koper.koper_lib.physics.KhysicsNetworking.broadcastSpawn(source.getServer(),
            new com.koper.koper_lib.network.KenderSpawnPayload(id, pos, rot, ra.stateIds(), ra.offsets(), ra.locals(),
                level != null ? com.koper.koper_lib.physics.KoperPhys.blockEntityTags(data, level)
                    : new net.minecraft.nbt.CompoundTag[ra.stateIds().length]));
        source.sendSystemMessage(Component.literal(
            "§a[KoperLib] Forced face cull rebuild for kontraktion §f" + id + "§a (" + data.blocks.size() + " blocks)"));
        return 1;
    }

    // F7: land — convert kontraktion back to static blocks at current position
    private static int executePhysicsLand(CommandSourceStack source, long id) {
        var data = requireKontra(source, id);
        if (data == null) return 0;
        int blockCount = data.blocks.size();
        if (!com.koper.koper_lib.physics.KoperPhys.tryRestoreToWorld(source.getServer(), id)) {
            source.sendFailure(Component.literal("[KoperLib] Cannot land here. Align the body to the block grid and clear occupied destinations."));
            return 0;
        }
        source.sendSystemMessage(Component.literal(
            "§a[KoperLib] Landed §f" + blockCount + " blocks§a from kontraktion §f" + id));
        return 1;
    }

    // F7: break — drop all blocks as items, remove kontraktion
    private static int executePhysicsBreak(CommandSourceStack source, long id) {
        if (requireKontra(source, id) == null) return 0;
        int dropped = com.koper.koper_lib.physics.KoperPhys.breakToDrops(source.getServer(), id);
        source.sendSystemMessage(Component.literal(
            "§a[KoperLib] Broke kontraktion §f" + id + "§a — §f" + dropped + " block(s) dropped"));
        return 1;
    }

    // "why does this thing weigh that" used to be unanswerable in game: /koperlib physics list gives
    // a whole body's mass and nothing gives one block's. Reports the resolved properties AND which
    // rule produced them, because a pack entry and the blast-resistance guess look identical
    // from the outside and only one of them is something you chose.
    private static int executePhysicsProps(net.minecraft.commands.CommandSourceStack source) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(Component.literal("\u00a7c[KoperLib] /koperlib physics props needs a player"));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.level();
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 end = eye.add(
            player.getViewVector(1f).scale(Math.max(6.0, player.blockInteractionRange())));
        net.minecraft.world.phys.BlockHitResult hit = level.clip(
            new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            source.sendSystemMessage(Component.literal("\u00a7c[KoperLib] Look at a block first"));
            return 0;
        }

        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        // a kontraption projects its blocks into world space; the real chunk there is usually air
        net.minecraft.world.level.block.state.BlockState state =
            com.koper.koper_lib.physics.KoperPhys.getBlockStateAt(level, pos);
        String where = "kontraption";
        if (state == null || state.isAir()) {
            state = level.getBlockState(pos);
            where = "world";
        }
        if (state == null || state.isAir()) {
            source.sendSystemMessage(Component.literal("\u00a7c[KoperLib] Nothing solid there"));
            return 0;
        }

        var props = com.koper.koper_lib.physics.weight.KhysWeightBook.get(state);
        var origin = com.koper.koper_lib.physics.weight.KhysWeightBook.source(state);
        String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));

        source.sendSystemMessage(Component.literal("\u00a76[KoperLib] \u00a7f" + id + " \u00a77(" + where + ")"));
        source.sendSystemMessage(Component.literal(String.format(
            "  \u00a77mass \u00a7f%.3f\u00a77  friction \u00a7f%.2f\u00a77  restitution \u00a7f%.2f\u00a77  fragility \u00a7f%.0f",
            props.mass(), props.friction(), props.restitution(), props.fragilityImpulse())));
        source.sendSystemMessage(Component.literal(String.format(
            "  \u00a77buoyancy \u00a7f%.2f\u00a77  drag \u00a7f%.2f\u00a77  lift \u00a7f%.2f\u00a77  balloon \u00a7f%.2f\u00a77  aero \u00a7f%s\u00a77  wheel \u00a7f%s",
            props.buoyancyVolume(), props.dragCoeff(), props.liftCoeff(), props.balloonLift(),
            props.aero(), props.wheel())));
        source.sendSystemMessage(Component.literal(
            "  \u00a77source: \u00a7e" + origin.rule() + "\u00a77 (" + origin.detail() + ")"));
        // mass is a relative scale, not kilograms - the Rust side says so explicitly
        source.sendSystemMessage(Component.literal(
            "  \u00a778mass is a relative scale, not kg; a plain block sits near 0.5-1.0"));
        return 1;
    }


    // a joint whose two ends do not name the same world point is a constraint the solver can never
    // satisfy, so it fights it every tick and the build wanders off or takes flight. anchorError is
    // the one number that says so, and until now nothing in game could show it.
    private static int executePhysicsJoints(net.minecraft.commands.CommandSourceStack source) {
        var specs = com.koper.koper_lib.physics.KoperPhys.jointSpecs();
        if (specs.isEmpty()) {
            source.sendSystemMessage(Component.literal("\u00a76[KoperLib] no live joints"));
            return 1;
        }
        source.sendSystemMessage(Component.literal(
            "\u00a76[KoperLib] " + specs.size() + " joint(s) \u00a77(error = how far the two ends disagree)"));
        int loud = 0;
        for (var entry : specs.entrySet()) {
            long id = entry.getKey();
            var spec = entry.getValue();
            float error = com.koper.koper_lib.physics.KoperPhys.jointAnchorError(id);
            // 0.02 is what the gametests treat as "held together"
            String colour = error > 0.2f ? "\u00a7c" : error > 0.02f ? "\u00a7e" : "\u00a7a";
            if (error > 0.02f) loud++;
            source.sendSystemMessage(Component.literal(String.format(
                "  \u00a77#%d %s %d\u00a77\u2194\u00a7f%d\u00a77  error %s%.4f\u00a77  motor %.2f/%.0f",
                id, spec.prismatic() ? "prism" : "rev", spec.a(), spec.b(),
                colour, error, spec.motorVel(), spec.motorForce())));
            // the error above only says the two anchors agree. it says NOTHING about whether the
            // anchor sits anywhere near the blocks it is supposed to hold — a joint can be perfectly
            // satisfied while the build hangs two blocks off it, which is what "error 0 but visibly
            // split" looks like. this is that missing number.
            koperlib$anchorGap(source, "A", spec.a(), spec.anchorA());
            if (spec.b() != 0L) koperlib$anchorGap(source, "B", spec.b(), spec.anchorB());
        }
        if (loud > 0)
            source.sendSystemMessage(Component.literal(
                "\u00a7c  " + loud + " joint(s) are being fought by the solver \u2014 that is what throws builds around"));
        return 1;
    }


    // distance from a joint anchor to the closest block of the body it belongs to
    private static void koperlib$anchorGap(net.minecraft.commands.CommandSourceStack source,
                                           String side, long body, float[] anchor) {
        var entry = com.koper.koper_lib.physics.KoperPhys.all().get(body);
        if (entry == null || entry.blockOffsets.isEmpty()) return;
        double best = Double.MAX_VALUE;
        for (float[] off : entry.blockOffsets.values()) {
            double dx = off[0] - anchor[0], dy = off[1] - anchor[1], dz = off[2] - anchor[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < best) best = d;
        }
        // a block is 1 wide, so an anchor on its own build sits under ~0.9 from some block centre
        String colour = best > 1.5 ? "\u00a7c" : best > 0.95 ? "\u00a7e" : "\u00a7a";
        source.sendSystemMessage(Component.literal(String.format(
            "     \u00a77%s(%d) anchor is %s%.3f\u00a77 from its nearest block (%d blocks)",
            side, body, colour, best, entry.blockOffsets.size())));
    }

}
