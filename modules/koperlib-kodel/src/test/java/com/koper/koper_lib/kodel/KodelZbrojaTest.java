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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// worn armour: the pack's item json says which model and which bone follows which
// limb, and every bone moves by the amount its limb moved away from rest
class KodelZbrojaTest {

    @TempDir
    Path root;

    private Path pack;

    // lifted from kopermod's fine_armor_helmet.json, keys and all
    private static final String HELMET = """
        {"id":"koper_mod_fabric:fine_armor_helmet","type":"helmet","slot":"head",
         "model":"fine_armor","armor_texture":"fine_armor",
         "armor_bones":{"bipedHead":"head","bipedBody":"body","bipedRightArm":"right_arm",
                        "bipedLeftArm":"left_arm","bipedRightLeg":"right_leg",
                        "bipedLeftLeg":"left_leg","armorHead":"none"}}
        """;

    @BeforeEach
    void makePack() throws Exception {
        pack = Files.createDirectories(root.resolve("koper_mod_content"));
        Files.createDirectories(pack.resolve("kodel"));
        try (InputStream in = KodelZbrojaTest.class.getResourceAsStream("/kapoka.kodel")) {
            assertNotNull(in);
            Files.write(pack.resolve("kodel").resolve("fine_armor.kodel"), in.readAllBytes());
        }
        KoperPackSources.register("zbroja-test", root, name -> true);
        KodelBook.clear();
        KodelZbrojaBook.clear();
    }

    @AfterEach
    void wipe() {
        KodelBook.clear();
        KodelZbrojaBook.clear();
    }

    private void item(String file, String json) throws Exception {
        Path d = Files.createDirectories(pack.resolve("items"));
        Files.writeString(d.resolve(file), json, StandardCharsets.UTF_8);
        KodelZbrojaBook.clear();
    }

    @Test
    void readsARealArmourItem() throws Exception {
        item("fine_armor_helmet.json", HELMET);
        KodelZbrojaBook.Zbroja bind = KodelZbrojaBook.of("koper_mod_fabric:fine_armor_helmet");
        assertNotNull(bind, "the helmet should have been bound");
        assertEquals("head", bind.slot());
        assertEquals("fine_armor", bind.model());
        assertEquals("koper_mod_fabric", bind.texture().getNamespace());
        // the real pack keeps it in textures/entity/, which is where a bare name points
        assertEquals("textures/entity/fine_armor.png", bind.texture().getPath());
        assertEquals("head", bind.bones().get("bipedHead"));
        assertEquals("none", bind.bones().get("armorHead"), "a bone pinned to nothing must survive the read");
        assertEquals(1f, bind.scale(), 1e-6f);
    }

    // the slot can come from the type when the json does not spell it out
    @Test
    void theSlotFallsOutOfTheItemType() throws Exception {
        item("boots.json", """
            {"id":"koper_mod_fabric:fine_armor_boots","type":"boots","model":"fine_armor"}
            """);
        assertEquals("feet", KodelZbrojaBook.of("koper_mod_fabric:fine_armor_boots").slot());
    }

    @Test
    void anItemWithNoKodelIsLeftToWhateverDrewIt() throws Exception {
        item("other.json", """
            {"id":"koper_mod_fabric:iron_helmet","type":"helmet","model":"no_such_model"}
            """);
        assertNull(KodelZbrojaBook.of("koper_mod_fabric:iron_helmet"));
    }

    @Test
    void aPlainItemIsNotArmour() throws Exception {
        item("sword.json", """
            {"id":"koper_mod_fabric:flame_sword","type":"sword","damage":7}
            """);
        assertNull(KodelZbrojaBook.of("koper_mod_fabric:flame_sword"));
    }

    // the posing half, which is pure maths and the part that can actually be checked
    private static KodelModel twoBones() {
        KodelModel m = new KodelModel();
        for (String name : new String[] {"bipedBody", "bipedHead"}) {
            KodelModel.KodelBone b = new KodelModel.KodelBone();
            b.name = name;
            b.parent = -1;
            m.bones.add(b);
        }
        return m;
    }

    @Test
    void noMovementLeavesTheArmourExactlyWhereItWasAuthored() {
        KodelModel m = twoBones();
        float[] bind = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], bind);

        float[] followed = new float[bind.length];
        KodelSampler.samplePoseFollowing(m, bone -> new float[6], followed);
        assertArrayEquals(bind, followed, 1e-6f,
            "a limb at rest means a zero delta, and the armour must not drift");
    }

    @Test
    void aMovedLimbMovesItsBoneAndOnlyItsBone() {
        KodelModel m = twoBones();
        float[] pose = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePoseFollowing(m,
            bone -> bone.equals("bipedHead") ? new float[] {0, 0, 0, 0, 5, 0} : new float[6], pose);

        int head = m.boneIndex("bipedHead") * KodelSampler.MAT4_FLOATS;
        int body = m.boneIndex("bipedBody") * KodelSampler.MAT4_FLOATS;
        assertEquals(5f, pose[head + 13], 1e-5f, "the head bone should have risen five pixels");
        assertEquals(0f, pose[body + 13], 1e-5f, "the body bone was not asked to move");
    }

    @Test
    void aRotatedLimbTurnsItsBone() {
        KodelModel m = twoBones();
        float[] pose = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePoseFollowing(m,
            bone -> bone.equals("bipedHead")
                ? new float[] {(float) Math.PI / 2f, 0, 0, 0, 0, 0} : new float[6], pose);

        // a quarter turn about x sends the y axis onto z
        int head = m.boneIndex("bipedHead") * KodelSampler.MAT4_FLOATS;
        assertEquals(0f, pose[head + 5], 1e-4f, "y axis should have left y");
        assertEquals(1f, Math.abs(pose[head + 6]), 1e-4f, "and landed on z");
    }

    @Test
    void aNullDeltaIsTreatedAsNoMovement() {
        KodelModel m = twoBones();
        float[] bind = new float[m.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(m, null, 0f, new float[3], new float[3], new float[3], bind);
        float[] pose = new float[bind.length];
        KodelSampler.samplePoseFollowing(m, bone -> null, pose);
        assertArrayEquals(bind, pose, 1e-6f);
        for (float f : pose) assertTrue(!Float.isNaN(f), "a null delta must not produce NaN");
    }
}
