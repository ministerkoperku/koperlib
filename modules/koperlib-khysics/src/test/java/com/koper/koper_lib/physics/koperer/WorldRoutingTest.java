package com.koper.koper_lib.physics.koperer;

import com.koper.koper_lib.panama.KoperPhysBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// the segfault this exists to stop: a rapier world id is a small index, an elpe one is a raw
// pointer. flip the backend under a live world and the wrong engine derefs the other's id.
class WorldRoutingTest {

    @Test
    void aWorldKeepsItsOwnEngineAfterTheBackendFlips() {
        ElpeKoperer elpe = new ElpeKoperer();
        org.junit.jupiter.api.Assumptions.assumeTrue(elpe.isLoaded(), "elpe native not built, skipping");

        long w = KoperPhysBridge.createWorld();
        assertNotEquals(0L, w, "no world");
        PhysKoperer made = KoperPhysBridge.of(w);

        // whatever the picker says now, this world must still answer to the engine that made it
        KopererPicker.swap("elpe");
        assertSame(made, KoperPhysBridge.of(w), "world changed engine under us — this is the segfault");

        KopererPicker.swap("rapier");
        assertSame(made, KoperPhysBridge.of(w));
        KoperPhysBridge.destroyWorld(w);
    }

    @Test
    void anIdWeNeverHandedOutTouchesNothing() {
        // a pointer sized lie. before world routing this went to a native as an address
        long junk = 0x1234_5678_9ABCL;
        assertSame(PhysKoperer.NOBODY, KoperPhysBridge.of(junk));
        KoperPhysBridge.heartbeat(junk);
        KoperPhysBridge.setGravity(junk, 0f, -28f, 0f);
        assertEquals(0, KoperPhysBridge.getAllTransforms(junk, new float[80], 8));
        assertEquals(-1L, KoperPhysBridge.spawnKontraktionOffsets(junk, new float[]{0, 0, 0}, new float[]{1}, 0, 0, 0, 0));
        assertEquals(-1L, KoperPhysBridge.raycast(junk, 0, 0, 0, 0, -1, 0, 10, new float[3]));
        KoperPhysBridge.destroyWorld(junk);
    }

    @Test
    void destroyedWorldStopsAnsweringAltogether() {
        long w = KoperPhysBridge.createWorld();
        org.junit.jupiter.api.Assumptions.assumeTrue(w != 0L, "no physics native at all, skipping");
        KoperPhysBridge.destroyWorld(w);
        assertSame(PhysKoperer.NOBODY, KoperPhysBridge.of(w), "freed world still routed somewhere");
        KoperPhysBridge.heartbeat(w); // would be a use after free
    }
}
