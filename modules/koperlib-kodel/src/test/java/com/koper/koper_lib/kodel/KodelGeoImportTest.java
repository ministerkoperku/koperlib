package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// conformance, not a smoke test. every number below was worked out from
// kodel/SPEC.md by hand, so when this fails the code moved and not the test.
// no Assumptions in here on purpose, it has to fail on a machine with no native
class KodelGeoImportTest {

    private static JsonObject geo(String json) {
        return JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // w=4 h=6 d=2 at uv 0,0. the atlas is two rows: caps on top (tall d), sides
    // under them (tall h). this table is THE thing that was scrambled before
    @Test
    void classicBoxUnwrapMatchesSpec() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.box","texture_width":64,"texture_height":64},
              "bones":[{"name":"root","pivot":[0,0,0],
                "cubes":[{"origin":[0,0,0],"size":[4,6,2],"uv":[0,0]}]}]}]}
            """));
        KodelModel.KodelFace[] f = m.bones.get(0).cubes.get(0).faces;

        assertFace(f[0], 0, 2, 2, 6, "px east");
        assertFace(f[1], 6, 2, 2, 6, "nx west");
        assertFace(f[2], 2, 0, 4, 2, "py up");
        assertFace(f[3], 6, 2, 4, -2, "ny down");
        assertFace(f[4], 8, 2, 4, 6, "pz south");
        assertFace(f[5], 2, 2, 4, 6, "nz north");
    }

    private static void assertFace(KodelModel.KodelFace f, float u, float v, float uw, float vh, String what) {
        assertEquals(u, f.u, 1e-5f, what + " u");
        assertEquals(v, f.v, 1e-5f, what + " v");
        assertEquals(uw, f.uw, 1e-5f, what + " uw");
        assertEquals(vh, f.vh, 1e-5f, what + " vh");
    }

    // pz is south, SPEC says so. python had this backwards and put every
    // per-face-uv model's face on its back
    @Test
    void perFaceUvMapsSouthToPz() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.faces"},
              "bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{
                "south":{"uv":[10,20],"uv_size":[1,1]},
                "north":{"uv":[30,40],"uv_size":[1,1]},
                "up":{"uv":[50,60],"uv_size":[1,1],"uv_rotation":90}}}]}]}]}
            """));
        KodelModel.KodelFace[] f = m.bones.get(0).cubes.get(0).faces;
        assertEquals(10f, f[4].u, 1e-5f, "pz must take the south entry");
        assertEquals(30f, f[5].u, 1e-5f, "nz must take the north entry");
        assertEquals(1, f[2].rot, "90 degrees is one quarter turn");
    }

    // bedrock never promised parents come first in the array
    @Test
    void childDeclaredBeforeItsParentStillHangsOffIt() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.order"},
              "bones":[
                {"name":"hand","parent":"arm","pivot":[0,0,0]},
                {"name":"arm","parent":"body","pivot":[0,0,0]},
                {"name":"body","pivot":[0,0,0]}]}]}
            """));
        assertEquals(3, m.bones.size());
        int body = m.boneIndex("body");
        int arm = m.boneIndex("arm");
        int hand = m.boneIndex("hand");
        assertEquals(-1, m.bones.get(body).parent, "body is the root");
        assertEquals(body, m.bones.get(arm).parent);
        assertEquals(arm, m.bones.get(hand).parent);
        assertTrue(body < arm && arm < hand, "model.bin needs parents written first");
        m.validate();
    }

    // a cycle must not lose bones or hang the importer
    @Test
    void parentCycleDegradesInsteadOfLoopingForever() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.cycle"},
              "bones":[
                {"name":"a","parent":"b","pivot":[0,0,0]},
                {"name":"b","parent":"a","pivot":[0,0,0]}]}]}
            """));
        assertEquals(2, m.bones.size());
    }

    // default lerp_mode is LINEAR. calling it smooth put overshoot on every clip
    @Test
    void defaultLerpModeStaysLinear() {
        List<KodelAnimation> anims = KodelConverters.animations(json("""
            {"animations":{"animation.test.idle":{"animation_length":2.0,"bones":{"arm":{
              "rotation":{"0.0":[0,0,0],"1.0":{"vector":[90,0,0]},
                          "2.0":{"vector":[0,0,0],"lerp_mode":"catmullrom"}}}}}}}
            """));
        assertEquals(1, anims.size());
        KodelAnimation.KodelChannel ch = anims.get(0).tracks.get(0).rotation;
        assertEquals(3, ch.count);
        assertEquals(KodelFormat.EASE_LINEAR, ch.easing[0], "bare vector is linear");
        assertEquals(KodelFormat.EASE_LINEAR, ch.easing[1], "vector with no lerp_mode is linear");
        assertEquals(KodelFormat.EASE_BEZIER, ch.easing[2], "catmullrom is the smooth one");
        assertEquals((float) Math.toRadians(90), ch.values[3], 1e-5f, "rotation keys are radians");
    }

    // clips without animation_length used to get chopped to one second
    @Test
    void missingAnimationLengthComesFromTheLastKey() {
        List<KodelAnimation> anims = KodelConverters.animations(json("""
            {"animations":{"animation.test.long":{"bones":{"arm":{
              "position":{"0.0":[0,0,0],"3.5":[0,8,0]}}}}}}
            """));
        assertEquals(3.5f, anims.get(0).length, 1e-5f);
    }

    @Test
    void keyframesOutOfOrderGetSorted() {
        List<KodelAnimation> anims = KodelConverters.animations(json("""
            {"animations":{"animation.test.jumbled":{"animation_length":3.0,"bones":{"arm":{
              "position":{"2.0":[2,0,0],"0.0":[0,0,0],"1.0":[1,0,0]}}}}}}
            """));
        KodelAnimation.KodelChannel ch = anims.get(0).tracks.get(0).position;
        assertEquals(0f, ch.times[0], 1e-6f);
        assertEquals(1f, ch.times[1], 1e-6f);
        assertEquals(2f, ch.times[2], 1e-6f);
        assertEquals(2f, ch.values[6], 1e-6f, "values must travel with their time");
    }

    // pre/post is a snap, not a bezier handle. becomes two keys a hair apart
    @Test
    void preAndPostBecomeASnap() {
        List<KodelAnimation> anims = KodelConverters.animations(json("""
            {"animations":{"animation.test.snap":{"animation_length":2.0,"bones":{"arm":{
              "position":{"0.0":[0,0,0],
                          "1.0":{"pre":{"vector":[5,0,0]},"post":{"vector":[-5,0,0]}}}}}}}}
            """));
        KodelAnimation.KodelChannel ch = anims.get(0).tracks.get(0).position;
        assertEquals(3, ch.count, "one key in, one key out, plus the opener");
        assertEquals(5f, ch.values[3], 1e-5f, "pre value arrives first");
        assertEquals(-5f, ch.values[6], 1e-5f, "post value leaves");
        assertTrue(ch.times[1] < ch.times[2], "the two halves cannot share a time");

        float[] out = new float[3];
        KodelSampler.sample(ch, 1.5f, out);
        assertEquals(-5f, out[0], 1e-5f, "after the snap it holds the post value");
    }

    // molang keyframes are strings, they need game state, they get dropped
    @Test
    void molangKeyframesAreSkippedNotCrashed() {
        List<KodelAnimation> anims = KodelConverters.animations(json("""
            {"animations":{"animation.test.molang":{"animation_length":1.0,"bones":{"arm":{
              "rotation":{"0.0":["math.sin(query.anim_time * 90)",0,0],"0.5":[45,0,0]}}}}}}
            """));
        KodelAnimation.KodelChannel ch = anims.get(0).tracks.get(0).rotation;
        assertEquals(1, ch.count, "only the numeric key survives");
        assertEquals(0.5f, ch.times[0], 1e-6f);
    }

    // positions/normals/uvs are pools, polys picks from them. reading the pools as
    // a vertex list turned every poly mesh into confetti
    @Test
    void polyMeshUsesThePolysIndices() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.poly","texture_width":16,"texture_height":16},
              "bones":[{"name":"root","pivot":[0,0,0],"poly_mesh":{
                "normalized_uvs":false,
                "positions":[[0,0,0],[1,0,0],[1,1,0],[0,1,0]],
                "normals":[[0,0,1]],
                "uvs":[[0,0],[1,0],[1,1],[0,1]],
                "polys":[[[0,0,0],[1,0,1],[2,0,2],[3,0,3]]]}}]}]}
            """));
        assertEquals(1, m.meshes.size());
        KodelModel.KodelMesh mesh = m.meshes.get(0);
        assertEquals(6, mesh.vertexCount(), "one quad triangulates into two tris");
        assertEquals(0, mesh.bone);
        // second triangle of the fan is 0,2,3 -> its middle vertex is pool entry 2
        assertEquals(1f, mesh.positions[4 * 3], 1e-5f);
        assertEquals(1f, mesh.positions[4 * 3 + 1], 1e-5f);
        assertEquals(1f, mesh.normals[2], 1e-5f, "every corner shares normal 0");
    }

    @Test
    void normalizedPolyMeshUvsBecomePixelsTopLeft() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.polyuv","texture_width":32,"texture_height":16},
              "bones":[{"name":"root","poly_mesh":{
                "normalized_uvs":true,
                "positions":[[0,0,0],[1,0,0],[1,1,0]],
                "normals":[[0,0,1]],
                "uvs":[[0.5,0.25]],
                "polys":"tri_list"}}]}]}
            """));
        KodelModel.KodelMesh mesh = m.meshes.get(0);
        assertEquals(16f, mesh.uvs[0], 1e-4f, "0.5 across a 32px atlas");
        assertEquals(12f, mesh.uvs[1], 1e-4f, "0.25 from the bottom is 12px from the top of 16");
    }

    // bone-level mirror and inflate apply to every cube that doesn't override them
    @Test
    void boneLevelMirrorAndInflateReachTheCubes() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.inherit"},
              "bones":[{"name":"root","mirror":true,"inflate":0.25,
                "cubes":[{"origin":[0,0,0],"size":[2,2,2],"uv":[0,0]},
                         {"origin":[0,0,0],"size":[2,2,2],"uv":[0,0],"inflate":1.5}]}]}]}
            """));
        List<KodelModel.KodelCube> cubes = m.bones.get(0).cubes;
        assertEquals(0.25f, cubes.get(0).inflate, 1e-6f);
        assertEquals(1.5f, cubes.get(1).inflate, 1e-6f, "the cube's own inflate wins");
        assertTrue(cubes.get(0).mirror);
        assertTrue(cubes.get(0).faces[0].mirror, "a mirrored box flips its faces");
    }

    // mirror is a per face flag the renderer turns into a u flip. it must NOT
    // also swap the px/nx rects, that mirrors the box twice and lands back where
    // it started with the caps on the wrong sides
    @Test
    void mirrorFlagsTheFacesWithoutMovingTheRects() {
        String body = """
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.m"},
              "bones":[{"name":"root","cubes":[
                {"origin":[0,0,0],"size":[4,6,2],"uv":[0,0],"mirror":%s}]}]}]}
            """;
        KodelModel plain = KodelConverters.geometry(geo(body.formatted("false")));
        KodelModel flipped = KodelConverters.geometry(geo(body.formatted("true")));
        KodelModel.KodelFace[] p = plain.bones.get(0).cubes.get(0).faces;
        KodelModel.KodelFace[] f = flipped.bones.get(0).cubes.get(0).faces;
        for (int i = 0; i < 6; i++) {
            assertEquals(p[i].u, f[i].u, 1e-5f, KodelConverters.FACE_NAMES[i] + " rect must not move");
            assertTrue(f[i].mirror, KodelConverters.FACE_NAMES[i] + " must carry the flag");
            assertFalse(p[i].mirror);
        }
    }

    // inflate grows the box both ways and does NOT move it. python and the old java
    // also translated by 2*inflate, so armor layers drifted off the body
    @Test
    void inflateGrowsTheBoxWithoutMovingIt() {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.inflate"},
              "bones":[{"name":"root","pivot":[0,0,0],
                "cubes":[{"origin":[0,0,0],"size":[4,4,4],"uv":[0,0],"inflate":1}]}]}]}
            """));
        float[] world = new float[16 * m.bones.size()];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], world);
        float[] baked = KodelModelRender.bake(m, world, null);

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (int i = 0; i + KodelModelRender.STRIDE <= baked.length; i += KodelModelRender.STRIDE) {
            minX = Math.min(minX, baked[i]);
            maxX = Math.max(maxX, baked[i]);
        }
        // pixels over 16, so -1px .. 5px
        assertEquals(-1f / 16f, minX, 1e-5f, "inflated box starts one pixel below origin");
        assertEquals(5f / 16f, maxX, 1e-5f, "and ends one pixel past origin+size");
    }

    // the binary is fed by content packs, it does not get to trust anything
    @Test
    void corruptCountsAreRefusedNotAllocated() {
        KodelFormat.Writer w = new KodelFormat.Writer();
        KodelFormat.header(w);
        w.u32(0x7fffffffL); // "two billion bones" in a twenty byte file
        assertThrows(KodelFormat.Corruption.class, () -> KodelModel.read(w.bytes()));
    }

    @Test
    void forwardParentIsRefusedAtLoad() {
        KodelModel m = new KodelModel();
        KodelModel.KodelBone child = new KodelModel.KodelBone();
        child.name = "child";
        child.parent = 1; // points at a bone written after it
        KodelModel.KodelBone parent = new KodelModel.KodelBone();
        parent.name = "parent";
        m.bones.add(child);
        m.bones.add(parent);

        byte[] bytes = m.write();
        KodelFormat.Corruption boom =
            assertThrows(KodelFormat.Corruption.class, () -> KodelModel.read(bytes));
        assertTrue(boom.getMessage().contains("parent"), boom.getMessage());
    }

    // model.bin does NOT store the atlas size, only manifest.json's frame does.
    // packing from the live model is the only way that number survives the trip
    @Test
    void packingFromTheModelCarriesTheAtlasSize() throws java.io.IOException {
        KodelModel m = KodelConverters.geometry(geo("""
            {"minecraft:geometry":[{
              "description":{"identifier":"geometry.big","texture_width":128,"texture_height":64},
              "bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":[0,0]}]}]}]}
            """));
        assertEquals(128, m.texWidth);

        byte[] packed = KodelPackager.pack(m, List.of(), null, "big");
        KodelLoader.Loaded loaded = KodelLoader.read(new java.io.ByteArrayInputStream(packed));
        assertNotNull(loaded.model());
        assertEquals(128, loaded.model().texWidth);
        assertEquals(64, loaded.model().texHeight);
    }

    // a manifest missing "model" used to NPE instead of saying what was wrong
    @Test
    void manifestWithoutModelEntryFallsBackInsteadOfNpe() throws java.io.IOException {
        KodelModel m = new KodelModel();
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zip.write("{\"format\":\"kodel\",\"version\":[1,0]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("model.bin"));
            zip.write(m.write());
            zip.closeEntry();
        }
        KodelLoader.Loaded loaded = KodelLoader.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertNotNull(loaded.model());
    }

    // a frame that isn't an object used to throw ClassCastException out of gson
    @Test
    void junkFrameDoesNotBlowUpTheLoader() throws java.io.IOException {
        KodelModel m = new KodelModel();
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zip.write(("{\"format\":\"kodel\",\"model\":\"model.bin\",\"frame\":128}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("model.bin"));
            zip.write(m.write());
            zip.closeEntry();
        }
        KodelLoader.Loaded loaded = KodelLoader.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals(64, loaded.model().texWidth, "junk frame falls back, it does not throw");
    }
}
