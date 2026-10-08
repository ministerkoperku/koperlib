package com.koper.koper_lib.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KontraProjectionOverlapTest {
    @Test
    void identityContainsItsOwnCell() {
        assertTrue(test(new float[]{0, 0, 0, 1}).touches(new float[]{.5f, .5f, .5f}, 0, 0, 0));
    }

    @Test
    void tinyRotationCannotEraseTheWholeProjection() {
        for (int axis = 0; axis < 3; axis++) {
            float[] rotation = {0, 0, 0, 1};
            rotation[axis] = .00002f;
            assertTrue(test(rotation).touches(new float[]{.5f, .5f, .5f}, 0, 0, 0),
                "tiny rotation around axis " + axis + " erased a fully overlapping cell");
        }
    }

    @Test
    void tinyRotationKeepsASettledCellAtWorldCoordinates() {
        assertTrue(test(new float[]{.00002f, 0, .00002f, 1})
            .touches(new float[]{-1535.4995f, 6.4987273f, -390.49997f}, -1536, 6, -391));
    }

    @Test
    void rotatedProjectionRejectsAnEmptyBroadPhaseCorner() {
        var rotated = test(new float[]{0, .38268343f, 0, .9238795f});
        float[] center = {.5f, .5f, .5f};
        assertTrue(rotated.touches(center, 1, 0, 0));
        assertFalse(rotated.touches(center, 1, 0, 1));
    }

    @Test
    void separatedAndOnlyTouchingCellsStayOutside() {
        var nearIdentity = test(new float[]{.00002f, 0, 0, 1});
        float[] center = {.5f, .5f, .5f};
        assertFalse(nearIdentity.touches(center, 2, 0, 0));
        assertFalse(nearIdentity.touches(center, 1, 0, 0));
    }

    private static KoperPhys.ObbCellTest test(float[] rotation) {
        return KoperPhys.obbCellTest(KoperPhys.quaternionAxes(rotation));
    }
}
