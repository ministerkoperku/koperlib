// koperlib-khysics — Rapier physics, 60Hz dedicated thread per MC dimension
// Java never touches KhysWorld directly — commands in, snapshots out
pub mod obb;
pub mod sections;
pub mod terrain;
pub mod world;

use world::{KhysWorld, NEXT_KONTRA_ID, PhysicsCmd, WorldSnapshot, BreakMode, Fragment};

use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock, OnceLock};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::panic;
use std::thread;
use std::time::{Duration, Instant};

struct WorldEntry {
    cmd_queue: Arc<Mutex<Vec<PhysicsCmd>>>,
    snapshot:  Arc<RwLock<WorldSnapshot>>,
    // per-phase step cost in ms, published by the physics thread once a second
    profile:   Arc<RwLock<[f32; 6]>>,
    fragments: Arc<Mutex<Vec<(i64, Vec<Fragment>)>>>,
    // split events: parent_id → list of disconnected component block lists
    splits:    Arc<Mutex<Vec<(i64, Vec<Vec<[i32; 3]>>)>>>,
    running:   Arc<AtomicBool>,
    // wall-clock millis of the last heartbeat from Java. Java pings every server tick; when the game
    // pauses the server stops ticking → no pings → the physics thread freezes so ships don't drift while
    // you're in the pause menu (you'd unpause to find yourself standing under your own ship).
    heartbeat: Arc<AtomicI64>,
    handle:    Option<thread::JoinHandle<()>>,
}

fn now_millis() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn worlds() -> &'static Mutex<HashMap<i64, WorldEntry>> {
    static W: OnceLock<Mutex<HashMap<i64, WorldEntry>>> = OnceLock::new();
    W.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_WORLD: AtomicI64 = AtomicI64::new(1);
static NEXT_JOINT: AtomicI64 = AtomicI64::new(1);

// freed kontraktion ids waiting to be reused — on destroy an id goes back here, the next spawn pops it
// instead of always bumping the counter. dedup on free so a double-destroy can't hand one id to two bodies
fn alloc_kontra_id() -> i64 {
    NEXT_KONTRA_ID.fetch_add(1, Ordering::Relaxed)
}

fn push_cmd(world_id: i64, cmd: PhysicsCmd) {
    if let Ok(map) = worlds().lock() {
        if let Some(entry) = map.get(&world_id) {
            if let Ok(mut q) = entry.cmd_queue.lock() { q.push(cmd); }
        }
    }
}

fn get_snapshot(world_id: i64) -> Option<Arc<RwLock<WorldSnapshot>>> {
    worlds().lock().ok()?.get(&world_id).map(|e| e.snapshot.clone())
}

fn get_profile(world_id: i64) -> Option<Arc<RwLock<[f32; 6]>>> {
    worlds().lock().ok()?.get(&world_id).map(|e| e.profile.clone())
}

// [total, sections, fluid+aero, solver, joint projection, post passes] in ms, averaged over the last
// 60 steps. -1 = no such world
#[no_mangle]
pub extern "C" fn koper_khysics_profile(world_id: i64, out: *mut f32, cap: i32) -> i32 {
    if out.is_null() || cap < 6 { return -1; }
    let arc = match get_profile(world_id) { Some(a) => a, None => return -1 };
    let values = match arc.read() { Ok(v) => *v, Err(_) => return -1 };
    unsafe { std::ptr::copy_nonoverlapping(values.as_ptr(), out, 6); }
    6
}

fn get_fragments(world_id: i64) -> Option<Arc<Mutex<Vec<(i64, Vec<Fragment>)>>>> {
    worlds().lock().ok()?.get(&world_id).map(|e| e.fragments.clone())
}

fn get_splits(world_id: i64) -> Option<Arc<Mutex<Vec<(i64, Vec<Vec<[i32; 3]>>)>>> > {
    worlds().lock().ok()?.get(&world_id).map(|e| e.splits.clone())
}

// ── world lifecycle ──────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn koper_khysics_create_world() -> i64 {
    let id        = NEXT_WORLD.fetch_add(1, Ordering::Relaxed);
    let cmd_queue = Arc::new(Mutex::new(Vec::<PhysicsCmd>::new()));
    let snapshot  = Arc::new(RwLock::new(WorldSnapshot::default()));
    let profile   = Arc::new(RwLock::new([0f32; 6]));
    let fragments = Arc::new(Mutex::new(Vec::<(i64, Vec<Fragment>)>::new()));
    let splits    = Arc::new(Mutex::new(Vec::<(i64, Vec<Vec<[i32; 3]>>)>::new()));
    let running   = Arc::new(AtomicBool::new(true));
    let heartbeat = Arc::new(AtomicI64::new(now_millis()));

    let cq2 = cmd_queue.clone();
    let sn2 = snapshot.clone();
    let pr2 = profile.clone();
    let fr2 = fragments.clone();
    let sp2 = splits.clone();
    let r2  = running.clone();
    let hb2 = heartbeat.clone();

    // physics thread owns KhysWorld — Java never locks it
    let handle = thread::spawn(move || {
        const DT: Duration = Duration::from_nanos(16_666_666); // 60Hz target
        const MAX_STEPS: u32 = 4; // don't spiral if we fall behind
        let mut world = KhysWorld::new();
        let mut last_time = Instant::now();
        let mut accumulator = Duration::ZERO;

        loop {
            if !r2.load(Ordering::Relaxed) { break; }

            // game paused (server stopped pinging) → freeze. reset the clock so we don't catch up a huge
            // backlog on resume, and don't step at all while paused.
            if now_millis() - hb2.load(Ordering::Relaxed) > 300 {
                last_time = Instant::now();
                accumulator = Duration::ZERO;
                thread::sleep(Duration::from_millis(20));
                continue;
            }

            let now = Instant::now();
            let delta = now.duration_since(last_time);
            last_time = now;
            // cap accumulator so we don't run 100 steps to catch up after a hitch
            accumulator = (accumulator + delta).min(DT * MAX_STEPS);

            while accumulator >= DT {
                accumulator -= DT;

                let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
                    let cmds = {
                        let mut q = match cq2.lock() { Ok(q) => q, Err(_) => return };
                        std::mem::take(&mut *q)
                    };
                    for cmd in cmds {
                        if let PhysicsCmd::Fence(reply)=cmd {
                            if let Ok(mut snapshot)=sn2.write() {
                                *snapshot=world.build_snapshot();
                                let _=reply.send(WorldSnapshot::default());
                            }
                        } else {world.process_cmd(cmd);}
                    }

                    let new_frags = world.step(1.0 / 60.0);
                    if !new_frags.is_empty() {
                        if let Ok(mut f) = fr2.lock() { f.extend(new_frags); }
                    }

                    let new_splits = world.take_pending_splits();
                    if !new_splits.is_empty() {
                        if let Ok(mut s) = sp2.lock() { s.extend(new_splits); }
                    }

                    if let Ok(mut snap) = sn2.write() {
                        *snap = world.build_snapshot();
                    }

                    if world.step_count() % 60 == 0 {
                        if let Ok(mut p) = pr2.write() { *p = world.profile(); }
                    }
                }));

                if result.is_err() {
                    eprintln!("[Khysics] tick panicked — thread lives, state may be dirty");
                }
            }

            // sleep remaining time — imprecision is self-correcting via accumulator
            let remaining = DT.saturating_sub(accumulator);
            if remaining > Duration::from_micros(500) {
                thread::sleep(remaining);
            }
        }
    });

    if let Ok(mut map) = worlds().lock() {
        map.insert(id, WorldEntry { cmd_queue, snapshot, profile, fragments, splits, running, heartbeat, handle: Some(handle) });
    }
    id
}

// Java pings this every server tick — keeps the physics thread running. no ping (game paused) → it freezes.
#[no_mangle]
pub extern "C" fn koper_khysics_heartbeat(world_id: i64) {
    if let Ok(map) = worlds().lock() {
        if let Some(e) = map.get(&world_id) { e.heartbeat.store(now_millis(), Ordering::Relaxed); }
    }
}

#[no_mangle]
pub extern "C" fn koper_khysics_destroy_world(world_id: i64) {
    // pull the entry out, signal stop, then JOIN the physics thread so it's fully gone before we
    // return — no lingering native thread on world unload / shutdown
    let entry = worlds().lock().ok().and_then(|mut m| m.remove(&world_id));
    if let Some(mut entry) = entry {
        entry.running.store(false, Ordering::Relaxed);
        if let Some(h) = entry.handle.take() { let _ = h.join(); }
    }
}

// legacy no-op — step is now driven by physics thread, not server tick
#[no_mangle]
pub extern "C" fn koper_khysics_step(_world_id: i64, _dt: f32) {}

// ── kontraktion management ───────────────────────────────────────────────────

// ID is allocated here and returned to Java immediately — spawn happens async
#[no_mangle]
pub extern "C" fn koper_khysics_spawn_kontraktion(
    world_id: i64,
    block_data: *const i32,
    mass_data:  *const f32,
    block_count: i32,
    light_count: i32,
    spawn_x: f32, spawn_y: f32, spawn_z: f32,
) -> i64 {
    if block_data.is_null() || mass_data.is_null() || block_count <= 0 { return -1; }
    let blocks: Vec<[i32; 3]> = unsafe {
        (0..block_count as usize).map(|i| {
            let p = block_data.add(i * 3);
            [*p, *p.add(1), *p.add(2)]
        }).collect()
    };
    let masses: Vec<f32> = unsafe {
        std::slice::from_raw_parts(mass_data, block_count as usize).to_vec()
    };
    let id = alloc_kontra_id();
    push_cmd(world_id, PhysicsCmd::SpawnKontra {
        id, positions: blocks, masses,
        light_count: light_count.max(0) as usize,
        spawn_pos: [spawn_x, spawn_y, spawn_z],
    });
    id
}

// spawn a kontra from float local offsets — persistence restore path
#[no_mangle]
pub extern "C" fn koper_khysics_spawn_kontraktion_offsets(
    world_id: i64,
    offset_data: *const f32,
    mass_data:  *const f32,
    block_count: i32,
    light_count: i32,
    spawn_x: f32, spawn_y: f32, spawn_z: f32,
) -> i64 {
    if offset_data.is_null() || mass_data.is_null() || block_count <= 0 { return -1; }
    let offsets: Vec<[f32; 3]> = unsafe {
        (0..block_count as usize).map(|i| {
            let p = offset_data.add(i * 3);
            [*p, *p.add(1), *p.add(2)]
        }).collect()
    };
    let masses: Vec<f32> = unsafe {
        std::slice::from_raw_parts(mass_data, block_count as usize).to_vec()
    };
    let id = alloc_kontra_id();
    push_cmd(world_id, PhysicsCmd::SpawnKontraOffsets {
        id, offsets, masses,
        light_count: light_count.max(0) as usize,
        spawn_pos: [spawn_x, spawn_y, spawn_z],
    });
    id
}

// per-entry layout: [id_lo_i32, id_hi_i32, cx, cy, cz, qx, qy, qz, qw, flags] — 10 floats
// flags bit 0 = aligned (resting on the world grid). returns count of entries written
#[no_mangle]
pub extern "C" fn koper_khysics_get_all_transforms(
    world_id: i64, out_buf: *mut f32, buf_capacity: i32,
) -> i32 {
    if out_buf.is_null() || buf_capacity <= 0 { return 0; }
    let arc = match get_snapshot(world_id) { Some(a) => a, None => return 0 };
    let snap = match arc.read() { Ok(s) => s, Err(_) => return 0 };
    let count = (snap.kontras.len() as i32).min(buf_capacity);
    for (i, (id, k)) in snap.kontras.iter().take(count as usize).enumerate() {
        unsafe {
            let base = out_buf.add(i * 10);
            let id_bits = *id as u64;
            *base = f32::from_bits((id_bits & 0xFFFF_FFFF) as u32);
            *base.add(1) = f32::from_bits((id_bits >> 32) as u32);
            *base.add(2) = k.pos[0]; *base.add(3) = k.pos[1]; *base.add(4) = k.pos[2];
            *base.add(5) = k.rot[0]; *base.add(6) = k.rot[1];
            *base.add(7) = k.rot[2]; *base.add(8) = k.rot[3];
            *base.add(9) = if k.aligned { 1.0 } else { 0.0 };
        }
    }
    count
}

// ── section terrain streaming ────────────────────────────────────────────────

// sections physics wants but hasn't been given yet — [sx,sy,sz] triples, returns triple count
#[no_mangle]
pub extern "C" fn koper_khysics_wanted_sections(
    world_id: i64, out_buf: *mut i32, max_sections: i32,
) -> i32 {
    if out_buf.is_null() || max_sections <= 0 { return 0; }
    let arc = match get_snapshot(world_id) { Some(a) => a, None => return 0 };
    let snap = match arc.read() { Ok(s) => s, Err(_) => return 0 };
    let count = (snap.missing_sections.len() as i32).min(max_sections);
    for (i, s) in snap.missing_sections.iter().take(count as usize).enumerate() {
        unsafe {
            let base = out_buf.add(i * 3);
            *base = s[0]; *base.add(1) = s[1]; *base.add(2) = s[2];
        }
    }
    count
}

// full 16^3 occupancy for one section: 64 u64 words, bit idx = (ly*16+lz)*16+lx
#[no_mangle]
pub extern "C" fn koper_khysics_upload_section(
    world_id: i64, sx: i32, sy: i32, sz: i32, bits: *const u64,
) {
    if bits.is_null() { return; }
    let mut boxed: Box<[u64; 64]> = Box::new([0u64; 64]);
    unsafe { std::ptr::copy_nonoverlapping(bits, boxed.as_mut_ptr(), 64); }
    push_cmd(world_id, PhysicsCmd::UploadSection { pos: [sx, sy, sz], bits: boxed });
}

// one world cell changed (block placed/broken) — physics flips the bit if it caches that section
#[no_mangle]
pub extern "C" fn koper_khysics_set_terrain_block(
    world_id: i64, x: i32, y: i32, z: i32, solid: i32,
) {
    push_cmd(world_id, PhysicsCmd::SetTerrainBlock { pos: [x, y, z], solid: solid != 0 });
}

// fluid occupancy for one section — Java sends this right AFTER the solids upload of the same
// section (order matters: fluids only land on known sections)
#[no_mangle]
pub extern "C" fn koper_khysics_upload_section_fluids(
    world_id: i64, sx: i32, sy: i32, sz: i32, bits: *const u64,
) {
    if bits.is_null() { return; }
    let mut boxed: Box<[u64; 64]> = Box::new([0u64; 64]);
    unsafe { std::ptr::copy_nonoverlapping(bits, boxed.as_mut_ptr(), 64); }
    push_cmd(world_id, PhysicsCmd::UploadSectionFluids { pos: [sx, sy, sz], bits: boxed });
}

// one world cell got/lost water (bucket, flow) — same relay as set_terrain_block
#[no_mangle]
pub extern "C" fn koper_khysics_set_fluid_block(
    world_id: i64, x: i32, y: i32, z: i32, fluid: i32,
) {
    push_cmd(world_id, PhysicsCmd::SetFluidBlock { pos: [x, y, z], fluid: fluid != 0 });
}

// per-block water displacement volumes — 4 floats per entry [lx,ly,lz,volume], resent like aero.
// count==0 clears back to the all-1.0 default
#[no_mangle]
pub extern "C" fn koper_khysics_set_buoyancy(
    world_id: i64, kontra_id: i64, data: *const f32, count: i32,
) {
    let entries: Vec<([i32; 3], f32)> = if data.is_null() || count <= 0 {
        Vec::new()
    } else {
        unsafe {
            (0..count as usize).map(|i| {
                let p = data.add(i * 4);
                ([jround(*p), jround(*p.add(1)), jround(*p.add(2))], *p.add(3))
            }).collect()
        }
    };
    push_cmd(world_id, PhysicsCmd::SetBuoyancy { id: kontra_id, entries });
}

// displaced mass per full block of water — the one knob for how hard water pushes up
#[no_mangle]
pub extern "C" fn koper_khysics_set_water_density(world_id: i64, density: f32) {
    push_cmd(world_id, PhysicsCmd::SetWaterDensity(density));
}

// Java's Math.round(float) — same helper as world.rs, for the buoyancy entry keys
#[inline] fn jround(v: f32) -> i32 { (v + 0.5).floor() as i32 }

#[no_mangle]
pub extern "C" fn koper_khysics_apply_force(
    world_id: i64, kontra_id: i64, fx: f32, fy: f32, fz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyForce { id: kontra_id, f: [fx, fy, fz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_apply_torque(
    world_id: i64, kontra_id: i64, tx: f32, ty: f32, tz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyTorque { id: kontra_id, t: [tx, ty, tz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_apply_impulse(
    world_id: i64, kontra_id: i64, ix: f32, iy: f32, iz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyImpulse { id: kontra_id, imp: [ix, iy, iz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_apply_impulse_group(
    world_id: i64, kontra_id: i64, ix: f32, iy: f32, iz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyImpulseGroup { id: kontra_id, imp: [ix, iy, iz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_apply_impulse_at_point(
    world_id: i64, kontra_id: i64,
    ix: f32, iy: f32, iz: f32,
    px: f32, py: f32, pz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyImpulseAtPoint {
        id: kontra_id,
        imp: [ix, iy, iz],
        point: [px, py, pz],
    });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_block_materials(
    world_id: i64, kontra_id: i64, data: *const f32, count: i32,
) {
    if data.is_null() || count <= 0 { return; }
    let materials = unsafe {
        (0..count as usize).map(|i| {
            let p = data.add(i * 15);
            [*p, *p.add(1), *p.add(2), *p.add(3), *p.add(4), *p.add(5),
             *p.add(6), *p.add(7), *p.add(8), *p.add(9), *p.add(10), *p.add(11),
             *p.add(12), *p.add(13), *p.add(14)]
        }).collect()
    };
    push_cmd(world_id, PhysicsCmd::SetBlockMaterials { id: kontra_id, materials });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_block_shapes(
    world_id: i64, kontra_id: i64, data: *const f32, count: i32,
) {
    if data.is_null() || count <= 0 { return; }
    let shapes = unsafe {
        (0..count as usize).map(|i| {
            let p = data.add(i * 11);
            [*p, *p.add(1), *p.add(2), *p.add(3), *p.add(4), *p.add(5),
             *p.add(6), *p.add(7), *p.add(8), *p.add(9), *p.add(10)]
        }).collect()
    };
    push_cmd(world_id, PhysicsCmd::SetBlockShapes { id: kontra_id, shapes });
}

// blocks/s, overwrites whatever the body was doing — flight stick control
#[no_mangle]
pub extern "C" fn koper_khysics_set_velocity(
    world_id: i64, kontra_id: i64, vx: f32, vy: f32, vz: f32,
) {
    push_cmd(world_id, PhysicsCmd::SetVelocity { id: kontra_id, v: [vx, vy, vz] });
}

// mode: 0=bounce 1=destructible
#[no_mangle]
pub extern "C" fn koper_khysics_set_break_mode(world_id: i64, kontra_id: i64, mode: i32) {
    let m = if mode == 1 { BreakMode::Destructible } else { BreakMode::Bounce };
    push_cmd(world_id, PhysicsCmd::SetBreakMode { id: kontra_id, mode: m });
}

#[no_mangle]
pub extern "C" fn koper_khysics_destroy_kontraktion(world_id: i64, kontra_id: i64) {
    push_cmd(world_id, PhysicsCmd::DestroyKontra(kontra_id));
}

// teleport to saved pos+rot, zeroes velocity — for persistence restore
#[no_mangle]
pub extern "C" fn koper_khysics_set_transform(
    world_id: i64, kontra_id: i64,
    px: f32, py: f32, pz: f32,
    qx: f32, qy: f32, qz: f32, qw: f32,
) -> i32 {
    push_cmd(world_id, PhysicsCmd::SetTransform {
        id: kontra_id, pos: [px, py, pz], rot: [qx, qy, qz, qw]
    });
    0
}

// drain fragment events from last N steps
// buf layout per frag: [cx, cy, cz, vx, vy, vz, block_count(f32)] — 7 floats
// returns count written, or -1 on error
#[no_mangle]
pub extern "C" fn koper_khysics_drain_fragments(
    world_id: i64, parent_id: i64,
    out_buf: *mut f32, buf_capacity: i32,
) -> i32 {
    if out_buf.is_null() { return -1; }
    let arc = match get_fragments(world_id) { Some(a) => a, None => return -1 };
    let mut frags_map = match arc.lock() { Ok(f) => f, Err(_) => return -1 };

    let pos = frags_map.iter().position(|(id, _)| *id == parent_id);
    let (_, frags) = match pos.map(|i| frags_map.remove(i)) {
        Some(pair) => pair,
        None => return 0,
    };

    let to_write = (frags.len() as i32).min(buf_capacity / 7);
    for (i, frag) in frags.iter().take(to_write as usize).enumerate() {
        unsafe {
            let ptr = out_buf.add(i * 7);
            *ptr        = frag.pos[0]; *ptr.add(1) = frag.pos[1]; *ptr.add(2) = frag.pos[2];
            *ptr.add(3) = frag.vel[0]; *ptr.add(4) = frag.vel[1]; *ptr.add(5) = frag.vel[2];
            *ptr.add(6) = frag.blocks.len() as f32;
        }
    }
    to_write
}

// ── steady pushes, per-body knobs and the pusher (the vehicle toolkit) ─────────

// force + torque re-applied every physics step until replaced. zeros = stop holding
#[no_mangle]
pub extern "C" fn koper_khysics_set_held_push(
    world_id: i64, kontra_id: i64,
    fx: f32, fy: f32, fz: f32, tx: f32, ty: f32, tz: f32,
) {
    push_cmd(world_id, PhysicsCmd::SetHeldPush { id: kontra_id, force: [fx, fy, fz], torque: [tx, ty, tz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_apply_angular_impulse(
    world_id: i64, kontra_id: i64, ix: f32, iy: f32, iz: f32,
) {
    push_cmd(world_id, PhysicsCmd::ApplyAngularImpulse { id: kontra_id, imp: [ix, iy, iz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_gravity_scale(world_id: i64, kontra_id: i64, scale: f32) {
    push_cmd(world_id, PhysicsCmd::SetGravityScale { id: kontra_id, scale });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_buoyancy_scale(world_id: i64, kontra_id: i64, scale: f32) {
    push_cmd(world_id, PhysicsCmd::SetBuoyancyScale { id: kontra_id, scale });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_pushed(world_id: i64, kontra_id: i64, on: i32) {
    push_cmd(world_id, PhysicsCmd::SetPushed { id: kontra_id, on: on != 0 });
}

// ox/oy/oz = the block's float offset (what spawn got), keyed the same way remove_block keys
#[no_mangle]
pub extern "C" fn koper_khysics_set_block_mass(
    world_id: i64, kontra_id: i64, ox: f32, oy: f32, oz: f32, mass: f32,
) {
    push_cmd(world_id, PhysicsCmd::SetBlockMass { id: kontra_id, offset: [ox, oy, oz], mass });
}

// one global pusher for every world — kontra ids are unique across worlds. null clears it.
// it runs on the PHYSICS thread; Java must never let an exception out of it
#[no_mangle]
pub extern "C" fn koper_khysics_set_pusher(f: Option<world::PushFn>) {
    let raw = f.map(|f| f as usize).unwrap_or(0);
    world::PUSHER.store(raw, Ordering::Release);
}

// latest published state of one body, world::BODY_STATE_LEN floats. returns floats written,
// 0 = body unknown to the last snapshot
#[no_mangle]
pub extern "C" fn koper_khysics_body_state(
    world_id: i64, kontra_id: i64, out_buf: *mut f32, cap: i32,
) -> i32 {
    if out_buf.is_null() || (cap as usize) < world::BODY_STATE_LEN { return 0; }
    let arc = match get_snapshot(world_id) { Some(a) => a, None => return 0 };
    let snap = match arc.read() { Ok(s) => s, Err(_) => return 0 };
    match snap.kontras.get(&kontra_id) {
        Some(k) => {
            unsafe { std::ptr::copy_nonoverlapping(k.state.as_ptr(), out_buf, world::BODY_STATE_LEN); }
            world::BODY_STATE_LEN as i32
        }
        None => 0,
    }
}

// ── joints (bearing = revolute + motor, piston = prismatic + motor) ──────────

// id handed out immediately, joint spawns async on the physics thread. anchors are local
// block-offset coords of each kontra, axis is shared by both local frames
#[no_mangle]
pub extern "C" fn koper_khysics_create_revolute_joint(
    world_id: i64, kontra_a: i64, kontra_b: i64,
    ax: f32, ay: f32, az: f32,
    bx: f32, by: f32, bz: f32,
    axis_x: f32, axis_y: f32, axis_z: f32,
) -> i64 {
    let id = NEXT_JOINT.fetch_add(1, Ordering::Relaxed);
    push_cmd(world_id, PhysicsCmd::CreateRevoluteJoint {
        joint_id: id, a: kontra_a, b: kontra_b,
        anchor_a: [ax, ay, az], anchor_b: [bx, by, bz], axis: [axis_x, axis_y, axis_z],
    });
    id
}

#[no_mangle]
pub extern "C" fn koper_khysics_create_prismatic_joint(
    world_id: i64, kontra_a: i64, kontra_b: i64,
    ax: f32, ay: f32, az: f32,
    bx: f32, by: f32, bz: f32,
    axis_x: f32, axis_y: f32, axis_z: f32,
) -> i64 {
    let id = NEXT_JOINT.fetch_add(1, Ordering::Relaxed);
    push_cmd(world_id, PhysicsCmd::CreatePrismaticJoint {
        joint_id: id, a: kontra_a, b: kontra_b,
        anchor_a: [ax, ay, az], anchor_b: [bx, by, bz], axis: [axis_x, axis_y, axis_z],
    });
    id
}

// revolute: target_vel rad/s, max torque. prismatic: blocks/s, max force. vel 0 + big force = brake
#[no_mangle]
pub extern "C" fn koper_khysics_joint_set_motor(
    world_id: i64, joint_id: i64, target_vel: f32, max_force: f32,
) {
    push_cmd(world_id, PhysicsCmd::JointSetMotor { joint_id, target_vel, max_force });
}

#[no_mangle]
pub extern "C" fn koper_khysics_joint_set_motor_position(
    world_id: i64, joint_id: i64, target_pos: f32,
    stiffness: f32, damping: f32, max_force: f32,
) {
    push_cmd(world_id, PhysicsCmd::JointSetMotorPosition {
        joint_id, target_pos, stiffness, damping, max_force, force_based: false,
    });
}

/// Position motor whose stiffness and damping are absolute forces instead of mass-scaled
/// accelerations. Springs use this so adding cargo changes their physical compression.
#[no_mangle]
pub extern "C" fn koper_khysics_joint_set_motor_position_force_based(
    world_id: i64, joint_id: i64, target_pos: f32,
    stiffness: f32, damping: f32, max_force: f32,
) {
    push_cmd(world_id, PhysicsCmd::JointSetMotorPosition {
        joint_id, target_pos, stiffness, damping, max_force, force_based: true,
    });
}

#[no_mangle]
pub extern "C" fn koper_khysics_joint_set_limits(
    world_id: i64, joint_id: i64, min: f32, max: f32,
) {
    push_cmd(world_id, PhysicsCmd::JointSetLimits { joint_id, min, max });
}

#[no_mangle]
pub extern "C" fn koper_khysics_joint_clear_limits(world_id: i64, joint_id: i64) {
    push_cmd(world_id, PhysicsCmd::JointClearLimits { joint_id });
}

#[no_mangle]
pub extern "C" fn koper_khysics_destroy_joint(world_id: i64, joint_id: i64) {
    push_cmd(world_id, PhysicsCmd::DestroyJoint(joint_id));
}

// out = [angle_rad_or_slide_blocks, velocity_along_axis, angle_unwrapped_past_pi]. 1 = written, 0 = joint unknown (yet)
#[no_mangle]
pub extern "C" fn koper_khysics_joint_state(
    world_id: i64, joint_id: i64, out_buf: *mut f32,
) -> i32 {
    if out_buf.is_null() { return 0; }
    let arc = match get_snapshot(world_id) { Some(a) => a, None => return 0 };
    let snap = match arc.read() { Ok(s) => s, Err(_) => return 0 };
    match snap.joints.get(&joint_id) {
        Some(v) => {
            unsafe { *out_buf = v[0]; *out_buf.add(1) = v[1]; *out_buf.add(2) = v[2]; }
            1
        }
        None => 0,
    }
}

// ── terrain ──────────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn koper_khysics_set_fluid_blocks(
    world_id: i64, data: *const i32, count: i32,
) {
    if data.is_null() || count <= 0 { return; }
    let fluids: Vec<[i32; 4]> = unsafe {
        (0..count as usize).map(|i| {
            let p = data.add(i * 4);
            [*p, *p.add(1), *p.add(2), *p.add(3)]
        }).collect()
    };
    push_cmd(world_id, PhysicsCmd::SetFluids(fluids));
}

// ── block add/remove ─────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn koper_khysics_set_gravity(world_id: i64, gx: f32, gy: f32, gz: f32) {
    push_cmd(world_id, PhysicsCmd::SetGravity { gx, gy, gz });
}

// aero surfaces from Java — one per aero-tagged block, resent whenever the kontra's blocks change.
// flat array, 10 floats per surface: [ox,oy,oz, nx,ny,nz, area, cd, cl, buoy]. count==0 clears (no aero).
// a zero normal means a chunky block (full cube): no face of its own
#[no_mangle]
pub extern "C" fn koper_khysics_set_aero(
    world_id: i64, kontra_id: i64, data: *const f32, count: i32,
) {
    let surfaces: Vec<world::AeroSurface> = if data.is_null() || count <= 0 {
        Vec::new()
    } else {
        unsafe {
            (0..count as usize).map(|i| {
                let p = data.add(i * 10);
                let normal = glam::Vec3::new(*p.add(3), *p.add(4), *p.add(5));
                // zero normal from java = a chunky block with no face of its own
                let chunky = normal.length_squared() < 1.0e-6;
                world::AeroSurface {
                    off:    glam::Vec3::new(*p, *p.add(1), *p.add(2)),
                    normal: if chunky { glam::Vec3::Y } else { normal },
                    chunky,
                    area:   *p.add(6),
                    cd:     *p.add(7),
                    cl:     *p.add(8),
                    buoy:   *p.add(9),
                }
            }).collect()
        }
    };
    push_cmd(world_id, PhysicsCmd::SetAero { id: kontra_id, surfaces });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_aero_mode(world_id: i64, kontra_id: i64, mode: i32) {
    push_cmd(world_id, PhysicsCmd::SetAeroMode { id: kontra_id, mode: world::AeroMode::from_i32(mode) });
}

// global air velocity for the whole world — relative wind = body velocity minus this
#[no_mangle]
pub extern "C" fn koper_khysics_set_wind(world_id: i64, wx: f32, wy: f32, wz: f32) {
    push_cmd(world_id, PhysicsCmd::SetWind { v: [wx, wy, wz] });
}

#[no_mangle]
pub extern "C" fn koper_khysics_self_right(world_id: i64, kontra_id: i64) {
    push_cmd(world_id, PhysicsCmd::SelfRight { id: kontra_id });
}

#[no_mangle]
pub extern "C" fn koper_khysics_add_block_at_offset(
    world_id: i64, kontra_id: i64, off_x: f32, off_y: f32, off_z: f32, mass: f32,
) -> i32 {
    push_cmd(world_id, PhysicsCmd::AddBlock {
        id: kontra_id, offset: [off_x, off_y, off_z], mass,
    });
    0
}

#[no_mangle]
pub extern "C" fn koper_khysics_remove_block_at_offset(
    world_id: i64, kontra_id: i64, lx: i32, ly: i32, lz: i32,
) -> i32 {
    push_cmd(world_id, PhysicsCmd::RemoveBlock { id: kontra_id, local: [lx, ly, lz] });
    0
}

// ── raycasting ───────────────────────────────────────────────────────────────

// reads last snapshot — may lag by 1 physics frame (~16ms), fine for server logic
// returns 1=hit 0=miss, writes hit pos to out_hit and kontraktion id to out_kontra_id
#[no_mangle]
pub extern "C" fn koper_khysics_raycast(
    world_id: i64,
    ox: f32, oy: f32, oz: f32,
    dx: f32, dy: f32, dz: f32,
    max_dist: f32,
    out_hit: *mut f32, out_kontra_id: *mut i64,
) -> i32 {
    if out_hit.is_null() || out_kontra_id.is_null() { return 0; }
    let arc = match get_snapshot(world_id) { Some(a) => a, None => return 0 };
    let snap = match arc.read() { Ok(s) => s, Err(_) => return 0 };

    // raycast against OBBs in snapshot — physics thread keeps them synced
    let o = glam::Vec3::new(ox, oy, oz);
    let d = glam::Vec3::new(dx, dy, dz).normalize_or_zero();
    if d == glam::Vec3::ZERO { return 0; }

    let mut best_t  = max_dist;
    let mut best_id = -1i64;
    for (id, k) in &snap.kontras {
        if let Some(t) = k.obb.ray_hit(o, d) {
            if t < best_t { best_t = t; best_id = *id; }
        }
    }
    if best_id < 0 { return 0; }
    let hit = o + d * best_t;
    unsafe {
        *out_hit          = hit.x;
        *out_hit.add(1)   = hit.y;
        *out_hit.add(2)   = hit.z;
        *out_kontra_id    = best_id;
    }
    1
}

// sleep only when kontraktion's chunks are unloaded — Java calls this each tick per kontraktion
#[no_mangle]
pub extern "C" fn koper_khysics_set_sleep_allowed(world_id: i64, kontra_id: i64, allowed: i32) {
    push_cmd(world_id, PhysicsCmd::SetSleepAllowed { id: kontra_id, allowed: allowed != 0 });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_damping(
    world_id: i64, kontra_id: i64, linear: f32, angular: f32,
) {
    push_cmd(world_id, PhysicsCmd::SetDamping { id: kontra_id, linear, angular });
}

#[no_mangle]
pub extern "C" fn koper_khysics_set_parked(world_id: i64, kontra_id: i64, parked: i32) {
    push_cmd(world_id, PhysicsCmd::SetParked { id: kontra_id, parked: parked != 0 });
}

// ── standalone OBB queries (bone hitboxes, no world needed) ──────────────────

#[no_mangle]
pub extern "C" fn koper_khysics_obb_overlap(
    a_cx: f32, a_cy: f32, a_cz: f32,
    a_hx: f32, a_hy: f32, a_hz: f32,
    a_qx: f32, a_qy: f32, a_qz: f32, a_qw: f32,
    b_cx: f32, b_cy: f32, b_cz: f32,
    b_hx: f32, b_hy: f32, b_hz: f32,
    b_qx: f32, b_qy: f32, b_qz: f32, b_qw: f32,
) -> i32 {
    use glam::{Quat, Vec3};
    let a = obb::Obb::new(Vec3::new(a_cx, a_cy, a_cz), Vec3::new(a_hx, a_hy, a_hz),
                          Quat::from_xyzw(a_qx, a_qy, a_qz, a_qw));
    let b = obb::Obb::new(Vec3::new(b_cx, b_cy, b_cz), Vec3::new(b_hx, b_hy, b_hz),
                          Quat::from_xyzw(b_qx, b_qy, b_qz, b_qw));
    if a.overlaps(&b) { 1 } else { 0 }
}

#[no_mangle]
pub extern "C" fn koper_khysics_obb_raycast(
    cx: f32, cy: f32, cz: f32,
    hx: f32, hy: f32, hz: f32,
    qx: f32, qy: f32, qz: f32, qw: f32,
    ox: f32, oy: f32, oz: f32,
    dx: f32, dy: f32, dz: f32,
    out_t: *mut f32,
) -> i32 {
    use glam::{Quat, Vec3};
    let o = obb::Obb::new(Vec3::new(cx, cy, cz), Vec3::new(hx, hy, hz),
                          Quat::from_xyzw(qx, qy, qz, qw));
    if let Some(t) = o.ray_hit(Vec3::new(ox, oy, oz), Vec3::new(dx, dy, dz)) {
        if !out_t.is_null() { unsafe { *out_t = t; } }
        1
    } else { 0 }
}

// ── connectivity split drain ──────────────────────────────────────────────────
// returns the number of split events pending for this world
// buf layout per split: [parent_id_lo(i32), parent_id_hi(i32), comp_count(i32),
//   then per component: [block_count(i32), lx0,ly0,lz0, lx1,ly1,lz1, ...]]
// returns -1 on error, 0 if no splits, else bytes written to buf
#[no_mangle]
pub extern "C" fn koper_khysics_drain_splits(
    world_id: i64, out_buf: *mut i32, buf_capacity: i32,
) -> i32 {
    if out_buf.is_null() || buf_capacity <= 0 { return -1; }
    let arc = match get_splits(world_id) { Some(a) => a, None => return -1 };
    let mut splits = match arc.lock() { Ok(s) => s, Err(_) => return -1 };
    if splits.is_empty() { return 0; }

    let mut written = 0i32;
    let mut keep = Vec::new();

    for (parent_id, components) in splits.drain(..) {
        // calculate how many i32s this event needs
        let blocks_total: usize = components.iter().map(|c| c.len()).sum();
        let needed = 3 + components.len() + blocks_total * 3; // header + per-comp counts + all positions
        if written as usize + needed > buf_capacity as usize {
            keep.push((parent_id, components));
            continue;
        }

        unsafe {
            let base = out_buf.add(written as usize);
            let id_bits = parent_id.to_le_bytes();
            // write parent id as two i32s
            *base = i32::from_le_bytes([id_bits[0],id_bits[1],id_bits[2],id_bits[3]]);
            *base.add(1) = i32::from_le_bytes([id_bits[4],id_bits[5],id_bits[6],id_bits[7]]);
            *base.add(2) = components.len() as i32;
            let mut off = 3usize;
            for comp in &components {
                *base.add(off) = comp.len() as i32;
                off += 1;
                for blk in comp {
                    *base.add(off)   = blk[0];
                    *base.add(off+1) = blk[1];
                    *base.add(off+2) = blk[2];
                    off += 3;
                }
            }
            written += needed as i32;
        }
    }

    *splits = keep;
    written
}

// ── glibc floor ──────────────────────────────────────────────────────────────
// The three f32 trig functions below got a new symbol version in glibc 2.43, so a build made on a
// bleeding-edge desktop imports acosf/asinf/atan2f@GLIBC_2.43 and refuses to load anywhere older:
//
//     libm.so.6: version `GLIBC_2.43' not found (required by libkoperlib_khysics_engine.so)
//
// That is what stops the mod on a hosted server, whose container is a few releases behind. Nothing
// else in the library needs anything past 2.34. Defining them here means the reference binds inside
// this object instead of against libm, and the requirement disappears — the f64 routines they call
// have been at GLIBC_2.2.5 since forever. Computing in f64 and narrowing is also *more* accurate
// than the f32 routines, and these sit in aero and joint maths, not in a per-contact hot loop.
//
// The JVM dlopens JNI libraries with RTLD_LOCAL, so these never enter the global symbol namespace
// and cannot interpose on anything else in the process.
#[no_mangle]
pub extern "C" fn acosf(x: f32) -> f32 { (x as f64).acos() as f32 }

#[no_mangle]
pub extern "C" fn asinf(x: f32) -> f32 { (x as f64).asin() as f32 }

#[no_mangle]
pub extern "C" fn atan2f(y: f32, x: f32) -> f32 { (y as f64).atan2(x as f64) as f32 }

#[no_mangle]
pub extern "C" fn koper_khysics_set_angular_velocity(world_id:i64,id:i64,x:f32,y:f32,z:f32) {
    push_cmd(world_id,PhysicsCmd::SetAngularVelocity{id,v:[x,y,z]});
}
#[no_mangle]
pub extern "C" fn koper_khysics_set_atmosphere(world_id:i64,drag:f32) {
    push_cmd(world_id,PhysicsCmd::SetAtmosphere{drag});
}
#[no_mangle]
pub extern "C" fn koper_khysics_set_flight_policy(world_id:i64,enabled:i32,max_speed:f32,min_section_y:i32,max_section_y:i32) {
    push_cmd(world_id,PhysicsCmd::SetFlightPolicy{enabled:enabled!=0,max_speed,min_section_y,max_section_y});
}

#[cfg(test)]
mod transfer_fence_tests {
    use super::*;
    #[test] fn command_fence_publishes_exact_velocity_before_transfer() {
        let w=koper_khysics_create_world(); let offset=[0.0f32;3]; let mass=[1.0f32];
        let id=koper_khysics_spawn_kontraktion_offsets(w,offset.as_ptr(),mass.as_ptr(),1,0,0.0,100.0,0.0);
        koper_khysics_set_gravity(w,0.0,0.0,0.0);
        koper_khysics_set_damping(w,id,0.0,0.0);
        koper_khysics_set_velocity(w,id,123.0,0.0,0.0);
        koper_khysics_set_angular_velocity(w,id,0.0,2.0,0.0);
        assert_eq!(koper_khysics_sync_commands(w,250),1);
        let mut out=[0.0f32;world::BODY_STATE_LEN];
        assert_eq!(koper_khysics_body_state(w,id,out.as_mut_ptr(),out.len() as i32),out.len() as i32);
        assert_eq!(out[7],123.0);assert_eq!(out[11],2.0);
        koper_khysics_destroy_world(w);
        assert_eq!(koper_khysics_sync_commands(w,20),0);
    }
}

#[no_mangle]
pub extern "C" fn koper_khysics_sync_commands(world_id:i64,timeout_ms:i32) -> i32 {
    let (reply,receiver)=std::sync::mpsc::channel();
    {
        let Ok(map)=worlds().lock() else {return 0};
        let Some(entry)=map.get(&world_id) else {return 0};
        entry.heartbeat.store(now_millis(),Ordering::Relaxed);
        let Ok(mut queue)=entry.cmd_queue.lock() else {return 0};
        queue.push(PhysicsCmd::Fence(reply));
    }
    if receiver.recv_timeout(Duration::from_millis(timeout_ms.clamp(1,1000) as u64)).is_ok() {return 1;}
    0
}
