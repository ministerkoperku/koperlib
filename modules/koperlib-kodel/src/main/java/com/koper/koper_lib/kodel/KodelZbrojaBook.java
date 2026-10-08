package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.api.core.KoperPackSources;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// which armour item wears which .kodel. packs already say this in their item json, the
// same keys fullpack item json always used, so this reads those rather than asking for a second spelling
public final class KodelZbrojaBook {

    public record Zbroja(String itemId, String slot, String model, Identifier texture,
                         float scale, float[] offset, float[] rotate,
                         Map<String, String> bones, Set<String> renderBones,
                         Map<String, float[]> placement, int tint, boolean dyeable,
                         String animation, float itemScale, float[] itemOffset) {}

    /** Armour pieces with no armor_offset sit where the old geo armour put them. */
    public static final float[] DEFAULT_OFFSET = {0f, 1.5f, 0f};

    private static final Map<String, Zbroja> BY_ID = new ConcurrentHashMap<>();
    private static volatile boolean scanned;
    // fullpack knows every item it registered, under its real id; when it is there it is the source
    private static volatile java.util.function.Supplier<java.util.Collection<Zbroja>> source;

    private KodelZbrojaBook() {}

    public static void source(java.util.function.Supplier<java.util.Collection<Zbroja>> from) {
        source = from;
        clear();
    }

    public static void clear() {
        BY_ID.clear();
        scanned = false;
    }

    public static Zbroja of(String itemId) {
        if (!scanned) scan();
        return itemId == null ? null : BY_ID.get(itemId);
    }

    public static List<String> ids() {
        if (!scanned) scan();
        return BY_ID.keySet().stream().sorted().toList();
    }

    private static synchronized void scan() {
        if (scanned) return;
        BY_ID.clear();
        var from = source;
        if (from != null) {
            for (Zbroja z : from.get()) BY_ID.put(z.itemId(), z);
            scanned = true;
            return;
        }
        for (KoperPackSources.Source source : KoperPackSources.all()) {
            Path root = source.root();
            if (root == null || !Files.isDirectory(root)) continue;
            try (var packs = Files.list(root)) {
                for (Path pack : packs.toList()) {
                    if (!Files.isDirectory(pack)) continue;
                    String packName = pack.getFileName().toString();
                    if (packName.startsWith(".") || !source.isEnabled(packName)) continue;
                    readDir(pack.resolve("items"), packName);
                    readDir(pack.resolve("koperlib").resolve("items"), packName);
                }
            } catch (IOException ignored) {
            }
        }
        scanned = true;
    }

    private static void readDir(Path dir, String packName) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                try {
                    read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject(), fileName.substring(0, fileName.length() - 5), packName);
                } catch (RuntimeException | IOException broken) {
                    // one bad item json costs only itself, but it says so
                    com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kodel] armour json {} does not read: {}", file, broken.toString());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void read(JsonObject item, String fileName, String packName) {
        String model = str(item, "model", null);
        String slot = str(item, "slot", slotFromType(str(item, "type", "")));
        if (model == null || slot == null) return;
        if (KodelBook.get(model) == null) return;

        String itemId = str(item, "id", null);
        if (itemId == null) itemId = packName + ":" + fileName;
        String ns = itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : packName;

        Map<String, String> bones = strMap(item.get("armor_bones"));
        Set<String> render = null;
        if (item.has("render_bones") && item.get("render_bones").isJsonArray()) {
            render = new HashSet<>();
            for (JsonElement e : item.getAsJsonArray("render_bones")) render.add(e.getAsString());
            if (render.isEmpty()) render = null;
        }
        Map<String, float[]> placement = new HashMap<>();
        if (item.has("bone_placement") && item.get("bone_placement").isJsonObject()) {
            JsonObject bp = item.getAsJsonObject("bone_placement");
            for (String key : bp.keySet()) placement.put(key, vec3(bp.get(key)));
        }

        BY_ID.put(itemId, new Zbroja(itemId, slot, model,
            texture(ns, str(item, "armor_texture", model)),
            num(item, "armor_scale", 1f),
            item.has("armor_offset") ? vec3(item.get("armor_offset")) : DEFAULT_OFFSET.clone(),
            vec3(item.get("armor_rotate")),
            bones, render, placement.isEmpty() ? null : placement,
            (int) num(item, "tint", 0xFFFFFFFF),
            item.has("dyeable") && item.get("dyeable").getAsBoolean(),
            str(item, "armor_animation", null),
            num(item, "item_scale", 0.9f),
            item.has("item_offset") ? vec3(item.get("item_offset")) : new float[] {0.5f, 0f, 0.5f}));
    }

    private static String slotFromType(String type) {
        return switch (type) {
            case "helmet" -> "head";
            case "chestplate" -> "chest";
            case "leggings" -> "legs";
            case "boots" -> "feet";
            default -> null;
        };
    }

    /** "foo" is textures/entity/foo.png, "armor/foo" is textures/armor/foo.png, a namespace may lead. */
    public static Identifier texture(String ns, String raw) {
        String t = raw.endsWith(".png") ? raw.substring(0, raw.length() - 4) : raw;
        if (t.contains(":")) {
            int c = t.indexOf(':');
            ns = t.substring(0, c);
            t = t.substring(c + 1);
        }
        if (!t.startsWith("textures/")) t = t.contains("/") ? "textures/" + t : "textures/entity/" + t;
        return Identifier.fromNamespaceAndPath(ns, t + ".png");
    }

    private static Map<String, String> strMap(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        Map<String, String> out = new HashMap<>();
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) out.put(key, o.get(key).getAsString());
        return out.isEmpty() ? null : out;
    }

    private static float[] vec3(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new float[] {0, 0, 0};
        JsonArray a = e.getAsJsonArray();
        float[] out = new float[3];
        for (int i = 0; i < 3 && i < a.size(); i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static float num(JsonObject o, String key, float def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : def;
    }
}
