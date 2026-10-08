package com.koper.koper_lib.kodel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// oriented bone hitboxes. any bone whose name contains "hitbox" turns into an OBB
// wrapping its cubes. own system, nothing to do with mc's axis aligned box -- a
// kodel mob is usually way bigger than whatever aabb its entity type claims.
//
// takes the posed bone matrices from KodelSampler, so the boxes follow the
// animation. pass the rest pose if you only want the bind shape.
public final class KodelHitboxer {
    private static final String MARKER = "hitbox";

    private KodelHitboxer() {}

    // center plus three half axis vectors. orientation and half extents are baked
    // into the vectors, so a corner is center +- ax +- ay +- az
    public record Obb(String bone,
                      float cx, float cy, float cz,
                      float axx, float axy, float axz,
                      float ayx, float ayy, float ayz,
                      float azx, float azy, float azz) {

        public boolean contains(float x, float y, float z) {
            float dx = x - cx, dy = y - cy, dz = z - cz;
            return within(dx, dy, dz, axx, axy, axz)
                && within(dx, dy, dz, ayx, ayy, ayz)
                && within(dx, dy, dz, azx, azy, azz);
        }

        private static boolean within(float dx, float dy, float dz, float ex, float ey, float ez) {
            float len2 = ex * ex + ey * ey + ez * ez;
            if (len2 <= 1e-12f) return Math.abs(dx * ex + dy * ey + dz * ez) <= 1e-6f;
            float d = dx * ex + dy * ey + dz * ez;
            return d * d <= len2 * len2;
        }

        /// distance along the ray to the first hit, or -1 when it misses. slab test in
        /// the box's own frame
        public float raycast(float ox, float oy, float oz, float dx, float dy, float dz) {
            float near = -Float.MAX_VALUE;
            float far = Float.MAX_VALUE;
            float px = ox - cx, py = oy - cy, pz = oz - cz;
            float[][] axes = {{axx, axy, axz}, {ayx, ayy, ayz}, {azx, azy, azz}};
            for (float[] axis : axes) {
                float len = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
                if (len <= 1e-6f) continue;
                float nx = axis[0] / len, ny = axis[1] / len, nz = axis[2] / len;
                float e = px * nx + py * ny + pz * nz;
                float f = dx * nx + dy * ny + dz * nz;
                if (Math.abs(f) < 1e-9f) {
                    if (-e - len > 0 || -e + len < 0) return -1f;
                    continue;
                }
                float t1 = (-e - len) / f;
                float t2 = (-e + len) / f;
                if (t1 > t2) {
                    float swap = t1;
                    t1 = t2;
                    t2 = swap;
                }
                near = Math.max(near, t1);
                far = Math.min(far, t2);
                if (near > far || far < 0) return -1f;
            }
            return near >= 0 ? near : far >= 0 ? 0f : -1f;
        }
    }

    /// every hitbox bone of the model at the pose in {@code boneWorld}, in model
    /// space pixels. empty list when the model marks none, which is the signal to
    /// fall back to whatever box the caller had before
    public static List<Obb> of(KodelModel model, float[] boneWorld) {
        List<Obb> out = new ArrayList<>();
        if (model == null || boneWorld == null) return out;
        for (int i = 0; i < model.bones.size(); i++) {
            KodelModel.KodelBone bone = model.bones.get(i);
            if (!isHitbox(bone) || bone.cubes.isEmpty()) continue;
            int at = i * KodelSampler.MAT4_FLOATS;
            if (at + KodelSampler.MAT4_FLOATS > boneWorld.length) break;
            Obb box = boxFor(bone, boneWorld, at);
            if (box != null) out.add(box);
        }
        return out;
    }

    public static boolean isHitbox(KodelModel.KodelBone bone) {
        return bone.name != null && bone.name.toLowerCase(Locale.ROOT).contains(MARKER);
    }

    /// true when the model marks any hitbox bone at all, cubes or not
    public static boolean marksAny(KodelModel model) {
        if (model == null) return false;
        for (KodelModel.KodelBone bone : model.bones) {
            if (isHitbox(bone)) return true;
        }
        return false;
    }

    private static Obb boxFor(KodelModel.KodelBone bone, float[] world, int at) {
        // the bone's own axes, normalised. a scaled bone keeps its direction here and
        // the extents below pick the size up
        float[] ux = norm(world[at], world[at + 1], world[at + 2]);
        float[] uy = norm(world[at + 4], world[at + 5], world[at + 6]);
        float[] uz = norm(world[at + 8], world[at + 9], world[at + 10]);
        float ox = world[at + 12], oy = world[at + 13], oz = world[at + 14];

        float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, minW = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE, maxW = -Float.MAX_VALUE;
        boolean any = false;

        float[] corner = new float[3];
        for (KodelModel.KodelCube cube : bone.cubes) {
            float inflate = cube.inflate;
            float x0 = cube.origin[0] - inflate, y0 = cube.origin[1] - inflate, z0 = cube.origin[2] - inflate;
            float x1 = x0 + cube.size[0] + 2f * inflate;
            float y1 = y0 + cube.size[1] + 2f * inflate;
            float z1 = z0 + cube.size[2] + 2f * inflate;
            for (int c = 0; c < 8; c++) {
                float lx = (c & 1) != 0 ? x1 : x0;
                float ly = (c & 2) != 0 ? y1 : y0;
                float lz = (c & 4) != 0 ? z1 : z0;
                cubeLocal(cube, lx, ly, lz, corner);
                float wx = world[at] * corner[0] + world[at + 4] * corner[1] + world[at + 8] * corner[2] + ox;
                float wy = world[at + 1] * corner[0] + world[at + 5] * corner[1] + world[at + 9] * corner[2] + oy;
                float wz = world[at + 2] * corner[0] + world[at + 6] * corner[1] + world[at + 10] * corner[2] + oz;
                float dx = wx - ox, dy = wy - oy, dz = wz - oz;
                float u = dx * ux[0] + dy * ux[1] + dz * ux[2];
                float v = dx * uy[0] + dy * uy[1] + dz * uy[2];
                float w = dx * uz[0] + dy * uz[1] + dz * uz[2];
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
                minW = Math.min(minW, w); maxW = Math.max(maxW, w);
                any = true;
            }
        }
        if (!any) return null;

        float hu = (maxU - minU) * 0.5f, hv = (maxV - minV) * 0.5f, hw = (maxW - minW) * 0.5f;
        float cu = (maxU + minU) * 0.5f, cv = (maxV + minV) * 0.5f, cw = (maxW + minW) * 0.5f;
        return new Obb(bone.name,
            ox + ux[0] * cu + uy[0] * cv + uz[0] * cw,
            oy + ux[1] * cu + uy[1] * cv + uz[1] * cw,
            oz + ux[2] * cu + uy[2] * cv + uz[2] * cw,
            ux[0] * hu, ux[1] * hu, ux[2] * hu,
            uy[0] * hv, uy[1] * hv, uy[2] * hv,
            uz[0] * hw, uz[1] * hw, uz[2] * hw);
    }

    // cube pivot/rotation, same chain KodelModelRender bakes with. no inflate here,
    // the caller already grew the corner
    private static void cubeLocal(KodelModel.KodelCube cube, float x, float y, float z, float[] out) {
        float px = x - cube.pivot[0], py = y - cube.pivot[1], pz = z - cube.pivot[2];
        float qx = cube.rotation[0], qy = cube.rotation[1], qz = cube.rotation[2], qw = cube.rotation[3];
        float n = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (n > 1e-9f) {
            qx /= n; qy /= n; qz /= n; qw /= n;
        } else {
            qx = qy = qz = 0f;
            qw = 1f;
        }
        // v + 2q_v x (q_v x v + w v)
        float tx = 2f * (qy * pz - qz * py);
        float ty = 2f * (qz * px - qx * pz);
        float tz = 2f * (qx * py - qy * px);
        out[0] = px + qw * tx + (qy * tz - qz * ty) + cube.pivot[0];
        out[1] = py + qw * ty + (qz * tx - qx * tz) + cube.pivot[1];
        out[2] = pz + qw * tz + (qx * ty - qy * tx) + cube.pivot[2];
    }

    /// model space aabb around every hitbox box, for culling. null when the model
    /// marks none -- caller should keep vanilla culling then.
    /// layout: minX minY minZ maxX maxY maxZ
    public static float[] bounds(List<Obb> boxes) {
        if (boxes == null || boxes.isEmpty()) return null;
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE, mnz = Float.MAX_VALUE;
        float mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE, mxz = -Float.MAX_VALUE;
        for (Obb o : boxes) {
            for (int c = 0; c < 8; c++) {
                float sx = (c & 1) != 0 ? 1 : -1, sy = (c & 2) != 0 ? 1 : -1, sz = (c & 4) != 0 ? 1 : -1;
                float x = o.cx() + sx * o.axx() + sy * o.ayx() + sz * o.azx();
                float y = o.cy() + sx * o.axy() + sy * o.ayy() + sz * o.azy();
                float z = o.cz() + sx * o.axz() + sy * o.ayz() + sz * o.azz();
                mnx = Math.min(mnx, x); mxx = Math.max(mxx, x);
                mny = Math.min(mny, y); mxy = Math.max(mxy, y);
                mnz = Math.min(mnz, z); mxz = Math.max(mxz, z);
            }
        }
        return new float[] {mnx, mny, mnz, mxx, mxy, mxz};
    }

    /// first box the ray enters, or null. origin/direction in the same model space
    /// the boxes are in; direction does not have to be normalised
    public static Obb pick(List<Obb> boxes, float ox, float oy, float oz, float dx, float dy, float dz) {
        Obb best = null;
        float bestT = Float.MAX_VALUE;
        if (boxes == null) return null;
        for (Obb o : boxes) {
            float t = o.raycast(ox, oy, oz, dx, dy, dz);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = o;
            }
        }
        return best;
    }

    private static float[] norm(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len <= 1e-9f) return new float[] {1f, 0f, 0f};
        return new float[] {x / len, y / len, z / len};
    }
}
