// oriented bounding boxes — standalone system, no parent
// SAT collision + ray intersection using glam math
// used by Khysics collision queries AND future bone hitboxes
use glam::{Mat3, Quat, Vec3};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Obb {
    pub center: Vec3,
    pub half:   Vec3,  // half-extents on each local axis
    pub rot:    Quat,  // orientation
}

impl Obb {
    pub fn unit_block(center: Vec3) -> Self {
        Self { center, half: Vec3::splat(0.5), rot: Quat::IDENTITY }
    }

    pub fn new(center: Vec3, half: Vec3, rot: Quat) -> Self {
        Self { center, half, rot }
    }

    fn axes(&self) -> [Vec3; 3] {
        let m = Mat3::from_quat(self.rot);
        [m.x_axis, m.y_axis, m.z_axis]
    }

    // SAT test — 15 axes for OBB vs OBB
    pub fn overlaps(&self, other: &Obb) -> bool {
        let ax = self.axes();
        let bx = other.axes();
        let d  = other.center - self.center;

        let project = |axis: Vec3, obb_axes: &[Vec3; 3], obb_half: Vec3| -> f32 {
            obb_half.x * obb_axes[0].dot(axis).abs()
          + obb_half.y * obb_axes[1].dot(axis).abs()
          + obb_half.z * obb_axes[2].dot(axis).abs()
        };

        let separated = |axis: Vec3| -> bool {
            d.dot(axis).abs() > project(axis, &ax, self.half) + project(axis, &bx, other.half)
        };

        // face axes of A and B
        for a in ax.iter().chain(bx.iter()) {
            if separated(*a) { return false; }
        }
        // edge cross-product axes
        for a in &ax {
            for b in &bx {
                let cross = a.cross(*b);
                if cross.length_squared() > 1e-10 {
                    if separated(cross.normalize()) { return false; }
                }
            }
        }
        true
    }

    // slab method in OBB local space — returns hit distance or None
    pub fn ray_hit(&self, origin: Vec3, dir: Vec3) -> Option<f32> {
        let axes = self.axes();
        let o = origin - self.center;

        // project ray into OBB local frame
        let lo = Vec3::new(o.dot(axes[0]), o.dot(axes[1]), o.dot(axes[2]));
        let ld = Vec3::new(dir.dot(axes[0]), dir.dot(axes[1]), dir.dot(axes[2]));

        let mut t_near = f32::NEG_INFINITY;
        let mut t_far  = f32::INFINITY;

        let halves = [self.half.x, self.half.y, self.half.z];
        let los    = [lo.x, lo.y, lo.z];
        let lds    = [ld.x, ld.y, ld.z];

        for i in 0..3 {
            let h = halves[i];
            let o = los[i];
            let d = lds[i];

            if d.abs() < 1e-10 {
                if o < -h || o > h { return None; }
            } else {
                let inv = 1.0 / d;
                let (t1, t2) = {
                    let a = (-h - o) * inv;
                    let b = ( h - o) * inv;
                    if a < b { (a, b) } else { (b, a) }
                };
                t_near = t_near.max(t1);
                t_far  = t_far.min(t2);
                if t_near > t_far { return None; }
            }
        }

        if t_far < 0.0 { None } else { Some(t_near.max(0.0)) }
    }
}
