package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// watches fullpack dirs for changes during development and auto-reloads
// only active when running under Loom (fabric.development=true)
// for mod devs: also watches koperlib/fullpack/ source dirs inside loaded mods
// PLEASE HELP A SILLY LITTLE KOPERDEV — NIO WatchService on windows has ~10s polling fallback if not FSEVENTS
public class DevPackWatcher {

    private static final boolean DEV = Boolean.getBoolean("fabric.development");
    private static final long DEBOUNCE_MS = 800;

    private static volatile boolean running = false;
    private static WatchService watcher;

    // guarded by AtomicLong — last time we saw a change
    private static final AtomicLong lastChangedAt = new AtomicLong(0);
    private static final AtomicLong suppressUntil = new AtomicLong(0);
    private static final AtomicInteger internalReloadDepth = new AtomicInteger(0);
    private static final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>();

    public static boolean isActive() { return DEV && running; }

    public static void setServer(MinecraftServer server) {
        serverRef.set(server);
    }

    public static void beginInternalReload() {
        internalReloadDepth.incrementAndGet();
        lastChangedAt.set(0);
        suppressUntil.set(Long.MAX_VALUE);
    }

    public static void endInternalReload() {
        int depth = internalReloadDepth.updateAndGet(v -> Math.max(0, v - 1));
        lastChangedAt.set(0);
        if (depth == 0) suppressUntil.set(System.currentTimeMillis() + 2_000L);
    }

    public static void start() {
        if (!DEV) return;
        if (running) return;

        try {
            watcher = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            KoperLib.LOGGER.error("[DevWatcher] Failed to create WatchService: {}", e.getMessage());
            return;
        }

        List<Path> watchPaths = gatherWatchPaths();
        if (watchPaths.isEmpty()) {
            KoperLib.LOGGER.info("[DevWatcher] No paths to watch — watcher idle.");
            return;
        }

        int registered = 0;
        for (Path p : watchPaths) {
            registered += registerRecursive(p);
        }
        KoperLib.LOGGER.info("[DevWatcher] Watching {} directories across {} roots.", registered, watchPaths.size());

        running = true;
        Thread.ofVirtual().name("koper-dev-watcher").start(DevPackWatcher::watchLoop);
        Thread.ofVirtual().name("koper-dev-debounce").start(DevPackWatcher::debounceLoop);
    }

    public static void stop() {
        running = false;
        if (watcher != null) try { watcher.close(); } catch (IOException ignored) {}
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static List<Path> gatherWatchPaths() {
        List<Path> out = new ArrayList<>();

        // always watch the main fullpacks dir
        Path fp = KoperLibDirectories.FULLPACKS;
        if (Files.isDirectory(fp)) out.add(fp);

        // also watch sourcePaths set on KoperMeta (via FULLPACKS loader) — these are dev symlinks
        for (Path src : FullPackLoader.getEnabledPackSourcePaths()) {
            if (Files.isDirectory(src) && !src.startsWith(fp)) out.add(src);
        }

        // walk all loaded mods — any that have koperlib/fullpack/ in their source tree
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            // singular koperlib/fullpack/ — one-pack-per-mod convention
            mod.findPath("koperlib/fullpack").ifPresent(p -> {
                if (Files.isDirectory(p)) {
                    // in dev Loom the path is an actual FS dir, not inside a jar
                    try {
                        p.getFileSystem().provider().getClass().getSimpleName(); // touch it
                        if (!p.getFileSystem().equals(FileSystems.getDefault())) return; // inside jar, skip
                        if (Files.isDirectory(p)) { out.add(p); }
                    } catch (Exception ignored) {}
                }
            });
            // plural koperlib/fullpacks/ — multi-pack convention
            mod.findPath("koperlib/fullpacks").ifPresent(p -> {
                if (Files.isDirectory(p)) {
                    try {
                        if (!p.getFileSystem().equals(FileSystems.getDefault())) return;
                        out.add(p);
                    } catch (Exception ignored) {}
                }
            });
        });

        return out;
    }

    private static int registerRecursive(Path root) {
        int[] count = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // skip .cache and hidden dirs
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        dir.register(watcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                        count[0]++;
                    } catch (IOException e) {
                        KoperLib.LOGGER.warn("[DevWatcher] Can't watch {}: {}", dir, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            KoperLib.LOGGER.warn("[DevWatcher] Walk failed for {}: {}", root, e.getMessage());
        }
        return count[0];
    }

    private static void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watcher.take(); // blocks
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                Path changed = ((WatchEvent<Path>) event).context();
                String name = changed.toString();
                // ignore hidden dirs/files — .cache/ gets created by java compiler during reload
                // without this filter the compiler run triggers a new reload → infinite loop
                if (name.startsWith(".")) continue;
                // ignore compilation output and editor temp files
                if (name.endsWith(".class") || name.endsWith("~") || name.endsWith(".tmp")) continue;
                if (name.startsWith("___jb_") || name.endsWith(".swp") || name.endsWith(".swx")) continue;
                if (internalReloadDepth.get() > 0 || System.currentTimeMillis() < suppressUntil.get()) continue;

                relevant = true;
                KoperLib.LOGGER.debug("[DevWatcher] Changed: {}/{}", ((Path) key.watchable()), name);

                // if a new directory appeared, register it too
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path newDir = ((Path) key.watchable()).resolve(changed);
                    if (Files.isDirectory(newDir)) registerRecursive(newDir);
                }
            }

            if (relevant) lastChangedAt.set(System.currentTimeMillis());
            if (!key.reset()) {
                // directory was deleted — key is invalid, that's fine, watcher continues for other dirs
                KoperLib.LOGGER.debug("[DevWatcher] Watch key invalidated (directory deleted): {}", key.watchable());
            }
        }
    }

    private static void debounceLoop() {
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }

            long changed = lastChangedAt.get();
            if (changed == 0) continue;
            if (System.currentTimeMillis() - changed < DEBOUNCE_MS) continue;

            // CAS — only one thread fires the reload
            if (!lastChangedAt.compareAndSet(changed, 0)) continue;

            MinecraftServer server = serverRef.get();
            if (server == null || !server.isRunning()) continue;

            KoperLib.LOGGER.info("[DevWatcher] Change detected — triggering hot-reload...");
            // schedule on server thread (Minecraft is not thread safe)
            server.execute(() -> {
                try {
                    com.koper.koper_lib.loader.FullpackReloader.reload(server);
                } catch (Exception e) {
                    KoperLib.LOGGER.error("[DevWatcher] Reload failed: {}", e.getMessage(), e);
                }
            });
        }
    }
}
