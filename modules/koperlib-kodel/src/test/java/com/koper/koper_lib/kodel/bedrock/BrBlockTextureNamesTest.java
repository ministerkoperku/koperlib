package com.koper.koper_lib.kodel.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrBlockTextureNamesTest {

    static boolean exists(String name) {
        return BrBlockTextureNamesTest.class.getResource("/assets/minecraft/textures/block/" + name + ".png") != null;
    }

    @Test
    void theOnesMowziesUses() {
        for (String[] p : new String[][] {{"brick", "bricks"}, {"stone_andesite", "andesite"}, {"hardened_clay_stained_white", "white_terracotta"},
                {"concrete_silver", "light_gray_concrete"}, {"planks_big_oak", "dark_oak_planks"}, {"stone_slab_top", "smooth_stone"},
                {"cobblestone_mossy", "mossy_cobblestone"}, {"end_bricks", "end_stone_bricks"}, {"prismarine_dark", "dark_prismarine"}}) {
            assertEquals(p[1], BrBlockTextureNames.toJava(p[0]), p[0]);
            assertTrue(exists(p[1]), p[1] + " is not a java texture");
        }
    }

    // KOPER_BEDROCK_VANILLA=<bedrock-samples resource_pack>: every rule has to land on a texture java has
    @Test
    void everyRuleLandsOnARealTexture() throws Exception {
        String v = System.getenv("KOPER_BEDROCK_VANILLA");
        if (v == null) return;
        List<String> wrong = new ArrayList<>();
        int hits = 0;
        try (var s = Files.list(Path.of(v, "textures", "blocks"))) {
            for (Path f : s.toList()) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".png") && !n.endsWith(".tga")) continue;
                n = n.substring(0, n.length() - 4);
                if (exists(n)) continue;
                String j = BrBlockTextureNames.toJava(n);
                if (j == null) continue;
                if (exists(j)) hits++;
                else wrong.add(n + " -> " + j);
            }
        }
        System.out.println("rules found java textures for " + hits + " bedrock names, wrong: " + wrong);
        assertTrue(wrong.isEmpty(), "too many rules point at nothing: " + wrong);
    }
}
