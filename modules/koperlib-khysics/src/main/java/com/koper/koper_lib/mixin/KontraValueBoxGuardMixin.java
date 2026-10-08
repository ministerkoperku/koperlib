package com.koper.koper_lib.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.ValueBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// create keys its value-box outlines by world pos. a kontra slides on and that pos suddenly holds a
// different (projected) block -> MotorValueBox does state.getValue(FACING) on oak planks and the game dies.
// stale box = skip the frame, outliner retires it on its own.
@Mixin(value = ValueBox.class, remap = false)
public abstract class KontraValueBoxGuardMixin {
    @Shadow protected BlockPos pos;
    @Shadow protected BlockState blockState;

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void koperlib$staleBoxGuard(Minecraft mc, PoseStack poseStack, SubmitNodeCollector collector,
                                        Vec3 camera, float pt, CallbackInfo ci) {
        if (com.koper.koper_lib.kender.KenderTargeting.targetsProjected(pos, blockState)) return;
        if (blockState != null && mc.level != null
                && mc.level.getBlockState(pos).getBlock() != blockState.getBlock())
            ci.cancel();
    }

    @WrapOperation(method = "submit",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0))
    private void koperlib$movingBoxPose(PoseStack poseStack, double x, double y, double z,
                                        Operation<Void> original, Minecraft mc, PoseStack ignored,
                                        SubmitNodeCollector collector, Vec3 camera, float pt) {
        var hit = com.koper.koper_lib.kender.KenderTargeting.getHit();
        var grid = com.koper.koper_lib.kender.KenderTargeting.targetedGrid();
        if (hit == null || grid == null
                || !com.koper.koper_lib.kender.KenderTargeting.targetsProjected(pos, blockState)) {
            original.call(poseStack, x, y, z);
            return;
        }
        float[] bodyPos = com.koper.koper_lib.kender.KenderClientState.renderPos(grid, System.nanoTime());
        float[] bodyRot = com.koper.koper_lib.kender.KenderClientState.renderRot(grid, System.nanoTime());
        float[] offset = grid.offsetsByLocal.get(hit.localPos());
        if (bodyPos == null || bodyRot == null || offset == null) {
            original.call(poseStack, x, y, z);
            return;
        }
        Quaternionf rotation = new Quaternionf(bodyRot[0], bodyRot[1], bodyRot[2], bodyRot[3]);
        Vector3f centerOffset = new Vector3f(offset[0], offset[1], offset[2]);
        rotation.transform(centerOffset);
        poseStack.translate(bodyPos[0] + centerOffset.x - camera.x,
            bodyPos[1] + centerOffset.y - camera.y,
            bodyPos[2] + centerOffset.z - camera.z);
        poseStack.rotate(rotation);
        poseStack.translate(-0.5, -0.5, -0.5);
    }
}
