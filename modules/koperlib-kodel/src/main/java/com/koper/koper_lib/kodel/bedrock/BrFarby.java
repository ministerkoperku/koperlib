package com.koper.koper_lib.kodel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// bedrock .material files -> which java render type draws it. packs make their own materials
// ("foo:entity_alphatest") and stack defines/states on top, so walk the chain down to a stock one.
// the thing that matters most: plain "entity" is OPAQUE, bedrock never looks at the alpha there.
// drawing that as cutout ate half of every texture that stores a mask in alpha
public final class BrFarby {

    // MASK/MASK_SOLID: alpha is the change_color mask, drawn in two passes through BrMaska.
    // GLOW*: alpha is how much the pixel glows (emissive), same split, second pass full bright
    public enum Tryb { SOLID, CUTOUT, BLEND, ADD, HIDDEN, MASK, MASK_SOLID, GLOW, GLOW_SOLID, GLOW_ONLY;

        public boolean dzielony() { return this == MASK || this == MASK_SOLID || this == GLOW || this == GLOW_SOLID || this == GLOW_ONLY; }
        public boolean alfaTest() { return this == MASK || this == GLOW || this == GLOW_ONLY; }
    }

    private BrFarby() {}

    // stock bedrock materials, what they are by default. names only, the shaders are theirs
    private static void stock(String name, Set<String> defs, Set<String> states) {
        switch (name) {
            case "entity", "entity_static", "entity_change_color", "entity_multitexture", "entity_emissive",
                 "entity_multitexture_color_mask", "entity_multitexture_masked", "banner", "banner_pole",
                 "entity_lead_base", "entity_dissolve_layer0", "villager_v2_masked", "zombie_villager_v2_masked",
                 "entity_multitexture_masked_culling", "entity_change_color_one_sided" -> {}
            case "entity_nocull", "entity_change_color_nocull" -> states.add("DisableCulling");
            case "entity_alphatest", "entity_alphatest_change_color", "entity_alphatest_glint", "entity_alphatest_one_sided",
                 "entity_alphatest_multicolor_tint", "armor", "armor_leather", "armor_enchanted", "armor_leather_enchanted",
                 "entity_emissive_alpha", "entity_emissive_alpha_one_sided", "entity_custom", "item_in_hand",
                 "entity_multitexture_alpha_test", "entity_multitexture_alpha_test_color_mask", "iron_golem" -> defs.add("ALPHA_TEST");
            case "entity_alphablend", "entity_alphablend_nocolorentity_static", "entity_beam", "guardian_ghost",
                 "charged_creeper", "entity_loyalty_rope", "entity_dissolve_layer1", "wither_boss_armor" -> states.add("Blending");
            case "entity_beam_additive", "entity_glint", "armor_enchanted_glint" -> { states.add("Blending"); states.add("Additive"); }
            default -> {
                defs.add("NIEZNANY");
                // not a name we know: guess from it, bedrock kept its naming pretty honest
                if (name.contains("blend") || name.contains("translucent") || name.contains("ghost")) states.add("Blending");
                else if (name.contains("alpha") || name.contains("cutout") || name.contains("armor")) defs.add("ALPHA_TEST");
                else if (!name.startsWith("entity")) defs.add("ALPHA_TEST");
            }
        }
        // the ones that sample more than one texture: the render controller's 2nd, 3rd... texture is theirs
        if (name.contains("multitexture") || name.contains("villager_v2_masked") || name.startsWith("banner") || name.startsWith("horse"))
            defs.add("MULTITEXTURE");
        // the change_color family reads alpha as "where the mob's color goes", not as coverage
        if (name.contains("change_color") || name.contains("multicolor_tint")) defs.add("USE_COLOR_MASK");
        // and emissive ones as "how much it glows". an item with alpha 3 is an item that barely glows
        if (name.contains("emissive") || name.contains("bioluminescent")) defs.add("USE_EMISSIVE");
        // stock overlay passes (warden's glowing layers, spots): drawn on top of the mob, not over it
        if (name.endsWith("_layer") || name.contains("pulsating")) states.add("Overlay");
    }

    // does this material lay the render controller's later textures over the first. a plain
    // entity_alphatest does not: A&S lists the enchant glint as the armor's 2nd texture and it got
    // painted over every piece of armor and every sword, opaque, black and streaky
    public static boolean wielo(BrPaczki.Indeks idx, BrPaczki.Paczka own, String name) {
        if (name == null || name.isEmpty()) return false;
        Set<String> defs = new HashSet<>(), states = new HashSet<>();
        walk(idx, own, name.toLowerCase(Locale.ROOT), defs, states, new String[3], 0);
        // a stock name we have no file for (llama, horse_leather_armor, minecart): those are vanilla's own
        // multitexture materials, the mob's later textures are its carpet, markings, cargo
        return defs.contains("MULTITEXTURE") || defs.contains("MASKED_MULTITEXTURE") || defs.contains("MULTIPLICATIVE_TINT")
            || defs.contains("NIEZNANY");
    }

    public static Tryb tryb(BrPaczki.Indeks idx, BrPaczki.Paczka own, String name) {
        if (name == null || name.isEmpty()) return Tryb.CUTOUT;
        Set<String> defs = new HashSet<>(), states = new HashSet<>();
        String[] blend = new String[3];
        walk(idx, own, name.toLowerCase(Locale.ROOT), defs, states, blend, 0);
        if (states.contains("DisableColorWrite")) return Tryb.HIDDEN; // stencil/depth masks, nothing to see
        // a pass that only draws where the mob already is (depthFunc Equal): an overlay. its see-through
        // pixels must stay see-through, as an opaque pass it painted the warden black
        if (states.contains("Overlay") || "equal".equalsIgnoreCase(blend[2]))
            return defs.contains("USE_EMISSIVE") || "one".equalsIgnoreCase(blend[1]) ? Tryb.ADD : Tryb.BLEND;
        if (states.contains("Blending")) {
            // anything onto One adds light: One/One and SourceAlpha/One (glows, halos) both, black stays invisible
            boolean add = states.contains("Additive") || "one".equalsIgnoreCase(blend[1]);
            return add ? Tryb.ADD : Tryb.BLEND;
        }
        boolean alpha = defs.contains("ALPHA_TEST");
        if (defs.contains("USE_COLOR_MASK") || defs.contains("MULTI_COLOR_TINT")) return alpha ? Tryb.MASK : Tryb.MASK_SOLID;
        if (defs.contains("USE_ONLY_EMISSIVE")) return Tryb.GLOW_ONLY;
        if (defs.contains("USE_EMISSIVE")) return alpha ? Tryb.GLOW : Tryb.GLOW_SOLID;
        return alpha ? Tryb.CUTOUT : Tryb.SOLID;
    }

    private static void walk(BrPaczki.Indeks idx, BrPaczki.Paczka own, String name, Set<String> defs, Set<String> states,
                             String[] blend, int depth) {
        BrPaczki.Farba f = depth > 24 ? null : find(idx, own, name);
        if (f == null) { stock(name, defs, states); return; }
        // packs name their own ones honestly too ("entity_emissive_layer_alpha_test")
        if (name.contains("emissive")) defs.add("USE_EMISSIVE");
        if (f.parent() != null) walk(idx, own, f.parent(), defs, states, blend, depth + 1);
        else stock(name, defs, states);
        JsonObject o = f.body();
        apply(o, "+defines", defs, true);
        apply(o, "-defines", defs, false);
        apply(o, "+states", states, true);
        apply(o, "-states", states, false);
        // a plain "defines"/"states" replaces everything the parent had
        if (o.has("defines") && o.get("defines").isJsonArray()) { defs.clear(); apply(o, "defines", defs, true); }
        if (o.has("states") && o.get("states").isJsonArray()) { states.clear(); apply(o, "states", states, true); }
        if (o.has("blendSrc")) blend[0] = o.get("blendSrc").getAsString();
        if (o.has("blendDst")) blend[1] = o.get("blendDst").getAsString();
        if (o.has("depthFunc")) blend[2] = o.get("depthFunc").getAsString();
    }

    private static void apply(JsonObject o, String key, Set<String> into, boolean add) {
        JsonElement a = o.get(key);
        if (a == null || !a.isJsonArray()) return;
        for (JsonElement e : a.getAsJsonArray()) {
            if (!e.isJsonPrimitive()) continue;
            if (add) into.add(e.getAsString()); else into.remove(e.getAsString());
        }
    }

    private static BrPaczki.Farba find(BrPaczki.Indeks idx, BrPaczki.Paczka own, String name) {
        BrPaczki.Farba f = own.materials.get(name);
        if (f != null) return f;
        for (int i = idx.packs.size() - 1; i >= 0; i--) {
            f = idx.packs.get(i).materials.get(name);
            if (f != null) return f;
        }
        return null;
    }

    // "name:parent" keys, a few packs write "name:parent:grandparent"-ish junk, first parent wins
    static void czytaj(JsonObject file, Map<String, BrPaczki.Farba> into) {
        JsonObject all = BrPaczki.obj(file, "materials");
        if (all == null) return;
        for (var e : all.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            String[] bits = e.getKey().toLowerCase(Locale.ROOT).split(":");
            into.put(bits[0], new BrPaczki.Farba(bits.length > 1 ? bits[1] : null, e.getValue().getAsJsonObject()));
        }
    }

    public static RenderType typ(Tryb t, Identifier tex) {
        return switch (t) {
            case SOLID -> RenderTypes.entitySolid(tex);
            case BLEND -> com.koper.koper_lib.kender.KenderObustronnie.translucent(tex);
            case ADD -> RenderTypes.eyes(tex);
            default -> com.koper.koper_lib.kender.KenderObustronnie.cutout(tex);
        };
    }
}
