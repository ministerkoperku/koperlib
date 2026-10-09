package com.koper.koper_lib.mixin;

import com.koper.koper_lib.block.KoperBlockBrain;
import com.koper.koper_lib.loader.FullpackTombstones;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

// A missing block has no loot table any more (it lived in its pack), so it would drop nothing.
// Breaking one gives back the block itself and everything it held instead; hardness is untouched,
// so a block that could not be broken before still cannot.
@Mixin(BlockBehaviour.class)
public abstract class FullpackTombstoneDropsMixin {

    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true)
    private void koperlib$tombstoneDrops(BlockState state, LootParams.Builder params,
            CallbackInfoReturnable<List<ItemStack>> cir) {
        Block block = state.getBlock();
        if (!FullpackTombstones.isMissing(block)) return;
        List<ItemStack> drops = new ArrayList<>();
        if (block.asItem() != Items.AIR) drops.add(new ItemStack(block.asItem()));
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof KoperBlockBrain brain) {
            for (ItemStack stack : brain.stacks()) if (!stack.isEmpty()) drops.add(stack.copy());
            brain.clearContent();
        }
        cir.setReturnValue(drops);
    }
}
