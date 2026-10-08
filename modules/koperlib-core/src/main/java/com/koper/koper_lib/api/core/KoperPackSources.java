package com.koper.koper_lib.api.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Optional content roots exposed to feature mods without making them depend on Fullpack. */
public final class KoperPackSources {
    public record Source(String owner, Path root, Predicate<String> enabled) {
        public boolean isEnabled(String pack) { return enabled == null || enabled.test(pack); }
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private KoperPackSources() {}

    public static void register(String owner, Path root, Predicate<String> enabled) {
        if (owner == null || root == null) throw new IllegalArgumentException("pack source owner/root missing");
        SOURCES.removeIf(source -> source.owner().equals(owner));
        SOURCES.add(new Source(owner, root, enabled));
    }

    public static List<Source> all() { return List.copyOf(SOURCES); }

    // pack priority, 0 = top (owns what packs both define). the fullpack module plugs its pack_order in
    private static volatile ToIntFunction<String> priority = pack -> Integer.MAX_VALUE;

    public static void setPriority(ToIntFunction<String> rank) { priority = rank == null ? pack -> Integer.MAX_VALUE : rank; }

    public static int priority(String pack) { return priority.applyAsInt(pack); }
}
