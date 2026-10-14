package com.koper.koper_lib.fullpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.FullpackAddons;
import com.koper.koper_lib.api.KoperSpace;
import com.koper.koper_lib.api.KoperSpace.Body;
import com.koper.koper_lib.api.KoperTeleport;
import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.network.SpaceBodiesPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

// space bodies: pack json from space_bodies/, plus whatever Java registers. every other tick each
// landable body pulls in what crossed its sphere in the space dimension and pushes out what climbed
// above leave_y in its planet dimension. physics assemblies go whole through the Fullpack physics
// provider (Khysics), everything else is teleported as a single entity
public final class SpaceBodies {
    private SpaceBodies() {}

    private static final Map<String, Body> PACK = new LinkedHashMap<>();
    private static final Map<String, Body> CODE = new LinkedHashMap<>();
    private static final Map<String, Predicate<KoperSpace.Crossing>> LISTENERS = new LinkedHashMap<>();
    // whatever just crossed waits this long before it may cross again
    private static final int COOLDOWN = 60;
    private static final Map<UUID, Integer> ENTITY_COOLDOWN = new HashMap<>();
    private static final Map<Long, Integer> ASSEMBLY_COOLDOWN = new HashMap<>();
    // assemblies that keep refusing to cross, so the error is logged once and not every tick
    private static final Map<Long, Integer> STUCK = new HashMap<>();
    private static final Set<String> REPORTED = new HashSet<>();
    private static boolean dirty;

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(SpaceBodies::tick);
        KoperNetwork.onJoin("fullpack", "space-bodies", (player, server) -> KoperNetwork.send(player, payload()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ENTITY_COOLDOWN.clear(); ASSEMBLY_COOLDOWN.clear(); STUCK.clear(); REPORTED.clear();
        });
    }

    // ── content ──────────────────────────────────────────────────────────

    public static synchronized void clearPack() {
        PACK.clear();
        dirty = true;
    }

    public static void load(JsonObject json, String source) {
        try {
            register(parse(json), true);
        } catch (RuntimeException e) {
            FullpackAddons.markReloadFailure();
            KoperLib.LOGGER.error("[Space] space body in {} does not work: {}", source, e.getMessage());
        }
    }

    static Body parse(JsonObject json) {
        if (!json.has("id") || !json.has("space") || !json.has("pos") || !json.has("radius") || !json.has("texture"))
            throw new IllegalArgumentException("needs id, space, pos, radius and texture");
        JsonArray pos = json.getAsJsonArray("pos");
        if (pos.size() != 3) throw new IllegalArgumentException("\"pos\" needs [x, y, z]");
        String target = json.has("target") ? json.get("target").getAsString() : null;
        return new Body(json.get("id").getAsString(), json.get("space").getAsString(),
            new Vec3(pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble()),
            json.get("radius").getAsDouble(), json.has("atmosphere") ? json.get("atmosphere").getAsDouble() : 0,
            json.get("texture").getAsString(), color(json, "color", 0xFFFFFFFF), color(json, "atmosphere_color", 0),
            json.has("spin") ? json.get("spin").getAsFloat() : 0f, target,
            json.has("landing_size") ? json.get("landing_size").getAsDouble() : 1000,
            json.has("arrive_y") ? json.get("arrive_y").getAsDouble() : 300,
            json.has("leave_y") ? json.get("leave_y").getAsDouble() : 600);
    }

    /** "#rrggbb", "#aarrggbb" or a number; rgb alone is opaque */
    static int color(JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        var el = json.get(key);
        if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
        String hex = el.getAsString().trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8)
            throw new IllegalArgumentException("\"" + key + "\" must be #rrggbb or #aarrggbb, got " + el.getAsString());
        long value = Long.parseLong(hex, 16);
        return hex.length() == 6 ? (int)(0xFF000000L | value) : (int) value;
    }

    // two bodies leading to one planet would not know which one to leave by
    private static synchronized void checkTargets() {
        Map<String, String> owners = new HashMap<>();
        for (Body body : merged().values()) {
            if (!body.landable()) continue;
            String other = owners.putIfAbsent(body.target(), body.id());
            if (other != null)
                throw new IllegalArgumentException("bodies " + other + " and " + body.id() + " both lead to " + body.target());
        }
    }

    public static synchronized void register(Body body, boolean fromPack) {
        (fromPack ? PACK : CODE).put(body.id(), body);
        dirty = true;
        try { checkTargets(); }
        catch (IllegalArgumentException e) { (fromPack ? PACK : CODE).remove(body.id()); throw e; }
    }

    public static synchronized void remove(String id) {
        if (CODE.remove(id) != null | PACK.remove(id) != null) dirty = true;
    }

    public static synchronized void move(String id, Vec3 center) {
        Body body = CODE.get(id);
        if (body != null) CODE.put(id, body.at(center));
        else if ((body = PACK.get(id)) != null) PACK.put(id, body.at(center));
        else throw new IllegalArgumentException("no space body " + id);
        dirty = true;
    }

    public static synchronized Body get(String id) {
        Body body = CODE.get(id);
        return body != null ? body : PACK.get(id);
    }

    public static synchronized Collection<Body> all() { return List.copyOf(merged().values()); }

    public static synchronized void onCrossing(String owner, Predicate<KoperSpace.Crossing> listener) {
        if (LISTENERS.putIfAbsent(owner, listener) != null)
            throw new IllegalStateException("space crossing listener already registered: " + owner);
    }

    private static Map<String, Body> merged() {
        Map<String, Body> all = new LinkedHashMap<>(PACK);
        all.putAll(CODE);
        return all;
    }

    public static void afterReload(MinecraftServer server) {
        dirty = true;
    }

    private static SpaceBodiesPayload payload() {
        List<SpaceBodiesPayload.Body> out = new ArrayList<>();
        for (Body b : all())
            out.add(new SpaceBodiesPayload.Body(b.id(), b.space(), b.center().x, b.center().y, b.center().z,
                b.radius(), b.atmosphere(), b.texture(), b.color(), b.glow(), b.spin()));
        return new SpaceBodiesPayload(out);
    }

    // ── crossings ────────────────────────────────────────────────────────

    private static void tick(MinecraftServer server) {
        boolean send;
        synchronized (SpaceBodies.class) { send = dirty; dirty = false; }
        if (send) KoperNetwork.broadcast(server, payload());
        int now = server.getTickCount();
        if ((now & 1) != 0) return;
        ENTITY_COOLDOWN.values().removeIf(until -> until <= now);
        ASSEMBLY_COOLDOWN.values().removeIf(until -> until <= now);
        STUCK.values().removeIf(since -> now - since > 1200);
        for (Body body : all()) {
            if (!body.landable()) continue;
            ServerLevel space = KoperTeleport.level(server, body.space());
            ServerLevel planet = KoperTeleport.level(server, body.target());
            if (space == null || planet == null) {
                if (REPORTED.add(body.id()))
                    KoperLib.LOGGER.error("[Space] landing on {} does not work: dimension {} does not exist",
                        body.id(), space == null ? body.space() : body.target());
                continue;
            }
            try {
                arrive(body, space, planet, now);
                leave(body, space, planet, now);
            } catch (RuntimeException e) {
                if (REPORTED.add(body.id() + "#tick"))
                    KoperLib.LOGGER.error("[Space] crossings of {} do not work", body.id(), e);
            }
        }
    }

    private static void arrive(Body body, ServerLevel space, ServerLevel planet, int now) {
        double reach = body.reach();
        for (var assembly : FullpackAddons.physicsAssemblies(space)) {
            double distance = assembly.pos().distanceTo(body.center());
            double lookahead = Math.max(64, assembly.velocity().length() * 3);
            if (distance > reach + lookahead) continue;
            Vec3 landing = KoperSpace.landingFor(body, assembly.pos());
            // the transfer refuses unloaded destinations, so load them while the assembly is still coming
            planet.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(landing)), 4);
            if (distance > reach) continue;
            crossAssembly(body, true, assembly.root(), planet, landing, now);
        }
        AABB box = new AABB(body.center(), body.center()).inflate(reach);
        for (Entity entity : space.getEntities((Entity) null, box, e -> e.isAlive() && e.getVehicle() == null)) {
            if (entity.position().distanceTo(body.center()) > reach) continue;
            Vec3 landing = KoperSpace.landingFor(body, entity.position());
            crossEntity(body, true, entity, planet, landing,
                KoperSpace.arrivalVelocity(body, entity.position(), entity.getDeltaMovement()), now);
        }
    }

    private static void leave(Body body, ServerLevel space, ServerLevel planet, int now) {
        for (var assembly : FullpackAddons.physicsAssemblies(planet)) {
            if (assembly.pos().y <= body.leaveY()) continue;
            Vec3 out = KoperSpace.departureFor(body, assembly.pos());
            space.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(out)), 4);
            crossAssembly(body, false, assembly.root(), space, out, now);
        }
        List<Entity> climbing = new ArrayList<>();
        for (Entity entity : planet.getAllEntities())
            if (entity.isAlive() && entity.getVehicle() == null && entity.getY() > body.leaveY()) climbing.add(entity);
        for (Entity entity : climbing) {
            Vec3 out = KoperSpace.departureFor(body, entity.position());
            crossEntity(body, false, entity, space, out,
                KoperSpace.departureVelocity(body, out, entity.getDeltaMovement()), now);
        }
    }

    private static void crossAssembly(Body body, boolean arriving, long root, ServerLevel target, Vec3 where, int now) {
        if (ASSEMBLY_COOLDOWN.containsKey(root)) return;
        if (!allowed(new KoperSpace.Crossing(body, arriving, null, root, where))) return;
        long moved = FullpackAddons.physicsTransfer(root, target, where);
        if (moved != 0) {
            ASSEMBLY_COOLDOWN.put(moved, now + COOLDOWN);
            STUCK.remove(root);
            KoperLib.LOGGER.info("[Space] assembly {} {} {} as {}", root, arriving ? "landed on" : "left", body.id(), moved);
            return;
        }
        // usually the destination is still loading; past five seconds something else is wrong
        int since = STUCK.computeIfAbsent(root, r -> now);
        if (now - since == 100)
            KoperLib.LOGGER.error("[Space] assembly {} cannot {} {}: the physics transfer keeps refusing (blocked or unloaded destination at {}, or outside the flight bounds)",
                root, arriving ? "land on" : "leave", body.id(), where);
    }

    private static void crossEntity(Body body, boolean arriving, Entity entity, ServerLevel target, Vec3 where, Vec3 velocity, int now) {
        if (ENTITY_COOLDOWN.containsKey(entity.getUUID()) || FullpackAddons.physicsCarries(entity)) return;
        if (!allowed(new KoperSpace.Crossing(body, arriving, entity, 0L, where))) return;
        Entity moved = KoperTeleport.to(entity, target.dimension().identifier().toString(), where, velocity);
        if (moved != null) ENTITY_COOLDOWN.put(moved.getUUID(), now + COOLDOWN);
    }

    private static boolean allowed(KoperSpace.Crossing crossing) {
        List<Map.Entry<String, Predicate<KoperSpace.Crossing>>> listeners;
        synchronized (SpaceBodies.class) { listeners = List.copyOf(LISTENERS.entrySet()); }
        for (var listener : listeners) {
            try {
                if (!listener.getValue().test(crossing)) return false;
            } catch (RuntimeException e) {
                KoperLib.LOGGER.error("[Space] crossing listener {} failed, crossing refused", listener.getKey(), e);
                return false;
            }
        }
        return true;
    }
}
