use crate::world::*;
use crate::terrain::pack_section;
use crate::NONE;

fn floor_world() -> KoperElpeWorld {
    let mut w = KoperElpeWorld::new(1024, 0.5);
    w.cfg.terrain = false;
    w.cfg.floor_y = 0.0;
    w
}

fn run(w: &mut KoperElpeWorld, ticks: u32) { for _ in 0..ticks { w.step(0.05, 2); } }

#[test]
fn falls_and_sleeps_on_floor() {
    let mut w = floor_world();
    let a = w.spawn([0.0, 10.0, 0.0], 0.25, 1.0, 0);
    run(&mut w, 100);
    assert!((w.pos[a as usize][1] - 0.25).abs() < 0.02, "y = {}", w.pos[a as usize][1]);
    assert_eq!(w.state[a as usize], ASLEEP);
    assert_eq!(w.awake.len(), 0);
}

#[test]
fn stack_does_not_sink() {
    let mut w = floor_world();
    let ids: Vec<u32> = (0..6).map(|k| w.spawn([0.0, 0.25 + k as f32 * 0.5, 0.0], 0.25, 1.0, 0)).collect();
    run(&mut w, 200);
    let top = w.pos[ids[5] as usize][1];
    assert!(top > 2.4, "tower collapsed into itself, top y = {top}");
}

#[test]
fn long_rope_barely_stretches() {
    let mut w = floor_world();
    w.cfg.floor_y = -1000.0;
    let mut prev = NONE;
    for k in 0..20 {
        let id = w.spawn([k as f32 * 0.4, 0.0, 0.0], 0.1, 1.0, 7);
        if prev == NONE {
            w.joint(KoperJoint { a: id, b: NONE, min: 0.0, max: 0.0, stiffness: 1.0, snap: 0.0, anchor: [0.0; 3], alive: true });
        } else {
            w.joint(KoperJoint { a: prev, b: id, min: 0.0, max: 0.4, stiffness: 1.0, snap: 0.0, anchor: [0.0; 3], alive: true });
        }
        prev = id;
    }
    run(&mut w, 200);
    let y = w.pos[prev as usize][1];
    // 19 links * 0.4 = 7.6 when perfectly stiff
    assert!(y > -8.2, "rope stretched to {y}");
}

#[test]
fn rope_hangs_from_pin() {
    let mut w = floor_world();
    w.cfg.floor_y = -100.0;
    let mut prev = NONE;
    let mut ids = vec![];
    for k in 0..10 {
        let id = w.spawn([k as f32 * 0.5, 20.0, 0.0], 0.1, 1.0, 7);
        if prev == NONE {
            w.joint(KoperJoint { a: id, b: NONE, min: 0.0, max: 0.0, stiffness: 1.0, snap: 0.0, anchor: [0.0, 20.0, 0.0], alive: true });
        } else {
            w.joint(KoperJoint { a: prev, b: id, min: 0.0, max: 0.5, stiffness: 1.0, snap: 0.0, anchor: [0.0; 3], alive: true });
        }
        prev = id;
        ids.push(id);
    }
    run(&mut w, 400);
    let end = w.pos[*ids.last().unwrap() as usize];
    // 9 links of 0.5 → end hangs about 4.5 below the pin, rope is allowed to stretch a tiny bit
    assert!(end[1] < 16.0 && end[1] > 15.0, "rope end at {:?}", end);
    assert!(end[0].abs() < 0.5, "rope did not swing down {:?}", end);
}

#[test]
fn joint_snaps() {
    let mut w = floor_world();
    let a = w.spawn([0.0, 5.0, 0.0], 0.1, 0.0, 0); // static
    let b = w.spawn([0.0, 4.0, 0.0], 0.1, 1.0, 0);
    let j = w.joint(KoperJoint { a, b, min: 1.0, max: 1.0, stiffness: 1.0, snap: 0.2, anchor: [0.0; 3], alive: true });
    run(&mut w, 5);
    assert!(w.joints[j as usize].alive);
    w.add_velocity(b, [0.0, -400.0, 0.0]);
    run(&mut w, 2);
    assert!(!w.joints[j as usize].alive, "joint should have snapped");
}

fn floor_section(w: &mut KoperElpeWorld, sx: i32, sz: i32) {
    // y = 0 layer solid in section sy = 0
    let mut bits = Box::new([0u64; 64]);
    for i in 0..256 { bits[i >> 6] |= 1 << (i & 63); }
    w.set_section(pack_section(sx, 0, sz), Some(bits));
}

#[test]
fn voxel_terrain_and_freeze_until_section_arrives() {
    let mut w = KoperElpeWorld::new(64, 0.5);
    let a = w.spawn([8.5, 5.0, 8.5], 0.4, 1.0, 0);
    w.step(0.05, 2);
    assert_eq!(w.state[a as usize], FROZEN);
    let mut req = [0i32; 30];
    let n = w.drain_requests(&mut req);
    assert_eq!(n, 1);
    assert_eq!(&req[..3], &[0, 0, 0]);
    floor_section(&mut w, 0, 0);
    assert_eq!(w.state[a as usize], AWAKE);
    run(&mut w, 100);
    let y = w.pos[a as usize][1];
    assert!((y - 1.4).abs() < 0.03, "should rest on top of the y=0 blocks, y = {y}");
    assert_eq!(w.state[a as usize], ASLEEP);
    assert_eq!((w.stats.live, w.stats.asleep, w.stats.frozen), (1, 1, 0));
    // break the block under it → wakes and falls into the hole, then freezes on the unknown section below
    assert!(w.set_block(8, 0, 8, false));
    assert_eq!(w.state[a as usize], AWAKE);
    run(&mut w, 20);
    assert!(w.pos[a as usize][1] < 1.0);
}

#[test]
fn inside_block_gets_pushed_out() {
    let mut w = KoperElpeWorld::new(64, 0.5);
    floor_section(&mut w, 0, 0);
    w.set_section(pack_section(0, -1, 0), None);
    w.set_section(pack_section(0, 1, 0), None);
    let a = w.spawn([3.5, 0.6, 3.5], 0.3, 1.0, 0);
    w.step(0.05, 1);
    assert!(w.pos[a as usize][1] >= 1.29, "y = {}", w.pos[a as usize][1]);
}

#[test]
fn despawn_reuse_keeps_awake_list_clean() {
    let mut w = floor_world();
    let a = w.spawn([0.0, 3.0, 0.0], 0.2, 1.0, 0);
    w.despawn(a);
    let b = w.spawn([1.0, 3.0, 0.0], 0.2, 1.0, 0);
    assert_ne!(a, b, "freed id must not be reused before the tick ends");
    run(&mut w, 1);
    let c = w.spawn([2.0, 3.0, 0.0], 0.2, 1.0, 0);
    assert_eq!(a, c);
    let mut seen = std::collections::HashSet::new();
    assert!(w.awake.iter().all(|i| seen.insert(*i)));
}

#[test]
fn big_pile_settles_and_sleeps() {
    let mut w = KoperElpeWorld::new(20_000, 0.5);
    w.cfg.terrain = false;
    w.cfg.floor_y = 0.0;
    for x in 0..40 { for z in 0..40 { for y in 0..8 {
        w.spawn([x as f32 * 0.52, 0.3 + y as f32 * 0.6, z as f32 * 0.52], 0.25, 1.0, 0);
    }}}
    run(&mut w, 400);
    assert!(w.awake.len() < 200, "pile never went to sleep, {} awake", w.awake.len());
    // nothing fell through the floor or exploded
    assert!(w.pos.iter().all(|p| p[1] > 0.2 && p[1] < 10.0));
}

#[test]
#[ignore]
fn debug_pile() {
    let mut w = KoperElpeWorld::new(20_000, 0.5);
    w.cfg.terrain = false;
    w.cfg.floor_y = 0.0;
    for x in 0..4 { for z in 0..4 { for y in 0..8 {
        w.spawn([x as f32 * 0.52, 0.3 + y as f32 * 0.6, z as f32 * 0.52], 0.25, 1.0, 0);
    }}}
    for t in 0..400 {
        w.step(0.05, 2);
        if t % 40 == 0 {
            let mut vs: Vec<(f32, f32)> = w.awake.iter().map(|&i| { let v = w.velocity(i); ((v[0]*v[0]+v[1]*v[1]+v[2]*v[2]).sqrt(), w.pos[i as usize][1]) }).collect();
            vs.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());
            println!("t {t} awake {} top {:?}", w.awake.len(), &vs[..vs.len().min(4)]);
        }
    }
}

#[test]
fn asleep_spawn_costs_nothing_until_poked() {
    let mut w = floor_world();
    let a = w.spawn_asleep([0.0, 0.25, 0.0], 0.25, 1.0, 0);
    let b = w.spawn([0.0, 5.0, 0.0], 0.25, 1.0, 0);
    assert_eq!(w.awake, vec![b]);
    run(&mut w, 60);
    // b fell on a hard enough to wake it? no — landing slower than wake_speed treats sleepers as walls
    assert!(w.pos[b as usize][1] > 0.7, "b sank into the sleeper");
    w.wake(a);
    assert_eq!(w.state[a as usize], AWAKE);
}

// ── klocs ────────────────────────────────────────────────────────────────────

use crate::kloc::*;

fn kloc_world() -> KoperKlocWorld {
    let mut k = KoperKlocWorld::default();
    k.floor_y = 0.0;
    k
}

fn kloc_run(k: &mut KoperKlocWorld, ticks: u32) {
    let t = crate::terrain::KoperTerrain::default();
    let mut want = Vec::new();
    for _ in 0..ticks { k.step(0.05, 2, &t, 0, false, &mut want); }
}

// a 2x1x2 slab of blocks, offsets around the centre
fn slab() -> Vec<[i16; 3]> {
    vec![[0, 0, 0], [1, 0, 0], [0, 0, 1], [1, 0, 1]]
}

#[test]
fn kloc_falls_and_rests_on_floor() {
    let mut k = kloc_world();
    let a = k.spawn(slab(), 1.0 / 4.0, [0.0, 12.0, 0.0]);
    kloc_run(&mut k, 120);
    let p = k.get(a).unwrap();
    assert!((p.pos[1] - p.half[1]).abs() < 0.05, "kloc did not settle, y = {}", p.pos[1]);
    assert_eq!(p.state, KLOC_ASLEEP, "kloc never fell asleep");
}

#[test]
fn kloc_lands_on_voxel_terrain() {
    let mut w = KoperElpeWorld::new(64, 0.5);
    // solid slab across y = 3 in the section at origin
    let mut bits = Box::new([0u64; 64]);
    for z in 0..16 { for x in 0..16 {
        let i = ((3usize & 15) << 8) | ((z & 15) << 4) | (x & 15);
        bits[i >> 6] |= 1 << (i & 63);
    }}
    w.set_section(pack_section(0, 0, 0), Some(bits));
    let a = w.klocs.spawn(vec![[0, 0, 0]], 1.0, [4.0, 11.0, 4.0]);
    for _ in 0..140 { w.step(0.05, 2); }
    let y = w.klocs.get(a).unwrap().pos[1];
    // block top is y = 4, our half height is 0.5, so the centre wants 4.5
    assert!((y - 4.5).abs() < 0.12, "kloc sank through terrain or floated, y = {y}");
}

#[test]
fn hinge_keeps_the_wheel_on() {
    let mut k = kloc_world();
    let body = k.spawn(slab(), 1.0 / 4.0, [0.0, 6.0, 0.0]);
    let wheel = k.spawn(vec![[0, 0, 0]], 1.0, [2.0, 6.0, 0.0]);
    k.joint(KlocJoint {
        a: body, b: wheel,
        anchor_a: [2.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0],
        axis: [0.0, 0.0, 1.0], kind: HINGE,
        min: 0.0, max: 0.0, limited: false,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
            spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    kloc_run(&mut k, 120);
    let (b, wl) = (k.get(body).unwrap().pos, k.get(wheel).unwrap().pos);
    let d = [wl[0] - b[0], wl[1] - b[1], wl[2] - b[2]];
    let err = ((d[0] - 2.0).powi(2) + d[1].powi(2) + d[2].powi(2)).sqrt();
    assert!(err < 0.15, "wheel drifted off its hinge by {err}");
}

#[test]
fn motor_spins_the_wheel() {
    let mut k = kloc_world();
    let body = k.spawn(slab(), 0.0, [0.0, 6.0, 0.0]); // static chassis
    let wheel = k.spawn(vec![[0, 0, 0]], 1.0, [2.0, 6.0, 0.0]);
    let j = k.joint(KlocJoint {
        a: body, b: wheel,
        anchor_a: [2.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0],
        axis: [0.0, 0.0, 1.0], kind: HINGE,
        min: 0.0, max: 0.0, limited: false,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
            spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    k.set_motor(j, 6.0, 500.0);
    kloc_run(&mut k, 40);
    let spin_vel = k.get(wheel).unwrap().spin_vel;
    assert!((spin_vel - 6.0).abs() < 0.5, "motor never reached speed, spin_vel = {spin_vel}");
    let st = k.joint_state(j).unwrap();
    assert!(st[0] != 0.0, "joint angle never moved");
}

#[test]
fn slider_stays_inside_its_limits() {
    let mut k = kloc_world();
    let base = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let arm = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 9.0, 0.0]);
    let j = k.joint(KlocJoint {
        a: base, b: arm,
        anchor_a: [0.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0],
        axis: [0.0, 1.0, 0.0], kind: SLIDER,
        min: -2.0, max: 0.0, limited: true,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
            spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    kloc_run(&mut k, 200);
    let slide = k.joint_state(j).unwrap()[0];
    assert!(slide >= -2.05 && slide <= 0.05, "slider left its limits, slide = {slide}");
    let arm_y = k.get(arm).unwrap().pos[1];
    assert!(arm_y > 7.5, "arm fell straight through the limit, y = {arm_y}");
}

#[test]
fn klocs_do_not_overlap() {
    let mut k = kloc_world();
    let a = k.spawn(slab(), 1.0 / 4.0, [0.0, 1.0, 0.0]);
    let b = k.spawn(slab(), 1.0 / 4.0, [0.3, 3.0, 0.0]);
    kloc_run(&mut k, 200);
    let (pa, pb) = (k.get(a).unwrap(), k.get(b).unwrap());
    let d = [(pb.pos[0] - pa.pos[0]).abs(), (pb.pos[1] - pa.pos[1]).abs(), (pb.pos[2] - pa.pos[2]).abs()];
    let apart = d[0] >= pa.half[0] + pb.half[0] - 0.05
             || d[1] >= pa.half[1] + pb.half[1] - 0.05
             || d[2] >= pa.half[2] + pb.half[2] - 0.05;
    assert!(apart, "two klocs ended up inside each other: d = {d:?}");
}

#[test]
fn transforms_carry_id_and_identity_rotation() {
    let mut k = kloc_world();
    let a = k.spawn(slab(), 1.0 / 4.0, [1.0, 5.0, 2.0]);
    let mut buf = [0.0f32; 40];
    let n = k.transforms(&mut buf);
    assert_eq!(n, 1);
    let id = (buf[0].to_bits() as u64) | ((buf[1].to_bits() as u64) << 32);
    assert_eq!(k.by_id(id), Some(a));
    // nothing on a hinge -> identity quaternion, every time
    assert_eq!([buf[5], buf[6], buf[7], buf[8]], [0.0, 0.0, 0.0, 1.0]);
}

fn hinge(a: u32, b: u32, at: [f32; 3], axis: [f32; 3]) -> KlocJoint {
    KlocJoint {
        a, b, anchor_a: at, anchor_b: [0.0; 3], axis, kind: HINGE,
        min: 0.0, max: 0.0, limited: false,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
        spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    }
}

// the drift koper hit: a car sitting on the ground, wheels on hinges. contacts used to be
// solved after the joints every substep, so the anchors crept apart forever
#[test]
fn a_car_on_the_ground_does_not_drift_apart() {
    let mut k = kloc_world();
    let chassis = k.spawn(slab(), 1.0 / 4.0, [0.0, 6.0, 0.0]);
    let wheels: Vec<u32> = [[-2.0f32, -1.0, 0.0], [2.0, -1.0, 0.0]].iter()
        .map(|o| k.spawn(vec![[0, 0, 0]], 1.0, [o[0], 6.0 + o[1], o[2]]))
        .collect();
    for (i, o) in [[-2.0f32, -1.0, 0.0], [2.0, -1.0, 0.0]].iter().enumerate() {
        k.joint(hinge(chassis, wheels[i], *o, [0.0, 0.0, 1.0]));
    }
    // drive it: motors on both wheels, and a few landings from a drop
    for id in 0..k.joints.len() as u32 { k.set_motor(id, 12.0, 400.0); }
    for round in 0..6 {
        if round % 2 == 1 {
            let all: Vec<u32> = std::iter::once(chassis).chain(wheels.iter().copied()).collect();
            for b in all { k.add_velocity(b, [3.0, 6.0, 0.0], 0.05); }
        }
        kloc_run(&mut k, 80);
    }
    let c = k.get(chassis).unwrap().pos;
    for (i, o) in [[-2.0f32, -1.0, 0.0], [2.0, -1.0, 0.0]].iter().enumerate() {
        let wp = k.get(wheels[i]).unwrap().pos;
        let want = [c[0] + o[0], c[1] + o[1], c[2] + o[2]];
        let err = ((wp[0] - want[0]).powi(2) + (wp[1] - want[1]).powi(2) + (wp[2] - want[2]).powi(2)).sqrt();
        assert!(err < 0.2, "wheel {i} drifted {err} off the chassis after 400 ticks");
    }
}

#[test]
fn a_kloc_resting_on_terrain_falls_asleep() {
    let mut w = KoperElpeWorld::new(64, 0.5);
    let mut bits = Box::new([0u64; 64]);
    for z in 0..16 { for x in 0..16 {
        let i = ((3usize & 15) << 8) | ((z & 15) << 4) | (x & 15);
        bits[i >> 6] |= 1 << (i & 63);
    }}
    w.set_section(pack_section(0, 0, 0), Some(bits));
    let a = w.klocs.spawn(vec![[0, 0, 0]], 1.0, [4.0, 9.0, 4.0]);
    for _ in 0..300 { w.step(0.05, 2); }
    assert_eq!(w.klocs.get(a).unwrap().state, KLOC_ASLEEP,
        "kloc standing on terrain never slept — contact was resetting its own counter");
}

// suspension: a prismatic joint with a spring on it has to hold weight up and squash under it
#[test]
fn spring_slider_holds_weight_and_compresses() {
    let mut k = kloc_world();
    let base = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let arm = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    let j = k.joint(KlocJoint {
        a: base, b: arm, anchor_a: [0.0; 3], anchor_b: [0.0; 3],
        axis: [0.0, 1.0, 0.0], kind: SLIDER,
        min: -0.75, max: 0.0, limited: true,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
        spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    // offroad's stiffest tune
    k.set_spring(j, 0.0, 1400.0, 140.0, 650_000.0);
    kloc_run(&mut k, 200);
    let slide = k.joint_state(j).unwrap()[0];
    assert!(slide > -0.75 + 0.02, "spring bottomed out, it is not holding anything: {slide}");
    assert!(slide < 0.0, "spring never compressed under the weight at all: {slide}");

    // a soft tune has to sag further than a stiff one
    let mut k2 = kloc_world();
    let b2 = k2.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let a2 = k2.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    let j2 = k2.joint(KlocJoint {
        a: b2, b: a2, anchor_a: [0.0; 3], anchor_b: [0.0; 3],
        axis: [0.0, 1.0, 0.0], kind: SLIDER,
        min: -0.75, max: 0.0, limited: true,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
        spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    k2.set_spring(j2, 0.0, 150.0, 24.0, 40_000.0);
    kloc_run(&mut k2, 200);
    let soft = k2.joint_state(j2).unwrap()[0];
    assert!(soft < slide, "soft spring {soft} did not sag more than the stiff one {slide}");
}

// koper's car on elpe: chassis, a hub per corner on a sprung slider, a wheel on a bearing.
// chassis and wheel are TWO joints apart, so pairwise "are these jointed" never skipped them
// and the contact solver shoved the car sideways across the world all by itself
fn elpe_car(k: &mut KoperKlocWorld) -> (u32, Vec<u32>, Vec<u32>) {
    let chassis = k.spawn(vec![[-1, 0, -1], [1, 0, -1], [-1, 0, 1], [1, 0, 1]], 1.0 / 8.0, [0.0, 6.0, 0.0]);
    let corners = [[-1.0f32, -1.0, -1.0], [1.0, -1.0, -1.0], [-1.0, -1.0, 1.0], [1.0, -1.0, 1.0]];
    let (mut hubs, mut wheels) = (Vec::new(), Vec::new());
    for c in corners {
        let hub = k.spawn(vec![[0, 0, 0]], 1.0, [c[0], 6.0 + c[1], c[2]]);
        let wheel = k.spawn(vec![[0, 0, 0]], 0.5, [c[0], 6.0 + c[1], c[2]]);
        let susp = k.joint(KlocJoint {
            a: chassis, b: hub, anchor_a: c, anchor_b: [0.0; 3],
            axis: [0.0, 1.0, 0.0], kind: SLIDER,
            min: -0.75, max: 0.0, limited: true,
            motor_on: false, motor_vel: 0.0, motor_force: 0.0,
            spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
            pos: 0.0, vel: 0.0, alive: true,
        });
        k.set_spring(susp, 0.0, 1400.0, 140.0, 650_000.0);
        let bearing = k.joint(hinge(hub, wheel, [0.0; 3], [1.0, 0.0, 0.0]));
        hubs.push(hub);
        wheels.push(wheel);
        let _ = bearing;
    }
    (chassis, hubs, wheels)
}

#[test]
fn an_elpe_car_does_not_drive_off_by_itself() {
    let mut k = kloc_world();
    let (chassis, _, _) = elpe_car(&mut k);
    let start = k.get(chassis).unwrap().pos;
    kloc_run(&mut k, 400);
    let end = k.get(chassis).unwrap().pos;
    let sideways = ((end[0] - start[0]).powi(2) + (end[2] - start[2]).powi(2)).sqrt();
    assert!(sideways < 0.3,
        "parked car wandered {sideways:.2} blocks sideways on its own");
}

#[test]
fn a_limited_steering_bearing_does_not_spin_forever() {
    let mut k = kloc_world();
    let post = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let knuckle = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    let j = k.joint(hinge(post, knuckle, [0.0; 3], [0.0, 1.0, 0.0]));
    k.set_limits(j, -0.6, 0.6);
    // a leftover drive motor, exactly what left the steering axle spinning
    k.set_motor(j, 12.0, 500.0);
    kloc_run(&mut k, 300);
    let angle = k.joint_state(j).unwrap()[0];
    assert!(angle <= 0.61 && angle >= -0.61,
        "steering axle spun past its lock to {angle} rad — it is going round and round");
}

#[test]
fn a_steering_servo_goes_where_it_is_told() {
    let mut k = kloc_world();
    let post = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let knuckle = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    let j = k.joint(hinge(post, knuckle, [0.0; 3], [0.0, 1.0, 0.0]));
    k.set_limits(j, -0.6, 0.6);
    k.set_spring(j, 0.35, 900.0, 90.0, 1000.0);
    kloc_run(&mut k, 200);
    let angle = k.joint_state(j).unwrap()[0];
    assert!((angle - 0.35).abs() < 0.08, "servo asked for 0.35 rad, sits at {angle}");
}

#[test]
fn a_pushed_car_rolls_to_a_stop() {
    let mut k = kloc_world();
    let (chassis, _, _) = elpe_car(&mut k);
    kloc_run(&mut k, 120); // settle
    let start = k.get(chassis).unwrap().pos;
    // shove it, the way koper did
    let group: Vec<u32> = (0..k.klocs.len() as u32).filter(|i| k.get(*i).is_some()).collect();
    for b in &group { k.add_velocity(*b, [4.0, 0.0, 0.0], 0.05); }
    kloc_run(&mut k, 400);
    let end = k.get(chassis).unwrap().pos;
    let v = k.velocity(chassis, 0.05);
    let speed = (v[0] * v[0] + v[2] * v[2]).sqrt();
    assert!(speed < 0.25, "car never stopped, still doing {speed:.2} blocks/s after 20s");
    assert!((end[0] - start[0]).abs() > 0.2, "the shove did nothing at all");
}

#[test]
fn driven_wheels_actually_move_the_car() {
    let mut k = kloc_world();
    let (chassis, _, wheels) = elpe_car(&mut k);
    kloc_run(&mut k, 120);
    let start = k.get(chassis).unwrap().pos;
    // drive every wheel, bearings are joints 1,3,5,7 (slider first per corner)
    for id in 0..k.joints.len() as u32 {
        if k.joints[id as usize].kind == HINGE { k.set_motor(id, 8.0, 400.0); }
    }
    kloc_run(&mut k, 200);
    let end = k.get(chassis).unwrap().pos;
    let moved = ((end[0] - start[0]).powi(2) + (end[2] - start[2]).powi(2)).sqrt();
    assert!(moved > 1.0, "wheels turned but the car only moved {moved:.2} blocks");
    let _ = wheels;
}

// koper's words: a bearing turns and the things on it turn WITH it. it is not a micro-wheel
// that spins on its own while whatever is bolted to it sits there
#[test]
fn a_bearing_carries_what_is_bolted_to_it() {
    let mut k = kloc_world();
    let post = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let bearing = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    // a wheel sitting one block out on the bearing, welded (a stick, not a second hinge)
    let wheel = k.spawn(vec![[0, 0, 0]], 1.0, [2.0, 10.0, 0.0]);
    let j = k.joint(hinge(post, bearing, [0.0; 3], [0.0, 1.0, 0.0]));
    k.joint(KlocJoint {
        a: bearing, b: wheel, anchor_a: [2.0, 0.0, 0.0], anchor_b: [0.0; 3],
        axis: [0.0, 1.0, 0.0], kind: SLIDER,
        min: 0.0, max: 0.0, limited: true,
        motor_on: false, motor_vel: 0.0, motor_force: 0.0,
        spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
        pos: 0.0, vel: 0.0, alive: true,
    });
    k.set_spring(j, std::f32::consts::FRAC_PI_2, 900.0, 60.0, 1000.0);
    kloc_run(&mut k, 200);

    let angle = k.joint_state(j).unwrap()[0];
    assert!((angle - std::f32::consts::FRAC_PI_2).abs() < 0.1, "bearing sits at {angle}, wanted pi/2");

    // a quarter turn about Y takes the wheel from +2x to about +2z
    let wp = k.get(wheel).unwrap().pos;
    let bp = k.get(bearing).unwrap().pos;
    let off = [wp[0] - bp[0], wp[1] - bp[1], wp[2] - bp[2]];
    assert!(off[2].abs() > 1.5 && off[0].abs() < 0.6,
        "bearing turned but the wheel on it did not come along: offset {off:?}");
}

#[test]
fn a_steering_knuckle_drags_its_wheel_round() {
    let mut k = kloc_world();
    let hub = k.spawn(vec![[0, 0, 0]], 0.0, [0.0, 10.0, 0.0]);
    let knuckle = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 0.0]);
    let wheel = k.spawn(vec![[0, 0, 0]], 1.0, [0.0, 10.0, 1.0]);
    let steer = k.joint(hinge(hub, knuckle, [0.0; 3], [0.0, 1.0, 0.0]));
    k.joint(hinge(knuckle, wheel, [0.0, 0.0, 1.0], [1.0, 0.0, 0.0]));
    k.set_limits(steer, -0.6, 0.6);
    k.set_spring(steer, 0.6, 900.0, 60.0, 3000.0);
    kloc_run(&mut k, 200);

    let (np, wp) = (k.get(knuckle).unwrap().pos, k.get(wheel).unwrap().pos);
    let off = [wp[0] - np[0], wp[2] - np[2]];
    // steered 0.6 rad about Y: the wheel should have swung off the z axis
    assert!(off[0].abs() > 0.4, "steering turned but the wheel stayed straight: {off:?}");
}

// elpe bodies do not turn, full stop. a car drives along the world axes and that is the deal
#[test]
fn an_elpe_body_never_reports_a_turned_body() {
    let mut k = kloc_world();
    let (chassis, _, _) = elpe_car(&mut k);
    kloc_run(&mut k, 120);
    for id in 0..k.joints.len() as u32 {
        if k.joints[id as usize].kind == HINGE { k.set_motor(id, 8.0, 400.0); }
    }
    kloc_run(&mut k, 300);
    let mut buf = [0.0f32; 400];
    let n = k.transforms(&mut buf);
    let chassis_id = k.id_of(chassis);
    for i in 0..n {
        let b = i * 10;
        let id = (buf[b].to_bits() as u64) | ((buf[b + 1].to_bits() as u64) << 32);
        if id != chassis_id { continue; }
        assert_eq!([buf[b + 5], buf[b + 6], buf[b + 7], buf[b + 8]], [0.0, 0.0, 0.0, 1.0],
            "the chassis came back rotated — nothing but a hinge may turn on elpe");
    }
}
