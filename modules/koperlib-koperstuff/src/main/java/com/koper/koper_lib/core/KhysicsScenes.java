package com.koper.koper_lib.core;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.ArrayList;
import java.util.List;

// /koperlib physics scene <name>: builds one Khysics release check in front of the player and
// turns it into a resting kontra, so a manual check starts from the same scene every time.
// the matching gametests live in Mechanics Dream (KhysicsReleaseGameTests).
public final class KhysicsScenes {
    public static final List<String> NAMES = List.of("redstone", "hopper", "light", "aligned", "edge");

    private KhysicsScenes() {}

    public static int build(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("scene needs a player")); return 0; }
        ServerLevel level = (ServerLevel) player.level();
        Direction ahead = player.getDirection();
        Direction side = ahead.getClockWise();
        BlockPos origin = player.blockPosition().relative(ahead, 3);
        List<BlockPos> cells = new ArrayList<>();
        String check;
        switch (name) {
            case "redstone" -> {
                for (int i = 0; i < 5; i++) cells.add(put(level, origin.relative(side, i), Blocks.STONE.defaultBlockState()));
                cells.add(put(level, origin.above(), Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, side)));
                for (int i = 1; i < 4; i++) cells.add(put(level, origin.relative(side, i).above(), Blocks.REDSTONE_WIRE.defaultBlockState()));
                cells.add(put(level, origin.relative(side, 4).above(), Blocks.REDSTONE_LAMP.defaultBlockState()));
                give(player, Items.REDSTONE, Items.LEVER, Items.REDSTONE_LAMP, Items.REPEATER, Items.OBSERVER);
                check = "KL-57: pull the lever, the lamp must light and the wire must glow. then build your own circuit on the deck";
            }
            case "hopper" -> {
                cells.add(put(level, origin, Blocks.STONE.defaultBlockState()));
                cells.add(put(level, origin.relative(side), Blocks.STONE.defaultBlockState()));
                cells.add(put(level, origin.above(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, side)));
                cells.add(put(level, origin.relative(side).above(), Blocks.CHEST.defaultBlockState()));
                if (level.getBlockEntity(origin.above()) instanceof HopperBlockEntity hopper)
                    hopper.setItem(0, new ItemStack(Items.DIAMOND, 16));
                check = "KL-57: within a few seconds the 16 diamonds must move from the hopper into the chest";
            }
            case "light" -> {
                for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++)
                    cells.add(put(level, origin.relative(ahead, a + 3).relative(side, b), Blocks.STONE.defaultBlockState()));
                cells.add(put(level, origin.relative(ahead, 3).above(), Blocks.TORCH.defaultBlockState()));
                for (int b = -3; b <= 3; b++) for (int y = 1; y <= 2; y++)
                    cells.add(put(level, origin.relative(ahead, 6).relative(side, b).above(y), Blocks.STONE.defaultBlockState()));
                check = "KL-59: at night or underground the torch must light the deck around it and the wall face, fading with distance";
            }
            case "aligned" -> {
                for (int a = 0; a < 3; a++) for (int b = -1; b <= 1; b++) for (int y = 0; y < 3; y++)
                    cells.add(put(level, origin.relative(ahead, a).relative(side, b).above(y), Blocks.STONE.defaultBlockState()));
                check = "KL-58: right away, before anything moves it, walk into every side and jump on top. no clipping in, no push out";
            }
            case "edge" -> {
                for (int a = 0; a < 3; a++) for (int b = -1; b <= 1; b++)
                    cells.add(put(level, origin.relative(ahead, a).relative(side, b), Blocks.STONE.defaultBlockState()));
                give(player, Items.NOTE_BLOCK, Items.OAK_PLANKS, Items.LANTERN);
                check = "KL-01: place blocks while aiming at the edges and corners of the deck blocks, both towards the world and away. "
                    + "every click must add one block that moves with the deck and collides";
            }
            default -> {
                source.sendFailure(Component.literal("unknown scene " + name + ", try " + String.join(", ", NAMES)));
                return 0;
            }
        }
        long id = KoperPhys.makeKontraktion(level, cells);
        if (id <= 0) {
            source.sendFailure(Component.literal("scene " + name + " could not become a kontra (" + id + ")"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Khysics scene] " + name + " is kontra " + id + ". " + check), false);
        return 1;
    }

    private static BlockPos put(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
        return pos;
    }

    private static void give(ServerPlayer player, net.minecraft.world.item.Item... items) {
        for (var item : items) player.getInventory().add(new ItemStack(item, 64));
    }
}
