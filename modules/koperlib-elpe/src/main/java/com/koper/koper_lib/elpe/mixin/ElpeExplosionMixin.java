package com.koper.koper_lib.elpe.mixin;

import com.koper.koper_lib.elpe.ElpeRubble;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

// part of what the explosion eats flies off as elpe rubble instead of vanishing. off unless /koperlib elpe rubble
@Mixin(ServerExplosion.class)
public abstract class ElpeExplosionMixin {
    @Shadow @Final private ServerLevel level;

    @ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> koperElpeRubblize(List<BlockPos> doomed) {
        if (!ElpeRubble.explosions) return doomed;
        ServerExplosion self = (ServerExplosion) (Object) this;
        return ElpeRubble.explosion(level, self.center(), self.radius(), doomed);
    }
}
