package com.koper.koper_lib.api.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.koper.koper_lib.coremod.KoperCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Small module-owned JSON config file. Core owns persistence and migration;
 * feature mods own the actual config classes and fields.
 */
public final class KoperConfigFile<T> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path LEGACY = FabricLoader.getInstance().getConfigDir()
        .resolve("koperlib/config.json");

    private final String moduleId;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final Path path;

    public KoperConfigFile(String moduleId, Class<T> type, Supplier<T> defaults) {
        if (moduleId == null || moduleId.isBlank())
            throw new IllegalArgumentException("config module id cannot be blank");
        this.moduleId = moduleId;
        this.type = type;
        this.defaults = defaults;
        this.path = FabricLoader.getInstance().getConfigDir()
            .resolve("koperlib").resolve(moduleId + ".json");
    }

    public T load() {
        T value = read(path);
        if (value == null && !path.equals(LEGACY)) {
            value = read(LEGACY);
            if (value != null)
                KoperCore.LOGGER.info("[Config] migrated '{}' fields from {}", moduleId, LEGACY);
        }
        if (value == null) value = defaults.get();
        save(value);
        return value;
    }

    /** Whether this module's file is on disk yet. */
    public boolean exists() {
        return Files.exists(path);
    }

    /** Takes over settings read from somewhere else, an older file, and writes them here. */
    public T adopt(T value) {
        if (value == null) value = defaults.get();
        KoperCore.LOGGER.info("[Config] '{}' takes over settings from an older file", moduleId);
        save(value);
        return value;
    }

    public void save(T value) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(value, type, writer);
            }
        } catch (Exception error) {
            KoperCore.LOGGER.warn("[Config] failed to save '{}' to {}", moduleId, path, error);
        }
    }

    private T read(Path source) {
        if (!Files.isRegularFile(source)) return null;
        try (Reader reader = Files.newBufferedReader(source)) {
            T value = GSON.fromJson(reader, type);
            if (value != null)
                KoperCore.LOGGER.info("[Config] loaded '{}' from {}", moduleId, source);
            return value;
        } catch (Exception error) {
            KoperCore.LOGGER.warn("[Config] failed to load '{}' from {}", moduleId, source, error);
            return null;
        }
    }
}
