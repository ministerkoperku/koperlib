package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraEntityQuery;
import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/** Level declares these methods; injecting ServerLevel's inherited methods cannot hook them. */
@Mixin(Level.class)
public abstract class KontraEntityQueryMixin {
    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridEntities(Entity except, AABB box, Predicate<? super Entity> predicate,
                                       CallbackInfoReturnable<List<Entity>> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        KontraGrid grid = KontraGridContext.active();
        if (!KontraEntityQuery.isGridQuery(level, grid, box)) return;
        KontraEntityQuery query = KontraEntityQuery.of(level, grid, box);
        cir.setReturnValue(query == null ? List.of() : KontraGridContext.outside(() ->
            level.getEntities(except, query.bounds(), entity -> KontraGridContext.call(grid, () ->
                query.intersects(entity.getBoundingBox()) && predicate.test(entity)))));
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void koperlib$gridTypedEntities(EntityTypeTest<Entity, T> type, AABB box,
                                                               Predicate<? super T> predicate,
                                                               CallbackInfoReturnable<List<T>> cir) {
        if (!((Object)this instanceof ServerLevel level)) return;
        KontraGrid grid = KontraGridContext.active();
        if (!KontraEntityQuery.isGridQuery(level, grid, box)) return;
        KontraEntityQuery query = KontraEntityQuery.of(level, grid, box);
        cir.setReturnValue(query == null ? List.of() : KontraGridContext.outside(() ->
            level.getEntities(type, query.bounds(), entity -> KontraGridContext.call(grid, () ->
                query.intersects(entity.getBoundingBox()) && predicate.test(entity)))));
    }
}
