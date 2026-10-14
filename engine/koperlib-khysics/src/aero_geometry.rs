use super::{AeroSurface, Vec3};

pub struct AeroPiece {
    pub surface: AeroSurface,
    pub size: Vec3,
}

pub fn panels(pieces: Vec<AeroPiece>) -> Vec<AeroSurface> {
    use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
    let mut out = Vec::new();
    let origin = pieces.iter().filter(|p| p.size.is_finite() && p.surface.off.is_finite())
        .map(|p| p.surface.off - p.size * 0.5).fold(Vec3::splat(f32::INFINITY), Vec3::min);
    let mut cells: HashMap<[i32; 3], AeroSurface> = HashMap::new();
    for piece in pieces {
        let mut s = piece.surface;
        s.chunky = piece.size.min_element() >= 0.99 && piece.size.max_element() <= 1.01;
        if !piece.size.is_finite() || !s.off.is_finite() || piece.size.min_element() <= 0.0 { continue; }
        if s.buoy > 0.0 || (s.cl <= 0.0 && s.propeller <= 0.0) { out.push(s); continue; }
        let lo = ((s.off - piece.size * 0.5 - origin) * 4.0).round().as_ivec3();
        let dimensions = (piece.size * 4.0).round().as_ivec3().max(glam::IVec3::ONE);
        for x in 0..dimensions.x { for y in 0..dimensions.y { for z in 0..dimensions.z {
            cells.insert([lo.x+x, lo.y+y, lo.z+z], s);
        }}}
    }
    let directions = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
    let mut remaining: HashSet<_> = cells.keys().copied().collect();
    // Stable component and panel order also keeps the panel-state history deterministic.
    let mut starts: Vec<_> = remaining.iter().copied().collect();
    starts.sort_unstable();
    for start in starts {
        if !remaining.remove(&start) { continue; }
        let mut queue = VecDeque::from([start]);
        let mut region = Vec::new();
        let mut lo = start;
        let mut hi = start;
        while let Some(cell) = queue.pop_front() {
            for axis in 0..3 { lo[axis] = lo[axis].min(cell[axis]); hi[axis] = hi[axis].max(cell[axis]); }
            region.push(cell);
            for direction in directions {
                let next = [cell[0]+direction[0],cell[1]+direction[1],cell[2]+direction[2]];
                if remaining.remove(&next) { queue.push_back(next); }
            }
        }
        let ext = [hi[0]-lo[0]+1,hi[1]-lo[1]+1,hi[2]-lo[2]+1];
        let axis = if ext[1] <= ext[0].min(ext[2]) { 1 } else if ext[0] <= ext[2] { 0 } else { 2 };
        let u = (axis+1)%3;
        let v = (axis+2)%3;
        let span_axis = if ext[u] >= ext[v] { u } else { v };
        let span = ext[span_axis] as f32 / 4.0;
        let chord = ext[u].min(ext[v]) as f32 / 4.0;
        let mut columns: BTreeMap<[i32;2], Vec<[i32;3]>> = BTreeMap::new();
        for cell in region { columns.entry([cell[u],cell[v]]).or_default().push(cell); }
        let mirrored = columns.keys().filter(|column| {
            let mut mirror = **column;
            let idx = if span_axis == u { 0 } else { 1 };
            mirror[idx] = lo[span_axis]+hi[span_axis]-mirror[idx];
            columns.contains_key(&mirror)
        }).count() as f32 / columns.len() as f32;
        let shape = (0.6+0.4*mirrored) * (0.65+0.35*span/(span+chord)) * span/(span+0.5);
        let normal = match axis { 0 => Vec3::X, 1 => Vec3::Y, _ => Vec3::Z };
        // Ambiguous normals follow the bearing; thickness is measured from geometry, not source pieces.
        let ambiguous = ext[axis] == ext[u] || ext[axis] == ext[v]
            || ext[axis] as f32 > ext.iter().copied().max().unwrap() as f32 * 0.5;
        let bucket_u = (ext[u] / 2).clamp(1, 2);
        let bucket_v = (ext[v] / 2).clamp(1, 2);
        let mut buckets: BTreeMap<[i32;3], (AeroSurface, f32)> = BTreeMap::new();
        for (column, occupied) in columns {
            let thickness = occupied.len() as f32 / 4.0;
            let chunky = ambiguous || thickness >= 1.0;
            let thickness_efficiency = if thickness < 0.375 { 0.32 }
                else { (0.5 / thickness).clamp(0.15, 1.0) };
            let n = occupied.len() as f32;
            let mut s = AeroSurface { off: Vec3::ZERO, normal, area: 1.0/16.0,
                cl: 0.0, cd: 0.0, propeller: 0.0, buoy: 0.0, chunky };
            let mut mid = 0.0;
            for cell in &occupied {
                let material = cells[cell];
                s.cl += material.cl/n * shape * thickness_efficiency;
                s.cd += material.cd/n;
                s.propeller += material.propeller/n;
                mid += (cell[axis] as f32+0.5)/n;
            }
            let mut center = [0.0;3];
            center[u] = column[0] as f32+0.5;
            center[v] = column[1] as f32+0.5;
            center[axis] = mid;
            s.off = origin+Vec3::from(center)/4.0;
            let mut key = [0;3];
            // Four samples per full face retain blade velocity around a centered rotor's axle.
            key[u] = column[0].div_euclid(bucket_u);
            key[v] = column[1].div_euclid(bucket_v);
            key[axis] = (mid/4.0).floor() as i32;
            let (bucket, count) = buckets.entry(key).or_insert((AeroSurface { off: Vec3::ZERO,
                normal, area: 0.0, cl: 0.0, cd: 0.0, propeller: 0.0, buoy: 0.0, chunky },0.0));
            bucket.chunky |= chunky;
            bucket.off += s.off; bucket.area += s.area;
            bucket.cl += s.cl; bucket.cd += s.cd; bucket.propeller += s.propeller;
            *count += 1.0;
        }
        for (_, (mut s,n)) in buckets {
            s.off /= n; s.cl /= n; s.cd /= n; s.propeller /= n;
            // A one-cell-wide face still has moving material on both sides of its centre.
            let sample_u: &[f32] = if ext[u] == 1 { &[-0.0625, 0.0625] } else { &[0.0] };
            let sample_v: &[f32] = if ext[v] == 1 { &[-0.0625, 0.0625] } else { &[0.0] };
            s.area /= (sample_u.len() * sample_v.len()) as f32;
            for &du in sample_u { for &dv in sample_v {
                let mut sample = s;
                sample.off[u] += du; sample.off[v] += dv;
                out.push(sample);
            }}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::{AeroMode, KhysWorld, PhysicsCmd};

    fn sheet(thickness: f32, cell: f32, span: f32, chord: f32) -> Vec<AeroPiece> {
        let mut pieces = Vec::new();
        for x in 0..(span/cell).round() as i32 {
            for z in 0..(chord/cell).round() as i32 {
                for y in 0..(thickness/cell).round() as i32 {
                    pieces.push(AeroPiece { size: Vec3::splat(cell), surface: AeroSurface {
                        off: Vec3::new((x as f32+0.5)*cell-span/2.0,
                            (y as f32+0.5)*cell-thickness/2.0, (z as f32+0.5)*cell-chord/2.0),
                        normal: Vec3::Y, area: cell*cell, cd: 0.35, cl: 4.392,
                        propeller: 4.0, buoy: 0.0, chunky: true,
                    }});
                }
            }
        }
        pieces
    }

    fn lift(pieces: Vec<AeroPiece>) -> f32 { lift_mode(pieces,AeroMode::Correct) }

    fn lift_mode(pieces: Vec<AeroPiece>,mode:AeroMode) -> f32 {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(1, vec![[0.0; 3]], &[100.0], 0, [0.0, 100.0, 0.0]);
        world.process_cmd(PhysicsCmd::SetGravityScale { id: 1, scale: 0.0 });
        world.process_cmd(PhysicsCmd::SetDamping { id: 1, linear: 0.0, angular: 0.0 });
        world.process_cmd(PhysicsCmd::SetVelocity { id: 1, v: [0.0, 0.0, 20.0] });
        let k = world.kontras.get_mut(&1).unwrap();
        k.aero_mode = mode;
        k.aero = panels(pieces);
        world.step(1.0/60.0);
        world.bodies.get(world.kontras[&1].body).unwrap().linvel().y
    }

    #[test]
    fn low_mode_uses_shape_and_material_lift_strength() {
        let flat = lift_mode(sheet(0.5,0.5,8.0,2.0),AeroMode::Low);
        let chunky = lift_mode(sheet(1.0,1.0,8.0,2.0),AeroMode::Low);
        assert!(flat>chunky*1.2,"Low flat={flat} chunky={chunky}");
        let mut magical=sheet(0.5,0.5,8.0,2.0);
        for piece in &mut magical { piece.surface.cl=10.98; }
        assert!(lift_mode(magical,AeroMode::Low)>flat*1.2);
    }

    #[test]
    fn half_block_sheet_lifts_better_than_chunky_same_planform() {
        let flat = lift(sheet(0.5, 0.5, 8.0, 2.0));
        let chunky = lift(sheet(1.0, 1.0, 8.0, 2.0));
        assert!(flat > chunky * 1.2, "flat={flat} chunky={chunky}");
    }

    #[test]
    fn stacked_micro_layers_do_not_multiply_exposed_area() {
        let flat = panels(sheet(0.5, 0.5, 8.0, 2.0));
        let stacked = panels(sheet(1.0, 0.5, 8.0, 2.0));
        let area = |s: &[AeroSurface]| s.iter().map(|s| s.area).sum::<f32>();
        assert!((area(&flat)-16.0).abs() < 0.001);
        assert!((area(&stacked)-16.0).abs() < 0.001, "stacked area {}", area(&stacked));
        assert!(lift(sheet(1.0, 0.5, 8.0, 2.0)) < lift(sheet(0.5, 0.5, 8.0, 2.0)));
    }

    #[test]
    fn quarter_block_sheet_is_weaker_than_full_and_half_block_sheets() {
        let thin = lift(sheet(0.25,0.25,8.0,2.0));
        let chunky = lift(sheet(1.0,1.0,8.0,2.0));
        let half = lift(sheet(0.5,0.5,8.0,2.0));
        assert!(half > chunky && chunky > thin, "half={half} full={chunky} quarter={thin}");
    }

    #[test]
    fn symmetry_improves_lift_at_equal_area_span_and_chord() {
        let mut symmetric = sheet(0.5,0.5,8.0,2.0);
        symmetric.retain(|p| !(p.surface.off.z > 0.5 && p.surface.off.x.abs() > 2.75));
        let mut asymmetric = sheet(0.5,0.5,8.0,2.0);
        asymmetric.retain(|p| !(p.surface.off.z > 0.5 && p.surface.off.x > 1.75));
        assert_eq!(symmetric.len(),asymmetric.len());
        let good = lift(symmetric); let poor = lift(asymmetric);
        assert!(good>poor*1.01, "symmetric={good} asymmetric={poor}");
    }

    #[test]
    fn actual_lift_follows_material_ordering() {
        let mut speeds = Vec::new();
        for (cl,cd) in [(2.0,0.8),(2.44,0.55),(4.392,0.35),(10.98,0.22)] {
            let mut pieces = sheet(0.5,0.5,8.0,2.0);
            for piece in &mut pieces { piece.surface.cl=cl; piece.surface.cd=cd; }
            speeds.push(lift(pieces));
        }
        assert!(speeds.windows(2).all(|w| w[1] > w[0]), "lift speeds {speeds:?}");
        let mut pieces = sheet(0.5,0.5,8.0,2.0);
        for piece in &mut pieces { piece.surface.cl=0.0; piece.surface.propeller=22.0; }
        assert!(lift(pieces).abs()<1.0e-5, "Dedicated propellers provided unpowered wing lift");
    }

    #[test]
    fn subdivision_preserves_shape_and_single_tiny_part_is_weak() {
        let half = lift(sheet(0.5, 0.5, 8.0, 2.0));
        let quarters = lift(sheet(0.5, 0.25, 8.0, 2.0));
        assert!((half-quarters).abs() < half*0.02, "same shape {half} vs {quarters}");
        assert!(lift(sheet(0.25, 0.25, 0.25, 0.25)) < lift(sheet(0.5, 0.5, 0.5, 0.5))*0.4);
    }
}
