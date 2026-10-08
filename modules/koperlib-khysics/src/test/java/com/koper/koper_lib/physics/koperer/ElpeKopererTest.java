package com.koper.koper_lib.physics.koperer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

// drives the real elpe .so through the khysics api. if the native isnt built this whole
// class skips instead of going red — not everyone has cargo output sitting there
class ElpeKopererTest {

    private static ElpeKoperer elpe;

    @BeforeAll
    static void boot() {
        elpe = new ElpeKoperer();
        Assumptions.assumeTrue(elpe.isLoaded(), "elpe native not built, skipping");
    }

    // 16^3 bits, bit = (ly*16+lz)*16+lx. one solid layer at local y
    private static long[] floorAt(int ly) {
        long[] bits = new long[64];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int i = ((ly & 15) << 8) | ((lz & 15) << 4) | (lx & 15);
            bits[i >> 6] |= 1L << (i & 63);
        }
        return bits;
    }

    private static float[] oneBlock() { return new float[]{0f, 0f, 0f}; }

    @Test
    void worldOpensAndCloses() {
        long w = elpe.createWorld();
        assertNotEquals(0L, w, "elpe world never opened");
        elpe.destroyWorld(w);
    }

    @Test
    void kontraktionFallsAndLandsOnTerrain() {
        long w = elpe.createWorld();
        try {
            elpe.uploadSection(w, 0, 0, 0, floorAt(3));
            long k = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{1f}, 0, 4f, 11f, 4f);
            assertTrue(k != -1L && k != 0L, "spawn failed: " + k);

            float[] buf = new float[PhysKoperer.TRANSFORM_STRIDE * 8];
            for (int t = 0; t < 160; t++) elpe.heartbeat(w);

            int n = elpe.getAllTransforms(w, buf, 8);
            assertEquals(1, n, "expected one kontraktion back");
            long id = ((long) Float.floatToRawIntBits(buf[0]) & 0xFFFFFFFFL)
                    | (((long) Float.floatToRawIntBits(buf[1]) & 0xFFFFFFFFL) << 32);
            assertEquals(k, id, "transform came back with a different id");
            // block top is y=4, half height 0.5 -> centre settles at 4.5
            assertEquals(4.5f, buf[3], 0.15f, "kontraktion did not land on the floor");
            // nothing on a hinge, so the rotation must be exactly identity
            assertArrayEquals(new float[]{0f, 0f, 0f, 1f},
                new float[]{buf[5], buf[6], buf[7], buf[8]}, 0f, "elpe invented a rotation");
        } finally { elpe.destroyWorld(w); }
    }

    @Test
    void hingeMotorSpinsAndReportsBack() {
        long w = elpe.createWorld();
        try {
            long chassis = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{0f}, 0, 0f, 20f, 0f);
            long wheel = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{1f}, 0, 2f, 20f, 0f);
            long j = elpe.createRevoluteJoint(w, chassis, wheel, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f);
            assertTrue(j >= 0, "revolute joint not created");

            elpe.jointSetMotor(w, j, 6f, 500f);
            for (int t = 0; t < 40; t++) elpe.heartbeat(w);

            float[] st = elpe.jointState(w, j);
            assertNotNull(st, "joint state never came back");
            assertNotEquals(0f, st[0], "wheel never turned");

            // the wheel is on a hinge, so its transform must carry a real rotation now
            float[] buf = new float[PhysKoperer.TRANSFORM_STRIDE * 8];
            int n = elpe.getAllTransforms(w, buf, 8);
            assertEquals(2, n);
            boolean spun = false;
            for (int i = 0; i < n; i++) {
                int b = i * PhysKoperer.TRANSFORM_STRIDE;
                if (buf[b + 8] != 1f) spun = true;
            }
            assertTrue(spun, "no body reported a rotated quaternion");
        } finally { elpe.destroyWorld(w); }
    }

    @Test
    void sliderKeepsItsLimits() {
        long w = elpe.createWorld();
        try {
            long base = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{0f}, 0, 0f, 30f, 0f);
            long arm = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{1f}, 0, 0f, 29f, 0f);
            long j = elpe.createPrismaticJoint(w, base, arm, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f);
            elpe.jointSetLimits(w, j, -2f, 0f);
            for (int t = 0; t < 200; t++) elpe.heartbeat(w);
            float[] st = elpe.jointState(w, j);
            assertNotNull(st);
            assertTrue(st[0] >= -2.05f && st[0] <= 0.05f, "slider escaped its limits: " + st[0]);
        } finally { elpe.destroyWorld(w); }
    }

    // suspension: SuspensionManager sets limits then drives a force based position motor with
    // the profile's stiffness/damping and rest 0. on elpe that has to become a real spring
    @Test
    void suspensionSpringHoldsTheWeightUp() {
        long w = elpe.createWorld();
        try {
            long hub = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{0f}, 0, 0f, 30f, 0f);
            long wheel = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{1f}, 0, 0f, 30f, 0f);
            long j = elpe.createPrismaticJoint(w, hub, wheel, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f);
            assertTrue(j >= 0, "prismatic joint not created");
            elpe.jointSetLimits(w, j, -0.75f, 0f);
            // offroad, stiffest tune
            elpe.jointSetMotorPositionForceBased(w, j, 0f, 1400f, 140f, 650_000f);

            for (int t = 0; t < 200; t++) elpe.heartbeat(w);
            float[] st = elpe.jointState(w, j);
            assertNotNull(st, "joint state never came back");
            assertTrue(st[0] > -0.73f, "spring bottomed out, it is holding nothing: " + st[0]);
            assertTrue(st[0] < 0f, "spring never compressed under the weight: " + st[0]);
        } finally { elpe.destroyWorld(w); }
    }

    @Test
    void theFlashyStuffIsHarmlessNoOps() {
        long w = elpe.createWorld();
        try {
            long k = elpe.spawnKontraktionOffsets(w, oneBlock(), new float[]{1f}, 0, 0f, 40f, 0f);
            // none of this exists in elpe. it must do nothing, not blow up
            elpe.setAero(w, k, new float[]{0, 0, 0, 0, 1, 0, 1, 1, 1, 0});
            elpe.setAeroMode(w, k, 1);
            elpe.setWind(w, 1f, 0f, 0f);
            elpe.setBuoyancy(w, k, new float[]{0, 0, 0, 1});
            elpe.setWaterDensity(w, 1f);
            elpe.setFluidBlock(w, 0, 0, 0, true);
            elpe.setBlockMaterials(w, k, new float[15]);
            elpe.setBlockShapes(w, k, new float[11]);
            elpe.applyTorque(w, k, 1f, 1f, 1f);
            elpe.selfRight(w, k);
            assertNull(elpe.drainSplits(w, 64), "elpe should never split a body");
            assertNull(elpe.profile(w), "elpe keeps no profile numbers");
            elpe.heartbeat(w);
        } finally { elpe.destroyWorld(w); }
    }
}
