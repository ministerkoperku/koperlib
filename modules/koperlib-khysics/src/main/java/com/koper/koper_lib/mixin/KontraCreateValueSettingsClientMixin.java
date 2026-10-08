package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderTargeting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.zurrtum.create.client.foundation.blockEntity.ValueSettingsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ValueSettingsClient.class, remap = false)
public abstract class KontraCreateValueSettingsClientMixin {

    @Shadow public BlockPos interactHeldPos;

    @WrapMethod(method = "tick")
    private void koperlib$keepMovingValueBoxHeld(Minecraft mc, Operation<Void> original) {
        KenderTargeting.PhysHit hit = KenderTargeting.getHit();
        if (hit == null || interactHeldPos == null || !hit.logicalPos().equals(interactHeldPos)
                || !(mc.hitResult instanceof BlockHitResult worldHit)) {
            original.call(mc);
            return;
        }

        HitResult previous = mc.hitResult;
        mc.hitResult = new BlockHitResult(worldHit.getLocation(), hit.localFace(),
            interactHeldPos, worldHit.isInside());
        try {
            original.call(mc);
        } finally {
            if (mc.hitResult instanceof BlockHitResult current
                    && current.getBlockPos().equals(interactHeldPos))
                mc.hitResult = previous;
        }
    }
}
