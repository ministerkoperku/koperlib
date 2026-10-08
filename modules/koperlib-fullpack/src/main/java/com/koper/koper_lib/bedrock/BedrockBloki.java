package com.koper.koper_lib.bedrock;

import com.koper.koper_lib.KoperLib;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// a bedrock block (name + states, from an mcstructure palette) -> the java block state. bedrock still
// carries a lot of pre-flattening names (stone_block_slab2 + stone_slab_type_2, wool + color) and its own
// state names (facing_direction 0..5, weirdo_direction, upside_down_bit). written from what the states
// mean in bedrock's block docs, not from anyone's mapping table. what it can not place says so once
final class BedrockBloki {

    private BedrockBloki() {}

    private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> KRZYCZAL = ConcurrentHashMap.newKeySet();

    static BlockState doJavy(String name, CompoundTag states) {
        String key = name + "|" + (states == null ? "" : states);
        BlockState got = CACHE.get(key);
        if (got != null) return got;
        got = przelicz(name == null ? "minecraft:air" : name.toLowerCase(Locale.ROOT), states);
        CACHE.put(key, got);
        return got;
    }

    // what a structure place could not do, once per thing, so a 600 structure pack doesn't drown the log
    static void krzyknij(String what) {
        if (KRZYCZAL.add(what)) KoperLib.LOGGER.error("[Bedrock/structure] {}", what);
    }

    private static BlockState przelicz(String name, CompoundTag raw) {
        Map<String, String> st = new LinkedHashMap<>();
        if (raw != null) for (String k : raw.keySet()) st.put(k, wartosc(raw.get(k)));
        Map<String, String> java = new LinkedHashMap<>();
        String n = name.contains(":") ? name : "minecraft:" + name;
        if (n.startsWith("minecraft:")) n = "minecraft:" + nazwa(n.substring(10), st, java);
        Block block = blok(n);
        if (block == null) {
            krzyknij("no java block for bedrock " + name + " " + st + ", placed as air");
            return Blocks.AIR.defaultBlockState();
        }
        BlockState s = block.defaultBlockState();
        for (var e : java.entrySet()) s = ustaw(s, e.getKey(), e.getValue(), name);
        for (var e : st.entrySet()) s = stan(s, e.getKey(), e.getValue(), name);
        return s;
    }

    private static Block blok(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        return BuiltInRegistries.BLOCK.getValue(rl);
    }

    private static String wartosc(Tag t) {
        if (t instanceof StringTag s) return s.value();
        if (t instanceof NumericTag nt) return String.valueOf(nt.box().longValue());
        return String.valueOf(t);
    }

    private static String kolor(String c) {
        return "silver".equals(c) ? "light_gray" : c;
    }

    // ── names: the old one-id-many-blocks ones get the state that picks the block folded in ─────

    private static final Map<String, String> PROSTE = new HashMap<>();
    static {
        String[][] a = {
            {"grass", "grass_block"}, {"grass_path", "dirt_path"}, {"brick_block", "bricks"}, {"hardened_clay", "terracotta"},
            {"deadbush", "dead_bush"}, {"darkoak_wall_sign", "dark_oak_wall_sign"}, {"darkoak_standing_sign", "dark_oak_sign"},
            {"standing_sign", "oak_sign"}, {"wall_sign", "oak_wall_sign"}, {"flowing_water", "water"}, {"flowing_lava", "lava"},
            {"snow_layer", "snow"}, {"snow", "snow_block"}, {"lit_pumpkin", "jack_o_lantern"}, {"web", "cobweb"},
            {"waterlily", "lily_pad"}, {"reeds", "sugar_cane"}, {"melon_block", "melon"}, {"noteblock", "note_block"},
            {"mob_spawner", "spawner"}, {"trapdoor", "oak_trapdoor"}, {"wooden_door", "oak_door"}, {"wooden_button", "oak_button"},
            {"wooden_pressure_plate", "oak_pressure_plate"}, {"golden_rail", "powered_rail"}, {"end_bricks", "end_stone_bricks"},
            {"end_brick_stairs", "end_stone_brick_stairs"}, {"nether_brick", "nether_bricks"}, {"red_nether_brick", "red_nether_bricks"},
            {"stonecutter_block", "stonecutter"}, {"magma", "magma_block"}, {"slime", "slime_block"}, {"invisible_bedrock", "barrier"},
            {"invisiblebedrock", "barrier"}, {"chain", "iron_chain"}, {"fence_gate", "oak_fence_gate"}, {"yellow_flower", "dandelion"},
            {"seagrass", "seagrass"}, {"quartz_ore", "nether_quartz_ore"}, {"prismarine_bricks_stairs", "prismarine_brick_stairs"},
            {"normal_stone_stairs", "stone_stairs"}, {"stone_stairs", "cobblestone_stairs"}, {"mossy_stone_brick_stairs", "mossy_stone_brick_stairs"},
            {"smooth_stone", "smooth_stone"}, {"lit_redstone_lamp", "redstone_lamp"}, {"unlit_redstone_torch", "redstone_torch"},
            {"powered_repeater", "repeater"}, {"unpowered_repeater", "repeater"}, {"powered_comparator", "comparator"},
            {"unpowered_comparator", "comparator"}, {"lit_furnace", "furnace"}, {"lit_smoker", "smoker"}, {"lit_blast_furnace", "blast_furnace"},
            {"lit_redstone_ore", "redstone_ore"}, {"lit_deepslate_redstone_ore", "deepslate_redstone_ore"}, {"daylight_detector_inverted", "daylight_detector"},
            {"frosted_ice", "frosted_ice"}, {"movingblock", "air"}, {"moving_block", "air"}, {"allow", "air"}, {"deny", "air"}, {"border_block", "air"},
            {"camera", "air"}, {"glowingobsidian", "obsidian"}, {"netherreactor", "air"}, {"info_update", "air"}, {"info_update2", "air"},
            {"reserved6", "air"}, {"stickypistonarmcollision", "air"}, {"piston_arm_collision", "piston_head"}, {"sticky_piston_arm_collision", "piston_head"},
            {"item_frame", "air"}, {"glow_frame", "air"}, {"frame", "air"}, {"light_block", "light"}, {"underwater_torch", "torch"},
            {"cave_vines_head_with_berries", "cave_vines"}, {"cave_vines_body_with_berries", "cave_vines_plant"},
            {"dirt_with_roots", "rooted_dirt"}, {"azalea_leaves_flowered", "flowering_azalea_leaves"}, {"carved_pumpkin", "carved_pumpkin"},
            {"concretepowder", "white_concrete_powder"}, {"tripwire", "tripwire"}, {"trip_wire", "tripwire"}, {"bubble_column", "bubble_column"},
            {"structure_void", "structure_void"}, {"cactus_flower", "cactus_flower"}, {"bed", "red_bed"}, {"skull", "skeleton_skull"},
            {"flower_pot", "flower_pot"}, {"cauldron", "cauldron"}, {"lava_cauldron", "lava_cauldron"}, {"podzol", "podzol"},
            {"mycelium", "mycelium"}, {"brown_mushroom_block", "brown_mushroom_block"}, {"red_mushroom_block", "red_mushroom_block"},
            {"chemistry_table", "air"}, {"element_0", "air"}, {"hard_glass", "glass"}, {"hard_glass_pane", "glass_pane"},
            {"pistonarmcollision", "air"}, {"stone_bricks", "stone_bricks"}, {"scaffolding", "scaffolding"},
            {"double_stone_slab4", "double_stone_block_slab4"}, {"double_stone_slab3", "double_stone_block_slab3"},
            {"double_stone_slab2", "double_stone_block_slab2"}, {"double_stone_slab", "double_stone_block_slab"},
            {"stone_slab4", "stone_block_slab4"}, {"stone_slab3", "stone_block_slab3"}, {"stone_slab2", "stone_block_slab2"},
            {"stone_slab", "stone_block_slab"}, {"wooden_slab", "wooden_slab"}, {"double_wooden_slab", "double_wooden_slab"},
        };
        for (String[] p : a) PROSTE.put(p[0], p[1]);
    }

    private static final String[] KOLOROWE = {"wool", "carpet", "concrete", "concrete_powder", "stained_glass", "stained_glass_pane",
        "stained_hardened_clay", "shulker_box", "glazed_terracotta"};

    private static String nazwa(String n, Map<String, String> st, Map<String, String> java) {
        n = PROSTE.getOrDefault(n, n);
        // color as a state
        for (String k : KOLOROWE) if (n.equals(k) && st.containsKey("color")) {
            String c = kolor(st.remove("color"));
            return k.equals("stained_hardened_clay") ? c + "_terracotta" : c + "_" + k;
        }
        if (n.equals("shulker_box")) return "shulker_box";
        // "_double_slab" are slabs that are full
        if (n.endsWith("_double_slab") && !n.startsWith("double_")) {
            java.put("type", "double");
            st.remove("minecraft:vertical_half");
            st.remove("top_slot_bit");
            return n.replace("_double_slab", "_slab").replace("brick_slab", "brick_slab");
        }
        switch (n) {
            case "stone" -> {
                String t = st.remove("stone_type");
                if (t != null) return switch (t) {
                    case "granite_smooth" -> "polished_granite";
                    case "diorite_smooth" -> "polished_diorite";
                    case "andesite_smooth" -> "polished_andesite";
                    default -> t;
                };
            }
            case "dirt" -> { if ("coarse".equals(st.remove("dirt_type"))) return "coarse_dirt"; }
            case "sand" -> { if ("red".equals(st.remove("sand_type"))) return "red_sand"; }
            case "sandstone", "red_sandstone" -> {
                String t = st.remove("sand_stone_type");
                String base = n;
                if (t != null) return switch (t) {
                    case "heiroglyphs" -> "chiseled_" + base;
                    case "cut" -> "cut_" + base;
                    case "smooth" -> "smooth_" + base;
                    default -> base;
                };
            }
            case "stonebrick", "stone_bricks" -> {
                String t = st.remove("stone_brick_type");
                if (t != null) return switch (t) {
                    case "mossy" -> "mossy_stone_bricks";
                    case "cracked" -> "cracked_stone_bricks";
                    case "chiseled" -> "chiseled_stone_bricks";
                    case "smooth" -> "stone_bricks";
                    default -> "stone_bricks";
                };
                return "stone_bricks";
            }
            case "quartz_block", "purpur_block" -> {
                String t = st.remove("chisel_type");
                String base = n.replace("_block", "");
                if (t != null) return switch (t) {
                    case "chiseled" -> n.equals("quartz_block") ? "chiseled_quartz_block" : n;
                    case "lines" -> base + "_pillar";
                    case "smooth" -> "smooth_" + base;
                    default -> n;
                };
            }
            case "planks", "log", "log2", "wood", "leaves", "leaves2", "sapling", "fence", "wooden_slab", "double_wooden_slab" -> {
                String t = first(st, "wood_type", "old_log_type", "new_log_type", "old_leaf_type", "new_leaf_type", "sapling_type");
                if (t == null) t = "oak";
                if (t.equals("big_oak")) t = "dark_oak";
                if (n.equals("double_wooden_slab")) { java.put("type", "double"); return t + "_slab"; }
                if (n.equals("wooden_slab")) return t + "_slab";
                String s = switch (n) { case "log", "log2" -> "_log"; case "leaves", "leaves2" -> "_leaves"; default -> "_" + n; };
                if (n.equals("wood") && "1".equals(st.remove("stripped_bit"))) return "stripped_" + t + "_wood";
                return t + s;
            }
            case "double_plant" -> {
                String t = st.remove("double_plant_type");
                return switch (t == null ? "" : t) {
                    case "syringa" -> "lilac";
                    case "grass" -> "tall_grass";
                    case "fern" -> "large_fern";
                    case "rose" -> "rose_bush";
                    case "paeonia" -> "peony";
                    default -> "sunflower";
                };
            }
            case "tallgrass" -> { return "fern".equals(st.remove("tall_grass_type")) ? "fern" : "short_grass"; }
            case "red_flower" -> {
                String t = st.remove("flower_type");
                return switch (t == null ? "" : t) {
                    case "orchid" -> "blue_orchid";
                    case "allium" -> "allium";
                    case "houstonia" -> "azure_bluet";
                    case "tulip_red" -> "red_tulip";
                    case "tulip_orange" -> "orange_tulip";
                    case "tulip_white" -> "white_tulip";
                    case "tulip_pink" -> "pink_tulip";
                    case "oxeye" -> "oxeye_daisy";
                    case "cornflower" -> "cornflower";
                    case "lily_of_the_valley" -> "lily_of_the_valley";
                    default -> "poppy";
                };
            }
            case "stone_block_slab", "double_stone_block_slab", "stone_block_slab2", "double_stone_block_slab2",
                 "stone_block_slab3", "double_stone_block_slab3", "stone_block_slab4", "double_stone_block_slab4" -> {
                if (n.startsWith("double_")) java.put("type", "double");
                String t = first(st, "stone_slab_type", "stone_slab_type_2", "stone_slab_type_3", "stone_slab_type_4");
                return slabTyp(t == null ? "stone" : t, n) + "_slab";
            }
            case "cobblestone_wall" -> {
                String t = st.remove("wall_block_type");
                if (t == null) return n;
                return switch (t) {
                    case "end_brick" -> "end_stone_brick_wall";
                    case "mossy_stone_brick" -> "mossy_stone_brick_wall";
                    default -> t + "_wall";
                };
            }
            case "coral", "coral_block", "coral_fan", "coral_fan_dead" -> {
                String c = koral(st.remove("coral_color"));
                boolean dead = "1".equals(st.remove("dead_bit")) || n.equals("coral_fan_dead");
                String part = n.equals("coral") ? "_coral" : n.equals("coral_block") ? "_coral_block" : "_coral_fan";
                st.remove("coral_fan_direction");
                return (dead ? "dead_" : "") + c + part;
            }
            case "coral_fan_hang", "coral_fan_hang2", "coral_fan_hang3" -> {
                boolean bit = "1".equals(st.remove("coral_hang_type_bit"));
                boolean dead = "1".equals(st.remove("dead_bit"));
                String c = switch (n) {
                    case "coral_fan_hang" -> bit ? "brain" : "tube";
                    case "coral_fan_hang2" -> bit ? "fire" : "bubble";
                    default -> "horn";
                };
                String d = st.remove("coral_direction");
                if (d != null) java.put("facing", switch (d) { case "0" -> "west"; case "1" -> "east"; case "2" -> "north"; default -> "south"; });
                return (dead ? "dead_" : "") + c + "_coral_wall_fan";
            }
            case "anvil" -> {
                String d = st.remove("damage");
                if ("slightly_damaged".equals(d)) return "chipped_anvil";
                if ("very_damaged".equals(d)) return "damaged_anvil";
            }
            case "monster_egg" -> {
                String t = st.remove("monster_egg_stone_type");
                return switch (t == null ? "" : t) {
                    case "cobblestone" -> "infested_cobblestone";
                    case "stone_brick" -> "infested_stone_bricks";
                    case "mossy_stone_brick" -> "infested_mossy_stone_bricks";
                    case "cracked_stone_brick" -> "infested_cracked_stone_bricks";
                    case "chiseled_stone_brick" -> "infested_chiseled_stone_bricks";
                    default -> "infested_stone";
                };
            }
            case "seagrass" -> {
                String t = st.remove("sea_grass_type");
                if ("double_bot".equals(t)) { java.put("half", "lower"); return "tall_seagrass"; }
                if ("double_top".equals(t)) { java.put("half", "upper"); return "tall_seagrass"; }
                return "seagrass";
            }
            case "torch", "redstone_torch", "soul_torch", "copper_torch" -> {
                String f = st.remove("torch_facing_direction");
                if (f != null && !f.equals("top") && !f.equals("unknown")) {
                    java.put("facing", f);
                    return n.replace("torch", "wall_torch");
                }
            }
            case "cauldron" -> {
                String liquid = st.remove("cauldron_liquid");
                String lvl = st.remove("fill_level");
                int f = lvl == null ? 0 : Integer.parseInt(lvl);
                if (f > 0) {
                    if ("lava".equals(liquid)) return "lava_cauldron";
                    java.put("level", String.valueOf(Math.max(1, Math.min(3, (f + 1) / 2))));
                    return "powder_snow".equals(liquid) ? "powder_snow_cauldron" : "water_cauldron";
                }
            }
            case "brown_mushroom_block", "red_mushroom_block" -> {
                String bits = st.remove("huge_mushroom_bits");
                int b = bits == null ? 14 : Integer.parseInt(bits);
                if (b == 10 || b == 15) return "mushroom_stem";
                grzyb(b, java);
            }
            case "bamboo" -> {
                String leaves = st.remove("bamboo_leaf_size");
                if (leaves != null) java.put("leaves", switch (leaves) { case "small_leaves" -> "small"; case "large_leaves" -> "large"; default -> "none"; });
                String th = st.remove("bamboo_stalk_thickness");
                if (th != null) java.put("age", "thick".equals(th) ? "1" : "0");
            }
            case "big_dripleaf" -> {
                if ("0".equals(st.remove("big_dripleaf_head"))) { st.remove("big_dripleaf_tilt"); return "big_dripleaf_stem"; }
            }
            case "lever" -> {
                String d = st.remove("lever_direction");
                if (d != null) {
                    switch (d) {
                        case "down_east_west" -> { java.put("face", "ceiling"); java.put("facing", "east"); }
                        case "down_north_south" -> { java.put("face", "ceiling"); java.put("facing", "north"); }
                        case "up_east_west" -> { java.put("face", "floor"); java.put("facing", "east"); }
                        case "up_north_south" -> { java.put("face", "floor"); java.put("facing", "north"); }
                        default -> { java.put("face", "wall"); java.put("facing", d); }
                    }
                }
            }
            default -> {}
        }
        // "*_standing_sign" and "*_wall_sign" with bedrock's own wood names
        if (n.endsWith("_standing_sign")) return n.replace("_standing_sign", "_sign");
        return n;
    }

    private static String first(Map<String, String> st, String... keys) {
        for (String k : keys) {
            String v = st.remove(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String slabTyp(String t, String which) {
        return switch (t) {
            case "smooth_stone", "stone" -> which.contains("4") ? "stone" : "smooth_stone";
            case "wood" -> "oak";
            case "prismarine_rough" -> "prismarine";
            case "prismarine_dark" -> "dark_prismarine";
            case "prismarine_brick" -> "prismarine_brick";
            case "end_stone_brick" -> "end_stone_brick";
            case "nether_brick" -> "nether_brick";
            case "red_nether_brick" -> "red_nether_brick";
            case "mossy_stone_brick" -> "mossy_stone_brick";
            default -> t;
        };
    }

    private static String koral(String c) {
        return switch (c == null ? "" : c) {
            case "pink" -> "brain";
            case "purple" -> "bubble";
            case "red" -> "fire";
            case "yellow" -> "horn";
            default -> "tube";
        };
    }

    // huge_mushroom_bits 0..14: which faces show the cap. 0 pores everywhere, 14 cap everywhere,
    // 1..9 the corner/edge/top pieces of a cap
    private static void grzyb(int b, Map<String, String> j) {
        boolean all = b == 14;
        boolean up = all || (b >= 1 && b <= 9);
        boolean north = all || b == 1 || b == 2 || b == 3;
        boolean south = all || b == 7 || b == 8 || b == 9;
        boolean west = all || b == 1 || b == 4 || b == 7;
        boolean east = all || b == 3 || b == 6 || b == 9;
        j.put("up", String.valueOf(up));
        j.put("down", String.valueOf(all));
        j.put("north", String.valueOf(north));
        j.put("south", String.valueOf(south));
        j.put("west", String.valueOf(west));
        j.put("east", String.valueOf(east));
    }

    // ── states that are left: bedrock name -> java property ──────────────────

    private static final String[] KIERUNEK6 = {"down", "up", "north", "south", "west", "east"};
    private static final String[] JAVA4 = {"south", "west", "north", "east"};

    private static BlockState stan(BlockState s, String k, String v, String name) {
        String n = name.substring(name.indexOf(':') + 1);
        switch (k) {
            case "pillar_axis" -> { return ustaw(s, "axis", v, name); }
            case "facing_direction" -> {
                int i = Integer.parseInt(v);
                return ustaw(s, "facing", i >= 0 && i < 6 ? KIERUNEK6[i] : "north", name);
            }
            case "minecraft:cardinal_direction", "minecraft:facing_direction", "minecraft:block_face" -> { return ustaw(s, "facing", v, name); }
            case "weirdo_direction" -> {
                String f = switch (v) { case "0" -> "east"; case "1" -> "west"; case "2" -> "south"; default -> "north"; };
                return ustaw(s, "facing", f, name);
            }
            case "direction" -> {
                int i = Integer.parseInt(v) & 3;
                String f;
                if (n.endsWith("_door") || n.equals("iron_door")) f = new String[] {"east", "south", "west", "north"}[i];
                else if (n.endsWith("trapdoor")) f = new String[] {"east", "west", "south", "north"}[i];
                else f = JAVA4[i];
                return ustaw(s, "facing", f, name);
            }
            case "upside_down_bit" -> { return ustaw(s, "half", "1".equals(v) ? "top" : "bottom", name); }
            case "minecraft:vertical_half" -> { return prop(s, "type") != null ? ustaw(s, "type", v, name) : ustaw(s, "half", v.equals("top") ? "top" : "bottom", name); }
            case "top_slot_bit" -> { return ustaw(s, "type", "1".equals(v) ? "top" : "bottom", name); }
            case "upper_block_bit" -> { return ustaw(s, "half", "1".equals(v) ? "upper" : "lower", name); }
            case "open_bit" -> { return n.equals("lever") ? ustaw(s, "powered", bool(v), name) : ustaw(s, "open", bool(v), name); }
            case "door_hinge_bit" -> { return ustaw(s, "hinge", "1".equals(v) ? "right" : "left", name); }
            case "persistent_bit" -> { return ustaw(s, "persistent", bool(v), name); }
            case "update_bit", "deprecated", "natural", "allow_underwater_bit", "coral_fan_direction", "stability_check",
                 "age_bit", "explode_bit", "covered_bit", "rotation", "structure_block_type", "brushed_progress", "suspended_bit",
                 "honey_level_unused", "infiniburn_bit", "triggered_bit_unused" -> { return s; }
            case "age", "growth", "kelp_age", "twisting_vines_age", "weeping_vines_age", "growing_plant_age" -> { return ustaw(s, "age", v, name); }
            case "lit" -> { return ustaw(s, "lit", bool(v), name); }
            case "extinguished" -> { return ustaw(s, "lit", "1".equals(v) ? "false" : "true", name); }
            case "powered_bit", "button_pressed_bit", "rail_data_bit", "output_lit_bit" -> { return ustaw(s, "powered", bool(v), name); }
            case "attached_bit" -> { return ustaw(s, "attached", bool(v), name); }
            case "disarmed_bit" -> { return ustaw(s, "disarmed", bool(v), name); }
            case "in_wall_bit" -> { return ustaw(s, "in_wall", bool(v), name); }
            case "head_piece_bit" -> { return ustaw(s, "part", "1".equals(v) ? "head" : "foot", name); }
            case "occupied_bit" -> { return ustaw(s, "occupied", bool(v), name); }
            case "candles" -> { return ustaw(s, "candles", String.valueOf(Integer.parseInt(v) + 1), name); }
            case "wall_post_bit" -> { return ustaw(s, "up", bool(v), name); }
            case "wall_connection_type_east", "wall_connection_type_west", "wall_connection_type_north", "wall_connection_type_south",
                 "pale_moss_carpet_side_east", "pale_moss_carpet_side_west", "pale_moss_carpet_side_north", "pale_moss_carpet_side_south" -> {
                String side = k.substring(k.lastIndexOf('_') + 1);
                return ustaw(s, side, "short".equals(v) ? "low" : v, name);
            }
            case "multi_face_direction_bits" -> {
                int b = Integer.parseInt(v);
                String[] f = {"down", "up", "south", "west", "north", "east"};
                for (int i = 0; i < 6; i++) s = ustaw(s, f[i], String.valueOf((b >> i & 1) == 1), name);
                return s;
            }
            case "vine_direction_bits" -> {
                int b = Integer.parseInt(v);
                s = ustaw(s, "south", String.valueOf((b & 1) != 0), name);
                s = ustaw(s, "west", String.valueOf((b & 2) != 0), name);
                s = ustaw(s, "north", String.valueOf((b & 4) != 0), name);
                return ustaw(s, "east", String.valueOf((b & 8) != 0), name);
            }
            case "liquid_depth" -> { return ustaw(s, "level", v, name); }
            case "redstone_signal" -> { return ustaw(s, "power", v, name); }
            case "rail_direction" -> {
                String[] shapes = {"north_south", "east_west", "ascending_east", "ascending_west", "ascending_north", "ascending_south",
                    "south_east", "south_west", "north_west", "north_east"};
                int i = Integer.parseInt(v);
                return ustaw(s, "shape", i >= 0 && i < shapes.length ? shapes[i] : "north_south", name);
            }
            case "ground_sign_direction" -> { return ustaw(s, "rotation", v, name); }
            case "dripstone_thickness" -> { return ustaw(s, "thickness", "merge".equals(v) ? "tip_merge" : v, name); }
            case "hanging" -> {
                if (n.equals("pointed_dripstone")) return ustaw(s, "vertical_direction", "1".equals(v) ? "down" : "up", name);
                return ustaw(s, "hanging", bool(v), name);
            }
            case "cluster_count" -> { return ustaw(s, "pickles", String.valueOf(Integer.parseInt(v) + 1), name); }
            case "dead_bit" -> { return ustaw(s, "waterlogged", "1".equals(v) ? "false" : "true", name); }
            case "attachment" -> {
                String a = switch (v) { case "hanging" -> "ceiling"; case "side" -> "single_wall"; case "multiple" -> "double_wall"; default -> "floor"; };
                return ustaw(s, "attachment", a, name);
            }
            case "height" -> { return ustaw(s, "layers", String.valueOf(Integer.parseInt(v) + 1), name); }
            case "moisturized_amount" -> { return ustaw(s, "moisture", v, name); }
            case "bite_counter" -> { return ustaw(s, "bites", v, name); }
            case "composter_fill_level" -> { return ustaw(s, "level", v, name); }
            case "books_stored" -> {
                int b = Integer.parseInt(v);
                for (int i = 0; i < 6; i++) s = ustaw(s, "slot_" + i + "_occupied", String.valueOf((b >> i & 1) == 1), name);
                return s;
            }
            case "brewing_stand_slot_a_bit" -> { return ustaw(s, "has_bottle_0", bool(v), name); }
            case "brewing_stand_slot_b_bit" -> { return ustaw(s, "has_bottle_1", bool(v), name); }
            case "brewing_stand_slot_c_bit" -> { return ustaw(s, "has_bottle_2", bool(v), name); }
            case "repeater_delay" -> { return ustaw(s, "delay", String.valueOf(Integer.parseInt(v) + 1), name); }
            case "output_subtract_bit" -> { return ustaw(s, "mode", "1".equals(v) ? "subtract" : "compare", name); }
            case "conditional_bit" -> { return ustaw(s, "conditional", bool(v), name); }
            case "stability" -> { return ustaw(s, "distance", v, name); }
            case "big_dripleaf_tilt" -> { return ustaw(s, "tilt", "none".equals(v) ? "none" : v.replace("_tilt", ""), name); }
            case "sculk_sensor_phase" -> { return ustaw(s, "sculk_sensor_phase", switch (v) { case "1" -> "active"; case "2" -> "cooldown"; default -> "inactive"; }, name); }
            case "drag_down" -> { return ustaw(s, "drag", bool(v), name); }
            case "toggle_bit" -> { return ustaw(s, "powered", bool(v), name); }
            case "trial_spawner_state" -> { return s; }
            case "vault_state" -> { return ustaw(s, "vault_state", v, name); }
            case "ominous" -> { return ustaw(s, "ominous", bool(v), name); }
            case "creaking_heart_state" -> { return ustaw(s, "creaking_heart_state", v, name); }
            case "tip" -> { return s; }
            case "block_light_level" -> { return ustaw(s, "level", v, name); }
            case "honey_level" -> { return ustaw(s, "honey_level", v, name); }
            case "damage" -> { return s; }
            default -> {}
        }
        // an addon's own block ("hfrlc:growth_stage" on hfrlc:mushroom): the converted block carries the
        // same state names, without the namespace
        String bare = k.contains(":") ? k.substring(k.indexOf(':') + 1) : k;
        if (prop(s, bare) != null) return ustaw(s, bare, v, name);
        krzyknij("bedrock state " + k + "=" + v + " on " + name + " has no java twin, left as default");
        return s;
    }

    private static String bool(String v) {
        return "1".equals(v) || "true".equals(v) ? "true" : "false";
    }

    private static Property<?> prop(BlockState s, String name) {
        for (Property<?> p : s.getProperties()) if (p.getName().equals(name)) return p;
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState ustaw(BlockState s, String key, String value, String bedrockName) {
        Property p = prop(s, key);
        if (p == null) return s; // the java block simply has no such property (a slab's "facing"), nothing lost
        var parsed = p.getValue(value);
        if (parsed.isEmpty()) {
            krzyknij("bedrock " + bedrockName + ": java " + BuiltInRegistries.BLOCK.getKey(s.getBlock()) + " has no " + key + "=" + value);
            return s;
        }
        return s.setValue(p, (Comparable) parsed.get());
    }
}
