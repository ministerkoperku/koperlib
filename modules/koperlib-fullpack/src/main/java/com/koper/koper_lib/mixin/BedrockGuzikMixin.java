package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// buttonPush. arrows pressing a button skip press() in java, so those stay silent for now
@Mixin(ButtonBlock.class)
public abstract class BedrockGuzikMixin {

    @Inject(method = "press", at = @At("RETURN"))
    private void koperlib$wcisniety(BlockState state, Level level, BlockPos pos, Player player, CallbackInfo ci) {
        BedrockUszy.buttonPushed(level, pos, player);
    }
}
