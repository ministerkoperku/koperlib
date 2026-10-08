package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.network.KfxImpactPayload;
import com.koper.koper_lib.network.KfxMotionBatchPayload;
import com.koper.koper_lib.network.KfxStopPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class KfxControllerRuntime {
    public static final int MAX_CONTROLLERS = KfxControllerBook.MAX_CONTROLLERS;
    private static final Logger LOG = LoggerFactory.getLogger("KFX/Controller");
    private static final Map<ServerLevel, KfxControllerBook> BOOKS = new WeakHashMap<>();
    private static final CopyOnWriteArrayList<KfxEventListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean initialized;

    private KfxControllerRuntime() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(KfxControllerRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    public static synchronized void launch(ServerLevel level, KfxController controller) {
        int active = BOOKS.values().stream().mapToInt(KfxControllerBook::size).sum();
        if (active >= MAX_CONTROLLERS) {
            throw new IllegalStateException("KFX global controller hard cap reached: " + MAX_CONTROLLERS);
        }
        BOOKS.computeIfAbsent(level, ignored -> new KfxControllerBook()).add(controller);
        com.koper.koper_lib.kfx.KfxDiagnostics.controller(controller);
    }

    public static synchronized boolean stop(ServerLevel level, long handle) {
        KfxControllerBook book = BOOKS.get(level);
        return book != null && book.stop(handle);
    }

    // live controller for a handle, or null. read-only use: position, velocity, age, state
    public static synchronized KfxController find(ServerLevel level, long handle) {
        KfxControllerBook book = BOOKS.get(level);
        return book == null ? null : book.get(handle);
    }

    public static synchronized boolean steer(ServerLevel level, long handle, net.minecraft.world.phys.Vec3 velocity) {
        KfxControllerBook book = BOOKS.get(level);
        if (book == null) return false;
        KfxController controller = book.get(handle);
        return controller != null && controller.steer(velocity);
    }

    public static void addEventListener(KfxEventListener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeEventListener(KfxEventListener listener) {
        LISTENERS.remove(listener);
    }

    static void dispatch(ServerLevel level, KfxImpact impact) {
        for (KfxEventListener listener : LISTENERS) {
            try {
                listener.onImpact(level, impact);
            } catch (Throwable error) {
                LOG.error("KFX impact listener crashed for handle {}", impact.handle(), error);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) tick(level);
    }

    private static void tick(ServerLevel level) {
        KfxControllerBook book;
        synchronized (KfxControllerRuntime.class) {
            book = BOOKS.get(level);
        }
        if (book == null) return;
        var budget = new KfxMinecraftSensor.Budget(KfxMinecraftSensor.MAX_BLOCK_CHECKS_PER_LEVEL_TICK);
        KfxControllerBook.Frame frame = book.tick(controller -> new KfxMinecraftSensor(level, controller, budget));
        frame.motions().forEach(step -> {
            KfxController controller = book.get(step.handle());
            if (controller != null) com.koper.koper_lib.kfx.KfxDiagnostics.controller(controller);
        });
        if (!frame.motions().isEmpty()) {
            var motions = frame.motions().stream().map(motion -> new KfxMotionBatchPayload.Motion(
                motion.handle(),
                (float)motion.previous().x, (float)motion.previous().y, (float)motion.previous().z,
                (float)motion.position().x, (float)motion.position().y, (float)motion.position().z)).toList();
            KoperNetwork.broadcast(level, new KfxMotionBatchPayload(motions));
        }
        for (KfxImpact impact : frame.impacts()) {
            KoperNetwork.broadcast(level, KfxImpactPayload.from(impact));
            dispatch(level, impact);
        }
        for (KfxController.Step motion : frame.motions()) {
            if (motion.state() == KfxController.State.STOPPED) {
                KoperNetwork.broadcast(level, new KfxStopPayload(motion.handle()));
            }
        }
        if (book.isEmpty()) {
            synchronized (KfxControllerRuntime.class) {
                BOOKS.remove(level);
            }
        }
    }

    public static synchronized void clear() {
        BOOKS.clear();
    }
}
