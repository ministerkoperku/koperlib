package com.koper.koper_lib.api.attachment;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.network.PlacementRotationPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

// placement is Specific API, its state and packet do not belong to fullpack or khysics
public final class KoperPlacementNetworking {
    private KoperPlacementNetworking() {}

    public static void init() {
        KoperNetwork.serverbound("specific", PlacementRotationPayload.TYPE, PlacementRotationPayload.CODEC);
        KoperNetwork.onLeave("specific", "placement-rotation", (player, server) ->
            KoperPlacementRotation.clear(player));
        ServerPlayNetworking.registerGlobalReceiver(PlacementRotationPayload.TYPE, (payload, context) ->
            context.server().execute(() -> KoperPlacementRotation.set(
                context.player(), payload.turns(), payload.axisTurns()))
        );
    }
}
