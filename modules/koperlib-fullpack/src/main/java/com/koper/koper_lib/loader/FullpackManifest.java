package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.network.FullPackSyncPayload;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Builds deterministic local/server manifests. It never downloads or installs anything. */
public final class FullpackManifest {
    private FullpackManifest() {}

    public static List<FullPackSyncPayload.PackEntry> loaded() {
        List<FullPackSyncPayload.PackEntry> entries = new ArrayList<>();
        FullPackLoader.getAllPacks().forEach((folder, meta) -> {
            if (!FullPackLoader.isEnabled(folder)) return;
            Path root = meta != null ? meta.sourcePath : KoperLibDirectories.FULLPACKS.resolve(folder);
            Fingerprint fingerprint = fingerprint(root);
            entries.add(new FullPackSyncPayload.PackEntry(
                safe(folder, 128), safe(meta == null ? folder : meta.name, 256),
                safe(meta == null ? "" : meta.version, 64), fingerprint.sha256(), fingerprint.bytes(),
                meta == null ? List.of() : safeList(meta.requiresMods, 128, 128),
                meta == null ? List.of() : safeList(meta.requiresPacks, 128, 128),
                meta == null ? List.of() : safeHttps(meta.downloadLinks),
                containsExecutableContent(root)));
        });
        entries.sort(Comparator.comparing(FullPackSyncPayload.PackEntry::id));
        return List.copyOf(entries);
    }

    private static Fingerprint fingerprint(Path root) {
        if (root == null || !Files.isDirectory(root)) return new Fingerprint("", 0L);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
            }
            long bytes = 0L;
            byte[] buffer = new byte[64 * 1024];
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream in = Files.newInputStream(file)) {
                    for (int read; (read = in.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        digest.update(buffer, 0, read);
                        bytes += read;
                    }
                }
            }
            return new Fingerprint(HexFormat.of().formatHex(digest.digest()), bytes);
        } catch (Throwable error) {
            KoperLib.LOGGER.warn("[Fullpack manifest] cannot hash {}: {}", root, error.toString());
            return new Fingerprint("", 0L);
        }
    }

    private static boolean containsExecutableContent(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(path -> {
                String value = root.relativize(path).toString().replace('\\', '/').toLowerCase();
                return value.endsWith(".java") || value.endsWith(".class") || value.endsWith(".jar")
                    || value.startsWith("java/") || value.contains("/java/");
            });
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static List<String> safeHttps(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> safe(value, 512))
            .filter(value -> value.startsWith("https://"))
            .distinct().limit(8).toList();
    }

    private static List<String> safeList(List<String> values, int maxItems, int maxChars) {
        if (values == null) return List.of();
        return values.stream().map(value -> safe(value, maxChars)).filter(value -> !value.isBlank())
            .distinct().limit(maxItems).toList();
    }

    public static String safe(String value, int maxChars) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}§]", "").trim();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
    }

    private record Fingerprint(String sha256, long bytes) {}
}
