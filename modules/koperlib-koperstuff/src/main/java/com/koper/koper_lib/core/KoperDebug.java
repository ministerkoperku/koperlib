package com.koper.koper_lib.core;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class KoperDebug {

    private KoperDebug() {}

    public static boolean active() {
        return KoperLibConfig.get().debugMode;
    }

    public static void dumpToLog() {
        for (String line : buildLines()) KoperLib.LOGGER.info("[KoperDebug] {}", line);
    }

    public static void dumpToChat(CommandSourceStack source) {
        for (String line : buildLines()) {
            source.sendSystemMessage(Component.literal(line));
        }
    }

    public static List<String> buildLines() {
        List<String> lines = new ArrayList<>();
        lines.add("KoperLib debug");
        lines.add(com.koper.koper_lib.kender.KenderBackend.describe());
        lines.addAll(KoperRuntime.snapshot());
        lines.add("fullpacks=" + FullPackLoader.getLoadedPacks().size()
                + " enabled=" + FullPackLoader.getEnabledNamespaces().size());
        try {
            lines.add("kontras=" + KoperPhys.all().size() + " paused=" + KoperPhys.isPaused());
        } catch (Throwable t) {
            lines.add("kontras=ERR " + t.getMessage());
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        lines.add("heapMB=" + used + "/" + (rt.maxMemory() / (1024 * 1024)));
        return lines;
    }
}
