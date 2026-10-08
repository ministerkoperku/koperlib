package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.kender.KenderClientState;
import com.koper.koper_lib.kender.KenderSyncClient;
import com.koper.koper_lib.kender.KontraRideClient;
import com.koper.koper_lib.network.KenderBlockDeltaPayload;
import com.koper.koper_lib.network.KenderBlockEntityUpdatePayload;
import com.koper.koper_lib.network.KenderBlockEventPayload;
import com.koper.koper_lib.network.KenderBreakPayload;
import com.koper.koper_lib.network.KenderGridBlockUpdatePayload;
import com.koper.koper_lib.network.KenderPickPayload;
import com.koper.koper_lib.network.KenderRemovePayload;
import com.koper.koper_lib.network.KenderResyncPayload;
import com.koper.koper_lib.network.KenderSelfRightPayload;
import com.koper.koper_lib.network.KenderSpawnPayload;
import com.koper.koper_lib.network.KenderSnapshotPayload;
import com.koper.koper_lib.network.KenderStampPayload;
import com.koper.koper_lib.network.KenderStreamPayloads;
import com.koper.koper_lib.network.KenderUpdatePayload;
import com.koper.koper_lib.network.KenderUsePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// all kontra wire behaviour belongs to khysics, core does not need to know what a grid hit is
public final class KhysicsNetworking {
    private KhysicsNetworking() {}

    public static void init() {
        KoperNetwork.clientbound("khysics", KenderStreamPayloads.Start.TYPE, KenderStreamPayloads.Start.CODEC);
        KoperNetwork.clientbound("khysics", KenderStreamPayloads.Data.TYPE, KenderStreamPayloads.Data.CODEC);
        KoperNetwork.clientbound("khysics", KenderStreamPayloads.Cancel.TYPE, KenderStreamPayloads.Cancel.CODEC);
        KoperNetwork.serverbound("khysics", KenderStreamPayloads.Ack.TYPE, KenderStreamPayloads.Ack.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(KenderStreamPayloads.Ack.TYPE, (payload, context) ->
            context.server().execute(() -> KenderSyncServer.ack(context.player(),payload.sequence())));
        KoperNetwork.onLeave("khysics", "geometry", (player,server) -> KenderSyncServer.leave(player.getUUID()));

        KoperNetwork.clientbound("khysics", KenderSpawnPayload.TYPE, KenderSpawnPayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderSnapshotPayload.TYPE, KenderSnapshotPayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderUpdatePayload.TYPE, KenderUpdatePayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderRemovePayload.TYPE, KenderRemovePayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderBlockEventPayload.TYPE, KenderBlockEventPayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderBlockEntityUpdatePayload.TYPE, KenderBlockEntityUpdatePayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderGridBlockUpdatePayload.TYPE, KenderGridBlockUpdatePayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderStampPayload.TYPE, KenderStampPayload.CODEC);
        KoperNetwork.clientbound("khysics", KenderBlockDeltaPayload.TYPE, KenderBlockDeltaPayload.CODEC);

        KoperNetwork.serverbound("khysics", KenderBreakPayload.TYPE, KenderBreakPayload.CODEC);
        KoperNetwork.serverbound("khysics", KenderSelfRightPayload.TYPE, KenderSelfRightPayload.CODEC);
        KoperNetwork.serverbound("khysics", KenderUsePayload.TYPE, KenderUsePayload.CODEC);
        KoperNetwork.serverbound("khysics", KenderPickPayload.TYPE, KenderPickPayload.CODEC);
        KoperNetwork.serverbound("khysics", KenderResyncPayload.TYPE, KenderResyncPayload.CODEC);

        KoperNetwork.onJoin("khysics", "kontraptions", (player, server) -> KoperPhys.sendAllToPlayer(player));

        ServerPlayNetworking.registerGlobalReceiver(KenderSelfRightPayload.TYPE, (payload, context) ->
            context.server().execute(() -> KoperPhys.selfRight(
                (ServerLevel) context.player().level(), payload.kontraId()))
        );

        ServerPlayNetworking.registerGlobalReceiver(KenderResyncPayload.TYPE, (payload, context) ->
            context.server().execute(() -> KoperPhys.resyncToPlayer(context.player(), payload.kontraId()))
        );

        ServerPlayNetworking.registerGlobalReceiver(KenderUsePayload.TYPE, (payload, context) ->
            context.server().execute(() -> KoperPhys.useGridBlock(context.player(), payload.hit()))
        );

        ServerPlayNetworking.registerGlobalReceiver(KenderPickPayload.TYPE, (payload, context) ->
            context.server().execute(() -> pick(context.player(), payload))
        );

        ServerPlayNetworking.registerGlobalReceiver(KenderBreakPayload.TYPE, (payload, context) ->
            context.server().execute(() -> breakBlock(context.player(), payload))
        );
    }

    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(KenderStreamPayloads.Start.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.start(payload)));
        ClientPlayNetworking.registerGlobalReceiver(KenderStreamPayloads.Data.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.data(payload,ClientPlayNetworking::send)));
        ClientPlayNetworking.registerGlobalReceiver(KenderStreamPayloads.Cancel.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.cancelToken(payload.token(),payload.body())));
        ClientPlayNetworking.registerGlobalReceiver(KenderSpawnPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                KenderSyncClient.cancel(payload.kontraId());
                KenderClientState.spawn(payload.kontraId(), payload.pos(), payload.rot(), payload.blockStateIds(), payload.offsets(),
                    payload.locals(), payload.blockEntityTags());
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderSnapshotPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                var p = payload.geometry();
                KenderSyncClient.cancel(p.kontraId());
                KenderClientState.spawnSnapshot(payload.serverTick(), p.kontraId(), p.pos(), p.rot(),
                    p.blockStateIds(), p.offsets(), p.locals(), p.blockEntityTags());
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderUpdatePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                for (int i = 0; i < payload.ids().length; i++) {
                    float[] transform = payload.transforms()[i];
                    long id = payload.ids()[i];
                    KenderSyncClient.notePose(id, payload.serverTick(),
                        new float[]{transform[0],transform[1],transform[2]},
                        new float[]{transform[3],transform[4],transform[5],transform[6]},
                        transform.length > 7 && transform[7] > 0.5f);
                    KenderClientState.updateTransform(id, payload.serverTick(),
                        new float[]{transform[0], transform[1], transform[2]},
                        new float[]{transform[3], transform[4], transform[5], transform[6]},
                        transform.length > 7 && transform[7] > 0.5f);
                    var kontra = KenderClientState.getById(id);
                    if (kontra != null) KontraRideClient.onTransformUpdate(id, kontra.lastUpdateNanos);
                }
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderBlockEventPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.applyUpdate(payload.kontraId(),payload,() -> KenderClientState.blockEvent(
                payload.kontraId(), payload.localPos(), payload.eventId(), payload.eventData())))
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderBlockEntityUpdatePayload.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.applyUpdate(payload.kontraId(),payload,() -> KenderClientState.updateBlockEntity(
                payload.kontraId(), payload.localPos(), payload.tag())))
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderGridBlockUpdatePayload.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.applyUpdate(payload.kontraId(),payload,() -> KenderClientState.updateBlockState(
                payload.kontraId(), payload.localPos(), payload.blockStateId())))
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderBlockDeltaPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KenderSyncClient.applyUpdate(payload.kontraId(),payload,() -> KenderClientState.applyDelta(
                payload.kontraId(), payload.local(), payload.blockStateId(), payload.ox(), payload.oy(), payload.oz(),
                payload.removed(), payload.blockEntityTag())))
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderStampPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KenderClientState.checkStamps(payload.ids(), payload.stamps()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KenderRemovePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                KenderSyncClient.cancel(payload.kontraId());
                KontraRideClient.onKontraRemoved(payload.kontraId());
                KenderClientState.remove(payload.kontraId());
            })
        );
    }

    public static void sendSpawn(ServerPlayer player, KenderSpawnPayload payload) {
        if (KenderSyncServer.offer(player,payload)) return;
        if (ServerPlayNetworking.canSend(player, KenderSnapshotPayload.TYPE))
            KoperNetwork.send(player, new KenderSnapshotPayload(KoperPhys.tickCount(), payload));
        else KoperNetwork.send(player, payload);
    }

    public static void broadcastSpawn(ServerLevel level, KenderSpawnPayload payload) {
        for (ServerPlayer player : level.players()) sendSpawn(player, payload);
    }

    public static void broadcastSpawn(net.minecraft.server.MinecraftServer server, KenderSpawnPayload payload) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendSpawn(player, payload);
    }

    private static void pick(ServerPlayer player, KenderPickPayload payload) {
        var hit = KoperPhys.resolveGridHit(player, payload.hit());
        if (hit == null) return;
        boolean includeData = player.hasInfiniteMaterials() && payload.includeData();
        var stack = KontraGridContext.call(hit.grid(), () ->
            hit.state().getCloneItemStack((ServerLevel) player.level(), hit.logicalPos(), includeData));
        if (stack.isEmpty() || !stack.isItemEnabled(player.level().enabledFeatures())) return;
        if (includeData)
            com.koper.koper_lib.mixin.ServerGamePacketAccessor.koper$addBlockDataToItem(
                hit.state(), (ServerLevel) player.level(), hit.logicalPos(), stack);
        ((com.koper.koper_lib.mixin.ServerGamePacketAccessor) player.connection).koper$tryPickItem(stack);
    }

    private static void breakBlock(ServerPlayer player, KenderBreakPayload payload) {
        ServerLevel level = (ServerLevel) player.level();
        KoperPhys.applyAttackImpulse(player, payload.kontraId(),
            payload.localX(), payload.localY(), payload.localZ(),
            payload.hitX(), payload.hitY(), payload.hitZ());
        KoperPhys.breakBlockAtLocal(level, player, payload.kontraId(),
            payload.localX(), payload.localY(), payload.localZ(), payload.localDirection(),
            payload.hitX(), payload.hitY(), payload.hitZ());
    }
}
