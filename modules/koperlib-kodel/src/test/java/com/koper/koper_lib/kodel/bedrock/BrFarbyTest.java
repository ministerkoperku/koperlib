package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// change_color keeps a tint mask in alpha. as plain cutout the sheep lost legs and face (alpha 3)
class BrFarbyTest {

    private static BrFarby.Tryb tryb(String materials, String name) {
        BrPaczki.Indeks idx = new BrPaczki.Indeks();
        BrPaczki.Paczka p = new BrPaczki.Paczka(Path.of("."), "test");
        if (materials != null) BrFarby.czytaj(JsonParser.parseString(materials).getAsJsonObject(), p.materials);
        idx.packs.add(p);
        return BrFarby.tryb(idx, p, name);
    }

    @Test
    void stockColorMasks() {
        assertEquals(BrFarby.Tryb.MASK, tryb(null, "entity_alphatest_change_color"));
        assertEquals(BrFarby.Tryb.MASK_SOLID, tryb(null, "entity_change_color"));
        assertEquals(BrFarby.Tryb.MASK, tryb(null, "entity_alphatest_multicolor_tint"));
        assertEquals(BrFarby.Tryb.CUTOUT, tryb(null, "entity_alphatest"));
        assertEquals(BrFarby.Tryb.SOLID, tryb(null, "entity"));
        // golem cracks are a mostly empty texture on the same material: as solid the golem went black
        assertEquals(BrFarby.Tryb.CUTOUT, tryb(null, "iron_golem"));
        assertEquals(BrFarby.Tryb.ADD, tryb(null, "warden_bioluminescent_layer"));
        assertEquals(BrFarby.Tryb.ADD, tryb("""
            {"materials": {"version": "1.0.0", "entity_emissive_layer_alpha_test:entity_nocull":
              {"+defines": ["USE_COLOR_MASK", "ALPHA_TEST", "USE_UV_ANIM"], "depthFunc": "Equal"}}}
            """, "entity_emissive_layer_alpha_test"));
        // alpha = glow amount: a held item at alpha 3 barely glows, it does not vanish
        assertEquals(BrFarby.Tryb.GLOW, tryb(null, "entity_emissive_alpha"));
        assertEquals(BrFarby.Tryb.GLOW_SOLID, tryb(null, "entity_emissive"));
    }

    @Test
    void packEmissiveChain() {
        String mats = """
            {"materials": {"version": "1.0.0",
              "vejvgev:entity_emissive_alpha": {},
              "arueojm:entity_emissive_alpha": {"+defines": ["USE_UV_ANIM"], "-defines": ["FANCY"]},
              "pumpkin:entity_emissive_alpha": {"+defines": ["USE_ONLY_EMISSIVE"]},
              "stbohaj:entity_emissive": {"+states": ["Blending"], "blendSrc": "One", "blendDst": "One"}}}
            """;
        assertEquals(BrFarby.Tryb.GLOW, tryb(mats, "arueojm"));
        assertEquals(BrFarby.Tryb.GLOW_ONLY, tryb(mats, "pumpkin"));
        assertEquals(BrFarby.Tryb.ADD, tryb(mats, "stbohaj"));
        // A&S torch halo: SourceAlpha onto One is still additive, as translucent its black showed
        assertEquals(BrFarby.Tryb.ADD, tryb(mats.replace("\"stbohaj:entity_emissive\"", "\"whthznb:stbohaj\": {\"blendSrc\": \"SourceAlpha\"}, \"stbohaj:entity_emissive\""), "whthznb"));
    }

    @Test
    void packMaterialsInheritTheMask() {
        String mats = """
            {"materials": {"version": "1.0.0",
              "sheep:entity_alphatest_change_color": {},
              "armor:entity_alphatest_change_color": {},
              "entity_change_color_culling:entity": {"+defines": ["USE_COLOR_MASK"]},
              "hidden:entity": {"+states": ["DisableColorWrite"], "+defines": ["USE_COLOR_MASK"]}}}
            """;
        assertEquals(BrFarby.Tryb.MASK, tryb(mats, "sheep"));
        assertEquals(BrFarby.Tryb.MASK, tryb(mats, "armor"));
        assertEquals(BrFarby.Tryb.MASK_SOLID, tryb(mats, "entity_change_color_culling"));
        assertEquals(BrFarby.Tryb.HIDDEN, tryb(mats, "hidden"));
    }
}
