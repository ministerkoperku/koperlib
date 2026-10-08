package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// the interaction ack packet reads level.getBlockState — through the kontra projection overlay.
// every click near a kontra acked the KONTRA's block back to the client as the real world state:
// wand click = instant static phantom of the clicked block, breaking a phantom = server correctly
// cancels but the ack re-confirms it (the "indestructible" ones), placing next to a kontra = your
// block acked into a kontra-block phantom. packets must always carry the REAL chunk state.
@Mixin(ClientboundBlockUpdatePacket.class)
public abstract class KontraBlockAckMixin {

    @Redirect(method = "<init>(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState koperlib$realAckState(BlockGetter getter, BlockPos pos) {
        if (getter instanceof ServerLevel level) {
            BlockState real = KoperPhys.realBlockState(level, pos);
            // ghost hunt: this is the exact state that goes on the wire for a single block update
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                    "[GhostProbe] packet built for {} carrying {} (projection={})",
                    pos, real.getBlock(), KoperPhys.getBlockStateAt(level, pos) != null);
            return real;
        }
        return getter.getBlockState(pos);
    }
}
