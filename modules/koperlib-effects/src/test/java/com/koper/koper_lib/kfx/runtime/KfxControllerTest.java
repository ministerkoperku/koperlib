package com.koper.koper_lib.kfx.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KfxControllerTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void fastSphereCannotTunnelThroughAThinBlockShape() {
        KfxSensor sensor = (from, to, radius, ignored) -> KfxSensor.clipBox(
            new AABB(4, 0, -1, 4.0625, 2, 1), new BlockPos(4, 0, 0), from, to, radius);
        KfxController controller = KfxController.projectile(7, OWNER, new Vec3(0, 1, 0), new Vec3(8, 0, 0))
            .radius(0.2).response(KfxController.Response.STOP).build();

        KfxController.Step step = controller.step(sensor);

        assertNotNull(step.impact());
        assertEquals(3.8, step.impact().position().x, 0.0001);
        assertEquals(KfxController.State.STOPPED, step.state());
    }

    @Test
    void bounceLimitEndsTheControllerOnTheConfiguredHit() {
        KfxController controller = KfxController.projectile(8, OWNER, Vec3.ZERO, new Vec3(4, 0, 0))
            .response(KfxController.Response.BOUNCE).maxBounces(2).build();
        KfxSensor.Contact wall = KfxSensor.Contact.block(
            new BlockPos(1, 0, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0));

        KfxImpact first = controller.accept(wall);
        KfxImpact second = controller.accept(wall);

        assertEquals(1, first.sequence());
        assertEquals(new Vec3(-4, 0, 0), first.outgoingVelocity());
        assertEquals(2, second.sequence());
        assertEquals(KfxController.State.STOPPED, controller.state());
    }

    @Test
    void bounceCanLoseNormalAndTangentialEnergy() {
        KfxController controller = KfxController.projectile(81, OWNER, Vec3.ZERO, new Vec3(3, -2, 4))
            .response(KfxController.Response.BOUNCE)
            .restitution(0.5)
            .surfaceFriction(0.25)
            .maxBounces(3)
            .build();

        KfxImpact impact = controller.accept(KfxSensor.Contact.block(
            BlockPos.ZERO, Vec3.ZERO, new Vec3(0, 1, 0)));

        assertEquals(new Vec3(2.25, 1, 3), impact.outgoingVelocity());
        assertEquals(KfxController.State.RUNNING, controller.state());
    }

    @Test
    void slideRemovesOnlyVelocityIntoTheSurface() {
        KfxController controller = KfxController.projectile(9, OWNER, Vec3.ZERO, new Vec3(3, -2, 4))
            .response(KfxController.Response.SLIDE).build();

        KfxImpact impact = controller.accept(KfxSensor.Contact.block(
            BlockPos.ZERO, Vec3.ZERO, new Vec3(0, 1, 0)));

        assertEquals(new Vec3(3, 0, 4), impact.outgoingVelocity());
        assertEquals(KfxController.State.RUNNING, controller.state());
    }

    @Test
    void passResponseReportsContactButKeepsTheFlightSegment() {
        UUID target = UUID.fromString("22222222-2222-2222-2222-222222222222");
        KfxSensor sensor = (from, to, radius, ignored) -> ignored.contains(target) ? null
            : KfxSensor.Contact.entity(target, new Vec3(2, 0, 0), new Vec3(-1, 0, 0));
        KfxController controller = KfxController.projectile(10, OWNER, Vec3.ZERO, new Vec3(5, 0, 0))
            .response(KfxController.Response.PASS).build();

        KfxController.Step first = controller.step(sensor);
        KfxController.Step second = controller.step(sensor);

        assertNotNull(first.impact());
        assertEquals(new Vec3(5, 0, 0), first.position());
        assertNull(second.impact());
        assertEquals(new Vec3(10, 0, 0), second.position());
    }

    @Test
    void nearestContactWinsAcrossBlockAndEntitySweeps() {
        KfxSensor.Contact block = KfxSensor.Contact.block(
            new BlockPos(6, 0, 0), new Vec3(6, 0, 0), new Vec3(-1, 0, 0));
        KfxSensor.Contact entity = KfxSensor.Contact.entity(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            new Vec3(3, 0, 0), new Vec3(-1, 0, 0));

        assertEquals(entity, KfxSensor.nearest(Vec3.ZERO, block, entity));
        assertEquals(entity, KfxSensor.nearest(Vec3.ZERO, entity, block));
    }

    @Test
    void stickHoldsAtTheContactUntilLifetimeEnds() {
        KfxController controller = KfxController.projectile(11, OWNER, Vec3.ZERO, new Vec3(2, 0, 0))
            .response(KfxController.Response.STICK).lifetime(2).build();
        KfxSensor.Contact wall = KfxSensor.Contact.block(
            new BlockPos(1, 0, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0));
        controller.accept(wall);

        KfxController.Step held = controller.step((from, to, radius, ignored) -> null);
        KfxController.Step ended = controller.step((from, to, radius, ignored) -> null);

        assertEquals(KfxController.State.STUCK, held.state());
        assertEquals(new Vec3(1, 0, 0), held.position());
        assertEquals(KfxController.State.STOPPED, ended.state());
    }

    @Test
    void builderRejectsUnsafeOrNonFiniteControllerValues() {
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, new Vec3(Double.NaN, 0, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, new Vec3(KfxController.MAX_SPEED + 1, 0, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, Vec3.ZERO).radius(KfxController.MAX_RADIUS + 0.01).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, Vec3.ZERO).lifetime(KfxController.MAX_LIFETIME + 1).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, Vec3.ZERO).maxHits(KfxController.MAX_HITS + 1).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, Vec3.ZERO).restitution(-0.01).build());
        assertThrows(IllegalArgumentException.class, () -> KfxController.projectile(
            12, OWNER, Vec3.ZERO, Vec3.ZERO).surfaceFriction(Double.NaN).build());
    }

    @Test
    void unavailableTerrainStopsWithoutInventingAnImpact() {
        KfxController controller = KfxController.projectile(13, OWNER, Vec3.ZERO, new Vec3(2, 0, 0)).build();

        KfxController.Step step = controller.step((from, to, radius, ignored) ->
            KfxSensor.Contact.unavailable(from));

        assertNull(step.impact());
        assertEquals(KfxController.State.STOPPED, step.state());
        assertEquals(Vec3.ZERO, step.position());
    }

    @Test
    void sharedCollisionBudgetHasAHardPerTickLimit() {
        KfxMinecraftSensor.Budget budget = new KfxMinecraftSensor.Budget(2);

        assertEquals(true, budget.take());
        assertEquals(true, budget.take());
        assertEquals(false, budget.take());
    }

    @Test
    void pointSweepDoesNotClaimAnAdjacentBroadphaseCell() {
        assertEquals(true, KfxMinecraftSensor.touchesCell(
            Vec3.ZERO, new Vec3(0.9, 0, 0), BlockPos.ZERO, 0));
        assertEquals(false, KfxMinecraftSensor.touchesCell(
            Vec3.ZERO, new Vec3(0.9, 0, 0), new BlockPos(1, 0, 0), 0));
    }
}
