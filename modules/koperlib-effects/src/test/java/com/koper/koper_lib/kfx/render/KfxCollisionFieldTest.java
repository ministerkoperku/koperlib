package com.koper.koper_lib.kfx.render;

import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxDef;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class KfxCollisionFieldTest {
    @Test
    void unchangedSectionsReuseTheCollisionField() {
        KfxCollisionCache cache = new KfxCollisionCache();
        KfxCollisionCache.Key key = new KfxCollisionCache.Key("level-a", 2, 4, 6, 8);
        AtomicInteger builds = new AtomicInteger();

        KfxCollisionField first = cache.field(key, 11L, () -> {
            builds.incrementAndGet();
            return KfxCollisionField.empty(new BlockPos(16, 48, 80), 17);
        });
        KfxCollisionField second = cache.field(key, 11L, () -> {
            builds.incrementAndGet();
            return KfxCollisionField.empty(new BlockPos(16, 48, 80), 17);
        });

        assertSame(first, second);
        assertEquals(1, builds.get());
    }

    @Test
    void cosmeticBounceNeverRegistersAServerImpact() {
        AtomicInteger serverEvents = new AtomicInteger();
        KfxCollisionRuntime runtime = new KfxCollisionRuntime();
        var result = runtime.applyCosmeticContact(
            new KfxCollisionRuntime.Particle(Vec3.ZERO, new Vec3(1, -2, 0), true),
            new Vec3(0, 1, 0), KfxParticleResponse.BOUNCE, 0.5, 0.25);

        assertEquals(new Vec3(0.75, 1.0, 0), result.velocity());
        assertFalse(result.gameplayImpact());
        assertEquals(0, serverEvents.get());
    }

    @Test
    void jsonEmitterCanOptIntoDampedVisualBounce() {
        var json = JsonParser.parseString("""
            {"shape":"emitter","emitter":{"collision":{"response":"bounce","radius":9,
            "fluids":true,"restitution":0.55,"friction":0.2}}}
            """).getAsJsonObject();
        KfxDef definition = KfxDef.fromJson(json, Identifier.parse("test:collision"));

        assertEquals("bounce", definition.collisionResponse());
        assertEquals(9, definition.collisionRadius());
        assertEquals(true, definition.collisionFluids());
        assertEquals(0.55f, definition.collisionRestitution());
        assertEquals(0.2f, definition.collisionFriction());
    }
}
