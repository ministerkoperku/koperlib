package com.koper.koper_lib.loader;

import com.koper.koper_lib.api.core.KoperCommands;
import com.koper.koper_lib.api.core.KoperConfigs;
import com.koper.koper_lib.api.core.KoperModules;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Production Fullpack commands. Cross-module torture/bench commands live in Koperstuff. */
public final class FullpackCommands {
    private FullpackCommands() {}

    public static void register() {
        KoperCommands.register("fullpack", root -> {
            root.then(Commands.literal("reload").executes(context -> {
                context.getSource().sendSystemMessage(Component.literal("§e[KoperLib] Reloading Fullpacks..."));
                FullpackReloader.reload(context.getSource().getServer());
                return 1;
            }));
            root.then(Commands.literal("status").executes(context -> {
                var modules = KoperModules.snapshot();
                String line = modules.stream()
                    .map(module -> module.id() + "=" + module.state().name().toLowerCase())
                    .collect(java.util.stream.Collectors.joining(", "));
                context.getSource().sendSystemMessage(Component.literal(
                    "§6[KoperLib] modules: §f" + line + " §7| fullpacks=" + FullPackLoader.getLoadedPacks().size()));
                return 1;
            }));

            var packs = Commands.literal("fullpack")
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("enable")
                    .then(Commands.argument("pack", StringArgumentType.word()).executes(context ->
                        set(context.getSource(), StringArgumentType.getString(context, "pack"), true))))
                .then(Commands.literal("disable")
                    .then(Commands.argument("pack", StringArgumentType.word()).executes(context ->
                        set(context.getSource(), StringArgumentType.getString(context, "pack"), false))))
                // the higher a pack, the more it owns when packs overlap (three bedrock packs redrawing the player)
                .then(Commands.literal("priority")
                    .then(Commands.argument("pack", StringArgumentType.word())
                        .then(Commands.argument("where", StringArgumentType.word())
                            .suggests((c, b) -> { for (String w : new String[] {"top", "up", "down", "bottom"}) b.suggest(w); return b.buildFuture(); })
                            .executes(context -> priority(context.getSource(),
                                StringArgumentType.getString(context, "pack"), StringArgumentType.getString(context, "where"))))));
            root.then(packs);

            root.then(Commands.literal("config")
                .executes(context -> {
                    context.getSource().sendSystemMessage(Component.literal(
                        "§6[KoperLib] config sections: §f" + String.join(", ", KoperConfigs.ids())));
                    return 1;
                })
                .then(Commands.literal("reload").executes(context -> {
                    KoperConfigs.reloadAll();
                    com.koper.koper_lib.core.KoperRuntime.applyConfig();
                    context.getSource().sendSystemMessage(Component.literal("§a[KoperLib] Config reloaded."));
                    return 1;
                })));

            root.then(Commands.literal("calls").executes(context -> {
                var ids = new java.util.ArrayList<>(com.koper.koper_lib.api.KoperCalls.ids());
                java.util.Collections.sort(ids);
                context.getSource().sendSystemMessage(Component.literal("§6[KoperCalls] §f" + String.join(", ", ids)));
                return 1;
            }));
            root.then(Commands.literal("itemtypes").executes(context -> {
                var ids = new java.util.ArrayList<>(com.koper.koper_lib.api.KoperItemTypes.ids());
                java.util.Collections.sort(ids);
                context.getSource().sendSystemMessage(Component.literal("§6[KoperItemTypes] §f" + String.join(", ", ids)));
                return 1;
            }));

            root.then(Commands.literal("give")
                .then(Commands.argument("item", StringArgumentType.string())
                    .executes(context -> give(context.getSource(), StringArgumentType.getString(context, "item"), 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(context -> give(context.getSource(), StringArgumentType.getString(context, "item"),
                            IntegerArgumentType.getInteger(context, "count"))))));

            // this used to be open-with-no-suggestions and nothing else, so without koperstuff
            // loaded you had to remember every page id by heart. same subtree as koperstuff now
            root.then(Commands.literal("gui")
                .executes(context -> guiList(context.getSource()))
                .then(Commands.literal("list").executes(context -> guiList(context.getSource())))
                .then(Commands.literal("open").then(Commands.argument("id", StringArgumentType.greedyString())
                    .suggests((c, b) -> { com.koper.koper_lib.kui.KuiBook.ids().forEach(b::suggest); return b.buildFuture(); })
                    .executes(context -> {
                        var player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        String id = StringArgumentType.getString(context, "id").trim();
                        if (com.koper.koper_lib.kui.KuiBook.get(id) == null) {
                            context.getSource().sendFailure(Component.literal(
                                "§c[Kui] no page '" + id + "'. /koperlib gui list shows what there is."));
                            return 0;
                        }
                        com.koper.koper_lib.kui.KuiOpen.open(player, id);
                        return 1;
                    })))
                .then(Commands.literal("bake").executes(context -> {
                    com.koper.koper_lib.kui.KuiBaker.syncAll(true);
                    context.getSource().sendSystemMessage(Component.literal(
                        "§a[Kui] Force-baked all gui textures. See pack/textures/gui/"));
                    return 1;
                })));

            root.then(Commands.literal("quest")
                .executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    com.koper.koper_lib.quest.QuestPages.open(player);
                    return 1;
                })
                .then(Commands.literal("book").executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    com.koper.koper_lib.quest.QuestPages.open(player);
                    return 1;
                }))
                .then(Commands.literal("dialog")
                    .executes(context -> {
                        var player = context.getSource().getPlayer();
                        if (player == null) return 0;
                        com.koper.koper_lib.quest.DialogMaker.open(player);
                        return 1;
                    })
                    // talk to a conversation without going to find the mob that owns it
                    .then(Commands.literal("play")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                            .suggests((c, b) -> { com.koper.koper_lib.quest.DialogBook.ids().forEach(b::suggest); return b.buildFuture(); })
                            .executes(context -> {
                                var player = context.getSource().getPlayer();
                                if (player == null) return 0;
                                String id = StringArgumentType.getString(context, "id").trim();
                                if (!com.koper.koper_lib.quest.DialogRunner.open(player, id, "test")) {
                                    context.getSource().sendFailure(Component.literal(
                                        "§c[Dialog] no dialog '" + id + "'"));
                                    return 0;
                                }
                                return 1;
                            })))
                    .then(Commands.literal("list").executes(context -> {
                        var ids = com.koper.koper_lib.quest.DialogBook.ids();
                        context.getSource().sendSystemMessage(Component.literal(
                            ids.isEmpty() ? "§e[Dialog] none loaded" : "§6[Dialog] §f" + String.join(", ", ids)));
                        return 1;
                    })))
                .then(Commands.literal("make").executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    com.koper.koper_lib.quest.QuestMaker.open(player);
                    return 1;
                }))
                .then(Commands.literal("list").executes(context -> questList(context.getSource())))
                .then(Commands.literal("start")
                    .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((c, b) -> { com.koper.koper_lib.quest.QuestBook.ids().forEach(b::suggest); return b.buildFuture(); })
                        .executes(context -> {
                            var player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            String id = StringArgumentType.getString(context, "id").trim();
                            if (com.koper.koper_lib.quest.QuestBook.get(id) == null) {
                                context.getSource().sendFailure(Component.literal("§c[Quest] no quest '" + id + "'"));
                                return 0;
                            }
                            if (!com.koper.koper_lib.quest.QuestChase.start(player, id)) {
                                context.getSource().sendFailure(Component.literal(
                                    "§c[Quest] can't start that. already going, already done, or something it needs isn't."));
                                return 0;
                            }
                            return 1;
                        })))
                .then(Commands.literal("reset")
                    .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((c, b) -> { com.koper.koper_lib.quest.QuestBook.ids().forEach(b::suggest); return b.buildFuture(); })
                        .executes(context -> {
                            var player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            String id = StringArgumentType.getString(context, "id").trim();
                            com.koper.koper_lib.quest.QuestChase.reset(player, id);
                            context.getSource().sendSystemMessage(Component.literal("§7[Quest] " + id + " wiped for you."));
                            return 1;
                        }))));

            root.then(Commands.literal("snitch").executes(context -> {
                var player = context.getSource().getPlayer();
                if (player == null) return 0;
                boolean on = com.koper.koper_lib.scripting.KoperSnitch.nosy(player);
                context.getSource().sendSystemMessage(Component.literal(on
                    ? "§a[Snitch] on. every player:* event lands in chat, heartbeat muted."
                    : "§7[Snitch] off."));
                return 1;
            }));
        });
    }

    private static int questList(net.minecraft.commands.CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) return 0;
        if (com.koper.koper_lib.quest.QuestBook.ids().isEmpty()) {
            source.sendSystemMessage(Component.literal(
                "§e[Quest] Nothing loaded. Drop one in pack/quests/ and §e/koperlib reload"));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§6[Quest] Journal:"));
        for (String row : com.koper.koper_lib.quest.QuestChase.journal(player))
            source.sendSystemMessage(Component.literal("  " + row));
        return 1;
    }

    private static int guiList(net.minecraft.commands.CommandSourceStack source) {
        var ids = com.koper.koper_lib.kui.KuiBook.ids();
        if (ids.isEmpty()) {
            source.sendSystemMessage(Component.literal(
                "§e[Kui] No guis registered. Drop one in pack/gui/ and §e/koperlib reload"));
            return 1;
        }
        source.sendSystemMessage(Component.literal("§6[Kui] Guis (" + ids.size() + "):"));
        for (var page : com.koper.koper_lib.kui.KuiBook.all())
            source.sendSystemMessage(Component.literal("  §f" + page.id + " §8" + page.mode + " " + page.w + "x" + page.h));
        return 1;
    }

    private static int list(net.minecraft.commands.CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("§6[KoperLib] Fullpacks, highest priority first:"));
        var all = FullPackLoader.getAllPacks();
        int n = 1;
        for (String folder : FullPackLoader.ordered()) {
            var meta = all.get(folder);
            source.sendSystemMessage(Component.literal("  §7" + n++ + ". " + (FullPackLoader.isEnabled(folder) ? "§a[ON] " : "§c[OFF] ") + "§f" + folder
                + (meta == null ? "" : " §7v" + meta.version)));
        }
        return 1;
    }

    private static int priority(net.minecraft.commands.CommandSourceStack source, String pack, String where) {
        if (!java.util.List.of("top", "up", "down", "bottom").contains(where)) {
            source.sendFailure(Component.literal("§c[KoperLib] where is top, up, down or bottom"));
            return 0;
        }
        if (!FullPackLoader.move(pack, where)) {
            source.sendFailure(Component.literal("§c[KoperLib] Unknown fullpack: " + pack));
            return 0;
        }
        list(source);
        source.sendSystemMessage(Component.literal("§7restart or /koperlib reload to apply"));
        return 1;
    }

    private static int set(net.minecraft.commands.CommandSourceStack source, String pack, boolean enabled) {
        if (!FullPackLoader.getAllPacks().containsKey(pack)) {
            source.sendFailure(Component.literal("§c[KoperLib] Unknown fullpack: " + pack));
            return 0;
        }
        FullPackLoader.setDisabled(pack, !enabled);
        source.sendSystemMessage(Component.literal("§6[KoperLib] " + pack + (enabled ? " §aenabled" : " §cdisabled")
            + "§7; restart or reload content to apply."));
        return 1;
    }

    private static int give(net.minecraft.commands.CommandSourceStack source, String rawId, int count) {
        var player = source.getPlayer();
        if (player == null) return 0;
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(rawId);
        var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("§c[KoperLib] Unknown item: " + rawId));
            return 0;
        }
        var stack = new net.minecraft.world.item.ItemStack(item, count);
        if (!player.getInventory().add(stack)) com.koper.koper_lib.core.KoperWyrzucacz.drop(player, stack, false);
        return 1;
    }
}
