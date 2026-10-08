package com.koper.koper_lib.kfx;

import com.koper.koper_lib.api.core.KoperNetwork;
import com.koper.koper_lib.network.KfxAttachBetweenPayload;
import com.koper.koper_lib.network.KfxAttachPayload;
import com.koper.koper_lib.network.KfxAnchorPayload;
import com.koper.koper_lib.network.KfxClearPayload;
import com.koper.koper_lib.network.KfxDetachPayload;
import com.koper.koper_lib.network.KfxImpactPayload;
import com.koper.koper_lib.network.KfxMotionBatchPayload;
import com.koper.koper_lib.network.KfxPropertiesPayload;
import com.koper.koper_lib.network.KfxSpawnPayload;
import com.koper.koper_lib.network.KfxStopPayload;
import com.koper.koper_lib.network.KfxUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

// Koper Effects owns its packet types and client handlers, core only provides the wire
public final class KfxNetworking {
    private KfxNetworking() {}

    public static void init() {
        KoperNetwork.clientbound("effects", KfxSpawnPayload.TYPE, KfxSpawnPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxUpdatePayload.TYPE, KfxUpdatePayload.CODEC);
        KoperNetwork.clientbound("effects", KfxMotionBatchPayload.TYPE, KfxMotionBatchPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxPropertiesPayload.TYPE, KfxPropertiesPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxStopPayload.TYPE, KfxStopPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxAttachPayload.TYPE, KfxAttachPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxAttachBetweenPayload.TYPE, KfxAttachBetweenPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxAnchorPayload.TYPE, KfxAnchorPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxDetachPayload.TYPE, KfxDetachPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxImpactPayload.TYPE, KfxImpactPayload.CODEC);
        KoperNetwork.clientbound("effects", KfxClearPayload.TYPE, KfxClearPayload.CODEC);
    }

    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(KfxSpawnPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                KfxInstance fx = payload.toInstance();
                boolean nativeReady = fx.kind == KfxDef.Kind.EMITTER
                    ? KfxKenderAdapter.spawn(fx)
                    : KfxBackend.KENDER_NATIVE.submit(fx);
                KfxRenderer.nativeReady(fx.id, nativeReady);
                KfxBackend.MC_PATH.submit(fx);
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxUpdatePayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.update(payload.id(),
                payload.sx(), payload.sy(), payload.sz(), payload.ex(), payload.ey(), payload.ez()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxMotionBatchPayload.TYPE, (payload, context) ->
            context.client().execute(() -> payload.motions().forEach(motion -> KfxClient.update(motion.id(),
                motion.sx(), motion.sy(), motion.sz(), motion.ex(), motion.ey(), motion.ez())))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxPropertiesPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.updateProperties(payload.id(), payload.color(), payload.color2(),
                payload.radius(), payload.thickness()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxStopPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.stop(payload.id()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxAttachPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.attach(payload.id(), payload.entityId(),
                payload.ox(), payload.oy(), payload.oz(), payload.ex(), payload.ey(), payload.ez(), payload.endRelative()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxAttachBetweenPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.attachBetween(payload.id(),
                payload.startEntityId(), payload.startOx(), payload.startOy(), payload.startOz(),
                payload.endEntityId(), payload.endOx(), payload.endOy(), payload.endOz()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxAnchorPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.anchor(payload.id(), payload.start(), payload.end()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxDetachPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.detach(payload.id()))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxImpactPayload.TYPE, (payload, context) ->
            context.client().execute(() -> KfxClient.impact(payload))
        );

        ClientPlayNetworking.registerGlobalReceiver(KfxClearPayload.TYPE, (payload, context) ->
            context.client().execute(KfxClient::clear)
        );
    }
}
