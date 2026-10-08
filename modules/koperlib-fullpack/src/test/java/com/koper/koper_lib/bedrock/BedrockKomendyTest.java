package com.koper.koper_lib.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockKomendyTest {

    static String t(String in) { return BedrockSkladnia.przetlumacz(in); }

    @Test
    void effects() {
        assertEquals("effect give @a minecraft:speed 30 2", t("/effect @a speed 30 2"));
        assertEquals("effect clear @s", t("effect @s clear"));
        assertEquals("effect give @s minecraft:regeneration 5", t("effect give @s minecraft:regeneration 5"));
    }

    @Test
    void selectors() {
        assertEquals("kill @e[type=zombie,limit=3,sort=furthest,distance=2..10]", t("kill @e[type=zombie,r=10,rm=2,c=-3]"));
        assertEquals("tag @a[gamemode=creative,level=5..] add pro", t("tag @a[m=c,lm=5] add pro"));
        assertEquals("say @e[tag=x,family=monster]", t("say @e[tag=x,family=monster]"));
        assertEquals("tp @e[type=!minecraft:player,family=!frostmaw,family=!despawn,limit=1,sort=nearest] @s",
            t("tp @e[type=!minecraft:player,family=!frostmaw,family=!despawn,c=1] @s"));
        assertEquals("say @e[has_property={p:sbkmbc=false}]", t("say @e[has_property={p:sbkmbc=false}]"));
        assertEquals("kill @e[scores={a=1..3},limit=1,sort=nearest]", t("kill @e[scores={a=1..3},c=1]"));
    }

    @Test
    void bedrockItemNames() {
        var n = (java.util.function.Function<String, String>) com.koper.koper_lib.api.core.BedrockNazwy::przedmiotDoJavy;
        assertEquals("minecraft:mutton", n.apply("minecraft:muttonRaw"));
        assertEquals("minecraft:music_disc_wait", n.apply("minecraft:record_wait"));
        assertEquals("minecraft:lapis_lazuli", n.apply("minecraft:dye:4"));
        assertEquals("minecraft:red_wool", n.apply("wool:14"));
        assertEquals("minecraft:birch_planks", n.apply("minecraft:planks:2"));
        assertEquals("minecraft:charcoal", n.apply("coal:1"));
        assertEquals("minecraft:diamond_sword", n.apply("diamond_sword"));
        assertEquals("demo:my_sword", n.apply("demo:my_sword"));
        assertEquals("give @s minecraft:cooked_mutton 3", t("give @s muttonCooked 3"));
        assertEquals("give @a minecraft:lapis_lazuli 5", t("give @a dye 5 4"));
        assertEquals("clear @s minecraft:bone_meal 2", t("clear @s dye 15 2"));
    }

    @Test
    void oddOnes() {
        assertEquals("gamemode creative @s", t("gamemode c @s"));
        assertEquals("give @p minecraft:stick 2", t("give @p minecraft:stick 2 0"));
        assertEquals("execute if entity @e[type=pig]", t("testfor @e[type=pig]"));
        // bedrock's own names for mobs java calls something else
        assertEquals("kill @e[type=villager]", t("kill @e[type=villager_v2]"));
        assertEquals("kill @e[type=!minecraft:zombified_piglin]", t("kill @e[type=!minecraft:zombie_pigman]"));
        assertEquals("summon evoker ~ ~1 ~", t("summon evocation_illager ~ ~1 ~"));
        assertEquals("item replace entity @s weapon.mainhand with minecraft:diamond_sword", t("replaceitem entity @s slot.weapon.mainhand 0 minecraft:diamond_sword"));
        assertEquals("item replace entity @s hotbar.3 with minecraft:bread 5", t("replaceitem entity @s slot.hotbar 3 minecraft:bread 5"));
        assertEquals("xp add @s 5 levels", t("xp 5L @s"));
        // bedrock's vanilla sound names are java's events (java has no random.orb)
        assertEquals("playsound minecraft:entity.experience_orb.pickup master @a", t("playsound random.orb @a"));
        assertEquals("playsound minecraft:entity.generic.explode master @a 1 2 3", t("playsound random.explode @a 1 2 3"));
        assertEquals("playsound minecraft:block.stone.break master @s", t("playsound dig.stone @s"));
        assertEquals("playsound minecraft:entity.zombie.ambient master @s", t("playsound mob.zombie.say @s"));
        assertEquals("setblock ~ ~ ~ minecraft:oak_log[pillar_axis=y]", t("setblock ~ ~ ~ minecraft:oak_log[\"pillar_axis\"=\"y\"]"));
        assertEquals("execute as @a at @s positioned ~ ~1 ~ run effect give @s minecraft:speed 1", t("execute @a ~ ~1 ~ effect @s speed 1"));
        assertEquals("execute as @a run effect give @s minecraft:haste 3", t("execute as @a run effect @s haste 3"));
        assertEquals("title @a title hi", t("titleraw @a title hi"));
    }

    @Test
    void bedrockOnlySyntax() {
        assertEquals("tellraw @a [\"\",{\"text\":\"hi \"},{\"selector\":\"@p\"}]", t("tellraw @a {\"rawtext\":[{\"text\":\"hi \"},{\"selector\":\"@p\"}]}"));
        assertEquals("title @a actionbar [\"\",{\"translate\":\"koper.hi\",\"with\":[\"a\"]}]", t("titleraw @a actionbar {\"rawtext\":[{\"translate\":\"koper.hi\",\"with\":[\"a\"]}]}"));
        assertEquals("execute store result score @s roll run random value 1..6", t("scoreboard players random @s roll 1 6"));
        assertEquals("execute if score @s hp matches 5..", t("scoreboard players test @s hp 5 *"));
        assertEquals("scoreboard objectives setdisplay sidebar kills", t("scoreboard objectives setdisplay sidebar kills descending"));
        assertEquals("execute if block ~ ~-1 ~ minecraft:grass_block", t("testforblock ~ ~-1 ~ grass_block"));
        assertEquals("tp @s ~ ~5 ~", t("tp @s ~ ~5 ~ true"));
        assertEquals("setblock ~ ~ ~ minecraft:stone replace", t("setblock ~ ~ ~ minecraft:stone 0 replace"));
        assertEquals("fill ~ ~ ~ ~5 ~ ~5 minecraft:air replace minecraft:stone", t("fill ~ ~ ~ ~5 ~ ~5 minecraft:air 0 replace minecraft:stone 0"));
        assertEquals("bparticle minecraft:heart_particle ~ ~2 ~", t("particle minecraft:heart_particle ~ ~2 ~"));
        assertEquals("bsummon koper:bug ~ ~ ~ koper:angry Bob", t("summon koper:bug ~ ~ ~ koper:angry Bob"));
        assertEquals("bsummon koper:bug ~ ~ ~ koper:angry", t("summon koper:bug ~ ~ ~ 90 0 koper:angry"));
        assertEquals("ride @s mount @e[type=horse,limit=1,sort=nearest]", t("ride @s start_riding @e[type=horse,c=1]"));
        assertEquals("damage @s 4 minecraft:mob_attack by @p", t("damage @s 4 entity_attack entity @p"));
    }

    @Test
    void gluedCoordinatesAndOldExecute() {
        assertEquals("playsound minecraft:block.lava.pop master @a ~ ~ ~ 1 1 0.001", t("playsound liquid.lavapop @a ~~~ 1 1 0.001"));
        assertEquals("execute as @s at @s positioned ~ ~ ~ run tp @s ~ ~0.25 ~ facing @e[type=hfrlc:nether_sword.target]",
            t("execute @s ~~~ tp @s ~~0.25~ facing @e[type=hfrlc:nether_sword.target] true"));
        assertEquals("execute as @s positioned ~ ~1 ~ run execute as @p[distance=..2] run say hi", t("execute as @s positioned ~~1~ run execute as @p[r=2] run say hi"));
        assertEquals("structure load mystructure:earth_sword.attack ~-2 ~-2 ~-2", t("structure load mystructure:earth_sword.attack ~-2~-2~-2"));
        assertEquals("structure load x ~-2 ~ ~-2", t("structure load x ~-2~~-2"));
        assertEquals("execute as @s at @s positioned ~ ~ ~ if block ~ ~-1 ~ minecraft:stone run say on stone", t("execute @s ~ ~ ~ detect ~ ~-1 ~ stone 0 say on stone"));
    }

    @Test
    void effectZeroTakesItOff() {
        assertEquals("effect clear @s minecraft:speed", t("effect @s speed 0"));
        assertEquals("effect clear @s minecraft:slowness", t("effect @s slowness 0 0"));
        assertEquals("effect clear @s minecraft:slowness", t("effect @s clear slowness"));
    }

    @Test
    void tagsWithColons() {
        assertEquals("btag @s add hfrlc:summon_cooldown", t("tag @s add hfrlc:summon_cooldown"));
        assertEquals("tag @s add plain", t("tag @s add plain"));
    }

    @Test
    void gameRulesGotRenamed() {
        assertEquals("gamerule advance_time false", t("gamerule doDaylightCycle false"));
        assertEquals("gamerule keep_inventory true", t("gamerule keepInventory true"));
        assertEquals("gamerule fire_spread_radius_around_player 0", t("gamerule dofiretick false"));
        assertEquals("gamerule spawn_mobs", t("gamerule doMobSpawning"));
    }

    @Test
    void lootTablesGetTheirNamespace() {
        assertEquals("loot spawn ~ ~ ~ loot minecraft:animals/deer", t("loot spawn ~~~ loot \"animals/deer\""));
        assertEquals("loot give @s loot minecraft:chests/x", t("loot give @s loot loot_tables/chests/x.json mainhand"));
    }

    @Test
    void plainTitlesBecomeComponents() {
        assertEquals("title @s actionbar \"Entities Cleared!\"", t("title @s actionbar Entities Cleared!"));
        assertEquals("title @s title \"hide_sphere;\"", t("title @s title hide_sphere;"));
        assertEquals("title @s times 1 2 3", t("title @s times 1 2 3"));
        assertEquals("execute as @a[tag=sun_blessing,tag=geomancy] at @s positioned ~ ~ ~ run title @s title \"sun_blessing and geomancy\"",
            t("execute @a[tag=sun_blessing, tag=geomancy] ~ ~ ~ title @s title sun_blessing and geomancy"));
    }

    @Test
    void stopsoundGetsASource() {
        assertEquals("stopsound @s[tag=do_tuto] * hfrlc:tutorial.dialogue.intro2", BedrockSkladnia.przetlumaczDla("hfrlc", "stopsound @s[tag=do_tuto] tutorial.dialogue.intro2"));
        assertEquals("playsound hfrlc:tutorial.dialogue.1 master @s", BedrockSkladnia.przetlumaczDla("hfrlc", "playsound tutorial.dialogue.1 @s"));
        assertEquals("stopsound @a", t("stopsound @a"));
    }
}
