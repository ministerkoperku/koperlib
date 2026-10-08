package com.koper.koper_lib.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.LuaModuleContext;
import com.koper.koper_lib.scripting.LuaAddonRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// koper.data used to be a plain table inside the lua vm: it looked persistent, survived nothing,
// and the source comment called it a simulation. this is the real one, saved next to the world.
//
//   koper.data.set("boss_stage", 3)
//   local s = koper.data.get("boss_stage", 0)
//   koper.data.remove("boss_stage")
//
// one store per world, shared by every pack. prefix your keys with your namespace.
public final class KoperWorldVault {

    private KoperWorldVault() {}

    private static final Map<String, String> STORE = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static volatile boolean dirty;

    public static void register() {
        LuaAddonRegistry.register("data", m -> m
            .function("set", KoperWorldVault::set)
            .function("get", KoperWorldVault::get)
            .function("remove", KoperWorldVault::remove)
            .function("has", KoperWorldVault::has)
            .function("all", KoperWorldVault::all)
        );
    }

    // ── lua surface ───────────────────────────────────────────────────────────

    private static JsonElement set(LuaModuleContext ctx, JsonArray a) {
        String key = str(a, 0);
        if (key.isEmpty()) return new JsonPrimitive(false);
        STORE.put(key, KoperSoulCodec.encode(at(a, 1)));
        dirty = true;
        return new JsonPrimitive(true);
    }

    private static JsonElement get(LuaModuleContext ctx, JsonArray a) {
        String raw = STORE.get(str(a, 0));
        if (raw == null) return def(a, 1);
        JsonElement v = KoperSoulCodec.decode(raw);
        return v == null ? def(a, 1) : v;
    }

    private static JsonElement remove(LuaModuleContext ctx, JsonArray a) {
        if (STORE.remove(str(a, 0)) != null) dirty = true;
        return new JsonPrimitive(true);
    }

    private static JsonElement has(LuaModuleContext ctx, JsonArray a) {
        return new JsonPrimitive(STORE.containsKey(str(a, 0)));
    }

    // all() or all("prefix") — with a prefix the returned keys have it stripped
    private static JsonElement all(LuaModuleContext ctx, JsonArray a) {
        JsonObject out = new JsonObject();
        String prefix = str(a, 0);
        for (var e : STORE.entrySet()) {
            String k = e.getKey();
            if (!prefix.isEmpty()) {
                if (!k.startsWith(prefix)) continue;
                k = k.substring(prefix.length());
            }
            JsonElement v = KoperSoulCodec.decode(e.getValue());
            out.add(k, v == null ? JsonNull.INSTANCE : v);
        }
        return out;
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("koperlib").resolve("world_data.nbt");
    }

    public static void loadIfNeeded(MinecraftServer server) {
        if (loaded) return;
        loaded = true;
        STORE.clear();
        Path p = file(server);
        if (!Files.isRegularFile(p)) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(p, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            for (String k : tag.keySet()) tag.getString(k).ifPresent(v -> STORE.put(k, v));
            KoperLib.LOGGER.info("[WorldVault] loaded {} keys", STORE.size());
        } catch (Exception e) {
            KoperLib.LOGGER.warn("[WorldVault] could not read {}: {}", p, e.getMessage());
        }
    }

    public static void save(MinecraftServer server) {
        if (!dirty) return;
        Path p = file(server);
        try {
            Files.createDirectories(p.getParent());
            CompoundTag tag = new CompoundTag();
            STORE.forEach(tag::putString);
            NbtIo.writeCompressed(tag, p);
            dirty = false;
        } catch (Exception e) {
            KoperLib.LOGGER.error("[WorldVault] could not write {}: {}", p, e.getMessage());
        }
    }

    public static void onStopping(MinecraftServer server) {
        save(server);
        loaded = false;
    }

    // ── arg plumbing ──────────────────────────────────────────────────────────

    private static String str(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonElement at(JsonArray a, int i) {
        return a != null && i < a.size() ? a.get(i) : null;
    }

    private static JsonElement def(JsonArray a, int i) {
        JsonElement e = at(a, i);
        return e == null ? JsonNull.INSTANCE : e;
    }
}
