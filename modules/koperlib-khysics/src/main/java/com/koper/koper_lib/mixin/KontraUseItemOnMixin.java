package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class KontraUseItemOnMixin {
    @WrapOperation(method = "handleUseItemOn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult koperlib$useKontraBlock(ServerPlayerGameMode gameMode, ServerPlayer player, Level level,
                                                       ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                                       Operation<InteractionResult> original) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel))
            return original.call(gameMode, player, level, stack, hand, hit);
        // KOPER'S RULE: being unable to build here is fine, quietly eating the block out of a
        // survival inventory is not. if nothing ended up placed — not in the world, not on a
        // kontraption — the stack goes back exactly as it was.
        //
        // the grid check is what stops this being a duplication glitch: a block welded onto a
        // kontraption IS placed, the world cells just do not show it.
        int before = stack.getCount();
        int gridBefore = koperlib$gridBlocks(serverLevel, hit);
        InteractionResult out = koperlib$route(gameMode, player, level, stack, hand, hit, original, serverLevel);
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem
                && stack.getCount() < before
                && koperlib$gridBlocks(serverLevel, hit) == gridBefore
                && KoperPhys.realBlockState(serverLevel, hit.getBlockPos()).isAir()
                && KoperPhys.realBlockState(serverLevel, hit.getBlockPos().relative(hit.getDirection())).isAir()) {
            stack.setCount(before);
            player.containerMenu.sendAllDataToRemote();
            return out;
        }
        // the write may have been swallowed somewhere below without the server stack ever moving —
        // routeProjectionWrite does exactly that. it pushes the real block state back to the client
        // but nothing pushes the INVENTORY back, so the client keeps the item it predicted away and
        // the block looks like it vanished out of survival. resync whenever the click went anywhere
        // near a machine; it is one packet and only on those clicks
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem
                && (KoperPhys.getBlockStateAt(serverLevel, hit.getBlockPos()) != null
                    || KoperPhys.getBlockStateAt(serverLevel, hit.getBlockPos().relative(hit.getDirection())) != null))
            player.containerMenu.sendAllDataToRemote();
        return out;
    }

    // how many blocks the kontraption touching this cell is made of. if that went up, the block
    // was placed onto the machine and must NOT come back
    @org.spongepowered.asm.mixin.Unique
    private static int koperlib$gridBlocks(net.minecraft.server.level.ServerLevel level, BlockHitResult hit) {
        KoperPhys.GridUse use = KoperPhys.gridUseAt(level, hit);
        return use == null ? -1 : use.grid().entry().blocks.size();
    }

    @org.spongepowered.asm.mixin.Unique
    private InteractionResult koperlib$route(ServerPlayerGameMode gameMode, ServerPlayer player, Level level,
                                             ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                             Operation<InteractionResult> original,
                                             net.minecraft.server.level.ServerLevel serverLevel) {
        // real block in the clicked cell (clone litter) → vanilla owns the click. without this the
        // fuzzy grid match steals it and a static lever toggles the kontra's lever instead of itself.
        if (!KoperPhys.realBlockState(serverLevel, hit.getBlockPos()).isAir())
            return original.call(gameMode, player, level, stack, hand, hit);
        // a machine owning a pixel or two of this cell does not get to claim the click. without
        // this the block is routed into the grid and welded ONTO the kontraption — from where you
        // are standing the block and the item both just vanish. patching a hole under a wheel is
        // the whole point, so let it land in the world and let physics shove the machine up
        if (KoperPhys.kontraBarelyThere(serverLevel, hit.getBlockPos()))
            return original.call(gameMode, player, level, stack, hand, hit);

        KoperPhys.GridUse use = KoperPhys.gridUseAt(serverLevel, hit);
        if (use == null) {
            // vanilla packet aimed at a kontra projection but the grid lookup missed → vanilla would
            // place a REAL block against a phantom = the static plate/target clones. eat the click.
            // a machine owning a pixel or two of the cell does not get to veto the placement —
            // let it land and let physics shove the machine up off it
            if (KoperPhys.getBlockStateAt(serverLevel, hit.getBlockPos()) != null
                    && KoperPhys.realBlockState(serverLevel, hit.getBlockPos()).isAir())
                return eat(player);
            return original.call(gameMode, player, level, stack, hand, hit);
        }
        // the precise payload path may already have run this exact click — don't toggle it again
        if (!KoperPhys.claimGridUse(player, use.hit().getBlockPos())) return InteractionResult.CONSUME;
        return KoperPhys.gridPlacementTransaction(player, use.grid(), stack, () ->
            KontraGridContext.call(use.grid(), () -> {
                KoperPhys.GRID_PLACEMENT.set(true);
                try { return original.call(gameMode, player, level, stack, hand, use.hit()); }
                finally { KoperPhys.GRID_PLACEMENT.set(false); }
            }));
    }

    // we are throwing this click away, and the client already predicted it: the stack is one
    // shorter over there and nothing is coming to correct it. that is how a block placed next to
    // a kontraption vanished out of the inventory for good. push the whole menu back.
    private static InteractionResult eat(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
        return InteractionResult.CONSUME;
    }
}
