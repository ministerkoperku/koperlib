package com.koper.koper_lib.kodel;

import java.util.ArrayList;
import java.util.List;

import static com.koper.koper_lib.kodel.KodelFormat.NO_BONE;

/**
 * In-memory .kodel model: bone hierarchy, box cubes with per-face UV, triangle
 * meshes. Mirrors the binary layout in kodel/SPEC.md, all positions in pixels,
 * Y up, X right, +Z toward the viewer.
 */
public final class KodelModel {
    public int texWidth = 64;
    public int texHeight = 64;
    public final List<KodelBone> bones = new ArrayList<>();
    public final List<KodelMesh> meshes = new ArrayList<>();

    public static final class KodelBone {
        public String name;
        public int parent = -1;
        public final float[] pivot = new float[3];
        public final float[] position = new float[3];
        // identity, not four zeros. a zero quaternion normalises to NaN and takes the
        // whole model off screen; the cube next door always had this right
        public final float[] rotation = new float[] {0f, 0f, 0f, 1f}; // quat xyzw
        public final float[] scale = new float[] {1f, 1f, 1f};
        public boolean visible = true;
        public final List<KodelCube> cubes = new ArrayList<>();
    }

    public static final class KodelCube {
        public final float[] origin = new float[3];
        public final float[] size = new float[] {1f, 1f, 1f};
        public float inflate;
        public final float[] pivot = new float[3];
        public final float[] rotation = new float[] {0f, 0f, 0f, 1f};
        public boolean mirror;
        public final KodelFace[] faces = new KodelFace[6];
    }

    public static final class KodelFace {
        public float u, v, uw, vh;
        public int rot;
        public boolean mirror;
    }

    /** Triangle list, flat arrays for cache friendliness. bone == NO_BONE for world space. */
    public static final class KodelMesh {
        public int bone = (int) NO_BONE;
        public float[] positions = new float[0];
        public float[] uvs = new float[0];
        public float[] normals = new float[0];
        public int[] indices = new int[0];
        public int vertexCount() {
            return positions.length / 3;
        }
    }

    // smallest on-disk size of each record, used to reject impossible counts
    private static final int BONE_MIN_BYTES = 63;
    private static final int CUBE_MIN_BYTES = 165;
    private static final int MESH_MIN_BYTES = 12;
    private static final int VERTEX_BYTES = 32;

    // sampler composes bone i against its parent's already written matrix, so a
    // bad or forward parent index blows up mid-render. catch it at load instead
    public void validate() {
        for (int i = 0; i < bones.size(); i++) {
            int parent = bones.get(i).parent;
            if (parent == -1) continue;
            if (parent < -1 || parent >= bones.size()) {
                throw new KodelFormat.Corruption(
                    "bone " + i + " (" + bones.get(i).name + ") has parent " + parent
                        + ", outside 0.." + (bones.size() - 1));
            }
            if (parent >= i) {
                throw new KodelFormat.Corruption(
                    "bone " + i + " (" + bones.get(i).name + ") has parent " + parent
                        + "; model.bin requires a parent to come first");
            }
        }
        for (KodelMesh mesh : meshes) {
            if (mesh.bone == (int) NO_BONE || mesh.bone == -1) continue;
            if (mesh.bone < 0 || mesh.bone >= bones.size()) {
                throw new KodelFormat.Corruption("mesh bound to bone " + mesh.bone
                    + ", outside 0.." + (bones.size() - 1));
            }
        }
    }

    public KodelBone bone(String name) {
        for (KodelBone b : bones) {
            if (b.name.equals(name)) return b;
        }
        return null;
    }

    public int boneIndex(String name) {
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    public static KodelModel read(byte[] data) {
        KodelFormat.Reader r = new KodelFormat.Reader(data);
        KodelFormat.header(r);
        KodelModel m = new KodelModel();
        int boneCount = r.count(BONE_MIN_BYTES, "bones");
        for (int i = 0; i < boneCount; i++) {
            KodelBone b = new KodelBone();
            b.name = r.string();
            b.parent = r.i32();
            r.vec3(b.pivot, 0);
            r.vec3(b.position, 0);
            r.quat(b.rotation, 0);
            r.vec3(b.scale, 0);
            b.visible = r.u8() != 0;
            int cubeCount = r.count(CUBE_MIN_BYTES, "cubes");
            for (int ci = 0; ci < cubeCount; ci++) {
                KodelCube c = new KodelCube();
                r.vec3(c.origin, 0);
                r.vec3(c.size, 0);
                c.inflate = r.f32();
                r.vec3(c.pivot, 0);
                r.quat(c.rotation, 0);
                c.mirror = r.u8() != 0;
                for (int f = 0; f < 6; f++) {
                    KodelFace face = new KodelFace();
                    face.u = r.f32();
                    face.v = r.f32();
                    face.uw = r.f32();
                    face.vh = r.f32();
                    face.rot = r.u8();
                    face.mirror = r.u8() != 0;
                    c.faces[f] = face;
                }
                b.cubes.add(c);
            }
            m.bones.add(b);
        }
        int meshCount = r.count(MESH_MIN_BYTES, "meshes");
        for (int i = 0; i < meshCount; i++) {
            KodelMesh mesh = new KodelMesh();
            mesh.bone = r.i32();
            int vc = r.count(VERTEX_BYTES, "vertices");
            mesh.positions = new float[vc * 3];
            mesh.uvs = new float[vc * 2];
            mesh.normals = new float[vc * 3];
            for (int v = 0; v < vc; v++) {
                r.vec3(mesh.positions, v * 3);
                mesh.uvs[v * 2] = r.f32();
                mesh.uvs[v * 2 + 1] = r.f32();
                r.vec3(mesh.normals, v * 3);
            }
            int ic = r.count(4, "indices");
            mesh.indices = new int[ic];
            for (int ii = 0; ii < ic; ii++) {
                mesh.indices[ii] = (int) r.u32();
            }
            m.meshes.add(mesh);
        }
        if (r.u32() != KodelFormat.ENDK) throw new KodelFormat.Corruption("model.bin corrupt (no ENDK)");
        m.validate();
        return m;
    }

    public byte[] write() {
        KodelFormat.Writer w = new KodelFormat.Writer();
        KodelFormat.header(w);
        w.u32(bones.size());
        for (KodelBone b : bones) {
            w.string(b.name);
            w.i32(b.parent);
            w.vec3(b.pivot, 0);
            w.vec3(b.position, 0);
            w.quat(b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3]);
            w.vec3(b.scale, 0);
            w.u8(b.visible ? 1 : 0);
            w.u32(b.cubes.size());
            for (KodelCube c : b.cubes) {
                w.vec3(c.origin, 0);
                w.vec3(c.size, 0);
                w.f32(c.inflate);
                w.vec3(c.pivot, 0);
                w.quat(c.rotation[0], c.rotation[1], c.rotation[2], c.rotation[3]);
                w.u8(c.mirror ? 1 : 0);
                for (int f = 0; f < 6; f++) {
                    KodelFace face = c.faces[f] != null ? c.faces[f] : new KodelFace();
                    w.f32(face.u);
                    w.f32(face.v);
                    w.f32(face.uw);
                    w.f32(face.vh);
                    w.u8(face.rot & 3);
                    w.u8(face.mirror ? 1 : 0);
                }
            }
        }
        w.u32(meshes.size());
        for (KodelMesh mesh : meshes) {
            w.i32(mesh.bone);
            int vc = mesh.vertexCount();
            w.u32(vc);
            for (int v = 0; v < vc; v++) {
                int p = v * 3;
                w.vec3(mesh.positions[p], mesh.positions[p + 1], mesh.positions[p + 2]);
                w.f32(mesh.uvs[v * 2]);
                w.f32(mesh.uvs[v * 2 + 1]);
                w.vec3(mesh.normals[p], mesh.normals[p + 1], mesh.normals[p + 2]);
            }
            w.u32(mesh.indices.length);
            for (int idx : mesh.indices) {
                w.u32(idx);
            }
        }
        w.u32(KodelFormat.ENDK);
        return w.bytes();
    }
}