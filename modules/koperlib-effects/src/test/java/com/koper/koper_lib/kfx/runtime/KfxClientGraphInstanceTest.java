package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxClientGraphInstanceTest {
    @Test
    void detachKeepsTheLastFrameEvenWhenTheEntityComesBack() {
        var start = new KfxAnchor.Entity(7, KfxSocket.CENTER, Vec3.ZERO, KfxMissingPolicy.DETACH);
        var end = KfxAnchor.world(new Vec3(5, 0, 0));
        var live = new KfxClientGraphInstance(44, start, end,
            KfxTransform.at(Vec3.ZERO), KfxTransform.at(new Vec3(5, 0, 0)));

        live.resolveFrame(sourceAt(new Vec3(2, 0, 0)), 1.0f);
        assertEquals(2.0, live.start().position().x, 0.0001);
        assertEquals(KfxAnchorState.DETACH, live.resolveFrame(missing(), 1.0f));
        assertEquals(KfxAnchorState.ACTIVE, live.resolveFrame(sourceAt(new Vec3(9, 0, 0)), 1.0f));
        assertEquals(2.0, live.start().position().x, 0.0001);
    }

    @Test
    void frozenAnchorRetriesAndCatchesTheEntityAgain() {
        var start = new KfxAnchor.Entity(7, KfxSocket.FEET, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var live = new KfxClientGraphInstance(45, start, KfxAnchor.world(Vec3.ZERO),
            KfxTransform.at(Vec3.ZERO), KfxTransform.at(Vec3.ZERO));

        assertEquals(KfxAnchorState.FREEZE, live.resolveFrame(missing(), 1.0f));
        assertEquals(KfxAnchorState.ACTIVE, live.resolveFrame(sourceAt(new Vec3(6, 0, 0)), 1.0f));
        assertEquals(6.0, live.start().position().x, 0.0001);
    }

    @Test
    void killOnEitherEndpointEndsTheWholeEffect() {
        var end = new KfxAnchor.Entity(8, KfxSocket.CENTER, Vec3.ZERO, KfxMissingPolicy.KILL);
        var live = new KfxClientGraphInstance(46, KfxAnchor.world(Vec3.ZERO), end,
            KfxTransform.at(Vec3.ZERO), KfxTransform.at(Vec3.ZERO));

        assertEquals(KfxAnchorState.KILL, live.resolveFrame(missing(), 1.0f));
    }

    private static KfxAnchorResolver.PoseSource sourceAt(Vec3 position) {
        return new KfxAnchorResolver.PoseSource() {
            @Override public KfxAnchorResolver.EntityPose entity(int id) {
                if (id != 7) return null;
                return new KfxAnchorResolver.EntityPose(position, position, 0, 0, 0, 0, 2, 1.6, true);
            }

            @Override public Optional<KfxTransform> bone(int id, String bone, float partialTick) { return Optional.empty(); }
        };
    }

    private static KfxAnchorResolver.PoseSource missing() {
        return new KfxAnchorResolver.PoseSource() {
            @Override public KfxAnchorResolver.EntityPose entity(int id) { return null; }
            @Override public Optional<KfxTransform> bone(int id, String bone, float partialTick) { return Optional.empty(); }
        };
    }
}
