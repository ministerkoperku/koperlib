// terrain compound building — greedy mesh for kontraktion + world terrain
use rapier3d::prelude::*;
use std::collections::HashSet;

// converts block positions into a minimal set of merged cuboids for a Rapier compound collider
// 14k individual cuboids → ~few hundred merged boxes, huge BVH win
pub fn greedy_mesh(blocks: &HashSet<[i32; 3]>) -> Vec<(Pose, SharedShape)> {
    let mut remaining = blocks.clone();
    let mut result: Vec<(Pose, SharedShape)> = Vec::new();

    let mut sorted: Vec<[i32; 3]> = remaining.iter().cloned().collect();
    sorted.sort_by(|a, b| a[1].cmp(&b[1]).then(a[2].cmp(&b[2])).then(a[0].cmp(&b[0])));

    for start in sorted {
        if !remaining.contains(&start) { continue; }

        let mut ex = start[0];
        while remaining.contains(&[ex + 1, start[1], start[2]]) { ex += 1; }

        let mut ez = start[2];
        'z: loop {
            for x in start[0]..=ex {
                if !remaining.contains(&[x, start[1], ez + 1]) { break 'z; }
            }
            ez += 1;
        }

        let mut ey = start[1];
        'y: loop {
            for z in start[2]..=ez {
                for x in start[0]..=ex {
                    if !remaining.contains(&[x, ey + 1, z]) { break 'y; }
                }
            }
            ey += 1;
        }

        for y in start[1]..=ey {
            for z in start[2]..=ez {
                for x in start[0]..=ex {
                    remaining.remove(&[x, y, z]);
                }
            }
        }

        let half_x = (ex - start[0] + 1) as f32 * 0.5;
        let half_y = (ey - start[1] + 1) as f32 * 0.5;
        let half_z = (ez - start[2] + 1) as f32 * 0.5;
        let mcx = start[0] as f32 + half_x;
        let mcy = start[1] as f32 + half_y;
        let mcz = start[2] as f32 + half_z;

        result.push((
            Pose::translation(mcx, mcy, mcz),
            SharedShape::cuboid(half_x, half_y, half_z),
        ));
    }

    result
}
