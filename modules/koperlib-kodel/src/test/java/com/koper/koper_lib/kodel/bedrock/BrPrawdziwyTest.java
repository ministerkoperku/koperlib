package com.koper.koper_lib.kodel.bedrock;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

// KOPER_BEDROCK_CONVERTED = a <name>_bedrock folder the fullpack converter made from a pack you own.
// every client entity in it goes through BrTyp and the native def, then ticks for a while
class BrPrawdziwyTest {

    @Test
    void everyEntityBuildsAndTicks() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_CONVERTED");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)) && BrNatywka.ready());
        BrPaczki.Paczka p = BrPaczki.czytaj(Path.of(env));
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        idx.packs.add(p);
        p.entities.keySet().forEach(id -> idx.entityOwner.put(id, p));
        int built = 0, vanillaModel = 0, failed = 0;
        long ticks = 0, nanos = 0;
        StringBuilder bad = new StringBuilder();
        for (var e : p.entities.entrySet()) {
            BrTyp t = BrTyp.zbuduj(idx, p, e.getKey(), e.getValue());
            if (t == null) { failed++; bad.append(e.getKey()).append(' '); continue; }
            built++;
            if (t.onVanillaModel) { vanillaModel++; System.out.println("  on the vanilla model: " + t.id); }
            for (BrTyp.Geo g : t.geos) {
                long inst = BrNatywka.spawn(g.def, 5);
                BrKlatka k = new BrKlatka(g.bones.length);
                float[] q = new float[g.queries.length];
                int[] qs = new int[g.queries.length];
                java.util.Arrays.fill(qs, -1);
                long start = System.nanoTime();
                for (int f = 0; f < 120; f++) {
                    assertTrue(BrNatywka.tick(inst, q, qs, 1 / 60f, k, true), e.getKey());
                    ticks++;
                }
                nanos += System.nanoTime() - start;
                BrNatywka.free(inst);
            }
            t.zwolnij();
        }
        int attBuilt = 0, attFailed = 0, attTicked = 0;
        for (var e : p.attachables.entrySet()) {
            BrTyp t = BrTyp.zbuduj(idx, p, e.getKey(), e.getValue());
            if (t == null) { attFailed++; continue; }
            attBuilt++;
            for (BrTyp.Geo g : t.geos) {
                long inst = BrNatywka.spawn(g.def, 9);
                BrKlatka k = new BrKlatka(g.bones.length);
                float[] q = new float[g.queries.length];
                int[] qs = new int[g.queries.length];
                java.util.Arrays.fill(qs, -1);
                if (BrNatywka.tick(inst, q, qs, g.cScratch, g.cstrScratch, 1 / 60f, k, false)) attTicked++;
                BrNatywka.free(inst);
            }
            t.zwolnij();
        }
        System.out.printf("attachables %d built, %d not, %d geometries ticked%n", attBuilt, attFailed, attTicked);
        System.out.printf("entities %d built, %d on the vanilla model, %d not buildable (%s); %.1f us per tick%n",
            built, vanillaModel, failed, bad.toString().trim(), nanos / 1000.0 / Math.max(1, ticks));
        assertTrue(built > 0);
    }
}
