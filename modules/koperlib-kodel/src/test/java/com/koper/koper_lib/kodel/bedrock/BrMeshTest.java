package com.koper.koper_lib.kodel.bedrock;

import com.koper.koper_lib.kodel.KodelModel;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

// KOPER_BEDROCK_CONVERTED: where a held item's texture mesh ends up in its own bone space, the
// cubes turned about their pivots. a held sword has its grip on the item bone, not 20px away
class BrMeshTest {

    @Test
    void meshBounds() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_CONVERTED");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)) && BrNatywka.ready());
        BrPaczki.Paczka p = BrPaczki.czytaj(Path.of(env));
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        idx.packs.add(p);
        for (String item : System.getenv().getOrDefault("KOPER_BEDROCK_ITEMS", "minecraft:diamond_sword,minecraft:bow").split(",")) {
            BrTyp t = BrTyp.zbuduj(idx, p, item, p.attachables.get(item));
            BrTyp.Geo g = t.geos.get(0);
            KodelModel m = g.model;
            // one third person tick, main hand: the bone matrices the draw would use
            long h = BrNatywka.spawn(g.def, 3);
            BrKlatka k = new BrKlatka(g.bones.length);
            float[] ctx = new float[g.contexts.size()];
            int[] cstr = new int[g.contexts.size()];
            java.util.Arrays.fill(cstr, -1);
            for (int i = 0; i < g.contexts.size(); i++)
                if (g.contexts.get(i).equals("item_slot")) cstr[i] = g.strings.getOrDefault("main_hand", -2);
            int[] qs = new int[g.queries.length];
            java.util.Arrays.fill(qs, -1);
            for (int f = 0; f < 5; f++) BrNatywka.tick(h, new float[g.queries.length], qs, ctx, cstr, 1 / 20f, k, false);
            BrNatywka.free(h);
            for (KodelModel.KodelBone b : m.bones) {
                if (b.cubes.isEmpty()) continue;
                float[] lo = {1e9f, 1e9f, 1e9f}, hi = {-1e9f, -1e9f, -1e9f};
                for (KodelModel.KodelCube c : b.cubes) {
                    Quaternionf q = new Quaternionf(c.rotation[0], c.rotation[1], c.rotation[2], c.rotation[3]);
                    for (int corner = 0; corner < 8; corner++) {
                        Vector3f v = new Vector3f(c.origin[0] + ((corner & 1) != 0 ? c.size[0] : 0), c.origin[1] + ((corner & 2) != 0 ? c.size[1] : 0),
                            c.origin[2] + ((corner & 4) != 0 ? c.size[2] : 0)).sub(c.pivot[0], c.pivot[1], c.pivot[2]);
                        q.transform(v).add(c.pivot[0], c.pivot[1], c.pivot[2]);
                        int bi = m.bones.indexOf(b);
                        // bone matrices and cubes are both in pixels
                        org.joml.Matrix4f w = new org.joml.Matrix4f().set(k.mats, bi * 16);
                        org.joml.Vector3f wv = w.transformPosition(new org.joml.Vector3f(v));
                        if (System.getenv("KOPER_POSED") != null) v.set(wv);
                        lo[0] = Math.min(lo[0], v.x); lo[1] = Math.min(lo[1], v.y); lo[2] = Math.min(lo[2], v.z);
                        hi[0] = Math.max(hi[0], v.x); hi[1] = Math.max(hi[1], v.y); hi[2] = Math.max(hi[2], v.z);
                    }
                }
                System.out.printf("%s bone %s pivot=[%.1f %.1f %.1f] %d cubes, bounds x %.1f..%.1f y %.1f..%.1f z %.1f..%.1f%n", item, b.name,
                    b.pivot[0], b.pivot[1], b.pivot[2], b.cubes.size(), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
            }
        }
    }
}
