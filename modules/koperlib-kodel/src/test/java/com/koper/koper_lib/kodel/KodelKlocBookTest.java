package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KoperPackSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// packs already say which block draws which model, in the "kender" object of their
// block json. these are real shapes lifted from mechanics_dream, not invented ones
class KodelKlocBookTest {

    @TempDir
    Path root;

    private Path pack;

    @BeforeEach
    void makePack() throws Exception {
        pack = Files.createDirectories(root.resolve("mechanics_dream"));
        Files.createDirectories(pack.resolve("kodel"));
        try (InputStream in = KodelKlocBookTest.class.getResourceAsStream("/kapoka.kodel")) {
            assertNotNull(in);
            Files.write(pack.resolve("kodel").resolve("wheel.kodel"), in.readAllBytes());
        }
        KoperPackSources.register("kodel-test", root, name -> true);
        KodelBook.clear();
        KodelKlocBook.clear();
    }

    @AfterEach
    void wipe() {
        KodelBook.clear();
        KodelKlocBook.clear();
    }

    private void block(String dir, String file, String json) throws Exception {
        Path d = Files.createDirectories(pack.resolve(dir));
        Files.writeString(d.resolve(file), json, StandardCharsets.UTF_8);
        KodelKlocBook.clear();
    }

    private static final String WHEEL = """
        {"id":"mechanics_dream:wheel","type":"block","name":"Wheel",
         "states":["facing","lit"],
         "kender":{"model":"wheel","texture":"wheel","tier":"koperblock",
                   "render_type":"cutout","rotate_by":"facing","rotate_by_base":"north",
                   "offset":[0,2,0],"hitbox":"model","bones":["bone"]}}
        """;

    @Test
    void readsTheKenderObjectOffARealBlock() throws Exception {
        block("koperlib/blocks", "wheel.json", WHEEL);
        KodelKlocBook.Wiazanie bind = KodelKlocBook.of("mechanics_dream:wheel");
        assertNotNull(bind, "the block should have been bound");
        assertEquals("wheel", bind.model());
        assertEquals("facing", bind.rotateBy());
        assertEquals("north", bind.rotateBase());
        assertEquals("cutout", bind.renderType());
        assertEquals(2f, bind.offset()[1], 1e-6f, "offset is in pixels and must survive");
        assertNotNull(bind.bones());
        assertTrue(bind.bones().contains("bone"), "only these bones draw");
    }

    // the flat layout and the older nested one both still ship
    @Test
    void bothPackLayoutsAreFound() throws Exception {
        block("blocks", "wheel.json", WHEEL);
        assertNotNull(KodelKlocBook.of("mechanics_dream:wheel"), "flat blocks/ should be read");
        KodelKlocBook.clear();
        Files.delete(pack.resolve("blocks").resolve("wheel.json"));
        block("koperlib/blocks", "wheel.json", WHEEL);
        assertNotNull(KodelKlocBook.of("mechanics_dream:wheel"), "nested koperlib/blocks/ too");
    }

    // a block whose model has no .kodel belongs to whatever drew it before
    @Test
    void aBlockWithNoKodelIsLeftAlone() throws Exception {
        block("koperlib/blocks", "engine.json", """
            {"id":"mechanics_dream:gas_engine","type":"block",
             "kender":{"model":"gas_engine","texture":"gas_engine"}}
            """);
        assertNull(KodelKlocBook.of("mechanics_dream:gas_engine"));
    }

    @Test
    void aBlockWithNoKenderObjectIsNotOurs() throws Exception {
        block("koperlib/blocks", "plain.json", """
            {"id":"mechanics_dream:scrap_metal","type":"block","hardness":1.0}
            """);
        assertNull(KodelKlocBook.of("mechanics_dream:scrap_metal"));
    }

    @Test
    void textureResolvesIntoThePacksNamespace() throws Exception {
        block("koperlib/blocks", "wheel.json", WHEEL);
        var bind = KodelKlocBook.of("mechanics_dream:wheel");
        assertEquals("mechanics_dream", bind.texture().getNamespace());
        assertEquals("textures/wheel.png", bind.texture().getPath());
    }

    @Test
    void anExplicitNamespaceOnTheTextureWins() throws Exception {
        block("koperlib/blocks", "wheel.json", WHEEL.replace('"' + "texture\":\"wheel\"",
            "\"texture\":\"minecraft:block/stone\""));
        var bind = KodelKlocBook.of("mechanics_dream:wheel");
        assertEquals("minecraft", bind.texture().getNamespace());
        assertEquals("textures/block/stone.png", bind.texture().getPath());
    }

    // a broken file must cost only itself
    @Test
    void oneMalformedBlockDoesNotLoseTheOthers() throws Exception {
        block("koperlib/blocks", "wheel.json", WHEEL);
        block("koperlib/blocks", "broken.json", "{ this is not json");
        assertNotNull(KodelKlocBook.of("mechanics_dream:wheel"),
            "a neighbouring bad file should not have taken this one down");
    }

    @Test
    void defaultsAreSaneWhenTheKenderObjectIsSparse() throws Exception {
        block("koperlib/blocks", "wheel.json", """
            {"id":"mechanics_dream:wheel","kender":{"model":"wheel"}}
            """);
        var bind = KodelKlocBook.of("mechanics_dream:wheel");
        assertNotNull(bind);
        assertEquals(1f, bind.scale(), 1e-6f);
        assertEquals("facing", bind.rotateBy(), "no rotate_by turns with facing, as the block json has always meant");
        assertNull(bind.bones(), "no bones list means draw all of them");
        assertEquals("textures/wheel.png", bind.texture().getPath(), "texture falls back to the model name");
    }

    @Test
    void rotateByFalseAndHorizontalFacingReadLikeKgecko() throws Exception {
        block("koperlib/blocks", "lift_top.json", """
            {"id":"mechanics_dream:lift_top","kender":{"model":"wheel","rotate_by":false}}
            """);
        block("koperlib/blocks", "lift.json", """
            {"id":"mechanics_dream:lift","kender":{"model":"wheel","rotate_by":"horizontal_facing"}}
            """);
        assertEquals("", KodelKlocBook.of("mechanics_dream:lift_top").rotateBy());
        assertEquals("facing", KodelKlocBook.of("mechanics_dream:lift").rotateBy());
    }
}
