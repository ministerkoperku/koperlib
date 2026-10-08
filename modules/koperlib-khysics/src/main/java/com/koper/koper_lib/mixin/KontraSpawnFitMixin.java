package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PostSpawnProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// spawn eggs fit the mob via getYOffset -> chunk collisions, where a rotated kontra does
// not exist -> the mob materializes inside the hull (or a block under it) and depen spits
// it out onto the terrain below. re-fit onto the deck with the SAT after vanilla is done.
// (26.2 release renamed the Consumer param to PostSpawnProcessor - the snapshot-5 decompile lied to me, fuck)
@Mixin(EntityType.class)
public abstract class KontraSpawnFitMixin {

    @Inject(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PostSpawnProcessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;",
            at = @At("RETURN"))
    private void koper$spawnOnDeck(ServerLevel level, PostSpawnProcessor<?> config, BlockPos spawnPos,
                                   EntitySpawnReason reason, boolean tryMoveDown, boolean movedUp,
                                   CallbackInfoReturnable<Entity> cir) {
        if (!tryMoveDown)
            return;
        KoperPhys.fitSpawnOnKontra(level, cir.getReturnValue(), spawnPos);
    }
}
