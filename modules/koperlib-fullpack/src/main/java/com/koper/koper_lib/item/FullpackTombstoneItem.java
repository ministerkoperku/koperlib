package com.koper.koper_lib.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

// Keeps a Fullpack item's id alive after a restart without its pack, so stacks in inventories and
// chests load as the same item with the same components instead of vanishing.
public class FullpackTombstoneItem extends Item {

    private final String missingId;

    public FullpackTombstoneItem(Properties props, String missingId) {
        super(props);
        this.missingId = missingId;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Missing: " + missingId);
    }

    public static class OfBlock extends BlockItem {

        private final String missingId;

        public OfBlock(Block block, Properties props, String missingId) {
            super(block, props);
            this.missingId = missingId;
        }

        @Override
        public Component getName(ItemStack stack) {
            return Component.literal("Missing: " + missingId);
        }

        @Override
        public InteractionResult place(BlockPlaceContext context) {
            return InteractionResult.FAIL;
        }
    }
}
