// per-vertex AO for kontraktion faces
// samples only the kontraktion's own block occupancy — world lighting happens in Java
// returns brightness in [0.2, 1.0], where 0.2 = fully occluded corner

use std::collections::HashSet;
use crate::types::FACE_CORNERS;

pub fn vertex_ao(
    face:   usize,
    corner: usize,
    bx: i32, by: i32, bz: i32,
    occ: &HashSet<(i32, i32, i32)>,
) -> f32 {
    let c = FACE_CORNERS[face][corner];

    // which direction along each axis this corner points from block center
    let sx = if c[0] < 0.5 { -1i32 } else { 1i32 };
    let sy = if c[1] < 0.5 { -1i32 } else { 1i32 };
    let sz = if c[2] < 0.5 { -1i32 } else { 1i32 };

    // the two tangent axes for this face (the axes that aren't the face normal)
    let (a0, a1): (usize, usize) = match face {
        0 | 1 => (1, 2), // ±X face: Y and Z
        2 | 3 => (0, 2), // ±Y face: X and Z
        4 | 5 => (0, 1), // ±Z face: X and Y
        _ => unreachable!(),
    };

    let deltas = [sx, sy, sz];
    let d0 = deltas[a0];
    let d1 = deltas[a1];

    // the 3 blocks adjacent to this corner in the tangent plane
    let n0 = step(bx, by, bz, a0, d0);
    let n1 = step(bx, by, bz, a1, d1);
    let nd = step2(bx, by, bz, a0, d0, a1, d1);

    let s0 = occ.contains(&n0) as u32;
    let s1 = occ.contains(&n1) as u32;
    let sc = occ.contains(&nd) as u32;

    // both sides solid → maximally dark (can't get darker than this)
    if s0 == 1 && s1 == 1 { return 0.2; }
    1.0 - (s0 + s1 + sc) as f32 * 0.2
}

#[inline(always)]
fn step(bx: i32, by: i32, bz: i32, axis: usize, d: i32) -> (i32, i32, i32) {
    match axis {
        0 => (bx + d, by,     bz    ),
        1 => (bx,     by + d, bz    ),
        _ => (bx,     by,     bz + d),
    }
}

#[inline(always)]
fn step2(bx: i32, by: i32, bz: i32, a0: usize, d0: i32, a1: usize, d1: i32) -> (i32, i32, i32) {
    let mut p = [bx, by, bz];
    p[a0] += d0;
    p[a1] += d1;
    (p[0], p[1], p[2])
}
