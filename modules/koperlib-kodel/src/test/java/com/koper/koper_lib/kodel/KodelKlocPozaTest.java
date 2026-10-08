package com.koper.koper_lib.kodel;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

// kender's anim ops (suspension shift/stretch) have to move a .kodel block exactly like the geo one
class KodelKlocPozaTest {

    private static KodelModel zawieszenie() {
        KodelModel m = new KodelModel();
        KodelModel.KodelBone down = new KodelModel.KodelBone();
        down.name = "downpart";
        KodelModel.KodelBone spring = new KodelModel.KodelBone();
        spring.name = "spring part";
        spring.parent = 0;
        spring.pivot[1] = 4f;
        KodelModel.KodelBone up = new KodelModel.KodelBone();
        up.name = "uppart";
        up.parent = 1;
        up.pivot[1] = 8f;
        Quaternionf base = new Quaternionf().rotationZYX(0f, 0.3f, 0f);
        up.rotation[0] = base.x; up.rotation[1] = base.y; up.rotation[2] = base.z; up.rotation[3] = base.w;
        m.bones.add(down);
        m.bones.add(spring);
        m.bones.add(up);
        return m;
    }

    private static float[] rest(KodelModel m) {
        float[] out = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], out);
        return out;
    }

    private static Vector3f at(float[] world, int bone, float x, float y, float z) {
        return new Matrix4f().set(world, bone * KodelSampler.MAT4_FLOATS).transformPosition(new Vector3f(x, y, z));
    }

    @Test
    void zeroPoseIsRest() {
        KodelModel m = zawieszenie();
        float[] rest = rest(m);
        float[] posed = KodelKloc.koperPosedBones(m, rest, bone -> new float[9]);
        assertArrayEquals(rest, posed, 1e-5f);
    }

    @Test
    void shiftMovesBoneAndKids() {
        KodelModel m = zawieszenie();
        float[] rest = rest(m);
        Map<String, float[]> pose = new HashMap<>();
        float[] v = new float[9];
        v[4] = 3f / 16f; // kender shift is in blocks
        pose.put("spring part", v);
        float[] posed = KodelKloc.koperPosedBones(m, rest, pose::get);
        assertEquals(at(rest, 1, 0, 5, 0).y + 3f, at(posed, 1, 0, 5, 0).y, 1e-4f);
        assertEquals(at(rest, 2, 1, 9, 1).y + 3f, at(posed, 2, 1, 9, 1).y, 1e-4f);
        assertEquals(at(rest, 0, 0, 1, 0).y, at(posed, 0, 0, 1, 0).y, 1e-5f);
    }

    @Test
    void stretchScalesAroundPivot() {
        KodelModel m = zawieszenie();
        Map<String, float[]> pose = new HashMap<>();
        float[] v = new float[9];
        v[7] = 0.5f;
        pose.put("spring part", v);
        float[] posed = KodelKloc.koperPosedBones(m, rest(m), pose::get);
        assertEquals(4f, at(posed, 1, 0, 4, 0).y, 1e-4f);
        assertEquals(6f, at(posed, 1, 0, 8, 0).y, 1e-4f);
    }

    @Test
    void rotationAddsOnTopOfAuthored() {
        KodelModel m = zawieszenie();
        Map<String, float[]> pose = new HashMap<>();
        float[] v = new float[9];
        v[1] = 0.2f;
        pose.put("uppart", v);
        float[] posed = KodelKloc.koperPosedBones(m, rest(m), pose::get);
        Vector3f expect = new Matrix4f().translation(0, 8, 0)
            .rotate(new Quaternionf().rotationZYX(0f, 0.5f, 0f))
            .translate(0, -8, 0).transformPosition(new Vector3f(2, 9, 0));
        Vector3f got = at(posed, 2, 2, 9, 0);
        assertEquals(expect.x, got.x, 1e-4f);
        assertEquals(expect.z, got.z, 1e-4f);
    }
}
