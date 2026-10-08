package com.koper.koper_lib.kfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxGraph;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import com.koper.koper_lib.kfx.graph.KfxValueType;
import com.koper.koper_lib.kfx.runtime.KfxPlayRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stateful boundary for Lua-authored graphs and their live, externally allocated handles. */
public final class KfxScriptBridge {
    private static final Map<Long, Live> LIVE = new ConcurrentHashMap<>();

    private KfxScriptBridge() {}

    public static KfxGraph declare(String graphJson) {
        return KfxLuaGraphs.declare(JsonParser.parseString(graphJson), "lua:koper.kfx.graph");
    }

    public static long play(MinecraftServer server, long id, String graphJson, String optionsJson) {
        if (server == null || id <= 0L) throw new IllegalArgumentException("KFX Lua play needs a server and positive handle");
        KfxGraph declared = declare(graphJson);
        JsonObject options = parseObject(optionsJson, "KFX play options");
        KfxAnchor start = parseAnchor(options.has("start") ? options.get("start") : vectorJson(Vec3.ZERO));
        KfxAnchor end = parseAnchor(options.has("finish") ? options.get("finish")
            : options.has("end") ? options.get("end") : vectorJson(Vec3.ZERO));
        ServerLevel level = levelFor(server, start, end);
        Map<String, KfxResolvedValue> parameters = parameters(declared, options);
        long seed = options.has("seed") ? options.get("seed").getAsLong() : mixSeed(id, declared.id());
        KfxApi.play(level, id, new KfxPlayRequest(declared.id(), parameters, start, end, seed));
        LIVE.put(id, new Live(level, declared, seed, parameters, start, end));
        return id;
    }

    public static void set(long id, String name, String valueJson) {
        Live live = require(id);
        KfxGraph.Input input = live.graph().inputs().get(name);
        if (input == null) throw new IllegalArgumentException("KFX graph has no input '" + name + "'");
        Map<String, KfxResolvedValue> parameters = new LinkedHashMap<>(live.parameters());
        parameters.put(name, resolved(JsonParser.parseString(valueJson), input.type(), "input " + name));
        KfxApi.play(live.level(), id,
            new KfxPlayRequest(live.graph().id(), parameters, live.start(), live.end(), live.seed()));
        LIVE.put(id, new Live(live.level(), live.graph(), live.seed(), Map.copyOf(parameters), live.start(), live.end()));
    }

    public static void reanchor(long id, String startJson, String endJson) {
        Live live = require(id);
        KfxAnchor start = parseAnchor(startJson);
        KfxAnchor end = parseAnchor(endJson);
        com.koper.koper_lib.kfx.runtime.KfxHandle.server(live.level(), id).reanchor(start, end);
        LIVE.put(id, new Live(live.level(), live.graph(), live.seed(), live.parameters(), start, end));
    }

    public static void detach(long id) {
        Live live = require(id);
        com.koper.koper_lib.kfx.runtime.KfxHandle.server(live.level(), id).detach();
    }

    public static void forget(long id) {
        LIVE.remove(id);
    }

    public static KfxAnchor parseAnchor(String json) {
        return parseAnchor(JsonParser.parseString(json));
    }

    static KfxAnchor parseAnchor(JsonElement json) {
        if (json == null || json.isJsonNull()) return world(Vec3.ZERO, null, null);
        if (json.isJsonArray()) return world(vector(json, "anchor"), null, null);
        JsonObject object = json.getAsJsonObject();
        String type = string(object, "type", object.has("entity") || object.has("entity_id") ? "entity" : "world");
        KfxMissingPolicy missing = policy(string(object, "missing", "freeze"));
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "world", "point" -> world(
                object.has("position") ? vector(object.get("position"), "anchor.position") : vector(object, "anchor"),
                object.has("normal") ? vector(object.get("normal"), "anchor.normal") : null,
                missing);
            case "entity", "socket" -> entity(object, missing);
            case "bone" -> {
                KfxAnchor.Entity fallback = entity(object, missing);
                yield new KfxAnchor.Bone(entityId(object), object.get("bone").getAsString(), fallback, missing);
            }
            case "between" -> new KfxAnchor.Between(
                parseAnchor(object.get("start")), parseAnchor(object.get("end")),
                object.has("mix") ? object.get("mix").getAsFloat() : 0.5f, missing);
            default -> throw new IllegalArgumentException("unknown KFX anchor type '" + type + "'");
        };
    }

    private static KfxAnchor.Entity entity(JsonObject object, KfxMissingPolicy missing) {
        KfxSocket socket = KfxSocket.valueOf(string(object, "socket", "center").toUpperCase(java.util.Locale.ROOT));
        Vec3 offset = object.has("offset") ? vector(object.get("offset"), "anchor.offset") : Vec3.ZERO;
        return new KfxAnchor.Entity(entityId(object), socket, offset, missing);
    }

    private static int entityId(JsonObject object) {
        JsonElement value = object.has("entity") ? object.get("entity") : object.get("entity_id");
        if (value == null) throw new IllegalArgumentException("entity KFX anchor needs entity id");
        return value.getAsInt();
    }

    private static KfxAnchor.World world(Vec3 position, Vec3 normal, KfxMissingPolicy missing) {
        return new KfxAnchor.World(position, normal == null ? new Vec3(0, 1, 0) : normal,
            missing == null ? KfxMissingPolicy.FREEZE : missing);
    }

    private static Vec3 vector(JsonElement json, String path) {
        if (json == null) throw new IllegalArgumentException(path + " is missing");
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            if (array.size() != 3) throw new IllegalArgumentException(path + " needs exactly 3 numbers");
            return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
        }
        JsonObject object = json.getAsJsonObject();
        return new Vec3(object.get("x").getAsDouble(), object.get("y").getAsDouble(), object.get("z").getAsDouble());
    }

    private static JsonArray vectorJson(Vec3 value) {
        JsonArray array = new JsonArray();
        array.add(value.x); array.add(value.y); array.add(value.z);
        return array;
    }

    private static ServerLevel levelFor(MinecraftServer server, KfxAnchor start, KfxAnchor end) {
        int entity = entityIn(start);
        if (entity < 0) entity = entityIn(end);
        if (entity >= 0) {
            for (ServerLevel level : server.getAllLevels()) if (level.getEntity(entity) != null) return level;
        }
        return server.overworld();
    }

    private static int entityIn(KfxAnchor anchor) {
        if (anchor instanceof KfxAnchor.Entity entity) return entity.entityId();
        if (anchor instanceof KfxAnchor.Bone bone) return bone.entityId();
        return -1;
    }

    private static Map<String, KfxResolvedValue> parameters(KfxGraph graph, JsonObject options) {
        if (!options.has("parameters") && !options.has("params")) return Map.of();
        JsonObject raw = (options.has("parameters") ? options.get("parameters") : options.get("params")).getAsJsonObject();
        Map<String, KfxResolvedValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            KfxGraph.Input input = graph.inputs().get(entry.getKey());
            if (input == null) throw new IllegalArgumentException("KFX graph has no input '" + entry.getKey() + "'");
            result.put(entry.getKey(), resolved(entry.getValue(), input.type(), "input " + entry.getKey()));
        }
        return Map.copyOf(result);
    }

    private static KfxResolvedValue resolved(JsonElement value, KfxValueType type, String path) {
        try {
            return switch (type) {
                case NUMBER -> KfxResolvedValue.number(value.getAsDouble());
                case INTEGER -> KfxResolvedValue.integer(value.getAsInt());
                case TEXT -> KfxResolvedValue.text(value.getAsString());
                case COLOR -> KfxResolvedValue.color(color(value.getAsString()));
            };
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(path + " does not match " + type.name().toLowerCase(), error);
        }
    }

    private static int color(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() == 6) return (int)(0xff000000L | Long.parseLong(hex, 16));
        if (hex.length() == 8) return (int)Long.parseLong(hex, 16);
        throw new IllegalArgumentException("expected #RRGGBB or #AARRGGBB color");
    }

    private static JsonObject parseObject(String raw, String label) {
        try { return JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IllegalArgumentException(label + " must be a JSON object", error); }
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) ? object.get(name).getAsString() : fallback;
    }

    private static KfxMissingPolicy policy(String raw) {
        return KfxMissingPolicy.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
    }

    private static Live require(long id) {
        Live live = LIVE.get(id);
        if (live == null) throw new IllegalArgumentException("unknown live KFX handle " + id);
        return live;
    }

    private static long mixSeed(long id, String graph) {
        return id * 0x9E3779B97F4A7C15L ^ graph.hashCode();
    }

    private record Live(ServerLevel level, KfxGraph graph, long seed,
                        Map<String, KfxResolvedValue> parameters, KfxAnchor start, KfxAnchor end) {}
}
