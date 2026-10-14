package com.koper.koper_lib.fullpack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpaceBodiesTest {
    @Test
    void parsesAPackBody() {
        var body = SpaceBodies.parse(JsonParser.parseString("""
            {"id":"pack:moon","space":"pack:space","pos":[4000,0,-2500],"radius":300,"atmosphere":60,
             "texture":"pack:textures/space/moon.png","atmosphere_color":"#406080ff","target":"pack:moon"}
            """).getAsJsonObject());
        assertEquals(360, body.reach(), 1e-9);
        assertEquals(0xFFFFFFFF, body.color());
        assertEquals(0x406080ff, body.glow());
        assertEquals(1000, body.landing(), 1e-9);
        assertTrue(body.landable());
    }

    @Test
    void colorsAcceptRgbAndArgb() {
        var json = JsonParser.parseString("{\"a\":\"#ff0000\",\"b\":\"#80ff0000\",\"c\":\"red\"}").getAsJsonObject();
        assertEquals(0xFFFF0000, SpaceBodies.color(json, "a", 0));
        assertEquals(0x80FF0000, SpaceBodies.color(json, "b", 0));
        assertThrows(IllegalArgumentException.class, () -> SpaceBodies.color(json, "c", 0));
    }

    @Test
    void missingFieldsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SpaceBodies.parse(JsonParser.parseString("{\"id\":\"x\"}").getAsJsonObject()));
    }
}
