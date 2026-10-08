package com.koper.koper_lib.mixin;

import com.koper.koper_lib.compat.create.KoperCreateContraptions;
import com.zurrtum.create.content.trains.entity.Carriage;
import com.zurrtum.create.content.trains.entity.CarriageContraptionEntity;
import com.zurrtum.create.content.trains.entity.CarriageEntityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.ref.WeakReference;

@Mixin(Carriage.DimensionalCarriageEntity.class)
public abstract class KontraCreateContraptionTrainChunksMixin {
    @Shadow WeakReference<CarriageContraptionEntity> entity;

    @Redirect(method = "alignEntity", at = @At(value = "INVOKE",
        target = "Lcom/zurrtum/create/content/trains/entity/CarriageEntityHandler;isActiveChunk(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean koperlib$checkPhysicalChunk(Level level, BlockPos logical,
            CarriageContraptionEntity carriageEntity) {
        Vec3 world = KoperCreateContraptions.logicalPointToWorld(
            carriageEntity, Vec3.atCenterOf(logical));
        return CarriageEntityHandler.isActiveChunk(level, BlockPos.containing(world));
    }

    @Redirect(method = "lambda$updatePassengerLoadout$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;snapTo(Lnet/minecraft/world/phys/Vec3;)V"))
    private void koperlib$loadPassengerAtPhysicalAnchor(Entity passenger, Vec3 logical) {
        CarriageContraptionEntity carriageEntity = entity == null ? null : entity.get();
        passenger.snapTo(carriageEntity == null
            ? logical : KoperCreateContraptions.logicalPointToWorld(carriageEntity, logical));
    }
}
