package com.koper.koper_lib.compat.create;

import com.koper.koper_lib.kender.KenderTargeting;
import com.zurrtum.create.client.foundation.blockEntity.behaviour.ValueSettingsInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

public final class KenderCreateValueSettings {

    private KenderCreateValueSettings() {}

    public static boolean tryActivate(Minecraft mc, KenderTargeting.PhysHit hit) {
        if (mc.level == null || mc.player == null || !(mc.hitResult instanceof BlockHitResult worldHit))
            return false;
        BlockHitResult logicalHit = new BlockHitResult(worldHit.getLocation(), hit.localFace(),
            hit.logicalPos(), worldHit.isInside());
        return ValueSettingsInputHandler.onBlockActivated(
            mc.level, mc.player, InteractionHand.MAIN_HAND, logicalHit) != null;
    }
}
