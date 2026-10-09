package com.koper.koper_lib.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KontraFrameTest {
    @Test void fastTranslationKeepsLocalWalkingSmall() {
        var frame = new KontraFrame(new Vec3(500, 0, 0), new Quaterniond(),
            new Vec3(10000, 0, 0), Vec3.ZERO, new Vec3(500, 0, 0), 1);
        assertEquals(503.2, frame.toWorld(new Vec3(3.2, 1, 2)).x, 1e-9);
        assertEquals(3.2, frame.toLocal(new Vec3(503.2, 1, 2)).x, 1e-9);
    }
    @Test void rotatingFrameRotatesAnchorAndDepartureVelocity() {
        var frame = new KontraFrame(Vec3.ZERO, new Quaterniond().rotateY(Math.PI / 2),
            new Vec3(100, 0, 0), new Vec3(0, 1, 0), Vec3.ZERO, 2);
        assertEquals(2, frame.toWorld(new Vec3(0, 0, 2)).x, 1e-9);
        assertEquals(102, frame.velocityAt(new Vec3(0, 0, 2)).x, 1e-9);
        assertEquals(2, frame.toLocal(new Vec3(2, 0, 0)).z, 1e-9);
    }
    @Test void snapshotDoesNotAliasMutableQuaternion() {
        var q = new Quaterniond();
        var frame = new KontraFrame(Vec3.ZERO, q, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 0);
        q.rotateY(Math.PI);
        frame.rotation().rotateY(Math.PI);
        assertEquals(1, frame.toWorld(new Vec3(1, 0, 0)).x, 1e-9);
    }
}
