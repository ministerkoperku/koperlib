package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

// serves pack assets; checks standard MC layout first, falls back to flat koperlib layout
public class FullPackResourcePack implements PackResources {
    private final PackLocationInfo info;
    private final String namespace;
    private final Path root;

    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    public FullPackResourcePack(PackLocationInfo info, Path path, String namespace) {
        this.info = info;
        this.root = path;
        this.namespace = namespace;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... segments) {
        Path p = root;
        for (String seg : segments) p = p.resolve(seg);
        final Path finalPath = p;
        if (Files.exists(finalPath)) {
            return () -> Files.newInputStream(finalPath);
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (!id.getNamespace().equals(namespace)) return overrideOf(id);

        String path = id.getPath();

        // 1. New layout: assets/{namespace}/{path}
        Path newPath = root.resolve("assets").resolve(namespace).resolve(path);
        if (Files.exists(newPath)) {
            if (path.endsWith(".png") && !isValidPng(newPath)) {
                KoperLib.LOGGER.warn("Skipping corrupt PNG: {}", newPath);
                return null;
            }
            return () -> Files.newInputStream(newPath);
        }

        // 2. GeckoLib legacy mapping: geckolib/animations/* → animations/*, geckolib/models/* → geo/*
        if (path.startsWith("geckolib/animations/")) {
            Path legacyAnim = root.resolve("animations").resolve(path.substring("geckolib/animations/".length()));
            if (Files.exists(legacyAnim)) return () -> Files.newInputStream(legacyAnim);
        } else if (path.startsWith("geckolib/models/")) {
            Path legacyGeo = root.resolve("geo").resolve(path.substring("geckolib/models/".length()));
            if (Files.exists(legacyGeo)) return () -> Files.newInputStream(legacyGeo);
        }

        // 3. Legacy layout: {path} directly under root (e.g. textures/item/sword.png)
        Path legacyPath = root.resolve(path);
        if (Files.exists(legacyPath)) {
            if (path.endsWith(".png") && !isValidPng(legacyPath)) {
                KoperLib.LOGGER.warn("Skipping corrupt PNG: {}", legacyPath);
                return null;
            }
            return () -> Files.newInputStream(legacyPath);
        }

        // old FULLPACKs keep textures flat; vanilla atlases ask for block/ or item/ now
        if (path.startsWith("textures/block/") || path.startsWith("textures/item/")) {
            String flatName = path.substring(path.indexOf('/', "textures/".length()) + 1);
            Path flatTexture = root.resolve("textures").resolve(flatName);
            if (Files.exists(flatTexture)) return () -> Files.newInputStream(flatTexture);
        }

        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput consumer) {
        if (!this.namespace.equals(namespace)) {
            // converted bedrock resource packs replace vanilla textures, they sit in assets/minecraft
            Path other = overrideRoot(namespace);
            if (other != null) scanDirectory(other, other, prefix, namespace, consumer);
            return;
        }

        // Scan new layout: assets/{namespace}/
        Path assetsNsDir = root.resolve("assets").resolve(namespace);
        if (Files.isDirectory(assetsNsDir)) {
            scanDirectory(assetsNsDir, assetsNsDir, prefix, namespace, consumer);
        }

        // Scan legacy layout: textures/ etc. directly under root
        scanLegacySubdir(root, "textures", prefix, namespace, consumer);
        scanFlatTextureAliases(root, prefix, namespace, consumer);
        scanLegacySubdir(root, "models", prefix, namespace, consumer);
        scanLegacySubdir(root, "lang", prefix, namespace, consumer);
        scanLegacySubdir(root, "blockstates", prefix, namespace, consumer);

        // GeckoLib 5 expects geckolib/animations/ and geckolib/models/ prefixes
        // Map legacy flat dirs: animations/ → geckolib/animations/, geo/ → geckolib/models/
        scanLegacyGeckoLib(root, "animations", "geckolib/animations", prefix, namespace, consumer);
        scanLegacyGeckoLib(root, "geo", "geckolib/models", prefix, namespace, consumer);
    }

    /**
     * Maps legacy flat GeckoLib dirs to the geckolib/ prefixed paths GeckoLib 5 expects.
     * E.g. root/animations/kapoka.animation.json → geckolib/animations/kapoka.animation.json
     *      root/geo/kapoka.geo.json              → geckolib/models/kapoka.geo.json
     */
    private void scanLegacyGeckoLib(Path packRoot, String legacyDir, String targetPrefix,
                                    String prefix, String namespace, PackResources.ResourceOutput consumer) {
        Path dir = packRoot.resolve(legacyDir);
        if (!Files.isDirectory(dir)) return;
        if (!targetPrefix.startsWith(prefix) && !prefix.startsWith(targetPrefix)) return;
        try (var walk = Files.walk(dir)) {
            walk.forEach(p -> {
                if (!Files.isRegularFile(p)) return;
                String fileName = dir.relativize(p).toString().replace('\\', '/');
                String mappedPath = targetPrefix + "/" + fileName;
                if (!mappedPath.startsWith(prefix)) return;
                Identifier id = Identifier.fromNamespaceAndPath(namespace, mappedPath);
                if (id != null) {
                    consumer.accept(id, () -> Files.newInputStream(p));
                }
            });
        } catch (IOException e) {
            KoperLib.LOGGER.warn("Error scanning legacy GeckoLib dir {} in {}", dir, packRoot, e);
        }
    }

    private void scanLegacySubdir(Path packRoot, String subdir, String prefix,
                                  String namespace, PackResources.ResourceOutput consumer) {
        Path dir = packRoot.resolve(subdir);
        if (!Files.isDirectory(dir)) return;
        // The "relative from parent" trick: textures/item/foo.png → textures/item/foo.png
        scanDirectory(dir.getParent(), dir, prefix, namespace, consumer);
    }

    private void scanFlatTextureAliases(Path packRoot, String prefix, String namespace,
                                        PackResources.ResourceOutput consumer) {
        Path textures = packRoot.resolve("textures");
        if (!Files.isDirectory(textures)) return;
        if (!"textures/block/".startsWith(prefix) && !"textures/item/".startsWith(prefix)
                && !prefix.startsWith("textures/block") && !prefix.startsWith("textures/item")) return;
        try (var list = Files.list(textures)) {
            list.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                for (String kind : java.util.List.of("block", "item")) {
                    String mapped = "textures/" + kind + "/" + name;
                    if (!mapped.startsWith(prefix)) continue;
                    Identifier id = Identifier.fromNamespaceAndPath(namespace, mapped);
                    consumer.accept(id, () -> Files.newInputStream(path));
                }
            });
        } catch (IOException e) {
            KoperLib.LOGGER.warn("Error mapping flat textures in {}", packRoot, e);
        }
    }

    // The files of scanDir whose path relative to baseDir starts with `prefix`, the same answer as
    // walking all of scanDir and filtering, but only descending where a match is possible. Minecraft
    // lists one folder at a time (every tag registry, functions, recipes, textures...), hundreds of
    // calls per reload, and walking the whole tree for each of them made a reload with a few hundred
    // MB of converted bedrock packs take 40 seconds.
    static java.util.List<Path> matchingFiles(Path baseDir, Path scanDir, String prefix) throws IOException {
        int cut = prefix.lastIndexOf('/');
        // the last segment can be a partial name ("sounds" matches sounds.json too), so list its folder
        Path folder = cut < 0 ? baseDir : baseDir.resolve(prefix.substring(0, cut));
        String start = prefix.substring(cut + 1);
        java.util.List<Path> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (var children = Files.list(folder)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!child.getFileName().toString().startsWith(start)) continue;
                Path from = child.startsWith(scanDir) ? child : scanDir.startsWith(child) ? scanDir : null;
                if (from == null) continue;
                try (var walk = Files.walk(from)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> baseDir.relativize(f).toString().replace('\\', '/').startsWith(prefix))
                        .forEach(out::add);
                }
            }
        }
        return out;
    }

    private void scanDirectory(Path baseDir, Path scanDir, String prefix,
                               String namespace, PackResources.ResourceOutput consumer) {
        try {
            matchingFiles(baseDir, scanDir, prefix).forEach(p -> {
                String relative = baseDir.relativize(p).toString().replace('\\', '/');
                if (relative.endsWith(".png") && !isValidPng(p)) {
                    KoperLib.LOGGER.warn("Skipping corrupt PNG in findResources: {}", p);
                    return;
                }
                Identifier id = Identifier.fromNamespaceAndPath(namespace, relative);
                if (id != null) {
                    consumer.accept(id, () -> Files.newInputStream(p));
                }
            });
        } catch (IOException e) {
            KoperLib.LOGGER.warn("Error scanning {} in {}", scanDir, root, e);
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == PackType.CLIENT_RESOURCES && overrideRoot("minecraft") != null && !namespace.equals("minecraft"))
            return Set.of(namespace, "minecraft");
        return Set.of(namespace);
    }

    // only a converted bedrock pack (it has bedrock.koper.json) gets to touch another namespace
    private Path overrideRoot(String ns) {
        if (!"minecraft".equals(ns) || !Files.isRegularFile(root.resolve("bedrock.koper.json"))) return null;
        Path p = root.resolve("assets").resolve("minecraft");
        return Files.isDirectory(p) ? p : null;
    }

    private IoSupplier<InputStream> overrideOf(Identifier id) {
        Path base = overrideRoot(id.getNamespace());
        if (base == null) return null;
        Path p = base.resolve(id.getPath());
        return Files.isRegularFile(p) ? () -> Files.newInputStream(p) : null;
    }

    @Override
    public <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> metaReader) throws IOException {
        // try pack.mcmeta from disk first
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier != null) {
            try (InputStream is = supplier.get()) {
                com.google.gson.JsonObject jsonRoot = com.google.gson.JsonParser
                    .parseReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
                com.google.gson.JsonObject section = jsonRoot.getAsJsonObject(metaReader.name());
                if (section != null) {
                    return metaReader.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, section)
                        .result().orElse(null);
                }
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[FullPackResourcePack] Failed to parse pack.mcmeta in {}: {}", root, e.getMessage());
            }
        }
        // fallback for packs without metadata: the running version, so MC never calls it too old
        return ManualPackProvider.dzisiejszyMeta(metaReader, "KoperLib FullPack");
    }

    @Override
    public PackLocationInfo location() {
        return info;
    }

    @Override
    public void close() {}

    private static boolean isValidPng(Path file) {
        try {
            long size = Files.size(file);
            if (size < 8) return false;
            try (InputStream is = Files.newInputStream(file)) {
                byte[] header = is.readNBytes(8);
                if (header.length < 8) return false;
                for (int i = 0; i < 8; i++) {
                    if (header[i] != PNG_HEADER[i]) return false;
                }
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
