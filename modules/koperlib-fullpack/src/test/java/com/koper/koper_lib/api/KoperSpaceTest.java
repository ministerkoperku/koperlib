package com.koper.koper_lib.api;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KoperSpaceTest {
    private static final KoperSpace.Body MOON = new KoperSpace.Body("pack:moon", "pack:space", new Vec3(1000, 0, -500),
        300, 50, "pack:textures/moon.png", -1, 0, 0f, "pack:moon", 2000, 320, 600);

    @Test
    void enteringFromTheSideLandsAtTheEdgeOfTheArea() {
        Vec3 landing = KoperSpace.landingFor(MOON, new Vec3(1000 + 350, 0, -500));
        assertEquals(2000, landing.x, 1e-9);
        assertEquals(0, landing.z, 1e-9);
        assertEquals(320, landing.y, 1e-9);
    }

    @Test
    void enteringFromTheTopLandsInTheMiddle() {
        Vec3 landing = KoperSpace.landingFor(MOON, new Vec3(1000, 350, -500));
        assertEquals(0, landing.x, 1e-9);
        assertEquals(0, landing.z, 1e-9);
    }

    @Test
    void leavingComesOutOverTheSameSpotOutsideTheReach() {
        Vec3 in = new Vec3(1000 + 200, 100, -500 - 150);
        Vec3 planet = KoperSpace.landingFor(MOON, in);
        Vec3 out = KoperSpace.departureFor(MOON, new Vec3(planet.x, 700, planet.z));
        Vec3 d = out.subtract(MOON.center());
        assertTrue(d.length() > MOON.reach(), "outside the reach");
        Vec3 n = d.normalize(), m = in.subtract(MOON.center()).normalize();
        assertEquals(m.x, n.x, 1e-9);
        assertEquals(m.z, n.z, 1e-9);
    }

    @Test
    void departureClampsPointsOutsideTheArea() {
        Vec3 out = KoperSpace.departureFor(MOON, new Vec3(9000, 700, 0));
        Vec3 n = out.subtract(MOON.center()).normalize();
        assertEquals(1, n.x, 1e-9);
        assertEquals(0, n.y, 1e-9);
    }

    @Test
    void fallingIntoTheSphereBecomesFallingDown() {
        Vec3 point = new Vec3(1000 + 350, 0, -500);
        Vec3 v = KoperSpace.arrivalVelocity(MOON, point, new Vec3(-30, 0, 0));
        assertEquals(-30, v.y, 1e-9);
        assertEquals(0, v.x, 1e-9);
        Vec3 back = KoperSpace.departureVelocity(MOON, point, new Vec3(0, 30, 0));
        assertEquals(30, back.x, 1e-9);
    }

    @Test
    void badBodiesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new KoperSpace.Body("a", "s", Vec3.ZERO, 0, 0, "t", 0, 0, 0, null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new KoperSpace.Body("a", "s", Vec3.ZERO, 10, 0, "t", 0, 0, 0, "p", 100, 600, 300));
    }
}
