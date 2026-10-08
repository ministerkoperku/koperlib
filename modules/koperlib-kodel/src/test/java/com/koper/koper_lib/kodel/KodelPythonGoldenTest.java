package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// three implementations claim to write the same container: java, the python
// reference and the blockbench plugin. nothing ever checked. this pins java against
// bytes python actually produced from the geo sitting next to it.
//
// NOT byte exact on purpose. python does its trig in float64 and java in float32, so
// a quaternion off a 15 degree rotation lands one ulp apart. chasing that is a
// losing game. everything that can actually be WRONG -- face tables, parent order,
// mirror, inflate, counts -- is compared exactly. the byte exact check that does
// hold is read-then-write, and that one is below.
//
// regenerate golden_python.model.bin with aq/kodel/python after a format change
class KodelPythonGoldenTest {
    private static final float ULP = 1e-5f;

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = KodelPythonGoldenTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name + " missing from test resources");
            return in.readAllBytes();
        }
    }

    private static KodelModel javaSide() throws Exception {
        JsonObject file = JsonParser.parseString(
            new String(resource("/golden_python.geo.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        return KodelConverters.geometry(file.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject());
    }

    @Test
    void javaImportsTheGeoExactlyLikeThePythonReference() throws Exception {
        KodelModel mine = javaSide();
        KodelModel theirs = KodelModel.read(resource("/golden_python.model.bin"));

        assertEquals(theirs.bones.size(), mine.bones.size(), "bone count");
        assertEquals(theirs.meshes.size(), mine.meshes.size(), "mesh count");

        for (int i = 0; i < theirs.bones.size(); i++) {
            KodelModel.KodelBone t = theirs.bones.get(i);
            KodelModel.KodelBone j = mine.bones.get(i);
            String at = "bone " + i + " (" + t.name + ")";
            assertEquals(t.name, j.name, at + " name, so the sort order agrees");
            assertEquals(t.parent, j.parent, at + " parent");
            assertArrayEquals(t.pivot, j.pivot, ULP, at + " pivot");
            assertArrayEquals(t.position, j.position, ULP, at + " position");
            assertArrayEquals(t.rotation, j.rotation, ULP, at + " rotation");
            assertArrayEquals(t.scale, j.scale, ULP, at + " scale");
            assertEquals(t.visible, j.visible, at + " visible");
            assertEquals(t.cubes.size(), j.cubes.size(), at + " cube count");

            for (int c = 0; c < t.cubes.size(); c++) {
                KodelModel.KodelCube tc = t.cubes.get(c);
                KodelModel.KodelCube jc = j.cubes.get(c);
                String cube = at + " cube " + c;
                assertArrayEquals(tc.origin, jc.origin, ULP, cube + " origin");
                assertArrayEquals(tc.size, jc.size, ULP, cube + " size");
                assertEquals(tc.inflate, jc.inflate, ULP, cube + " inflate");
                assertArrayEquals(tc.pivot, jc.pivot, ULP, cube + " pivot");
                assertArrayEquals(tc.rotation, jc.rotation, ULP, cube + " rotation");
                assertEquals(tc.mirror, jc.mirror, cube + " mirror");
                for (int f = 0; f < 6; f++) {
                    String face = cube + " face " + KodelConverters.FACE_NAMES[f];
                    assertEquals(tc.faces[f].u, jc.faces[f].u, ULP, face + " u");
                    assertEquals(tc.faces[f].v, jc.faces[f].v, ULP, face + " v");
                    assertEquals(tc.faces[f].uw, jc.faces[f].uw, ULP, face + " uw");
                    assertEquals(tc.faces[f].vh, jc.faces[f].vh, ULP, face + " vh");
                    assertEquals(tc.faces[f].rot, jc.faces[f].rot, face + " rot");
                    assertEquals(tc.faces[f].mirror, jc.faces[f].mirror, face + " mirror");
                }
            }
        }
    }

    // this one IS byte exact. nothing here recomputes anything, so anything that
    // moves means the reader and the writer disagree about the layout
    @Test
    void readThenWriteIsTheIdentity() throws Exception {
        byte[] golden = resource("/golden_python.model.bin");
        assertArrayEquals(golden, KodelModel.read(golden).write(),
            "read then write is not the identity, reader and writer disagree");
    }

    @Test
    void theGoldenModelSurvivesJavasOwnRoundTrip() throws Exception {
        KodelModel mine = javaSide();
        assertArrayEquals(mine.write(), KodelModel.read(mine.write()).write());
    }
}
