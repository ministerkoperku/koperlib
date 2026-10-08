package com.koper.koper_lib.api.core;

import com.koper.koper_lib.coremod.KoperCore;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

// core owns /koperlib, every mod only bolts its own branches onto it
public final class KoperCommands {
    private static final Map<String, Consumer<LiteralArgumentBuilder<CommandSourceStack>>> PARTS = new LinkedHashMap<>();
    private static boolean initialized;

    private KoperCommands() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("koperlib").requires(CommandSourceStack::isPlayer);
            Map<String, Consumer<LiteralArgumentBuilder<CommandSourceStack>>> snapshot;
            synchronized (KoperCommands.class) {
                snapshot = new LinkedHashMap<>(PARTS);
            }
            snapshot.forEach((id, contributor) -> {
                try {
                    contributor.accept(root);
                } catch (Throwable error) {
                    KoperCore.LOGGER.error("[Commands] module '{}' failed to build its command tree", id, error);
                }
            });
            dispatcher.register(root);
        });
    }

    public static synchronized void register(
        String moduleId,
        Consumer<LiteralArgumentBuilder<CommandSourceStack>> contributor
    ) {
        if (moduleId == null || moduleId.isBlank() || contributor == null)
            throw new IllegalArgumentException("command contributor needs an id and builder");
        if (PARTS.putIfAbsent(moduleId, contributor) != null)
            throw new IllegalStateException("command contributor already registered: " + moduleId);
    }
}
