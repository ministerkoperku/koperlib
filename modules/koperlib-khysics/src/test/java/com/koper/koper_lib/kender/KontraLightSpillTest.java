package com.koper.koper_lib.kender;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KontraLightSpillTest {
    // 9x1x9 stone deck at y=0 with a torch (14) standing on its middle at y=1
    private static byte[] deckWithTorch(int[][] extra, int[] extraEmission, boolean[] extraOpaque) {
        int deck = 81, n = deck + 1 + extra.length;
        int[] locals = new int[n * 3];
        int[] emission = new int[n];
        boolean[] opaque = new boolean[n];
        int i = 0;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++, i++) {
            locals[i * 3] = x; locals[i * 3 + 2] = z; opaque[i] = true;
        }
        locals[i * 3 + 1] = 1; emission[i] = 14; i++;
        for (int e = 0; e < extra.length; e++, i++) {
            locals[i * 3] = extra[e][0]; locals[i * 3 + 1] = extra[e][1]; locals[i * 3 + 2] = extra[e][2];
            emission[i] = extraEmission[e]; opaque[i] = extraOpaque[e];
        }
        return KontraLightSpill.flood(locals, emission, opaque);
    }

    private static int deckIndex(int x, int z) { return (x + 4) * 9 + (z + 4); }

    @Test void torchLightsTheDeckAroundItThroughAir() {
        byte[] light = deckWithTorch(new int[0][], new int[0], new boolean[0]);
        assertEquals(14, light[81], "torch keeps its own emission");
        assertEquals(14, light[deckIndex(0, 0)], "block under the torch takes the torch cell light on its top face");
        assertEquals(13, light[deckIndex(1, 0)], "deck block next to the torch was dark, the light never left the torch");
        assertEquals(10, light[deckIndex(4, 0)]);
        assertEquals(7, light[deckIndex(4, 3)], "light walks around through air like vanilla, manhattan falloff");
    }

    @Test void opaqueWallStopsLightButIsLitOnItsFace() {
        // a stone wall column at x=2 from y=1 to y=3 across the whole deck width
        int[][] wall = new int[27][];
        for (int z = -4, w = 0; z <= 4; z++) for (int y = 1; y <= 3; y++) wall[w++] = new int[]{2, y, z};
        boolean[] op = new boolean[27];
        java.util.Arrays.fill(op, true);
        byte[] light = deckWithTorch(wall, new int[27], op);
        assertEquals(13, light[82 + 0 * 3 + 0 + 4 * 3], "wall face next to the torch row");
        assertTrue(light[deckIndex(3, 0)] < light[deckIndex(1, 0)], "light passed straight through an opaque wall");
    }

    @Test void noEmittersMeansNoOwnLight() {
        int[] locals = {0, 0, 0, 1, 0, 0};
        byte[] light = KontraLightSpill.flood(locals, new int[2], new boolean[]{true, true});
        assertEquals(0, light[0]);
        assertEquals(0, light[1]);
    }
}
