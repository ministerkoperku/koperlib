package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.network.KenderSnapshotPayload;
import com.koper.koper_lib.network.KenderSpawnPayload;
import com.koper.koper_lib.network.KenderStreamPayloads;
import com.koper.koper_lib.network.KenderStreamQueue;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class KenderSyncServer {
    private KenderSyncServer() {}
    private static final class Peer {
        final KenderStreamQueue queue=new KenderStreamQueue();
        final Map<Long,Long> tokens=new HashMap<>();
        String dimension;
        int lastTickBytes;
        long totalBytes, frames;
        Peer(String dimension) { this.dimension=dimension; }
    }
    public record Stats(int queuedBodies,long inFlightBytes,int lastTickBytes,long totalBytes,long frames) {}
    private static final Map<UUID,Peer> PEERS=new HashMap<>();
    private static long nextToken;

    public static boolean offer(ServerPlayer player,KenderSpawnPayload geometry) {
        var config=KhysicsConfig.get();
        if (config.kenderGeometryStreamThreshold <= 0 || geometry.blockStateIds().length < config.kenderGeometryStreamThreshold
            || !ServerPlayNetworking.canSend(player,KenderStreamPayloads.Start.TYPE)
            || !ServerPlayNetworking.canSend(player,KenderStreamPayloads.Data.TYPE)
            || !ServerPlayNetworking.canSend(player,KenderStreamPayloads.Cancel.TYPE)) {
            cancel(player,geometry.kontraId());
            return false;
        }
        var peer=PEERS.computeIfAbsent(player.getUUID(),id -> new Peer(dimension(player)));
        syncDimension(player,peer);
        if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("geometry stream tokens exhausted");
        long token=++nextToken;
        long tick=KoperPhys.tickCount();
        // capture can fail without discarding the previous queued geometry
        peer.queue.offer(token,new KenderSnapshotPayload(tick,geometry));
        Long previous=peer.tokens.put(geometry.kontraId(),token);
        if (previous != null) KoperNetwork.send(player,new KenderStreamPayloads.Cancel(previous,geometry.kontraId()));
        KoperNetwork.send(player,new KenderStreamPayloads.Start(token,geometry.kontraId(),tick,geometry.blockStateIds().length,
            geometry.pos(),geometry.rot()));
        return true;
    }

    public static void tick(MinecraftServer server) {
        var config=KhysicsConfig.get();
        int budget=Math.max(129,config.kenderGeometryBytesPerTick), window=Math.max(129,config.kenderGeometryWindowBytes);
        int chunk=Math.max(1,Math.min(KenderStreamPayloads.MAX_FRAGMENT_BYTES,config.kenderGeometryChunkBytes));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Peer peer=PEERS.get(player.getUUID());
            if (peer == null) continue;
            syncDimension(player,peer);
            peer.lastTickBytes=0;
            while (peer.queue.queuedBodies()>0 && peer.lastTickBytes<budget) {
                try {
                    peer.queue.drain(budget-peer.lastTickBytes,window,chunk,data -> {
                        int bytes=data.bytes().length+KenderStreamPayloads.FRAME_OVERHEAD;
                        peer.lastTickBytes+=bytes; peer.totalBytes+=bytes; peer.frames++;
                        KoperNetwork.send(player,data);
                    });
                    break;
                } catch (KenderStreamQueue.EncodingFailure failure) {
                    KoperCore.LOGGER.warn("[Kender] geometry encoding failed for {}: {}",failure.body(),failure.getCause().toString());
                    cancel(player,failure.body());
                }
            }
        }
    }

    public static void ack(ServerPlayer player,long sequence) {
        Peer peer=PEERS.get(player.getUUID());
        if (peer != null) peer.queue.ack(sequence);
    }

    public static void cancel(ServerPlayer player,long body) {
        Peer peer=PEERS.get(player.getUUID());
        if (peer == null) return;
        peer.queue.cancel(body);
        Long token=peer.tokens.remove(body);
        if (token != null && ServerPlayNetworking.canSend(player,KenderStreamPayloads.Cancel.TYPE))
            KoperNetwork.send(player,new KenderStreamPayloads.Cancel(token,body));
    }

    public static void cancelBody(MinecraftServer server,long body) {
        if (server != null) for (ServerPlayer player : server.getPlayerList().getPlayers()) cancel(player,body);
    }

    private static String dimension(ServerPlayer player) { return KoperPhys.levelKey((ServerLevel)player.level()); }

    private static void syncDimension(ServerPlayer player,Peer peer) {
        String dimension=dimension(player);
        if (peer.dimension.equals(dimension)) return;
        for (long body : java.util.List.copyOf(peer.tokens.keySet())) cancel(player,body);
        // old fragments can still arrive and ACK: keep the connection sequence and credit
        peer.dimension=dimension;
    }

    public static void leave(UUID player) { PEERS.remove(player); }
    public static void clear() { PEERS.clear(); nextToken=0; }
    public static Stats stats(UUID player) {
        Peer peer=PEERS.get(player);
        return peer == null ? new Stats(0,0,0,0,0)
            : new Stats(peer.queue.queuedBodies(),peer.queue.inFlightBytes(),peer.lastTickBytes,peer.totalBytes,peer.frames);
    }
}
