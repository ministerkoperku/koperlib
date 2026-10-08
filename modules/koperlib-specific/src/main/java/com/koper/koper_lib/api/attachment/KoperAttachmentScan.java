package com.koper.koper_lib.api.attachment;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// every connection point around the player, cached. both the overlay draw and the connector's own
// targeting used to walk a 17x13x17 box of getBlockState EVERY FRAME — that is ~3.7k block lookups
// per frame just for holding a tool, which a phone feels immediately
@Environment(EnvType.CLIENT)
public final class KoperAttachmentScan {

    public record Spot(BlockPos pos, BlockState state, KoperAttachmentPoint point) {}

    private static final int REFRESH_TICKS = 10;
    private static List<Spot> cache = List.of();
    private static long stamp = Long.MIN_VALUE;
    private static BlockPos anchor;
    private static int radius;
    private static int height;

    private KoperAttachmentScan() {}

    public static List<Spot> world(Minecraft mc, int radiusXZ, int radiusY) {
        if (mc.player == null || mc.level == null) return List.of();
        long now = mc.level.getGameTime();
        BlockPos center = mc.player.blockPosition();
        boolean stale = anchor == null || radius != radiusXZ || height != radiusY
            || now - stamp >= REFRESH_TICKS || now < stamp
            || center.distSqr(anchor) > 4;
        if (!stale) return cache;

        stamp = now;
        anchor = center;
        radius = radiusXZ;
        height = radiusY;
        List<Spot> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radiusXZ, -radiusY, -radiusXZ),
                center.offset(radiusXZ, radiusY, radiusXZ))) {
            // the targeted moving-grid block is projected into Level#getBlockState for vanilla
            // outlines. a world scan must bypass that or every moving port gets a static twin
            BlockState state = com.koper.koper_lib.api.core.KenderMovingGrids.outsideGrid(
                () -> mc.level.getBlockState(pos));
            var points = KoperAttachments.points(state);
            if (points.isEmpty()) continue;
            BlockPos immutable = pos.immutable();
            for (KoperAttachmentPoint point : points) found.add(new Spot(immutable, state, point));
        }
        cache = List.copyOf(found);
        return cache;
    }

    // a block was placed/broken right next to you — do not make the player wait half a second
    public static void invalidate() {
        stamp = Long.MIN_VALUE;
    }
}
