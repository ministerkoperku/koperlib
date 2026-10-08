package com.koper.koper_lib.network;

import com.koper.koper_lib.api.core.KoperNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// old facade kept while addons migrate to the core KoperNetwork API
public class KoperNetworking {
    @Deprecated(forRemoval = true)
    public static void init() {
        KoperNetwork.init();
    }

    // called from KoperPhys — kept here so networking is centralized
    public static void broadcastToAll(net.minecraft.server.MinecraftServer server, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        KoperNetwork.broadcast(server, payload);
    }

    public static void sendToPlayer(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        KoperNetwork.send(player, payload);
    }

    // send only to players in a specific level — prevents cross-dimension bleed
    public static void broadcastToLevel(ServerLevel level, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        KoperNetwork.broadcast(level, payload);
    }
}
