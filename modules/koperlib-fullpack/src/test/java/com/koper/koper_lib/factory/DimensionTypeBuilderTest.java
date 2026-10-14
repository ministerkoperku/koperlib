package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionTypeBuilderTest {
    private static DimensionTypeBuilder.Built build(String json) {
        return DimensionTypeBuilder.build(JsonParser.parseString(json).getAsJsonObject(), "pack", "moon", DimensionFactory::vanilla);
    }

    private static JsonObject attributes(DimensionTypeBuilder.Built built) {
        return built.type().getAsJsonObject("attributes");
    }

    @Test
    void plainDimensionKeepsTheOldGeometryAndHasNoPre263Keys() {
        var built = build("{\"id\":\"pack:moon\"}");
        var type = built.type();
        assertEquals(-64, type.get("min_y").getAsInt());
        assertEquals(384, type.get("height").getAsInt());
        assertEquals(256, type.get("logical_height").getAsInt());
        for (String old : new String[]{"effects", "fixed_time", "natural", "ultrawarm", "bed_works", "piglin_safe", "respawn_anchor_works", "has_raids"})
            assertFalse(type.has(old), old);
        assertEquals("minecraft:overworld", type.get("default_clock").getAsString());
        assertNull(built.fixedTime());
    }

    @Test
    void oldGameplayKeysBecomeAttributes() {
        var a = attributes(build("{\"bed_works\":false,\"has_raids\":false,\"piglin_safe\":true,\"ultrawarm\":true,\"respawn_anchor_works\":true}"));
        assertEquals("never", a.getAsJsonObject("minecraft:gameplay/bed_rule").get("can_sleep").getAsString());
        assertFalse(a.get("minecraft:gameplay/can_start_raid").getAsBoolean());
        assertFalse(a.get("minecraft:gameplay/piglins_zombify").getAsBoolean());
        assertTrue(a.get("minecraft:gameplay/water_evaporates").getAsBoolean());
        assertTrue(a.get("minecraft:gameplay/respawn_anchor_works").getAsBoolean());
    }

    @Test
    void endEffectsBringTheEndSkyAndDropOverworldLooks() {
        var built = build("{\"effects\":\"minecraft:the_end\"}");
        assertEquals("end", built.type().get("skybox").getAsString());
        assertEquals("#000000", attributes(built).get("minecraft:visual/sky_color").getAsString());
        assertFalse(attributes(built).has("minecraft:visual/cloud_height"));
    }

    @Test
    void unknownEffectsAreRefusedLoudly() {
        assertThrows(IllegalArgumentException.class, () -> build("{\"effects\":\"pack:custom\"}"));
        assertThrows(IllegalArgumentException.class, () -> build("{\"skybox\":\"purple\"}"));
    }

    @Test
    void fixedTimeMakesAnOwnPausedClockWithTimelines() {
        var built = build("{\"fixed_time\":6000}");
        assertEquals(6000, built.fixedTime());
        assertEquals("pack:moon", built.clock());
        assertEquals("pack:moon", built.type().get("default_clock").getAsString());
        assertTrue(built.type().get("has_fixed_time").getAsBoolean());
        assertEquals(2, built.type().getAsJsonArray("timelines").size());
        var day = built.files().get("timeline/moon_day.json");
        assertEquals("pack:moon", day.get("clock").getAsString());
        assertFalse(day.has("time_markers"));
        assertTrue(built.files().containsKey("world_clock/moon.json"));
    }

    @Test
    void spaceIsBlackStarryAndFrozenAtMidnight() {
        var built = build("{\"base_type\":\"space\"}");
        var a = attributes(built);
        assertEquals("overworld", built.type().get("skybox").getAsString());
        assertEquals("#000000", a.get("minecraft:visual/sky_color").getAsString());
        assertEquals(1.0, a.get("minecraft:visual/star_brightness").getAsDouble());
        assertEquals(DimensionTypeBuilder.SPACE_TIME, built.fixedTime());
    }

    @Test
    void packAttributesHaveTheLastWord() {
        var a = attributes(build("{\"base_type\":\"space\",\"attributes\":{\"visual/sky_color\":\"#101020\"}}"));
        assertEquals("#101020", a.get("minecraft:visual/sky_color").getAsString());
    }
}
