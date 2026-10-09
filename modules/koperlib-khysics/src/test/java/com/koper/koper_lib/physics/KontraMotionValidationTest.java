package com.koper.koper_lib.physics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KontraMotionValidationTest {
    @Test void replayWrongEpochAndNonFiniteMovementAreRejected() {
        var session = new KontraMotionState(7, "test:space", 4, Vec3.ZERO);
        assertTrue(session.accept(7, "test:space", 4, 1, 10, new Vec3(.2, 0, 0)));
        assertFalse(session.accept(7, "test:space", 4, 1, 11, new Vec3(.4, 0, 0)));
        assertFalse(session.accept(8, "test:space", 4, 2, 11, new Vec3(.4, 0, 0)));
        assertFalse(session.accept(7, "test:other", 4, 2, 11, new Vec3(.4, 0, 0)));
        assertFalse(session.accept(7, "test:space", 5, 2, 11, new Vec3(.4, 0, 0)));
        assertFalse(session.accept(7, "test:space", 4, 2, 11, new Vec3(Double.NaN, 0, 0)));
    }
    @Test void packetFloodDoesNotMultiplyTheWalkingBudget() {
        var session = new KontraMotionState(7, "test:space", 4, Vec3.ZERO);
        assertTrue(session.accept(7, "test:space", 4, 1, 10, new Vec3(1, 0, 0)));
        session.solved(new Vec3(1, 0, 0));
        assertFalse(session.accept(7, "test:space", 4, 2, 10, new Vec3(2, 0, 0)));
        assertFalse(session.accept(7, "test:space", 4, 3, 11, new Vec3(20, 0, 0)));
    }
}
