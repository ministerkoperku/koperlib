// unit meshes for the KFX particle styles, triangle lists of [x,y,z, nx,ny,nz]. the portable renderer
// builds the same shapes in KfxMeshes.java; keep the two in step. style codes follow KfxStyles:
// 0 sprite (no mesh, halo only), 1 spark, 2 star, 3 ring, 4 shard, 5 cube, 6 tetra, 7 orb3d, 8 gem

pub const STYLE_COUNT: usize = 9;

struct Tris(Vec<[f32; 6]>);

impl Tris {
    fn new() -> Self { Tris(Vec::new()) }

    // flat triangle, normal faces away from `inside`
    fn flat(&mut self, a: [f32; 3], b: [f32; 3], c: [f32; 3], inside: [f32; 3]) {
        let u = sub(b, a);
        let v = sub(c, a);
        let mut n = cross(u, v);
        let o = [a[0] + b[0] + c[0] - 3.0 * inside[0], a[1] + b[1] + c[1] - 3.0 * inside[1],
            a[2] + b[2] + c[2] - 3.0 * inside[2]];
        if dot(n, o) < 0.0 { n = [-n[0], -n[1], -n[2]]; }
        let n = norm(n);
        for p in [a, b, c] { self.0.push([p[0], p[1], p[2], n[0], n[1], n[2]]); }
    }

    fn smooth(&mut self, a: [f32; 3], na: [f32; 3], b: [f32; 3], nb: [f32; 3], c: [f32; 3], nc: [f32; 3]) {
        self.0.push([a[0], a[1], a[2], na[0], na[1], na[2]]);
        self.0.push([b[0], b[1], b[2], nb[0], nb[1], nb[2]]);
        self.0.push([c[0], c[1], c[2], nc[0], nc[1], nc[2]]);
    }
}

const ORIGIN: [f32; 3] = [0.0, 0.0, 0.0];

fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
fn dot(a: [f32; 3], b: [f32; 3]) -> f32 { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }
fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}
fn norm(a: [f32; 3]) -> [f32; 3] {
    let l = dot(a, a).sqrt();
    if l < 1.0e-9 { a } else { [a[0] / l, a[1] / l, a[2] / l] }
}

fn bipyramid(sides: usize, radius: f32, tip: f32) -> Vec<[f32; 6]> {
    let mut t = Tris::new();
    let top = [0.0, tip, 0.0];
    let bottom = [0.0, -tip, 0.0];
    for i in 0..sides {
        let a0 = std::f32::consts::TAU * i as f32 / sides as f32;
        let a1 = std::f32::consts::TAU * (i + 1) as f32 / sides as f32;
        let p0 = [a0.cos() * radius, 0.0, a0.sin() * radius];
        let p1 = [a1.cos() * radius, 0.0, a1.sin() * radius];
        t.flat(top, p0, p1, ORIGIN);
        t.flat(bottom, p1, p0, ORIGIN);
    }
    t.0
}

fn scaled(mut mesh: Vec<[f32; 6]>, s: [f32; 3]) -> Vec<[f32; 6]> {
    for v in &mut mesh {
        v[0] *= s[0]; v[1] *= s[1]; v[2] *= s[2];
        let n = norm([v[3] / s[0], v[4] / s[1], v[5] / s[2]]);
        v[3] = n[0]; v[4] = n[1]; v[5] = n[2];
    }
    mesh
}

fn cube() -> Vec<[f32; 6]> {
    let mut t = Tris::new();
    let a = 0.8;
    let c: Vec<[f32; 3]> = (0..8).map(|i| [
        if i & 4 != 0 { a } else { -a }, if i & 2 != 0 { a } else { -a }, if i & 1 != 0 { a } else { -a },
    ]).collect();
    for f in [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]] {
        t.flat(c[f[0]], c[f[1]], c[f[2]], ORIGIN);
        t.flat(c[f[0]], c[f[2]], c[f[3]], ORIGIN);
    }
    t.0
}

fn tetra() -> Vec<[f32; 6]> {
    let mut t = Tris::new();
    let s = 1.05;
    let (p0, p1, p2, p3) = ([s, s, s], [s, -s, -s], [-s, s, -s], [-s, -s, s]);
    t.flat(p0, p1, p2, ORIGIN);
    t.flat(p0, p3, p1, ORIGIN);
    t.flat(p0, p2, p3, ORIGIN);
    t.flat(p1, p3, p2, ORIGIN);
    t.0
}

fn star() -> Vec<[f32; 6]> {
    let mut t = Tris::new();
    let (len, base) = (2.1f32, 0.24f32);
    for ax in [[1.0, 0.0, 0.0], [-1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, -1.0, 0.0], [0.0, 0.0, 1.0], [0.0, 0.0, -1.0f32]] {
        let u = if ax[1].abs() > 0.5 { [1.0, 0.0, 0.0] } else { [0.0, 1.0, 0.0] };
        let w = cross(ax, u);
        let tip = [ax[0] * len, ax[1] * len, ax[2] * len];
        let ring: Vec<[f32; 3]> = (0..4).map(|i| {
            let a = std::f32::consts::FRAC_PI_2 * i as f32;
            let (cu, cw) = (a.cos() * base, a.sin() * base);
            [u[0] * cu + w[0] * cw, u[1] * cu + w[1] * cw, u[2] * cu + w[2] * cw]
        }).collect();
        let axis = [tip[0] / 3.0, tip[1] / 3.0, tip[2] / 3.0];
        for i in 0..4 { t.flat(tip, ring[i], ring[(i + 1) & 3], axis); }
    }
    let mut out = t.0;
    out.extend(bipyramid(4, 0.55, 0.55));
    out
}

fn torus(major: usize, minor: usize, radius: f32, tube: f32) -> Vec<[f32; 6]> {
    let mut t = Tris::new();
    for i in 0..major {
        for j in 0..minor {
            let mut p = [[0.0f32; 3]; 4];
            let mut n = [[0.0f32; 3]; 4];
            for k in 0..4 {
                let ii = i + if k == 1 || k == 2 { 1 } else { 0 };
                let jj = j + if k >= 2 { 1 } else { 0 };
                let a = std::f32::consts::TAU * ii as f32 / major as f32;
                let b = std::f32::consts::TAU * jj as f32 / minor as f32;
                let (cx, cz, cb, sb) = (a.cos(), a.sin(), b.cos(), b.sin());
                n[k] = [cx * cb, sb, cz * cb];
                p[k] = [cx * (radius + tube * cb), tube * sb, cz * (radius + tube * cb)];
            }
            t.smooth(p[0], n[0], p[1], n[1], p[2], n[2]);
            t.smooth(p[0], n[0], p[2], n[2], p[3], n[3]);
        }
    }
    t.0
}

fn icosphere(subdivisions: usize) -> Vec<[f32; 6]> {
    let g = (1.0 + 5.0f32.sqrt()) / 2.0;
    let v = [[-1.0, g, 0.0], [1.0, g, 0.0], [-1.0, -g, 0.0], [1.0, -g, 0.0], [0.0, -1.0, g], [0.0, 1.0, g],
        [0.0, -1.0, -g], [0.0, 1.0, -g], [g, 0.0, -1.0], [g, 0.0, 1.0], [-g, 0.0, -1.0], [-g, 0.0, 1.0]];
    let f = [[0, 11, 5], [0, 5, 1], [0, 1, 7], [0, 7, 10], [0, 10, 11], [1, 5, 9], [5, 11, 4], [11, 10, 2],
        [10, 7, 6], [7, 1, 8], [3, 9, 4], [3, 4, 2], [3, 2, 6], [3, 6, 8], [3, 8, 9], [4, 9, 5], [2, 4, 11],
        [6, 2, 10], [8, 6, 7], [9, 8, 1]];
    let mut tris: Vec<[[f32; 3]; 3]> = f.iter().map(|t| [v[t[0]], v[t[1]], v[t[2]]]).collect();
    let mid = |a: [f32; 3], b: [f32; 3]| [(a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5, (a[2] + b[2]) * 0.5];
    for _ in 0..subdivisions {
        let mut next = Vec::with_capacity(tris.len() * 4);
        for [a, b, c] in tris {
            let (ab, bc, ca) = (mid(a, b), mid(b, c), mid(c, a));
            next.push([a, ab, ca]);
            next.push([b, bc, ab]);
            next.push([c, ca, bc]);
            next.push([ab, bc, ca]);
        }
        tris = next;
    }
    let mut t = Tris::new();
    for [a, b, c] in tris {
        let (a, b, c) = (norm(a), norm(b), norm(c));
        t.smooth(a, a, b, b, c, c);
    }
    t.0
}

// every style's mesh in one buffer: (vertex data, first vertex and vertex count per style)
pub fn all() -> (Vec<[f32; 6]>, [(u32, u32); STYLE_COUNT]) {
    let meshes: [Vec<[f32; 6]>; STYLE_COUNT] = [
        Vec::new(),
        scaled(bipyramid(6, 1.0, 1.0), [0.22, 2.6, 0.22]),
        star(),
        torus(14, 6, 1.0, 0.2),
        scaled(bipyramid(6, 1.0, 1.0), [0.5, 1.9, 0.5]),
        cube(),
        tetra(),
        icosphere(1),
        bipyramid(4, 1.0, 1.25),
    ];
    let mut data = Vec::new();
    let mut ranges = [(0u32, 0u32); STYLE_COUNT];
    for (style, mesh) in meshes.into_iter().enumerate() {
        ranges[style] = (data.len() as u32, mesh.len() as u32);
        data.extend(mesh);
    }
    (data, ranges)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_mesh_style_has_whole_triangles_and_unit_normals() {
        let (data, ranges) = all();
        assert_eq!(ranges[0].1, 0, "sprite is halo only");
        for (style, (first, count)) in ranges.iter().enumerate().skip(1) {
            assert!(*count > 0 && count % 3 == 0, "style {style} has {count} vertices");
            for v in &data[*first as usize..(*first + *count) as usize] {
                let l = (v[3] * v[3] + v[4] * v[4] + v[5] * v[5]).sqrt();
                assert!((l - 1.0).abs() < 1.0e-3, "style {style} normal length {l}");
            }
        }
        assert_eq!(ranges[7].1, 240, "orb3d is a once-subdivided icosphere");
        assert_eq!(ranges[8].1, 24, "gem is the octahedron");
    }
}
