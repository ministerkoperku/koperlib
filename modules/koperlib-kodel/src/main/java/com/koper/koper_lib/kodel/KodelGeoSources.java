package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.coremod.KoperCore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock geometry and animation files a fullpack ships next to its content
 * ({@code models/} and {@code geo/}), by base name: {@code kapoka.geo.json} and
 * {@code kapoka.animation.json} are both "kapoka".
 *
 * <p>The book asks here when no {@code .kodel} answers to a name, and converts on first use. A
 * packed {@code .kodel} always wins, so converting a model by hand changes nothing for its users.
 */
public final class KodelGeoSources {
    private KodelGeoSources() {}

    private static final Map<String, File> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, File> ANIMATIONS = new ConcurrentHashMap<>();

    public static void model(String name, File file) {
        MODELS.put(name, file);
        KodelBook.forget(name);
        KodelPhysicsShapes.invalidate(name);
    }

    public static void animation(String name, File file) {
        ANIMATIONS.put(name, file);
        KodelBook.forget(name);
    }

    public static void clear() {
        MODELS.clear();
        ANIMATIONS.clear();
    }

    public static boolean has(String name) {
        return MODELS.containsKey(name);
    }

    public static java.util.Set<String> names() {
        return new java.util.TreeSet<>(MODELS.keySet());
    }

    /** Null when no fullpack registered a geo file of that name. Throws when one did and it does not convert. */
    static KodelLoader.Loaded load(String name) throws IOException {
        File geoFile = MODELS.get(name);
        if (geoFile == null) return null;
        JsonObject geo = read(geoFile);
        var geos = geo.getAsJsonArray("minecraft:geometry");
        if (geos == null || geos.isEmpty())
            throw new IOException(geoFile + " has no minecraft:geometry");
        KodelModel model = KodelConverters.geometry(geos.get(0).getAsJsonObject());
        if (model.bones.isEmpty())
            throw new IOException(geoFile + " has no bones");
        File animFile = ANIMATIONS.get(name);
        List<KodelAnimation> clips = List.of();
        if (animFile != null) {
            JsonObject anim = read(animFile);
            if (anim.has("animations")) clips = KodelConverters.animations(anim);
        }
        // the texture comes from the binding (block json, entity json), not from the geo file
        return KodelLoader.read(new java.io.ByteArrayInputStream(KodelPackager.pack(model, clips, null, name)));
    }

    private static JsonObject read(File file) throws IOException {
        String raw = Files.readString(file.toPath());
        if (file.getName().endsWith(".hjson")) raw = KodelBlockBook.hjsonLite(raw);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    static void fail(String name, Throwable e) {
        KoperCore.LOGGER.error("[kodel] model '{}' does not load, nothing will draw it: {}", name, e.toString());
    }
}
