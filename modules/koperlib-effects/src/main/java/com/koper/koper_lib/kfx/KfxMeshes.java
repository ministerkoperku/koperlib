package com.koper.koper_lib.kfx;

import java.util.ArrayList;
import java.util.List;

// unit meshes for the 3D particle styles, triangle lists of (x,y,z, nx,ny,nz). the Kender particle
// pipeline builds the same shapes in engine/koperlib-kender/src/vk/meshes.rs; keep the two in step
public final class KfxMeshes {
    public static final int FLOATS_PER_VERTEX = 6;
    private static final float[][] BY_STYLE = new float[9][];
    private static final float[] ORIGIN = {0, 0, 0};

    static {
        BY_STYLE[0] = new float[0];
        BY_STYLE[1] = scaled(bipyramid(6, 1.0f, 1.0f), 0.22f, 2.6f, 0.22f);
        BY_STYLE[2] = star();
        BY_STYLE[3] = torus(14, 6, 1.0f, 0.2f);
        BY_STYLE[4] = scaled(bipyramid(6, 1.0f, 1.0f), 0.5f, 1.9f, 0.5f);
        BY_STYLE[5] = cube();
        BY_STYLE[6] = tetra();
        BY_STYLE[7] = icosphere(1);
        BY_STYLE[8] = bipyramid(4, 1.0f, 1.25f);
    }

    private KfxMeshes() {}

    // null for styles that are not a mesh (sprite, addon styles)
    public static float[] forStyle(int style) {
        if (style < 1 || style >= BY_STYLE.length) return null;
        return BY_STYLE[style];
    }

    private static final class Tris {
        final List<float[]> v = new ArrayList<>();

        void flat(float[] a, float[] b, float[] c) {
            flat(a, b, c, ORIGIN);
        }

        // normal faces away from `inside`
        void flat(float[] a, float[] b, float[] c, float[] inside) {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float ox = a[0] + b[0] + c[0] - 3 * inside[0], oy = a[1] + b[1] + c[1] - 3 * inside[1];
            float oz = a[2] + b[2] + c[2] - 3 * inside[2];
            if (nx * ox + ny * oy + nz * oz < 0) {
                nx = -nx; ny = -ny; nz = -nz;
            }
            float l = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1.0e-9f) l = 1;
            for (float[] p : new float[][] {a, b, c}) v.add(new float[] {p[0], p[1], p[2], nx / l, ny / l, nz / l});
        }

        void smooth(float[] a, float[] na, float[] b, float[] nb, float[] c, float[] nc) {
            v.add(new float[] {a[0], a[1], a[2], na[0], na[1], na[2]});
            v.add(new float[] {b[0], b[1], b[2], nb[0], nb[1], nb[2]});
            v.add(new float[] {c[0], c[1], c[2], nc[0], nc[1], nc[2]});
        }

        float[] build() {
            float[] out = new float[v.size() * FLOATS_PER_VERTEX];
            for (int i = 0; i < v.size(); i++) System.arraycopy(v.get(i), 0, out, i * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            return out;
        }
    }

    // n-sided double pyramid, tips on the y axis. 4 sides = octahedron
    static float[] bipyramid(int sides, float radius, float tip) {
        Tris t = new Tris();
        float[] top = {0, tip, 0}, bottom = {0, -tip, 0};
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
            float[] p0 = {(float)Math.cos(a0) * radius, 0, (float)Math.sin(a0) * radius};
            float[] p1 = {(float)Math.cos(a1) * radius, 0, (float)Math.sin(a1) * radius};
            t.flat(top, p0, p1);
            t.flat(bottom, p1, p0);
        }
        return t.build();
    }

    static float[] cube() {
        Tris t = new Tris();
        float a = 0.8f;
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) c[i] = new float[] {(i & 4) != 0 ? a : -a, (i & 2) != 0 ? a : -a, (i & 1) != 0 ? a : -a};
        for (int[] f : faces) {
            t.flat(c[f[0]], c[f[1]], c[f[2]]);
            t.flat(c[f[0]], c[f[2]], c[f[3]]);
        }
        return t.build();
    }

    static float[] tetra() {
        Tris t = new Tris();
        float s = 1.05f;
        float[] p0 = {s, s, s}, p1 = {s, -s, -s}, p2 = {-s, s, -s}, p3 = {-s, -s, s};
        t.flat(p0, p1, p2);
        t.flat(p0, p3, p1);
        t.flat(p0, p2, p3);
        t.flat(p1, p3, p2);
        return t.build();
    }

    // six thin spikes on the axes around a small octahedron core
    static float[] star() {
        Tris t = new Tris();
        float len = 2.1f, base = 0.24f;
        float[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] ax : axes) {
            float[] u = Math.abs(ax[1]) > 0.5f ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
            float[] w = {ax[1] * u[2] - ax[2] * u[1], ax[2] * u[0] - ax[0] * u[2], ax[0] * u[1] - ax[1] * u[0]};
            float[] tip = {ax[0] * len, ax[1] * len, ax[2] * len};
            float[][] ring = new float[4][];
            for (int i = 0; i < 4; i++) {
                double a = Math.PI * 0.5 * i;
                float cu = (float)Math.cos(a) * base, cw = (float)Math.sin(a) * base;
                ring[i] = new float[] {u[0] * cu + w[0] * cw, u[1] * cu + w[1] * cw, u[2] * cu + w[2] * cw};
            }
            float[] axis = {tip[0] / 3, tip[1] / 3, tip[2] / 3};
            for (int i = 0; i < 4; i++) t.flat(tip, ring[i], ring[(i + 1) & 3], axis);
        }
        float[] core = bipyramid(4, 0.55f, 0.55f);
        float[] spikes = t.build();
        float[] out = new float[spikes.length + core.length];
        System.arraycopy(spikes, 0, out, 0, spikes.length);
        System.arraycopy(core, 0, out, spikes.length, core.length);
        return out;
    }

    // ring in the xz plane, smooth normals
    static float[] torus(int major, int minor, float radius, float tube) {
        Tris t = new Tris();
        for (int i = 0; i < major; i++) {
            for (int j = 0; j < minor; j++) {
                float[][] p = new float[4][], n = new float[4][];
                for (int k = 0; k < 4; k++) {
                    int ii = i + (k == 1 || k == 2 ? 1 : 0), jj = j + (k >= 2 ? 1 : 0);
                    double a = Math.PI * 2 * ii / major, b = Math.PI * 2 * jj / minor;
                    float cx = (float)Math.cos(a), cz = (float)Math.sin(a);
                    float cb = (float)Math.cos(b), sb = (float)Math.sin(b);
                    n[k] = new float[] {cx * cb, sb, cz * cb};
                    p[k] = new float[] {cx * (radius + tube * cb), tube * sb, cz * (radius + tube * cb)};
                }
                t.smooth(p[0], n[0], p[1], n[1], p[2], n[2]);
                t.smooth(p[0], n[0], p[2], n[2], p[3], n[3]);
            }
        }
        return t.build();
    }

    static float[] icosphere(int subdivisions) {
        float g = (1.0f + (float)Math.sqrt(5.0)) / 2.0f;
        List<float[]> tris = new ArrayList<>();
        float[][] v = {{-1, g, 0}, {1, g, 0}, {-1, -g, 0}, {1, -g, 0}, {0, -1, g}, {0, 1, g}, {0, -1, -g}, {0, 1, -g},
            {g, 0, -1}, {g, 0, 1}, {-g, 0, -1}, {-g, 0, 1}};
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2},
            {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11},
            {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        for (int[] tri : f) tris.add(new float[] {v[tri[0]][0], v[tri[0]][1], v[tri[0]][2], v[tri[1]][0], v[tri[1]][1],
            v[tri[1]][2], v[tri[2]][0], v[tri[2]][1], v[tri[2]][2]});
        for (int s = 0; s < subdivisions; s++) {
            List<float[]> next = new ArrayList<>();
            for (float[] tr : tris) {
                float[] a = {tr[0], tr[1], tr[2]}, b = {tr[3], tr[4], tr[5]}, c = {tr[6], tr[7], tr[8]};
                float[] ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
                next.add(cat(a, ab, ca));
                next.add(cat(b, bc, ab));
                next.add(cat(c, ca, bc));
                next.add(cat(ab, bc, ca));
            }
            tris = next;
        }
        Tris t = new Tris();
        for (float[] tr : tris) {
            float[] a = unit(tr[0], tr[1], tr[2]), b = unit(tr[3], tr[4], tr[5]), c = unit(tr[6], tr[7], tr[8]);
            t.smooth(a, a, b, b, c, c);
        }
        return t.build();
    }

    private static float[] scaled(float[] mesh, float sx, float sy, float sz) {
        float[] out = mesh.clone();
        for (int i = 0; i < out.length; i += FLOATS_PER_VERTEX) {
            out[i] *= sx; out[i + 1] *= sy; out[i + 2] *= sz;
            // normals of a stretched shape scale by the inverse
            float nx = out[i + 3] / sx, ny = out[i + 4] / sy, nz = out[i + 5] / sz;
            float l = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            out[i + 3] = nx / l; out[i + 4] = ny / l; out[i + 5] = nz / l;
        }
        return out;
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[] {(a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f};
    }

    private static float[] cat(float[] a, float[] b, float[] c) {
        return new float[] {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
    }

    private static float[] unit(float x, float y, float z) {
        float l = (float)Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / l, y / l, z / l};
    }
}
