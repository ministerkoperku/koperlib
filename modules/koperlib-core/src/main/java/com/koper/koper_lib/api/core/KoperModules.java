package com.koper.koper_lib.api.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// shared module truth, mods register themselves here instead of core importing their initializer
public final class KoperModules {
    public enum Environment { COMMON, CLIENT, SERVER }
    public enum State { LOADED, DISABLED, FAILED }

    public record ModuleInfo(
        String id,
        String version,
        Environment environment,
        State state,
        Set<String> capabilities,
        String note
    ) {
        public ModuleInfo {
            capabilities = Set.copyOf(capabilities);
            note = note == null ? "" : note;
        }
    }

    private static final Map<String, MutableModule> MODULES = new ConcurrentHashMap<>();

    private KoperModules() {}

    public static void register(String id, String version, Environment environment, String... capabilities) {
        String clean = clean(id);
        Set<String> caps = new LinkedHashSet<>();
        if (capabilities != null) {
            for (String capability : capabilities) {
                if (capability != null && !capability.isBlank()) caps.add(clean(capability));
            }
        }
        MutableModule added = new MutableModule(clean, version, environment, caps);
        MutableModule old = MODULES.putIfAbsent(clean, added);
        if (old != null) old.capabilities.addAll(caps);
    }

    public static void capability(String moduleId, String capability) {
        MutableModule module = require(moduleId);
        module.capabilities.add(clean(capability));
    }

    public static void state(String moduleId, State state, String note) {
        MutableModule module = require(moduleId);
        module.state = state;
        module.note = note == null ? "" : note;
    }

    public static boolean present(String moduleId) {
        MutableModule module = MODULES.get(clean(moduleId));
        return module != null && module.state == State.LOADED;
    }

    public static boolean has(String capability) {
        String wanted = clean(capability);
        return MODULES.values().stream()
            .anyMatch(module -> module.state == State.LOADED && module.capabilities.contains(wanted));
    }

    public static ModuleInfo get(String moduleId) {
        MutableModule module = MODULES.get(clean(moduleId));
        return module == null ? null : module.snapshot();
    }

    public static List<ModuleInfo> snapshot() {
        return MODULES.values().stream()
            .map(MutableModule::snapshot)
            .sorted(Comparator.comparing(ModuleInfo::id))
            .toList();
    }

    private static MutableModule require(String id) {
        MutableModule module = MODULES.get(clean(id));
        if (module == null) throw new IllegalStateException("koper module is not registered: " + id);
        return module;
    }

    private static String clean(String value) {
        if (value == null) throw new IllegalArgumentException("module id is null");
        String clean = value.trim().toLowerCase();
        if (clean.isEmpty() || !clean.matches("[a-z0-9_.-]+"))
            throw new IllegalArgumentException("bad koper module id: " + value);
        return clean;
    }

    private static final class MutableModule {
        final String id;
        final String version;
        final Environment environment;
        final Set<String> capabilities = ConcurrentHashMap.newKeySet();
        volatile State state = State.LOADED;
        volatile String note = "";

        MutableModule(String id, String version, Environment environment, Collection<String> capabilities) {
            this.id = id;
            this.version = version == null || version.isBlank() ? "unknown" : version;
            this.environment = environment == null ? Environment.COMMON : environment;
            this.capabilities.addAll(capabilities);
        }

        ModuleInfo snapshot() {
            return new ModuleInfo(id, version, environment, state, capabilities, note);
        }
    }
}
