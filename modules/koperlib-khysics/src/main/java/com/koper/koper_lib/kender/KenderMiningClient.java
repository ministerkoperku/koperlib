package com.koper.koper_lib.kender;

import com.koper.koper_lib.network.KenderBreakPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

public final class KenderMiningClient {

    private static long body = -1L;
    private static BlockPos local;
    private static ItemStack tool = ItemStack.EMPTY;
    private static float progress;
    private static int ticks;
    private static int waitingForServer;

    private KenderMiningClient() {}

    public static void begin(Minecraft mc, KenderTargeting.PhysHit hit) {
        if (same(hit)) return;
        body = hit.kontraId();
        local = hit.localPos().immutable();
        tool = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem().copy();
        progress = 0f;
        ticks = 0;
        waitingForServer = 0;
    }

    public static boolean continueMining(Minecraft mc, boolean attackDown) {
        boolean wasActive = active();
        if (!attackDown || mc.player == null || mc.level == null || mc.player.isCreative()) {
            if (wasActive) cancel();
            return wasActive;
        }

        KenderTargeting.PhysHit hit = KenderTargeting.getHit();
        if (hit == null) {
            if (wasActive) cancel();
            return wasActive;
        }
        if (!same(hit)) begin(mc, hit);

        if (!ItemStack.isSameItemSameComponents(tool, mc.player.getMainHandItem())) {
            tool = mc.player.getMainHandItem().copy();
            progress = 0f;
            ticks = 0;
            waitingForServer = 0;
        }

        var state = KenderTargeting.targetedState();
        if (state == null || state.isAir()) {
            cancel();
            return true;
        }
        if (waitingForServer > 0) {
            waitingForServer--;
            return true;
        }

        float step = state.getDestroyProgress(mc.player, mc.level, hit.logicalPos());
        if (step <= 0f) return true;
        progress = Math.min(1f, progress + step);
        ticks++;
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), false);

        if ((ticks & 3) == 0 && mc.hitResult instanceof BlockHitResult worldHit) {
            var sound = state.getSoundType();
            var point = worldHit.getLocation();
            mc.level.playLocalSound(point.x, point.y, point.z, sound.getHitSound(),
                SoundSource.BLOCKS, (sound.getVolume() + 1f) / 8f,
                sound.getPitch() * 0.5f, false);
        }

        if (progress >= 1f) {
            ClientPlayNetworking.send(new KenderBreakPayload(hit.networkRef()));
            progress = 0f;
            waitingForServer = 8;
        }
        return true;
    }

    public static int stage(long kontraId, BlockPos localPos) {
        if (body != kontraId || local == null || !local.equals(localPos)
                || waitingForServer > 0 || progress <= 0f)
            return -1;
        return Math.min(9, (int)(progress * 10f));
    }

    public static boolean active() {
        return body >= 0L && local != null;
    }

    public static void cancel() {
        body = -1L;
        local = null;
        tool = ItemStack.EMPTY;
        progress = 0f;
        ticks = 0;
        waitingForServer = 0;
    }

    private static boolean same(KenderTargeting.PhysHit hit) {
        return hit != null && body == hit.kontraId() && local != null && local.equals(hit.localPos());
    }
}
