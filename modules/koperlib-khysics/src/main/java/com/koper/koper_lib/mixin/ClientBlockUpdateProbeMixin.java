package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ghost hunt, client half: did the server's "this cell is air" actually land in our chunk?
// debugMode only. every other suspect on the server side checked out, so the next thing to know
// is whether the packet arrives at all and whether it sticks.
@Mixin(ClientLevel.class)
public abstract class ClientBlockUpdateProbeMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("TAIL"), require = 0)
    private void koperlib$probeServerState(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        if (!com.koper.koper_lib.config.KoperLibConfig.get().debugMode) return;
        if (KenderClientState.isEmpty()) return;
        ClientLevel self = (ClientLevel)(Object)this;
        boolean projected = KenderClientState.getClientBlockStateAt(pos) != null;
        // no filter: a packet carrying the WRONG state is exactly what we are hunting, and
        // filtering on air would hide it
        BlockState now = com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get()
            ? self.getBlockState(pos) : realClientState(self, pos);
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
            "[GhostProbe] server says {} at {} -> chunk now {} (kontraProjects={}){}",
            state.getBlock(), pos, now.getBlock(), projected,
            now.getBlock() == state.getBlock() ? "" : "  <<< CHUNK DISAGREES WITH SERVER");
    }

    private static BlockState realClientState(ClientLevel level, BlockPos pos) {
        var flag = com.koper.koper_lib.physics.KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS;
        boolean old = flag.get();
        flag.set(true);
        try { return level.getBlockState(pos); }
        finally { flag.set(old); }
    }
}
