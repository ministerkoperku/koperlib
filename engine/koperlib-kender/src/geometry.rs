// kender mesh builder — face culling + AO in a single kontraktion-local pass
// runs on dirty flag only, result cached in KontraGeometry.vertex_buf

use std::collections::HashSet;
use crate::types::*;
use crate::light::vertex_ao;

/// Rebuild vertex buffer + face masks for a dirty kontraktion.
/// O(n) where n = block count. Called at most once per dirty cycle.
pub fn rebuild_mesh(geo: &mut KontraGeometry) {
    let n = geo.blocks.len();

    // hash set for O(1) neighbor queries — avoids O(n²) pairwise checks
    let occ: HashSet<(i32, i32, i32)> = geo.blocks.iter()
        .map(|b| (b.ox, b.oy, b.oz))
        .collect();

    geo.face_masks.clear();
    geo.face_masks.resize(n, 0u8);
    geo.vertex_buf.clear();
    geo.vertex_buf.reserve(n * 6 * FLOATS_PER_FACE); // worst case: all faces visible
    geo.face_count = 0;

    for (bi, block) in geo.blocks.iter().enumerate() {
        let (bx, by, bz) = (block.ox, block.oy, block.oz);
        let mut mask: u8 = 0;

        for face in 0..6usize {
            let d = FACE_DIRS[face];

            // neighbor fills this face → interior, skip it
            if occ.contains(&(bx + d[0], by + d[1], bz + d[2])) {
                continue;
            }

            mask |= 1 << face;

            // emit 6 verts per face: two CCW triangles (v0v1v2, v0v2v3)
            // [0,1,2, 0,2,3] index pattern on the quad corners
            // Vulkan TRIANGLE_LIST — no index buffer needed
            for &qi in &[0usize, 1, 2, 0, 2, 3] {
                let c  = FACE_CORNERS[face][qi];
                let uv = QUAD_UVS[qi];
                let ao = vertex_ao(face, qi, bx, by, bz, &occ);
                geo.vertex_buf.extend_from_slice(&[
                    bx as f32 + c[0],
                    by as f32 + c[1],
                    bz as f32 + c[2],
                    uv[0], uv[1], ao,
                    face as f32,
                    block.block_id as f32,
                    0.0,
                ]);
            }
            geo.face_count += 1;
        }

        geo.face_masks[bi] = mask;
    }

    geo.dirty = false;
}

/// How many floats would the vertex buffer need (pre-rebuild estimate)?
/// Uses face_masks from last rebuild — safe to call without triggering a rebuild.
#[allow(dead_code)]
pub fn estimated_float_count(geo: &KontraGeometry) -> usize {
    // popcnt all face masks → total visible faces
    let total_faces: usize = geo.face_masks.iter()
        .map(|m| m.count_ones() as usize)
        .sum();
    total_faces * FLOATS_PER_FACE
}
