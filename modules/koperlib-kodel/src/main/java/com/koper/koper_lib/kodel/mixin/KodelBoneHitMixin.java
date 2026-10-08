package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.api.core.KoperBonePositions;
import com.koper.koper_lib.kodel.KodelBoneHitPayload;
import com.koper.koper_lib.kodel.KodelEntities;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Optional;

// a left click on a mob with damage bones: ray against the boxes on those bones, tell the server which one
@Mixin(MultiPlayerGameMode.class)
public class KodelBoneHitMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void interceptBoneHit(Player player, Entity target, CallbackInfo ci) {
        Map<String, KodelEntities.Box> hitboxes = KodelEntities.boxes(target.getType());
        if (hitboxes.isEmpty()) return;

        // Get animated bone positions captured during this frame's rendering
        Map<String, Vec3> bonePositions = KoperBonePositions.grouped().get(target.getId());
        if (bonePositions == null || bonePositions.isEmpty()) return; // No data yet — allow vanilla attack

        // Ray-cast from player eyes against each bone's AABB
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(8.0));

        String hitBone = null;
        float hitMult = 1.0f;
        double closestSq = Double.MAX_VALUE;

        for (Map.Entry<String, Vec3> entry : bonePositions.entrySet()) {
            String boneName = entry.getKey();
            Vec3 bp = entry.getValue();
            KodelEntities.Box def = hitboxes.get(boneName);
            if (def == null) continue;

            // Half-extents
            float hw = Math.max(Math.max(def.width(), def.depth()), 0.5f) / 2f;
            float hh = Math.max(def.height(), 0.5f) / 2f;

            AABB box = new AABB(bp.x - hw, bp.y - hh, bp.z - hw,
                               bp.x + hw, bp.y + hh, bp.z + hw);

            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isPresent()) {
                double distSq = hit.get().distanceToSqr(eye);
                if (distSq < closestSq) {
                    closestSq = distSq;
                    hitBone = boneName;
                    hitMult = def.damageMultiplier();
                }
            }
        }

        if (hitBone != null) {
            // Swing arm (client visual) and send bone hit to server
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            ClientPlayNetworking.send(new KodelBoneHitPayload(target.getId(), hitBone, hitMult));
            ci.cancel(); // Don't send vanilla PlayerAttackEntityC2SPacket
        }
        // If hitBone == null: player clicked entity area but missed all bones → vanilla attack as fallback
    }
}
