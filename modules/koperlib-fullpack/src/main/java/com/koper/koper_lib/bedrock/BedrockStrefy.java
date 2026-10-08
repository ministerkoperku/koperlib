package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.Map;

// world.tickingAreaManager: bedrock's ticking areas on java's chunk tickets. an area keeps its chunks
// loaded and ticking until a script removes it. bedrock also keeps them over a restart, here they
// last until the server stops (the ticket type does not persist), packs that clean up on start
// (toss lab does) find nothing to clean and carry on
public final class BedrockStrefy {

    private BedrockStrefy() {}

    // loading + simulation, no persist, no timeout. radius 2 like /forceload: entity ticking, 1 only ticks blocks
    private static TicketType BILET;
    // bedrock's own cap per world
    static final int MAX = 400;
    // "<addon folder>|<id>" -> area
    private static final Map<String, Strefa> STREFY = new LinkedHashMap<>();

    private record Strefa(String id, String dim, int x0, int y0, int z0, int x1, int y1, int z1) {
        int cx0() { return Math.floorDiv(x0, 16); }
        int cz0() { return Math.floorDiv(z0, 16); }
        int cx1() { return Math.floorDiv(x1, 16); }
        int cz1() { return Math.floorDiv(z1, 16); }
        int chunks() { return (cx1() - cx0() + 1) * (cz1() - cz0() + 1); }
    }

    // fullpack init, before the registries freeze
    public static void zarejestruj() {
        if (BILET != null) return;
        BILET = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("koperlib", "bedrock_ticking"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
    }

    private static String klucz(String id) {
        var a = BedrockSkrypciarz.teraz();
        return (a == null ? "?" : a.folder) + "|" + id;
    }

    static JsonElement handle(JsonObject q) {
        String k = BedrockPytajnik.s(q, "k");
        return switch (k) {
            case "add" -> dodaj(q);
            case "get" -> {
                Strefa s = STREFY.get(klucz(BedrockPytajnik.s(q, "id")));
                yield s == null ? null : opis(s);
            }
            case "all" -> {
                JsonArray out = new JsonArray();
                String pre = klucz("");
                for (var e : STREFY.entrySet()) if (e.getKey().startsWith(pre)) out.add(opis(e.getValue()));
                yield out;
            }
            case "remove" -> {
                Strefa s = STREFY.remove(klucz(BedrockPytajnik.s(q, "id")));
                if (s != null) puscic(s);
                yield new JsonPrimitive(s != null);
            }
            case "clear" -> {
                String pre = klucz("");
                STREFY.entrySet().removeIf(e -> {
                    if (!e.getKey().startsWith(pre)) return false;
                    puscic(e.getValue());
                    return true;
                });
                yield null;
            }
            case "count" -> {
                int n = 0;
                for (Strefa s : STREFY.values()) n += s.chunks();
                yield new JsonPrimitive(n);
            }
            default -> throw new BedrockPytajnik.Oops("ticking area: unknown " + k);
        };
    }

    private static JsonElement dodaj(JsonObject q) {
        if (BILET == null) throw new BedrockPytajnik.Oops("ticking areas are off (ticket type not registered)");
        String id = BedrockPytajnik.s(q, "id");
        String key = klucz(id);
        if (STREFY.containsKey(key)) throw new BedrockPytajnik.Oops("Identifier already exists: " + id);
        JsonObject a = q.getAsJsonObject("from"), b = q.getAsJsonObject("to");
        int ax = (int) Math.floor(a.get("x").getAsDouble()), ay = (int) Math.floor(a.get("y").getAsDouble()), az = (int) Math.floor(a.get("z").getAsDouble());
        int bx = (int) Math.floor(b.get("x").getAsDouble()), by = (int) Math.floor(b.get("y").getAsDouble()), bz = (int) Math.floor(b.get("z").getAsDouble());
        Strefa s = new Strefa(id, BedrockPytajnik.s(q, "dim"), Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
            Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
        int uzyte = 0;
        for (Strefa o : STREFY.values()) uzyte += o.chunks();
        if (uzyte + s.chunks() > MAX) throw new BedrockPytajnik.Oops("ticking area over the chunk limit (" + MAX + ")");
        ServerLevel lvl = BedrockPytajnik.level(s.dim());
        for (int cx = s.cx0(); cx <= s.cx1(); cx++)
            for (int cz = s.cz0(); cz <= s.cz1(); cz++)
                lvl.getChunkSource().addTicketWithRadius(BILET, new ChunkPos(cx, cz), 2);
        STREFY.put(key, s);
        return opis(s);
    }

    private static void puscic(Strefa s) {
        ServerLevel lvl;
        try { lvl = BedrockPytajnik.level(s.dim()); } catch (RuntimeException e) { return; }
        for (int cx = s.cx0(); cx <= s.cx1(); cx++)
            for (int cz = s.cz0(); cz <= s.cz1(); cz++)
                lvl.getChunkSource().removeTicketWithRadius(BILET, new ChunkPos(cx, cz), 2);
    }

    private static JsonObject opis(Strefa s) {
        JsonObject o = new JsonObject();
        o.addProperty("identifier", s.id());
        o.addProperty("dim", s.dim());
        o.addProperty("chunkCount", s.chunks());
        boolean full = true;
        try {
            ServerLevel lvl = BedrockPytajnik.level(s.dim());
            for (int cx = s.cx0(); cx <= s.cx1() && full; cx++)
                for (int cz = s.cz0(); cz <= s.cz1() && full; cz++)
                    full = lvl.isPositionTickingWithEntitiesLoaded(ChunkPos.pack(cx, cz));
        } catch (RuntimeException e) { full = false; }
        o.addProperty("isFullyLoaded", full);
        JsonObject min = new JsonObject(), max = new JsonObject();
        min.addProperty("x", s.x0()); min.addProperty("y", s.y0()); min.addProperty("z", s.z0());
        max.addProperty("x", s.x1()); max.addProperty("y", s.y1()); max.addProperty("z", s.z1());
        JsonObject box = new JsonObject();
        box.add("min", min);
        box.add("max", max);
        o.add("boundingBox", box);
        return o;
    }

    // server stopped: tickets die with the levels, the list goes too
    static void wyczysc() {
        STREFY.clear();
    }
}
