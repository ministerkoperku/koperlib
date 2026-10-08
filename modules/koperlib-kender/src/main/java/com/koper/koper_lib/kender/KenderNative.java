package com.koper.koper_lib.kender;

import com.koper.koper_lib.api.core.KoperModuleNative;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

// the .so rides in THIS jar, so the anchor class has to sit here too — moved it out of core
public final class KenderNative {
    private static final String LIBRARY = "koperlib_engine";
    private static final KoperModuleNative NATIVE =
        KoperModuleNative.load("kender", LIBRARY, KenderNative.class);
    public static final boolean LOADED = NATIVE.loaded();

    private KenderNative() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    public static MethodHandle function(String name, FunctionDescriptor descriptor) {
        return NATIVE.function(name, descriptor);
    }
}
