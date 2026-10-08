package com.koper.koper_lib.api.core;

import com.google.gson.JsonObject;

// bedrock geo.json + animation.json + png -> .kodel bytes. fullpack's bedrock converter asks,
// kodel answers, neither imports the other. no kodel installed = null and the geo.json stays
public final class KodelBedrockBridge {

    public interface Converter {
        byte[] convert(JsonObject geometryFile, JsonObject animationFile, byte[] png, String name);
    }

    private static volatile Converter converter;

    private KodelBedrockBridge() {}

    public static void install(Converter value) {
        converter = value;
    }

    // kodel may init after fullpack, so it also answers as a "koperlib-kodel-bedrock" entrypoint we can pull ourselves
    private static Converter find() {
        Converter c = converter;
        if (c != null) return c;
        try {
            var found = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getEntrypoints("koperlib-kodel-bedrock", Converter.class);
            if (!found.isEmpty()) converter = c = found.get(0);
        } catch (Throwable noLoader) {
            // unit tests and other places with no fabric around, just no kodel then
        }
        return c;
    }

    public static boolean installed() {
        return find() != null;
    }

    public static byte[] convert(JsonObject geometryFile, JsonObject animationFile, byte[] png, String name) {
        Converter c = find();
        if (c == null) return null;
        try {
            return c.convert(geometryFile, animationFile, png, name);
        } catch (Throwable broke) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.warn("[Kodel] bedrock model {} did not convert: {}", name, broke.toString());
            return null;
        }
    }
}
