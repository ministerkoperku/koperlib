package com.koper.koper_lib.api.core;

import com.koper.koper_lib.coremod.KoperCore;

import java.util.LinkedHashMap;
import java.util.Map;

// core coordinates config lifecycle, each mod still owns its fields and file format
public final class KoperConfigs {
    private record Section(Runnable load, Runnable save) {}
    private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();

    private KoperConfigs() {}

    public static synchronized void register(String moduleId, Runnable load, Runnable save) {
        if (moduleId == null || moduleId.isBlank() || load == null || save == null)
            throw new IllegalArgumentException("config section needs module id, loader and saver");
        SECTIONS.putIfAbsent(moduleId, new Section(load, save));
    }

    public static void loadAll() {
        snapshot().forEach((id, section) -> run(id, "load", section.load));
    }

    public static void saveAll() {
        snapshot().forEach((id, section) -> run(id, "save", section.save));
    }

    public static void reloadAll() {
        loadAll();
    }

    public static synchronized boolean registered(String moduleId) {
        return SECTIONS.containsKey(moduleId);
    }

    public static synchronized java.util.List<String> ids() {
        return java.util.List.copyOf(SECTIONS.keySet());
    }

    private static synchronized Map<String, Section> snapshot() {
        return new LinkedHashMap<>(SECTIONS);
    }

    private static void run(String id, String action, Runnable task) {
        try { task.run(); }
        catch (Throwable error) { KoperCore.LOGGER.error("[Config] {} failed for '{}'", action, id, error); }
    }
}
