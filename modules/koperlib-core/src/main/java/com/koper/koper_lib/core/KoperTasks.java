package com.koper.koper_lib.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class KoperTasks {
    private static final Queue<Task> TASKS = new ConcurrentLinkedQueue<>();
    private static boolean registered;

    private KoperTasks() {}

    public static void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(KoperTasks::tick);
    }

    public static void later(MinecraftServer server, int ticks, Runnable runnable) {
        if (server == null || runnable == null) return;
        TASKS.add(new Task(server.getTickCount() + Math.max(1, ticks), runnable));
    }

    private static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        int guard = TASKS.size();
        while (guard-- > 0) {
            Task task = TASKS.poll();
            if (task == null) return;
            if (task.tick <= now) {
                try {
                    task.runnable.run();
                } catch (Exception e) {
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Tasks] task failed: {}", e.getMessage());
                }
            } else {
                TASKS.add(task);
            }
        }
    }

    private record Task(int tick, Runnable runnable) {}
}
