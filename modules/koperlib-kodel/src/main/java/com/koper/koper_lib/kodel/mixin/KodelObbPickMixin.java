package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

// OBB hit-detection. the model's OBB hitboxes can be bigger than the mc aabb, so we can't just refine the vanilla
// aabb result — we ADD hits: scan model-obb entities, test ray vs their per-bone OBBs, pick the nearest hit overall
@Mixin(ProjectileUtil.class)
public class KodelObbPickMixin {
    private static boolean koperlib$warned;

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void koperlib$obbPick(Entity shooter, Vec3 from, Vec3 to, AABB box, Predicate<Entity> filter,
                                         double pickRadius, CallbackInfoReturnable<EntityHitResult> cir) {
        try {
            EntityHitResult vanilla = cir.getReturnValue();
            Vec3 dir = to.subtract(from);
            double rayLen = dir.length();
            if (rayLen < 1e-9) return;

            EntityHitResult best = null;
            double bestDist = Double.MAX_VALUE;

            // keep the vanilla hit unless it's a model-obb entity that the ray actually missed (then it's invalid)
            if (vanilla != null) {
                Entity ve = vanilla.getEntity();
                if (KodelEntities.obb(ve.getType())) {
                    double d = KodelEntities.rayHit(ve, from, to);
                    if (d >= 0) { best = vanilla; bestDist = d; } // else discard (aimed inside aabb but missed the OBBs)
                } else {
                    best = vanilla;
                    bestDist = from.distanceTo(vanilla.getLocation());
                }
            }

            // widen the search by the biggest OBB reach so far-flung hitboxes (centre far from the ray) are still found
            double reach = KodelEntities.maxReach();
            AABB search = reach > 0 ? box.inflate(reach) : box;

            // additively pick any model-obb entity whose OBB the ray crosses, nearest wins (covers OBBs outside the aabb)
            for (Entity e : shooter.level().getEntities(shooter, search, filter)) {
                if (!KodelEntities.obb(e.getType())) continue;
                double d = KodelEntities.rayHit(e, from, to);
                if (d >= 0 && d < bestDist) {
                    bestDist = d;
                    best = new EntityHitResult(e, from.add(dir.scale(d / rayLen)));
                }
            }

            cir.setReturnValue(best);
        } catch (RuntimeException broken) {
            // vanilla picking keeps its answer, but a broken model hitbox has to say so
            if (!koperlib$warned) {
                koperlib$warned = true;
                com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[kodel] model hitbox picking failed, vanilla boxes only: {}", broken.toString());
            }
        }
    }
}
