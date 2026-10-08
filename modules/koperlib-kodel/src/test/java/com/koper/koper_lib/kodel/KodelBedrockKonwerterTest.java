package com.koper.koper_lib.kodel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

// what the fullpack bedrock converter hands kodel: a geo file, an animation file, a png
class KodelBedrockKonwerterTest {

    static final String GEO = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.koper_bug","texture_width":32,"texture_height":32},
         "bones":[{"name":"body","pivot":[0,4,0],"cubes":[{"origin":[-4,2,-6],"size":[8,5,12],"uv":[0,0]}]},
                  {"name":"leg_l","parent":"body","pivot":[3,2,0],"cubes":[{"origin":[2,0,-1],"size":[2,2,2],"uv":[20,17]}]}]}]}""";
    static final String ANIM = """
        {"format_version":"1.8.0","animations":{"animation.koper_bug.walk":{"loop":true,"animation_length":0.5,
         "bones":{"leg_l":{"rotation":{"0.0":[30,0,0],"0.25":[-30,0,0],"0.5":[30,0,0]}}}}}}""";

    @Test
    void bedrockGoesInKodelComesOut() throws Exception {
        JsonObject geo = JsonParser.parseString(GEO).getAsJsonObject();
        JsonObject anim = JsonParser.parseString(ANIM).getAsJsonObject();
        byte[] bytes = new KodelBedrockKonwerter().convert(geo, anim, null, "koper_bug");
        assertNotNull(bytes);
        KodelLoader.Loaded back = KodelLoader.read(new ByteArrayInputStream(bytes));
        assertEquals(2, back.model().bones.size());
        assertEquals("body", back.model().bones.get(0).name);
        assertEquals(0, back.model().bones.get(1).parent);
        assertEquals(32, back.model().texWidth);
        assertEquals(1, back.animations().size());
        assertEquals("animation.koper_bug.walk", back.animations().get(0).name);
        assertEquals(0.5f, back.animations().get(0).length, 1e-6);
    }

    @Test
    void emptyGeometryIsNull() {
        JsonObject geo = JsonParser.parseString("{\"minecraft:geometry\":[]}").getAsJsonObject();
        assertNull(new KodelBedrockKonwerter().convert(geo, null, null, "x"));
    }
}
