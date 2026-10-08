package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import com.koper.koper_lib.network.KfxAnchorPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxAnchorResolverTest {
    private final KfxAnchorResolver resolver = new KfxAnchorResolver();

    @Test
    void entityAnchorInterpolatesTheCurrentRenderFrame() {
        var anchor = new KfxAnchor.Entity(7, KfxSocket.CENTER, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            new Vec3(0, 1, 0), new Vec3(10, 1, 0),
            0, 0, 0, 0, 2, 1.6, true
        ));

        var result = resolver.resolve(anchor, source, 0.25f, null);

        assertEquals(KfxAnchorState.ACTIVE, result.state());
        assertEquals(2.5, result.transform().position().x, 0.0001);
        assertEquals(2.0, result.transform().position().y, 0.0001);
    }

    @Test
    void handSocketTurnsItsLocalOffsetWithTheEntity() {
        var anchor = new KfxAnchor.Entity(7, KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            Vec3.ZERO, Vec3.ZERO, 0, 90, 0, 0, 2, 1.6, true
        ));

        Vec3 hand = resolver.resolve(anchor, source, 1.0f, null).transform().position();

        // Body yaw 90 faces west, so the main hand of a right-handed entity sits to the north (-Z),
        // pushed a little further west than the body and just below eye height.
        assertEquals(-0.22, hand.x, 0.0001);
        assertEquals(1.344, hand.y, 0.0001);
        assertEquals(-0.38, hand.z, 0.0001);
    }

    @Test
    void handSocketsSitOnOppositeSides() {
        var main = new KfxAnchor.Entity(7, KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var off = new KfxAnchor.Entity(7, KfxSocket.OFF_HAND, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            Vec3.ZERO, Vec3.ZERO, 0, 0, 0, 0, 2, 1.6, true
        ));

        Vec3 right = resolver.resolve(main, source, 1.0f, null).transform().position();
        Vec3 left = resolver.resolve(off, source, 1.0f, null).transform().position();

        // Facing south, the right hand is west of the body and the off hand mirrors it.
        assertEquals(-0.38, right.x, 0.0001);
        assertEquals(0.38, left.x, 0.0001);
    }

    @Test
    void eyeSocketForwardOffsetUsesTheViewPitch() {
        var anchor = new KfxAnchor.Entity(7, KfxSocket.EYES, new Vec3(0, 0, 2), KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            Vec3.ZERO, Vec3.ZERO, 0, 0, 0, -90, 2, 1.6, true
        ));

        Vec3 eye = resolver.resolve(anchor, source, 1.0f, null).transform().position();

        assertEquals(0.0, eye.x, 0.0001);
        assertEquals(3.6, eye.y, 0.0001);
        assertEquals(0.0, eye.z, 0.0001);
    }

    @Test
    void betweenAnchorProducesAMovingMidpointAndDirection() {
        var start = new KfxAnchor.Entity(7, KfxSocket.FEET, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var end = new KfxAnchor.World(new Vec3(10, 2, 0), new Vec3(0, 1, 0), KfxMissingPolicy.FREEZE);
        var between = new KfxAnchor.Between(start, end, 0.25f, KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            Vec3.ZERO, new Vec3(2, 2, 0), 0, 0, 0, 0, 2, 1.6, true
        ));

        KfxTransform transform = resolver.resolve(between, source, 1.0f, null).transform();

        assertEquals(new Vec3(4, 2, 0), transform.position());
        assertEquals(new Vec3(1, 0, 0), transform.forward());
    }

    @Test
    void missingAnchorUsesItsRequestedLossPolicy() {
        KfxTransform last = KfxTransform.at(new Vec3(3, 4, 5));
        KfxAnchorResolver.PoseSource gone = new KfxAnchorResolver.PoseSource() {
            @Override public KfxAnchorResolver.EntityPose entity(int id) { return null; }
            @Override public Optional<KfxTransform> bone(int id, String bone, float partialTick) { return Optional.empty(); }
        };

        for (var expected : KfxAnchorState.values()) {
            if (expected == KfxAnchorState.ACTIVE) continue;
            KfxMissingPolicy policy = KfxMissingPolicy.valueOf(expected.name());
            var anchor = new KfxAnchor.Entity(404, KfxSocket.CENTER, Vec3.ZERO, policy);
            var result = resolver.resolve(anchor, gone, 0.5f, last);
            assertEquals(expected, result.state());
            assertEquals(last, result.transform());
        }
    }

    @Test
    void missingBoneFallsBackToItsEntitySocket() {
        var fallback = new KfxAnchor.Entity(7, KfxSocket.EYES, new Vec3(0, 0.1, 0), KfxMissingPolicy.FREEZE);
        var anchor = new KfxAnchor.Bone(7, "staff_tip", fallback, KfxMissingPolicy.FREEZE);
        var source = poses(new KfxAnchorResolver.EntityPose(
            new Vec3(1, 2, 3), new Vec3(1, 2, 3), 0, 0, 0, 0, 2, 1.6, true
        ));

        Vec3 position = resolver.resolve(anchor, source, 0.5f, null).transform().position();

        assertEquals(new Vec3(1, 3.7, 3), position);
    }

    @Test
    void boneAnchorReceivesTheCurrentRenderPartialTick() {
        var fallback = new KfxAnchor.Entity(7, KfxSocket.CENTER, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var anchor = new KfxAnchor.Bone(7, "staff_tip", fallback, KfxMissingPolicy.FREEZE);
        var source = new KfxAnchorResolver.PoseSource() {
            @Override public KfxAnchorResolver.EntityPose entity(int id) { return null; }
            @Override public Optional<KfxTransform> bone(int id, String bone, float partialTick) {
                return Optional.of(KfxTransform.at(new Vec3(partialTick * 10, 4, 5)));
            }
        };

        Vec3 position = resolver.resolve(anchor, source, 0.25f, null).transform().position();

        assertEquals(new Vec3(2.5, 4, 5), position);
    }

    @Test
    void anchorPacketCarriesReferencesInsteadOfGraphSource() {
        var start = new KfxAnchor.Entity(7, KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var end = new KfxAnchor.World(new Vec3(10, 2, 3), new Vec3(0, 1, 0), KfxMissingPolicy.DETACH);

        var payload = new KfxAnchorPayload(99, start, end);

        assertTrue(payload.estimatedSize() < 128);
    }

    @Test
    void nestedAnchorPacketRoundTripsInWireOrder() {
        var entity = new KfxAnchor.Entity(7, KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FREEZE);
        var world = new KfxAnchor.World(new Vec3(10, 2, 3), new Vec3(0, 1, 0), KfxMissingPolicy.DETACH);
        var payload = new KfxAnchorPayload(99,
            new KfxAnchor.Between(entity, world, 0.25f, KfxMissingPolicy.FADE), world);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);

        KfxAnchorPayload.CODEC.encode(buffer, payload);
        KfxAnchorPayload decoded = KfxAnchorPayload.CODEC.decode(buffer);

        assertEquals(payload, decoded);
        buffer.release();
    }

    private static KfxAnchorResolver.PoseSource poses(KfxAnchorResolver.EntityPose pose) {
        return new KfxAnchorResolver.PoseSource() {
            @Override public KfxAnchorResolver.EntityPose entity(int id) { return id == 7 ? pose : null; }
            @Override public Optional<KfxTransform> bone(int id, String bone, float partialTick) { return Optional.empty(); }
        };
    }
}
