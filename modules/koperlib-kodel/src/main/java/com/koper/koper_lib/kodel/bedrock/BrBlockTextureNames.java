package com.koper.koper_lib.kodel.bedrock;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// bedrock's names for vanilla block textures -> java's (textures/blocks/brick is java's block/bricks).
// packs lean on bedrock's own block textures (mowzie's geomancy blocks draw stone, clay, concrete...),
// the pack doesn't carry them and java's have other names. rules first, then the odd ones by hand
final class BrBlockTextureNames {

    private BrBlockTextureNames() {}

    private static final Map<String, String> ODD_ONES = Map.ofEntries(
        Map.entry("brick", "bricks"), Map.entry("nether_brick", "nether_bricks"), Map.entry("red_nether_brick", "red_nether_bricks"),
        Map.entry("end_bricks", "end_stone_bricks"), Map.entry("cobblestone_mossy", "mossy_cobblestone"),
        Map.entry("stonebrick", "stone_bricks"), Map.entry("stonebrick_mossy", "mossy_stone_bricks"),
        Map.entry("stonebrick_cracked", "cracked_stone_bricks"), Map.entry("stonebrick_carved", "chiseled_stone_bricks"),
        Map.entry("stone_slab_top", "smooth_stone"), Map.entry("stone_slab_side", "smooth_stone_slab_side"),
        Map.entry("sandstone_normal", "sandstone"), Map.entry("sandstone_carved", "chiseled_sandstone"), Map.entry("sandstone_smooth", "cut_sandstone"),
        Map.entry("red_sandstone_normal", "red_sandstone"), Map.entry("red_sandstone_carved", "chiseled_red_sandstone"),
        Map.entry("red_sandstone_smooth", "cut_red_sandstone"),
        Map.entry("prismarine_rough", "prismarine"), Map.entry("prismarine_dark", "dark_prismarine"),
        Map.entry("ice_packed", "packed_ice"), Map.entry("hardened_clay", "terracotta"),
        Map.entry("grass_top", "grass_block_top"), Map.entry("grass_side", "grass_block_side"), Map.entry("grass_side_snowed", "grass_block_snow"),
        Map.entry("grass_carried", "grass_block_top"), Map.entry("dirt_podzol_top", "podzol_top"), Map.entry("dirt_podzol_side", "podzol_side"),
        Map.entry("grass_path_top", "dirt_path_top"), Map.entry("grass_path_side", "dirt_path_side"), Map.entry("dirt_with_roots", "rooted_dirt"),
        Map.entry("mob_spawner", "spawner"), Map.entry("noteblock", "note_block"),
        Map.entry("quartz_block_chiseled", "chiseled_quartz_block"), Map.entry("quartz_block_lines", "quartz_pillar_side"),
        Map.entry("quartz_block_lines_top", "quartz_pillar_top"), Map.entry("quartz_ore", "nether_quartz_ore"),
        Map.entry("mushroom_block_skin_brown", "brown_mushroom_block"), Map.entry("mushroom_block_skin_red", "red_mushroom_block"),
        Map.entry("mushroom_block_skin_stem", "mushroom_stem"), Map.entry("mushroom_block_inside", "mushroom_block_inside"),
        Map.entry("mushroom_brown", "brown_mushroom"), Map.entry("mushroom_red", "red_mushroom"),
        Map.entry("deadbush", "dead_bush"), Map.entry("reeds", "sugar_cane"), Map.entry("web", "cobweb"), Map.entry("sponge_wet", "wet_sponge"),
        Map.entry("farmland_wet", "farmland_moist"), Map.entry("farmland_dry", "farmland"), Map.entry("furnace_front_off", "furnace_front"),
        Map.entry("blast_furnace_front_off", "blast_furnace_front"), Map.entry("honeycomb", "honeycomb_block"),
        Map.entry("crimson_nylium_top", "crimson_nylium"), Map.entry("warped_nylium_top", "warped_nylium"),
        Map.entry("trapdoor", "oak_trapdoor"), Map.entry("iron_trapdoor", "iron_trapdoor"), Map.entry("waterlily", "lily_pad"),
        Map.entry("carried_waterlily", "lily_pad"), Map.entry("tallgrass", "short_grass"), Map.entry("fern_carried", "fern"),
        Map.entry("flower_rose", "poppy"), Map.entry("flower_dandelion", "dandelion"), Map.entry("flower_houstonia", "azure_bluet"),
        Map.entry("flower_paeonia", "peony_top"), Map.entry("flower_rose_blue", "blue_orchid"), Map.entry("flower_oxeye_daisy", "oxeye_daisy"),
        Map.entry("anvil_base", "anvil"), Map.entry("anvil_top_damaged_0", "anvil_top"), Map.entry("anvil_top_damaged_1", "chipped_anvil_top"),
        Map.entry("anvil_top_damaged_2", "damaged_anvil_top"), Map.entry("endframe_top", "end_portal_frame_top"),
        Map.entry("endframe_side", "end_portal_frame_side"), Map.entry("endframe_eye", "end_portal_frame_eye"),
        Map.entry("comparator_off", "comparator"), Map.entry("compost", "composter_compost"), Map.entry("compost_ready", "composter_ready"),
        Map.entry("melon_stem_disconnected", "melon_stem"), Map.entry("melon_stem_connected", "attached_melon_stem"),
        Map.entry("lava_still_normal", "lava_still"), Map.entry("lava_flow_normal", "lava_flow"), Map.entry("cauldron_water", "water_still"),
        Map.entry("command_block", "command_block_front"), Map.entry("chest_front", "oak_planks"), Map.entry("itemframe_background", "item_frame"));

    private static final Pattern COLORED = Pattern.compile("^(concrete_powder|concrete|glass_pane_top|glass|glazed_terracotta|hardened_clay_stained|wool_colored|shulker_top|stained_glass)_([a-z_]+)$");
    private static final Pattern WOOD = Pattern.compile("^(planks|log|leaves|door|sapling)_([a-z_]+?)(_top|_lower|_upper|_opaque|_carried)?$");
    private static final Pattern STONE = Pattern.compile("^stone_(andesite|granite|diorite)(_smooth)?$");
    private static final Pattern STAGE = Pattern.compile("^(beetroots|carrots|potatoes|nether_wart|cocoa)_stage_(\\d)$");
    private static final Pattern FLOWER = Pattern.compile("^flower_([a-z_]+)$");
    private static final Pattern CORAL = Pattern.compile("^coral(_fan|_plant)?_(blue|pink|purple|red|yellow)(_dead)?$");

    // java's block texture name, or null when there is no rule (the caller then tries the name as it is)
    static String toJava(String b) {
        // _mers / _normal / _heightmap are bedrock's pbr maps, not colour, java has nothing like them
        if (b.endsWith("_mers") || b.endsWith("_mer") || b.endsWith("_heightmap") || b.endsWith("_normal_map")) return null;
        String d = ODD_ONES.get(b);
        if (d != null) return d;
        Matcher m = COLORED.matcher(b);
        if (m.matches()) {
            String c = color(m.group(2));
            return switch (m.group(1)) {
                case "concrete" -> c + "_concrete";
                case "concrete_powder" -> c + "_concrete_powder";
                case "glass", "stained_glass" -> c + "_stained_glass";
                case "glass_pane_top" -> c + "_stained_glass_pane_top";
                case "glazed_terracotta" -> c + "_glazed_terracotta";
                case "hardened_clay_stained" -> c + "_terracotta";
                case "wool_colored" -> c + "_wool";
                case "shulker_top" -> c.equals("undyed") ? "shulker_box" : c + "_shulker_box";
                default -> null;
            };
        }
        m = STONE.matcher(b);
        if (m.matches()) return (m.group(2) != null ? "polished_" : "") + m.group(1);
        m = STAGE.matcher(b);
        if (m.matches()) return m.group(1) + "_stage" + m.group(2);
        m = CORAL.matcher(b);
        if (m.matches()) {
            String kind = switch (m.group(2)) { case "blue" -> "tube"; case "pink" -> "brain"; case "purple" -> "bubble"; case "red" -> "fire"; default -> "horn"; };
            String what = m.group(1) == null ? "_coral_block" : m.group(1).equals("_fan") ? "_coral_fan" : "_coral";
            return (m.group(3) != null ? "dead_" : "") + kind + what;
        }
        m = WOOD.matcher(b);
        if (m.matches()) {
            String w = wood(m.group(2));
            String suf = m.group(3) == null ? "" : m.group(3);
            return switch (m.group(1)) {
                case "planks" -> w + "_planks";
                case "log" -> w + "_log" + (suf.equals("_top") ? "_top" : "");
                case "leaves" -> w + "_leaves";
                case "sapling" -> w + "_sapling";
                case "door" -> (w.equals("wood") ? "oak" : w) + (suf.equals("_upper") ? "_door_top" : "_door_bottom");
                default -> null;
            };
        }
        m = FLOWER.matcher(b);
        if (m.matches()) {
            String f = m.group(1);
            return f.startsWith("tulip_") ? f.substring("tulip_".length()) + "_tulip" : f;
        }
        return null;
    }

    private static String color(String c) {
        return c.equals("silver") ? "light_gray" : c;
    }

    private static String wood(String w) {
        return switch (w) {
            case "big_oak", "roofed_oak" -> "dark_oak";
            case "wood" -> "oak";
            default -> w;
        };
    }
}
