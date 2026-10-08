package com.koper.koper_lib.api.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// bedrock and java call a handful of the same mobs differently. packs say minecraft:villager_v2,
// the game has minecraft:villager; a resource pack that redraws the villager, a behavior pack that
// gives it properties and a script that checks typeId all speak bedrock. one table, both ways, for
// fullpack (scripts, behavior) and kodel (drawing). hello whoever finds a mob missing here, add it
public final class BedrockNazwy {

    // bedrock -> java. only the ones that differ
    private static final Map<String, String> DO_JAVY = new HashMap<>();
    // java -> bedrock, the name current bedrock uses first
    private static final Map<String, String> DO_BEDROCKA = new HashMap<>();

    static {
        para("villager_v2", "villager");
        para("zombie_villager_v2", "zombie_villager");
        para("evocation_illager", "evoker");
        para("evocation_fang", "evoker_fangs");
        para("zombie_pigman", "zombified_piglin");
        para("ender_crystal", "end_crystal");
        para("eye_of_ender_signal", "eye_of_ender");
        para("fireworks_rocket", "firework_rocket");
        para("fishing_hook", "fishing_bobber");
        para("thrown_trident", "trident");
        para("tropicalfish", "tropical_fish");
        para("xp_bottle", "experience_bottle");
        para("xp_orb", "experience_orb");
        para("wind_charge_projectile", "wind_charge");
        para("breeze_wind_charge_projectile", "breeze_wind_charge");
        para("boat", "oak_boat");
        para("chest_boat", "oak_chest_boat");
        // old bedrock names that still show up in packs, java side only
        DO_JAVY.put("villager", "villager");
        DO_JAVY.put("zombie_villager", "zombie_villager");
        DO_JAVY.put("wither_skull_dangerous", "wither_skull");
        DO_JAVY.put("snowman", "snow_golem");
    }

    private BedrockNazwy() {}

    private static void para(String bedrock, String java) {
        DO_JAVY.put(bedrock, java);
        DO_BEDROCKA.put(java, bedrock);
    }

    private static String sciezka(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        return low.startsWith("minecraft:") ? low.substring(10) : low.contains(":") ? null : low;
    }

    // a bedrock entity id -> the java one. not a vanilla id or already java: unchanged
    public static String doJavy(String bedrockId) {
        if (bedrockId == null) return null;
        String p = sciezka(bedrockId);
        if (p == null) return bedrockId;
        String j = DO_JAVY.get(p);
        return j == null ? (bedrockId.contains(":") ? bedrockId : "minecraft:" + bedrockId) : "minecraft:" + j;
    }

    // a java entity id -> what bedrock calls it (villager -> villager_v2). every wood's boat is bedrock's boat
    public static String doBedrocka(String javaId) {
        if (javaId == null) return null;
        String p = sciezka(javaId);
        if (p == null) return javaId;
        String b = DO_BEDROCKA.get(p);
        if (b == null && (p.endsWith("_chest_boat") || p.endsWith("_chest_raft"))) b = "chest_boat";
        else if (b == null && (p.endsWith("_boat") || p.endsWith("_raft"))) b = "boat";
        return "minecraft:" + (b == null ? p : b);
    }

    // every bedrock name a pack may use for this java entity, the current one first (villager_v2, villager)
    public static List<String> bedrockoweDla(String javaId) {
        String b = doBedrocka(javaId);
        String p = sciezka(javaId);
        if (p == null || b.equals(javaId)) return List.of(javaId);
        return List.of(b, "minecraft:" + p);
    }

    // ── items ────────────────────────────────────────────────────────────────
    // old bedrock item names packs still write (loot tables, recipes, give) that java calls differently.
    // keys lowercase, bedrock used camelCase for some (muttonRaw)
    private static final Map<String, String> PRZEDMIOTY = new HashMap<>();
    private static final String[] KOLORY = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] DREWNO = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] BARWNIKI = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye",
        "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye",
        "orange_dye", "bone_meal", "black_dye", "brown_dye", "blue_dye", "white_dye"};
    private static final String[] CZASZKI = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head",
        "creeper_head", "dragon_head", "piglin_head"};
    // bedrock block-ish items whose data value picks the colour: wool:14 -> red_wool
    private static final Map<String, String> KOLOROWE = Map.of("wool", "_wool", "carpet", "_carpet", "concrete", "_concrete",
        "concretepowder", "_concrete_powder", "concrete_powder", "_concrete_powder", "stained_glass", "_stained_glass",
        "stained_glass_pane", "_stained_glass_pane", "stained_hardened_clay", "_terracotta", "shulker_box", "_shulker_box", "bed", "_bed");
    private static final Map<String, String> DREWNIANE = Map.of("planks", "_planks", "sapling", "_sapling", "leaves", "_leaves",
        "wooden_slab", "_slab", "fence", "_fence", "log", "_log", "boat", "_boat");

    static {
        for (String d : new String[]{"13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward", "11",
            "wait", "pigstep", "otherside", "5", "relic", "creator", "creator_music_box", "precipice", "tears", "lava_chicken"})
            PRZEDMIOTY.put("record_" + d, "music_disc_" + d);
        String[][] pary = {
            {"muttonraw", "mutton"}, {"muttoncooked", "cooked_mutton"}, {"mutton_raw", "mutton"}, {"mutton_cooked", "cooked_mutton"},
            {"fish", "cod"}, {"cooked_fish", "cooked_cod"}, {"clownfish", "tropical_fish"}, {"appleenchanted", "enchanted_golden_apple"},
            {"horsearmorleather", "leather_horse_armor"}, {"horsearmoriron", "iron_horse_armor"},
            {"horsearmorgold", "golden_horse_armor"}, {"horsearmordiamond", "diamond_horse_armor"},
            {"carrotonastick", "carrot_on_a_stick"}, {"speckled_melon", "glistering_melon_slice"}, {"fireball", "fire_charge"},
            {"fireworks", "firework_rocket"}, {"fireworkscharge", "firework_star"}, {"netherbrick", "nether_brick"},
            {"emptymap", "map"}, {"empty_map", "map"}, {"turtle_shell_piece", "turtle_scute"}, {"scute", "turtle_scute"},
            {"totem", "totem_of_undying"}, {"chorus_fruit_popped", "popped_chorus_fruit"}, {"wooden_door", "oak_door"},
            {"reeds", "sugar_cane"}, {"netherstar", "nether_star"}, {"sign", "oak_sign"}, {"frame", "item_frame"},
            {"glow_frame", "glow_item_frame"}, {"web", "cobweb"}, {"tallgrass", "short_grass"}, {"grass", "grass_block"},
            {"yellow_flower", "dandelion"}, {"red_flower", "poppy"}, {"double_plant", "sunflower"}, {"waterlily", "lily_pad"},
            {"snow_layer", "snow"}, {"lit_pumpkin", "jack_o_lantern"}, {"melon_block", "melon"}, {"brick_block", "bricks"},
            {"stonebrick", "stone_bricks"}, {"mob_spawner", "spawner"}, {"noteblock", "note_block"}, {"stone_slab", "smooth_stone_slab"},
            {"fence_gate", "oak_fence_gate"}, {"trapdoor", "oak_trapdoor"}, {"wooden_button", "oak_button"},
            {"wooden_pressure_plate", "oak_pressure_plate"}, {"hardened_clay", "terracotta"}, {"quartz_ore", "nether_quartz_ore"},
            {"lit_redstone_lamp", "redstone_lamp"}, {"slime", "slime_block"}, {"undyed_shulker_box", "shulker_box"},
            {"silver_glazed_terracotta", "light_gray_glazed_terracotta"}, {"end_bricks", "end_stone_bricks"},
            {"magma", "magma_block"}, {"red_nether_brick", "red_nether_bricks"}, {"log2", "acacia_log"}, {"leaves2", "acacia_leaves"},
            {"dye", "ink_sac"}, {"skull", "skeleton_skull"}, {"banner", "white_banner"}, {"boat", "oak_boat"},
            {"log", "oak_log"}, {"planks", "oak_planks"}, {"sapling", "oak_sapling"}, {"leaves", "oak_leaves"}, {"fence", "oak_fence"},
            {"wool", "white_wool"}, {"carpet", "white_carpet"}, {"concrete", "white_concrete"}, {"concretepowder", "white_concrete_powder"},
            {"stained_glass", "white_stained_glass"}, {"stained_glass_pane", "white_stained_glass_pane"},
            {"stained_hardened_clay", "white_terracotta"}, {"bed", "red_bed"},
            {"chest_boat", "oak_chest_boat"}, {"golden_rail", "powered_rail"}, {"wooden_slab", "oak_slab"},
            {"deadbush", "dead_bush"}, {"chain", "iron_chain"}, {"grass_path", "dirt_path"}, {"darkoak_sign", "dark_oak_sign"},
            {"seagrass", "seagrass"}, {"invisible_bedrock", "barrier"}, {"stonecutter_block", "stonecutter"},
            {"cocoa", "cocoa_beans"}, {"nether_wart_item", "nether_wart"}, {"lodestonecompass", "compass"},
        };
        for (String[] p : pary) PRZEDMIOTY.put(p[0], p[1]);
    }

    // a bedrock item id, optionally with bedrock's data value ("minecraft:dye:4", "wool", "minecraft:muttonRaw")
    // -> the java item id. anything java already knows under the same name comes back as minecraft:<name>
    public static String przedmiotDoJavy(String bedrockId) {
        if (bedrockId == null) return null;
        String id = bedrockId.trim();
        int dane = -1;
        String[] p = id.split(":");
        String ns = "minecraft", nazwa = id;
        if (p.length == 3) { ns = p[0]; nazwa = p[1]; dane = liczba(p[2]); }
        else if (p.length == 2 && liczba(p[1]) >= 0 && !p[0].equals("minecraft")) { nazwa = p[0]; dane = liczba(p[1]); }
        else if (p.length == 2) { ns = p[0]; nazwa = p[1]; }
        if (!ns.equals("minecraft")) return ns + ":" + nazwa;
        String low = nazwa.toLowerCase(Locale.ROOT);
        if (dane >= 0) {
            if (KOLOROWE.containsKey(low) && dane < 16) return "minecraft:" + KOLORY[dane] + KOLOROWE.get(low);
            if (DREWNIANE.containsKey(low) && dane < 6) return "minecraft:" + DREWNO[dane] + DREWNIANE.get(low);
            if (low.equals("log2") && dane < 2) return "minecraft:" + DREWNO[4 + dane] + "_log";
            if (low.equals("leaves2") && dane < 2) return "minecraft:" + DREWNO[4 + dane] + "_leaves";
            if (low.equals("dye") && dane < BARWNIKI.length) return "minecraft:" + BARWNIKI[dane];
            if (low.equals("skull") && dane < CZASZKI.length) return "minecraft:" + CZASZKI[dane];
            if (low.equals("coal") && dane == 1) return "minecraft:charcoal";
            if (low.equals("golden_apple") && dane == 1) return "minecraft:enchanted_golden_apple";
        }
        String j = PRZEDMIOTY.get(low);
        return "minecraft:" + (j != null ? j : low);
    }

    private static int liczba(String s) {
        if (s.isEmpty() || s.length() > 4) return -1;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return -1;
        return Integer.parseInt(s);
    }

    // ── sounds ───────────────────────────────────────────────────────────────
    // bedrock's vanilla sound event -> java's. koperlib_core/bedrock_dzwieki.json pairs the two by the sound
    // files both games play for them (1092 of bedrock's names), RECZNE wins where that guessed wrong
    private static volatile Map<String, String> DZWIEKI;
    private static final Map<String, String> RECZNE = Map.ofEntries(
        Map.entry("random.orb", "entity.experience_orb.pickup"), Map.entry("random.levelup", "entity.player.levelup"),
        Map.entry("random.pop", "entity.item.pickup"), Map.entry("random.click", "ui.button.click"),
        Map.entry("mob.endermen.portal", "entity.enderman.teleport"), Map.entry("random.explode", "entity.generic.explode"),
        Map.entry("fire.ignite", "item.flintandsteel.use"), Map.entry("random.break", "entity.item.break"),
        Map.entry("random.chestopen", "block.chest.open"), Map.entry("random.chestclosed", "block.chest.close"),
        Map.entry("random.door_open", "block.wooden_door.open"), Map.entry("random.door_close", "block.wooden_door.close"),
        Map.entry("random.toast", "ui.toast.in"), Map.entry("random.totem", "item.totem.use"),
        Map.entry("ambient.weather.thunder", "entity.lightning_bolt.thunder"), Map.entry("random.bow", "entity.arrow.shoot"),
        Map.entry("random.burp", "entity.player.burp"), Map.entry("random.fizz", "block.fire.extinguish"),
        Map.entry("block.bell.hit", "block.bell.use"), Map.entry("mob.wolf.bark", "entity.wolf.ambient"),
        Map.entry("mob.slime.big", "entity.slime.squish"), Map.entry("mob.slime.small", "entity.slime.squish_small"));

    // a bedrock sound event name -> java's, null when bedrock's name is not one we know
    public static String dzwiekDoJavy(String bedrock) {
        if (bedrock == null) return null;
        String k = bedrock.toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) k = k.substring(10);
        String r = RECZNE.get(k);
        if (r != null) return r;
        Map<String, String> m = DZWIEKI;
        if (m == null) {
            m = new HashMap<>();
            try (var in = BedrockNazwy.class.getResourceAsStream("/koperlib_core/bedrock_dzwieki.json")) {
                if (in != null) {
                    var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (Exception ignored) {}
            DZWIEKI = m;
        }
        return m.get(k);
    }

    // same mob? a script comparing typeId with a filter in either naming
    public static boolean ten(String a, String b) {
        if (a == null || b == null) return false;
        return doJavy(a).equals(doJavy(b));
    }
}
