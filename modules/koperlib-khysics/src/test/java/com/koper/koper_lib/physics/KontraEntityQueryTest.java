package com.koper.koper_lib.physics;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KontraEntityQueryTest {
    private static final float[] IDENTITY = {0, 0, 0, 1};

    @Test
    void translatedQueryKeepsItsActualVolume() {
        var query = new KontraEntityQuery(new AABB(-1, -.5, -.25, 1, .5, .25),
            new float[]{10, 20, 30}, IDENTITY);
        assertEquals(new AABB(9, 19.5, 29.75, 11, 20.5, 30.25), query.bounds());
        assertTrue(query.intersects(new AABB(10, 20, 30, 10.1, 20.1, 30.1)));
        assertFalse(query.intersects(new AABB(10, 20, 31, 10.1, 20.1, 31.1)));
    }

    @Test
    void rotatedQueryRejectsEmptyBroadPhaseCorners() {
        var query = new KontraEntityQuery(new AABB(-2, -.5, -.125, 2, .5, .125),
            new float[]{0, 0, 0}, new float[]{0, .38268343f, 0, .9238795f});
        AABB inside = new AABB(.9, -.1, -1.1, 1.1, .1, -.9);
        AABB corner = new AABB(.9, -.1, .9, 1.1, .1, 1.1);
        assertTrue(query.bounds().intersects(corner));
        assertTrue(query.intersects(inside));
        assertFalse(query.intersects(corner));
    }

    @Test
    void tiltedEdgesCanSeparateWithoutAnyFaceAxisSeparating() {
        var query = new KontraEntityQuery(new AABB(-2, -.3, -.2, 2, .3, .2),
            new float[]{0, 0, 0}, new float[]{.2f, .3f, .1f, .92736185f});
        AABB edgeCorner = new AABB(-.09, -.51, -.99, .31, .09, -.59);
        assertTrue(query.bounds().intersects(edgeCorner));
        assertFalse(query.intersects(edgeCorner));
    }

    @Test
    void overlappingEntityEdgeCountsEvenWhenItsCenterIsOutside() {
        var query = new KontraEntityQuery(new AABB(-1, -1, -1, 1, 1, 1),
            new float[]{0, 0, 0}, IDENTITY);
        assertTrue(query.intersects(new AABB(.9, -.1, -.1, 2, .1, .1)));
        assertFalse(query.intersects(new AABB(1, -.1, -.1, 2, .1, .1)));
    }

    @Test
    void largeTranslationRetainsSubBlockBounds() {
        var query = new KontraEntityQuery(new AABB(-.125, -.125, -.125, .125, .125, .125),
            new float[]{10_000_000, 100, -10_000_000}, IDENTITY);
        assertEquals(9_999_999.875, query.bounds().minX);
        assertEquals(-9_999_999.875, query.bounds().maxZ);
        assertTrue(query.intersects(new AABB(10_000_000.1, 100, -10_000_000,
            10_000_000.2, 100.1, -9_999_999.9)));
    }

    @Test
    void querySnapshotsItsPose() {
        float[] pos = {10, 20, 30};
        float[] rot = IDENTITY.clone();
        var query = new KontraEntityQuery(new AABB(-1, -1, -1, 1, 1, 1), pos, rot);
        pos[0] = 200;
        rot[3] = 0;
        assertTrue(query.intersects(new AABB(9.5, 19.5, 29.5, 10.5, 20.5, 30.5)));
        assertEquals(9, query.bounds().minX);
    }

    @Test
    void pointQueryPreservesVanillaInteriorOverlap() {
        AABB point = new AABB(0, 0, 0, 0, 0, 0);
        AABB entity = new AABB(-1, -1, -1, 1, 1, 1);
        assertTrue(point.intersects(entity));
        var query = new KontraEntityQuery(point, new float[]{0, 0, 0}, IDENTITY);
        assertTrue(query.intersects(entity));
        assertFalse(query.intersects(new AABB(0, -1, -1, 1, 1, 1)));
    }

    @Test
    void zeroSizeEntityIsFoundInsideAnIdentityQuery() {
        AABB box = new AABB(-1, -1, -1, 1, 1, 1);
        AABB point = new AABB(.25, 0, 0, .25, 0, 0);
        assertTrue(box.intersects(point));
        var query = new KontraEntityQuery(box, new float[]{0, 0, 0}, IDENTITY);
        assertTrue(query.intersects(point));
        assertFalse(query.intersects(new AABB(1, 0, 0, 1, 0, 0)));
    }

    @Test
    void rotatedQueryFindsPointEntitiesAndRejectsEmptyCorners() {
        var query = new KontraEntityQuery(new AABB(-2, -.5, -.125, 2, .5, .125),
            new float[]{0, 0, 0}, new float[]{0, .38268343f, 0, .9238795f});
        assertTrue(query.intersects(new AABB(1, 0, -1, 1, 0, -1)));
        assertFalse(query.intersects(new AABB(1, 0, 1, 1, 0, 1)));
    }
}
