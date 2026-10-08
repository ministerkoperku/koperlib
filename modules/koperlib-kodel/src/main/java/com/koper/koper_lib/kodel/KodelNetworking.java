package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Script clips (server tells clients what to play) and bone hits (a client says which bone it struck). */
public final class KodelNetworking {
    private KodelNetworking() {}

    /** Further than this from the struck entity and a bone hit is not believed. */
    private static final double REACH_SQ = 10.0 * 10.0;

    public static void init() {
        KoperNetwork.clientbound("kodel", KodelClipPayload.TYPE, KodelClipPayload.CODEC);
        KoperNetwork.serverbound("kodel", KodelBoneHitPayload.TYPE, KodelBoneHitPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(KodelBoneHitPayload.TYPE, (payload, context) ->
            context.server().execute(() -> {
                var player = context.player();
                ServerLevel level = (ServerLevel) player.level();
                var target = level.getEntity(payload.entityId());
                if (target == null || !target.isAlive() || player.distanceToSqr(target) > REACH_SQ) return;
                // the client only names the bone; how much it is worth comes from the server's own binding
                KodelEntities.Box box = KodelEntities.boxes(target.getType()).get(payload.bone());
                float multiplier = box != null ? box.damageMultiplier() : 1f;
                float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE) * multiplier
                    * com.koper.koper_lib.api.core.KoperGameplayBridge.damageMultiplier();
                if (damage <= 0) return;
                var source = level.damageSources().playerAttack(player);
                if (target.hurtServer(level, source, damage) && target instanceof LivingEntity living)
                    living.knockback(0.4, player.getX() - target.getX(), player.getZ() - target.getZ(), source, 0.0F);
            }));
    }
}
