package com.koper.koper_lib.kodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// vanilla culls an entity on the aabb its type declares. a kodel mob is usually much
// larger than that, so it blinks out while still filling the screen
class KodelCullingTest {

    @AfterEach
    void wipe() {
        KodelBook.clear();
    }

    private static KodelBook.Entry load(String name) throws Exception {
        try (InputStream in = KodelCullingTest.class.getResourceAsStream("/" + name + ".kodel")) {
            assertNotNull(in, name + ".kodel missing");
            return KodelBook.put(name, KodelLoader.read(in));
        }
    }

    @Test
    void theBoxActuallyWrapsTheModel() throws Exception {
        KodelBook.Entry entry = load("kapoka");
        float[] b = entry.modelBounds();
        assertNotNull(b);
        assertEquals(6, b.length);
        assertTrue(b[3] > b[0] && b[4] > b[1] && b[5] > b[2], "empty box: " + java.util.Arrays.toString(b));

        // every baked vertex has to sit inside it
        float[] mesh = KodelModelRender.bake(entry.model(), entry.restPose(), null);
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            assertTrue(mesh[i] >= b[0] - 1e-4f && mesh[i] <= b[3] + 1e-4f, "x outside the box");
            assertTrue(mesh[i + 1] >= b[1] - 1e-4f && mesh[i + 1] <= b[4] + 1e-4f, "y outside the box");
            assertTrue(mesh[i + 2] >= b[2] - 1e-4f && mesh[i + 2] <= b[5] + 1e-4f, "z outside the box");
        }
    }

    @Test
    void reachIsBigEnoughToMatterOnATree() throws Exception {
        load("kapoka");
        float[] reach = KodelBook.cullingReach("kapoka");
        System.out.printf("kapoka reach: %.2f blocks sideways, %.2f up, %.2f down%n",
            reach[0], reach[1], reach[2]);
        // a vanilla mob box is about 0.6 wide. if the reach were under that there
        // would have been nothing to fix
        assertTrue(reach[0] > 0.6f,
            "kapoka reaches only " + reach[0] + " blocks sideways, that cannot be right for a tree");
        assertTrue(reach[1] > 1f, "and it should stand taller than a block");
    }

    @Test
    void anUnknownModelAsksForNothing() {
        float[] reach = KodelBook.cullingReach("no_such_model");
        assertEquals(0f, reach[0], 1e-6f);
        assertEquals(0f, reach[1], 1e-6f);
        assertEquals(0f, reach[2], 1e-6f);
    }

    @Test
    void reachNeverGoesNegative() throws Exception {
        for (String name : new String[] {"kapoka", "kopertrex"}) {
            KodelBook.clear();
            load(name);
            float[] reach = KodelBook.cullingReach(name);
            for (float f : reach) {
                assertTrue(f >= 0f, name + " asked for a negative culling reach: " + f);
            }
        }
    }
}
