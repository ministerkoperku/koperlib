package com.koper.koper_lib.api.core;

import com.koper.koper_lib.coremod.KoperCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

// payload ownership and connection lifecycle live here, payload behaviour stays in its mod
public final class KoperNetwork {
    @FunctionalInterface public interface JoinHook { void run(ServerPlayer player, MinecraftServer server); }
    @FunctionalInterface public interface LeaveHook { void run(ServerPlayer player, MinecraftServer server); }

    private static final Map<CustomPacketPayload.Type<?>, String> CLIENTBOUND = new LinkedHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, String> SERVERBOUND = new LinkedHashMap<>();
    private static final Map<String, JoinHook> JOIN = new LinkedHashMap<>();
    private static final Map<String, LeaveHook> LEAVE = new LinkedHashMap<>();
    private static boolean initialized;

    private KoperNetwork() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            joinSnapshot().forEach((id, hook) -> runJoin(id, hook, handler.player, server)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            leaveSnapshot().forEach((id, hook) -> runLeave(id, hook, handler.player, server)));
    }

    public static synchronized <T extends CustomPacketPayload> void clientbound(
        String owner,
        CustomPacketPayload.Type<T> type,
        StreamCodec<RegistryFriendlyByteBuf, T> codec
    ) {
        own(CLIENTBOUND, owner, type);
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    public static synchronized <T extends CustomPacketPayload> void serverbound(
        String owner,
        CustomPacketPayload.Type<T> type,
        StreamCodec<RegistryFriendlyByteBuf, T> codec
    ) {
        own(SERVERBOUND, owner, type);
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
    }

    public static synchronized void onJoin(String owner, String hookId, JoinHook hook) {
        addHook(JOIN, owner, hookId, hook);
    }

    public static synchronized void onLeave(String owner, String hookId, LeaveHook hook) {
        addHook(LEAVE, owner, hookId, hook);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    public static void broadcast(MinecraftServer server, CustomPacketPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, payload);
    }

    public static void broadcast(ServerLevel level, CustomPacketPayload payload) {
        for (ServerPlayer player : level.players()) send(player, payload);
    }

    public static synchronized Map<CustomPacketPayload.Type<?>, String> clientboundOwners() {
        return Map.copyOf(CLIENTBOUND);
    }

    public static synchronized Map<CustomPacketPayload.Type<?>, String> serverboundOwners() {
        return Map.copyOf(SERVERBOUND);
    }

    private static void own(Map<CustomPacketPayload.Type<?>, String> map, String owner, CustomPacketPayload.Type<?> type) {
        if (owner == null || owner.isBlank() || type == null) throw new IllegalArgumentException("payload needs owner and type");
        String old = map.putIfAbsent(type, owner);
        if (old != null) throw new IllegalStateException("payload " + type + " already belongs to " + old);
    }

    private static <T> void addHook(Map<String, T> hooks, String owner, String hookId, T hook) {
        if (owner == null || owner.isBlank() || hookId == null || hookId.isBlank() || hook == null)
            throw new IllegalArgumentException("network hook needs owner, id and callback");
        String id = owner + ":" + hookId;
        if (hooks.putIfAbsent(id, hook) != null) throw new IllegalStateException("network hook already registered: " + id);
    }

    private static synchronized Map<String, JoinHook> joinSnapshot() { return new LinkedHashMap<>(JOIN); }
    private static synchronized Map<String, LeaveHook> leaveSnapshot() { return new LinkedHashMap<>(LEAVE); }

    private static void runJoin(String id, JoinHook hook, ServerPlayer player, MinecraftServer server) {
        try { hook.run(player, server); }
        catch (Throwable error) { KoperCore.LOGGER.error("[Network] join hook '{}' failed", id, error); }
    }

    private static void runLeave(String id, LeaveHook hook, ServerPlayer player, MinecraftServer server) {
        try { hook.run(player, server); }
        catch (Throwable error) { KoperCore.LOGGER.error("[Network] leave hook '{}' failed", id, error); }
    }
}
