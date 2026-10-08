package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// intercepts block breaks BEFORE vanilla does anything
// if the target pos belongs to a kontraktion → handle it ourselves and cancel vanilla
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerBreakMixin {

    @Shadow public ServerPlayer player;
    @Shadow public ServerLevel level;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // KenderBreakPayload (client → server, carries kontraId+local) is the sole break authority —
        // it's correct even for rotated kontras. here we only CANCEL the vanilla STOP_DESTROY
        // completion so it can't double-break a different cell or chew a real block at the same spot.
        // recently-broke guard handles the packet-order race (payload may arrive before STOP_DESTROY).
        // real block in the cell (static clone litter) = vanilla owns the break, otherwise clones
        // are indestructible and the deck block eats their clicks
        // the recently-broken guard only ever meant "do not let STOP_DESTROY break the phantom a
        // second time". a kontra sits in cells that hold REAL blocks too, so marking a world cell
        // because a kontra block there was broken made every real block in it unbreakable for half
        // a second — while still telling the player it broke. the client drops it, the server keeps
        // it, and the two disagree. never swallow a break when there is something real to break.
        if (KoperPhys.realBlockState(level, pos).isAir()
                && (KoperPhys.getBlockStateAt(level, pos) != null
                    || KoperPhys.wasRecentlyKontraBroken(pos))) {
            if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
                com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[GridDbg] vanilla break CANCELLED at {} (projection={}, recentGridBreak={})",
                    pos, KoperPhys.getBlockStateAt(level, pos) != null, KoperPhys.wasRecentlyKontraBroken(pos));
            cir.setReturnValue(true);
        }
    }
}
