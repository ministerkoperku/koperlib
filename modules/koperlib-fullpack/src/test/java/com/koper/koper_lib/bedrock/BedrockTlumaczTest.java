package com.koper.koper_lib.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

// the sample addon in test resources goes through the converter, every output file gets poked
class BedrockTlumaczTest {

    @TempDir static Path out;
    static JsonObject side;

    static Path sample() throws Exception {
        return Path.of(BedrockTlumaczTest.class.getResource("/bedrock/koper_bedrock_test").toURI());
    }

    static JsonObject json(String rel) throws Exception {
        Path f = out.resolve(rel);
        assertTrue(Files.isRegularFile(f), "missing " + rel);
        return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
    }

    @BeforeAll
    static void convert() throws Exception {
        side = BedrockTlumacz.translate(new BedrockTlumacz.Paczka("koper_bedrock_test",
            sample().resolve("BP"), sample().resolve("RP")), out);
    }

    @Test
    void meta() throws Exception {
        JsonObject m = json("pack.kopermeta");
        assertEquals("kbt", m.get("namespace").getAsString());
        assertEquals("Koper Bedrock Test", m.get("name").getAsString());
        assertEquals("1.0.0", m.get("version").getAsString());
        assertEquals("kbt", side.get("namespace").getAsString());
    }

    @Test
    void items() throws Exception {
        JsonObject wand = json("items/zap_wand.json");
        assertEquals("kbt:zap_wand", wand.get("id").getAsString());
        assertEquals("Zap Wand", wand.get("name").getAsString());
        assertEquals("kbt:item/zap_wand", wand.get("texture").getAsString());
        assertEquals(64, wand.get("durability").getAsInt());
        assertEquals(1, wand.get("max_stack").getAsInt());
        assertTrue(Files.isRegularFile(out.resolve("assets/kbt/textures/item/zap_wand.png")));

        JsonObject bread = json("items/koper_bread.json");
        assertEquals("food", bread.get("type").getAsString());
        assertEquals(6, bread.get("food_hunger").getAsInt());
        assertEquals(0.8f, bread.get("food_saturation").getAsFloat(), 1e-6);
        assertTrue(bread.get("always_edible").getAsBoolean());
        assertEquals("Koper Bread", bread.get("name").getAsString());

        var comps = side.getAsJsonObject("items");
        assertEquals("kbt:zap", comps.getAsJsonArray("kbt:zap_wand").get(0).getAsJsonObject().get("n").getAsString());
        assertEquals(7, comps.getAsJsonArray("kbt:zap_wand").get(0).getAsJsonObject().getAsJsonObject("p").get("power").getAsInt());
        assertEquals("kbt:yum", comps.getAsJsonArray("kbt:koper_bread").get(0).getAsJsonObject().get("n").getAsString());
    }

    @Test
    void blocks() throws Exception {
        JsonObject crate = json("blocks/glow_crate.json");
        assertEquals(1.0f, crate.get("hardness").getAsFloat(), 1e-6);
        assertEquals(7, crate.get("light_level").getAsInt());
        assertEquals(3f, crate.get("resistance").getAsFloat(), 1e-6);
        assertEquals("kbt:block/glow_crate", crate.get("texture").getAsString());
        assertFalse(crate.get("drops_self").getAsBoolean());
        assertEquals(40, crate.get("tick_interval").getAsInt());
        assertTrue(crate.getAsJsonObject("events").has("on_tick"));
        assertTrue(crate.getAsJsonObject("events").has("on_step"));
        assertEquals("Glow Crate", crate.get("name").getAsString());
        JsonObject loot = json("datapacks/bedrock/data/kbt/loot_table/blocks/glow_crate.json");
        assertEquals("minecraft:block", loot.get("type").getAsString());
        assertEquals(2, loot.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject()
            .getAsJsonObject("modifier").get("count").getAsInt());
        assertTrue(side.getAsJsonObject("blocks").has("kbt:glow_crate"));
    }

    @Test
    void blockStatesAndPermutations() throws Exception {
        JsonObject lamp = json("blocks/lamp.json");
        String states = lamp.getAsJsonArray("states").toString();
        assertTrue(states.contains("bool:kbt_lit"), states);
        assertTrue(states.contains("enum:kbt_color:red|blue"), states);
        assertTrue(states.contains("horizontal_facing"), states);
        assertEquals("player", lamp.get("facing_from").getAsString());
        assertTrue(lamp.get("own_assets").getAsBoolean());
        JsonObject light = lamp.getAsJsonObject("light_by_state");
        assertEquals(15, light.get("facing=north,kbt_color=red,kbt_lit=true").getAsInt());
        assertEquals(9, light.get("facing=north,kbt_color=blue,kbt_lit=true").getAsInt());
        assertEquals(0, light.get("facing=north,kbt_color=red,kbt_lit=false").getAsInt());
        JsonObject variants = json("assets/kbt/blockstates/lamp.json").getAsJsonObject("variants");
        assertEquals(16, variants.size());
        JsonObject onRed = variants.getAsJsonObject("facing=north,kbt_color=red,kbt_lit=true");
        JsonObject offRed = variants.getAsJsonObject("facing=north,kbt_color=red,kbt_lit=false");
        assertTrue(!onRed.get("model").equals(offRed.get("model")), "lit state must use the lit texture model");
        assertEquals(90, variants.getAsJsonObject("facing=east,kbt_color=red,kbt_lit=false").get("y").getAsInt());
        String litModel = onRed.get("model").getAsString().replace("kbt:block/", "");
        assertTrue(json("assets/kbt/models/block/" + litModel + ".json").toString().contains("kbt:block/lamp_on"));
    }

    @Test
    void entities() throws Exception {
        JsonObject bug = json("entities/koper_bug.json");
        assertEquals(12, bug.get("health").getAsInt());
        assertEquals(3f, bug.get("attack_damage").getAsFloat(), 1e-6); // from the spawn component group
        assertEquals(0.8f, bug.get("width").getAsFloat(), 1e-6);
        assertEquals("HOSTILE", bug.get("ai_type").getAsString());
        assertTrue(bug.getAsJsonArray("entity_ai").toString().contains("attack_melee"));
        assertTrue(bug.getAsJsonArray("entity_ai").toString().contains("target_nearest_player"));
        assertEquals("koper_bug", bug.get("model").getAsString());
        assertEquals("kbt:textures/entity/koper_bug.png", bug.get("texture").getAsString());
        assertEquals("animation.koper_bug.walk", bug.get("run_animation").getAsString());
        assertEquals("#3c9c3c", bug.get("spawn_egg_primary").getAsString());
        assertEquals("Koper Bug", bug.get("name").getAsString());
        // no kodel module in a unit test -> the geo.json is kept for kodel to convert on load
        JsonObject geo = json("models/koper_bug.geo.json");
        assertEquals(4, geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").size());
        assertTrue(json("animations/koper_bug.animation.json").getAsJsonObject("animations").has("animation.koper_bug.walk"));
        assertTrue(side.getAsJsonObject("families").getAsJsonArray("kbt:koper_bug").toString().contains("monster"));
        assertFalse(side.getAsJsonObject("properties").getAsJsonObject("kbt:koper_bug").get("kbt:angry").getAsBoolean());

        JsonObject loot = json("datapacks/bedrock/data/kbt/loot_table/entities/koper_bug.json");
        var pool0 = loot.getAsJsonArray("pools").get(0).getAsJsonObject();
        // 26.3 loot: several functions are a "modifier" list, one condition is a single "condition"
        var fn = pool0.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("modifier");
        assertEquals("minecraft:set_count", fn.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("minecraft:uniform", fn.get(0).getAsJsonObject().getAsJsonObject("count").get("type").getAsString());
        assertEquals("minecraft:enchanted_count_increase", fn.get(1).getAsJsonObject().get("type").getAsString());
        var cond = loot.getAsJsonArray("pools").get(1).getAsJsonObject().getAsJsonObject("condition");
        assertEquals("minecraft:all_of", cond.get("type").getAsString());
        assertEquals("minecraft:killed_by_player", cond.getAsJsonArray("terms").get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(Files.isRegularFile(out.resolve("datapacks/bedrock/data/kbt/loot_table/chests/koper_stash.json")));
        // the raw definition rides along for the live component groups
        assertTrue(json("bedrock_bp/entities/koper_bug.json").getAsJsonObject("minecraft:entity").has("component_groups"));
    }

    @Test
    void recipesAndFunctions() throws Exception {
        JsonObject shaped = json("datapacks/bedrock/data/kbt/recipe/zap_wand.json");
        assertEquals("minecraft:crafting_shaped", shaped.get("type").getAsString());
        assertEquals("minecraft:glowstone_dust", shaped.getAsJsonObject("key").get("G").getAsString());
        assertEquals("minecraft:stick", shaped.getAsJsonObject("key").get("S").getAsString());
        assertEquals("kbt:zap_wand", shaped.getAsJsonObject("result").get("id").getAsString());
        JsonObject smelt = json("datapacks/bedrock/data/kbt/recipe/crate_smelt.json");
        assertEquals("minecraft:smelting", smelt.get("type").getAsString());

        String fn = Files.readString(out.resolve("datapacks/bedrock/data/kbt/function/hello.mcfunction"));
        assertTrue(fn.contains("\nsay hi from bedrock"), fn);
        assertTrue(fn.contains("effect give @s minecraft:speed 10 1 true"), fn);
        assertTrue(fn.contains("give @p[limit=1,sort=nearest,distance=..5] minecraft:stick 2\n"), fn);
        assertTrue(json("datapacks/bedrock/data/minecraft/tags/function/tick.json").toString().contains("kbt:hello"));
    }

    @Test
    void scriptsAndLang() throws Exception {
        assertTrue(Files.isRegularFile(out.resolve("bedrock_scripts/main.js")));
        assertTrue(Files.isRegularFile(out.resolve("bedrock_scripts/lib/pretty.js")));
        assertEquals("bedrock_scripts/main.js", side.getAsJsonObject("script").get("entry").getAsString());
        assertEquals(2, side.getAsJsonObject("script").get("api_major").getAsInt());
        assertEquals("Zap Wand", json("assets/kbt/lang/en_us.json").get("item.kbt:zap_wand.name").getAsString());
        // the "\t#" comment tail must not end up in the name
        assertEquals("Glow Crate", json("assets/kbt/lang/en_us.json").get("tile.kbt:glow_crate.name").getAsString());
    }
}
