package com.koper.koper_lib.kodel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

// bedrock writes inside out shells as negative size from the far corner (slime's inner layer).
// the face builder only knows positive boxes, it threw those faces out as big flat planes
class KodelUjemnyTest {

    @Test
    void negativeSizeIsTheSameBox() {
        KodelModel m = KodelConverters.geometry(JsonParser.parseString("""
            {"description": {"identifier": "geometry.ujemny", "texture_width": 64, "texture_height": 32},
             "bones": [{"name": "shell", "pivot": [0, 0, 0], "cubes": [
               {"origin": [4, 8.4, 4], "size": [-8, -8, -8], "inflate": -0.4, "uv": [0, 0]}]}]}
            """).getAsJsonObject());
        KodelModel.KodelCube c = m.bones.get(0).cubes.get(0);
        assertArrayEquals(new float[] {-4, 0.4f, -4}, c.origin, 1e-5f);
        assertArrayEquals(new float[] {8, 8, 8}, c.size, 1e-5f);
    }
}
