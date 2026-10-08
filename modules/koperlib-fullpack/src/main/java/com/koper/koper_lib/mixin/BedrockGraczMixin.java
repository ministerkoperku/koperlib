package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// bedrock before/after events for breaking, using and clicking blocks. the before ones can cancel
@Mixin(ServerPlayerGameMode.class)
public abstract class BedrockGraczMixin {

    @Shadow @Final protected ServerPlayer player;
    @Shadow protected ServerLevel level;

    @Unique private BlockState koperlib$wasState;
    @Unique private ItemStack koperlib$wasHeld;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockBeforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        koperlib$wasState = level.getBlockState(pos);
        koperlib$wasHeld = player.getMainHandItem().copy();
        if (BedrockUszy.beforeBreak(player, pos)) cir.setReturnValue(false);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void koperlib$bedrockAfterBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && koperlib$wasState != null) BedrockUszy.afterBreak(player, pos, koperlib$wasState, koperlib$wasHeld);
        koperlib$wasState = null;
        koperlib$wasHeld = null;
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockBeforeUse(ServerPlayer who, Level world, ItemStack stack, InteractionHand hand,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        if (BedrockUszy.beforeUse(who, stack)) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void koperlib$bedrockAfterUse(ServerPlayer who, Level world, ItemStack stack, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue() != InteractionResult.FAIL) BedrockUszy.afterUse(who, stack);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockBeforeUseOn(ServerPlayer who, Level world, ItemStack stack, InteractionHand hand,
                                             BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.MAIN_HAND && BedrockUszy.beforeUseOn(who, stack, hit)) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void koperlib$bedrockAfterUseOn(ServerPlayer who, Level world, ItemStack stack, InteractionHand hand,
                                            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.MAIN_HAND && cir.getReturnValue() != InteractionResult.FAIL) BedrockUszy.afterUseOn(who, stack, hit);
    }
}
