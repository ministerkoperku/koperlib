package com.koper.koper_lib.kodel;

import com.koper.koper_lib.kodel.mixin.KodelModelPartAccessor;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a live vanilla {@link ModelPart} tree (including kopermod's
 * embedded Java models) into a .kodel model. Each part becomes one bone
 * (rest pose from the public x/y/z + xRot/yRot/zRot fields, mirroring
 * VanillaEntityKender's baking), and every cube is exported as an exact
 * triangle mesh with the original per-vertex UVs and normals, so nothing is
 * lost to cube reconstruction. Client-side transformer; call it from a mod
 * menu / CLI when packing built-ins to .kodel.
 */
public final class KodelJavaModels {
    // client thread only, like everything else here
    private static final Quaternionf SCRATCH = new Quaternionf();

    private KodelJavaModels() {}

    // how to get at a part's cubes and children. normally the mixin accessor, but a
    // test has no mixins applied and this conversion is the half worth checking
    public interface Parts {
        List<ModelPart.Cube> cubes(ModelPart part);

        Map<String, ModelPart> children(ModelPart part);
    }

    private static final Parts MIXIN = new Parts() {
        @Override
        public List<ModelPart.Cube> cubes(ModelPart part) {
            return ((KodelModelPartAccessor) (Object) part).kodel$cubes();
        }

        @Override
        public Map<String, ModelPart> children(ModelPart part) {
            return ((KodelModelPartAccessor) (Object) part).kodel$children();
        }
    };

    public static KodelModel fromModelPart(ModelPart root, String name, int texWidth, int texHeight) {
        return fromModelPart(root, name, texWidth, texHeight, MIXIN);
    }

    public static KodelModel fromModelPart(ModelPart root, String name, int texWidth, int texHeight,
                                           Parts parts) {
        KodelModel m = new KodelModel();
        m.texWidth = texWidth > 0 ? texWidth : 64;
        m.texHeight = texHeight > 0 ? texHeight : 64;
        walk(root, -1, name, new HashSet<>(), m, parts);
        return m;
    }

    // one bone per part, named after its key in the parent's child map. vanilla
    // calls those head / left_arm etc which is exactly what a track binds to.
    // old version gave EVERY bone the same name so boneIndex always said 0 lol
    private static void walk(ModelPart part, int parent, String name, Set<String> taken, KodelModel m,
                             Parts parts) {
        int boneIndex = m.bones.size();
        KodelModel.KodelBone bone = new KodelModel.KodelBone();
        bone.name = unique(name, taken);
        bone.parent = parent;
        bone.position[0] = part.x;
        bone.position[1] = part.y;
        bone.position[2] = part.z;
        // ZYX, same order ModelPart rotates in and the one KodelSampler reads back.
        // joml rotationXYZ composes the other way and skewed every multi-axis part
        KodelSampler.quatFromEuler(part.xRot, part.yRot, part.zRot, SCRATCH);
        bone.rotation[0] = SCRATCH.x();
        bone.rotation[1] = SCRATCH.y();
        bone.rotation[2] = SCRATCH.z();
        bone.rotation[3] = SCRATCH.w();
        bone.visible = part.visible;
        m.bones.add(bone);

        for (ModelPart.Cube cube : parts.cubes(part)) {
            m.meshes.add(meshOf(cube, boneIndex));
        }
        for (Map.Entry<String, ModelPart> child : parts.children(part).entrySet()) {
            walk(child.getValue(), boneIndex, child.getKey(), taken, m, parts);
        }
    }

    private static String unique(String name, Set<String> taken) {
        String base = name == null || name.isBlank() ? "bone" : name;
        if (taken.add(base)) return base;
        for (int i = 1; ; i++) {
            String candidate = base + "_" + i;
            if (taken.add(candidate)) return candidate;
        }
    }

    private static KodelModel.KodelMesh meshOf(ModelPart.Cube cube, int bone) {
        int triCount = 0;
        for (ModelPart.Polygon p : cube.polygons) triCount += p.vertices().length - 2;
        KodelModel.KodelMesh out = new KodelModel.KodelMesh();
        out.bone = bone;
        int vc = triCount * 3;
        out.positions = new float[vc * 3];
        out.uvs = new float[vc * 2];
        out.normals = new float[vc * 3];
        out.indices = new int[vc];
        int v = 0;
        int ii = 0;
        for (ModelPart.Polygon p : cube.polygons) {
            ModelPart.Vertex[] verts = p.vertices();
            float nx = p.normal().x(), ny = p.normal().y(), nz = p.normal().z();
            for (int a = 0; a < verts.length - 2; a++) {
                put(verts[0], nx, ny, nz, out, v++);
                put(verts[a + 1], nx, ny, nz, out, v++);
                put(verts[a + 2], nx, ny, nz, out, v++);
                int tri = (v / 3) - 1;
                out.indices[ii++] = tri * 3;
                out.indices[ii++] = tri * 3 + 1;
                out.indices[ii++] = tri * 3 + 2;
            }
        }
        return out;
    }

    private static void put(ModelPart.Vertex vertex, float nx, float ny, float nz,
                            KodelModel.KodelMesh out, int at) {
        out.positions[at * 3] = vertex.worldX();
        out.positions[at * 3 + 1] = vertex.worldY();
        out.positions[at * 3 + 2] = vertex.worldZ();
        out.uvs[at * 2] = vertex.u();
        out.uvs[at * 2 + 1] = vertex.v();
        out.normals[at * 3] = nx;
        out.normals[at * 3 + 1] = ny;
        out.normals[at * 3 + 2] = nz;
    }

}