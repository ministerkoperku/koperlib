package com.koper.koper_lib.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// parses pack.kopermeta (or fallback fullpack.json / pack.mcmeta) from a pack directory
public class KoperMeta {
    private static final Gson GSON = new Gson();

    public final String packFormat;
    public final String name;
    public final String namespace; // explicit namespace override (null = use folder name)
    public final String description;
    public final String author;
    public final String version;
    public final String koperlibMin;
    public final boolean isLegacy; // true if loaded from pack.mcmeta
    public final List<String> requiresPacks;  // pack folder names or namespaces this pack needs
    public final List<String> requiresMods;   // fabric mod IDs this pack needs
    public final List<String> downloadLinks;  // optional HTTPS pages/files, shown only for manual use

    // set by FullPackLoader after loading; points to the pack dir on disk — used by dev watcher
    public Path sourcePath;

    private KoperMeta(String packFormat, String name, String namespace, String description,
                      String author, String version, String koperlibMin, boolean isLegacy,
                      List<String> requiresPacks, List<String> requiresMods, List<String> downloadLinks) {
        this.packFormat = packFormat;
        this.name = name;
        this.namespace = namespace;
        this.description = description;
        this.author = author;
        this.version = version;
        this.koperlibMin = koperlibMin;
        this.isLegacy = isLegacy;
        this.requiresPacks = Collections.unmodifiableList(requiresPacks);
        this.requiresMods  = Collections.unmodifiableList(requiresMods);
        this.downloadLinks = Collections.unmodifiableList(downloadLinks);
    }

    public static KoperMeta load(File packDir) {
        String dirName = packDir.getName();

        // New format
        File koperMeta = new File(packDir, "pack.kopermeta");
        if (koperMeta.exists()) return parseKoperMeta(koperMeta, dirName);

        // Original docs format: fullpack.json
        File fullpackJson = new File(packDir, "fullpack.json");
        if (fullpackJson.exists()) return parseFullpackJson(fullpackJson, dirName);

        // Legacy fallback
        File mcMeta = new File(packDir, "pack.mcmeta");
        if (mcMeta.exists()) return parseMcMeta(mcMeta, dirName);

        return null;
    }

    private static KoperMeta parseFullpackJson(File file, String dirName) {
        try (FileReader reader = new FileReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return null;

            String name    = str(json, "name",        dirName);
            String ns      = json.has("id") ? json.get("id").getAsString() : null;
            String desc    = str(json, "description", "");
            String author  = json.has("authors") && json.get("authors").isJsonArray()
                           ? json.get("authors").getAsJsonArray().get(0).getAsString()
                           : str(json, "author", "");
            String version = str(json, "version", "1.0.0");
            String minVer  = str(json, "koperlib",   "");

            return new KoperMeta("K1", name, ns, desc, author, version, minVer, false,
                List.of(), List.of(), strListEither(json, "download_links", "downloads"));
        } catch (Exception e) {
            KoperLib.LOGGER.warn("Failed to parse fullpack.json in {}: {}", dirName, e.getMessage());
            return null;
        }
    }

    private static KoperMeta parseKoperMeta(File file, String dirName) {
        try (FileReader reader = new FileReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return null;

            String format = str(json, "pack_format", "K1");
            String name = str(json, "name", dirName);
            String ns = json.has("namespace") ? json.get("namespace").getAsString() : null;
            String desc = str(json, "description", "");
            String author = str(json, "author", "");
            String version = str(json, "version", "1.0.0");
            String minVer = str(json, "koperlib_min", "");
            List<String> reqPacks = strList(json, "requires_packs");
            List<String> reqMods  = strList(json, "requires_mods");
            List<String> links = strListEither(json, "download_links", "downloads");

            return new KoperMeta(format, name, ns, desc, author, version, minVer, false, reqPacks, reqMods, links);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("Failed to parse pack.kopermeta in {}: {}", dirName, e.getMessage());
            return null;
        }
    }

    private static KoperMeta parseMcMeta(File file, String dirName) {
        try (FileReader reader = new FileReader(file)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null || !json.has("pack")) return null;

            JsonObject pack = json.getAsJsonObject("pack");
            String desc = str(pack, "description", "");
            // Legacy mcmeta can also have koperlib.namespace in a custom section
            String ns = null;
            if (json.has("koperlib") && json.get("koperlib").isJsonObject()) {
                JsonObject kl = json.getAsJsonObject("koperlib");
                if (kl.has("namespace")) ns = kl.get("namespace").getAsString();
            }

            return new KoperMeta("legacy", dirName, ns, desc, "", "1.0.0", "", true, List.of(), List.of(), List.of());
        } catch (Exception e) {
            KoperLib.LOGGER.warn("Failed to parse pack.mcmeta in {}: {}", dirName, e.getMessage());
            return null;
        }
    }

    private static String str(JsonObject j, String key, String def) {
        return j.has(key) ? j.get(key).getAsString() : def;
    }

    private static List<String> strList(JsonObject j, String key) {
        if (!j.has(key) || !j.get(key).isJsonArray()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (var el : j.getAsJsonArray(key)) out.add(el.getAsString());
        return out;
    }

    private static List<String> strListEither(JsonObject json, String primary, String fallback) {
        List<String> values = strList(json, primary);
        if (!values.isEmpty()) return values;
        values = strList(json, fallback);
        if (!values.isEmpty()) return values;
        if (json.has("download") && json.get("download").isJsonPrimitive())
            return List.of(json.get("download").getAsString());
        return List.of();
    }

    public String getEffectiveNamespace(String folderName) {
        return namespace != null && !namespace.isEmpty() ? namespace : folderName.toLowerCase();
    }

    @Override
    public String toString() {
        return name + " v" + version + " [" + packFormat + "]" + (isLegacy ? " (legacy)" : "");
    }
}
