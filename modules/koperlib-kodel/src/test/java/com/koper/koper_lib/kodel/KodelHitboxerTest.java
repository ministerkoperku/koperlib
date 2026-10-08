package com.koper.koper_lib.kodel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// pure math, no minecraft, no native. it either boxes the right volume or it does not
class KodelHitboxerTest {

    private static KodelModel geo(String json) {
        return KodelConverters.geometry(JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject());
    }

    private static float[] rest(KodelModel m) {
        float[] world = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], world);
        return world;
    }

    private static final String RIG = """
        {"minecraft:geometry":[{
          "description":{"identifier":"geometry.rig"},
          "bones":[
            {"name":"body","pivot":[0,0,0],
             "cubes":[{"origin":[-4,0,-4],"size":[8,8,8],"uv":[0,0]}]},
            {"name":"head_hitbox","parent":"body","pivot":[0,8,0],
             "cubes":[{"origin":[-2,8,-2],"size":[4,4,4],"uv":[0,0]}]}]}]}
        """;

    @Test
    void onlyBonesMarkedHitboxBecomeBoxes() {
        KodelModel m = geo(RIG);
        assertTrue(KodelHitboxer.marksAny(m));
        List<KodelHitboxer.Obb> boxes = KodelHitboxer.of(m, rest(m));
        assertEquals(1, boxes.size(), "body is geometry, not a hitbox");
        assertEquals("head_hitbox", boxes.get(0).bone());
    }

    @Test
    void anUnrotatedBoxLandsExactlyOnItsCube() {
        KodelModel m = geo(RIG);
        KodelHitboxer.Obb box = KodelHitboxer.of(m, rest(m)).get(0);
        // cube spans -2..2 on x and z, 8..12 on y
        assertEquals(0f, box.cx(), 1e-4f);
        assertEquals(10f, box.cy(), 1e-4f);
        assertEquals(0f, box.cz(), 1e-4f);
        assertEquals(2f, Math.abs(box.axx()), 1e-4f, "half extent on x");
        assertEquals(2f, Math.abs(box.ayy()), 1e-4f, "half extent on y");
        assertEquals(2f, Math.abs(box.azz()), 1e-4f, "half extent on z");

        assertTrue(box.contains(0f, 10f, 0f), "dead centre");
        assertTrue(box.contains(1.9f, 11.9f, -1.9f), "just inside the corner");
        assertFalse(box.contains(2.6f, 10f, 0f), "outside on x");
        assertFalse(box.contains(0f, 13f, 0f), "above the top");
    }

    @Test
    void inflateGrowsTheBoxToo() {
        KodelModel plain = geo(RIG);
        KodelModel fat = geo(RIG.replace("\"size\":[4,4,4],\"uv\":[0,0]",
            "\"size\":[4,4,4],\"uv\":[0,0],\"inflate\":1"));
        KodelHitboxer.Obb a = KodelHitboxer.of(plain, rest(plain)).get(0);
        KodelHitboxer.Obb b = KodelHitboxer.of(fat, rest(fat)).get(0);
        assertEquals(2f, Math.abs(a.axx()), 1e-4f);
        assertEquals(3f, Math.abs(b.axx()), 1e-4f, "one pixel each side");
        assertEquals(a.cy(), b.cy(), 1e-4f, "inflate grows the box, it must not move it");
    }

    // the point of an OBB: a rotated bone gets a box that turns with it, not a
    // fatter axis aligned one
    @Test
    void theBoxTurnsWithTheBone() {
        KodelModel m = geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.turn"},
              "bones":[{"name":"arm_hitbox","pivot":[0,0,0],"rotation":[0,0,90],
                "cubes":[{"origin":[0,-1,-1],"size":[8,2,2],"uv":[0,0]}]}]}]}
            """);
        KodelHitboxer.Obb box = KodelHitboxer.of(m, rest(m)).get(0);

        // 90 degrees about z takes the long axis from +x onto +y (or -y, the sign of
        // a half axis vector carries no meaning)
        float longOnY = Math.abs(box.axy()) + Math.abs(box.ayy()) + Math.abs(box.azy());
        float longOnX = Math.abs(box.axx()) + Math.abs(box.ayx()) + Math.abs(box.azx());
        assertTrue(longOnY > longOnX, "the 8 long side should now run up, got y=" + longOnY + " x=" + longOnX);
        assertTrue(box.contains(box.cx(), box.cy(), box.cz()), "centre is always inside");
    }

    @Test
    void animatingTheBoneMovesItsBox() {
        KodelModel m = geo(RIG);
        KodelAnimation anim = new KodelAnimation();
        anim.name = "nod";
        anim.length = 1f;
        var track = new KodelAnimation.KodelTrack();
        track.bone = "head_hitbox";
        track.position = new KodelAnimation.KodelChannel().set(
            new float[] {0f, 1f}, new float[] {0, 0, 0, 0, 6, 0},
            new int[] {KodelFormat.EASE_LINEAR, KodelFormat.EASE_LINEAR},
            new float[6], new float[6]);
        anim.tracks.add(track);

        var tracks = KodelSampler.ResolvedTracks.of(m, anim);
        float[] world = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, tracks, 1f, new float[3], new float[3], new float[3], world);
        KodelHitboxer.Obb moved = KodelHitboxer.of(m, world).get(0);
        KodelHitboxer.Obb still = KodelHitboxer.of(m, rest(m)).get(0);

        assertEquals(still.cy() + 6f, moved.cy(), 1e-4f, "the box has to follow the animation");
    }

    @Test
    void raysHitFromOutsideAndMissWhenTheyShould() {
        KodelModel m = geo(RIG);
        KodelHitboxer.Obb box = KodelHitboxer.of(m, rest(m)).get(0);

        float hit = box.raycast(-20f, 10f, 0f, 1f, 0f, 0f);
        assertTrue(hit > 0f, "straight at the middle should connect, got " + hit);
        assertEquals(18f, hit, 1e-3f, "first face is at x = -2");

        assertEquals(-1f, box.raycast(-20f, 30f, 0f, 1f, 0f, 0f), "way over the top");
        assertEquals(-1f, box.raycast(-20f, 10f, 0f, -1f, 0f, 0f), "fired the other way");
    }

    @Test
    void pickTakesTheNearestBox() {
        KodelModel m = geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.two"},
              "bones":[
                {"name":"near_hitbox","pivot":[0,0,0],
                 "cubes":[{"origin":[-1,-1,-1],"size":[2,2,2],"uv":[0,0]}]},
                {"name":"far_hitbox","pivot":[0,0,0],
                 "cubes":[{"origin":[-1,-1,19],"size":[2,2,2],"uv":[0,0]}]}]}]}
            """);
        List<KodelHitboxer.Obb> boxes = KodelHitboxer.of(m, rest(m));
        assertEquals(2, boxes.size());
        KodelHitboxer.Obb picked = KodelHitboxer.pick(boxes, 0f, 0f, -30f, 0f, 0f, 1f);
        assertNotNull(picked);
        assertEquals("near_hitbox", picked.bone());
    }

    @Test
    void boundsWrapEveryBoxAndVanishWhenThereAreNone() {
        KodelModel m = geo(RIG);
        float[] b = KodelHitboxer.bounds(KodelHitboxer.of(m, rest(m)));
        assertNotNull(b);
        assertEquals(-2f, b[0], 1e-4f);
        assertEquals(8f, b[1], 1e-4f);
        assertEquals(2f, b[3], 1e-4f);
        assertEquals(12f, b[4], 1e-4f);

        assertNull(KodelHitboxer.bounds(List.of()), "no hitbox bones means keep vanilla culling");
    }

    @Test
    void aModelWithNoMarkedBonesIsLeftAlone() {
        KodelModel m = geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.plain"},
              "bones":[{"name":"body","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":[0,0]}]}]}]}
            """);
        assertFalse(KodelHitboxer.marksAny(m));
        assertTrue(KodelHitboxer.of(m, rest(m)).isEmpty());
    }
}
