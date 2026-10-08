package com.koper.koper_lib.kfx.runtime;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxMotionLerpTest {
    @Test
    void retargetStartsFromTheCurrentInterpolatedFrame() {
        KfxMotionLerp motion = new KfxMotionLerp(Vec3.ZERO, new Vec3(1, 0, 0));
        motion.retarget(10, new Vec3(10, 0, 0), new Vec3(11, 0, 0));

        KfxMotionLerp.Frame half = motion.sample(10.5f);
        motion.retarget(10.5f, new Vec3(20, 0, 0), new Vec3(21, 0, 0));
        KfxMotionLerp.Frame sameFrame = motion.sample(10.5f);

        assertEquals(new Vec3(5, 0, 0), half.start());
        assertEquals(half, sameFrame);
        assertEquals(new Vec3(20, 0, 0), motion.sample(11.5f).start());
    }
}
