package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a resolved {@link KodelModel} into a {@link VertexConsumer}. This is the
 * Java port of the reference {@code bake_model} in {@code kodel/python/kodel.py},
 * so a model renders the same here as it does through the Python OBJ/glTF export:
 * bone world matrix (from {@link KodelSampler}) times the cube's own bake matrix,
 * six atlas faces with rot/mirror applied, positions in pixels converted to the
 * 1/16 block space the rest of the render stack uses.
 *
 * <p>No GPU state and no model cache live here on purpose — the caller owns the
 * pose, the render type and the texture, mirroring how KGecko's render path is
 * driven.
 */
public final class KodelModelRender {
    private KodelModelRender() {}

    private static final Matrix4f IDENTITY = new Matrix4f();

    /** Face order shared with {@link KodelConverters#FACE_NAMES}: px, nx, py, ny, pz, nz. */
    private static final float[][] NORMAL = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };
    private static final float[][] U_AXIS = {
        {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {1, 0, 0},
    };
    private static final float[][] V_AXIS = {
        {0, 1, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, 1, 0},
    };
    private static final float[][] CORNER_UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    /** Per-vertex stride of {@link #bake}: x, y, z, u, v, nx, ny, nz. */
    public static final int STRIDE = 8;

    // a zero thickness cube is two coplanar faces and the depth buffer cannot pick
    // between them, so they strobe. bedrock models use these constantly as flat
    // decals. a hundredth of a block of thickness ends the fight, same figure
    // geckolib's fixZeroSizeCube uses. in pixels because that is what cubes are in
    private static final float FLAT_THICKNESS = 0.16f;

    // kender's skinned layout: x y z u v nx ny nz boneId. one float more than STRIDE
    public static final int SKIN_STRIDE = 9;

    /**
     * Bakes the BIND pose once, cube local, with a bone index on every vertex.
     *
     * <p>This is the mesh Kender wants. It uploads this once and then only pushes
     * bone matrices per frame, so the skinning happens on the GPU instead of this
     * class rebuilding every vertex of every model every frame.
     *
     * <p>Positions come out in block units, not pixels, because that is what the
     * batch and {@link #boneMatricesForKender} both work in. Bones named as hitboxes
     * contribute nothing to draw but still take their index, or the matrices would
     * line up against the wrong bones.
     */
    public static float[] bakeSkinned(KodelModel model) {
        if (model == null) return new float[0];
        FloatList out = new FloatList();
        Vector3f normal = new Vector3f();
        Matrix3f normalMat = new Matrix3f();
        for (int bi = 0; bi < model.bones.size(); bi++) {
            KodelModel.KodelBone bone = model.bones.get(bi);
            if (!bone.visible || bone.cubes.isEmpty() || KodelHitboxer.isHitbox(bone)) continue;
            for (KodelModel.KodelCube cube : bone.cubes) {
                Matrix4f cubeMat = cubeMatrix(cube);
                normalMat.set(cubeMat);
                if (Math.abs(normalMat.determinant()) > 1e-9f) normalMat.invert().transpose();
                for (int f = 0; f < 6; f++) {
                    KodelModel.KodelFace face = cube.faces[f];
                    if (face == null) continue;
                    float[][] quad = faceQuad(cube, f);
                    normal.set(NORMAL[f]);
                    flipInwardFlatNormal(cube, normal);
                    normalMat.transform(normal);
                    if (normal.lengthSquared() > 1e-12f) normal.normalize();
                    int[] order = kolejnosc(quad, cubeMat, normal);
                    for (int kk = 0; kk < 4; kk++) {
                        int k = order[kk];
                        float x = quad[k][0], y = quad[k][1], z = quad[k][2];
                        // cube pivot and rotation belong to the mesh; the bone chain
                        // does not, it lives in the gpu buffer
                        out.add((cubeMat.m00() * x + cubeMat.m10() * y + cubeMat.m20() * z + cubeMat.m30()) / 16f);
                        out.add((cubeMat.m01() * x + cubeMat.m11() * y + cubeMat.m21() * z + cubeMat.m31()) / 16f);
                        out.add((cubeMat.m02() * x + cubeMat.m12() * y + cubeMat.m22() * z + cubeMat.m32()) / 16f);
                        float[] uv = faceUv(face, f, k);
                        out.add(uv[0] / (model.texWidth > 0 ? model.texWidth : 64f));
                        out.add(uv[1] / (model.texHeight > 0 ? model.texHeight : 64f));
                        out.add(normal.x);
                        out.add(normal.y);
                        out.add(normal.z);
                        out.add(bi);
                    }
                }
            }
        }
        for (KodelModel.KodelMesh mesh : model.meshes) {
            int bone = mesh.bone >= 0 && mesh.bone < model.bones.size() ? mesh.bone : 0;
            if (model.bones.isEmpty()) break;
            if (KodelHitboxer.isHitbox(model.bones.get(bone))) continue;
            float texW = model.texWidth > 0 ? model.texWidth : 64f;
            float texH = model.texHeight > 0 ? model.texHeight : 64f;
            for (int i = 0; i + 2 < mesh.indices.length; i += 3) {
                int[] tri = {mesh.indices[i], mesh.indices[i + 1], mesh.indices[i + 2], mesh.indices[i + 2]};
                for (int k = 0; k < 4; k++) {
                    int v = tri[k];
                    if (v < 0 || v * 3 + 2 >= mesh.positions.length) continue;
                    out.add(mesh.positions[v * 3] / 16f);
                    out.add(mesh.positions[v * 3 + 1] / 16f);
                    out.add(mesh.positions[v * 3 + 2] / 16f);
                    out.add(mesh.uvs[v * 2] / texW);
                    out.add(mesh.uvs[v * 2 + 1] / texH);
                    out.add(mesh.normals[v * 3]);
                    out.add(mesh.normals[v * 3 + 1]);
                    out.add(mesh.normals[v * 3 + 2]);
                    out.add(bone);
                }
            }
        }
        return out.toArray();
    }

    /** Bones a skinned bake indexes into. Every bone counts, drawn or not. */
    public static int boneCount(KodelModel model) {
        return model == null ? 0 : model.bones.size();
    }

    /**
     * Turns the sampler's pixel space world matrices into the block space ones the
     * batch uploads. Only the translation column changes; the rotation and scale of
     * a matrix do not care what a unit is.
     */
    public static float[] boneMatricesForKender(float[] world, float[] into) {
        if (world == null) return new float[0];
        float[] out = into != null && into.length >= world.length ? into : new float[world.length];
        for (int at = 0; at + KodelSampler.MAT4_FLOATS <= world.length; at += KodelSampler.MAT4_FLOATS) {
            System.arraycopy(world, at, out, at, KodelSampler.MAT4_FLOATS);
            out[at + 12] = world[at + 12] / 16f;
            out[at + 13] = world[at + 13] / 16f;
            out[at + 14] = world[at + 14] / 16f;
        }
        return out;
    }

    /** Renders the model in model space; no extra base transform. */
    public static void render(KodelModel model, float[] boneWorld, VertexConsumer vc,
                              int light, int overlay, int color) {
        render(model, boneWorld, null, vc, light, overlay, color);
    }

    /**
     * Renders every visible bone of {@code model}. {@code boneWorld} is the flat
     * world-matrix array produced by
     * {@link KodelSampler#samplePose} — 16 column-major floats per bone.
     * {@code base} is applied before every bone (the entity's yaw/scale/position
     * snapshot); may be {@code null}.
     */
    public static void render(KodelModel model, float[] boneWorld, Matrix4f base, VertexConsumer vc,
                              int light, int overlay, int color) {
        // reuse one buffer per render thread. kapoka is 458 cubes, which is about
        // 350 KB of float per mob per frame if this allocates, and it used to
        FloatList out = SCRATCH.get();
        out.clear();
        bakeInto(model, boneWorld, base, null, out);
        float[] mesh = out.data;
        int n = out.size;
        for (int i = 0; i + STRIDE <= n; i += STRIDE) {
            vc.addVertex(mesh[i], mesh[i + 1], mesh[i + 2], color, mesh[i + 3], mesh[i + 4],
                    overlay, light, mesh[i + 5], mesh[i + 6], mesh[i + 7]);
        }
    }

    /** As {@link #render(KodelModel, float[], Matrix4f, VertexConsumer, int, int, int)}, only the named bones. */
    public static void render(KodelModel model, float[] boneWorld, Matrix4f base, java.util.Set<String> onlyBones,
                              VertexConsumer vc, int light, int overlay, int color) {
        FloatList out = SCRATCH.get();
        out.clear();
        bakeInto(model, boneWorld, base, onlyBones, out);
        float[] mesh = out.data;
        int n = out.size;
        for (int i = 0; i + STRIDE <= n; i += STRIDE) {
            vc.addVertex(mesh[i], mesh[i + 1], mesh[i + 2], color, mesh[i + 3], mesh[i + 4],
                    overlay, light, mesh[i + 5], mesh[i + 6], mesh[i + 7]);
        }
    }

    private static final ThreadLocal<FloatList> SCRATCH = ThreadLocal.withInitial(FloatList::new);

    /**
     * Bakes the posed model into a flat vertex buffer (see {@link #STRIDE}) with
     * UVs normalised to the model's texture frame. Pure and Minecraft-free, so
     * it can be cached for static geometry or unit-tested.
     */
    public static float[] bake(KodelModel model, float[] boneWorld, Matrix4f base) {
        return bake(model, boneWorld, base, null);
    }

    /**
     * As above, drawing only the named bones. Two blocks can then share one model
     * file and each show its own part of it, which is what a pack's
     * {@code "bones": ["top"]} asks for. Null draws everything.
     */
    public static float[] bake(KodelModel model, float[] boneWorld, Matrix4f base,
                               java.util.Set<String> onlyBones) {
        FloatList out = new FloatList();
        bakeInto(model, boneWorld, base, onlyBones, out);
        return out.toArray();
    }

    private static void bakeInto(KodelModel model, float[] boneWorld, Matrix4f base,
                                 java.util.Set<String> onlyBones, FloatList out) {
        float texW = model.texWidth > 0 ? model.texWidth : 64f;
        float texH = model.texHeight > 0 ? model.texHeight : 64f;
        Matrix4f baseMat = base != null ? base : IDENTITY;
        Matrix4f world = new Matrix4f();
        Matrix4f modelMat = new Matrix4f();
        Matrix4f finalMat = new Matrix4f();
        Matrix3f normalMat = new Matrix3f();
        Vector3f normal = new Vector3f();
        for (int bi = 0; bi < model.bones.size(); bi++) {
            KodelModel.KodelBone bone = model.bones.get(bi);
            // a hitbox bone is a collision volume, not geometry. kgecko hides them too
            if (!bone.visible || bone.cubes.isEmpty() || KodelHitboxer.isHitbox(bone)) continue;
            if (onlyBones != null && !onlyBones.contains(bone.name)) continue;
            setColumnMajor(world, boneWorld, bi * KodelSampler.MAT4_FLOATS);
            for (KodelModel.KodelCube cube : bone.cubes) {
                Matrix4f cubeMat = cubeMatrix(cube);
                // model geometry is authored in pixels; the pose (pivot/position)
                // shares that unit, so the whole model->block divide happens after
                // the bone+cube transform, before the entity base transform
                modelMat.set(world).mul(cubeMat);
                finalMat.set(baseMat).mul(modelMat);
                normalMat.set(finalMat);
                // flat cubes are everywhere in bedrock and make this singular ->
                // NaN normals -> black face. rotation part alone is fine without
                // non-uniform scale which is every case we actually hit
                if (Math.abs(normalMat.determinant()) > 1e-9f) normalMat.invert().transpose();
                for (int f = 0; f < 6; f++) {
                    KodelModel.KodelFace face = cube.faces[f];
                    if (face == null) continue;
                    float[][] quad = faceQuad(cube, f);
                    normal.set(NORMAL[f]);
                    flipInwardFlatNormal(cube, normal);
                    normalMat.transform(normal);
                    if (normal.lengthSquared() > 1e-12f) normal.normalize();
                    // same space the normal is in (base included), or a turned camera flips the answer
                    int[] order = kolejnosc(quad, finalMat, normal);
                    for (int kk = 0; kk < 4; kk++) {
                        int k = order[kk];
                        float x = quad[k][0];
                        float y = quad[k][1];
                        float z = quad[k][2];
                        float mx = (modelMat.m00() * x + modelMat.m10() * y + modelMat.m20() * z + modelMat.m30()) / 16f;
                        float my = (modelMat.m01() * x + modelMat.m11() * y + modelMat.m21() * z + modelMat.m31()) / 16f;
                        float mz = (modelMat.m02() * x + modelMat.m12() * y + modelMat.m22() * z + modelMat.m32()) / 16f;
                        float[] uv = faceUv(face, f, k);
                        out.add(baseMat.m00() * mx + baseMat.m10() * my + baseMat.m20() * mz + baseMat.m30());
                        out.add(baseMat.m01() * mx + baseMat.m11() * my + baseMat.m21() * mz + baseMat.m31());
                        out.add(baseMat.m02() * mx + baseMat.m12() * my + baseMat.m22() * mz + baseMat.m32());
                        out.add(uv[0] / texW);
                        out.add(uv[1] / texH);
                        out.add(normal.x);
                        out.add(normal.y);
                        out.add(normal.z);
                    }
                }
            }
        }
        appendMeshes(model, boneWorld, out, texW, texH, onlyBones);
    }

    // poly_mesh triangles. the vertex consumer upstream is quad based, so each
    // triangle goes out as a quad with its last corner doubled. that is the normal
    // trick for feeding triangles to a quad pipeline and it rasterises identically
    private static void appendMeshes(KodelModel model, float[] boneWorld, FloatList out,
                                     float texW, float texH, java.util.Set<String> onlyBones) {
        if (model.meshes.isEmpty()) return;
        Matrix4f world = new Matrix4f();
        Matrix3f normalMat = new Matrix3f();
        Vector3f normal = new Vector3f();
        for (KodelModel.KodelMesh mesh : model.meshes) {
            boolean boned = mesh.bone >= 0 && mesh.bone < model.bones.size();
            if (boned) {
                if (KodelHitboxer.isHitbox(model.bones.get(mesh.bone))) continue;
                if (onlyBones != null && !onlyBones.contains(model.bones.get(mesh.bone).name)) continue;
                setColumnMajor(world, boneWorld, mesh.bone * KodelSampler.MAT4_FLOATS);
            } else {
                world.identity();
            }
            normalMat.set(world);
            if (Math.abs(normalMat.determinant()) > 1e-9f) normalMat.invert().transpose();

            for (int i = 0; i + 2 < mesh.indices.length; i += 3) {
                int[] tri = {mesh.indices[i], mesh.indices[i + 1], mesh.indices[i + 2], mesh.indices[i + 2]};
                for (int k = 0; k < 4; k++) {
                    int v = tri[k];
                    if (v < 0 || v * 3 + 2 >= mesh.positions.length) continue;
                    float x = mesh.positions[v * 3], y = mesh.positions[v * 3 + 1], z = mesh.positions[v * 3 + 2];
                    out.add((world.m00() * x + world.m10() * y + world.m20() * z + world.m30()) / 16f);
                    out.add((world.m01() * x + world.m11() * y + world.m21() * z + world.m31()) / 16f);
                    out.add((world.m02() * x + world.m12() * y + world.m22() * z + world.m32()) / 16f);
                    out.add(mesh.uvs[v * 2] / texW);
                    out.add(mesh.uvs[v * 2 + 1] / texH);
                    normal.set(mesh.normals[v * 3], mesh.normals[v * 3 + 1], mesh.normals[v * 3 + 2]);
                    normalMat.transform(normal);
                    if (normal.lengthSquared() > 1e-12f) normal.normalize();
                    out.add(normal.x);
                    out.add(normal.y);
                    out.add(normal.z);
                }
            }
        }
    }

    /** Tiny growable float buffer — avoids pulling a fastutil dependency into this module. */
    private static final class FloatList {
        private float[] data = new float[64];
        private int size;

        void clear() {
            size = 0;
        }

        void add(float value) {
            if (size == data.length) {
                float[] grown = new float[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = value;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    // pivot, rotate, pivot back. NO inflate here on purpose, faceQuad already grows
    // the box both ways. python also translated by 2*inflate which shoved every
    // inflated cube off its own origin, armor layers floated off the body
    private static Matrix4f cubeMatrix(KodelModel.KodelCube c) {
        return new Matrix4f()
                .translation(c.pivot[0], c.pivot[1], c.pivot[2])
                .rotate(new Quaternionf(c.rotation[0], c.rotation[1], c.rotation[2], c.rotation[3]))
                .translate(-c.pivot[0], -c.pivot[1], -c.pivot[2]);
    }

    private static final int[] PROSTO = {0, 1, 2, 3}, WSPAK = {0, 3, 2, 1};

    // corners come in u,v order and four of the six face slots wind inward that way (py ny pz nz),
    // so with culling on (every attachable) tops, bottoms, fronts and backs vanished: dirt missing
    // sides, a sword with only its row edges. wind every face along its own normal instead
    private static int[] kolejnosc(float[][] q, Matrix4f m, Vector3f normal) {
        Vector3f a = m.transformPosition(new Vector3f(q[0][0], q[0][1], q[0][2]));
        Vector3f b = m.transformPosition(new Vector3f(q[1][0], q[1][1], q[1][2])).sub(a);
        Vector3f c = m.transformPosition(new Vector3f(q[2][0], q[2][1], q[2][2])).sub(a);
        return b.cross(c).dot(normal) < 0 ? WSPAK : PROSTO;
    }

    /** Four box corners for a face, bone-local, in u,v order. */
    private static float[][] faceQuad(KodelModel.KodelCube c, int face) {
        float[] normal = NORMAL[face];
        float[] uAxis = U_AXIS[face];
        float[] vAxis = V_AXIS[face];
        float inflate = c.inflate;
        float[] origin = {c.origin[0] - inflate, c.origin[1] - inflate, c.origin[2] - inflate};
        float[] sh = {c.size[0] + 2f * inflate, c.size[1] + 2f * inflate, c.size[2] + 2f * inflate};
        for (int ax = 0; ax < 3; ax++) {
            if (c.size[ax] == 0f) sh[ax] += FLAT_THICKNESS;
        }
        float[] base = {origin[0], origin[1], origin[2]};
        for (int ax = 0; ax < 3; ax++) {
            if (normal[ax] > 0 || uAxis[ax] < 0 || vAxis[ax] < 0) base[ax] += sh[ax];
        }
        // the face has to span the box it actually belongs to, so measure it against
        // sh and not the raw size. using the raw size left every inflated cube's
        // faces short by 2*inflate, which is the sub-pixel gap against kgecko that
        // was written off as "inflate handling", and left a flat cube's sliver faces
        // with no extent at all
        float uLen = Math.abs(dot(sh, uAxis));
        float vLen = Math.abs(dot(sh, vAxis));
        float[][] out = new float[4][3];
        for (int k = 0; k < 4; k++) {
            float uu = CORNER_UV[k][0];
            float vv = CORNER_UV[k][1];
            for (int ax = 0; ax < 3; ax++) {
                out[k][ax] = base[ax] + uAxis[ax] * uLen * uu + vAxis[ax] * vLen * vv;
            }
        }
        return out;
    }

    // both halves of a flat cube face the same way once it has thickness, so the one
    // that used to point into the sliver ends up lit from inside and reads as a hole.
    // geckolib does this in RenderUtil.fixInvertedFlatCube
    private static void flipInwardFlatNormal(KodelModel.KodelCube c, Vector3f normal) {
        if (normal.x < 0 && (c.size[1] == 0f || c.size[2] == 0f)) normal.mul(-1, 1, 1);
        if (normal.y < 0 && (c.size[0] == 0f || c.size[2] == 0f)) normal.mul(1, -1, 1);
        if (normal.z < 0 && (c.size[0] == 0f || c.size[1] == 0f)) normal.mul(1, 1, -1);
    }

    private static float[] faceUv(KodelModel.KodelFace face, int f, int k) {
        float sx = CORNER_UV[k][0];
        float sy = CORNER_UV[k][1];
        // kodel atlas UVs are top-down and kodel's +u runs opposite to the
        // texture on the top/bottom faces; align with GeckoLib's ofBoxUv.
        if (f >= 2) sx = 1f - sx;
        sy = 1f - sy;
        int rot = ((face.rot % 4) + 4) % 4;
        for (int r = 0; r < rot; r++) {
            float ns = sy;
            sy = 1f - sx;
            sx = ns;
        }
        if (face.mirror) sx = 1f - sx;
        return new float[] {face.u + sx * face.uw, face.v + sy * face.vh};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Loads the 16 floats at {@code at} into {@code out}. This is the exact
     * inverse of {@code Matrix4f.get(float[], offset)} (both in column-major
     * field order), so it reconstructs what {@link KodelSampler} wrote.
     */
    private static void setColumnMajor(Matrix4f out, float[] m, int at) {
        out.set(m[at + 0], m[at + 1], m[at + 2], m[at + 3],
                m[at + 4], m[at + 5], m[at + 6], m[at + 7],
                m[at + 8], m[at + 9], m[at + 10], m[at + 11],
                m[at + 12], m[at + 13], m[at + 14], m[at + 15]);
    }
}
