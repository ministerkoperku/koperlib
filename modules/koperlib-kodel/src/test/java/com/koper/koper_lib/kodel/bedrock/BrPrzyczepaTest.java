package com.koper.koper_lib.kodel.bedrock;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

// KOPER_BEDROCK_CONVERTED again: an item attachable held in the main hand of the pack's player.
// packs hide held items until the holder says so (parent_setup + c.owning_entity->v.x), a broken
// link there = every item invisible. KOPER_BEDROCK_ITEM picks the item, golden apple by default
class BrPrzyczepaTest {

    @Test
    void heldItemShows() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_CONVERTED");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)) && BrNatywka.ready());
        BrPaczki.Paczka p = BrPaczki.czytaj(Path.of(env));
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        idx.packs.add(p);
        p.entities.keySet().forEach(id -> idx.entityOwner.put(id, p));
        String item = System.getenv().getOrDefault("KOPER_BEDROCK_ITEM", "minecraft:golden_apple");

        BrTyp gracz = BrTyp.zbuduj(idx, p, "minecraft:player", p.entities.get("minecraft:player"));
        BrTyp.Geo pg = gracz.geos.get(0);
        for (BrTyp.Geo x : gracz.geos) if (x.model == null) { pg = x; break; }
        String attId = null;
        for (var e : p.attachables.entrySet()) {
            var d = e.getValue();
            if (d.has("identifier") && d.get("identifier").getAsString().equals(item)) { attId = e.getKey(); break; }
        }
        if (attId == null && p.attachables.containsKey(item)) attId = item;
        assertTrue(attId != null, "no attachable for " + item + " among " + p.attachables.size());
        BrTyp att = BrTyp.zbuduj(idx, p, attId, p.attachables.get(attId));
        assertTrue(att != null, attId);
        BrTyp.Geo ag = att.geos.get(0);

        long ph = BrNatywka.spawn(pg.def, 1), ah = BrNatywka.spawn(ag.def, 2);
        BrKlatka pk = new BrKlatka(pg.bones.length), ak = new BrKlatka(ag.bones.length);
        java.util.List<String> aq = new java.util.ArrayList<>();
        BrNatywka.describe(ag.def).getAsJsonArray("queries").forEach(x -> aq.add(x.getAsString()));
        float[] pq = new float[pg.queries.length], qa = new float[ag.queries.length];
        int[] pqs = new int[pq.length], qas = new int[qa.length];
        java.util.Arrays.fill(pqs, -1);
        java.util.Arrays.fill(qas, -1);
        for (int i = 0; i < aq.size(); i++) {
            if (aq.get(i).startsWith("is_owner_identifier_any") && aq.get(i).contains("minecraft:player")) qa[i] = 1;
            if (aq.get(i).equals("is_attached")) qa[i] = 1;
        }
        float[] ctx = new float[ag.contexts.size()];
        int[] cstr = new int[ag.contexts.size()];
        java.util.Arrays.fill(cstr, -1);
        for (int i = 0; i < ag.contexts.size(); i++)
            if (ag.contexts.get(i).equals("item_slot")) cstr[i] = ag.strings.getOrDefault("main_hand", -2);
            else if (ag.contexts.get(i).equals("is_first_person") && System.getenv("KOPER_FP") != null) ctx[i] = 1;
        System.out.println(item + " attachable " + attId + " bones " + java.util.Arrays.toString(ag.bones) + " contexts " + ag.contexts);
        for (int f = 0; f < 30; f++) {
            assertTrue(BrNatywka.tick(ph, pq, pqs, new float[pg.contexts.size()], new int[pg.contexts.size()], 1 / 60f, pk, true));
            BrNatywka.owner(ah, ph);
            assertTrue(BrNatywka.tick(ah, qa, qas, ctx, cstr, 1 / 60f, ak, true));
            BrNatywka.parentSetup(ah, ph);
            if (f == 0 || f == 29) {
                StringBuilder sb = new StringBuilder("f" + f + ":");
                for (int b = 0; b < ag.bones.length; b++)
                    sb.append(String.format(" %s vis=%d rot=[%.0f %.0f %.0f] pos=[%.1f %.1f %.1f] scl=[%.2f %.2f %.2f]", ag.bones[b], ak.vis[b],
                        ak.local[b * 9], ak.local[b * 9 + 1], ak.local[b * 9 + 2], ak.local[b * 9 + 3], ak.local[b * 9 + 4], ak.local[b * 9 + 5],
                        ak.local[b * 9 + 6], ak.local[b * 9 + 7], ak.local[b * 9 + 8]));
                System.out.println(sb);
            }
        }
        BrNatywka.free(ph);
        BrNatywka.free(ah);
    }
}
