package com.koper.koper_lib.loader;

import com.koper.koper_lib.KoperLib;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class ManualPackProvider implements RepositorySource {
    private final File packsDir;

    public ManualPackProvider(File packsDir) {
        this.packsDir = packsDir;
    }

    @Override
    public void loadPacks(Consumer<Pack> profileAdder) {
        KoperLib.LOGGER.info("[ManualPackProvider] Scanning packs in {}", packsDir.getAbsolutePath());
        if (!packsDir.exists() || !packsDir.isDirectory()) {
            KoperLib.LOGGER.warn("[ManualPackProvider] Packs directory not found!");
            return;
        }

        File[] files = packsDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".")) continue;
            if (!file.isDirectory()) continue;

            // Single source of truth: FullPackLoader decides enabled/disabled
            if (!FullPackLoader.isEnabled(name)) {
                KoperLib.LOGGER.info("[ManualPackProvider] Skipping disabled pack: {}", name);
                continue;
            }

            KoperLib.LOGGER.info("[ManualPackProvider] Registering pack: {}", name);

            KoperMeta meta = FullPackLoader.getMeta(name);
            String effectiveNamespace = meta != null
                    ? meta.getEffectiveNamespace(name)
                    : name.toLowerCase();

            String baseId = "koper_fullpack_" + name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            Component displayName = Component.literal("KoperPack: " + name);

            PackLocationInfo clientInfo = new PackLocationInfo(baseId, displayName, PackSource.BUILT_IN, Optional.empty());
            PackLocationInfo serverInfo = new PackLocationInfo(baseId + "_data", displayName, PackSource.BUILT_IN, Optional.empty());

            final String ns = effectiveNamespace;
            final File packFile = file;
            Pack.ResourcesSupplier packFactory = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openMetadata(PackLocationInfo info) {
                    return new FullPackResourcePack(info, packFile.toPath(), ns);
                }

                @Override
                public java.util.stream.Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata metadata) {
                    return java.util.stream.Stream.of(openMetadata(info));
                }
            };

            // required=true, alwaysEnabled=true keeps the pack active after every reload
            PackSelectionConfig clientPos = new PackSelectionConfig(true, Pack.Position.TOP, true);
            PackSelectionConfig serverPos = new PackSelectionConfig(true, Pack.Position.TOP, true);

            Pack clientProfile = Pack.readMetaAndCreate(clientInfo, packFactory, PackType.CLIENT_RESOURCES, clientPos);
            Pack serverProfile = Pack.readMetaAndCreate(serverInfo, packFactory, PackType.SERVER_DATA, serverPos);

            if (clientProfile != null) profileAdder.accept(clientProfile);
            if (serverProfile != null) profileAdder.accept(serverProfile);

            // embedded datapacks — fullpack/datapacks/{sub}/ registered as standalone SERVER_DATA packs
            // needed because FullPackResourcePack has a single-namespace filter; embedded packs can have any namespaces
            File embeddedDatapacksDir = new File(file, "datapacks");
            if (embeddedDatapacksDir.isDirectory()) {
                File[] embedded = embeddedDatapacksDir.listFiles(File::isDirectory);
                if (embedded != null) {
                    for (File ep : embedded) {
                        String epId = baseId + "_embedded_" + ep.getName().toLowerCase().replaceAll("[^a-z0-9_.]", "_");
                        Component epName = Component.literal("KoperEmbedded: " + name + "/" + ep.getName());
                        PackLocationInfo epInfo = new PackLocationInfo(epId, epName, PackSource.BUILT_IN, Optional.empty());
                        final Path epRoot = ep.toPath();
                        Pack.ResourcesSupplier epFactory = new Pack.ResourcesSupplier() {
                            @Override public PackResources openMetadata(PackLocationInfo i) { return new EmbeddedDatapackResources(i, epRoot); }
                            @Override public java.util.stream.Stream<PackResources> openResources(PackLocationInfo i, Pack.Metadata m) { return java.util.stream.Stream.of(openMetadata(i)); }
                        };
                        Pack epPack = Pack.readMetaAndCreate(epInfo, epFactory, PackType.SERVER_DATA,
                            new PackSelectionConfig(true, Pack.Position.TOP, true));
                        if (epPack != null) {
                            profileAdder.accept(epPack);
                            KoperLib.LOGGER.info("[ManualPackProvider] Registered embedded datapack: {}/{}", name, ep.getName());
                        }
                    }
                }
            }
        }
    }

    // serves standard MC datapack layout (data/{namespace}/{path}) from a directory root
    // no namespace filter — embedded datapacks can use any namespace (minecraft, veinminer, etc.)
    private static class EmbeddedDatapackResources implements PackResources {
        private final PackLocationInfo info;
        private final Path root;

        EmbeddedDatapackResources(PackLocationInfo info, Path root) {
            this.info = info;
            this.root = root;
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... segments) {
            Path p = root;
            for (String s : segments) p = p.resolve(s);
            final Path fp = p;
            return Files.exists(fp) ? () -> Files.newInputStream(fp) : null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            if (type != PackType.SERVER_DATA) return null;
            Path file = root.resolve("data").resolve(id.getNamespace()).resolve(id.getPath());
            return Files.exists(file) ? Stary263Wykrywacz.watch(type, id, info.id(), () -> Files.newInputStream(file)) : null;
        }

        @Override
        public void listResources(PackType type, String namespace, String prefix, PackResources.ResourceOutput output) {
            if (type != PackType.SERVER_DATA) return;
            Path nsDir = root.resolve("data").resolve(namespace);
            if (!Files.isDirectory(nsDir)) return;
            try (var walk = Files.walk(nsDir)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = nsDir.relativize(p).toString().replace('\\', '/');
                    if (!rel.startsWith(prefix)) return;
                    Identifier id = Identifier.fromNamespaceAndPath(namespace, rel);
                    if (id != null) output.accept(id, Stary263Wykrywacz.watch(type, id, info.id(), () -> Files.newInputStream(p)));
                });
            } catch (IOException e) {
                KoperLib.LOGGER.warn("[EmbeddedDatapack] listResources failed: {}", e.getMessage());
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            if (type != PackType.SERVER_DATA) return Set.of();
            Path dataDir = root.resolve("data");
            if (!Files.isDirectory(dataDir)) return Set.of();
            try (var stream = Files.list(dataDir)) {
                return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
            } catch (IOException e) { return Set.of(); }
        }

        @Override
        public <T> T getMetadataSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> type) throws IOException {
            IoSupplier<InputStream> mcmeta = getRootResource("pack.mcmeta");
            if (mcmeta != null) {
                try (InputStream is = mcmeta.get()) {
                    com.google.gson.JsonObject root = com.google.gson.JsonParser
                        .parseReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                    com.google.gson.JsonObject section = root.getAsJsonObject(type.name());
                    if (section != null)
                        return type.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, section).result().orElse(null);
                } catch (Exception ignored) {}
            }
            // no pack.mcmeta → synthesise one for the running version so MC accepts it
            return dzisiejszyMeta(type, "KoperLib Embedded Datapack");
        }

        @Override public PackLocationInfo location() { return info; }
        @Override public void close() {}
    }

    // a pack.mcmeta "pack" section for exactly the version that is running. since 1.21.9 a data pack
    // format above 81 needs min_format/max_format, a bare pack_format of 104 fails the codec and MC
    // drops the pack with "Missing metadata" (that is how every bedrock recipe, loot table and function went missing)
    @SuppressWarnings("unchecked")
    static <T> T dzisiejszyMeta(net.minecraft.server.packs.metadata.MetadataSectionType<T> type, String opis) {
        PackType jaki;
        if (type == net.minecraft.server.packs.metadata.pack.PackMetadataSection.CLIENT_TYPE) jaki = PackType.CLIENT_RESOURCES;
        else if (type == net.minecraft.server.packs.metadata.pack.PackMetadataSection.SERVER_TYPE
            || type == net.minecraft.server.packs.metadata.pack.PackMetadataSection.FALLBACK_TYPE) jaki = PackType.SERVER_DATA;
        else return null;
        var teraz = net.minecraft.SharedConstants.getCurrentVersion().packVersion(jaki);
        return (T) new net.minecraft.server.packs.metadata.pack.PackMetadataSection(Component.literal(opis),
            new net.minecraft.util.InclusiveRange<>(teraz));
    }

    /**
     * Disables a pack — delegates to FullPackLoader (the single source of truth).
     */
    public static boolean disablePack(String packName) {
        FullPackLoader.setDisabled(packName, true);
        return true;
    }

    /**
     * Enables a pack — delegates to FullPackLoader.
     */
    public static boolean enablePack(String packName) {
        FullPackLoader.setDisabled(packName, false);
        return true;
    }
}
