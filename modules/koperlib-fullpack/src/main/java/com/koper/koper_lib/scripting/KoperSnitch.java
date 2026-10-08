package com.koper.koper_lib.scripting;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// the global "player just did a thing" bus. every other event hook in koperlib is per-content —
// namespace:id/on_use fires for ONE item that has a script. nothing anywhere could answer
// "did this player kill anything at all", which is exactly what quests and a guide need.
//
// java:  KoperSnitch.listen("player:kill", t -> ...);
// lua:   koper.events.on("player:kill", function(e) ... end)
//
// names are namespaced with player: so they can't collide with gui:/net:/command: on the lua bus.
public final class KoperSnitch {
    private KoperSnitch() {}

    public static final String KILL    = "player:kill";
    public static final String DIED    = "player:died";
    public static final String CRAFT   = "player:craft";
    public static final String GOT     = "player:got";      // chattiest one, fires on every inventory add
    public static final String ADVANCE = "player:advancement";
    public static final String JOIN    = "player:join";
    public static final String LEFT    = "player:left";
    public static final String DIM     = "player:dimension";
    public static final String CHUNK   = "player:chunk";
    public static final String IDLE    = "player:idle";     // the stuck detector
    public static final String HEART   = "player:heartbeat";
    public static final String BREAK   = "player:break";
    public static final String PLACE   = "player:place";
    public static final String TALK    = "player:talk";     // right clicked a mob

    public record Tattle(ServerPlayer who, String what, Map<String, String> bits) {
        public String bit(String key)              { return bits.getOrDefault(key, ""); }
        public String bit(String key, String def)  { String v = bits.get(key); return v == null || v.isBlank() ? def : v; }
        public String id()                         { return bit("id"); }
        public int count() {
            try { return Integer.parseInt(bit("count", "1")); }
            catch (NumberFormatException notANumber) { return 1; }
        }
    }

    @FunctionalInterface
    public interface Ear { void heard(Tattle tattle); }

    private static final Map<String, List<Ear>> EARS = new ConcurrentHashMap<>();
    private static final Map<UUID, Watch> WATCHED = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> NOSY = ConcurrentHashMap.newKeySet(); // /koperlib snitch

    // how long a player has to do nothing before we decide they're lost. 90s felt right in testing,
    // shorter and it nags you while you're building
    private static final int IDLE_SECONDS = 90;
    private static final double IDLE_RADIUS = 6.0;

    private static int tickCounter;

    // "player:kill" for one event, "*" for all of them (that's what a guide/director wants)
    public static void listen(String what, Ear ear) {
        if (what == null || ear == null) return;
        // copy on write because mixins fire this from the server thread while a mod may still be
        // registering during init
        EARS.computeIfAbsent(what, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(ear);
    }

    // kv pairs flat: snitch(player, KILL, "id", "minecraft:zombie", "count", "1")
    public static void snitch(ServerPlayer who, String what, String... kv) {
        if (who == null || what == null || who.level().isClientSide()) return;

        Map<String, String> bits = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) bits.put(kv[i], kv[i + 1]);

        Tattle tattle = new Tattle(who, what, bits);
        poke(EARS.get(what), tattle);
        poke(EARS.get("*"), tattle);

        // anything the player did counts as "not stuck". idle is excluded or it resets the flag it
        // just set and you get a nag every 90s instead of once
        if (!HEART.equals(what) && !CHUNK.equals(what) && !IDLE.equals(what)) {
            Watch watch = WATCHED.get(who.getUUID());
            if (watch != null) watch.stir(tickCounter);
        }

        if (UniversalScriptEngine.anyVmAlive())
            UniversalScriptEngine.fireSnitchEvent(what, who, bits);
    }

    private static void poke(List<Ear> ears, Tattle tattle) {
        if (ears == null) return;
        for (Ear ear : ears) {
            try { ear.heard(tattle); }
            catch (Exception badListener) {
                KoperLib.LOGGER.warn("[Snitch] listener blew up on {}: {}", tattle.what(), badListener.toString());
            }
        }
    }

    // /koperlib snitch — dumps the bus into chat so you can see the thing working. returns the new state
    public static boolean nosy(ServerPlayer player) {
        UUID id = player.getUUID();
        if (NOSY.remove(id)) return false;
        NOSY.add(id);
        return true;
    }

    public static void register() {
        // heartbeat is muted or chat is unreadable within a second
        listen("*", tattle -> {
            if (NOSY.isEmpty() || HEART.equals(tattle.what())) return;
            var server = tattle.who().level().getServer();
            if (server == null) return;

            StringBuilder line = new StringBuilder("§8[snitch] §e")
                .append(tattle.what()).append(" §7").append(tattle.who().getGameProfile().name());
            tattle.bits().forEach((k, v) -> line.append(" §8").append(k).append("=§f").append(v));

            for (UUID uuid : NOSY) {
                ServerPlayer nosyOne = server.getPlayerList().getPlayer(uuid);
                if (nosyOne != null) nosyOne.sendSystemMessage(net.minecraft.network.chat.Component.literal(line.toString()));
            }
        });

        // AFTER_DEATH gives us both sides of a kill in one hook
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level().isClientSide()) return;

            if (entity instanceof ServerPlayer corpse) {
                snitch(corpse, DIED, "by", nameOf(source.getEntity()), "source", source.getMsgId());
            }
            if (source.getEntity() instanceof ServerPlayer killer && !(entity instanceof ServerPlayer)) {
                snitch(killer, KILL, "id", typeIdOf(entity), "name", entity.getName().getString());
            }
        });

        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
            (level, player, pos, state, blockEntity) -> {
                if (level.isClientSide() || !(player instanceof ServerPlayer breaker)) return;
                var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (key != null) snitch(breaker, BREAK, "id", key.toString(),
                    "x", String.valueOf(pos.getX()), "y", String.valueOf(pos.getY()), "z", String.valueOf(pos.getZ()));
            });

        // talking to a mob is how a quest giver works, so the id of what was clicked matters
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
            (player, level, hand, entity, hit) -> {
                if (level.isClientSide() || !(player instanceof ServerPlayer talker)
                    || hand != net.minecraft.world.InteractionHand.MAIN_HAND)
                    return net.minecraft.world.InteractionResult.PASS;

                snitch(talker, TALK, "id", typeIdOf(entity), "name", entity.getName().getString(),
                    "tags", String.join(",", entity.entityTags())); // 26.2 renamed getTags()
                return net.minecraft.world.InteractionResult.PASS;
            });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            WATCHED.put(player.getUUID(), new Watch(player, tickCounter));
            snitch(player, JOIN, "id", player.getGameProfile().name());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            snitch(handler.player, LEFT, "id", handler.player.getGameProfile().name());
            WATCHED.remove(handler.player.getUUID());
        });

        // everything position-shaped is a diff against last second instead of its own hook.
        // dimension changes, chunk crossings and standing still all fall out of the same pass
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % 20 != 0) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Watch watch = WATCHED.computeIfAbsent(player.getUUID(), u -> new Watch(player, tickCounter));
                watch.sniff(player, tickCounter);
            }
        });

        KoperLib.LOGGER.info("[KoperLib] snitch listening — player:* events live");
    }

    // per player memory of where they were and when they last did anything
    private static final class Watch {
        String dim;
        long chunkKey;
        Vec3 spot;
        int lastStir;
        boolean nagged;

        Watch(ServerPlayer player, int now) {
            this.dim = dimOf(player);
            this.chunkKey = chunkOf(player);
            this.spot = player.position();
            this.lastStir = now;
        }

        void stir(int now) {
            lastStir = now;
            nagged = false;
        }

        void sniff(ServerPlayer player, int now) {
            String nowDim = dimOf(player);
            if (!nowDim.equals(dim)) {
                String was = dim;
                dim = nowDim;
                snitch(player, DIM, "from", was, "to", nowDim);
            }

            long nowChunk = chunkOf(player);
            if (nowChunk != chunkKey) {
                chunkKey = nowChunk;
                snitch(player, CHUNK,
                    "x", String.valueOf(player.chunkPosition().x()),
                    "z", String.valueOf(player.chunkPosition().z()));
            }

            // moving counts as being fine even if nothing else fired
            if (player.position().distanceToSqr(spot) > IDLE_RADIUS * IDLE_RADIUS) {
                spot = player.position();
                stir(now);
            }

            snitch(player, HEART, "dim", nowDim);

            if (!nagged && (now - lastStir) >= IDLE_SECONDS * 20) {
                nagged = true;
                snitch(player, IDLE, "secs", String.valueOf((now - lastStir) / 20));
            }
        }
    }

    // 26.2: ChunkPos is a record now, toLong is pack(), and ResourceKey.location() is identifier()
    private static long chunkOf(ServerPlayer player) {
        return player.chunkPosition().pack();
    }

    private static String dimOf(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    private static String typeIdOf(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "" : key.toString();
    }

    private static String nameOf(Entity entity) {
        return entity == null ? "world" : typeIdOf(entity);
    }
}
