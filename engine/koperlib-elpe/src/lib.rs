// koperlib-elpe — extremely light physics engine
// points with a radius, joints between them, voxel terrain. that's it. no rotations.
// a "rigid body" is a cluster of points held by stiff joints, a rope is a chain, a ragdoll is a stick figure.
// java owns the world pointer and calls in once per tick, everything else is plain memory it can read

pub mod crew;
pub mod kloc;
pub mod grid;
pub mod terrain;
pub mod world;

pub use grid::NONE;
use terrain::pack_section;
use world::{KoperElpeWorld, KoperJoint};

use std::panic::{catch_unwind, AssertUnwindSafe};

pub const ELPE_VERSION: u32 = 1;

// never let a rust panic unwind into the jvm. release is panic=abort anyway but dev builds arent
#[inline]
fn koper_guard<R>(fallback: R, f: impl FnOnce() -> R) -> R {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

#[inline]
fn w<'a>(ptr: *mut KoperElpeWorld) -> Option<&'a mut KoperElpeWorld> {
    unsafe { ptr.as_mut() }
}

#[no_mangle]
pub extern "C" fn koper_elpe_version() -> u32 { ELPE_VERSION }

#[no_mangle]
pub extern "C" fn koper_elpe_world_new(capacity: u32, max_radius: f32) -> *mut KoperElpeWorld {
    koper_guard(std::ptr::null_mut(), || {
        Box::into_raw(Box::new(KoperElpeWorld::new(capacity as usize, max_radius)))
    })
}

#[no_mangle]
pub extern "C" fn koper_elpe_world_free(ptr: *mut KoperElpeWorld) {
    if !ptr.is_null() { unsafe { drop(Box::from_raw(ptr)) } }
}

// cfg layout (f32 x 14): gx gy gz damping friction sleep_speed sleep_ticks wake_speed
// iterations max_step terrain(0/1) floor_y kill_y threads(0 = all cores) [joint_iterations]
#[no_mangle]
pub extern "C" fn koper_elpe_configure(ptr: *mut KoperElpeWorld, cfg: *const f32, n: u32) {
    let Some(world) = w(ptr) else { return };
    if cfg.is_null() || n < 14 { return; }
    let c = unsafe { std::slice::from_raw_parts(cfg, 14) };
    let k = &mut world.cfg;
    k.gravity = [c[0], c[1], c[2]];
    k.damping = c[3].clamp(0.0, 1.0);
    k.friction = c[4].clamp(0.0, 1.0);
    k.sleep_speed = c[5].max(0.0);
    k.sleep_ticks = c[6].clamp(1.0, 255.0) as u8;
    k.wake_speed = c[7].max(0.0);
    k.iterations = c[8].clamp(1.0, 32.0) as u32;
    k.max_step = c[9].clamp(0.01, 16.0);
    k.terrain = c[10] != 0.0;
    k.floor_y = c[11];
    k.kill_y = c[12];
    let t = c[13] as usize;
    if n >= 15 { k.joint_iterations = unsafe { *cfg.add(14) }.clamp(1.0, 64.0) as u32; }
    k.threads = if t == 0 { std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1) } else { t };
}

#[no_mangle]
pub extern "C" fn koper_elpe_step(ptr: *mut KoperElpeWorld, dt: f32, substeps: u32) {
    if let Some(world) = w(ptr) { koper_guard((), || world.step(dt, substeps)); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_spawn(ptr: *mut KoperElpeWorld, x: f32, y: f32, z: f32, r: f32, inv_mass: f32, group: u32) -> u32 {
    w(ptr).map_or(NONE, |world| world.spawn([x, y, z], r, inv_mass, group))
}

// xyz packed floats, n points, ids written to out_ids (can be null). returns how many spawned.
// asleep != 0 = loading a save, nothing moves until poked
#[no_mangle]
pub extern "C" fn koper_elpe_spawn_bulk(
    ptr: *mut KoperElpeWorld, xyz: *const f32, n: u32, r: f32, inv_mass: f32, group: u32, asleep: u32, out_ids: *mut u32,
) -> u32 {
    let Some(world) = w(ptr) else { return 0 };
    if xyz.is_null() { return 0; }
    let src = unsafe { std::slice::from_raw_parts(xyz, n as usize * 3) };
    let mut ok = 0u32;
    for k in 0..n as usize {
        let p = [src[k * 3], src[k * 3 + 1], src[k * 3 + 2]];
        let id = if asleep != 0 { world.spawn_asleep(p, r, inv_mass, group) } else { world.spawn(p, r, inv_mass, group) };
        if !out_ids.is_null() { unsafe { *out_ids.add(k) = id; } }
        if id != NONE { ok += 1; }
    }
    ok
}

#[no_mangle]
pub extern "C" fn koper_elpe_despawn(ptr: *mut KoperElpeWorld, id: u32) {
    if let Some(world) = w(ptr) { world.despawn(id); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_set_pos(ptr: *mut KoperElpeWorld, id: u32, x: f32, y: f32, z: f32, keep_velocity: u32) {
    if let Some(world) = w(ptr) { world.set_pos(id, [x, y, z], keep_velocity != 0); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_add_velocity(ptr: *mut KoperElpeWorld, id: u32, vx: f32, vy: f32, vz: f32) {
    if let Some(world) = w(ptr) { world.add_velocity(id, [vx, vy, vz]); }
}

// out: x y z vx vy vz state  (7 floats). returns 0 if id is dead
#[no_mangle]
pub extern "C" fn koper_elpe_get(ptr: *mut KoperElpeWorld, id: u32, out: *mut f32) -> u32 {
    let Some(world) = w(ptr) else { return 0 };
    let iu = id as usize;
    if out.is_null() || iu >= world.state.len() || world.state[iu] == world::DEAD { return 0; }
    let p = world.pos[iu];
    let v = world.velocity(id);
    let o = unsafe { std::slice::from_raw_parts_mut(out, 7) };
    o.copy_from_slice(&[p[0], p[1], p[2], v[0], v[1], v[2], world.state[iu] as f32]);
    1
}

#[no_mangle]
pub extern "C" fn koper_elpe_wake(ptr: *mut KoperElpeWorld, id: u32) {
    if let Some(world) = w(ptr) { world.wake(id); }
}

// b = NONE pins a to the world point (ax ay az). min..max is the allowed distance,
// min=max rigid stick, min=0 rope, stiffness<1 spring. snap <= 0 never breaks
#[no_mangle]
pub extern "C" fn koper_elpe_joint(
    ptr: *mut KoperElpeWorld, a: u32, b: u32, min: f32, max: f32, stiffness: f32, snap: f32,
    ax: f32, ay: f32, az: f32,
) -> u32 {
    let Some(world) = w(ptr) else { return NONE };
    let (min, max) = (min.max(0.0), max.max(min.max(0.0)));
    world.joint(KoperJoint { a, b, min, max, stiffness: stiffness.clamp(0.0, 1.0), snap, anchor: [ax, ay, az], alive: true })
}

// rest length = current distance. the lazy way to build a body out of already spawned points
#[no_mangle]
pub extern "C" fn koper_elpe_joint_here(ptr: *mut KoperElpeWorld, a: u32, b: u32, stiffness: f32, snap: f32) -> u32 {
    let Some(world) = w(ptr) else { return NONE };
    let (au, bu) = (a as usize, b as usize);
    if au >= world.pos.len() || bu >= world.pos.len() { return NONE; }
    let (pa, pb) = (world.pos[au], world.pos[bu]);
    let d = ((pa[0] - pb[0]).powi(2) + (pa[1] - pb[1]).powi(2) + (pa[2] - pb[2]).powi(2)).sqrt();
    world.joint(KoperJoint { a, b, min: d, max: d, stiffness: stiffness.clamp(0.0, 1.0), snap, anchor: [0.0; 3], alive: true })
}

#[no_mangle]
pub extern "C" fn koper_elpe_unjoint(ptr: *mut KoperElpeWorld, id: u32) {
    if let Some(world) = w(ptr) { world.unjoint(id); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_joint_alive(ptr: *mut KoperElpeWorld, id: u32) -> u32 {
    w(ptr).map_or(0, |world| world.joints.get(id as usize).is_some_and(|j| j.alive) as u32)
}

// bits = 64 u64 words, bit index = (y<<8)|(z<<4)|x inside the section. null = all air
#[no_mangle]
pub extern "C" fn koper_elpe_section(ptr: *mut KoperElpeWorld, sx: i32, sy: i32, sz: i32, bits: *const u64) {
    let Some(world) = w(ptr) else { return };
    let b = if bits.is_null() {
        None
    } else {
        let mut boxed = Box::new([0u64; 64]);
        boxed.copy_from_slice(unsafe { std::slice::from_raw_parts(bits, 64) });
        Some(boxed)
    };
    world.set_section(pack_section(sx, sy, sz), b);
}

#[no_mangle]
pub extern "C" fn koper_elpe_forget_section(ptr: *mut KoperElpeWorld, sx: i32, sy: i32, sz: i32) {
    if let Some(world) = w(ptr) { world.terrain.map.remove(&pack_section(sx, sy, sz)); }
}

// returns 1 if that section was cached and got the edit
#[no_mangle]
pub extern "C" fn koper_elpe_block(ptr: *mut KoperElpeWorld, x: i32, y: i32, z: i32, solid: u32) -> u32 {
    w(ptr).map_or(0, |world| world.set_block(x, y, z, solid != 0) as u32)
}

// sections the engine wants, as sx sy sz triples. returns how many triples were written
#[no_mangle]
pub extern "C" fn koper_elpe_requests(ptr: *mut KoperElpeWorld, out: *mut i32, max_triples: u32) -> u32 {
    let Some(world) = w(ptr) else { return 0 };
    if out.is_null() { return 0; }
    let o = unsafe { std::slice::from_raw_parts_mut(out, max_triples as usize * 3) };
    world.drain_requests(o) as u32
}

#[no_mangle]
pub extern "C" fn koper_elpe_query_sphere(ptr: *mut KoperElpeWorld, x: f32, y: f32, z: f32, r: f32, out: *mut u32, max: u32) -> u32 {
    let Some(world) = w(ptr) else { return 0 };
    let mut n = 0u32;
    world.query_sphere([x, y, z], r, |j| {
        if n < max && !out.is_null() { unsafe { *out.add(n as usize) = j; } }
        n += 1;
    });
    n.min(max)
}

#[no_mangle]
pub extern "C" fn koper_elpe_wake_sphere(ptr: *mut KoperElpeWorld, x: f32, y: f32, z: f32, r: f32) -> u32 {
    w(ptr).map_or(0, |world| world.wake_sphere([x, y, z], r))
}

#[no_mangle]
pub extern "C" fn koper_elpe_blast(ptr: *mut KoperElpeWorld, x: f32, y: f32, z: f32, r: f32, speed: f32) -> u32 {
    w(ptr).map_or(0, |world| world.blast([x, y, z], r, speed))
}

// zero copy views. pointers are valid until the next spawn (vec can grow), re-fetch every tick
#[no_mangle]
pub extern "C" fn koper_elpe_positions(ptr: *mut KoperElpeWorld) -> *const f32 {
    w(ptr).map_or(std::ptr::null(), |world| world.pos.as_ptr() as *const f32)
}

#[no_mangle]
pub extern "C" fn koper_elpe_states(ptr: *mut KoperElpeWorld) -> *const u8 {
    w(ptr).map_or(std::ptr::null(), |world| world.state.as_ptr())
}

#[no_mangle]
pub extern "C" fn koper_elpe_awake_ids(ptr: *mut KoperElpeWorld) -> *const u32 {
    w(ptr).map_or(std::ptr::null(), |world| world.awake.as_ptr())
}

#[no_mangle]
pub extern "C" fn koper_elpe_high_water(ptr: *mut KoperElpeWorld) -> u32 {
    w(ptr).map_or(0, |world| world.high_water() as u32)
}

// out: live awake asleep frozen joints sections step_us snapped (8 u32)
#[no_mangle]
pub extern "C" fn koper_elpe_stats(ptr: *mut KoperElpeWorld, out: *mut u32) {
    let Some(world) = w(ptr) else { return };
    if out.is_null() { return; }
    let s = world.stats;
    let o = unsafe { std::slice::from_raw_parts_mut(out, 8) };
    o.copy_from_slice(&[s.live, s.awake, s.asleep, s.frozen, s.joints, s.sections, s.step_us, s.snapped]);
}

#[cfg(test)]
mod tests;

// ── klocs: kontraptions ──────────────────────────────────────────────────────
// mirrors the khysics abi close enough that ElpeKoperer on the java side is a thin wrapper.
// ids are u64 like khysics, packed (gen << 32) | slot.

use kloc::{KlocJoint, HINGE, SLIDER, NONE as KLOC_NONE};

// offsets: 3 floats per block, local to the body centre. masses are ignored past their total —
// elpe has no inertia tensor to feed them into, so all a mass does here is scale inv_mass
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_spawn(ptr: *mut KoperElpeWorld, offsets: *const f32, masses: *const f32,
                                        count: u32, x: f32, y: f32, z: f32) -> u64 {
    koper_guard(u64::MAX, || {
        let Some(world) = w(ptr) else { return u64::MAX };
        if offsets.is_null() || count == 0 { return u64::MAX; }
        let off = unsafe { std::slice::from_raw_parts(offsets, count as usize * 3) };
        let mut blocks = Vec::with_capacity(count as usize);
        for i in 0..count as usize {
            blocks.push([off[i * 3].round() as i16, off[i * 3 + 1].round() as i16, off[i * 3 + 2].round() as i16]);
        }
        let total: f32 = if masses.is_null() { count as f32 } else {
            unsafe { std::slice::from_raw_parts(masses, count as usize) }.iter().sum()
        };
        let inv = if total > 0.0 { 1.0 / total } else { 0.0 };
        let i = world.klocs.spawn(blocks, inv, [x, y, z]);
        world.klocs.id_of(i)
    })
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_free(ptr: *mut KoperElpeWorld, id: u64) {
    let Some(world) = w(ptr) else { return };
    if let Some(i) = world.klocs.by_id(id) { world.klocs.despawn(i); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_transforms(ptr: *mut KoperElpeWorld, out: *mut f32, cap: u32) -> u32 {
    koper_guard(0, || {
        let Some(world) = w(ptr) else { return 0 };
        if out.is_null() || cap == 0 { return 0; }
        let buf = unsafe { std::slice::from_raw_parts_mut(out, cap as usize * 10) };
        world.klocs.transforms(buf) as u32
    })
}

// quaternion is taken but thrown away — nothing in elpe holds an orientation. saying so here
// beats java wondering why its rotation never came back
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_set_transform(ptr: *mut KoperElpeWorld, id: u64, x: f32, y: f32, z: f32,
                                                _qx: f32, _qy: f32, _qz: f32, _qw: f32) -> i32 {
    let Some(world) = w(ptr) else { return -1 };
    let Some(i) = world.klocs.by_id(id) else { return -1 };
    world.klocs.set_pos(i, [x, y, z], false);
    0
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_set_velocity(ptr: *mut KoperElpeWorld, id: u64, vx: f32, vy: f32, vz: f32) {
    let Some(world) = w(ptr) else { return };
    let h = 0.05f32;
    if let Some(i) = world.klocs.by_id(id) { world.klocs.set_velocity(i, [vx, vy, vz], h); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_impulse(ptr: *mut KoperElpeWorld, id: u64, ix: f32, iy: f32, iz: f32, group: i32) {
    let Some(world) = w(ptr) else { return };
    let Some(i) = world.klocs.by_id(id) else { return };
    let h = 0.05f32;
    if group == 0 {
        world.klocs.add_velocity(i, [ix, iy, iz], h);
        return;
    }
    let mut buf = Vec::new();
    world.klocs.group_of(i, &mut buf);
    for k in buf { world.klocs.add_velocity(k, [ix, iy, iz], h); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_block(ptr: *mut KoperElpeWorld, id: u64, lx: i32, ly: i32, lz: i32, add: i32) -> i32 {
    let Some(world) = w(ptr) else { return -1 };
    let Some(i) = world.klocs.by_id(id) else { return -1 };
    let off = [lx as i16, ly as i16, lz as i16];
    let ok = if add != 0 { world.klocs.add_block(i, off) } else { world.klocs.remove_block(i, off) };
    if ok { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_flags(ptr: *mut KoperElpeWorld, id: u64, sleep_ok: i32, parked: i32, damping: f32) {
    let Some(world) = w(ptr) else { return };
    let Some(i) = world.klocs.by_id(id) else { return };
    let Some(k) = world.klocs.klocs.get_mut(i as usize) else { return };
    k.sleep_ok = sleep_ok != 0;
    if damping > 0.0 { k.damping = damping.clamp(0.0, 1.0); }
    if parked != 0 {
        k.state = kloc::KLOC_PARKED;
        k.prev = k.pos;
    } else if k.state == kloc::KLOC_PARKED {
        k.state = kloc::KLOC_AWAKE;
        k.still = 0;
    }
}

// kind 0 = hinge (revolute), 1 = slider (prismatic). b = u64::MAX pins to the world
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_joint(ptr: *mut KoperElpeWorld, kind: i32, a: u64, b: u64,
                                        ax: f32, ay: f32, az: f32,
                                        bx: f32, by: f32, bz: f32,
                                        axis_x: f32, axis_y: f32, axis_z: f32) -> u64 {
    koper_guard(u64::MAX, || {
        let Some(world) = w(ptr) else { return u64::MAX };
        let Some(ia) = world.klocs.by_id(a) else { return u64::MAX };
        let ib = if b == u64::MAX { KLOC_NONE } else {
            match world.klocs.by_id(b) { Some(v) => v, None => return u64::MAX }
        };
        // a world joint's anchor is where it is right now, in world coords
        let anchor_b = if ib == KLOC_NONE {
            let p = world.klocs.klocs[ia as usize].pos;
            [p[0] + ax, p[1] + ay, p[2] + az]
        } else { [bx, by, bz] };
        let j = KlocJoint {
            a: ia, b: ib,
            anchor_a: [ax, ay, az],
            anchor_b,
            axis: [axis_x, axis_y, axis_z],
            kind: if kind == 1 { SLIDER } else { HINGE },
            min: 0.0, max: 0.0, limited: false,
            motor_on: false, motor_vel: 0.0, motor_force: 0.0,
            spring_on: false, rest: 0.0, stiffness: 0.0, damping: 0.0,
            pos: 0.0, vel: 0.0, alive: true,
        };
        world.klocs.joint(j) as u64
    })
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_unjoint(ptr: *mut KoperElpeWorld, id: u64) {
    let Some(world) = w(ptr) else { return };
    world.klocs.unjoint(id as u32);
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_motor(ptr: *mut KoperElpeWorld, id: u64, vel: f32, force: f32) {
    let Some(world) = w(ptr) else { return };
    world.klocs.set_motor(id as u32, vel, force);
}

// khysics position motor: target, stiffness, damping, max force
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_spring(ptr: *mut KoperElpeWorld, id: u64, rest: f32,
                                         stiffness: f32, damping: f32, max_force: f32) {
    let Some(world) = w(ptr) else { return };
    world.klocs.set_spring(id as u32, rest, stiffness, damping, max_force);
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_limits(ptr: *mut KoperElpeWorld, id: u64, min: f32, max: f32, on: i32) {
    let Some(world) = w(ptr) else { return };
    if on != 0 { world.klocs.set_limits(id as u32, min, max); } else { world.klocs.clear_limits(id as u32); }
}

#[no_mangle]
pub extern "C" fn koper_elpe_kloc_joint_state(ptr: *mut KoperElpeWorld, id: u64, out: *mut f32) -> i32 {
    let Some(world) = w(ptr) else { return 0 };
    if out.is_null() { return 0; }
    match world.klocs.joint_state(id as u32) {
        Some(v) => { unsafe { *out = v[0]; *out.add(1) = v[1]; } 1 }
        None => 0,
    }
}

// ray vs every live kloc's box. hundreds of bodies, so brute force and move on
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_raycast(ptr: *mut KoperElpeWorld, ox: f32, oy: f32, oz: f32,
                                          dx: f32, dy: f32, dz: f32, max_dist: f32,
                                          hit_out: *mut f32, id_out: *mut u64) -> i32 {
    koper_guard(0, || {
        let Some(world) = w(ptr) else { return 0 };
        let o = [ox, oy, oz];
        let dl = (dx * dx + dy * dy + dz * dz).sqrt();
        if dl < 1e-6 { return 0; }
        let d = [dx / dl, dy / dl, dz / dl];
        let mut best = max_dist;
        let mut best_i = None;
        for (i, k) in world.klocs.klocs.iter().enumerate() {
            if k.state == kloc::KLOC_DEAD || k.blocks.is_empty() { continue; }
            let (mut t0, mut t1) = (0.0f32, best);
            let mut miss = false;
            for a in 0..3 {
                let (lo, hi) = (k.pos[a] - k.half[a], k.pos[a] + k.half[a]);
                if d[a].abs() < 1e-6 {
                    if o[a] < lo || o[a] > hi { miss = true; break; }
                    continue;
                }
                let inv = 1.0 / d[a];
                let (mut ta, mut tb) = ((lo - o[a]) * inv, (hi - o[a]) * inv);
                if ta > tb { std::mem::swap(&mut ta, &mut tb); }
                t0 = t0.max(ta);
                t1 = t1.min(tb);
                if t0 > t1 { miss = true; break; }
            }
            if miss || t0 < 0.0 || t0 >= best { continue; }
            best = t0;
            best_i = Some(i as u32);
        }
        let Some(i) = best_i else { return 0 };
        if !hit_out.is_null() {
            unsafe {
                *hit_out = o[0] + d[0] * best;
                *hit_out.add(1) = o[1] + d[1] * best;
                *hit_out.add(2) = o[2] + d[2] * best;
            }
        }
        if !id_out.is_null() { unsafe { *id_out = world.klocs.id_of(i); } }
        1
    })
}

// [live, awake, joints]
#[no_mangle]
pub extern "C" fn koper_elpe_kloc_stats(ptr: *mut KoperElpeWorld, out: *mut u32) -> i32 {
    let Some(world) = w(ptr) else { return 0 };
    if out.is_null() { return 0; }
    let s = world.klocs.stats();
    unsafe { *out = s.live; *out.add(1) = s.awake; *out.add(2) = s.joints; }
    1
}
