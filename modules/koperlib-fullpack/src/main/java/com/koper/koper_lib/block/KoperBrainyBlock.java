package com.koper.koper_lib.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

// every factory-built pack block extends this. blocks that didn't ask for a brain return null
// from newBlockEntity — LevelChunk null-checks it, so nothing is allocated for plain decoration
public abstract class KoperBrainyBlock extends Block implements EntityBlock {

    protected KoperBrainyBlock(Properties props) {
        super(props);
    }

    // asked at call time, never cached. baking this into a final field would mean flipping
    // block_entity in json needed a restart, and everything in koperlib reloads
    public boolean brainy() { return KoperBrainRegistry.wants(this); }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (!brainy()) return null;
        // MC validates the same set in the BlockEntity constructor and throws if it disagrees,
        // which crashes the client mid placement. never hand it something it will reject
        if (!KoperBrainRegistry.TYPE.isValid(state)) return null;
        return new KoperBlockBrain(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != KoperBrainRegistry.TYPE || !brainy()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<KoperBlockBrain>) KoperBlockBrain::serverTick;
    }

    // comparators. without this a pack machine can't drive redstone off its own contents
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return KoperBrainRegistry.slotCount(this) > 0;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction dir) {
        return level.getBlockEntity(pos) instanceof KoperBlockBrain brain
            ? net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(brain)
            : 0;
    }
}
