package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

// riders are client-predicted now — the client carries itself along the kontra and the server's
// re-simulation can lag the interpolated transform by a chunk of a tick. loosen the "moved wrongly"
// gate for them or every ship ride turns into rubber-band hell.
// 26.3 moved the checks out of handleMovePlayer into handlePlayerPositionChange (same 0.0625 gate, same call)
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class KontraMoveValidateMixin {

    @Shadow public ServerPlayer player;

    @ModifyConstant(method = "handlePlayerPositionChange(DDDFFZZ)V",
                    constant = @Constant(doubleValue = 0.0625D), require = 1)
    private double koper$riderWrongTolerance(double orig) {
        return KoperPhys.softMoveValidation(this.player) ? 9.0 : orig;
    }

    @Redirect(method = "handlePlayerPositionChange(DDDFFZZ)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isEntityCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;DDD)Z"),
              require = 1)
    private boolean koper$riderNewCollision(ServerGamePacketListenerImpl self, LevelReader level, Entity entity,
                                            AABB oldAABB, double newX, double newY, double newZ) {
        if (KoperPhys.softMoveValidation(this.player)) return false;
        return ((ServerGamePacketAccessor) self).koper$collidingWithAnythingNew(level, entity, oldAABB, newX, newY, newZ);
    }
}
