package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Opens a .kodel ZIP and pulls out the manifest, model, anims and texture
 * entries. Kept data-first: the returned {@link Loaded} can be handed straight
 * to {@link KodelSampler} or uploaded to Kender.
 */
public final class KodelLoader {
    public record Loaded(JsonObject manifest, KodelModel model, List<KodelAnimation> animations,
                         byte[] texture) {
        public KodelAnimation animation(String name) {
            if (animations == null) return null;
            for (KodelAnimation a : animations) {
                if (a.name.equals(name)) return a;
            }
            return null;
        }
    }

    private KodelLoader() {}

    public static Loaded load(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return read(zip);
        }
    }

    public static Loaded read(ZipFile zip) throws IOException {
        return parse(name -> {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            try {
                return readAll(zip.getInputStream(entry));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Reads a whole .kodel archive already in memory (mod resources, network, tests). */
    public static Loaded read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), readAll(zip));
            }
        }
        return parse(entries::get);
    }

    private static Loaded parse(Function<String, byte[]> entry) {
        byte[] manifestBytes = entry.apply("manifest.json");
        if (manifestBytes == null) throw new KodelFormat.Corruption("kodel archive has no manifest.json");
        String manifestRaw = new String(manifestBytes, StandardCharsets.UTF_8);
        JsonObject manifest = JsonParser.parseString(manifestRaw).getAsJsonObject();

        String modelEntry = manifest.has("model") && manifest.get("model").isJsonPrimitive()
            ? manifest.get("model").getAsString()
            : "model.bin";
        KodelModel model = KodelModel.read(require(entry, modelEntry));
        // frame is optional. used to be read with a hard 64 fallback that clobbered
        // whatever model.bin said, so anything on a 128px texture got rescaled to shit
        JsonObject frame = manifest.has("frame") && manifest.get("frame").isJsonObject()
            ? manifest.getAsJsonObject("frame")
            : null;
        model.texWidth = atoi(frame, "width", model.texWidth);
        model.texHeight = atoi(frame, "height", model.texHeight);

        List<KodelAnimation> animations = null;
        if (manifest.has("animation") && !manifest.get("animation").isJsonNull()) {
            byte[] animBytes = entry.apply(manifest.get("animation").getAsString());
            if (animBytes != null) {
                animations = KodelAnimation.readAll(animBytes);
            }
        }

        byte[] texture = null;
        if (manifest.has("textures") && manifest.getAsJsonArray("textures").size() > 0) {
            byte[] texBytes = entry.apply(manifest.getAsJsonArray("textures").get(0).getAsString());
            if (texBytes != null) texture = texBytes;
        }
        return new Loaded(manifest, model, animations, texture);
    }

    private static byte[] require(Function<String, byte[]> entry, String name) {
        byte[] data = entry.apply(name);
        if (data == null) throw new KodelFormat.Corruption("kodel archive has no " + name);
        return data;
    }

    private static int atoi(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}