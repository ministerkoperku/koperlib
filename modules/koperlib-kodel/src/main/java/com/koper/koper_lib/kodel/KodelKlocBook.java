package com.koper.koper_lib.kodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.api.core.KoperPackSources;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Quaternionf;

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

// which block draws which .kodel. packs already describe this in the "kender" object
// of their block json, so kodel reads the same thing rather than inventing a second
// way to say it. a block whose model has no .kodel is simply not in here and whatever
// drew it before keeps drawing it
public final class KodelKlocBook {

    public record Wiazanie(String blockId, String model, Identifier texture, float scale,
                           float[] offset, String rotateBy, String rotateBase,
                           Set<String> bones, String renderType, int tint) {}

    private static final Map<String, Wiazanie> BY_ID = new ConcurrentHashMap<>();
    private static volatile boolean scanned;

    private KodelKlocBook() {}

    public static void clear() {
        BY_ID.clear();
        scanned = false;
    }

    public static Wiazanie of(BlockState state) {
        if (state == null) return null;
        return of(net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(state.getBlock()).toString());
    }

    public static Wiazanie of(String blockId) {
        if (!scanned) scan();
        return blockId == null ? null : BY_ID.get(blockId);
    }

    public static int size() {
        if (!scanned) scan();
        return BY_ID.size();
    }

    /// the turn from the facing the model was authored for to the one the state says.
    /// null when this binding does not turn, or is already authored that way
    public static Quaternionf facingQuat(Wiazanie bind, BlockState state) {
        if (bind == null || state == null || bind.rotateBy() == null || bind.rotateBy().isEmpty()) return null;
        Property<?> prop = state.getBlock().getStateDefinition().getProperty(bind.rotateBy());
        if (prop == null) return null;
        Object value = state.getValue(prop);
        if (!(value instanceof Direction dir)) return null;
        if (dir.getSerializedName().equals(bind.rotateBase())) return null;
        // spelled out rather than Direction.byName, which answers null for up and down
        // and leaves a floor mounted model spinning like it was authored facing north
        return dirQuat(dir).mul(dirQuat(baseDir(bind.rotateBase())).conjugate());
    }

    private static Direction baseDir(String name) {
        if (name == null) return Direction.NORTH;
        return switch (name) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    // rotationY(+90) sends a north facing front toward west, right handed, Y up
    private static Quaternionf dirQuat(Direction d) {
        return switch (d) {
            case NORTH -> new Quaternionf();
            case WEST -> new Quaternionf().rotationY((float) Math.toRadians(90));
            case SOUTH -> new Quaternionf().rotationY((float) Math.toRadians(180));
            case EAST -> new Quaternionf().rotationY((float) Math.toRadians(270));
            case UP -> new Quaternionf().rotationX((float) Math.toRadians(90));
            case DOWN -> new Quaternionf().rotationX((float) Math.toRadians(-90));
        };
    }

    private static synchronized void scan() {
        if (scanned) return;
        BY_ID.clear();
        for (KoperPackSources.Source source : KoperPackSources.all()) {
            Path root = source.root();
            if (root == null || !Files.isDirectory(root)) continue;
            try (var packs = Files.list(root)) {
                for (Path pack : packs.toList()) {
                    if (!Files.isDirectory(pack)) continue;
                    String packName = pack.getFileName().toString();
                    if (packName.startsWith(".") || !source.isEnabled(packName)) continue;
                    // flat layout and the older nested one both still ship
                    readDir(pack.resolve("blocks"));
                    readDir(pack.resolve("koperlib").resolve("blocks"));
                }
            } catch (IOException ignored) {
            }
        }
        scanned = true;
    }

    private static void readDir(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".json")) continue;
                try {
                    read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject());
                } catch (RuntimeException | IOException broken) {
                    // one bad block json must not cost the pack every other block
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void read(JsonObject block) {
        if (!block.has("id") || !block.has("kender") || !block.get("kender").isJsonObject()) return;
        String blockId = block.get("id").getAsString();
        JsonObject kender = block.getAsJsonObject("kender");
        String model = str(kender, "model", null);
        if (model == null || KodelBook.get(model) == null) return; // no kodel, not ours

        String ns = blockId.contains(":") ? blockId.substring(0, blockId.indexOf(':')) : "minecraft";
        Identifier texture = texture(ns, str(kender, "texture", model));
        float[] offset = vec3(kender.get("offset"));
        Set<String> bones = null;
        if (kender.has("bones") && kender.get("bones").isJsonArray()) {
            bones = new HashSet<>();
            for (JsonElement e : kender.getAsJsonArray("bones")) bones.add(e.getAsString());
            if (bones.isEmpty()) bones = null;
        }
        // same default as kgecko: a facing block turns unless the json says "rotate_by": false.
        // this was "" and the engine and seat never followed R on a jar with kodel in it
        String koperTurn = "facing";
        JsonElement rb = kender.get("rotate_by");
        if (rb != null && !rb.isJsonNull()) {
            koperTurn = rb.isJsonPrimitive() && rb.getAsJsonPrimitive().isBoolean()
                ? (rb.getAsBoolean() ? "facing" : "")
                : rb.getAsString().toLowerCase();
            if (koperTurn.equals("horizontal_facing")) koperTurn = "facing";
        }
        BY_ID.put(blockId, new Wiazanie(blockId, model, texture,
            num(kender, "scale", 1f), offset,
            koperTurn, str(kender, "rotate_by_base", "north").toLowerCase(),
            bones, str(kender, "render_type", "cutout"),
            (int) num(kender, "tint", 0xFFFFFFFF)));
    }

    private static Identifier texture(String ns, String raw) {
        String t = raw.endsWith(".png") ? raw.substring(0, raw.length() - 4) : raw;
        if (t.contains(":")) {
            int c = t.indexOf(':');
            ns = t.substring(0, c);
            t = t.substring(c + 1);
        }
        return Identifier.fromNamespaceAndPath(ns, (t.startsWith("textures/") ? t : "textures/" + t) + ".png");
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

    /// every block id the enabled packs bind to a kodel, for commands and debugging
    public static List<String> ids() {
        if (!scanned) scan();
        return BY_ID.keySet().stream().sorted().toList();
    }

    static Map<String, Wiazanie> all() {
        if (!scanned) scan();
        return new HashMap<>(BY_ID);
    }
}
