package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperModuleNative;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

/**
 * Anchor for the bundled native Kodel parser. The .so rides in THIS jar, so the
 * anchor class has to sit here too, exactly like KenderNative.
 */
public final class KodelNative {
    private static final String LIBRARY = "koperlib_kodel_engine";
    private static final KoperModuleNative NATIVE =
        KoperModuleNative.load("kodel", LIBRARY, KodelNative.class);
    public static final boolean LOADED = NATIVE.loaded();

    private KodelNative() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    public static MethodHandle function(String name, FunctionDescriptor descriptor) {
        return NATIVE.function(name, descriptor);
    }
}