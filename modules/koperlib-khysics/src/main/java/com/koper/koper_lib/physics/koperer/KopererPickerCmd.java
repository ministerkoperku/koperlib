package com.koper.koper_lib.physics.koperer;

import com.koper.koper_lib.api.core.KoperCommands;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

// /koperlib engine — see which engine is running, or move to the other one
public final class KopererPickerCmd {

    private KopererPickerCmd() {}

    public static void install() {
        KoperCommands.register("engine", root -> root.then(Commands.literal("engine")
            .executes(KopererPickerCmd::list)
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests((c, b) -> {
                    KopererPicker.names().forEach(b::suggest);
                    return b.buildFuture();
                })
                .executes(c -> swap(c, StringArgumentType.getString(c, "name"))))));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        PhysKoperer current = KopererPicker.now();
        c.getSource().sendSystemMessage(Component.literal("physics backend: " + current.name())
            .withStyle(ChatFormatting.AQUA));
        // listing must not drag an engine's native into the process just to print its name
        for (String key : KopererPicker.names()) {
            boolean on = key.equals(current.name());
            String note = on ? "" : KopererPicker.isUp(key) ? "  (loaded)" : "  (not loaded)";
            c.getSource().sendSystemMessage(Component.literal((on ? "  * " : "    ") + key + note)
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int swap(CommandContext<CommandSourceStack> c, String name) {
        PhysKoperer found = KopererPicker.find(name);
        if (found == null) {
            c.getSource().sendFailure(Component.literal("no engine called " + name));
            return 0;
        }
        if (!found.isLoaded()) {
            c.getSource().sendFailure(Component.literal(name + " has no native on this machine"));
            return 0;
        }
        if (!KopererPicker.swap(name)) {
            c.getSource().sendFailure(Component.literal("could not switch to " + name));
            return 0;
        }
        c.getSource().sendSystemMessage(Component.literal("physics backend -> " + name)
            .withStyle(ChatFormatting.GREEN));
        // worlds that already exist keep the engine that made them, ids from one engine mean
        // nothing to the other. only new worlds get this one
        c.getSource().sendSystemMessage(Component.literal("applies to new worlds — reload to move what is already running")
            .withStyle(ChatFormatting.YELLOW));
        return 1;
    }
}
