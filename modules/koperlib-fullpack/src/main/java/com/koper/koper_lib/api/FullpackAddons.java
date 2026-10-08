package com.koper.koper_lib.api;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.List;

/**
 * Extension surface used by feature mods and third-party addons to teach Fullpack new content.
 * Implementations stay in their owning mods; Fullpack stores only callbacks.
 */
public final class FullpackAddons {
    private static final Map<String, TypedHandler> TYPES = new LinkedHashMap<>();
    private static final Map<String, BiConsumer<String, File>> MODEL_FILES = new LinkedHashMap<>();
    private static final Map<String, BiConsumer<String, File>> ANIMATION_FILES = new LinkedHashMap<>();
    private static final Map<String, BiFunction<Identifier, JsonObject, Identifier>> BLOCK_VISUALS = new LinkedHashMap<>();
    private static final Map<String, Runnable> RELOAD_HOOKS = new LinkedHashMap<>();
    private static final Map<String, Runnable> AFTER_RELOAD_HOOKS = new LinkedHashMap<>();
    private static Effects effects;
    private static Physics physics;
    private static Geo geo;
    private static boolean reloadInProgress;
    private static boolean reloadHealthy = true;

    private FullpackAddons() {}

    public static synchronized void contentTypes(String owner, Collection<String> types,
                                                 BiConsumer<JsonObject, String> handler) {
        for (String type : types) {
            String key = type.toLowerCase();
            if (TYPES.putIfAbsent(key, new TypedHandler(owner, handler)) != null)
                throw new IllegalStateException("Fullpack content type already registered: " + key);
        }
    }

    public static synchronized void modelFiles(String owner, BiConsumer<String, File> handler) {
        if (MODEL_FILES.putIfAbsent(owner, handler) != null)
            throw new IllegalStateException("Fullpack model-file addon already registered: " + owner);
    }

    public static synchronized void animationFiles(String owner, BiConsumer<String, File> handler) {
        if (ANIMATION_FILES.putIfAbsent(owner, handler) != null)
            throw new IllegalStateException("Fullpack animation-file addon already registered: " + owner);
    }

    public static synchronized void blockVisuals(String owner,
            BiFunction<Identifier, JsonObject, Identifier> handler) {
        if (BLOCK_VISUALS.putIfAbsent(owner, handler) != null)
            throw new IllegalStateException("Fullpack block-visual addon already registered: " + owner);
    }

    public static synchronized void reloadHook(String owner, Runnable hook) {
        if (RELOAD_HOOKS.putIfAbsent(owner, hook) != null)
            throw new IllegalStateException("Fullpack reload hook already registered: " + owner);
    }

    public static synchronized void afterReloadHook(String owner, Runnable hook) {
        if (AFTER_RELOAD_HOOKS.putIfAbsent(owner, hook) != null)
            throw new IllegalStateException("Fullpack after-reload hook already registered: " + owner);
    }

    public static synchronized void effects(String owner, Effects provider) {
        if (effects != null) throw new IllegalStateException("Fullpack effects provider already registered");
        effects = java.util.Objects.requireNonNull(provider, owner);
    }

    public static synchronized void physics(String owner, Physics provider) {
        if (physics != null) throw new IllegalStateException("Fullpack physics provider already registered");
        physics = java.util.Objects.requireNonNull(provider, owner);
    }

    public static synchronized void geo(String owner, Geo provider) {
        if (geo != null) throw new IllegalStateException("Fullpack geo provider already registered");
        geo = java.util.Objects.requireNonNull(provider, owner);
    }

    public static long spawnEffect(ServerLevel level, String effectId,
                                   double sx, double sy, double sz, double ex, double ey, double ez) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        return current == null ? 0L : current.spawn(level, effectId, sx, sy, sz, ex, ey, ez);
    }

    public static void updateEffect(ServerLevel level, long id,
                                    double sx, double sy, double sz, double ex, double ey, double ez) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.update(level, id, sx, sy, sz, ex, ey, ez);
    }

    public static void stopEffect(MinecraftServer server, long id) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.stop(server, id);
    }

    public static void attachEffect(ServerLevel level, long id, Entity entity,
                                    double ox, double oy, double oz,
                                    double ex, double ey, double ez, boolean endRelative) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.attach(level, id, entity, ox, oy, oz, ex, ey, ez, endRelative);
    }

    public static long runEffectProgram(ServerLevel level, String json,
                                        double sx, double sy, double sz, double ex, double ey, double ez) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        return current == null ? 0L : current.program(level, json, sx, sy, sz, ex, ey, ez);
    }

    public static void declareEffectGraph(String graphJson) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.declareGraph(graphJson);
    }

    public static long playEffectGraph(MinecraftServer server, long id, String graphJson, String optionsJson) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        return current == null ? 0L : current.playGraph(server, id, graphJson, optionsJson);
    }

    public static void setEffectHandle(MinecraftServer server, long id, String name, String valueJson) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.setHandle(server, id, name, valueJson);
    }

    public static void reanchorEffectHandle(MinecraftServer server, long id, String startJson, String endJson) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.reanchorHandle(server, id, startJson, endJson);
    }

    public static void detachEffectHandle(MinecraftServer server, long id) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.detachHandle(server, id);
    }

    public static void signalEffectHandle(MinecraftServer server, long id, String name, String dataJson) {
        Effects current;
        synchronized (FullpackAddons.class) { current = effects; }
        if (current != null) current.signalHandle(server, id, name, dataJson);
    }

    public static void physicsForce(long id, float x, float y, float z) {
        Physics current;
        synchronized (FullpackAddons.class) { current = physics; }
        if (current != null) current.force(id, x, y, z);
    }

    public static void physicsImpulse(long id, float x, float y, float z) {
        Physics current;
        synchronized (FullpackAddons.class) { current = physics; }
        if (current != null) current.impulse(id, x, y, z);
    }

    public static void physicsSelfRight(long id) {
        Physics current;
        synchronized (FullpackAddons.class) { current = physics; }
        if (current != null) current.selfRight(id);
    }

    public static void physicsDestroy(MinecraftServer server, long id) {
        Physics current;
        synchronized (FullpackAddons.class) { current = physics; }
        if (current != null) current.destroy(server, id);
    }

    public static void physicsRestore(MinecraftServer server, long id) {
        Physics current;
        synchronized (FullpackAddons.class) { current = physics; }
        if (current != null) current.restore(server, id);
    }

    public static List<String> entityBones(Entity entity) {
        Geo current;
        synchronized (FullpackAddons.class) { current = geo; }
        return current == null ? List.of() : List.copyOf(current.entityBones(entity));
    }

    public static void playEntityAnimation(Entity entity, String clip, int holdTicks) {
        Geo current;
        synchronized (FullpackAddons.class) { current = geo; }
        if (current != null) current.playAnimation(entity, clip, holdTicks);
    }

    public static boolean dispatch(String type, JsonObject json, String source) {
        TypedHandler entry;
        synchronized (FullpackAddons.class) { entry = TYPES.get(type.toLowerCase()); }
        if (entry == null) return false;
        entry.handler().accept(json, source);
        return true;
    }

    public static void modelFile(String name, File file) {
        Map<String, BiConsumer<String, File>> snapshot;
        synchronized (FullpackAddons.class) { snapshot = Map.copyOf(MODEL_FILES); }
        snapshot.values().forEach(handler -> handler.accept(name, file));
    }

    public static void animationFile(String name, File file) {
        Map<String, BiConsumer<String, File>> snapshot;
        synchronized (FullpackAddons.class) { snapshot = Map.copyOf(ANIMATION_FILES); }
        snapshot.values().forEach(handler -> handler.accept(name, file));
    }

    /** Returns a texture for the generated invisible vanilla model, or null when unclaimed. */
    public static Identifier blockVisual(Identifier id, JsonObject definition) {
        Map<String, BiFunction<Identifier, JsonObject, Identifier>> snapshot;
        synchronized (FullpackAddons.class) { snapshot = Map.copyOf(BLOCK_VISUALS); }
        for (var handler : snapshot.values()) {
            Identifier texture = handler.apply(id, definition);
            if (texture != null) return texture;
        }
        return null;
    }

    public static synchronized boolean hasModelFiles() { return !MODEL_FILES.isEmpty(); }
    public static synchronized boolean hasAnimationFiles() { return !ANIMATION_FILES.isEmpty(); }

    public static void prepareReload() {
        Map<String, Runnable> snapshot;
        synchronized (FullpackAddons.class) {
            reloadInProgress = true;
            reloadHealthy = true;
            snapshot = Map.copyOf(RELOAD_HOOKS);
        }
        snapshot.forEach((owner, hook) -> {
            try { hook.run(); }
            catch (Throwable error) {
                markReloadFailure();
                com.koper.koper_lib.KoperLib.LOGGER.error("[Fullpack/addon] reload hook '{}' failed", owner, error);
            }
        });
    }

    /** Opens an initial/alternate content scan when it did not pass through prepareReload(). */
    public static void beginContentScan() {
        Map<String, Runnable> snapshot;
        synchronized (FullpackAddons.class) {
            if (reloadInProgress) return;
            reloadInProgress = true;
            reloadHealthy = true;
            snapshot = Map.copyOf(RELOAD_HOOKS);
        }
        snapshot.forEach((owner, hook) -> {
            try { hook.run(); }
            catch (Throwable error) {
                markReloadFailure();
                com.koper.koper_lib.KoperLib.LOGGER.error("[Fullpack/addon] initial-scan hook '{}' failed", owner, error);
            }
        });
    }

    public static synchronized void markReloadFailure() {
        reloadHealthy = false;
    }

    public static synchronized boolean reloadHealthy() {
        return reloadHealthy;
    }

    public static void finishReload() {
        Map<String, Runnable> snapshot;
        synchronized (FullpackAddons.class) {
            if (!reloadInProgress) return;
            snapshot = Map.copyOf(AFTER_RELOAD_HOOKS);
        }
        try {
            snapshot.forEach((owner, hook) -> {
                try { hook.run(); }
                catch (Throwable error) {
                    com.koper.koper_lib.KoperLib.LOGGER.error("[Fullpack/addon] after-reload hook '{}' failed", owner, error);
                }
            });
        } finally {
            synchronized (FullpackAddons.class) { reloadInProgress = false; }
        }
    }

    public static void abortReload() {
        markReloadFailure();
        finishReload();
    }

    private record TypedHandler(String owner, BiConsumer<JsonObject, String> handler) {}

    public interface Effects {
        long spawn(ServerLevel level, String effectId,
                   double sx, double sy, double sz, double ex, double ey, double ez);
        void update(ServerLevel level, long id,
                    double sx, double sy, double sz, double ex, double ey, double ez);
        void stop(MinecraftServer server, long id);
        void attach(ServerLevel level, long id, Entity entity,
                    double ox, double oy, double oz, double ex, double ey, double ez, boolean endRelative);
        long program(ServerLevel level, String json,
                     double sx, double sy, double sz, double ex, double ey, double ez);
        void declareGraph(String graphJson);
        long playGraph(MinecraftServer server, long id, String graphJson, String optionsJson);
        void setHandle(MinecraftServer server, long id, String name, String valueJson);
        void reanchorHandle(MinecraftServer server, long id, String startJson, String endJson);
        void detachHandle(MinecraftServer server, long id);
        void signalHandle(MinecraftServer server, long id, String name, String dataJson);
    }

    public interface Physics {
        void force(long id, float x, float y, float z);
        void impulse(long id, float x, float y, float z);
        void selfRight(long id);
        void destroy(MinecraftServer server, long id);
        void restore(MinecraftServer server, long id);
    }

    public interface Geo {
        Collection<String> entityBones(Entity entity);
        void playAnimation(Entity entity, String clip, int holdTicks);
    }
}
