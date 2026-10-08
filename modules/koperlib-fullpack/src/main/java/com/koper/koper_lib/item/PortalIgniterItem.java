package com.koper.koper_lib.item;

import com.koper.koper_lib.loader.ContentRegistry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class PortalIgniterItem extends Item {
    private final String dimension;
    private final String frameBlock;

    public PortalIgniterItem(Item.Properties settings, String dimension, String frameBlock) {
        super(settings);
        this.dimension = dimension;
        this.frameBlock = frameBlock;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level Level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        Player player = context.getPlayer();

        if (Level.isClientSide()) return InteractionResult.SUCCESS;

        Block frame = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(frameBlock));
        if (frame == null) frame = Blocks.OBSIDIAN;

        if (Level.getBlockState(pos.below()).is(frame)) {
            Block portalBlock = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("koper_lib", dimension.replace(":", "_") + "_portal"));
            if (portalBlock != null && portalBlock != Blocks.AIR) {
                Level.setBlockAndUpdate(pos, portalBlock.defaultBlockState());
            }
            context.getItemInHand().setDamageValue(context.getItemInHand().getDamageValue() + 1);
            if (context.getItemInHand().getDamageValue() >= context.getItemInHand().getMaxDamage()) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }
}
