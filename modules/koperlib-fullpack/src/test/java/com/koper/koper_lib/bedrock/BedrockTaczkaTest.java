package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// the fast paths of the converter: parallel unzip, links, background delete, png over tga, geometry parents
class BedrockTaczkaTest {

    private static void zip(Path zip, Map<String, String> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    @Test
    void unzipsManyFilesBackslashesAndNoEscape(@TempDir Path tmp) throws IOException {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 300; i++) entries.put("RP/textures/blocks/b" + (i % 7) + "/t" + i + ".png", "px" + i);
        entries.put("RP\\texts\\en_US.lang", "item.a.name=A");
        entries.put("../../evil.txt", "nope");
        Path zip = tmp.resolve("a.mcaddon");
        zip(zip, entries);
        Path out = tmp.resolve("out");
        BedrockTaczka.rozpakuj(zip, out);
        for (int i = 0; i < 300; i++)
            assertEquals("px" + i, Files.readString(out.resolve("RP/textures/blocks/b" + (i % 7) + "/t" + i + ".png")));
        assertEquals("item.a.name=A", Files.readString(out.resolve("RP/texts/en_US.lang")));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
        assertFalse(Files.exists(tmp.getParent().resolve("evil.txt")));
    }

    @Test
    void linkOrCopyKeepsBytesAndSurvivesTheSource(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("a.png");
        Files.writeString(src, "koper");
        Path dst = tmp.resolve("x/b.png");
        Files.createDirectories(dst.getParent());
        BedrockTaczka.przenies(src, dst);
        // twice is fine, the second one lands on top
        BedrockTaczka.przenies(src, dst);
        Files.delete(src);
        assertEquals("koper", Files.readString(dst));
    }

    @Test
    void wipeMovesAsideAndTheTrashEmpties(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("pack_bedrock");
        for (int i = 0; i < 50; i++) {
            Path f = dir.resolve("d" + (i % 5)).resolve("f" + i);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "x");
        }
        Path trash = tmp.resolve(".cache/trash");
        BedrockTaczka.wywal(dir, trash);
        assertFalse(Files.exists(dir), "gone from where the loader looks right away");
        for (int i = 0; i < 100; i++) {
            try (Stream<Path> s = Files.list(trash)) {
                if (s.findAny().isEmpty()) return;
            }
            Thread.sleep(50);
        }
        fail("trash never emptied");
    }

    @Test
    void pngBeatsTgaOfTheSameName(@TempDir Path tmp) throws IOException {
        Path rp = tmp.resolve("rp");
        Files.createDirectories(rp.resolve("textures/blocks"));
        Files.writeString(rp.resolve("manifest.json"), "{\"header\":{\"name\":\"t\",\"uuid\":\"1\"},\"modules\":[{\"type\":\"resources\",\"uuid\":\"2\"}]}");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        Files.write(rp.resolve("textures/blocks/leaf.png"), png);
        // a tga that is really a png too, just different bytes: if it won we would see them
        byte[] other = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 9, 9, 9};
        Files.write(rp.resolve("textures/blocks/leaf.tga"), other);
        Path out = tmp.resolve("out");
        BedrockTlumacz.translate(new BedrockTlumacz.Paczka("t", null, rp), out);
        assertArrayEquals(png, Files.readAllBytes(out.resolve("assets/t/textures/bedrock/blocks/leaf.png")));
    }

    @Test
    void tgaStillDecodes() {
        // 2x1 uncompressed 32 bit, bottom up: blue then red on disk (bgra)
        byte[] tga = new byte[18 + 8];
        tga[2] = 2;
        tga[12] = 2;
        tga[14] = 1;
        tga[16] = 32;
        byte[] px = {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255};
        System.arraycopy(px, 0, tga, 18, 8);
        byte[] png = BedrockSkladacz.tgaToPng(tga);
        assertNotNull(png);
        int[] wh = BedrockPikselarz.rozmiar(png);
        assertArrayEquals(new int[] {2, 1}, new int[] {wh[0], wh[1]});
    }

    @Test
    void palettedTgaDecodes() {
        // type 1, two 24 bit palette entries (bgr), 2x1 pixels pointing at entry 1 then 0
        byte[] tga = new byte[18 + 6 + 2];
        tga[1] = 1;
        tga[2] = 1;
        tga[5] = 2;
        tga[7] = 24;
        tga[12] = 2;
        tga[14] = 1;
        tga[16] = 8;
        tga[17] = 0x20; // top down
        byte[] rest = {0, 0, (byte) 255, (byte) 255, 0, 0, 1, 0};
        System.arraycopy(rest, 0, tga, 18, rest.length);
        byte[] png = BedrockSkladacz.tgaToPng(tga);
        assertNotNull(png);
        int[] wh = BedrockPikselarz.rozmiar(png);
        assertArrayEquals(new int[] {2, 1}, new int[] {wh[0], wh[1]});
    }

    @Test
    void childGeometryKeepsItsParentsBones() {
        JsonObject parent = JsonParser.parseString("""
            {"texturewidth": 64, "bones": [{"name": "body", "pivot": [0, 12, 0]}, {"name": "head", "parent": "body", "pivot": [0, 24, 0]}]}
            """).getAsJsonObject();
        JsonObject child = JsonParser.parseString("""
            {"texturewidth": 128, "bones": [{"name": "Head", "parent": "body", "pivot": [0, 26, 0]}, {"name": "hat", "parent": "head"}]}
            """).getAsJsonObject();
        JsonObject m = BedrockTlumacz.dziedzicz(parent, child);
        JsonArray b = m.getAsJsonArray("bones");
        assertEquals(3, b.size());
        assertEquals("body", b.get(0).getAsJsonObject().get("name").getAsString());
        // replaced in place, the child's version whole
        assertEquals(26, b.get(1).getAsJsonObject().getAsJsonArray("pivot").get(1).getAsInt());
        assertEquals("hat", b.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals(128, m.get("texturewidth").getAsInt());
    }

    @Test
    void bedrockNamesForJavasMobs() {
        var n = com.koper.koper_lib.api.core.BedrockNazwy.class;
        assertEquals("minecraft:villager", com.koper.koper_lib.api.core.BedrockNazwy.doJavy("minecraft:villager_v2"));
        assertEquals("minecraft:villager_v2", com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka("minecraft:villager"));
        assertEquals("minecraft:zombified_piglin", com.koper.koper_lib.api.core.BedrockNazwy.doJavy("zombie_pigman"));
        assertEquals("minecraft:boat", com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka("minecraft:birch_boat"));
        assertEquals("minecraft:chest_boat", com.koper.koper_lib.api.core.BedrockNazwy.doBedrocka("minecraft:bamboo_chest_raft"));
        assertEquals("koper:bug", com.koper.koper_lib.api.core.BedrockNazwy.doJavy("koper:bug"));
        assertTrue(com.koper.koper_lib.api.core.BedrockNazwy.ten("villager_v2", "minecraft:villager"));
        assertFalse(com.koper.koper_lib.api.core.BedrockNazwy.ten("minecraft:pig", "minecraft:cow"));
        assertNotNull(n);
    }

    @Test
    void villagerVoicesLandOnJavasEvents() {
        java.util.Set<String> jawa = java.util.Set.of("entity.villager.ambient", "entity.villager.trade", "entity.villager.yes", "entity.drowned.ambient_water");
        assertEquals("entity.villager.ambient", BedrockTlumacz.javaEntityEvent("villager", "ambient", jawa));
        assertEquals("entity.villager.trade", BedrockTlumacz.javaEntityEvent("villager", "haggle", jawa));
        assertEquals("entity.villager.yes", BedrockTlumacz.javaEntityEvent("villager", "haggle.yes", jawa));
        assertEquals("entity.drowned.ambient_water", BedrockTlumacz.javaEntityEvent("drowned", "ambient.in.water", jawa));
        assertNull(BedrockTlumacz.javaEntityEvent("villager", "haggle.no", jawa), "java lacks it here, nothing to replace");
    }

    @Test
    void packRedefiningAVanillaMobGoesToTheOverlay(@TempDir Path tmp) throws IOException {
        Path bp = tmp.resolve("bp");
        Files.createDirectories(bp.resolve("entities"));
        Files.writeString(bp.resolve("manifest.json"), "{\"header\":{\"name\":\"vn\",\"uuid\":\"1\"},\"modules\":[{\"type\":\"data\",\"uuid\":\"2\"}]}");
        Files.writeString(bp.resolve("entities/villager.json"), """
            {"minecraft:entity": {"description": {"identifier": "minecraft:villager_v2",
              "properties": {"vn:mood": {"type": "int", "range": [0, 5], "default": 2}}},
             "components": {"minecraft:health": {"value": 20}}, "events": {"vn:smile": {"set_property": {"vn:mood": 5}}}}}
            """);
        Path out = tmp.resolve("out");
        JsonObject side = BedrockTlumacz.translate(new BedrockTlumacz.Paczka("vn", bp, null), out);
        assertTrue(Files.isRegularFile(out.resolve("bedrock_bp/entities/vanilla/villager_v2.json")));
        assertFalse(Files.exists(out.resolve("entities/villager_v2.json")), "no koper mob for java's own villager");
        assertEquals(2, side.getAsJsonObject("properties").getAsJsonObject("minecraft:villager_v2").get("vn:mood").getAsInt());
    }

    @Test
    void packMobsGetTheirVoices(@TempDir Path tmp) throws IOException {
        Path rp = tmp.resolve("rp");
        Files.createDirectories(rp.resolve("sounds/mob/bear"));
        Files.writeString(rp.resolve("manifest.json"), "{\"header\":{\"name\":\"bear\",\"uuid\":\"1\"},\"modules\":[{\"type\":\"resources\",\"uuid\":\"2\"}]}");
        Files.write(rp.resolve("sounds/mob/bear/growl1.ogg"), new byte[] {1, 2, 3});
        Files.writeString(rp.resolve("sounds/sound_definitions.json"), """
            {"format_version": "1.14.0", "sound_definitions": {"mob.bear.growl": {"sounds": ["sounds/mob/bear/growl1"]}}}
            """);
        Files.writeString(rp.resolve("sounds.json"), """
            {"entity_sounds": {"defaults": {"events": {"fall.big": "damage.fallbig"}},
             "entities": {"mcl:black_bear": {"volume": 1.0, "pitch": [0.8, 1.2],
               "events": {"ambient": "mob.bear.growl", "hurt": "mob.polarbear.hurt", "death": {"sound": "mob.polarbear.death", "volume": 0.5}}}}}}
            """);
        JsonObject side = BedrockTlumacz.translate(new BedrockTlumacz.Paczka("bear", null, rp), tmp.resolve("out"));
        JsonObject bear = side.getAsJsonObject("voices").getAsJsonObject("mcl:black_bear");
        String ns = side.get("sound_namespace").getAsString();
        assertEquals(ns + ":mob.bear.growl", bear.getAsJsonObject("ambient").get("s").getAsString(), "the pack's own file");
        assertEquals("minecraft:entity.polar_bear.hurt", bear.getAsJsonObject("hurt").get("s").getAsString(), "bedrock's vanilla name, java's event");
        assertEquals(0.5f, bear.getAsJsonObject("death").get("v").getAsFloat(), 1e-4);
        assertEquals(2, bear.getAsJsonObject("ambient").getAsJsonArray("p").size());
        assertTrue(bear.has("fall.big"), "defaults reach every entity");
    }

    @Test
    void throwableItemsGoToTheSidecar(@TempDir Path tmp) throws IOException {
        Path bp = tmp.resolve("bp");
        Files.createDirectories(bp.resolve("items"));
        Files.writeString(bp.resolve("manifest.json"), "{\"header\":{\"name\":\"t\",\"uuid\":\"1\"},\"modules\":[{\"type\":\"data\",\"uuid\":\"2\"}]}");
        Files.writeString(bp.resolve("items/spear.json"), """
            {"minecraft:item": {"description": {"identifier": "rl:spear"}, "components": {
              "minecraft:throwable": {"do_swing_animation": true, "max_launch_power": 2.0},
              "minecraft:projectile": {"projectile_entity": "rl:thrown_spear"}}}}
            """);
        JsonObject side = BedrockTlumacz.translate(new BedrockTlumacz.Paczka("t", bp, null), tmp.resolve("out"));
        JsonObject t = side.getAsJsonObject("throwables").getAsJsonObject("rl:spear");
        assertEquals("rl:thrown_spear", t.get("entity").getAsString());
        assertEquals(3.0f, t.get("power").getAsFloat(), 1e-4);
    }

    @Test
    void spawnRulesComeAlong(@TempDir Path tmp) throws IOException {
        Path bp = tmp.resolve("bp");
        Files.createDirectories(bp.resolve("spawn_rules"));
        Files.writeString(bp.resolve("manifest.json"), "{\"header\":{\"name\":\"t\",\"uuid\":\"1\"},\"modules\":[{\"type\":\"data\",\"uuid\":\"2\"}]}");
        Files.writeString(bp.resolve("spawn_rules/wraith.json"), """
            // bedrock allows comments here
            {"format_version": "1.8.0", "minecraft:spawn_rules": {"description": {"identifier": "rl:wraith", "population_control": "monster"},
              "conditions": [{"minecraft:spawns_on_surface": {}, "minecraft:weight": {"default": 40}}]}}
            """);
        Path out = tmp.resolve("out");
        JsonObject side = BedrockTlumacz.translate(new BedrockTlumacz.Paczka("t", bp, null), out);
        assertEquals(1, side.get("spawn_rules").getAsInt());
        assertTrue(Files.readString(out.resolve("bedrock_bp/spawn_rules/wraith.json")).contains("rl:wraith"));
    }
}
