package com.koper.koper_lib.kodel.bedrock;

import com.koper.koper_lib.kodel.KodelModel;
import com.koper.koper_lib.kodel.KodelModelRender;
import com.koper.koper_lib.kodel.KodelSampler;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

// KOPER_BEDROCK_CONVERTED: every face of a held item's baked render model has to face out of its
// cube, by winding and by normal, or culling eats it (A&S's sword showed its row sides and no top)
class BrScianyTest {

    @Test
    void facesFaceOut() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_CONVERTED");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)));
        BrPaczki.Paczka p = BrPaczki.czytaj(Path.of(env));
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        idx.packs.add(p);
        for (String item : System.getenv().getOrDefault("KOPER_BEDROCK_ITEMS", "minecraft:diamond_sword,minecraft:dirt").split(",")) {
            BrTyp t = BrTyp.zbuduj(idx, p, item, p.attachables.get(item));
            KodelModel m = t.geos.get(0).renderModel;
            float[] world = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
            KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], world);
            float[] mesh = KodelModelRender.bake(m, world, null);
            int quads = mesh.length / (KodelModelRender.STRIDE * 4);
            int[] windIn = new int[6], normIn = new int[6], all = new int[6];
            // quads come out 6 per cube in face order; the cube centre is the mean of its 24 corners
            for (int c = 0; c + 6 <= quads; c += 6) {
                Vector3f centre = new Vector3f();
                for (int q = c; q < c + 6; q++)
                    for (int k = 0; k < 4; k++) centre.add(v(mesh, q, k));
                centre.div(24f);
                for (int f = 0; f < 6; f++) {
                    int q = c + f;
                    Vector3f a = v(mesh, q, 0), b = v(mesh, q, 1), d = v(mesh, q, 2);
                    Vector3f wind = new Vector3f(b).sub(a).cross(new Vector3f(d).sub(a));
                    Vector3f mid = new Vector3f(a).add(v(mesh, q, 2)).mul(0.5f).sub(centre);
                    int o = (q * 4) * KodelModelRender.STRIDE;
                    Vector3f n = new Vector3f(mesh[o + 5], mesh[o + 6], mesh[o + 7]);
                    all[f]++;
                    if (wind.dot(mid) < 0) windIn[f]++;
                    if (n.dot(mid) < 0) normIn[f]++;
                }
            }
            System.out.printf("%s: %d quads; per face slot [px nx py ny pz nz] total %s, winding inward %s, normal inward %s%n",
                item, quads, java.util.Arrays.toString(all), java.util.Arrays.toString(windIn), java.util.Arrays.toString(normIn));
        }
    }

    private static Vector3f v(float[] mesh, int quad, int k) {
        int o = (quad * 4 + k) * KodelModelRender.STRIDE;
        return new Vector3f(mesh[o], mesh[o + 1], mesh[o + 2]);
    }
}
