package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGlue;
import com.koper.koper_lib.physics.KontraRide;
import com.koper.koper_lib.physics.KoperPhys;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the ONE place where kontras touch entity movement. inherited platform motion is injected
// into move()'s own delta, the SAT solve clamps it against kontras, vanilla collide clamps
// against terrain — and vanilla's own delta-vs-result compare sets every flag for free.
@Mixin(Entity.class)
public abstract class KontraCollideRedirectMixin implements KontraGlue.MindHaver {

    @Unique
    private final KontraGlue.Mind koper$mind = new KontraGlue.Mind();

    @Unique
    private boolean koper$deckGrounded;

    @Unique
    private boolean koper$solveRan;

    @Unique
    private Vec3 koper$flagDelta;

    @Unique
    private Vec3 koper$solvedVec;

    @Unique
    private boolean koper$solveHitX, koper$solveHitZ;

    @Shadow
    public boolean horizontalCollision;

    @Shadow
    public boolean verticalCollision;

    @Shadow
    public boolean verticalCollisionBelow;

    @Override
    public KontraGlue.Mind koper$rideMind() {
        return koper$mind;
    }

    @Shadow
    private Vec3 collide(Vec3 movement) { throw new AssertionError(); }

    @Shadow
    private void restituteMovementAfterCollisions(net.minecraft.world.level.block.state.BlockState effectState,
                                                  boolean xCollision, boolean zCollision, Vec3 movement) {
        throw new AssertionError();
    }

    @Unique
    private MoverType koper$moveType;

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"))
    private void koper$stashMover(MoverType type, Vec3 delta, CallbackInfo ci) {
        koper$moveType = type;
    }

    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 koper$collideWithKontras(Entity self, Vec3 rawDelta) {
        // inherited platform motion joins HERE, on the actual call value. the old
        // @ModifyVariable at the invoke wrote the LOCAL SLOT after the arg was already on the
        // stack — the solve saw the raw delta while the Mind swore the carry was fed, then
        // subtracted it: riders got dragged BACKWARD into deck walls or left behind entirely
        // (koper's budget log: own was exactly minus fed. fuck me). backoff/stuck upstream
        // still see only the entity's own motion, which was the whole point of moving it here.
        Vec3 delta = KontraGlue.feedInherited(self, koper$moveType, rawDelta);
        KontraRide.Ride r;
        if (self.level().isClientSide()) {
            KontraRide.ClientClamp hook = KontraRide.CLIENT_HOOK;
            r = hook != null ? hook.clamp(self, delta) : null;
        } else {
            // no pilot special-case here: forcing Ride=ZERO froze the server-side player while
            // the client carried itself along the ship — the desync snapped on un-crouch
            r = KontraRide.serverClamp(self, delta);
        }

        Vec3 solved = r != null ? r.delta : delta;
        Vec3 afterVanilla;
        KoperPhys.ENTITY_COLLISION_ACTIVE.set(true);
        try {
            afterVanilla = this.collide(solved);
        } finally {
            KoperPhys.ENTITY_COLLISION_ACTIVE.set(false);
        }
        koper$deckGrounded = r != null && r.onGround && r.kontraId != 0L;
        koper$solveRan = r != null;
        koper$flagDelta = delta;
        koper$solvedVec = solved;
        koper$solveHitX = r != null && r.hitX;
        koper$solveHitZ = r != null && r.hitZ;
        KontraGlue.budgetDbg(self, delta, solved, afterVanilla);
        KontraGlue.afterMove(self, r, solved, afterVanilla);
        return afterVanilla;
    }

    // did anything REAL block this axis: the solve's own verdict (intent vs achieved,
    // slope redirection excluded) plus whatever vanilla terrain clamped off the solve output
    @Unique
    private boolean koper$realHitX(Vec3 movement) {
        return koper$solveHitX || Math.abs(koper$solvedVec.x - movement.x) > 0.005;
    }

    @Unique
    private boolean koper$realHitZ(Vec3 movement) {
        return koper$solveHitZ || Math.abs(koper$solvedVec.z - movement.z) > 0.005;
    }

    // two flag repairs in one, only for moves our solve touched:
    // 1. a deck moving UP makes the whole delta.y positive → vanilla verticalCollisionBelow
    //    can never fire → no jumps + air friction while riding a climbing kontra. the solve
    //    knows better, stamp its ground on top.
    // 2. the solver's contact slop (~1.5-4mm nudges along normals) tripped vanilla's EXACT
    //    delta-vs-result compare → phantom collisions → restitute zeroed deltaMovement every
    //    tick. that was the rotated-kontra slowdown, dead sprint and the eaten wall-jump.
    //    a real wall blocks whole centimetres, so a 5mm slop filter loses nothing.
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void koper$stampMoveFlags(Entity self, boolean grounded, boolean horizontal, Vec3 movement) {
        if (!koper$solveRan || koper$flagDelta == null || koper$solvedVec == null) {
            self.setOnGroundWithMovement(grounded || koper$deckGrounded, horizontal, movement);
            return;
        }
        boolean hit = koper$realHitX(movement) || koper$realHitZ(movement);
        boolean vhit = Math.abs(koper$flagDelta.y - movement.y) > 0.005;
        this.horizontalCollision = hit;
        this.verticalCollision = vhit;
        this.verticalCollisionBelow = vhit && koper$flagDelta.y < 0.0;
        self.setOnGroundWithMovement(this.verticalCollisionBelow || koper$deckGrounded, hit, movement);
    }

    // vanilla hands restitute the RAW per-axis compare locals — on a tilted deck the slope
    // projection legitimately redirects x/z, the raw compare read that as a wall hit and
    // restitute zeroed deltaMovement every tick. THAT was the rotated-kontra slowdown and
    // the dead sprint (round 2 fixed only the fields, not these locals. oops.)
    @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;restituteMovementAfterCollisions(Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void koper$restituteReal(Entity self, net.minecraft.world.level.block.state.BlockState effectState,
                                     boolean xCollision, boolean zCollision, Vec3 movement) {
        if (koper$solveRan && koper$solvedVec != null) {
            xCollision = koper$realHitX(movement);
            zCollision = koper$realHitZ(movement);
        }
        this.restituteMovementAfterCollisions(effectState, xCollision, zCollision, movement);
    }
}
