package com.koper.koper_lib.api.core;

import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.panama.KoperNativeLoader;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One independently loaded native library owned by one KoperLib module. */
public final class KoperModuleNative {
    private static final Linker LINKER = Linker.nativeLinker();

    private final String module;
    private final String library;
    private final SymbolLookup lookup;
    private final Map<String, MethodHandle> functions = new ConcurrentHashMap<>();

    private KoperModuleNative(String module, String library, Class<?> anchor) {
        this.module = module;
        this.library = library;
        SymbolLookup resolved = null;
        boolean loaded = false;
        try {
            Path path = KoperNativeLoader.loadModuleFromJar(module, library, anchor);
            if (path != null) {
                resolved = SymbolLookup.loaderLookup();
                loaded = true;
            }
        } catch (Throwable error) {
            KoperCore.LOGGER.warn("[{}/Native] bundled {} failed: {}", module, library, error.getMessage());
        }
        if (!loaded) {
            Path path = findLibrary(System.mapLibraryName(library));
            if (path != null) {
                try {
                    System.load(path.toAbsolutePath().toString());
                    resolved = SymbolLookup.loaderLookup();
                    loaded = true;
                } catch (Throwable error) {
                    KoperCore.LOGGER.error("[{}/Native] failed to load {}", module, path, error);
                }
            }
        }
        if (!loaded) {
            try {
                System.loadLibrary(library);
                resolved = SymbolLookup.loaderLookup();
            } catch (Throwable ignored) {
                KoperCore.LOGGER.warn("[{}/Native] {} unavailable; module fallback stays active", module, library);
            }
        }
        lookup = resolved;
    }

    public static KoperModuleNative load(String module, String library, Class<?> anchor) {
        return new KoperModuleNative(module, library, anchor);
    }

    public boolean loaded() {
        return lookup != null;
    }

    public MethodHandle function(String name, FunctionDescriptor descriptor) {
        if (lookup == null) return null;
        return functions.computeIfAbsent(name, ignored -> lookup.find(name)
            .map(symbol -> LINKER.downcallHandle(symbol, descriptor)).orElse(null));
    }

    private static Path findLibrary(String name) {
        for (String directory : System.getProperty("java.library.path", "")
            .split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        Path candidate = Path.of(System.getProperty("user.dir", ".")).resolve(name);
        return Files.exists(candidate) ? candidate : null;
    }
}
