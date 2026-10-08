package com.koper.koper_lib.kodel;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// converting a vanilla ModelPart tree is the harder direction, so it gets the same
// treatment the geo path got: build a real model through vanilla's own mesh pipeline,
// convert it, and compare the baked vertices against the ones the ModelPart itself
// would push. no eyeballing.
class KodelJavaModelsTest {

    // mixins are not applied in a unit test, so reach the fields directly
    private static final KodelJavaModels.Parts REFLECT = new KodelJavaModels.Parts() {
        @Override
        @SuppressWarnings("unchecked")
        public List<ModelPart.Cube> cubes(ModelPart part) {
            return (List<ModelPart.Cube>) field(part, "cubes");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, ModelPart> children(ModelPart part) {
            return (Map<String, ModelPart>) field(part, "children");
        }
    };

    private static Object field(ModelPart part, String name) {
        for (Field f : ModelPart.class.getDeclaredFields()) {
            if (!f.getName().equals(name)) continue;
            try {
                f.setAccessible(true);
                return f.get(part);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read ModelPart." + name + " in a test jvm", e);
            }
        }
        throw new AssertionError("ModelPart has no field " + name);
    }

    private static ModelPart rig() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body",
            CubeListBuilder.create().texOffs(0, 0).addBox(-4f, 0f, -2f, 8f, 12f, 4f),
            PartPose.offset(0f, 0f, 0f));
        body.addOrReplaceChild("head",
            CubeListBuilder.create().texOffs(0, 16).addBox(-4f, -8f, -4f, 8f, 8f, 8f),
            PartPose.offsetAndRotation(0f, 0f, 0f, 0.3f, -0.2f, 0.1f));
        return LayerDefinition.create(mesh, 64, 64).bakeRoot();
    }

    /// every vertex the ModelPart tree would push, in block units, at its own rest pose
    private static List<Vector3f> vanillaCloud(ModelPart root) {
        List<Vector3f> out = new ArrayList<>();
        walk(root, new Matrix4f(), out);
        return out;
    }

    private static void walk(ModelPart part, Matrix4f parent, List<Vector3f> out) {
        Matrix4f m = new Matrix4f(parent);
        m.translate(part.x / 16f, part.y / 16f, part.z / 16f);
        if (part.zRot != 0 || part.yRot != 0 || part.xRot != 0) {
            m.rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        }
        for (ModelPart.Cube cube : REFLECT.cubes(part)) {
            for (ModelPart.Polygon poly : cube.polygons) {
                for (ModelPart.Vertex v : poly.vertices()) {
                    out.add(m.transformPosition(new Vector3f(
                        v.worldX() / 16f, v.worldY() / 16f, v.worldZ() / 16f)));
                }
            }
        }
        for (ModelPart child : REFLECT.children(part).values()) walk(child, m, out);
    }

    private static List<Vector3f> kodelCloud(ModelPart root) {
        KodelModel model = KodelJavaModels.fromModelPart(root, "root", 64, 64, REFLECT);
        float[] world = new float[model.bones.size() * KodelSampler.MAT4_FLOATS];
        KodelSampler.samplePose(model, null, 0f, new float[3], new float[3], new float[3], world);
        float[] mesh = KodelModelRender.bake(model, world, null);
        List<Vector3f> out = new ArrayList<>();
        for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
            out.add(new Vector3f(mesh[i], mesh[i + 1], mesh[i + 2]));
        }
        return out;
    }

    private static float maxGap(List<Vector3f> from, List<Vector3f> to) {
        float worst = 0f;
        for (Vector3f v : from) {
            float best = Float.MAX_VALUE;
            for (Vector3f t : to) {
                best = Math.min(best, Math.max(Math.abs(t.x - v.x),
                    Math.max(Math.abs(t.y - v.y), Math.abs(t.z - v.z))));
                if (best == 0f) break;
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    @Test
    void everyBoneKeepsItsOwnName() {
        KodelModel model = KodelJavaModels.fromModelPart(rig(), "root", 64, 64, REFLECT);
        assertTrue(model.boneIndex("root") >= 0, "the root should be there");
        assertTrue(model.boneIndex("body") >= 0, "a child takes its key in the parent's map");
        assertTrue(model.boneIndex("head") >= 0);
        assertEquals(3, model.bones.size(), "three parts, three bones");
    }

    @Test
    void theHierarchyComesAcross() {
        KodelModel model = KodelJavaModels.fromModelPart(rig(), "root", 64, 64, REFLECT);
        int root = model.boneIndex("root");
        int body = model.boneIndex("body");
        int head = model.boneIndex("head");
        assertEquals(-1, model.bones.get(root).parent);
        assertEquals(root, model.bones.get(body).parent);
        assertEquals(body, model.bones.get(head).parent);
        model.validate();
    }

    @Test
    void theConvertedModelStandsWhereTheVanillaOneDid() {
        ModelPart rig = rig();
        List<Vector3f> theirs = vanillaCloud(rig);
        List<Vector3f> mine = kodelCloud(rig);

        assertTrue(!theirs.isEmpty(), "vanilla baked nothing");
        // the counts do NOT match and that is expected: a vanilla quad becomes two
        // triangles on the way in and each of those goes out as a quad with a doubled
        // corner, so kodel pushes twice the vertices over the same surface. what has
        // to hold is that the surface is the same one
        assertEquals(theirs.size() * 2, mine.size(),
            "the triangle split should exactly double the count, nothing else");
        float gap = Math.max(maxGap(mine, theirs), maxGap(theirs, mine));
        assertTrue(gap <= 1e-4f,
            "converted model sits " + gap + " blocks (" + gap * 16f + " px) off the vanilla one");
    }

    // a part rotated on more than one axis is where an euler order mistake shows
    @Test
    void aMultiAxisRotationSurvives() {
        ModelPart rig = rig();
        ModelPart body = REFLECT.children(rig).get("body");
        ModelPart head = REFLECT.children(body).get("head");
        head.xRot = 0.7f;
        head.yRot = -0.4f;
        head.zRot = 0.25f;
        body.yRot = 0.5f;

        float gap = Math.max(maxGap(kodelCloud(rig), vanillaCloud(rig)),
            maxGap(vanillaCloud(rig), kodelCloud(rig)));
        assertTrue(gap <= 1e-4f, "a multi axis pose drifted by " + gap * 16f + " px");
    }

    @Test
    void anOffsetPartLandsWhereItShould() {
        ModelPart rig = rig();
        ModelPart body = REFLECT.children(rig).get("body");
        body.x = 3f;
        body.y = -2f;
        body.z = 1.5f;

        float gap = Math.max(maxGap(kodelCloud(rig), vanillaCloud(rig)),
            maxGap(vanillaCloud(rig), kodelCloud(rig)));
        assertTrue(gap <= 1e-4f, "an offset part drifted by " + gap * 16f + " px");
    }
}
