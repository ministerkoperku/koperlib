package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// block-update packets read level.getBlockState — THROUGH the kontra projection overlay. any world
// change under a resting kontra (grass dying etc.) broadcast the KONTRA's block as the real state,
// the client chunk kept it forever = the phantom/shadow blocks left behind after launch. packets
// must carry what's actually stored in the chunk, never the overlay.
@Mixin(ChunkHolder.class)
public abstract class KontraChunkSendMixin {

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void koperlib$realIn(LevelChunk chunk, CallbackInfo ci) {
        KoperPhys.REAL_WORLD_LOOKUP.set(true);
    }

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void koperlib$realOut(LevelChunk chunk, CallbackInfo ci) {
        KoperPhys.REAL_WORLD_LOOKUP.set(false);
    }
}
