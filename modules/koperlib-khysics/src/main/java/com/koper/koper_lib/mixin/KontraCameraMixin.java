package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraRideClient;
import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.physics.KoperPhys;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class KontraCameraMixin {

    @Shadow private Vec3 position;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private Entity entity;
    @Shadow private boolean detached;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void koper$tiltWithKontra(float tickDelta, CallbackInfo ci) {
        if (this.entity == null) return;
        KontraRideClient.CameraMode mode = KontraRideClient.cameraMode();
        Vec3 rideOffset = KontraRideClient.renderRideOffset(this.entity, tickDelta);
        Vec3 shiftedPos = rideOffset.lengthSqr() > 1.0e-8 ? this.position.add(rideOffset) : this.position;
        if (mode == KontraRideClient.CameraMode.VANILLA) {
            if (shiftedPos != this.position) this.setPosition(shiftedPos);
            return;
        }
        if (mode != KontraRideClient.CameraMode.KONTRA_ROT) {
            if (this.detached) {
                this.setPosition(koper$lockedThirdPersonPos(tickDelta, shiftedPos, rideOffset));
            } else if (shiftedPos != this.position) {
                this.setPosition(shiftedPos);
            }
            return;
        }

        float[] q = KontraRideClient.visualRideRot(this.entity);
        if (q == null || q.length < 4) {
            if (shiftedPos != this.position) this.setPosition(shiftedPos);
            return;
        }
        if (Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]) < 1.0e-5f && Math.abs(q[3] - 1.0f) < 1.0e-5f) {
            if (shiftedPos != this.position) this.setPosition(shiftedPos);
            return;
        }

        if (this.detached) {
            Vec3 anchor = koper$eyeAnchor(tickDelta).add(rideOffset);
            Vec3 rel = shiftedPos.subtract(anchor);
            double rideCameraDist = KontraRideClient.renderRideCameraDistance(this.entity);
            if (rideCameraDist > 0.0 && rel.lengthSqr() > 1.0e-8) {
                rel = rel.normalize().scale(Math.max(rel.length(), rideCameraDist));
            }
            this.setPosition(anchor.add(koper$rotate(rel, q)));
        } else if (shiftedPos != this.position) {
            this.setPosition(shiftedPos);
        }

        this.rotation.set(new Quaternionf(q[0], q[1], q[2], q[3]).normalize().mul(this.rotation));
        this.forwards.set(0.0f, 0.0f, -1.0f).rotate(this.rotation);
        this.up.set(0.0f, 1.0f, 0.0f).rotate(this.rotation);
        this.left.set(-1.0f, 0.0f, 0.0f).rotate(this.rotation);
    }

    @Redirect(method = "getMaxZoom",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
              require = 0)
    private BlockHitResult koper$clipKontraCamera(Level level, ClipContext context) {
        boolean prev = KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get();
        KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
        BlockHitResult vanilla;
        try {
            vanilla = level.clip(context);
        } finally {
            KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(prev);
        }

        Vec3 from = context.getFrom();
        Vec3 to = context.getTo();
        float best = (float)from.distanceTo(to);
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) {
            best = (float)Math.sqrt(vanilla.getLocation().distanceToSqr(this.position));
        }

        BlockHitResult kontra = KenderTargeting.cameraClip(from, to, best);
        if (kontra != null) return kontra;
        return vanilla;
    }

    private Vec3 koper$eyeAnchor(float tickDelta) {
        return new Vec3(
                Mth.lerp((double)tickDelta, this.entity.xo, this.entity.getX()),
                Mth.lerp((double)tickDelta, this.entity.yo, this.entity.getY()) + Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight),
                Mth.lerp((double)tickDelta, this.entity.zo, this.entity.getZ()));
    }

    private Vec3 koper$lockedThirdPersonPos(float tickDelta, Vec3 shiftedPos, Vec3 rideOffset) {
        double rideCameraDist = KontraRideClient.renderRideCameraDistance(this.entity);
        if (rideCameraDist <= 0.0) return shiftedPos;
        Vec3 anchor = koper$eyeAnchor(tickDelta).add(rideOffset);
        Vec3 rel = shiftedPos.subtract(anchor);
        if (rel.lengthSqr() <= 1.0e-8) return shiftedPos;
        return anchor.add(rel.normalize().scale(Math.max(rel.length(), rideCameraDist)));
    }

    private static Vec3 koper$rotate(Vec3 v, float[] q) {
        double tx = 2.0 * (q[1] * v.z - q[2] * v.y);
        double ty = 2.0 * (q[2] * v.x - q[0] * v.z);
        double tz = 2.0 * (q[0] * v.y - q[1] * v.x);
        return new Vec3(
                v.x + q[3] * tx + q[1] * tz - q[2] * ty,
                v.y + q[3] * ty + q[2] * tx - q[0] * tz,
                v.z + q[3] * tz + q[0] * ty - q[1] * tx);
    }
}
