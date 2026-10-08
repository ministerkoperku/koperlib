package com.koper.koper_lib.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

// right-click block → add to selection
// sneak+right-click → if ≥1 block selected, spawn kontraktion from selection
// right-click air (attack) → clear selection
public class KhysSelectionWand extends Item {

    public KhysSelectionWand(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel sl)) return InteractionResult.PASS;

        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos pos = ctx.getClickedPos();

        if (player.isShiftKeyDown()) {
            // sneak + right click -> make physics from the cuboid selection
            BlockPos[] sel = KoperPhys.getTwoPointSelection(player.getUUID());
            if (sel[0] == null || sel[1] == null) {
                player.sendSystemMessage(Component.literal("§c[KoperLib] Select both Pos 1 (left-click) and Pos 2 (right-click) first!"));
                return InteractionResult.SUCCESS;
            }

            String problem = KoperPhys.cuboidProblem(sl, sel[0], sel[1]);
            if (problem != null) {
                player.sendSystemMessage(Component.literal("§c[KoperLib] " + problem + "."));
                return InteractionResult.SUCCESS;
            }
            List<BlockPos> list = KoperPhys.collectCuboidBlocks(sl, sel[0], sel[1]);

            if (list.isEmpty()) {
                player.sendSystemMessage(Component.literal("§c[KoperLib] No blocks in the selected region to turn into physics!"));
                return InteractionResult.SUCCESS;
            }

            long id = KoperPhys.makeKontraktion(sl, list);
            if (id >= 0) {
                player.sendSystemMessage(Component.literal("§a[KoperLib] Kontraktion spawned! (" + list.size() + " blocks, id=" + id + ")"));
                KoperPhys.clearTwoPointSelection(player.getUUID());
            } else {
                player.sendSystemMessage(Component.literal("§c[KoperLib] Spawn failed — check logs."));
            }
        } else {
            // normal right-click -> set Pos 2
            KoperPhys.getTwoPointSelection(player.getUUID())[1] = pos;
            player.sendSystemMessage(Component.literal("§a[KoperLib] Pos 2 set to " + pos.toShortString()));
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }
}
