package com.koper.koper_lib.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;

public class IgniterItem extends Item {
    private final String portalBlockId;

    public IgniterItem(Item.Properties settings, String portalBlockId) {
        super(settings);
        this.portalBlockId = portalBlockId;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        if (level.getBlockState(pos).isAir()) {
            Block portalBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(portalBlockId));
            if (portalBlock != null) {
                level.setBlockAndUpdate(pos, portalBlock.defaultBlockState());
                context.getItemInHand().shrink(1);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.FAIL;
    }
}
