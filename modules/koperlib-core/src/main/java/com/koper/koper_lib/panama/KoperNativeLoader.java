package com.koper.koper_lib.panama;

import com.koper.koper_lib.coremod.KoperCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// pulls koperlib_engine from inside the mod jar — drop only koper_lib.jar in mods/
public final class KoperNativeLoader {

    private static final String LIB_STEM = "koperlib_engine";
    private static final Map<String, Path> LOADED = new ConcurrentHashMap<>();

    private KoperNativeLoader() {}

    public static Path loadFromJar() {
        return load("legacy", LIB_STEM, KoperNativeLoader.class, false);
    }

    public static Path loadFromJar(Class<?> resourceAnchor) {
        return load("legacy", LIB_STEM, resourceAnchor, false);
    }

    // every koper mod can ship its own native/<platform>/<mapped name>; core does extraction and loading
    public static Path loadModuleFromJar(String moduleId, String libraryStem, Class<?> resourceAnchor) {
        return load(moduleId, libraryStem, resourceAnchor, true);
    }

    private static Path load(String moduleId, String libraryStem, Class<?> resourceAnchor, boolean moduleDirectory) {
        String owner = clean(moduleId);
        String stem = clean(libraryStem);
        if (resourceAnchor == null) throw new IllegalArgumentException("native resource anchor is null");

        String fileName = System.mapLibraryName(stem);
        String platform = platformKey();
        String resource = "native/" + platform + "/" + fileName;
        String loadedKey = owner + ":" + stem + ":" + platform;
        Path already = LOADED.get(loadedKey);
        if (already != null) return already;

        try (InputStream in = resourceAnchor.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                // debug-level here meant a player on an unshipped OS saw a mod that just quietly did
                // nothing. scripting and physics both die on this path — it deserves to be loud
                KoperCore.LOGGER.error("[Native] this build ships no engine for {} — scripting, physics and"
                    + " the whole rust side stay OFF. koperlib will load but most of it won't work.", platform);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length < 4096) {
                KoperCore.LOGGER.warn("[Native] bundled {} looks too small ({} bytes)", resource, bytes.length);
                return null;
            }

            Path nativeRoot = FabricLoader.getInstance().getGameDir().resolve(".koperlib/natives");
            Path dir = moduleDirectory
                ? nativeRoot.resolve(owner).resolve(platform)
                : nativeRoot.resolve(platform);
            Files.createDirectories(dir);
            Path lib = dir.resolve(fileName);
            Path stamp = dir.resolve(fileName + ".sha256");

            String hash = sha256(bytes);
            boolean fresh = Files.exists(lib) && Files.exists(stamp)
                    && hash.equals(Files.readString(stamp).trim());

            if (!fresh) {
                Path tmp = dir.resolve(fileName + ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, lib, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(tmp, lib, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(stamp, hash);
                KoperCore.LOGGER.info("[Native] extracted {} ({} bytes) → {}", resource, bytes.length, lib);
            }

            loadNative(lib, stem);
            LOADED.put(loadedKey, lib);
            return lib;
        } catch (UnsatisfiedLinkError e) {
            KoperCore.LOGGER.error("[Native] System.load failed for {}: {}", fileName, e.getMessage());
            return null;
        } catch (Exception e) {
            KoperCore.LOGGER.error("[Native] extract/load failed for {}", resource, e);
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) throw new IllegalArgumentException("native id is null");
        String clean = value.trim().toLowerCase();
        if (clean.isEmpty() || !clean.matches("[a-z0-9_.-]+"))
            throw new IllegalArgumentException("bad native id: " + value);
        return clean;
    }

    static String platformKey() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown").toLowerCase();
        String archKey = arch.contains("amd64") || arch.contains("x86_64") ? "x86_64"
                : arch.contains("aarch64") || arch.contains("arm64") ? "aarch64"
                : arch.replace(' ', '-');
        // zalith/pojav is bionic, not glibc — needs its own .so, can't share the linux key
        if (isAndroid()) return "android-" + archKey;
        String osKey = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos"
                : os.contains("linux") ? "linux" : os.replace(' ', '-');
        return osKey + "-" + archKey;
    }

    // pojav/zalith run an android jvm — /system is the tell, desktop linux has none of it
    static boolean isAndroid() {
        if (System.getProperty("java.vendor", "").toLowerCase().contains("android")) return true;
        try {
            return Files.exists(Path.of("/system/build.prop"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // desktop just System.load()s the abs path. android api29+ can refuse that from a
    // random dir, so fall back to dropping it into a writable java.library.path + loadLibrary
    private static void loadNative(Path lib, String stem) {
        if (!isAndroid()) {
            System.load(lib.toAbsolutePath().toString());
            return;
        }
        try {
            System.load(lib.toAbsolutePath().toString());
            return;
        } catch (UnsatisfiedLinkError blocked) {
            KoperCore.LOGGER.warn("[Native] android refused System.load({}), retrying via library.path", lib);
        }
        String mapped = System.mapLibraryName(stem);
        for (String dir : System.getProperty("java.library.path", "").split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) continue;
            try {
                Path dst = Path.of(dir).resolve(mapped);
                Files.copy(lib, dst, StandardCopyOption.REPLACE_EXISTING);
                System.loadLibrary(stem);
                KoperCore.LOGGER.info("[Native] android loaded {} from {}", mapped, dir);
                return;
            } catch (Throwable ignored) {}
        }
        throw new UnsatisfiedLinkError("android: no writable java.library.path entry for " + mapped);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
