package com.koper.koper_lib.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// single source of truth for pack discovery and enabled/disabled state
public class FullPackLoader {
    private static final Object PACK_STATE_LOCK = new Object();

    // folder-name → metadata (null meta = valid pack, just missing kopermeta file)
    private static final Map<String, KoperMeta> LOADED_PACKS = new LinkedHashMap<>();

    // folder names of disabled packs; persisted to options.json
    private static final Set<String> DISABLED_PACKS = new LinkedHashSet<>();

    // embedded packs that have been auto-copied from mod JARs; persisted so we detect user deletion
    private static final Set<String> AUTO_INSTALLED_EMBEDDED = new LinkedHashSet<>();

    private static final Path CACHE_DIR = KoperLibDirectories.FULLPACKS.resolve(".cache");

    // pack priority, top first, persisted as "pack_order" in options.json. like bedrock's pack stack: when two packs
    // define the same thing (three of them redraw the player) the higher one owns it and the others hook onto it.
    // packs not in the list come after the listed ones, alphabetically
    private static final List<String> PACK_ORDER = new ArrayList<>();

    // every known pack, highest priority first
    public static List<String> ordered() {
        List<String> all;
        List<String> order;
        synchronized (PACK_STATE_LOCK) {
            all = new ArrayList<>(LOADED_PACKS.keySet());
            order = new ArrayList<>(PACK_ORDER);
        }
        List<String> out = new ArrayList<>();
        for (String p : order) if (all.contains(p)) out.add(p);
        all.stream().filter(p -> !out.contains(p)).sorted().forEach(out::add);
        return out;
    }

    // 0 = top. a folder nobody knows sorts last
    public static int priority(String packFolderName) {
        int i = ordered().indexOf(packFolderName);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    // top / up / down / bottom. false = no such pack
    public static boolean move(String packFolderName, String how) {
        List<String> now = ordered();
        int i = now.indexOf(packFolderName);
        if (i < 0) return false;
        now.remove(i);
        int to = switch (how) {
            case "top" -> 0;
            case "up" -> Math.max(0, i - 1);
            case "down" -> Math.min(now.size(), i + 1);
            default -> now.size();
        };
        now.add(to, packFolderName);
        synchronized (PACK_STATE_LOCK) {
            PACK_ORDER.clear();
            PACK_ORDER.addAll(now);
        }
        persistOptions();
        return true;
    }

    public static boolean isEnabled(String packFolderName) {
        synchronized (PACK_STATE_LOCK) {
            return !DISABLED_PACKS.contains(packFolderName);
        }
    }

    // persists state to options.json; does NOT trigger a reload
    public static void setDisabled(String packFolderName, boolean disabled) {
        synchronized (PACK_STATE_LOCK) {
            if (disabled) {
                DISABLED_PACKS.add(packFolderName);
            } else {
                DISABLED_PACKS.remove(packFolderName);
                // remove from auto-installed so it gets re-copied from JAR on next reload
                AUTO_INSTALLED_EMBEDDED.remove(packFolderName);
            }
        }
        persistOptions();
    }

    // called at reload start to pick up any manual options.json edits
    public static void loadDisabledPacks() {
        Set<String> disabled = new LinkedHashSet<>();
        Set<String> autoInstalled = new LinkedHashSet<>();
        File optionsFile = KoperLibDirectories.ROOT.resolve("options.json").toFile();
        if (!optionsFile.exists()) {
            try (FileWriter fw = new FileWriter(optionsFile)) {
                fw.write("{\n  \"disabled_packs\": []\n}\n");
            } catch (Exception ignored) {}
            synchronized (PACK_STATE_LOCK) {
                DISABLED_PACKS.clear();
                AUTO_INSTALLED_EMBEDDED.clear();
            }
            return;
        }
        try (FileReader fr = new FileReader(optionsFile)) {
            JsonObject obj = JsonParser.parseReader(fr).getAsJsonObject();
            if (obj.has("disabled_packs")) {
                obj.getAsJsonArray("disabled_packs").forEach(e -> disabled.add(e.getAsString()));
            }
            if (obj.has("auto_installed_embedded")) {
                obj.getAsJsonArray("auto_installed_embedded").forEach(e -> autoInstalled.add(e.getAsString()));
            }
            List<String> order = new ArrayList<>();
            if (obj.has("pack_order")) obj.getAsJsonArray("pack_order").forEach(e -> order.add(e.getAsString()));
            synchronized (PACK_STATE_LOCK) {
                PACK_ORDER.clear();
                PACK_ORDER.addAll(order);
            }
            synchronized (PACK_STATE_LOCK) {
                DISABLED_PACKS.clear();
                DISABLED_PACKS.addAll(disabled);
                AUTO_INSTALLED_EMBEDDED.clear();
                AUTO_INSTALLED_EMBEDDED.addAll(autoInstalled);
            }
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[FullPackLoader] Failed to read options.json: {}", e.getMessage());
        }
    }

    private static void persistOptions() {
        File optionsFile = KoperLibDirectories.ROOT.resolve("options.json").toFile();
        JsonObject obj = new JsonObject();

        if (optionsFile.exists()) {
            try (FileReader fr = new FileReader(optionsFile)) {
                JsonObject existing = JsonParser.parseReader(fr).getAsJsonObject();
                existing.entrySet().forEach(e -> obj.add(e.getKey(), e.getValue()));
            } catch (Exception ignored) {}
        }

        Set<String> disabledSnapshot;
        Set<String> autoInstalledSnapshot;
        synchronized (PACK_STATE_LOCK) {
            disabledSnapshot = new LinkedHashSet<>(DISABLED_PACKS);
            autoInstalledSnapshot = new LinkedHashSet<>(AUTO_INSTALLED_EMBEDDED);
        }

        JsonArray disabled = new JsonArray();
        disabledSnapshot.forEach(disabled::add);
        obj.add("disabled_packs", disabled);

        JsonArray autoInstalled = new JsonArray();
        autoInstalledSnapshot.forEach(autoInstalled::add);
        obj.add("auto_installed_embedded", autoInstalled);

        JsonArray order = new JsonArray();
        synchronized (PACK_STATE_LOCK) { PACK_ORDER.forEach(order::add); }
        if (!order.isEmpty()) obj.add("pack_order", order);

        try (FileWriter fw = new FileWriter(optionsFile)) {
            fw.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj));
        } catch (Exception e) {
            KoperLib.LOGGER.error("[FullPackLoader] Failed to write options.json", e);
        }
    }

    public static void loadFullPacks() {
        loadDisabledPacks();
        synchronized (PACK_STATE_LOCK) {
            LOADED_PACKS.clear();
            PACK_DIRS.clear();
        }

        File fullPacksDir = KoperLibDirectories.FULLPACKS.toFile();
        if (!fullPacksDir.exists() || !fullPacksDir.isDirectory()) return;

        try {
            if (!Files.exists(CACHE_DIR)) Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            KoperLib.LOGGER.error("Failed to create fullpacks cache", e);
        }

        // bedrock .mcaddon/.mcpack get turned into <name>_bedrock folders first, the scan below then finds them
        try {
            com.koper.koper_lib.bedrock.BedrockPaczkomat.przerob();
        } catch (Throwable bedrockDied) {
            KoperLib.LOGGER.error("[FullPackLoader] bedrock addon conversion died, koper packs still load", bedrockDied);
        }

        // scan on-disk packs (dirs + zips)
        scanPackDir(fullPacksDir, false);

        // QoL: a fullpack dropped into mods/ or a global datapacks/ folder just works.
        // stranger dirs only take dirs/zips that actually carry koper metadata — a plain
        // datapack or some random mod zip is none of our business
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        scanPackDir(new File(gameDir, "datapacks"), true);
        scanPackDir(new File(gameDir, "mods"), true);

        // scan embedded packs from other mod JARs
        loadEmbeddedPacks();

        checkDependencies();

        Map<String, KoperMeta> packs = loadedPacksSnapshot();
        int enabled  = (int) packs.keySet().stream().filter(FullPackLoader::isEnabled).count();
        int disabled = packs.size() - enabled;
        KoperLib.LOGGER.info("[FullPackLoader] Discovered {} fullpacks ({} enabled, {} disabled):",
            packs.size(), enabled, disabled);
        packs.forEach((name, meta) -> {
            String state = isEnabled(name) ? "§aON" : "§cOFF";
            KoperLib.LOGGER.info("  [{}] {} {}", state, name, meta != null ? meta : "(no metadata)");
        });
    }

    // iterative dep check — runs until stable or 10 iterations (handles cascading deps)
    private static void checkDependencies() {
        for (int pass = 0; pass < 10; pass++) {
            boolean changed = false;
            Map<String, KoperMeta> packs = loadedPacksSnapshot();
            for (var entry : packs.entrySet()) {
                String packName = entry.getKey();
                KoperMeta meta  = entry.getValue();
                if (meta == null || !isEnabled(packName)) continue;

                // check mod deps
                for (String modId : meta.requiresMods) {
                    if (!FabricLoader.getInstance().isModLoaded(modId)) {
                        KoperLib.LOGGER.warn("[FullPackLoader] Disabling '{}' — required mod '{}' not loaded.", packName, modId);
                        addDisabled(packName);
                        changed = true;
                        break;
                    }
                }

                if (!isEnabled(packName)) continue;

                // check pack deps
                for (String req : meta.requiresPacks) {
                    boolean depOk = packs.entrySet().stream().anyMatch(e -> {
                        if (!isEnabled(e.getKey())) return false;
                        // match by folder name or effective namespace
                        if (e.getKey().equals(req)) return true;
                        KoperMeta dm = e.getValue();
                        return dm != null && req.equals(dm.getEffectiveNamespace(e.getKey()));
                    });
                    if (!depOk) {
                        KoperLib.LOGGER.warn("[FullPackLoader] Disabling '{}' — required pack '{}' not enabled.", packName, req);
                        addDisabled(packName);
                        changed = true;
                        break;
                    }
                }
            }
            if (!changed) break;
        }
    }

    // paths of all enabled packs that have sourcePath set — for dev watcher
    public static List<Path> getEnabledPackSourcePaths() {
        List<Path> out = new ArrayList<>();
        loadedPacksSnapshot().forEach((name, meta) -> {
            if (!isEnabled(name) || meta == null || meta.sourcePath == null) return;
            out.add(meta.sourcePath);
        });
        return out;
    }

    private static void scanPackDir(File dir, boolean strangerDir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().startsWith(".")) continue;
            if (file.isDirectory()) {
                if (com.koper.koper_lib.bedrock.BedrockPaczkomat.isBedrockDir(file.toPath())) continue;
                if (strangerDir && (hasLoadedPack(file.getName()) || !hasKoperMeta(file))) continue;
                registerPack(file);
            } else if (file.getName().endsWith(".zip")) {
                if (com.koper.koper_lib.bedrock.BedrockPaczkomat.isBedrockArchive(file.toPath())) continue;
                String packName = file.getName().replace(".zip", "");
                if (strangerDir && (hasLoadedPack(packName) || !zipHasKoperMeta(file))) continue;
                Path target = CACHE_DIR.resolve(packName);
                if (unzip(file, target)) {
                    registerPack(target.toFile());
                }
            }
        }
    }

    private static boolean hasKoperMeta(File dir) {
        return new File(dir, "pack.kopermeta").exists() || new File(dir, "fullpack.json").exists();
    }

    private static boolean zipHasKoperMeta(File zip) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) {
            return z.getEntry("pack.kopermeta") != null || z.getEntry("fullpack.json") != null;
        } catch (Exception e) {
            return false; // unreadable zip = not our pack, whatever it is
        }
    }

    // pack folder by name — content loading must work for packs living OUTSIDE the
    // fullpacks dir too (mods/, datapacks/), so nobody gets to re-list directories themselves
    private static final Map<String, File> PACK_DIRS = new LinkedHashMap<>();

    public static List<File> getEnabledPackDirs() {
        List<File> out = new ArrayList<>();
        packDirsSnapshot().forEach((name, dir) -> {
            if (isEnabled(name) && dir.isDirectory()) out.add(dir);
        });
        return out;
    }

    private static void registerPack(File packDir) {
        String name = packDir.getName();
        KoperMeta meta = KoperMeta.load(packDir);
        if (meta != null) meta.sourcePath = packDir.toPath();
        synchronized (PACK_STATE_LOCK) {
            LOADED_PACKS.put(name, meta);
            PACK_DIRS.put(name, packDir);
        }
        if (!isEnabled(name)) {
            KoperLib.LOGGER.info("[FullPackLoader] Skipping disabled pack: {}", name);
        }
    }

    private static void loadEmbeddedPacks() {
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            String modId = mod.getMetadata().getId();
            mod.findPath("koperlib/fullpacks").ifPresent(embeddedRoot -> {
                if (!Files.isDirectory(embeddedRoot)) return;
                try {
                    Files.list(embeddedRoot).forEach(packPath -> {
                        if (!Files.isDirectory(packPath)) return;
                        String packName = packPath.getFileName().toString();
                        Path dest = KoperLibDirectories.FULLPACKS.resolve(packName);

                        if (Files.exists(dest)) {
                            if (wasAutoInstalled(packName) && isEnabled(packName)) {
                                KoperLib.LOGGER.info("[FullPackLoader] Refreshing auto-installed embedded pack '{}' from mod '{}'", packName, modId);
                                try {
                                    syncDirectory(packPath, dest);
                                } catch (IOException e) {
                                    KoperLib.LOGGER.error("[FullPackLoader] Failed to refresh embedded pack '{}' from mod '{}'", packName, modId, e);
                                }
                                return;
                            }
                            KoperLib.LOGGER.info("[FullPackLoader] Embedded pack '{}' from mod '{}' skipped — on-disk has priority.", packName, modId);
                            return;
                        }

                        // dest doesn't exist — check if user deleted it
                        if (wasAutoInstalled(packName)) {
                            // was previously copied; user deleted the folder → treat as disabled, don't re-copy
                            if (isEnabled(packName)) {
                                addDisabled(packName);
                                persistOptions();
                            }
                            KoperLib.LOGGER.info("[FullPackLoader] Embedded pack '{}' was deleted by user — skipping. Use /koperlib fullpack enable {} to restore.", packName, packName);
                            return;
                        }

                        if (!isEnabled(packName)) {
                            KoperLib.LOGGER.info("[FullPackLoader] Skipping disabled embedded pack '{}'.", packName);
                            return;
                        }

                        KoperLib.LOGGER.info("[FullPackLoader] Copying embedded pack '{}' from mod '{}'", packName, modId);
                        try {
                            copyDirectory(packPath, dest);
                            addAutoInstalled(packName);
                            persistOptions();
                            registerPack(dest.toFile());
                        } catch (IOException e) {
                            KoperLib.LOGGER.error("[FullPackLoader] Failed to copy embedded pack '{}' from mod '{}'", packName, modId, e);
                        }
                    });
                } catch (IOException e) {
                    KoperLib.LOGGER.warn("[FullPackLoader] Failed to scan embedded packs in mod '{}'", modId, e);
                }
            });
        });
    }

    public static List<String> getLoadedPacks() {
        return new ArrayList<>(loadedPacksSnapshot().keySet());
    }

    // returns namespaces of all currently enabled packs (for registry filtering)
    public static Set<String> getEnabledNamespaces() {
        Set<String> result = new LinkedHashSet<>();
        loadedPacksSnapshot().forEach((folder, meta) -> {
            if (!isEnabled(folder)) return;
            result.add(meta != null && meta.namespace != null && !meta.namespace.isEmpty()
                ? meta.namespace : folder);
        });
        return result;
    }

    public static KoperMeta getMeta(String packName) {
        synchronized (PACK_STATE_LOCK) {
            return LOADED_PACKS.get(packName);
        }
    }

    public static Map<String, KoperMeta> getAllPacks() {
        return Collections.unmodifiableMap(loadedPacksSnapshot());
    }

    private static Map<String, KoperMeta> loadedPacksSnapshot() {
        synchronized (PACK_STATE_LOCK) {
            return new LinkedHashMap<>(LOADED_PACKS);
        }
    }

    private static Map<String, File> packDirsSnapshot() {
        synchronized (PACK_STATE_LOCK) {
            return new LinkedHashMap<>(PACK_DIRS);
        }
    }

    private static boolean hasLoadedPack(String name) {
        synchronized (PACK_STATE_LOCK) {
            return LOADED_PACKS.containsKey(name);
        }
    }

    private static void addDisabled(String name) {
        synchronized (PACK_STATE_LOCK) {
            DISABLED_PACKS.add(name);
        }
    }

    private static boolean wasAutoInstalled(String name) {
        synchronized (PACK_STATE_LOCK) {
            return AUTO_INSTALLED_EMBEDDED.contains(name);
        }
    }

    private static void addAutoInstalled(String name) {
        synchronized (PACK_STATE_LOCK) {
            AUTO_INSTALLED_EMBEDDED.add(name);
        }
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (var stream = Files.walk(src)) {
            stream.forEach(s -> {
                Path d = dest.resolve(src.relativize(s).toString());
                try {
                    if (Files.isDirectory(s)) Files.createDirectories(d);
                    else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void syncDirectory(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);

        // Remove files no longer shipped by the embedded pack before refreshing it.
        try (var stream = Files.walk(dest)) {
            List<Path> existing = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path target : existing) {
                if (target.equals(dest)) continue;
                Path relative = dest.relativize(target);
                Path sourceTarget = src;
                for (Path part : relative) sourceTarget = sourceTarget.resolve(part.toString());
                if (!Files.exists(sourceTarget)) {
                    Files.deleteIfExists(target);
                }
            }
        }

        copyDirectory(src, dest);
    }

    private static boolean unzip(File zipFile, Path targetDir) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
            return true;
        } catch (Exception e) {
            KoperLib.LOGGER.error("Failed to unzip FullPack: " + zipFile.getName(), e);
            return false;
        }
    }
}
