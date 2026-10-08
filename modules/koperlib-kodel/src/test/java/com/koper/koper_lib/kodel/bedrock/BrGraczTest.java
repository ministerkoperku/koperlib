package com.koper.koper_lib.kodel.bedrock;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

// KOPER_BEDROCK_CONVERTED again. the pack's player standing, then walking like a real one does:
// the legs have to swing while walking or the pack's walk never kicked in (queries answer wrong)
class BrGraczTest {

    private static final String[] LEGS = {"rightleg", "leftleg", "rightarm", "leftarm"};

    @Test
    void playerWalks() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_CONVERTED");
        Assumptions.assumeTrue(env != null && Files.isDirectory(Path.of(env)) && BrNatywka.ready());
        BrPaczki.Paczka p = BrPaczki.czytaj(Path.of(env));
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        idx.packs.add(p);
        p.entities.keySet().forEach(id -> idx.entityOwner.put(id, p));
        String who = System.getenv().getOrDefault("KOPER_BEDROCK_ENTITY", "minecraft:player");
        BrTyp t = BrTyp.zbuduj(idx, p, who, p.entities.get(who));
        assertTrue(t != null, who);
        BrTyp.Geo g = t.geos.get(0);
        for (BrTyp.Geo x : t.geos) if (x.model == null) { g = x; break; }
        List<String> names = new ArrayList<>();
        BrNatywka.describe(g.def).getAsJsonArray("queries").forEach(x -> names.add(x.getAsString()));
        System.out.println(who + " geo " + g.key + ", bones " + java.util.Arrays.toString(g.bones));
        System.out.println("queries: " + names);

        long inst = BrNatywka.spawn(g.def, 5);
        BrKlatka k = new BrKlatka(g.bones.length);
        float[] q = new float[names.size()];
        int[] qs = new int[names.size()];
        java.util.Arrays.fill(qs, -1);
        Map<String, Float> now = new HashMap<>();
        float dt = 1 / 60f, life = 0, walked = 0;
        float swing = 0;
        float[] stoi = new float[g.bones.length], idzie = new float[g.bones.length];
        for (int f = 0; f < 600; f++) {
            boolean walking = f >= 200;
            float speed = walking ? 4.3f : 0f;
            life += dt;
            walked += speed * dt;
            now.clear();
            now.put("is_alive", 1f);
            now.put("health", 20f);
            now.put("max_health", 20f);
            now.put("is_on_ground", 1f);
            now.put("life_time", life);
            now.put("ground_speed", speed);
            now.put("modified_move_speed", walking ? 1f : 0f);
            now.put("walk_distance", walked);
            now.put("modified_distance_moved", walked);
            now.put("is_moving", walking ? 1f : 0f);
            now.put("is_local_player", 1f);
            now.put("is_first_person", 0f);
            now.put("movement_direction(0)", 0f);
            now.put("movement_direction(2)", walking ? 1f : 0f);
            now.put("is_first_person", 0f);
            now.put("is_in_ui", 0f);
            now.put("vertical_speed", Float.parseFloat(System.getenv().getOrDefault("KOPER_VSPEED", "0")));
            if (System.getenv("KOPER_OLDJAVA") != null) {
                // what the old java answers gave: delta movement speed, walkAnimation units
                now.put("ground_speed", walking ? 2.35f : 0f);
                now.put("vertical_speed", -1.57f);
                now.put("modified_move_speed", walking ? 0.86f : 0f);
                now.put("walk_distance", walked * 4f);
                now.put("modified_distance_moved", walked * 4f);
                // knownMovement().normalize() with gravity in it
                now.put("movement_direction(1)", walking ? -0.35f : -1f);
                now.put("movement_direction(2)", walking ? 0.93f : 0f);
            }
            for (int i = 0; i < q.length; i++) q[i] = now.getOrDefault(names.get(i), 0f);
            assertTrue(BrNatywka.tick(inst, q, qs, new float[g.contexts.size()], new int[g.contexts.size()], dt, k, true));
            for (int b = 0; b < g.bones.length; b++) {
                float r = Math.abs(k.local[b * 9]) + Math.abs(k.local[b * 9 + 1]) + Math.abs(k.local[b * 9 + 2]);
                if (walking) idzie[b] = Math.max(idzie[b], r); else stoi[b] = Math.max(stoi[b], r);
            }
            if (f % 50 == 49) {
                StringBuilder sb = new StringBuilder(String.format("f%03d %s", f, walking ? "walk " : "stand"));
                for (String leg : LEGS) {
                    int b = bone(g, leg);
                    if (b < 0) continue;
                    sb.append(String.format("  %s[%.1f %.1f %.1f]", leg, k.local[b * 9], k.local[b * 9 + 1], k.local[b * 9 + 2]));
                    if (walking && leg.equals("rightleg")) swing = Math.max(swing, Math.abs(k.local[b * 9]));
                }
                System.out.println(sb);
            }
        }
        StringBuilder ruch = new StringBuilder("bones moving when walking:");
        for (int b = 0; b < g.bones.length; b++)
            if (idzie[b] > stoi[b] + 5) ruch.append(String.format(" %s(%.0f>%.0f)", g.bones[b], idzie[b], stoi[b]));
        System.out.println(ruch);
        StringBuilder all = new StringBuilder("bones vis:");
        for (int b = 0; b < g.bones.length; b++) all.append(' ').append(g.bones[b]).append('=').append(k.vis[b]);
        System.out.println(all);
        int[] lay = new int[48];
        int n = BrNatywka.layers(inst, lay);
        StringBuilder ls = new StringBuilder("layers (rc, texture, geometry):");
        for (int i = 0; i < n; i++) ls.append(String.format(" [%d %d %d]", lay[i * 3], lay[i * 3 + 1], lay[i * 3 + 2]));
        System.out.println(ls + " textures " + t.textures + " info0=" + k.info[0] + " geo=" + g.key + " geos=" + t.geos.size());
        System.out.println("malowanie " + g.grupy);
        BrNatywka.free(inst);
        t.zwolnij();
        System.out.println("max right leg swing while walking: " + swing);
    }

    private static int bone(BrTyp.Geo g, String name) {
        for (int i = 0; i < g.bones.length; i++) if (g.bones[i].equalsIgnoreCase(name)) return i;
        return -1;
    }
}
