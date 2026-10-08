// KhysWorld — one Rapier physics world per MC dimension
// lives ONLY on the physics thread — Java never locks this directly
use glam::{Quat, Vec3};
use rapier3d::prelude::*;
use std::collections::{HashMap, HashSet, VecDeque};
use crate::obb::Obb;
use crate::sections::{self, SectionStore};

// ── khys debug logger ────────────────────────────────────────────────────────
// Rust eprintln never shows in the runClient console, so we dump physics state to a file instead.
// lands in the game's working dir (run/koperlib_khys.log), truncated fresh on each launch, flushed
// per line so it survives a hard game close. read it after closing the game. compile out by flipping
// KHYS_DEBUG = false.
const KHYS_DEBUG: bool = false;

// Island bookkeeping check. Rapier keeps "which island is a body in" and "which islands are awake"
// in parallel arrays; when they drift apart the solver quietly simulates the wrong set of bodies and
// a joint stops being solved at all — the parts sag apart under gravity and the next solve that DOES
// see the joint fires them across the map. Flip this on and every broken invariant lands in
// run/koperlib_khys.log with the step number. Costs a full walk of every island per step, so it is a
// debugging switch, not something to ship.
const KHYS_VALIDATE_ISLANDS: bool = true;

// extra solver iterations for an island that contains a joint. this multiplies the WHOLE island's
// solver cost, so it is the first dial to turn when [KhysProfile] says "solver". 16 is what killed
// the visible bearing wobble; anything lower needs a wobble check on a real car
const JOINT_SOLVER_ITERS: usize = 16;
static KHYS_LOG: std::sync::OnceLock<std::sync::Mutex<std::fs::File>> = std::sync::OnceLock::new();

pub fn khys_log(args: std::fmt::Arguments) {
    if !KHYS_DEBUG && !KHYS_VALIDATE_ISLANDS { return; }
    let cell = KHYS_LOG.get_or_init(|| {
        let f = std::fs::File::create("koperlib_khys.log")
            .or_else(|_| std::fs::File::create(std::env::temp_dir().join("koperlib_khys.log")))
            .expect("khys log file");
        std::sync::Mutex::new(f)
    });
    if let Ok(mut f) = cell.lock() {
        use std::io::Write;
        let _ = writeln!(f, "{}", args);
        let _ = f.flush();
    }
}

// ── PhysicsCmd (inlined from cmd.rs) ─────────────────────────────────────────

pub enum PhysicsCmd {
    SpawnKontra        { id: i64, positions: Vec<[i32; 3]>, masses: Vec<f32>, light_count: usize, spawn_pos: [f32; 3] },
    SpawnKontraOffsets { id: i64, offsets: Vec<[f32; 3]>, masses: Vec<f32>, light_count: usize, spawn_pos: [f32; 3] },
    DestroyKontra(i64),
    SetBreakMode { id: i64, mode: BreakMode },
    ApplyForce   { id: i64, f: [f32; 3] },
    ApplyTorque  { id: i64, t: [f32; 3] },
    ApplyImpulse { id: i64, imp: [f32; 3] },
    ApplyImpulseGroup { id: i64, imp: [f32; 3] },
    ApplyImpulseAtPoint { id: i64, imp: [f32; 3], point: [f32; 3] },
    SetBlockMaterials { id: i64, materials: Vec<[f32; 15]> },
    SetBlockShapes { id: i64, shapes: Vec<[f32; 11]> },
    SetVelocity  { id: i64, v: [f32; 3] },
    SetDamping   { id: i64, linear: f32, angular: f32 },
    SetParked    { id: i64, parked: bool },
    SetTransform { id: i64, pos: [f32; 3], rot: [f32; 4] },
    AddBlock  { id: i64, offset: [f32; 3], mass: f32 },
    RemoveBlock { id: i64, local: [i32; 3] },
    SetFluids(Vec<[i32; 4]>),
    // full 16^3 occupancy bitset for one section — Java streams these when we report them missing.
    // upload BEFORE the spawn cmd in the same batch = terrain is there for the very first step.
    UploadSection { pos: [i32; 3], bits: Box<[u64; 64]> },
    // one world cell flipped (block placed/broken) — no-op if we don't cache that section
    SetTerrainBlock { pos: [i32; 3], solid: bool },
    SelfRight { id: i64 },
    SetGravity { gx: f32, gy: f32, gz: f32 },
    SetSleepAllowed { id: i64, allowed: bool },
    // the kontra's aero elements (one per tagged block) — replaces the whole set each time Java resends
    SetAero { id: i64, surfaces: Vec<AeroSurface> },
    SetAeroMode { id: i64, mode: AeroMode },
    SetWind { v: [f32; 3] },
    // joints — bearing = revolute + motor, piston = prismatic + motor. anchors in each body's
    // local (block-offset) space, axis shared by both local frames (kontras spawn unrotated)
    CreateRevoluteJoint { joint_id: i64, a: i64, b: i64, anchor_a: [f32; 3], anchor_b: [f32; 3], axis: [f32; 3] },
    CreatePrismaticJoint { joint_id: i64, a: i64, b: i64, anchor_a: [f32; 3], anchor_b: [f32; 3], axis: [f32; 3] },
    JointSetMotor { joint_id: i64, target_vel: f32, max_force: f32 },
    JointSetMotorPosition { joint_id: i64, target_pos: f32, stiffness: f32, damping: f32, max_force: f32, force_based: bool },
    JointSetLimits { joint_id: i64, min: f32, max: f32 },
    JointClearLimits { joint_id: i64 },
    DestroyJoint(i64),
    // water buoyancy volumes per block (jround key) — Java resends like aero. missing key = 1.0
    SetBuoyancy { id: i64, entries: Vec<([i32; 3], f32)> },
    SetWaterDensity(f32),
    // fluid occupancy bitset for one section — ONLY lands on already-uploaded sections, Java
    // sends it right after the solids upload of the same section
    UploadSectionFluids { pos: [i32; 3], bits: Box<[u64; 64]> },
    SetFluidBlock { pos: [i32; 3], fluid: bool },
    // ── steady pushes and per-body knobs, the vehicle toolkit ──
    // a force + torque (world frame, through the centre of mass) re-applied EVERY step until replaced.
    // apply_force only lasts one 60Hz step, which is useless from a 20Hz server tick
    SetHeldPush { id: i64, force: [f32; 3], torque: [f32; 3] },
    // spin kick, the angular twin of apply_impulse. used to need two opposite point impulses
    ApplyAngularImpulse { id: i64, imp: [f32; 3] },
    SetGravityScale { id: i64, scale: f32 },
    // multiplies what water pushes up on this body. 1 = normal, 0 = sinks like a stone
    SetBuoyancyScale { id: i64, scale: f32 },
    // hand this body to the Java pusher every step (see PUSHER)
    SetPushed { id: i64, on: bool },
    // one block's weight changed without the block itself changing (ballast, a filled tank)
    SetBlockMass { id: i64, offset: [f32; 3], mass: f32 },
}

// ── the pusher: a Java callback run ON the physics thread, once per step per pushed body ─────────
// ship mods are usually written around "physics calls me every physics tick with fresh state and I push". the
// 20Hz server tick can't do that: it reads a pose that's already a step old and its force dies after
// one of the three steps. the pusher is the same contract, native: fresh state in, force + torque out.
// signature: (kontra_id, state[BODY_STATE_LEN], out[6] = force xyz, torque xyz, world frame at COM)
pub type PushFn = extern "C" fn(i64, *const f32, *mut f32);
pub static PUSHER: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

// one body's state as a flat float block — same layout for the pusher and for the snapshot readback
//  [0..3]   origin (what getAllTransforms reports)     [3..7]   rotation xyzw
//  [7..10]  linear velocity of the centre of mass      [10..13] angular velocity, world, rad/s
//  [13..16] centre of mass, world                      [16..19] centre of mass, body frame (from origin)
//  [19]     mass                                       [20..29] inertia about the COM, world, row-major
//  [29..32] gravity this body feels (scale included)   [32]     dt of the step (0 in snapshots)
//  [33]     share of blocks in water 0..1              [34]     flags: 1 sleeping, 2 aligned, 4 parked
//  [35..38] khysics' own force on it this step so far (water + aero + held push), world
//  [38]     gravity scale                              [39]     buoyancy scale
pub const BODY_STATE_LEN: usize = 40;

pub struct KontraSnap {
    pub pos: [f32; 3],
    pub rot: [f32; 4],
    pub vel: [f32; 3],
    pub obb: Obb,
    // resting within a hair of the world grid (align-assist finished) — Java swaps SAT pushout
    // for real solid VoxelShapes while this is true
    pub aligned: bool,
    pub state: [f32; BODY_STATE_LEN],
}

pub struct WorldSnapshot {
    pub kontras: HashMap<i64, KontraSnap>,
    // sections the physics wants but Java hasn't uploaded yet — polled every server tick
    pub missing_sections: Vec<[i32; 3]>,
    // joint_id -> [angle_or_pos, velocity_along_axis] — wheels read spin speed off this
    pub joints: HashMap<i64, [f32; 3]>,
}

impl Default for WorldSnapshot {
    fn default() -> Self {
        Self {
            kontras: HashMap::new(),
            missing_sections: Vec::new(),
            joints: HashMap::new(),
        }
    }
}

// ── KontraKtion + Fragment (inlined from contraption.rs) ─────────────────────

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum BreakMode { Bounce, Destructible }

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum AeroMode { Low, Correct, Extreme }

impl AeroMode {
    pub fn from_i32(value: i32) -> Self {
        match value { 1 => Self::Low, 3 => Self::Extreme, _ => Self::Correct }
    }
}

pub struct Fragment {
    pub pos:    [f32; 3],
    pub vel:    [f32; 3],
    pub blocks: Vec<[i32; 3]>,
}

// contact filter: parts of one creation ignore each other, everything else collides as usual
struct AssemblyHooks {
    assembly: HashMap<RigidBodyHandle, u32>,
}

impl PhysicsHooks for AssemblyHooks {
    fn filter_contact_pair(&self, context: &PairFilterContext) -> Option<SolverFlags> {
        let (Some(a), Some(b)) = (context.rigid_body1, context.rigid_body2)
            else { return Some(SolverFlags::COMPUTE_IMPULSES) };
        match (self.assembly.get(&a), self.assembly.get(&b)) {
            (Some(x), Some(y)) if x == y => None,   // same creation — no contact at all
            _ => Some(SolverFlags::COMPUTE_IMPULSES),
        }
    }
}

pub struct KontraKtion {
    pub id:                i64,
    pub body:              RigidBodyHandle,
    pub colliders:         Vec<ColliderHandle>,   // one per block, parallel to block_offsets (same index)
    pub block_offsets:     Vec<[f32; 3]>,
    pub block_set:         HashSet<[i32; 3]>,
    pub break_mode:        BreakMode,
    pub pending_fragments: Vec<Fragment>,
    pub obb:               Obb,
    pub last_impact_force: f32,
    pub light_block_count: usize,
    // tagged wings/fins/balloons. hull drag comes from the whole kontra geometry in every aero mode.
    pub aero:              Vec<AeroSurface>,
    pub aero_state:        Vec<AeroPanelState>,
    pub aero_mode:         AeroMode,
    // happens to rest exactly on the world grid (detection only, body never gets moved)
    pub aligned:           bool,
    // water displaced per block (jround key) — from KhysWeightBook buoyancy_volume. missing = 1.0
    pub buoy_volumes:      HashMap<[i32; 3], f32>,
    // a wheel block sets its local axle here. rapier friction is one scalar in every direction, so a
    // tyre slides sideways exactly as happily as it rolls — steered wheels turn and the car ploughs
    // straight on. this is what lets it bite. (local axle, share of sideways speed killed per step,
    // already weighted by how much of this body is actually tyre)
    pub wheel_axle:        Option<(Vec3, f32)>,
    // SetHeldPush — world frame, through the COM, applied every step
    pub held_force:        Vec3,
    pub held_torque:       Vec3,
    // handed to PUSHER every step
    pub pushed:            bool,
    pub buoy_scale:        f32,
    // share of blocks sitting in water, written by apply_water
    pub submerged:         f32,
}

// blade pitch a flat plate gets while spinning in its own plane, rad. under the 0.30 stall
const ROTOR_PITCH: f32 = 0.2;
// blocks of forward travel per radian of prop spin: top speed a prop can pull to is PROP_PITCH * spin
const PROP_PITCH: f32 = 0.6;

// one aerodynamic element = one tagged block. untagged blocks make NONE of these → a plain build has
// an empty aero list and the whole aero pass skips it. the craft's lift/drag is just the sum over these.
#[derive(Clone, Copy)]
pub struct AeroSurface {
    pub off:    Vec3, // local position of the block center
    pub normal: Vec3, // local "up" of the surface — kept for future per-face use
    pub area:   f32,  // m² of lifting/drag surface (1 per block)
    pub cd:     f32,  // drag coefficient
    pub cl:     f32,  // WING lift coefficient (airfoil, lift only while moving; 0 = not a wing)
    pub buoy:   f32,  // BALLOON buoyancy (mass it lifts; rises to a density-altitude; 0 = not a balloon)
    pub chunky: bool, // a full block: no face of its own, normal is local up unless it's a prop blade
}

#[derive(Clone, Copy, Default)]
pub struct AeroPanelState {
    pub alpha: f32,
    pub separation: f32,
}

impl KontraKtion {
    pub fn sync_obb(&mut self, bodies: &RigidBodySet) {
        let body = match bodies.get(self.body) { Some(b) => b, None => return };
        let t = body.translation();
        let r = body.rotation();
        let center = Vec3::new(t.x, t.y, t.z);
        let mut max = Vec3::ZERO;
        for off in &self.block_offsets {
            max.x = max.x.max(off[0].abs() + 0.5);
            max.y = max.y.max(off[1].abs() + 0.5);
            max.z = max.z.max(off[2].abs() + 0.5);
        }
        self.obb = Obb::new(center, max, *r);
    }

    pub fn fragment(&self, bodies: &RigidBodySet, force: f32) -> Vec<Fragment> {
        if self.block_offsets.is_empty() { return Vec::new(); }
        let body = match bodies.get(self.body) { Some(b) => b, None => return Vec::new() };
        let pos  = body.translation();
        let vel  = body.linvel();
        let chunk_count = ((force / 5000.0).min(8.0) as usize).max(1);
        let blocks_per_chunk = (self.block_offsets.len() / chunk_count).max(1);
        self.block_offsets.chunks(blocks_per_chunk).enumerate().map(|(i, chunk)| {
            let cx: f32 = chunk.iter().map(|b| b[0]).sum::<f32>() / chunk.len() as f32;
            let cy: f32 = chunk.iter().map(|b| b[1]).sum::<f32>() / chunk.len() as f32;
            let cz: f32 = chunk.iter().map(|b| b[2]).sum::<f32>() / chunk.len() as f32;
            let scatter = (i as f32 / chunk_count as f32) * std::f32::consts::TAU;
            Fragment {
                pos: [pos.x + cx, pos.y + cy, pos.z + cz],
                vel: [vel.x + scatter.cos() * 2.0, vel.y + 1.5, vel.z + scatter.sin() * 2.0],
                blocks: chunk.iter().map(|b| {
                    [(b[0] - cx).round() as i32, (b[1] - cy).round() as i32, (b[2] - cz).round() as i32]
                }).collect(),
            }
        }).collect()
    }
}


// diagnostics: which commands the game sends, counted and printed every 600 steps when the
// environment has KOPER_CMD_LOG. a replay that behaves unlike the game is missing one of these
fn koper_cmd_name(cmd: &PhysicsCmd) -> &'static str {
    #[allow(unreachable_patterns)]
    match cmd {
            PhysicsCmd::SpawnKontra { .. } => "SpawnKontra",
            PhysicsCmd::SpawnKontraOffsets { .. } => "SpawnKontraOffsets",
            PhysicsCmd::DestroyKontra { .. } => "DestroyKontra",
            PhysicsCmd::SetBreakMode { .. } => "SetBreakMode",
            PhysicsCmd::ApplyForce { .. } => "ApplyForce",
            PhysicsCmd::ApplyTorque { .. } => "ApplyTorque",
            PhysicsCmd::ApplyImpulse { .. } => "ApplyImpulse",
            PhysicsCmd::ApplyImpulseGroup { .. } => "ApplyImpulseGroup",
            PhysicsCmd::ApplyImpulseAtPoint { .. } => "ApplyImpulseAtPoint",
            PhysicsCmd::SetBlockMaterials { .. } => "SetBlockMaterials",
            PhysicsCmd::SetBlockShapes { .. } => "SetBlockShapes",
            PhysicsCmd::SetVelocity { .. } => "SetVelocity",
            PhysicsCmd::SetDamping { .. } => "SetDamping",
            PhysicsCmd::SetParked { .. } => "SetParked",
            PhysicsCmd::SetTransform { .. } => "SetTransform",
            PhysicsCmd::AddBlock { .. } => "AddBlock",
            PhysicsCmd::RemoveBlock { .. } => "RemoveBlock",
            PhysicsCmd::SetFluids { .. } => "SetFluids",
            PhysicsCmd::UploadSection { .. } => "UploadSection",
            PhysicsCmd::SetTerrainBlock { .. } => "SetTerrainBlock",
            PhysicsCmd::SelfRight { .. } => "SelfRight",
            PhysicsCmd::SetGravity { .. } => "SetGravity",
            PhysicsCmd::SetSleepAllowed { .. } => "SetSleepAllowed",
            PhysicsCmd::SetAero { .. } => "SetAero",
            PhysicsCmd::SetAeroMode { .. } => "SetAeroMode",
            PhysicsCmd::SetWind { .. } => "SetWind",
            PhysicsCmd::CreateRevoluteJoint { .. } => "CreateRevoluteJoint",
            PhysicsCmd::CreatePrismaticJoint { .. } => "CreatePrismaticJoint",
            PhysicsCmd::JointSetMotor { .. } => "JointSetMotor",
            PhysicsCmd::JointSetMotorPosition { .. } => "JointSetMotorPosition",
            PhysicsCmd::JointSetLimits { .. } => "JointSetLimits",
            PhysicsCmd::JointClearLimits { .. } => "JointClearLimits",
            PhysicsCmd::DestroyJoint { .. } => "DestroyJoint",
            PhysicsCmd::SetBuoyancy { .. } => "SetBuoyancy",
            PhysicsCmd::SetWaterDensity { .. } => "SetWaterDensity",
            PhysicsCmd::UploadSectionFluids { .. } => "UploadSectionFluids",
            PhysicsCmd::SetFluidBlock { .. } => "SetFluidBlock",
            PhysicsCmd::SetHeldPush { .. } => "SetHeldPush",
            PhysicsCmd::ApplyAngularImpulse { .. } => "ApplyAngularImpulse",
            PhysicsCmd::SetGravityScale { .. } => "SetGravityScale",
            PhysicsCmd::SetBuoyancyScale { .. } => "SetBuoyancyScale",
            PhysicsCmd::SetPushed { .. } => "SetPushed",
            PhysicsCmd::SetBlockMass { .. } => "SetBlockMass",
        _ => "?",
    }
}

static KOPER_CMD_LOG: std::sync::OnceLock<bool> = std::sync::OnceLock::new();

pub static NEXT_KONTRA_ID: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

const GROUP_TERRAIN: Group = Group::GROUP_1;
const GROUP_KONTRA:  Group = Group::GROUP_2;
// disconnected pieces only break off into their own kontra once they're this many blocks apart —
// mining one block leaves a ~1-block gap so the structure stays welded as a single body
const SPLIT_GAP: i32 = 5;
// a tyre's rolling resistance coefficient: the share of its load it loses to rolling. a real tyre is
// around 0.015; blocky ones get twice that, 0.84 m/s2 under this world's gravity of 28
const TYRE_ROLLING_RESISTANCE: f32 = 0.03;

// one live joint. axis kept for the velocity readout, kind picks which motor axis to drive
#[derive(Clone, Copy)]
struct JointRec {
    handle: ImpulseJointHandle,
    a: i64,
    b: i64,
    anchor_a: Vec3,
    anchor_b: Vec3,
    axis: Vec3,
    prismatic: bool,
    anchor_body: Option<RigidBodyHandle>,
    // revolute angle unwrapped past +-pi, tracked every step so java never has to guess the way round
    last_angle: Option<f32>,
    turned: f32,
    // which way the motor last drove the spinning end about the axis, +1/-1. a prop's blades are
    // twisted for that way round, like a real one; they don't re-twist when the air turns it back
    hand: f32,
}

pub struct KhysWorld {
    pipeline:         PhysicsPipeline,
    gravity:          Vec3,
    wind:             Vec3,   // global air velocity — relative wind = body vel - this. zero by default
    params:           IntegrationParameters,
    islands:          IslandManager,
    broad_phase:      BroadPhaseBvh,
    narrow_phase:     NarrowPhase,
    // last finite transform per body — the rollback target when a solver blow-up NaNs one
    last_good:        HashMap<RigidBodyHandle, ([f32; 3], [f32; 4])>,
    cmd_counts:       HashMap<&'static str, u64>,
    bodies:           RigidBodySet,
    colliders:        ColliderSet,
    impulse_joints:   ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    ccd:              CCDSolver,
    kontras:          HashMap<i64, KontraKtion>,
    fluids:           HashMap<[i32; 3], f32>,
    sections:         SectionStore,
    step_count:       u64,
    // pending splits from block removal — drained by Java each tick
    pending_splits: Vec<(i64, Vec<Vec<[i32; 3]>>)>,
    joints:           HashMap<i64, JointRec>,
    // joint topology cache — rebuilding the parent/child graph every step was allocating five
    // collections 60×/s and going quadratic on joint count. bump `joint_topology_dirty` whenever
    // the joint set changes and it gets rebuilt once instead
    joint_order:      Vec<JointRec>,
    joint_children:   HashMap<i64, Vec<i64>>,
    joint_subtrees:   HashMap<i64, Vec<i64>>,
    joint_topology_dirty: bool,
    joint_scratch:    Vec<i64>,
    // rolling per-phase step cost in ms: [total, sections, fluid+aero, pipeline, joint projection,
    // post passes]. costs two Instant reads per phase, and it is the only way anyone finds out
    // WHERE a phone is losing its frame instead of guessing
    profile_acc:      [f64; 6],
    profile_samples:  u32,
    profile_out:      [f32; 6],
    water_density:    f32, // displaced mass per full block of water volume — buoyancy scale
    // last KHYS_VALIDATE_ISLANDS result, so only the transition into a broken state gets logged
    islands_were_broken: bool,
    // pairs already reported by the self-contact check, so it says it once instead of 60x a second
    self_contact_seen: HashSet<(RigidBodyHandle, RigidBodyHandle)>,
    // joint records already reported as having no rapier constraint behind them
    zombie_joints_seen: HashSet<(i64, i64)>,
}

impl KhysWorld {
    pub fn new() -> Self {
        let mut params = IntegrationParameters::default();
        // soften contact push-out: a terrain rebuild resets the resting contact, and the default 10 m/s
        // correction would punt a resting kontra up → it bobs / on a block-break it launched. 4 still
        // recovers real penetration fast, just doesn't fling.
        params.normalized_max_corrective_velocity = 4.0;

        let bodies = RigidBodySet::new();
        Self {
            pipeline:           PhysicsPipeline::new(),
            gravity:            Vec3::new(0.0, -28.0, 0.0),
            wind:               Vec3::ZERO,
            params,
            islands:            { let mut i = IslandManager::new(); i.set_min_island_size(1); i },
            broad_phase:        BroadPhaseBvh::default(),
            narrow_phase:       NarrowPhase::new(),
            last_good:          HashMap::new(),
            cmd_counts:         HashMap::new(),
            joint_order:        Vec::new(),
            joint_children:     HashMap::new(),
            joint_subtrees:     HashMap::new(),
            joint_topology_dirty: true,
            joint_scratch:      Vec::new(),
            profile_acc:        [0.0; 6],
            profile_samples:    0,
            profile_out:        [0.0; 6],
            bodies,
            colliders:          ColliderSet::new(),
            impulse_joints:     ImpulseJointSet::new(),
            multibody_joints:   MultibodyJointSet::new(),
            ccd:                CCDSolver::new(),
            kontras:            HashMap::new(),
            fluids:             HashMap::new(),
            sections:           SectionStore::new(),
            step_count:         0,
            pending_splits:     Vec::new(),
            joints:             HashMap::new(),
            water_density:      1.0,
            islands_were_broken: false,
            self_contact_seen:   HashSet::new(),
            zombie_joints_seen:  HashSet::new(),
        }
    }

    pub fn process_cmd(&mut self, cmd: PhysicsCmd) {
        if *KOPER_CMD_LOG.get_or_init(|| std::env::var("KOPER_CMD_LOG").is_ok()) {
            *self.cmd_counts.entry(koper_cmd_name(&cmd)).or_insert(0) += 1;
        }
        match cmd {
            PhysicsCmd::SpawnKontra { id, positions, masses, light_count, spawn_pos } => {
                self.spawn_kontraktion_with_id(id, &positions, &masses, light_count, spawn_pos);
            }
            PhysicsCmd::SpawnKontraOffsets { id, offsets, masses, light_count, spawn_pos } => {
                self.spawn_kontraktion_from_offsets(id, offsets, &masses, light_count, spawn_pos);
            }
            PhysicsCmd::DestroyKontra(id)             => self.destroy_contraption(id),
            PhysicsCmd::SetBreakMode { id, mode }     => self.set_break_mode(id, mode),
            PhysicsCmd::ApplyForce { id, f }          => self.apply_force(id, f),
            PhysicsCmd::ApplyTorque { id, t }         => self.apply_torque(id, t),
            PhysicsCmd::ApplyImpulse { id, imp }      => self.apply_impulse(id, imp),
            PhysicsCmd::ApplyImpulseGroup { id, imp } => self.apply_impulse_group(id, imp),
            PhysicsCmd::ApplyImpulseAtPoint { id, imp, point } => self.apply_impulse_at_point(id, imp, point),
            PhysicsCmd::SetBlockMaterials { id, materials } => self.set_block_materials(id, &materials),
            PhysicsCmd::SetBlockShapes { id, shapes } => self.set_block_shapes(id, &shapes),
            PhysicsCmd::SetVelocity { id, v }         => self.set_velocity(id, v),
            PhysicsCmd::SetDamping { id, linear, angular } => self.set_damping(id, linear, angular),
            PhysicsCmd::SetParked { id, parked } => self.set_parked(id, parked),
            PhysicsCmd::SetTransform { id, pos, rot } => self.set_transform(id, pos, rot),
            PhysicsCmd::AddBlock { id, offset, mass } => { self.add_block_at_local_offset(id, offset, mass); }
            PhysicsCmd::RemoveBlock { id, local }   => { self.remove_block_at_local_offset(id, local); }
            PhysicsCmd::SetFluids(data)             => self.set_fluids_raw(&data),
            PhysicsCmd::UploadSection { pos, bits } => {
                self.sections.upload(pos, bits, self.step_count);
            }
            PhysicsCmd::SetTerrainBlock { pos, solid } => {
                self.sections.set_block(pos[0], pos[1], pos[2], solid);
            }
            PhysicsCmd::SetGravity { gx, gy, gz } => self.set_gravity(gx, gy, gz),
            PhysicsCmd::SetSleepAllowed { id, allowed } => self.set_sleep_allowed(id, allowed),
            PhysicsCmd::SetAero { id, surfaces } => {
                if let Some(k) = self.kontras.get_mut(&id) {
                    k.aero = surfaces;
                    k.aero_state.clear();
                }
            }
            PhysicsCmd::SetAeroMode { id, mode } => {
                if let Some(k) = self.kontras.get_mut(&id) {
                    k.aero_mode = mode;
                    if mode != AeroMode::Extreme { k.aero_state.clear(); }
                    if let Some(b) = self.bodies.get_mut(k.body) {
                        let (linear, angular) = match mode {
                            AeroMode::Low => (0.70, 1.0),
                            AeroMode::Correct => (0.06, 0.12),
                            AeroMode::Extreme => (0.02, 0.05),
                        };
                        b.set_linear_damping(linear);
                        b.set_angular_damping(angular);
                        b.wake_up(true);
                    }
                }
            }
            PhysicsCmd::SetWind { v } => self.wind = Vec3::from(v),
            PhysicsCmd::CreateRevoluteJoint { joint_id, a, b, anchor_a, anchor_b, axis } => {
                self.create_joint(joint_id, a, b, anchor_a, anchor_b, axis, false);
            }
            PhysicsCmd::CreatePrismaticJoint { joint_id, a, b, anchor_a, anchor_b, axis } => {
                self.create_joint(joint_id, a, b, anchor_a, anchor_b, axis, true);
            }
            PhysicsCmd::JointSetMotor { joint_id, target_vel, max_force } => {
                if !target_vel.is_finite() || !max_force.is_finite() || max_force < 0.0 {
                    return;
                }
                if let Some(rec) = self.joints.get_mut(&joint_id) {
                    // the motor drives body2 relative to body1. on a world joint body1 is the part
                    // itself, so the part turns the other way round than the target says
                    if target_vel.abs() > 1.0 && max_force > 0.0 {
                        rec.hand = target_vel.signum() * if rec.b == 0 { -1.0 } else { 1.0 };
                    }
                    if let Some(j) = self.impulse_joints.get_mut(rec.handle, true) {
                        let axis = if rec.prismatic { JointAxis::LinX } else { JointAxis::AngX };
                        // damping IS the motor's torque per unit of speed error — hardcoding 1.0 meant a
                        // wheel 16 rad/s off target asked for 16 Nm while max_force said it could have
                        // 26000. the cap was decoration and every motor crawled. tie it to max_force so
                        // the motor saturates its allowance past 1 rad/s of error and eases off near the
                        // target instead of chattering across it.
                        j.data.set_motor_velocity(axis, target_vel, max_force.max(1.0));
                        j.data.set_motor_max_force(axis, max_force);
                    }
                }
                self.wake_joint(joint_id);
            }
            PhysicsCmd::JointSetMotorPosition {
                joint_id, target_pos, stiffness, damping, max_force, force_based
            } => {
                if !target_pos.is_finite() || !stiffness.is_finite()
                    || !damping.is_finite() || !max_force.is_finite()
                    || stiffness < 0.0 || damping < 0.0 || max_force < 0.0 {
                    return;
                }
                if let Some(rec) = self.joints.get(&joint_id) {
                    if let Some(j) = self.impulse_joints.get_mut(rec.handle, true) {
                        let axis = if rec.prismatic { JointAxis::LinX } else { JointAxis::AngX };
                        j.data.set_motor_model(axis, if force_based {
                            MotorModel::ForceBased
                        } else {
                            MotorModel::AccelerationBased
                        });
                        j.data.set_motor_position(axis, target_pos, stiffness, damping);
                        j.data.set_motor_max_force(axis, max_force);
                    }
                }
                self.wake_joint(joint_id);
            }
            PhysicsCmd::JointSetLimits { joint_id, min, max } => {
                if !min.is_finite() || !max.is_finite() || min > max {
                    return;
                }
                if let Some(rec) = self.joints.get(&joint_id) {
                    if let Some(j) = self.impulse_joints.get_mut(rec.handle, true) {
                        let axis = if rec.prismatic { JointAxis::LinX } else { JointAxis::AngX };
                        j.data.set_limits(axis, [min, max]);
                    }
                }
            }
            PhysicsCmd::JointClearLimits { joint_id } => {
                if let Some(rec) = self.joints.get(&joint_id) {
                    if let Some(j) = self.impulse_joints.get_mut(rec.handle, true) {
                        let axis = if rec.prismatic { JointAxis::LinX } else { JointAxis::AngX };
                        j.data.limit_axes.remove(axis.into());
                        j.data.limits[axis as usize].impulse = 0.0;
                    }
                }
            }
            PhysicsCmd::DestroyJoint(joint_id) => {
                self.destroy_joint(joint_id);
            }
            PhysicsCmd::SetBuoyancy { id, entries } => {
                if let Some(k) = self.kontras.get_mut(&id) {
                    k.buoy_volumes = entries.into_iter().collect();
                }
            }
            PhysicsCmd::SetWaterDensity(d) => self.water_density = d.max(0.0),
            PhysicsCmd::UploadSectionFluids { pos, bits } => self.sections.upload_fluids(pos, bits),
            PhysicsCmd::SetFluidBlock { pos, fluid } => {
                self.sections.set_fluid(pos[0], pos[1], pos[2], fluid);
            }
            PhysicsCmd::SetHeldPush { id, force, torque } => {
                let (f, t) = (Vec3::from(force), Vec3::from(torque));
                if !f.is_finite() || !t.is_finite() { return; }
                if let Some(k) = self.kontras.get_mut(&id) {
                    k.held_force = f;
                    k.held_torque = t;
                    // a sleeping body never reads its forces, so a new push has to wake it
                    if f != Vec3::ZERO || t != Vec3::ZERO {
                        if let Some(b) = self.bodies.get_mut(k.body) { b.wake_up(true); }
                    }
                }
            }
            PhysicsCmd::ApplyAngularImpulse { id, imp } => {
                let imp = Vec3::from(imp);
                if !imp.is_finite() { return; }
                if let Some(k) = self.kontras.get(&id) {
                    if let Some(b) = self.bodies.get_mut(k.body) { b.apply_torque_impulse(imp, true); }
                }
            }
            PhysicsCmd::SetGravityScale { id, scale } => {
                if !scale.is_finite() { return; }
                if let Some(k) = self.kontras.get(&id) {
                    if let Some(b) = self.bodies.get_mut(k.body) { b.set_gravity_scale(scale, true); }
                }
            }
            PhysicsCmd::SetBuoyancyScale { id, scale } => {
                if !scale.is_finite() { return; }
                if let Some(k) = self.kontras.get_mut(&id) { k.buoy_scale = scale.max(0.0); }
            }
            PhysicsCmd::SetPushed { id, on } => {
                if let Some(k) = self.kontras.get_mut(&id) { k.pushed = on; }
            }
            PhysicsCmd::SetBlockMass { id, offset, mass } => {
                self.set_block_mass(id, offset, mass);
            }
            PhysicsCmd::SelfRight { id } => {
                if let Some(k) = self.kontras.get(&id) {
                    if let Some(b) = self.bodies.get_mut(k.body) {
                        let mass = b.mass();
                        let r = b.rotation();
                        // rotate world Y by body quaternion to get body's current up vector
                        let q = Quat::from_xyzw(r.x, r.y, r.z, r.w);
                        let body_up = q * Vec3::new(0.0, 1.0, 0.0);
                        // cross product gives torque axis that rotates body_up toward world up
                        let world_up = Vec3::new(0.0, 1.0, 0.0);
                        let correction = body_up.cross(world_up);
                        b.wake_up(true);
                        b.apply_torque_impulse(correction * mass * 8.0, true);
                        b.apply_impulse(Vec3::new(0.0, mass * 3.0, 0.0), true);
                    }
                }
            }
        }
    }

    pub fn step(&mut self, dt: f32) -> Vec<(i64, Vec<Fragment>)> {
        self.step_count += 1;
        if self.step_count % 600 == 0 && !self.cmd_counts.is_empty() {
            let mut counts: Vec<_> = self.cmd_counts.drain().collect();
            counts.sort_by(|a, b| b.1.cmp(&a.1));
            eprintln!("[KhysCmds] last 600 steps: {:?}", counts);
        }
        let t_start = std::time::Instant::now();

        // re-mesh only the sections that changed — the rest keep their live contacts
        self.sections.rebuild_dirty(
            &mut self.colliders, &mut self.islands, &mut self.bodies,
            InteractionGroups::new(GROUP_TERRAIN, GROUP_KONTRA, InteractionTestMode::And));
        // want-list housekeeping at 10Hz is plenty
        if self.step_count % 6 == 0 {
            let wanted = self.wanted_sections();
            self.sections.update_wanted(&wanted, self.step_count,
                &mut self.colliders, &mut self.islands, &mut self.bodies);
        }

        let t_sections = t_start.elapsed();
        self.params.dt = dt;
        self.apply_water(dt);
        self.apply_aero(dt);
        self.apply_pushes(dt);
        let t_fluid = t_start.elapsed();
        let hooks = AssemblyHooks { assembly: self.assemblies() };
        self.pipeline.step(
            self.gravity, &self.params,
            &mut self.islands, &mut self.broad_phase, &mut self.narrow_phase,
            &mut self.bodies, &mut self.colliders,
            &mut self.impulse_joints, &mut self.multibody_joints,
            &mut self.ccd, &hooks, &(),
        );
        let t_pipeline = t_start.elapsed();
        self.koper_unwrap_joints();
        if KHYS_VALIDATE_ISLANDS {
            // Two parts of ONE creation must never touch: a bearing and its flush-mounted head share
            // a cell, so a single contact between them is a full block of penetration and the solver
            // shoves them apart with everything it has. The joint pulls back, they settle a metre
            // apart still "connected", and the projection pass then teleports the pair to hide it.
            // filter_contact_pair is supposed to make this impossible — prove whether it holds.
            for pair in self.narrow_phase.contact_pairs() {
                if !pair.has_any_active_contact() { continue; }
                let (Some(p1), Some(p2)) = (
                    self.colliders.get(pair.collider1).and_then(|c| c.parent()),
                    self.colliders.get(pair.collider2).and_then(|c| c.parent())) else { continue };
                let (Some(x), Some(y)) = (hooks.assembly.get(&p1), hooks.assembly.get(&p2))
                    else { continue };
                if x != y { continue; }
                let name = |h| self.kontras.iter().find(|(_, k)| k.body == h).map(|(id, _)| *id);
                if self.self_contact_seen.insert((p1, p2)) {
                    khys_log(format_args!(
                        "SELF-CONTACT LEAK: kontras {:?} and {:?} are one creation but are pushing \
                         each other apart — the contact filter did not hold",
                        name(p1), name(p2)));
                }
            }
            let problems = self.islands.island_state_problems(&self.bodies);
            // only the transition into a broken state is interesting — it repeats every step after
            if !problems.is_empty() && !self.islands_were_broken {
                khys_log(format_args!(
                    "step {}: island bookkeeping went inconsistent ({} problems)",
                    self.step_count, problems.len()));
                for problem in &problems { khys_log(format_args!("    {problem}")); }
            }
            self.islands_were_broken = !problems.is_empty();
        }
        self.project_joint_hierarchy();
        let t_project = t_start.elapsed();
        // This Rapier fork says user forces clear after step, but never actually clears them. Without
        // this every aero/helm force becomes permanent and stacks forever: hover, shake, free energy.
        // kontras only — they are the only bodies anything pushes, and `bodies.iter_mut()` flags the
        // WHOLE set as user-modified every step, which the next step then walks for nothing
        let kontra_bodies: Vec<RigidBodyHandle> = self.kontras.values().map(|k| k.body).collect();
        for handle in &kontra_bodies {
            if let Some(body) = self.bodies.get_mut(*handle) {
                body.reset_forces(false);
                body.reset_torques(false);
            }
        }
        self.apply_wheel_grip();
        self.rescue_nan_bodies();
        self.clamp_runaway_bodies();
        self.anti_clip();
        self.detect_grid_rest();
        let t_end = t_start.elapsed();
        let ms = |d: std::time::Duration| d.as_secs_f64() * 1000.0;
        self.profile_acc[0] += ms(t_end);
        self.profile_acc[1] += ms(t_sections);
        self.profile_acc[2] += ms(t_fluid) - ms(t_sections);
        self.profile_acc[3] += ms(t_pipeline) - ms(t_fluid);
        self.profile_acc[4] += ms(t_project) - ms(t_pipeline);
        self.profile_acc[5] += ms(t_end) - ms(t_project);
        self.profile_samples += 1;
        if self.profile_samples >= 60 {
            for i in 0..6 {
                self.profile_out[i] = (self.profile_acc[i] / self.profile_samples as f64) as f32;
                self.profile_acc[i] = 0.0;
            }
            self.profile_samples = 0;
        }

        // ── debug dump to run/koperlib_khys.log (~3×/s per kontra) — see KHYS_DEBUG ──
        if KHYS_DEBUG {
            use std::sync::atomic::{AtomicU64, Ordering};
            static TICK: AtomicU64 = AtomicU64::new(0);
            let n = TICK.fetch_add(1, Ordering::Relaxed);
            if n % 20 == 0 {
                for (id, k) in &self.kontras {
                    if let Some(b) = self.bodies.get(k.body) {
                        let t = b.translation(); let v = b.linvel(); let a = b.angvel();
                        khys_log(format_args!(
                            "t={} k{} y={:.3} vy={:+.3} |v|={:.2} v=({:+.2},{:+.2},{:+.2}) |w|={:.2} sleep={} aero={} mass={:.2}",
                            n, id, t.y, v.y,
                            (v.x*v.x+v.y*v.y+v.z*v.z).sqrt(), v.x, v.y, v.z,
                            (a.x*a.x+a.y*a.y+a.z*a.z).sqrt(),
                            b.is_sleeping(), k.aero.len(), b.mass()));
                    }
                }
            }
        }

        let ids: Vec<i64> = self.kontras.keys().cloned().collect();

        for id in &ids {
            let k = match self.kontras.get_mut(id) { Some(k) => k, None => continue };
            k.sync_obb(&self.bodies);

            if k.break_mode == BreakMode::Destructible && k.last_impact_force > 2000.0 {
                let force = k.last_impact_force;
                let frags = k.fragment(&self.bodies, force);
                if !frags.is_empty() {
                    let mut pending = std::mem::take(&mut k.pending_fragments);
                    pending.extend(frags);
                    k.pending_fragments = pending;
                }
                k.last_impact_force = 0.0;
            }
        }

        let mut new_frags: Vec<(i64, Vec<Fragment>)> = Vec::new();
        for id in &ids {
            let k = match self.kontras.get_mut(id) { Some(k) => k, None => continue };
            if !k.pending_fragments.is_empty() {
                new_frags.push((*id, std::mem::take(&mut k.pending_fragments)));
            }
        }

        for (id, _) in &new_frags { self.destroy_contraption(*id); }
        new_frags
    }

    // hard safety net, runs every step: no body keeps a NaN or an insane velocity. without this a bad
    // contact or force spike sends a kontra to infinity → its position becomes NaN → Java's getEntities
    // packs that into a section long and overflows → server crash. clamp here = it simply can't happen.
    // A body whose position goes NaN is unrecoverable and Java culls it — that is somebody's build
    // deleted for good because a solver hiccuped. Keep the last sane transform and roll back to it
    // instead: the creation survives an explosion, it just gets put back where it was.
    fn rescue_nan_bodies(&mut self) {
        let mut lost: Vec<RigidBodyHandle> = Vec::new();
        for (handle, b) in self.bodies.iter() {
            if !b.is_dynamic() { continue; }
            let t = b.translation();
            let r = b.rotation();
            if t.x.is_finite() && t.y.is_finite() && t.z.is_finite()
                && r.x.is_finite() && r.y.is_finite() && r.z.is_finite() && r.w.is_finite() {
                self.last_good.insert(handle, ([t.x, t.y, t.z], [r.x, r.y, r.z, r.w]));
            } else {
                lost.push(handle);
            }
        }
        for handle in lost {
            let Some((pos, rot)) = self.last_good.get(&handle).copied() else { continue };
            if let Some(b) = self.bodies.get_mut(handle) {
                b.set_translation(Vec3::from(pos), false);
                b.set_rotation(Quat::from_xyzw(rot[0], rot[1], rot[2], rot[3]), false);
                b.set_linvel(Vec3::ZERO, false);
                b.set_angvel(Vec3::ZERO, false);
            }
        }
    }

    fn clamp_runaway_bodies(&mut self) {
        // NOT a gameplay speed limit — koper decreed kontras may zapierdalac as fast as they
        // want. this only catches genuine physics explosions before they NaN the world.
        const V_MAX: f32 = 10_000.0; // blocks/s — nothing legit flies this fast
        const W_MAX: f32 = 120.0;    // rad/s — 40 strangled every propeller, a blowup spins way past this anyway
        let handles: Vec<RigidBodyHandle> = self.kontras.values().map(|k| k.body).collect();
        for handle in handles {
            let Some(b) = self.bodies.get_mut(handle) else { continue };
            if !b.is_dynamic() { continue; }
            let lv = b.linvel();
            let v = Vec3::new(lv.x, lv.y, lv.z);
            if !v.is_finite() { b.set_linvel(Vec3::ZERO, false); }
            else if v.length() > V_MAX { b.set_linvel(v.normalize() * V_MAX, false); }
            let av = b.angvel();
            let w = Vec3::new(av.x, av.y, av.z);
            if !w.is_finite() { b.set_angvel(Vec3::ZERO, false); }
            else if w.length() > W_MAX { b.set_angvel(w.normalize() * W_MAX, false); }
        }
    }

    // last full second of per-phase averages, ms
    pub fn profile(&self) -> [f32; 6] { self.profile_out }

    pub fn step_count(&self) -> u64 { self.step_count }

    // one step turns a joint way less than half a turn, so the shortest way round is the real one here
    fn koper_unwrap_joints(&mut self) {
        for rec in self.joints.values_mut() {
            if rec.prismatic { continue; }
            let Some(j) = self.impulse_joints.get(rec.handle) else { continue };
            let (Some(b1), Some(b2)) = (self.bodies.get(j.body1), self.bodies.get(j.body2)) else { continue };
            let Some(angle) = j.data.as_revolute().map(|r| r.angle(b1.rotation(), b2.rotation())) else { continue };
            if !angle.is_finite() { continue; }
            if let Some(last) = rec.last_angle {
                let mut d = angle - last;
                if d > std::f32::consts::PI { d -= std::f32::consts::TAU; }
                else if d < -std::f32::consts::PI { d += std::f32::consts::TAU; }
                rec.turned += d;
            } else {
                rec.turned = angle;
            }
            rec.last_angle = Some(angle);
        }
    }

    pub fn take_pending_splits(&mut self) -> Vec<(i64, Vec<Vec<[i32; 3]>>)> {
        std::mem::take(&mut self.pending_splits)
    }

    // every section any kontra could touch soon: OBB world box + margin + velocity lookahead,
    // and a fat pad straight down so a falling kontra always has a floor waiting
    fn wanted_sections(&self) -> HashSet<[i32; 3]> {
        let mut wanted: HashSet<[i32; 3]> = HashSet::new();
        for k in self.kontras.values() {
            let b = match self.bodies.get(k.body) { Some(b) => b, None => continue };
            let t = b.translation();
            let v = b.linvel();
            let r = b.rotation();
            let m = glam::Mat3::from_quat(Quat::from_xyzw(r.x, r.y, r.z, r.w));
            let h = k.obb.half;
            // rotated half-extents projected onto world axes
            let ext = Vec3::new(
                m.x_axis.x.abs() * h.x + m.y_axis.x.abs() * h.y + m.z_axis.x.abs() * h.z,
                m.x_axis.y.abs() * h.x + m.y_axis.y.abs() * h.y + m.z_axis.y.abs() * h.z,
                m.x_axis.z.abs() * h.x + m.y_axis.z.abs() * h.y + m.z_axis.z.abs() * h.z,
            );
            let pos  = Vec3::new(t.x, t.y, t.z);
            let look = Vec3::new(v.x, v.y, v.z) * 0.75;
            let min = pos - ext - Vec3::splat(4.0) + look.min(Vec3::ZERO) - Vec3::new(0.0, 12.0, 0.0);
            let max = pos + ext + Vec3::splat(4.0) + look.max(Vec3::ZERO);
            let (sx0, sy0, sz0) = (sections::sec_coord(min.x.floor() as i32),
                                   sections::sec_coord(min.y.floor() as i32),
                                   sections::sec_coord(min.z.floor() as i32));
            let (sx1, sy1, sz1) = (sections::sec_coord(max.x.floor() as i32),
                                   sections::sec_coord(max.y.floor() as i32),
                                   sections::sec_coord(max.z.floor() as i32));
            for sx in sx0..=sx1 { for sy in sy0..=sy1 { for sz in sz0..=sz1 {
                wanted.insert([sx, sy, sz]);
            }}}
        }
        wanted
    }

    // last-resort de-clipper: a block that ended up INSIDE cached solid terrain (missed CCD, monster
    // kontra, whatever) pushes the whole body back up and kills the downward velocity. resting contact
    // penetration is millimetres — this only wakes up past half a block, normal physics never trips it.
    fn anti_clip(&mut self) {
        if self.step_count % 5 != 0 { return; }
        // a body nailed to the world by a joint can't have fallen through anything, the joint holds
        // it. a bearing's turning half sits in the cell behind its plate ON PURPOSE, inside the wall
        // or floor the bearing stands on, and got lifted 0.35 every 5 steps and yanked back — the
        // "bearing loses its half" koper kept seeing
        let pinned: std::collections::HashSet<i64> = self.joints.values()
            .filter(|rec| rec.b == 0).map(|rec| rec.a).collect();
        let sections = &self.sections;
        let kontras  = &self.kontras;
        let bodies   = &mut self.bodies;
        for (id, k) in kontras.iter() {
            if pinned.contains(id) { continue; }
            let b = match bodies.get_mut(k.body) { Some(b) => b, None => continue };
            if b.is_sleeping() { continue; }
            let lv = b.linvel();
            if lv.y > 0.05 { continue; }
            let t = b.translation();
            let r = b.rotation();
            let q = Quat::from_xyzw(r.x, r.y, r.z, r.w);
            let pos = Vec3::new(t.x, t.y, t.z);
            let mut worst = 0.0f32;
            for off in &k.block_offsets {
                let wp = pos + q * Vec3::from(*off);
                let (cx, cy, cz) = (wp.x.floor() as i32, wp.y.floor() as i32, wp.z.floor() as i32);
                if !sections.solid_at(cx, cy, cz) { continue; }
                // how deep the block CENTRE sits below the cell's top face. measuring from the
                // block bottom was the levitating-ramp bug: a tipping corner grazing the ground
                // 5cm deep read as "buried" and got lifted+frozen every 5 steps → hovering ramp.
                let depth = (cy as f32 + 1.0) - wp.y;
                if depth > worst { worst = depth; }
            }
            // 0.45 = the centre is nearly half a block under the floor — genuinely clipped through,
            // something the contact solver never allows in normal play. tipping stays untouched.
            if worst > 0.45 {
                khys_log(format_args!("anticlip k{} depth={:.2} y={:.2}", k.id, worst, t.y));
                let lift = (worst - 0.40).min(0.5);
                b.set_translation(Vec3::new(t.x, t.y + lift, t.z), true);
                let v = b.linvel();
                if v.y < 0.0 { b.set_linvel(Vec3::new(v.x, 0.0, v.z), true); }
            }
        }
    }

    // DETECTION ONLY — never touches the body. a kontra that happens to rest exactly on the
    // world grid (freshly assembled ones do, until their first real move) gets served to vanilla
    // as real solid blocks; anything rotated or offset stays on the SAT path. a 40° ramp is a
    // ramp, it stays a 40° ramp — no snapping, no sliding, koper said so and koper is right.
    fn detect_grid_rest(&mut self) {
        for k in self.kontras.values_mut() {
            let b = match self.bodies.get(k.body) { Some(b) => b, None => continue };
            if b.is_sleeping() { continue; } // keeps whatever state it fell asleep with
            let lv = b.linvel();
            let av = b.angvel();
            if Vec3::new(lv.x, lv.y, lv.z).length() > 0.03
                || Vec3::new(av.x, av.y, av.z).length() > 0.03 {
                k.aligned = false;
                continue;
            }
            let r = b.rotation();
            let q = Quat::from_xyzw(r.x, r.y, r.z, r.w).normalize();
            let (qa, angle) = nearest_grid_quat(q);
            // hysteresis — a kontra balancing right on the threshold used to flicker between
            // solid-blocks and SAT every few ticks, which read as "collision randomly gone"
            let (ang_gate, eps) = if k.aligned { (0.035, 0.06) } else { (0.02, 0.03) };
            // angle error grows with distance from centre — the farthest block must stay in-cell
            let radius = k.obb.half.length().max(1.0);
            if angle * radius > ang_gate { k.aligned = false; continue; }
            let t = b.translation();
            let pos = Vec3::new(t.x, t.y, t.z);
            let off0 = match k.block_offsets.first() { Some(o) => Vec3::from(*o), None => continue };
            let w0 = pos + qa * off0; // qa not q — fp noise must not wobble the cell mapping
            let e = Vec3::new(frac01(w0.x) - 0.5, frac01(w0.y) - 0.5, frac01(w0.z) - 0.5);
            k.aligned = e.x.abs() < eps && e.y.abs() < eps && e.z.abs() < eps;
        }
    }

    pub fn build_snapshot(&self) -> WorldSnapshot {
        let mut snap = WorldSnapshot::default();
        for (id, k) in &self.kontras {
            let b = match self.bodies.get(k.body) { Some(b) => b, None => continue };
            let t = b.translation();
            let r = b.rotation();
            let v = b.linvel();

            snap.kontras.insert(*id, KontraSnap {
                pos: [t.x, t.y, t.z],
                rot: [r.x, r.y, r.z, r.w],
                vel: [v.x, v.y, v.z],
                obb: k.obb.clone(),
                aligned: k.aligned,
                state: body_state(b, k, self.gravity, 0.0),
            });
        }
        snap.missing_sections = self.sections.missing.clone();
        for (jid, rec) in &self.joints {
            let j = match self.impulse_joints.get(rec.handle) { Some(j) => j, None => continue };
            let (b1, b2) = match (self.bodies.get(j.body1), self.bodies.get(j.body2)) {
                (Some(b1), Some(b2)) => (b1, b2),
                _ => continue,
            };
            if rec.prismatic {
                // slide distance along the axis + closing speed
                let q1 = *b1.rotation();
                let axis_w = Quat::from_xyzw(q1.x, q1.y, q1.z, q1.w) * rec.axis;
                let t1 = b1.translation(); let t2 = b2.translation();
                let d = Vec3::new(t2.x - t1.x, t2.y - t1.y, t2.z - t1.z);
                let v1 = b1.linvel(); let v2 = b2.linvel();
                let rel_v = Vec3::new(v2.x - v1.x, v2.y - v1.y, v2.z - v1.z);
                let position = d.dot(axis_w);
                let velocity = rel_v.dot(axis_w);
                if position.is_finite() && velocity.is_finite() {
                    snap.joints.insert(*jid, [position, velocity, position]);
                }
            } else {
                let angle = j.data.as_revolute()
                    .map(|r| r.angle(b1.rotation(), b2.rotation()))
                    .unwrap_or(0.0);
                let q1 = *b1.rotation();
                let axis_w = Quat::from_xyzw(q1.x, q1.y, q1.z, q1.w) * rec.axis;
                let w1 = b1.angvel(); let w2 = b2.angvel();
                let rel_w = Vec3::new(w2.x - w1.x, w2.y - w1.y, w2.z - w1.z);
                let velocity = rel_w.dot(axis_w);
                if angle.is_finite() && velocity.is_finite() {
                    snap.joints.insert(*jid, [angle, velocity, rec.last_angle.map_or(angle, |_| rec.turned)]);
                }
            }
        }
        snap
    }

    fn set_fluids_raw(&mut self, data: &[[i32; 4]]) {
        self.fluids.clear();
        for d in data {
            self.fluids.insert([d[0], d[1], d[2]], d[3] as f32 / 1000.0);
        }
    }

    fn aero_cl(base: f32, alpha: f32, extreme: bool, incidence: f32) -> f32 {
        let stall = if extreme { 0.26 } else { 0.30 };
        let signed = alpha + incidence;
        let a = signed.abs().min(std::f32::consts::FRAC_PI_2);
        let curve = if a <= stall {
            a / stall
        } else {
            let past = ((a - stall) / (std::f32::consts::FRAC_PI_2 - stall)).clamp(0.0, 1.0);
            1.0 - 0.75 * past
        };
        base * curve * signed.signum()
    }

    fn aero_panel_force(
        point_air_vel: Vec3, normal: Vec3, area: f32, cd: f32, cl: f32,
        density: f32, aspect_ratio: f32, extreme: bool,
        alpha_override: Option<f32>, separation: f32, induced_scale: f32, incidence: f32,
    ) -> Vec3 {
        let speed = point_air_vel.length();
        if speed < 0.05 || area <= 0.0 { return Vec3::ZERO; }
        let v_dir = point_air_vel / speed;
        let n = normal.try_normalize().unwrap_or(Vec3::Y);
        let alpha = alpha_override.unwrap_or_else(|| (-v_dir.dot(n)).clamp(-1.0, 1.0).asin());
        let mut cl_eff = if cl > 0.0 { Self::aero_cl(cl, alpha, extreme, incidence) } else { 0.0 };
        let wing = cl > 0.0;
        let mut cd_eff = if wing {
            (cd * if extreme { 0.045 } else { 0.07 }).clamp(0.015, 0.16)
        } else {
            cd.max(0.01)
        };

        if extreme {
            // one-block chord; Reynolds correction matters mainly near takeoff speed
            let reynolds = speed / 1.48e-5;
            let re_factor = (reynolds / 250_000.0).clamp(0.35, 1.15).sqrt();
            cl_eff *= re_factor;
            cd_eff /= re_factor.max(0.55);
            let mach = (speed / 343.0).min(0.75);
            cl_eff /= (1.0 - mach * mach).sqrt().max(0.66);
            cl_eff *= 1.0 - 0.68 * separation.clamp(0.0, 1.0);
            cd_eff += 0.72 * separation.clamp(0.0, 1.0) * alpha.sin().abs();
        }

        // Koper masses are much heavier than SI kilograms at one-block wing scale. This gain keeps the
        // coefficient curve useful without also multiplying hull/profile drag.
        let lift_gain = if extreme { 3.8 } else { 3.0 };
        cd_eff += cl_eff * cl_eff * lift_gain * induced_scale.clamp(0.35, 1.0)
            / (std::f32::consts::PI * aspect_ratio.clamp(1.0, 20.0) * if extreme { 0.90 } else { 0.82 });
        let q = 0.5 * density * speed * speed * area;
        let drag = -v_dir * (q * cd_eff);
        let lift_axis = n - v_dir * n.dot(v_dir);
        let lift = lift_axis.try_normalize().unwrap_or(Vec3::ZERO) * (q * cl_eff * lift_gain);
        let force = drag + lift;
        if force.is_finite() { force } else { Vec3::ZERO }
    }

    // real water: per-block buoyancy + drag, force applied AT each submerged block, so hulls tip,
    // self-right and settle instead of the old whole-body up-shove. fluid cells stream in with the
    // section terrain (fluid bitset) — the legacy SetFluids map still counts as water on top.
    // displaced mass per block = water_density * buoyancy_volume (KhysWeightBook), so wood floats
    // and whatever the pack calls heavy sinks. drag is linear in point velocity — stable at 60Hz.
    fn apply_water(&mut self, dt: f32) {
        if dt <= 0.0 { return; }
        // the world's real gravity, not the 28 it used to be pinned to — a pack that sets a dimension
        // to moon gravity got earth-strength buoyancy and every boat there jumped out of the water
        let g_mag = self.gravity.length();
        const WATER_DRAG: f32 = 2.5;

        let fluids = &self.fluids;
        let sections = &self.sections;
        if fluids.is_empty() && !sections.any_fluid() {
            for k in self.kontras.values_mut() { k.submerged = 0.0; }
            return;
        }
        let rho = self.water_density;
        let kontras = &mut self.kontras;
        let bodies = &mut self.bodies;

        for k in kontras.values_mut() {
            let b = match bodies.get_mut(k.body) { Some(b) => b, None => continue };
            if b.is_sleeping() { continue; }
            let mut wet_blocks = 0usize;
            let t = b.translation();
            let pos = Vec3::new(t.x, t.y, t.z);
            let r = b.rotation();
            let q = Quat::from_xyzw(r.x, r.y, r.z, r.w);
            let lv = b.linvel(); let lin = Vec3::new(lv.x, lv.y, lv.z);
            let av = b.angvel(); let ang = Vec3::new(av.x, av.y, av.z);

            for off in &k.block_offsets {
                let arm = q * Vec3::from(*off);
                let wp = pos + arm;
                let (cx, cy, cz) = (wp.x.floor() as i32, wp.y.floor() as i32, wp.z.floor() as i32);
                let wet = sections.fluid_at(cx, cy, cz) || fluids.contains_key(&[cx, cy, cz]);
                if !wet { continue; }
                wet_blocks += 1;
                // surface sits at the top of the highest wet cell; a wet cell above = fully under
                let above = sections.fluid_at(cx, cy + 1, cz) || fluids.contains_key(&[cx, cy + 1, cz]);
                let surface = if above { f32::INFINITY } else { cy as f32 + 0.9 };
                let frac = (surface - (wp.y - 0.5)).clamp(0.0, 1.0);
                if frac <= 0.0 { continue; }
                let key = [jround(off[0]), jround(off[1]), jround(off[2])];
                let vol = k.buoy_volumes.get(&key).copied().unwrap_or(1.0);
                if vol <= 0.0 { continue; }
                let v_pt = lin + ang.cross(arm);
                let up = g_mag * rho * vol * frac * k.buoy_scale;
                let drag = -v_pt * (rho * vol * frac * WATER_DRAG);
                let f = Vec3::new(drag.x, up + drag.y, drag.z);
                if f.is_finite() { b.add_force_at_point(f, wp, true); }
            }
            k.submerged = if k.block_offsets.is_empty() { 0.0 }
                else { wet_blocks as f32 / k.block_offsets.len() as f32 };
        }
    }

    // Every body reachable through joints from every other one, flattened to a single id per island.
    // Two parts of ONE creation must never push each other: in a chain of bearings the pieces that are
    // not directly jointed still overlap, and the contact solver blasts the whole build apart. That is
    // the "stack three joints and it explodes" bug, and no amount of solver iterations fixes it.
    fn assemblies(&self) -> HashMap<RigidBodyHandle, u32> {
        let mut root: HashMap<RigidBodyHandle, RigidBodyHandle> = HashMap::new();
        fn find(root: &mut HashMap<RigidBodyHandle, RigidBodyHandle>,
                h: RigidBodyHandle) -> RigidBodyHandle {
            let mut cur = h;
            while let Some(&up) = root.get(&cur) {
                if up == cur { break; }
                cur = up;
            }
            root.insert(h, cur);
            cur
        }
        for rec in self.joints.values() {
            // a world-anchored joint has b == 0 and kontras.get(&0) is None, which used to skip the
            // whole record — so a bearing pinned to the world never even entered this map, the
            // contact filter saw None and let everything collide. that is the same "stack joints and
            // it explodes" case the filter exists to stop, just through the world instead of a body.
            if let Some(ka) = self.kontras.get(&rec.a) { root.entry(ka.body).or_insert(ka.body); }
            if let Some(kb) = self.kontras.get(&rec.b) { root.entry(kb.body).or_insert(kb.body); }
            let (Some(ka), Some(kb)) = (self.kontras.get(&rec.a), self.kontras.get(&rec.b)) else { continue };
            let (ha, hb) = (ka.body, kb.body);
            root.entry(ha).or_insert(ha);
            root.entry(hb).or_insert(hb);
            let (ra, rb) = (find(&mut root, ha), find(&mut root, hb));
            if ra != rb { root.insert(ra, rb); }
        }
        let handles: Vec<RigidBodyHandle> = root.keys().copied().collect();
        let mut out = HashMap::new();
        for h in handles {
            let r = find(&mut root, h);
            out.insert(h, r.0.into_raw_parts().0);
        }
        out
    }

    fn create_joint(&mut self, joint_id: i64, a: i64, b: i64,
                    anchor_a: [f32; 3], anchor_b: [f32; 3], axis: [f32; 3], prismatic: bool) {
        let ba = match self.kontras.get(&a) {
            Some(k) => k.body,
            None => return,
        };
        self.destroy_joint(joint_id);
        let (bb, local_anchor_b, anchor_body) = if b == 0 {
            // A world joint gets its own Rapier fixed body. Reusing the terrain body made
            // contacts_enabled(false) disable every terrain contact on the attached wheel.
            let fixed = self.bodies.insert(
                RigidBodyBuilder::fixed()
                    .translation(Vec3::from(anchor_b))
                    .build());
            (fixed, Vec3::ZERO, Some(fixed))
        } else {
            match self.kontras.get(&b) {
                Some(k) => (k.body, Vec3::from(anchor_b), None),
                None => return,
            }
        };
        // A joint must NEVER be born violated. If the two anchors do not already name the same world
        // point, the solver's first step yanks them together with a corrective impulse — and a light
        // build takes off like a rocket the instant it spawns, slams into the ground, and the anchor
        // error reads a perfect 0.0000 afterwards because the solver already won. Close the gap by
        // MOVING the body instead, and say so, because a violated joint means the caller's anchor
        // maths is wrong and that is worth knowing.
        {
            let world_a = self.bodies.get(ba).map(|body| {
                let t = body.translation();
                let r = body.rotation();
                Vec3::new(t.x, t.y, t.z)
                    + Quat::from_xyzw(r.x, r.y, r.z, r.w) * Vec3::from(anchor_a)
            });
            let world_b = if b == 0 {
                Some(Vec3::from(anchor_b))
            } else {
                self.bodies.get(bb).map(|body| {
                    let t = body.translation();
                    let r = body.rotation();
                    Vec3::new(t.x, t.y, t.z) + Quat::from_xyzw(r.x, r.y, r.z, r.w) * local_anchor_b
                })
            };
            if let (Some(wa), Some(wb)) = (world_a, world_b) {
                let gap = wb - wa;
                if gap.length() > 0.05 {
                    // eprintln never reaches the runClient console (see the note on khys_log at the
                    // top of this file), so this warning has never once been seen by anybody. A joint
                    // revived blind from kontras.bin with a pose that no longer matches its anchors
                    // lands here, and "the build took off like a rocket on world load" is exactly the
                    // symptom. Put it where it can actually be read.
                    let pa = self.bodies.get(ba).map(|x| x.translation()).unwrap_or(Vec3::ZERO);
                    let pb = self.bodies.get(bb).map(|x| x.translation()).unwrap_or(Vec3::ZERO);
                    khys_log(format_args!(
                        "JOINT BORN VIOLATED: joint {joint_id} ({a}->{b}) born {:.3} apart | \
                         bodyA=({:.3},{:.3},{:.3}) anchorA_local=({:.3},{:.3},{:.3}) worldA=({:.3},{:.3},{:.3}) | \
                         bodyB=({:.3},{:.3},{:.3}) anchorB_local=({:.3},{:.3},{:.3}) worldB=({:.3},{:.3},{:.3})",
                        gap.length(),
                        pa.x, pa.y, pa.z, anchor_a[0], anchor_a[1], anchor_a[2], wa.x, wa.y, wa.z,
                        pb.x, pb.y, pb.z, local_anchor_b.x, local_anchor_b.y, local_anchor_b.z,
                        wb.x, wb.y, wb.z));
                    // Move the CHILD, never the parent. Body A is the thing everything else hangs
                    // off — a car chassis carries a joint per wheel plus the steering — so nudging it
                    // to satisfy one joint silently violates every joint already attached to it. Each
                    // following joint then moves the chassis again to fix ITS anchor, and the error
                    // compounds until the car is scattered. Body B is a leaf (a wheel, a mounted
                    // head): moving it disturbs nothing else. A world joint has no body B, so there
                    // the kontra is still the only end that can move.
                    let (to_move, shift) = if b == 0 { (ba, gap) } else { (bb, -gap) };
                    if let Some(body) = self.bodies.get_mut(to_move) {
                        let t = body.translation();
                        body.set_translation(
                            Vec3::new(t.x + shift.x, t.y + shift.y, t.z + shift.z), true);
                        body.set_linvel(Vec3::ZERO, true);
                        body.set_angvel(Vec3::ZERO, true);
                    }
                }
            }
        }

        let ax = Vec3::from(axis).try_normalize().unwrap_or(Vec3::Y);
        // Tiny bearing heads amplify the normal six-iteration joint error into visible side wobble,
        // and a car at speed stretches its bearings enough that the mounted part visibly floats up and
        // settles back. More iterations is the only real lever — and it costs nothing until a body
        // actually joins a mechanism.
        // both ends MUST be awake before the joint lands. rapier merges the two islands next step
        // and asserts they share a sleeping status, so jointing a fresh body onto something that
        // already fell asleep (a lift that sat there a second) aborts the whole process with
        // "cannot merge two island with different sleeping statuses". SIGABRT, no stacktrace, gone.
        if let Some(body) = self.bodies.get_mut(ba) {
            body.set_additional_solver_iterations(
                body.additional_solver_iterations().max(JOINT_SOLVER_ITERS));
            body.wake_up(true);
        }
        if b != 0 {
            if let Some(body) = self.bodies.get_mut(bb) {
                body.set_additional_solver_iterations(
                    body.additional_solver_iterations().max(JOINT_SOLVER_ITERS));
                body.wake_up(true);
            }
        }
        let handle = if prismatic {
            let j = PrismaticJointBuilder::new(ax)
                .local_anchor1(Vec3::from(anchor_a))
                .local_anchor2(local_anchor_b)
                .contacts_enabled(false)
                .build();
            self.impulse_joints.insert(ba, bb, j, true)
        } else {
            let j = RevoluteJointBuilder::new(ax)
                .local_anchor1(Vec3::from(anchor_a))
                .local_anchor2(local_anchor_b)
                .contacts_enabled(false)
                .build();
            self.impulse_joints.insert(ba, bb, j, true)
        };
        self.joint_topology_dirty = true;
        self.joints.insert(joint_id, JointRec {
            handle, a, b,
            anchor_a: Vec3::from(anchor_a),
            anchor_b: local_anchor_b,
            axis: ax, prismatic, anchor_body,
            last_angle: None, turned: 0.0, hand: 1.0,
        });
    }

    // Rapier solves every body independently, so an impulse joint is allowed a small positional
    // error. On a 1px bearing that error is the whole visible part. Treat joint direction as a
    // parent/child hierarchy and move the complete child subtree by the residual after each solve.
    // Rotation still belongs to Rapier; this only removes translation that a bearing never permits.
    fn project_joint_hierarchy(&mut self) {
        if self.joints.is_empty() { return; }
        if self.joint_topology_dirty { self.rebuild_joint_topology(); }

        // anything under this is far below one screen pixel at any sane view distance. it used to be
        // 1e-10, which meant every jointed body got set_translation EVERY step — and each of those
        // re-transforms all of that body's colliders and dirties the broad-phase. that was the joint tax.
        const MIN_CORRECTION_SQ: f32 = 1.0e-6;
        for index in 0..self.joint_order.len() {
            let rec = self.joint_order[index];
            let Some(a_handle) = self.kontras.get(&rec.a).map(|k| k.body) else { continue };
            let Some(a_body) = self.bodies.get(a_handle) else { continue };
            let at = a_body.translation();
            let ar = a_body.rotation();
            let aq = Quat::from_xyzw(ar.x, ar.y, ar.z, ar.w);
            let anchor_a = Vec3::new(at.x, at.y, at.z) + aq * rec.anchor_a;

            let (anchor_b, moving_root, axis_world) = if rec.b == 0 {
                let Some(fixed) = rec.anchor_body.and_then(|h| self.bodies.get(h)) else { continue };
                let bt = fixed.translation();
                (Vec3::new(bt.x, bt.y, bt.z) + rec.anchor_b, rec.a, aq * rec.axis)
            } else {
                let Some(b_handle) = self.kontras.get(&rec.b).map(|k| k.body) else { continue };
                let Some(b_body) = self.bodies.get(b_handle) else { continue };
                let bt = b_body.translation();
                let br = b_body.rotation();
                let bq = Quat::from_xyzw(br.x, br.y, br.z, br.w);
                (Vec3::new(bt.x, bt.y, bt.z) + bq * rec.anchor_b, rec.b, aq * rec.axis)
            };
            let mut correction = anchor_a - anchor_b;
            if rec.b == 0 { correction = -correction; }
            if rec.prismatic {
                let axis = axis_world.try_normalize().unwrap_or(Vec3::Y);
                correction -= axis * correction.dot(axis);
            }
            if !correction.is_finite() || correction.length_squared() < MIN_CORRECTION_SQ { continue; }

            // This pass exists to erase sub-pixel joint error, and a settled bearing sits under 0.005
            // blocks. Anything approaching a whole block is not solver noise — the joint is genuinely
            // violated, and teleporting the ENTIRE child subtree by that much is how a whole car ends
            // up in orbit with all of its joints still reading perfect: nothing here touches velocity,
            // so damping cannot bleed it off and the anchors look fine afterwards. Hand those back to
            // the solver, which is what it is for, and say so.
            // A JointRec whose Rapier constraint is gone is a zombie: rapier removes every joint
            // attached to a body when that body is removed, but this map keeps its record. Nothing
            // then constrains the two bodies — gravity pulls the child away, this pass drags it back,
            // and the pair settles at a CONSTANT gap that never converges. That is exactly what the
            // log shows: the same "wanted 1.472 blocks" for thousands of steps in a row.
            if KHYS_VALIDATE_ISLANDS && self.zombie_joints_seen.insert((rec.a, rec.b)) {
                match self.impulse_joints.get(rec.handle) {
                    None => khys_log(format_args!(
                        "ZOMBIE JOINT: {}->{} still in the khysics joint map but its rapier \
                         constraint is gone", rec.a, rec.b)),
                    Some(joint) => {
                        // The rapier constraint is alive and rapier is happy with it, yet this pass
                        // measures a gap that never closes. Then the two disagree about WHERE the
                        // anchors are: rapier enforces local_frame1/2, this pass uses JointRec. Print
                        // both so the mismatch is a number, not a theory.
                        let r1 = joint.data.local_frame1.translation;
                        let r2 = joint.data.local_frame2.translation;
                        khys_log(format_args!(
                            "ANCHOR CHECK {}->{}: rec_a=({:.3},{:.3},{:.3}) rapier_a=({:.3},{:.3},{:.3}) \
                             rec_b=({:.3},{:.3},{:.3}) rapier_b=({:.3},{:.3},{:.3})",
                            rec.a, rec.b,
                            rec.anchor_a.x, rec.anchor_a.y, rec.anchor_a.z, r1.x, r1.y, r1.z,
                            rec.anchor_b.x, rec.anchor_b.y, rec.anchor_b.z, r2.x, r2.y, r2.z));
                    }
                }
            }
            // subtree of the moving end, precomputed with the topology
            let subtree = match self.joint_subtrees.get(&moving_root) {
                Some(list) => { self.joint_scratch.clear(); self.joint_scratch.extend_from_slice(list); &self.joint_scratch }
                None => { self.joint_scratch.clear(); self.joint_scratch.push(moving_root); &self.joint_scratch }
            };
            for i in 0..subtree.len() {
                let id = self.joint_scratch[i];
                let Some(handle) = self.kontras.get(&id).map(|k| k.body) else { continue };
                if let Some(body) = self.bodies.get_mut(handle) {
                    let at = body.translation();
                    body.set_translation(Vec3::new(
                        at.x + correction.x, at.y + correction.y, at.z + correction.z), true);
                }
            }
        }
    }

    // parent/child order + per-root subtree, built once per joint-set change instead of per step
    fn rebuild_joint_topology(&mut self) {
        self.joint_topology_dirty = false;
        self.joint_order.clear();
        self.joint_children.clear();
        self.joint_subtrees.clear();
        if self.joints.is_empty() { return; }

        let records: Vec<JointRec> = self.joints.values().copied().collect();
        let mut incoming: HashSet<i64> = HashSet::new();
        for rec in &records {
            if rec.b == 0 { continue; }
            self.joint_children.entry(rec.a).or_default().push(rec.b);
            incoming.insert(rec.b);
        }

        let mut queued: HashSet<(i64, i64)> = HashSet::new();
        let mut queue: VecDeque<JointRec> = VecDeque::new();
        for rec in &records {
            if rec.b == 0 || !incoming.contains(&rec.a) {
                if queued.insert((rec.a, rec.b)) { queue.push_back(*rec); }
            }
        }
        while let Some(rec) = queue.pop_front() {
            self.joint_order.push(rec);
            if rec.b == 0 { continue; }
            for child in records.iter().filter(|next| next.a == rec.b && next.b != 0) {
                if queued.insert((child.a, child.b)) { queue.push_back(*child); }
            }
        }
        for rec in &records {
            if queued.insert((rec.a, rec.b)) { self.joint_order.push(*rec); }
        }

        // every body that hangs off a given root, so the per-step pass is just a copy
        let roots: Vec<i64> = self.joint_order.iter()
            .map(|rec| if rec.b == 0 { rec.a } else { rec.b }).collect();
        for root in roots {
            if self.joint_subtrees.contains_key(&root) { continue; }
            let mut seen: HashSet<i64> = HashSet::new();
            let mut walk: VecDeque<i64> = VecDeque::from([root]);
            let mut out: Vec<i64> = Vec::new();
            while let Some(id) = walk.pop_front() {
                if !seen.insert(id) { continue; }
                out.push(id);
                if let Some(children) = self.joint_children.get(&id) {
                    for child in children { walk.push_back(*child); }
                }
            }
            self.joint_subtrees.insert(root, out);
        }
    }

    // a parked body is asleep, and telling a joint to move does not by itself wake it — the motor
    // then does absolutely nothing until something else bumps the body. a lift platform sitting still
    // is exactly that case, so every motor command has to knock on the door.
    fn wake_joint(&mut self, joint_id: i64) {
        let Some(rec) = self.joints.get(&joint_id) else { return };
        let (a, b) = (rec.a, rec.b);
        for id in [a, b] {
            if id == 0 { continue; }
            let Some(k) = self.kontras.get(&id) else { continue };
            let handle = k.body;
            if let Some(body) = self.bodies.get_mut(handle) { body.wake_up(true); }
        }
    }

    // Rapier warm-starts motors/limits with last step's impulse. That impulse is only valid for the
    // old mass: removing 10 kg of cargo from a 0.01 kg suspension head otherwise replays the loaded
    // spring force into the tiny head, and adding the cargo back replays the unloaded solution.
    fn clear_joint_warmstarts_for_body(&mut self, kontra_id: i64) {
        let handles: Vec<ImpulseJointHandle> = self.joints.values()
            .filter(|rec| rec.a == kontra_id || rec.b == kontra_id)
            .map(|rec| rec.handle)
            .collect();
        for handle in handles {
            if let Some(joint) = self.impulse_joints.get_mut(handle, true) {
                for motor in &mut joint.data.motors { motor.impulse = 0.0; }
                for limit in &mut joint.data.limits { limit.impulse = 0.0; }
            }
        }
    }

    fn destroy_joint(&mut self, joint_id: i64) {
        let Some(rec) = self.joints.remove(&joint_id) else { return };
        self.joint_topology_dirty = true;
        self.impulse_joints.remove(rec.handle, true);
        if let Some(anchor) = rec.anchor_body {
            self.bodies.remove(
                anchor,
                &mut self.islands,
                &mut self.colliders,
                &mut self.impulse_joints,
                &mut self.multibody_joints,
                true,
            );
        }
        // create_joint bumps both ends to JOINT_SOLVER_ITERS and nothing ever put it back, so a body
        // that held a joint once kept paying for 16 extra solver iterations forever — and every
        // respawned build inherited another one. hand it back when the last joint is gone.
        for id in [rec.a, rec.b] {
            if id == 0 { continue; }
            if self.joints.values().any(|other| other.a == id || other.b == id) { continue; }
            let Some(k) = self.kontras.get(&id) else { continue };
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.set_additional_solver_iterations(0);
            }
        }
    }

    // Three per-kontraktion costs. None of them adds fake self-righting or vertical damping.
    // Low is a cheap lumped wing, Correct is one proper craft airfoil, Extreme is per-block panels.
    fn apply_aero(&mut self, dt: f32) {
        if dt <= 0.0 { return; }
        // real gravity magnitude (was a hard 28) — lift caps and balloon force scale with the world
        #[allow(non_snake_case)]
        let G: f32 = self.gravity.length();
        const RHO0: f32 = 0.08; // khysics mass scale, not kilograms
        const SEA_Y: f32 = 64.0;
        const SCALE_HEIGHT: f32 = 180.0;

        let wind = self.wind;
        let self_up = (-self.gravity).try_normalize().unwrap_or(Vec3::Y);
        // the aero caps below scale with mass. a light rotor on a bearing pulls the whole machine, so
        // it is capped by what it is jointed to, not by its own four plates. world joints (b = 0) do
        // not count, a rotor on a static bearing is holding the planet
        let mut assembly_mass: HashMap<i64, f32> = HashMap::new();
        {
            let mut parent: HashMap<i64, i64> = self.kontras.keys().map(|id| (*id, *id)).collect();
            fn root(parent: &mut HashMap<i64, i64>, mut id: i64) -> i64 {
                while let Some(&up) = parent.get(&id) {
                    if up == id { break; }
                    id = up;
                }
                id
            }
            for rec in self.joints.values() {
                if rec.a == 0 || rec.b == 0 { continue; }
                let (ra, rb) = (root(&mut parent, rec.a), root(&mut parent, rec.b));
                if ra != rb { parent.insert(ra, rb); }
            }
            let mut totals: HashMap<i64, f32> = HashMap::new();
            for (id, k) in &self.kontras {
                let m = self.bodies.get(k.body).map(|b| b.mass()).unwrap_or(0.0);
                *totals.entry(root(&mut parent, *id)).or_insert(0.0) += m;
            }
            for id in self.kontras.keys() {
                let total = totals.get(&root(&mut parent, *id)).copied().unwrap_or(0.0);
                assembly_mass.insert(*id, total);
            }
        }
        // which way each spinning part pushes: along its bearing's axis, out of the bearing, whichever
        // way it turns. blocks can't be twisted into a handed blade, so the thrust used to follow the
        // spin: a counter-rotating pair pushed against each other and the plane went sideways, and a
        // helicopter's counter-rotor pressed it into the ground. real counter-rotating props have
        // mirrored blades for exactly this reason. the axis is in the frame of end a of the joint
        let mut rotor_axis: HashMap<i64, (Vec3, f32)> = HashMap::new();
        for rec in self.joints.values() {
            if rec.prismatic { continue; }
            let spinner = if rec.b == 0 { rec.a } else { rec.b };
            let Some(frame) = self.kontras.get(&rec.a).and_then(|k| self.bodies.get(k.body)) else { continue };
            let r = frame.rotation();
            rotor_axis.insert(spinner, (Quat::from_xyzw(r.x, r.y, r.z, r.w) * rec.axis, rec.hand));
        }
        let sections = &self.sections;
        let kontras = &mut self.kontras;
        let bodies = &mut self.bodies;

        for (kontra_id, k) in kontras.iter_mut() {
            let b = match bodies.get_mut(k.body) { Some(b) => b, None => continue };
            if b.is_sleeping() { continue; }

            let mass = b.mass().max(0.01);
            let cap_mass = assembly_mass.get(kontra_id).copied().unwrap_or(mass).max(mass);
            let r = b.rotation();
            let qrot = Quat::from_xyzw(r.x, r.y, r.z, r.w);
            let t = b.translation();
            let pos = Vec3::new(t.x, t.y, t.z);
            let lv = b.linvel();
            let lin = Vec3::new(lv.x, lv.y, lv.z);
            let av = b.angvel();
            let ang = Vec3::new(av.x, av.y, av.z);

            let density_at = |y: f32| {
                RHO0 * (-(y - SEA_Y) / SCALE_HEIGHT).exp().clamp(0.001, 1.25)
            };
            let mut net_force = Vec3::ZERO;
            let mut net_torque = Vec3::ZERO;
            let mut radius = 1.0f32;
            let mut wing_area = 0.0f32;
            let mut wing_min = Vec3::splat(f32::INFINITY);
            let mut wing_max = Vec3::splat(f32::NEG_INFINITY);
            for s in &k.aero {
                if s.cl <= 0.0 { continue; }
                wing_area += s.area;
                wing_min = wing_min.min(s.off);
                wing_max = wing_max.max(s.off);
            }
            let wing_size = wing_max - wing_min;
            let span = wing_size.x.max(wing_size.z) + 1.0;
            let aspect_ratio = if wing_area > 0.0 {
                (span * span / wing_area).clamp(1.0, 20.0)
            } else { 1.0 };
            // Heavy mode treats disconnected lifting regions as separate wings/tailplanes.
            let mut cluster_id: Vec<usize> = Vec::new();
            let mut cluster_area: Vec<f32> = Vec::new();
            let mut cluster_min: Vec<Vec3> = Vec::new();
            let mut cluster_max: Vec<Vec3> = Vec::new();
            let mut global_span_x = true;
            let mut main_chord = 0.0f32;
            let mut cluster_span: Vec<f32> = Vec::new();
            let mut cluster_span_x: Vec<bool> = Vec::new();
            let mut cluster_mid: Vec<f32> = Vec::new();
            let mut cluster_aspect: Vec<f32> = Vec::new();
            let mut cluster_elliptic: Vec<f32> = Vec::new();
            if k.aero_mode == AeroMode::Extreme {
                cluster_id = vec![usize::MAX; k.aero.len()];
                if k.aero_state.len() != k.aero.len() {
                    k.aero_state.resize(k.aero.len(), AeroPanelState::default());
                }
                for start in 0..k.aero.len() {
                    if k.aero[start].cl <= 0.0 || cluster_id[start] != usize::MAX { continue; }
                    let id = cluster_area.len();
                    cluster_area.push(0.0);
                    cluster_min.push(Vec3::splat(f32::INFINITY));
                    cluster_max.push(Vec3::splat(f32::NEG_INFINITY));
                    let mut queue = VecDeque::from([start]);
                    cluster_id[start] = id;
                    while let Some(i) = queue.pop_front() {
                        let surface = k.aero[i];
                        cluster_area[id] += surface.area;
                        cluster_min[id] = cluster_min[id].min(surface.off);
                        cluster_max[id] = cluster_max[id].max(surface.off);
                        for j in 0..k.aero.len() {
                            if k.aero[j].cl <= 0.0 || cluster_id[j] != usize::MAX { continue; }
                            let d = (k.aero[j].off - surface.off).abs();
                            if d.x + d.y + d.z <= 1.01 {
                                cluster_id[j] = id;
                                queue.push_back(j);
                            }
                        }
                    }
                }
                let main_cluster = cluster_area.iter().enumerate()
                    .max_by(|a, b| a.1.partial_cmp(b.1).unwrap_or(std::cmp::Ordering::Equal))
                    .map(|(i, _)| i).unwrap_or(usize::MAX);
                global_span_x = wing_size.x >= wing_size.z;
                main_chord = if main_cluster != usize::MAX {
                    if global_span_x {
                        (cluster_min[main_cluster].z + cluster_max[main_cluster].z) * 0.5
                    } else {
                        (cluster_min[main_cluster].x + cluster_max[main_cluster].x) * 0.5
                    }
                } else {
                    0.0
                };
                cluster_span = vec![1.0f32; cluster_area.len()];
                cluster_span_x = vec![true; cluster_area.len()];
                cluster_mid = vec![0.0f32; cluster_area.len()];
                cluster_aspect = vec![1.0f32; cluster_area.len()];
                cluster_elliptic = vec![0.0f32; cluster_area.len()];
                for id in 0..cluster_area.len() {
                    let size = cluster_max[id] - cluster_min[id];
                    cluster_span_x[id] = size.x >= size.z;
                    cluster_span[id] = if cluster_span_x[id] { size.x } else { size.z } + 1.0;
                    cluster_mid[id] = if cluster_span_x[id] {
                        (cluster_min[id].x + cluster_max[id].x) * 0.5
                    } else {
                        (cluster_min[id].z + cluster_max[id].z) * 0.5
                    };
                    cluster_aspect[id] = (cluster_span[id] * cluster_span[id] / cluster_area[id].max(0.01))
                        .clamp(1.0, 20.0);
                }
                for (i, surface) in k.aero.iter().enumerate() {
                    let id = cluster_id[i];
                    if id == usize::MAX { continue; }
                    let coord = if cluster_span_x[id] { surface.off.x } else { surface.off.z };
                    let u = (2.0 * (coord - cluster_mid[id]) / cluster_span[id]).clamp(-1.0, 1.0);
                    cluster_elliptic[id] += (1.0 - u * u).sqrt().max(0.20) * surface.area;
                }
            }

            match k.aero_mode {
                AeroMode::Low => {
                    // Cheap arcade glide: one upward term, capped well below weight so it cannot hover.
                    let air_vel = lin - wind;
                    let speed = air_vel.length();
                    let bulk = (k.block_offsets.len() as f32).cbrt().max(1.0);
                    net_force -= air_vel * (mass * (0.55 + speed * 0.045) + bulk * 0.18);
                    let horizontal_sq = air_vel.x * air_vel.x + air_vel.z * air_vel.z;
                    net_force.y += (wing_area * horizontal_sq * 0.025).min(mass * G * 0.38);
                    net_torque -= ang * (mass * 1.8);
                }
                AeroMode::Correct => {
                    let body_air = lin - wind;
                    let body_speed = body_air.length();
                    if body_speed > 0.05 {
                        let dir = body_air / body_speed;
                        let local_dir = qrot.conjugate() * dir;
                        let h = k.obb.half;
                        let projected = 4.0 * (
                            h.y * h.z * local_dir.x.abs()
                            + h.x * h.z * local_dir.y.abs()
                            + h.x * h.y * local_dir.z.abs()
                        );
                        let body_drag = 0.5 * density_at(pos.y) * body_speed * body_speed
                            * projected.max(1.0) * 0.15;
                        net_force -= dir * body_drag;
                    }

                    // every panel feels the air at its OWN point, spin included, along its OWN normal.
                    // this used to be one averaged panel on the body's centre: a spinning rotor has its
                    // centre on the axis, so it felt no spin at all, and a wing tilted on a bearing
                    // got averaged back to flat. balloons are an envelope, handled further down
                    // torque: only the part along the spin axis, i.e. the air resisting a spinning rotor.
                    // Correct stays the forgiving model for pitch/roll (Extreme owns full CoP torque),
                    // but without that axial drag a propeller's thrust looks like free energy and the
                    // passive clamp below deletes it the moment the plane starts moving
                    let spin = ang.length();
                    let spin_axis = if spin > 1.0 { ang / spin } else { Vec3::ZERO };
                    let mut spin_torque = 0.0f32;
                    let rotor = rotor_axis.get(kontra_id).map(|r| r.0);
                    let hand = rotor_axis.get(kontra_id).map_or(1.0, |r| r.1);
                    for s in &k.aero {
                        if s.buoy > 0.0 && s.cl <= 0.0 { continue; }
                        if s.area <= 0.0 { continue; }
                        let arm = qrot * s.off;
                        radius = radius.max(arm.length());
                        let point_vel = lin + ang.cross(arm) - wind;
                        // a plate lying flat in the plane it spins in is a propeller blade. blocks
                        // can't be twisted, so it gets a fixed blade pitch, signed so the push always
                        // runs along the spin axis: spin it the other way and it pulls instead.
                        // anything else that spins gets no incidence at all, the 3 degrees a flat
                        // wing uses would be a twist that makes one spin direction stronger
                        // a full block on a spinning prop is a blade lying in the spin plane: that
                        // is how everyone builds a prop out of cubes. as local up it was a paddle
                        // that only fought the motor. a slow one (a flap on a bearing) stays a wing
                        let normal = match rotor {
                            Some(mount) if s.chunky && ang.dot(mount).abs() > 5.0 => mount,
                            _ => qrot * s.normal,
                        };
                        let along = normal.dot(spin_axis);
                        // a blade is twisted like a real helical prop: the angle falls off with the
                        // radius so every piece of it runs out of pull at the same forward speed,
                        // PROP_PITCH blocks per radian of spin. a flat 0.2 rad plate one block out ran
                        // out at ~12 m/s, slower than any wing needs, so nothing ever took off
                        let incidence = match rotor {
                            // a fin or any upright surface gets no built-in angle, that was a constant
                            // sideways push and the plane slowly turned off course
                            None if normal.dot(self_up).abs() < 0.7 => 0.0,
                            None => {
                                // tailplane decalage: a surface well behind the centre of mass along the
                                // flight path flies a bit flatter than the wing. with both at the same
                                // angle a flat tail trims the plane at zero lift and it just dives. a real
                                // tail is set lower for exactly this, blocks can't be tilted by 2 degrees
                                let behind = if body_speed > 1.0 { arm.dot(body_air / body_speed) } else { 0.0 };
                                if behind < -1.5 { -0.02 } else { 0.06 }
                            }
                            Some(_) if spin <= 1.0 => 0.06,
                            Some(_) if along.abs() > 0.97 => {
                                // twisted for the way the motor drives it: turning that way it pushes
                                // out of its bearing, dragged the other way it pushes back in
                                let radius = (arm - spin_axis * arm.dot(spin_axis)).length().max(0.25);
                                (PROP_PITCH / radius).atan() * along.signum() * hand
                            }
                            Some(_) => 0.0,
                        };
                        let force = Self::aero_panel_force(
                            point_vel, normal, s.area, s.cd, s.cl,
                            density_at(pos.y + arm.y), aspect_ratio, false, None, 0.0, 1.0, incidence,
                        );
                        net_force += force;
                        // an airframe feels every panel's lever arm: the tail keeps the nose into the
                        // wind and an elevator on a bearing can lift it. a rotor keeps only the drag
                        // about its own axle, its blades' off-axis pulls cancel round the turn anyway
                        if rotor.is_some() { spin_torque += arm.cross(force).dot(spin_axis); }
                        else { net_torque += arm.cross(force); }
                    }
                    net_torque += spin_axis * spin_torque;
                }
                AeroMode::Extreme => {
                    // a body on a bearing that spins it is a rotor, same rules as Correct: its blades lie
                    // across the axle, carry a pitch for the way the motor drives them, and only the part
                    // of its torque along the axle is kept. without them a propeller of full blocks was a
                    // stack of flat plates facing up: next to no pull, and a wobble that turned the plane
                    let spin = ang.length();
                    let spin_axis = if spin > 1.0 { ang / spin } else { Vec3::ZERO };
                    let rotor = rotor_axis.get(kontra_id).map(|r| r.0);
                    let hand = rotor_axis.get(kontra_id).map_or(1.0, |r| r.1);
                    let mut spin_torque = 0.0f32;
                    const FACE_DIRS: [([i32; 3], Vec3); 6] = [
                        ([1, 0, 0], Vec3::X), ([-1, 0, 0], Vec3::NEG_X),
                        ([0, 1, 0], Vec3::Y), ([0, -1, 0], Vec3::NEG_Y),
                        ([0, 0, 1], Vec3::Z), ([0, 0, -1], Vec3::NEG_Z),
                    ];
                    for off in &k.block_offsets {
                        let key = [jround(off[0]), jround(off[1]), jround(off[2])];
                        let arm = qrot * Vec3::from(*off);
                        radius = radius.max(arm.length());
                        let point_vel = lin + ang.cross(arm) - wind;
                        let rho = density_at(pos.y + arm.y);
                        for (step, local_normal) in FACE_DIRS {
                            let neighbour = [key[0] + step[0], key[1] + step[1], key[2] + step[2]];
                            if k.block_set.contains(&neighbour) { continue; }
                            let normal = qrot * local_normal;
                            let normal_speed = point_vel.dot(normal);
                            let tangent = point_vel - normal * normal_speed;
                            let face_cd = if normal_speed >= 0.0 { 0.62 } else { 0.08 };
                            let pressure = -normal * (0.5 * rho * normal_speed * normal_speed.abs() * face_cd);
                            let skin = -tangent * (0.5 * rho * tangent.length() * 0.008);
                            let force = pressure + skin;
                            if force.is_finite() {
                                net_force += force;
                                if rotor.is_some() { spin_torque += arm.cross(force).dot(spin_axis); }
                                else { net_torque += arm.cross(force); }
                            }
                        }
                    }

                    let (surfaces, states) = (&k.aero, &mut k.aero_state);
                    for (index, s) in surfaces.iter().enumerate() {
                        let normal = match rotor {
                            Some(mount) if s.chunky && ang.dot(mount).abs() > 5.0 => mount,
                            _ => (qrot * s.normal).try_normalize().unwrap_or(Vec3::Y),
                        };
                        let base_arm = qrot * s.off;
                        let base_vel = lin + ang.cross(base_arm) - wind;
                        let instant_alpha = if base_vel.length_squared() > 1.0e-5 {
                            let dir = base_vel.normalize();
                            (-dir.dot(normal)).clamp(-1.0, 1.0).asin()
                        } else { 0.0 };

                        let state = &mut states[index];
                        let alpha_response = (dt / 0.09).clamp(0.0, 1.0);
                        state.alpha += (instant_alpha - state.alpha) * alpha_response;
                        let separation_target = if s.cl > 0.0 {
                            ((state.alpha.abs() - 0.22) / 0.38).clamp(0.0, 1.0)
                        } else { 0.0 };
                        let separation_tau = if separation_target > state.separation { 0.07 } else { 0.28 };
                        state.separation += (separation_target - state.separation)
                            * (dt / separation_tau).clamp(0.0, 1.0);

                        // Pressure moves aft as the flow separates. Recompute point velocity at the same
                        // point where force is applied so the wrench keeps the correct energy balance.
                        let flow = (-base_vel).try_normalize().unwrap_or(Vec3::ZERO);
                        let chord = (flow - normal * flow.dot(normal)).try_normalize().unwrap_or(Vec3::ZERO);
                        let arm = base_arm + chord * (0.16 * state.separation);
                        radius = radius.max(arm.length());
                        let point_vel = lin + ang.cross(arm) - wind;

                        let cluster = cluster_id[index];
                        let panel_span = if cluster != usize::MAX { cluster_span[cluster] } else { 1.0 };
                        let panel_aspect = if cluster != usize::MAX { cluster_aspect[cluster] } else { 1.0 };
                        let coord = if cluster != usize::MAX && cluster_span_x[cluster] { s.off.x } else { s.off.z };
                        let mid = if cluster != usize::MAX { cluster_mid[cluster] } else { 0.0 };
                        let u = (2.0 * (coord - mid) / panel_span).clamp(-1.0, 1.0);
                        let elliptic = if cluster != usize::MAX && cluster_elliptic[cluster] > 0.0 {
                            (1.0 - u * u).sqrt().max(0.20)
                                * cluster_area[cluster] / cluster_elliptic[cluster]
                        } else { 1.0 };

                        let wp = pos + arm;
                        let max_scan = ((panel_span.ceil() as i32) * 2).clamp(2, 16);
                        let mut ground_height = f32::INFINITY;
                        for down in 1..=max_scan {
                            if sections.solid_at(wp.x.floor() as i32, wp.y.floor() as i32 - down, wp.z.floor() as i32) {
                                ground_height = down as f32;
                                break;
                            }
                        }
                        let ground_ratio = (ground_height / panel_span).clamp(0.0, 1.0);
                        let induced_scale = 0.45 + 0.55 * ground_ratio * ground_ratio;
                        let cluster_chord = if cluster != usize::MAX {
                            if global_span_x {
                                (cluster_min[cluster].z + cluster_max[cluster].z) * 0.5
                            } else {
                                (cluster_min[cluster].x + cluster_max[cluster].x) * 0.5
                            }
                        } else { main_chord };
                        // Split left/right wing halves share their chord station and remain main wings.
                        // A smaller region well fore/aft becomes a stabilizer with a little downforce.
                        let along = normal.dot(spin_axis);
                        let incidence = match rotor {
                            None => if (cluster_chord - main_chord).abs() > 1.5 { -0.055 } else { 0.06 },
                            Some(_) if spin <= 1.0 => 0.06,
                            Some(_) if along.abs() > 0.97 => {
                                let blade_radius = (arm - spin_axis * arm.dot(spin_axis)).length().max(0.25);
                                (PROP_PITCH / blade_radius).atan() * along.signum() * hand
                            }
                            Some(_) => 0.0,
                        };
                        let force = Self::aero_panel_force(
                            point_vel, normal, s.area * elliptic, s.cd, s.cl,
                            density_at(pos.y + arm.y), panel_aspect, true,
                            Some(state.alpha), state.separation, induced_scale, incidence,
                        );
                        net_force += force;
                        if rotor.is_some() { spin_torque += arm.cross(force).dot(spin_axis); }
                        else { net_torque += arm.cross(force); }
                    }
                    net_torque += spin_axis * spin_torque;
                }
            }

            // Balloon blocks are a bulky envelope, not a flat wing. This damps vertical bobbing and
            // sideways drift without inventing a fake stabilizing force.
            let mut balloon_area = 0.0f32;
            let mut balloon_volume = 0.0f32;
            let mut balloon_cd_area = 0.0f32;
            let mut balloon_center = Vec3::ZERO;
            for s in &k.aero {
                if s.buoy <= 0.0 || s.area <= 0.0 { continue; }
                balloon_area += s.area;
                balloon_volume += s.buoy * s.area;
                balloon_cd_area += s.cd.max(0.15) * s.area;
                balloon_center += s.off * s.area;
            }
            if balloon_area > 0.0 {
                let arm = qrot * (balloon_center / balloon_area);
                radius = radius.max(arm.length());
                let envelope_vel = lin + ang.cross(arm) - wind;
                let speed = envelope_vel.length();
                if speed > 0.05 {
                    let cd = (balloon_cd_area / balloon_area).clamp(0.15, 1.5);
                    let cross_section = balloon_area.max(balloon_volume.powf(2.0 / 3.0));
                    let drag = -envelope_vel / speed
                        * (0.5 * density_at(pos.y + arm.y) * speed * speed * cross_section * cd);
                    net_force += drag;
                    if k.aero_mode != AeroMode::Low { net_torque += arm.cross(drag); }
                }
            }

            // With no balloon the aerodynamic wrench must be passive. Lift may redirect motion and drag
            // may remove it, but numerical aggregation is never allowed to manufacture kinetic energy.
            let aero_power = net_force.dot(lin - wind) + net_torque.dot(ang);
            if aero_power > 1.0e-4 {
                let denom = (lin - wind).length_squared() + ang.length_squared();
                if denom > 1.0e-6 {
                    let correction = aero_power / denom;
                    net_force -= (lin - wind) * correction;
                    net_torque -= ang * correction;
                }
            }

            let force_cap = cap_mass * G * 12.0;
            let torque_cap = cap_mass * G * radius * 8.0;
            let aero_scale = (force_cap / net_force.length().max(force_cap))
                .min(torque_cap / net_torque.length().max(torque_cap));
            net_force *= aero_scale;
            net_torque *= aero_scale;

            let mut buoy_force = 0.0f32;
            let mut buoy_center = Vec3::ZERO;
            for s in &k.aero {
                let capacity = s.buoy * s.area;
                if capacity <= 0.0 { continue; }
                let arm = qrot * s.off;
                let force = G * capacity * (density_at(pos.y + arm.y) / RHO0);
                buoy_force += force;
                buoy_center += arm * force;
            }
            if buoy_force > 0.0 {
                let force = Vec3::Y * buoy_force;
                let arm = buoy_center / buoy_force;
                net_force += force;
                if k.aero_mode != AeroMode::Low { net_torque += arm.cross(force); }
            }

            // One scale preserves the force/torque relationship. Clamping them separately can turn a
            // zero-work lift force into a positive-work motor.
            let final_scale = (force_cap / net_force.length().max(force_cap))
                .min(torque_cap / net_torque.length().max(torque_cap));
            net_force *= final_scale;
            net_torque *= final_scale;
            if net_force.is_finite() { b.add_force(net_force, true); }
            if net_torque.is_finite() { b.add_torque(net_torque, true); }
        }
    }

    // real airfoil aerodynamics, per tagged block (flight-sim grade), plus a hot-air balloon.
    //   WING (cl>0): each block is a little wing. it feels the air at ITS OWN point (body vel + spin×arm),
    //     derives an angle of attack from its facing, a lift coefficient that STALLS past ~45° (so lift is
    //     bounded — no runaway), lift ⟂ to the flow + quadratic drag, applied AT the block → the craft
    //     pitches/rolls by HOW you place the wings. lift magnitude scales with the BODY's airspeed² (not the
    //     per-point speed) so a spin can't pump it. zero airspeed → zero lift (a parked wool build just sits).
    //   BALLOON (buoy>0): hot-air style. steady up-force = G·capacity·airDensity(y), NOT capped → it RISES,
    //     but the air thins with altitude so it slows and STOPS at the density-altitude where lift == weight.
    //   STABILITY (the part that was missing): net aero force/torque are clamped each step so they can't
    //     out-muscle the body's momentum (kinetic clamp + hard cap), plus self-right + spin-kill, plus the
    //     velocity clamp in step(). it can glide and tilt, but it can't launch to space or spin out.
    #[allow(dead_code)]
    fn apply_aero_old(&mut self, dt: f32) {
        if dt <= 0.0 { return; }
        const G:          f32 = 28.0;  // = world gravity magnitude
        const LIFT_K:     f32 = 0.8;   // wing lift per (area·Cl·bodySpeed²)
        const DRAG_K:     f32 = 0.15;  // air resistance per (area·cd·pointSpeed²)
        const BIAS:       f32 = 0.12;  // ~7° built-in wing incidence → some lift in level flight
        const VDAMP:      f32 = 2.0;   // vertical velocity damping → settles the bob / eases the balloon
        const RIGHT:      f32 = 2.0;   // GENTLE self-right — lets the wing pitch into a glide, just stops tumbling
        const SPIN_KILL:  f32 = 7.0;   // angular damping → the airfoil torque can't wind into a spin-out
        const SEA_Y:      f32 = 64.0;  // altitude where air is "full"
        const AIR_SPAN:   f32 = 320.0; // blocks over which air density thins to MIN_AIR
        const MIN_AIR:    f32 = 0.10;  // thin but never zero

        let wind    = self.wind;
        let kontras = &self.kontras;       // disjoint field borrows — immutable kontras + mutable bodies
        let bodies  = &mut self.bodies;

        for k in kontras.values() {
            // aero ONLY. buoyancy used to live here too as a whole-body up-shove of 1.0 displaced
            // mass per submerged block — blind to the block's actual mass, to buoyancy_volume and to
            // water_density. apply_water already does it properly per block, so every wet build was
            // getting buoyancy TWICE and the dumb copy won: grass (mass 0.506, displaced 1.0) got
            // about two gravities upward and shot out of the water like a cannon.
            if k.aero.is_empty() { continue; }

            let b = match bodies.get_mut(k.body) { Some(b) => b, None => continue };
            if b.is_sleeping() { continue; }

            let mass = b.mass().max(0.01);
            let r = b.rotation();
            let q = Quat::from_xyzw(r.x, r.y, r.z, r.w);
            let t = b.translation();
            let lv = b.linvel(); let lin = Vec3::new(lv.x, lv.y, lv.z);
            let av = b.angvel(); let ang = Vec3::new(av.x, av.y, av.z);
            let pos = Vec3::new(t.x, t.y, t.z);

            let rel_body   = lin - wind;          // the craft's airspeed (drives lift magnitude)
            let body_speed = rel_body.length();
            let alt = (1.0 - (pos.y - SEA_Y).max(0.0) / AIR_SPAN).clamp(MIN_AIR, 1.0); // air density up high

            let mut r_max = 1.0f32;

            // ── WING: the WHOLE wool region as ONE airfoil (per-wing, not per-block) ──
            // aggregate every aero block into a single wing: total area, area-weighted CENTRE (= centre of
            // lift), and averaged facing. the lift is one force at that centre — and add_force_at_point
            // turns its offset from the body's CENTER OF MASS (built from the per-block masses) into
            // pitch/roll → the craft trims and GLIDES (descends drifting forward) instead of bobbing.
            let mut lift_area = 0.0f32; // Σ cl·area
            let mut drag_area = 0.0f32; // Σ cd·area
            let mut wing_area = 0.0f32; // Σ area of lifting blocks
            let mut a_sum     = 0.0f32; // Σ area of all aero blocks (for the centroid)
            let mut cent      = Vec3::ZERO;
            let mut n_sum     = Vec3::ZERO;
            for s in &k.aero {
                lift_area += s.cl * s.area;
                drag_area += s.cd * s.area;
                a_sum     += s.area;
                cent      += s.off * s.area;
                if s.cl > 0.0 { wing_area += s.area; n_sum += s.normal * s.area; }
            }
            if a_sum > 0.0 && (lift_area > 0.0 || drag_area > 0.0) {
                let center_local = cent / a_sum;
                r_max = r_max.max(center_local.length());
                let arm  = q * center_local;
                let v_pt = lin + ang.cross(arm) - wind;     // airflow at the wing centre (includes spin)
                let sp   = v_pt.length();
                if sp > 1.0e-2 {
                    let v_dir = v_pt / sp;
                    let mut lift = Vec3::ZERO;
                    // lift: airfoil ⟂ to the flow, Cl stalls past ~45°; magnitude from BODY airspeed²
                    if wing_area > 0.0 && body_speed > 0.3 {
                        let n = (q * n_sum).try_normalize().unwrap_or(q * Vec3::Y);
                        let sin_a = (-v_dir.dot(n)).clamp(-1.0, 1.0);
                        let cl_eff = (lift_area / wing_area) * (2.0 * (sin_a.asin() + BIAS)).sin();
                        let perp = n - n.dot(v_dir) * v_dir;
                        if perp.length_squared() > 1.0e-5 {
                            lift = perp.normalize() * (LIFT_K * wing_area * cl_eff * body_speed * body_speed * alt);
                        }
                    }
                    // cap LIFT *below* weight → wings can NEVER hold altitude or fling the craft up: gravity
                    // always wins, so it GLIDES DOWN (descends drifting forward) — no bobbing, no launch, no
                    // overspeed. forward drift comes from the lift's horizontal part.
                    let lift_cap = 0.85 * mass * G;
                    if lift.length() > lift_cap { lift = lift.normalize() * lift_cap; }
                    // drag opposes the flow but is clamped so it can only BLEED speed, never reverse it
                    let mut drag = v_pt * (-DRAG_K * drag_area * sp);
                    let drag_cap = 0.5 * mass * sp / dt.max(1.0e-4);
                    if drag.length() > drag_cap { drag = drag.normalize() * drag_cap; }
                    let fw = lift + drag;
                    if fw.is_finite() && fw.length() > 1.0e-4 { b.add_force_at_point(fw, pos + arm, true); }
                }
            }

            // ── BALLOON: hot-air buoyancy. up = G·capacity·airDensity, NOT capped → rises, but density
            // falls with altitude → STOPS where lift == weight. at the balloon centroid → self-rights. ──
            let mut buoy_cap = 0.0f32;
            let mut buoy_cent = Vec3::ZERO;
            for s in &k.aero { let bw = s.buoy * s.area; buoy_cap += bw; buoy_cent += s.off * bw; }
            if buoy_cap > 0.0 {
                let bc = buoy_cent / buoy_cap;
                r_max = r_max.max(bc.length());
                b.add_force_at_point(Vec3::new(0.0, G * buoy_cap * alt, 0.0), pos + q * bc, true);
            }

            // ── vertical velocity damping (settles the bob, eases the balloon onto its altitude) ──
            let vy_damp = -VDAMP * mass * rel_body.y;
            if vy_damp.is_finite() && vy_damp.abs() > 1.0e-4 { b.add_force(Vec3::new(0.0, vy_damp, 0.0), true); }

            // ── self-right + spin-kill: GENTLE — strong enough to keep it from tumbling, weak enough that
            // the wing's CoM torque can still pitch it into a glide. ──
            let mut torque = (q * Vec3::Y).cross(Vec3::Y) * (RIGHT * mass) - ang * (SPIN_KILL * mass);
            let max_t = G * mass * r_max * 0.6;
            if torque.length() > max_t && max_t > 0.0 { torque = torque.normalize() * max_t; }
            if torque.is_finite() && torque.length() > 1.0e-4 { b.add_torque(torque, true); }
        }
    }

    pub fn spawn_kontraktion_with_id(
        &mut self, id: i64, block_positions: &[[i32; 3]], masses: &[f32], light_count: usize,
        world_pos: [f32; 3],
    ) {
        if block_positions.is_empty() { return; }

        let total_mass: f32 = masses.iter().sum::<f32>().max(1.0);
        // cx/cy/cz used for shape-building only (centroid in blockCoord space)
        let cx = block_positions.iter().zip(masses.iter())
            .map(|(p, &m)| (p[0] as f32 + 0.5) * m).sum::<f32>() / total_mass;
        let cy = block_positions.iter().zip(masses.iter())
            .map(|(p, &m)| (p[1] as f32 + 0.5) * m).sum::<f32>() / total_mass;
        let cz = block_positions.iter().zip(masses.iter())
            .map(|(p, &m)| (p[2] as f32 + 0.5) * m).sum::<f32>() / total_mass;

        // spawn at Java-computed world centroid so shapes are at correct world positions from tick 1
        // without this the body sits at Rust's local cx (near 0,0,0) for 1-2 ticks → glitches
        let rb = RigidBodyBuilder::dynamic()
            // a spinning rotor or wheel resists being tipped, like a real one. rapier solves it implicitly
            .gyroscopic_forces_enabled(false)
            .translation(Vec3::new(world_pos[0], world_pos[1], world_pos[2]))
            .ccd_enabled(true)
            .linear_damping(0.08)
            .angular_damping(0.14)
            .sleeping(false)
            .build();
        let body_handle = self.bodies.insert(rb);
        if let Some(b) = self.bodies.get_mut(body_handle) {
            let act = b.activation_mut();
            act.normalized_linear_threshold = 0.02;
            act.angular_threshold = 0.02;
            act.time_until_sleep = 1.0;
        }

        let offsets: Vec<[f32; 3]> = block_positions.iter()
            .map(|p| [p[0] as f32 + 0.5 - cx, p[1] as f32 + 0.5 - cy, p[2] as f32 + 0.5 - cz])
            .collect();

        // one cuboid collider per block at its fixed local offset — frame never recomputed.
        // per-block masses sum to total_mass; Rapier derives body mass + COM from the colliders
        let mut col_handles = Vec::with_capacity(offsets.len());
        for (i, off) in offsets.iter().enumerate() {
            let m = masses.get(i).copied().unwrap_or(total_mass / offsets.len() as f32).max(0.01);
            col_handles.push(self.colliders.insert_with_parent(block_collider(*off, m), body_handle, &mut self.bodies));
        }

        // MUST be jround(offset) basis like everything else (add/remove/split/aero occlusion all key
        // that way). raw world positions here meant: any block placed later landed thousands of cells
        // "away" in the set → connectivity called it a far component → collider dropped + phantom split.
        // that was the "freshly spawned kontra has no collision in some spots" one. fuck.
        let block_set: HashSet<[i32; 3]> = offsets.iter()
            .map(|o| [jround(o[0]), jround(o[1]), jround(o[2])])
            .collect();

        self.kontras.insert(id, KontraKtion {
            id,
            body: body_handle,
            colliders: col_handles,
            block_offsets: offsets,
            block_set,
            break_mode: BreakMode::Bounce,
            pending_fragments: Vec::new(),
            obb: Obb::unit_block(Vec3::new(cx, cy, cz)),
            last_impact_force: 0.0,
            light_block_count: light_count,
            aero: Vec::new(),
            aero_state: Vec::new(),
            aero_mode: AeroMode::Correct,
            aligned: false,
            buoy_volumes: HashMap::new(),
            wheel_axle: None,
            held_force: Vec3::ZERO,
            held_torque: Vec3::ZERO,
            pushed: false,
            buoy_scale: 1.0,
            submerged: 0.0,
        });
    }

    pub fn spawn_kontraktion_from_offsets(
        &mut self, id: i64, offsets: Vec<[f32; 3]>, masses: &[f32], light_count: usize,
        world_pos: [f32; 3],
    ) {
        if offsets.is_empty() { return; }

        let total_mass: f32 = masses.iter().sum::<f32>().max(1.0);
        let rb = RigidBodyBuilder::dynamic()
            // a spinning rotor or wheel resists being tipped, like a real one. rapier solves it implicitly
            .gyroscopic_forces_enabled(false)
            .translation(Vec3::new(world_pos[0], world_pos[1], world_pos[2]))
            .ccd_enabled(true)
            .linear_damping(0.08)
            .angular_damping(0.14)
            .sleeping(false)
            .build();
        let body_handle = self.bodies.insert(rb);
        if let Some(b) = self.bodies.get_mut(body_handle) {
            let act = b.activation_mut();
            act.normalized_linear_threshold = 0.02;
            act.angular_threshold = 0.02;
            act.time_until_sleep = 1.0;
        }

        let mut col_handles = Vec::with_capacity(offsets.len());
        for (i, off) in offsets.iter().enumerate() {
            let m = masses.get(i).copied().unwrap_or(total_mass / offsets.len() as f32).max(0.01);
            col_handles.push(self.colliders.insert_with_parent(block_collider(*off, m), body_handle, &mut self.bodies));
        }

        let block_set: HashSet<[i32; 3]> = offsets.iter()
            .map(|o| [jround(o[0]), jround(o[1]), jround(o[2])])
            .collect();

        self.kontras.insert(id, KontraKtion {
            id,
            body: body_handle,
            colliders: col_handles,
            block_offsets: offsets,
            block_set,
            break_mode: BreakMode::Bounce,
            pending_fragments: Vec::new(),
            obb: Obb::unit_block(Vec3::ZERO),
            last_impact_force: 0.0,
            light_block_count: light_count,
            aero: Vec::new(),
            aero_state: Vec::new(),
            aero_mode: AeroMode::Correct,
            aligned: false,
            buoy_volumes: HashMap::new(),
            wheel_axle: None,
            held_force: Vec3::ZERO,
            held_torque: Vec3::ZERO,
            pushed: false,
            buoy_scale: 1.0,
            submerged: 0.0,
        });
    }

    // held pushes, then the Java pusher. runs after water + aero so the pusher sees what khysics
    // already did to the body this step (state[35..38]) and can cancel or add to it
    fn apply_pushes(&mut self, dt: f32) {
        let pusher = PUSHER.load(std::sync::atomic::Ordering::Acquire);
        let gravity = self.gravity;
        for k in self.kontras.values() {
            if k.held_force == Vec3::ZERO && k.held_torque == Vec3::ZERO && !(k.pushed && pusher != 0) {
                continue;
            }
            let b = match self.bodies.get_mut(k.body) { Some(b) => b, None => continue };
            if !b.is_dynamic() { continue; }
            if k.held_force != Vec3::ZERO { b.add_force(k.held_force, true); }
            if k.held_torque != Vec3::ZERO { b.add_torque(k.held_torque, true); }
            if !(k.pushed && pusher != 0) { continue; }

            let state = body_state(b, k, gravity, dt);
            let mut out = [0f32; 6];
            // SAFETY: PUSHER only ever holds a PushFn stored by koper_khysics_set_pusher. the Java
            // side catches every Throwable, an exception never unwinds through here
            let f: PushFn = unsafe { std::mem::transmute::<usize, PushFn>(pusher) };
            f(k.id, state.as_ptr(), out.as_mut_ptr());
            let force = Vec3::new(out[0], out[1], out[2]);
            let torque = Vec3::new(out[3], out[4], out[5]);
            // a NaN from Java would poison the body for good — drop it, the rescue pass is for solver bugs
            if force.is_finite() && force != Vec3::ZERO { b.add_force(force, true); }
            if torque.is_finite() && torque != Vec3::ZERO { b.add_torque(torque, true); }
        }
    }

    pub fn set_block_mass(&mut self, id: i64, offset: [f32; 3], mass: f32) {
        if !mass.is_finite() { return; }
        let Some(k) = self.kontras.get(&id) else { return };
        let key = [jround(offset[0]), jround(offset[1]), jround(offset[2])];
        let Some(idx) = k.block_offsets.iter().position(|o|
            [jround(o[0]), jround(o[1]), jround(o[2])] == key) else { return };
        let (handle, body) = (k.colliders[idx], k.body);
        if let Some(c) = self.colliders.get_mut(handle) { c.set_mass(mass.max(0.01)); }
        if let Some(b) = self.bodies.get_mut(body) { b.wake_up(true); }
        self.clear_joint_warmstarts_for_body(id);
    }

    pub fn get_velocity(&self, id: i64) -> Option<[f32; 3]> {
        let b = self.bodies.get(self.kontras.get(&id)?.body)?;
        let v = b.linvel();
        Some([v.x, v.y, v.z])
    }

    pub fn get_transform(&self, id: i64) -> Option<([f32; 3], [f32; 4])> {
        let b = self.bodies.get(self.kontras.get(&id)?.body)?;
        let t = b.translation();
        let r = b.rotation();
        Some(([t.x, t.y, t.z], [r.x, r.y, r.z, r.w]))
    }

    pub fn apply_force(&mut self, id: i64, f: [f32; 3]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.add_force(Vec3::new(f[0], f[1], f[2]), true);
            }
        }
    }

    pub fn apply_torque(&mut self, id: i64, t: [f32; 3]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.add_torque(Vec3::new(t[0], t[1], t[2]), true);
            }
        }
    }

    pub fn apply_impulse(&mut self, id: i64, imp: [f32; 3]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.apply_impulse(Vec3::new(imp[0], imp[1], imp[2]), true);
            }
        }
    }

    // whack one part of a jointed machine and the joint has to eat the whole difference, so the
    // thing visibly tears itself apart mid-flight. give every jointed body the SAME velocity change
    // instead — the hit body flies exactly as hard as before, it just takes its friends with it.
    pub fn apply_impulse_group(&mut self, id: i64, imp: [f32; 3]) {
        let hit_mass = match self.kontras.get(&id).and_then(|k| self.bodies.get(k.body)) {
            Some(b) => b.mass().max(0.01),
            None => return,
        };
        let dv = Vec3::new(imp[0], imp[1], imp[2]) / hit_mass;

        let mut group = vec![id];
        let mut seen: HashSet<i64> = HashSet::new();
        seen.insert(id);
        let mut cursor = 0;
        while cursor < group.len() {
            let current = group[cursor];
            cursor += 1;
            for rec in self.joints.values() {
                // b == 0 is the world anchor, there is nothing on the other end to carry
                let next = if rec.a == current && rec.b != 0 { rec.b }
                    else if rec.b == current && rec.a != 0 { rec.a }
                    else { continue };
                if seen.insert(next) { group.push(next); }
            }
        }

        for member in group {
            if let Some(k) = self.kontras.get(&member) {
                if let Some(b) = self.bodies.get_mut(k.body) {
                    let m = b.mass().max(0.01);
                    b.apply_impulse(dv * m, true);
                }
            }
        }
    }

    pub fn apply_impulse_at_point(&mut self, id: i64, imp: [f32; 3], point: [f32; 3]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                let impulse = Vec3::from(imp);
                let world_point = Vec3::from(point);
                if impulse.is_finite() && world_point.is_finite() {
                    b.apply_impulse_at_point(impulse, world_point, true);
                }
            }
        }
    }

    pub fn set_block_materials(&mut self, id: i64, materials: &[[f32; 15]]) {
        let Some(k) = self.kontras.get_mut(&id) else { return };
        if materials.len() != k.colliders.len() { return; }

        // Java's LinkedHashMap keeps insertion order while collider removal uses swap_remove.
        // Put both Rust arrays back into the wire order before applying per-block shapes.
        let old_offsets = k.block_offsets.clone();
        let old_colliders = k.colliders.clone();
        let mut reordered_offsets = Vec::with_capacity(materials.len());
        let mut reordered_colliders = Vec::with_capacity(materials.len());
        let mut used = vec![false; old_offsets.len()];
        for material in materials {
            let key = [jround(material[12]), jround(material[13]), jround(material[14])];
            let Some(index) = old_offsets.iter().enumerate().position(|(index, offset)|
                !used[index] && [jround(offset[0]), jround(offset[1]), jround(offset[2])] == key)
            else { return };
            used[index] = true;
            reordered_offsets.push(old_offsets[index]);
            reordered_colliders.push(old_colliders[index]);
        }
        k.block_offsets = reordered_offsets;
        k.colliders = reordered_colliders;

        let mut axle: Option<(Vec3, f32)> = None;
        for (handle, material) in k.colliders.iter().zip(materials) {
            if let Some(collider) = self.colliders.get_mut(*handle) {
                collider.set_friction(material[0].max(0.0));
                collider.set_restitution(material[1].clamp(0.0, 1.0));
                collider.set_shape(SharedShape::cuboid(0.5, 0.5, 0.5));
                collider.set_position_wrt_parent(Pose::from_translation(Vec3::new(
                    material[9], material[10], material[11])));
                // liquid cargo keeps a slot so wire indices stay aligned, but cannot make contact
                collider.set_enabled(material[6] >= 0.0);
                let resolution = material[6].round() as usize;
                if resolution == 2 || resolution == 4 {
                    let cells = material[7].to_bits() as u64 | ((material[8].to_bits() as u64) << 32);
                    let size = 1.0 / resolution as f32;
                    let half = size * 0.5;
                    let mut parts = Vec::new();
                    for y in 0..resolution {
                        for z in 0..resolution {
                            for x in 0..resolution {
                                let bit = x + resolution * (z + resolution * y);
                                if cells & (1u64 << bit) == 0 { continue; }
                                let centre = Vec3::new(
                                    -0.5 + (x as f32 + 0.5) * size,
                                    -0.5 + (y as f32 + 0.5) * size,
                                    -0.5 + (z as f32 + 0.5) * size);
                                parts.push((Pose::from_translation(centre),
                                    SharedShape::cuboid(half, half, half)));
                            }
                        }
                    }
                    if !parts.is_empty() { collider.set_shape(SharedShape::compound(parts)); }
                } else if material[2] > 0.5 {
                    // Rubber sits a hair proud of a one-block chassis so the chassis does not scrape.
                    collider.set_shape(SharedShape::cylinder(0.125, 0.53));
                    let axis = Vec3::new(material[3], material[4], material[5])
                        .try_normalize().unwrap_or(Vec3::Y);
                    axle = Some((axis, material[0]));
                    let dot = Vec3::Y.dot(axis).clamp(-1.0, 1.0);
                    let cross = Vec3::Y.cross(axis);
                    let rotation = if cross.length_squared() < 1.0e-8 {
                        if dot < 0.0 { Vec3::X * std::f32::consts::PI } else { Vec3::ZERO }
                    } else {
                        cross.normalize() * dot.acos()
                    };
                    collider.set_rotation_wrt_parent(rotation);
                }
            }
        }
        // one tyre bolted into a twenty block hull must not drag the whole hull sideways as if the hull
        // were the tyre. weight the bite by how much of this body is actually wheel
        let wheels = materials.iter().filter(|m| m[2] > 0.5).count();
        let share = if materials.is_empty() { 0.0 } else { wheels as f32 / materials.len() as f32 };
        k.wheel_axle = axle.map(|(ax, friction)|
            (ax, (friction * 0.25).clamp(0.0, 0.8) * share));
    }

    // Sideways grip. Rapier friction is one scalar in every direction, so a tyre is just as happy
    // sliding along its own axle as rolling — steered wheels turn, the car ploughs straight ahead.
    // Kill the part of a touching wheel's velocity that points along its axle and it bites instead.
    fn apply_wheel_grip(&mut self) {
        let narrow = &self.narrow_phase;
        let kontras = &self.kontras;
        let bodies = &mut self.bodies;
        for k in kontras.values() {
            let Some((axle_local, grip)) = k.wheel_axle else { continue };
            if grip <= 0.0 { continue; }
            // no grip in mid air, otherwise a jumping car snaps sideways onto nothing
            let touching = k.colliders.iter().any(|handle|
                narrow.contact_pairs_with(*handle).any(|pair| pair.has_any_active_contact()));
            if !touching { continue; }
            // what the ground pushed back with last step: the load this tyre carries
            let mut load = 0.0f32;
            for handle in &k.colliders {
                for pair in narrow.contact_pairs_with(*handle) {
                    for manifold in &pair.manifolds {
                        for point in &manifold.points { load += point.data.impulse.abs(); }
                    }
                }
            }
            let Some(b) = bodies.get_mut(k.body) else { continue };
            let r = b.rotation();
            let Some(axle) = (Quat::from_xyzw(r.x, r.y, r.z, r.w) * axle_local).try_normalize()
                else { continue };
            let lv = b.linvel();
            let v = Vec3::new(lv.x, lv.y, lv.z);
            let sideways = v.dot(axle);
            if sideways.abs() >= 1.0e-4 { b.set_linvel(v - axle * (sideways * grip), true); }
            // rolling resistance: a tyre pressed on the ground loses a little of its forward motion,
            // in proportion to the load on it. without it a free wheel was a perfect bearing and a
            // pushed car rolled on for good. only on the ground, so a wheel spun in the air keeps
            // spinning, and never more than stops it
            let lv = b.linvel();
            let rolling = Vec3::new(lv.x, 0.0, lv.z) - Vec3::new(axle.x, 0.0, axle.z) * Vec3::new(lv.x, 0.0, lv.z).dot(axle);
            let speed = rolling.length();
            if speed > 1.0e-3 && load > 0.0 {
                let impulse = (TYRE_ROLLING_RESISTANCE * load).min(speed * b.mass());
                b.apply_impulse(-rolling / speed * impulse, true);
            }
        }
    }

    pub fn set_block_shapes(&mut self, id: i64, shapes: &[[f32; 11]]) {
        let Some(k) = self.kontras.get(&id) else { return };
        let mut parts: Vec<Vec<(Pose, SharedShape)>> =
            (0..k.colliders.len()).map(|_| Vec::new()).collect();
        for shape in shapes {
            let index = shape[0].round() as isize;
            if index < 0 || index as usize >= parts.len() { continue; }
            let half = Vec3::new(shape[4].abs(), shape[5].abs(), shape[6].abs());
            if half.min_element() <= 1.0e-4 { continue; }
            let mut rotation = Quat::from_xyzw(shape[7], shape[8], shape[9], shape[10]);
            if !rotation.is_finite() || rotation.length_squared() < 1.0e-8 {
                rotation = Quat::IDENTITY;
            } else {
                rotation = rotation.normalize();
            }
            parts[index as usize].push((
                Pose::from_parts(Vec3::new(shape[1], shape[2], shape[3]), rotation),
                SharedShape::cuboid(half.x, half.y, half.z),
            ));
        }
        for (index, compound) in parts.into_iter().enumerate() {
            if compound.is_empty() { continue; }
            if let Some(collider) = self.colliders.get_mut(k.colliders[index]) {
                collider.set_shape(SharedShape::compound(compound));
            }
        }
    }

    // direct velocity write (blocks/s) — flight stick style control. forces get reset every
    // step and fight drag/aero, this just says where the body is going. still V_MAX-clamped.
    pub fn set_velocity(&mut self, id: i64, v: [f32; 3]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.set_linvel(Vec3::new(v[0], v[1], v[2]), true);
            }
        }
    }

    pub fn set_break_mode(&mut self, id: i64, mode: BreakMode) {
        if let Some(k) = self.kontras.get_mut(&id) { k.break_mode = mode; }
    }

    pub fn set_transform(&mut self, id: i64, pos: [f32; 3], rot: [f32; 4]) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                let q = Quat::from_xyzw(rot[0], rot[1], rot[2], rot[3]).normalize();
                b.set_position(Pose::from_parts(Vec3::new(pos[0], pos[1], pos[2]), q), true);
                b.set_linvel(Vec3::ZERO, true);
                b.set_angvel(Vec3::ZERO, true);
            }
        }
    }

    pub fn set_parked(&mut self, id: i64, parked: bool) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(body) = self.bodies.get_mut(k.body) {
                body.set_linvel(Vec3::ZERO, false);
                body.set_angvel(Vec3::ZERO, false);
                body.reset_forces(false);
                body.reset_torques(false);
                body.set_body_type(
                    if parked { RigidBodyType::Fixed } else { RigidBodyType::Dynamic },
                    true);
                if !parked {
                    body.wake_up(true);
                }
            }
        }
    }

    pub fn set_gravity(&mut self, gx: f32, gy: f32, gz: f32) {
        self.gravity = Vec3::new(gx, gy, gz);
    }

    pub fn set_sleep_allowed(&mut self, id: i64, allowed: bool) {
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                if allowed {
                    // unloaded chunk — allow sleep after 4s still
                    let act = b.activation_mut();
                    act.normalized_linear_threshold = 0.3;
                    act.angular_threshold = 0.15;
                    act.time_until_sleep = 4.0;
                } else {
                    // Loaded mechanisms still need to settle. A changed motor/contact wakes them on demand.
                    let act = b.activation_mut();
                    act.normalized_linear_threshold = 0.02;
                    act.angular_threshold = 0.02;
                    act.time_until_sleep = 1.0;
                }
            }
        }
    }

    pub fn destroy_contraption(&mut self, id: i64) {
        if let Some(k) = self.kontras.remove(&id) {
            let joint_ids: Vec<i64> = self.joints.iter()
                .filter_map(|(joint_id, rec)| (rec.a == id || rec.b == id).then_some(*joint_id))
                .collect();
            for joint_id in joint_ids {
                self.destroy_joint(joint_id);
            }
            for col in &k.colliders {
                self.colliders.remove(*col, &mut self.islands, &mut self.bodies, false);
            }
            self.bodies.remove(k.body, &mut self.islands, &mut self.colliders,
                               &mut self.impulse_joints, &mut self.multibody_joints, true);
        }
    }

    pub fn set_damping(&mut self, id: i64, linear: f32, angular: f32) {
        if !linear.is_finite() || !angular.is_finite() || linear < 0.0 || angular < 0.0 {
            return;
        }
        if let Some(k) = self.kontras.get(&id) {
            if let Some(b) = self.bodies.get_mut(k.body) {
                b.set_linear_damping(linear);
                b.set_angular_damping(angular);
                b.wake_up(true);
            }
        }
    }

    pub fn add_block_at_local_offset(&mut self, kontra_id: i64, local_off: [f32; 3], mass: f32) -> bool {
        let body_handle = match self.kontras.get(&kontra_id) {
            Some(k) => k.body,
            None => return false,
        };
        // Java resolves the mass of the exact state being added. A running body average made tiny
        // technical parts weigh as much as their cargo and prevented unloaded springs rebounding.
        let m = if mass.is_finite() { mass.max(0.01) } else { 1.0 };
        // insert ONE collider at its fixed local offset — every other block's collider (and its
        // contacts) stays put, so the body doesn't re-solve and jolt
        let nh = self.colliders.insert_with_parent(block_collider(local_off, m), body_handle, &mut self.bodies);
        if let Some(k) = self.kontras.get_mut(&kontra_id) {
            k.block_set.insert([jround(local_off[0]), jround(local_off[1]), jround(local_off[2])]);
            k.block_offsets.push(local_off);
            k.colliders.push(nh);
        }
        // Changing a body's mass is a physical event. A settled suspension head may already be
        // asleep; without this, newly attached cargo can hang at full extension forever.
        if let Some(body) = self.bodies.get_mut(body_handle) {
            body.wake_up(true);
        }
        self.clear_joint_warmstarts_for_body(kontra_id);
        true
    }

    pub fn remove_block_at_local_offset(&mut self, kontra_id: i64, local: [i32; 3]) -> bool {
        // jround matches Java's Math.round(float) — they agree on half-integer ties
        let idx = match self.kontras.get(&kontra_id) {
            Some(k) => k.block_offsets.iter().position(|o|
                jround(o[0]) == local[0] && jround(o[1]) == local[1] && jround(o[2]) == local[2]),
            None => return false,
        };
        let idx = match idx { Some(i) => i, None => return false };

        // pull just this block's collider + its parallel offset; the rest keep their contacts → no twitch
        let removed = {
            let k = self.kontras.get_mut(&kontra_id).unwrap();
            k.block_offsets.swap_remove(idx);
            k.block_set.remove(&local);
            k.buoy_volumes.remove(&local);
            k.colliders.swap_remove(idx)
        };
        self.colliders.remove(removed, &mut self.islands, &mut self.bodies, false);
        if let Some(body_handle) = self.kontras.get(&kontra_id).map(|k| k.body) {
            if let Some(body) = self.bodies.get_mut(body_handle) {
                body.wake_up(true);
            }
        }
        self.clear_joint_warmstarts_for_body(kontra_id);

        // empty now → Java calls destroyKontraktion to drop the body; leave the empty entry for it
        if self.kontras.get(&kontra_id).map(|k| k.block_offsets.is_empty()).unwrap_or(true) {
            return true;
        }

        // connectivity: only pieces that ended up ≥ SPLIT_GAP from the main body break off — everything
        // closer stays welded into one kontra (so mining a block doesn't spawn a second contraption)
        let block_set = self.kontras.get(&kontra_id).unwrap().block_set.clone();
        let components = find_connected_components(&block_set);
        let far_split: Vec<Vec<[i32; 3]>> = if components.len() > 1 {
            let main_idx = components.iter().enumerate().max_by_key(|(_, c)| c.len()).map(|(i, _)| i).unwrap();
            components.iter().enumerate()
                .filter(|(i, c)| *i != main_idx && component_gap_ge(&components[main_idx], c, SPLIT_GAP))
                .map(|(_, c)| c.clone())
                .collect()
        } else { Vec::new() };

        if !far_split.is_empty() {
            let split_blocks: HashSet<[i32; 3]> = far_split.iter().flatten().cloned().collect();
            // drop the split blocks' colliders only — no rebuild, no mass juggling (Rapier re-derives mass)
            let mut dropped = Vec::new();
            if let Some(k) = self.kontras.get_mut(&kontra_id) {
                let mut i = 0;
                while i < k.block_offsets.len() {
                    let key = [jround(k.block_offsets[i][0]), jround(k.block_offsets[i][1]), jround(k.block_offsets[i][2])];
                    if split_blocks.contains(&key) {
                        k.block_offsets.swap_remove(i);
                        k.block_set.remove(&key);
                        dropped.push(k.colliders.swap_remove(i));
                    } else {
                        i += 1;
                    }
                }
            }
            for h in dropped { self.colliders.remove(h, &mut self.islands, &mut self.bodies, false); }
            self.pending_splits.push((kontra_id, far_split));
        }
        true
    }

    pub fn raycast(&self, origin: [f32; 3], dir: [f32; 3], max_dist: f32) -> Option<(i64, [f32; 3])> {
        let o = Vec3::from(origin);
        let d = Vec3::from(dir).normalize_or_zero();
        if d == Vec3::ZERO { return None; }

        let mut best_t = max_dist;
        let mut best_id = -1i64;
        for (id, k) in &self.kontras {
            if let Some(t) = k.obb.ray_hit(o, d) {
                if t < best_t { best_t = t; best_id = *id; }
            }
        }
        if best_id < 0 { return None; }
        let hit = o + d * best_t;
        Some((best_id, [hit.x, hit.y, hit.z]))
    }
}

// Java's Math.round(float) rounds half toward +∞; Rust f32::round() rounds half away from zero
// they diverge at exactly ±0.5 — this matches Java's behaviour
#[inline] fn jround(v: f32) -> i32 { (v + 0.5).floor() as i32 }

// fraction in [0,1) that behaves at negative coords (f32::fract keeps the sign — useless here)
#[inline] fn frac01(v: f32) -> f32 { v - v.floor() }

// one block = one parented 0.5-cuboid at its fixed local offset. per-block colliders mean a
// block edit adds/removes a single collider instead of rebuilding the whole compound → no twitch
// see BODY_STATE_LEN for the layout
fn body_state(b: &RigidBody, k: &KontraKtion, gravity: Vec3, dt: f32) -> [f32; BODY_STATE_LEN] {
    let mut s = [0f32; BODY_STATE_LEN];
    let t = b.translation();
    let r = *b.rotation();
    let v = b.linvel();
    let w = b.angvel();
    let com = b.center_of_mass();
    let mp = &b.mass_properties().local_mprops;
    let lc = mp.local_com;
    s[0] = t.x; s[1] = t.y; s[2] = t.z;
    s[3] = r.x; s[4] = r.y; s[5] = r.z; s[6] = r.w;
    s[7] = v.x; s[8] = v.y; s[9] = v.z;
    s[10] = w.x; s[11] = w.y; s[12] = w.z;
    s[13] = com.x; s[14] = com.y; s[15] = com.z;
    s[16] = lc.x; s[17] = lc.y; s[18] = lc.z;
    s[19] = b.mass();
    // I_world = R I_local R^T, about the centre of mass
    let rot = glam::Mat3::from_quat(Quat::from_xyzw(r.x, r.y, r.z, r.w));
    let local = mp.reconstruct_inertia_matrix();
    let world = rot * glam::Mat3::from_cols(local.x_axis, local.y_axis, local.z_axis) * rot.transpose();
    for row in 0..3 {
        for col in 0..3 { s[20 + row * 3 + col] = world.col(col)[row]; }
    }
    let g = gravity * b.gravity_scale();
    s[29] = g.x; s[30] = g.y; s[31] = g.z;
    s[32] = dt;
    s[33] = k.submerged;
    let mut flags = 0u32;
    if b.is_sleeping() { flags |= 1; }
    if k.aligned { flags |= 2; }
    if b.is_fixed() { flags |= 4; }
    s[34] = flags as f32;
    let uf = b.user_force();
    s[35] = uf.x; s[36] = uf.y; s[37] = uf.z;
    s[38] = b.gravity_scale();
    s[39] = k.buoy_scale;
    for x in s.iter_mut() { if !x.is_finite() { *x = 0.0; } }
    s
}

fn block_collider(off: [f32; 3], mass: f32) -> Collider {
    ColliderBuilder::cuboid(0.5, 0.5, 0.5)
        .translation(Vec3::new(off[0], off[1], off[2]))
        .mass(mass).friction(0.8).restitution(0.0)
        .collision_groups(InteractionGroups::new(GROUP_KONTRA, GROUP_TERRAIN | GROUP_KONTRA, InteractionTestMode::And))
        .active_events(ActiveEvents::COLLISION_EVENTS)
        // without this flag the assembly filter is never consulted and parts of one creation keep
        // shoving each other apart
        .active_hooks(ActiveHooks::FILTER_CONTACT_PAIRS)
        .build()
}

// the 24 axis-aligned orientations of a cube — align-assist pulls resting kontras onto the nearest one
fn grid_quats() -> &'static [Quat; 24] {
    static Q: std::sync::OnceLock<[Quat; 24]> = std::sync::OnceLock::new();
    Q.get_or_init(|| {
        let dirs = [Vec3::X, Vec3::NEG_X, Vec3::Y, Vec3::NEG_Y, Vec3::Z, Vec3::NEG_Z];
        let mut out = [Quat::IDENTITY; 24];
        let mut i = 0;
        for x in dirs {
            for y in dirs {
                if x.dot(y).abs() > 0.5 { continue; }
                let z = x.cross(y);
                out[i] = Quat::from_mat3(&glam::Mat3::from_cols(x, y, z)).normalize();
                i += 1;
            }
        }
        out
    })
}

// nearest grid orientation + angle to it (radians). candidate flipped into q's hemisphere so a
// spring torque built from their delta always takes the short way round.
fn nearest_grid_quat(q: Quat) -> (Quat, f32) {
    let mut best = Quat::IDENTITY;
    let mut best_dot = -1.0f32;
    for c in grid_quats() {
        let d = q.dot(*c).abs();
        if d > best_dot { best_dot = d; best = *c; }
    }
    if q.dot(best) < 0.0 { best = -best; }
    (best, 2.0 * best_dot.clamp(-1.0, 1.0).acos())
}

// flood fill to find connected components in a block set (6-connectivity)
fn find_connected_components(blocks: &HashSet<[i32; 3]>) -> Vec<Vec<[i32; 3]>> {
    let mut visited: HashSet<[i32; 3]> = HashSet::new();
    let mut components: Vec<Vec<[i32; 3]>> = Vec::new();

    for &start in blocks {
        if visited.contains(&start) { continue; }
        let mut component = Vec::new();
        let mut queue: VecDeque<[i32; 3]> = VecDeque::new();
        queue.push_back(start);
        visited.insert(start);

        while let Some(cur) = queue.pop_front() {
            component.push(cur);
            for [dx, dy, dz] in [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]] {
                let nb = [cur[0]+dx, cur[1]+dy, cur[2]+dz];
                if blocks.contains(&nb) && !visited.contains(&nb) {
                    visited.insert(nb);
                    queue.push_back(nb);
                }
            }
        }
        components.push(component);
    }
    components
}

// true if every block of `a` is ≥ gap (Chebyshev distance) from every block of `b` — i.e. far apart.
// early-outs the moment a close pair is found, so the common "still touching" case is cheap.
fn component_gap_ge(a: &[[i32; 3]], b: &[[i32; 3]], gap: i32) -> bool {
    for pa in a {
        for pb in b {
            let d = (pa[0]-pb[0]).abs().max((pa[1]-pb[1]).abs()).max((pa[2]-pb[2]).abs());
            if d < gap { return false; }
        }
    }
    true
}

#[cfg(test)]
mod aero_tests {
    use super::*;

    fn wing() -> AeroSurface {
        AeroSurface {
            off: Vec3::ZERO,
            normal: Vec3::Y,
            area: 1.0,
            cd: 0.8,
            cl: 2.0,
            buoy: 0.0,
            chunky: false,
        }
    }

    fn one_block(mode: AeroMode, gravity: Vec3) -> KhysWorld {
        let mut world = KhysWorld::new();
        world.gravity = gravity;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 100.0, 0.0]);
        let kontra = world.kontras.get_mut(&1).unwrap();
        kontra.aero_mode = mode;
        kontra.aero.push(wing());
        world
    }

    #[test]
    fn wings_cannot_hover_from_rest() {
        for mode in [AeroMode::Low, AeroMode::Correct, AeroMode::Extreme] {
            let mut world = one_block(mode, Vec3::new(0.0, -28.0, 0.0));
            for _ in 0..120 { world.step(1.0 / 60.0); }
            let body = world.bodies.get(world.kontras[&1].body).unwrap();
            assert!(body.translation().y < 90.0, "{mode:?} hovered at {}", body.translation().y);
        }
    }

    #[test]
    fn airflow_never_accelerates_a_coasting_body() {
        for mode in [AeroMode::Low, AeroMode::Correct, AeroMode::Extreme] {
            let mut world = one_block(mode, Vec3::ZERO);
            let handle = world.kontras[&1].body;
            world.bodies.get_mut(handle).unwrap().set_linvel(Vec3::new(10.0, 0.0, 0.0), true);
            for _ in 0..120 { world.step(1.0 / 60.0); }
            let speed = world.bodies.get(handle).unwrap().linvel().length();
            assert!(speed < 9.9, "{mode:?} added or retained energy: {speed}");
        }
    }

    fn glider(mode: AeroMode) -> (f32, f32, f32) {
        let mut world = KhysWorld::new();
        let mut offsets = Vec::new();
        let mut masses = Vec::new();
        for z in -4..=3 {
            offsets.push([0.0, 0.0, z as f32]);
            masses.push(1.0);
        }
        for x in [-3, -2, -1, 1, 2, 3] {
            offsets.push([x as f32, 0.0, 0.0]);
            masses.push(0.3);
        }
        for x in [-1, 1] {
            offsets.push([x as f32, 0.0, -4.0]);
            masses.push(0.3);
        }
        world.spawn_kontraktion_from_offsets(1, offsets, &masses, 0, [0.0, 100.0, 0.0]);
        let kontra = world.kontras.get_mut(&1).unwrap();
        kontra.aero_mode = mode;
        for x in -3..=3 {
            let mut surface = wing();
            surface.off = Vec3::new(x as f32, 0.0, 0.0);
            kontra.aero.push(surface);
        }
        for x in -1..=1 {
            let mut surface = wing();
            surface.off = Vec3::new(x as f32, 0.0, -4.0);
            surface.area = 0.65;
            kontra.aero.push(surface);
        }
        let handle = kontra.body;
        world.bodies.get_mut(handle).unwrap().set_linvel(Vec3::new(0.0, -1.0, 16.0), true);
        for _ in 0..180 { world.step(1.0 / 60.0); }
        let body = world.bodies.get(handle).unwrap();
        (body.translation().z, body.translation().y, body.linvel().length())
    }

    #[test]
    fn balloons_have_a_stable_altitude_and_kill_bobbing() {
        let equilibrium = 64.0 + 180.0 * 20.0f32.ln();
        for mode in [AeroMode::Low, AeroMode::Correct, AeroMode::Extreme] {
            let mut world = KhysWorld::new();
            world.spawn_kontraktion_from_offsets(
                1, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, equilibrium, 0.0],
            );
            let kontra = world.kontras.get_mut(&1).unwrap();
            kontra.aero_mode = mode;
            kontra.aero.push(AeroSurface {
                off: Vec3::ZERO,
                normal: Vec3::Y,
                area: 1.0,
                cd: 0.47,
                cl: 0.0,
                buoy: 20.0,
                chunky: false,
            });
            let handle = kontra.body;
            world.bodies.get_mut(handle).unwrap().set_linvel(Vec3::new(3.0, 8.0, 0.0), true);
            for _ in 0..900 { world.step(1.0 / 60.0); }
            let body = world.bodies.get(handle).unwrap();
            let height_error = (body.translation().y - equilibrium).abs();
            assert!(height_error < 35.0, "{mode:?} balloon missed equilibrium by {height_error}");
            assert!(body.linvel().length() < 4.0, "{mode:?} balloon kept bobbing at {:?}", body.linvel());
        }
    }

    // fill a slab of water via the section fluid path, y in [wy0, wy1]
    fn flood(world: &mut KhysWorld, wy0: i32, wy1: i32) {
        for sx in -2..=2 { for sz in -2..=2 {
            for sy in sections::sec_coord(wy0)..=sections::sec_coord(wy1) {
                world.sections.upload([sx, sy, sz], Box::new([0u64; 64]), 0);
                let mut bits = Box::new([0u64; 64]);
                for ly in 0..16 {
                    let wy = sy * 16 + ly;
                    if wy < wy0 || wy > wy1 { continue; }
                    for lz in 0..16 { for lx in 0..16 {
                        let idx = ((ly * 16 + lz) * 16 + lx) as usize;
                        bits[idx >> 6] |= 1u64 << (idx & 63);
                    }}
                }
                world.sections.upload_fluids([sx, sy, sz], bits);
            }
        }}
    }

    #[test]
    fn light_blocks_float_heavy_blocks_sink() {
        for (mass, floats) in [(0.5f32, true), (3.0f32, false)] {
            let mut world = KhysWorld::new();
            flood(&mut world, 0, 40);
            world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[mass], 0, [8.0, 38.0, 8.0]);
            for _ in 0..600 { world.step(1.0 / 60.0); }
            let y = world.bodies.get(world.kontras[&1].body).unwrap().translation().y;
            if floats {
                assert!(y > 36.0, "mass {mass} sank to y={y}, should float near the surface");
            } else {
                assert!(y < 34.0, "mass {mass} floats at y={y}, should sink");
            }
        }
    }

    #[test]
    fn floater_settles_instead_of_bobbing_forever() {
        let mut world = KhysWorld::new();
        flood(&mut world, 0, 40);
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[0.5], 0, [8.0, 50.0, 8.0]);
        for _ in 0..900 { world.step(1.0 / 60.0); }
        let v = world.bodies.get(world.kontras[&1].body).unwrap().linvel().length();
        assert!(v < 0.5, "still bobbing at |v|={v}");
    }

    #[test]
    fn a_light_build_deep_underwater_is_not_launched() {
        // buoyancy used to run TWICE — apply_water per block, plus a legacy whole-body shove of one
        // displaced mass per submerged block that ignored the block's real mass. a light build got
        // roughly two gravities upward and came out of the water like a cannon shot.
        let mut world = KhysWorld::new();
        flood(&mut world, 0, 40);
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0], [0.0, 1.0, 0.0], [1.0, 0.0, 0.0], [-1.0, 0.0, 0.0]],
            &[0.5, 0.5, 0.5, 0.5], 0, [8.0, 12.0, 8.0]);

        let mut peak_y: f32 = 0.0;
        let mut peak_up: f32 = 0.0;
        for _ in 0..900 {
            world.step(1.0 / 60.0);
            let b = world.bodies.get(world.kontras[&1].body).unwrap();
            peak_y = peak_y.max(b.translation().y);
            peak_up = peak_up.max(b.linvel().y);
        }
        // it should rise to the surface and stay there, not get fired through it
        assert!(peak_up < 12.0, "launched out of the water at {peak_up} blocks/s");
        assert!(peak_y < 46.0, "flew to y={peak_y}, well past the surface at 40");
    }

    #[test]
    fn revolute_motor_spins_the_wheel() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[5.0], 0, [0.0, 100.0, 0.0]);
        world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [2.0, 100.0, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 7, a: 1, b: 2,
            anchor_a: [1.0, 0.0, 0.0], anchor_b: [-1.0, 0.0, 0.0], axis: [1.0, 0.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetLimits {
            joint_id: 7, min: -0.6, max: 0.6,
        });
        world.process_cmd(PhysicsCmd::JointClearLimits { joint_id: 7 });
        world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: 7, target_vel: 6.0, max_force: 500.0 });
        for _ in 0..240 { world.step(1.0 / 60.0); }
        let snap = world.build_snapshot();
        let js = snap.joints.get(&7).expect("joint state missing from snapshot");
        assert!(js[1].abs() > 2.0, "motor never spun up, axis vel = {}", js[1]);
        assert!(js[0].abs() > 0.6, "cleared joint limit still blocked rotation at {}", js[0]);
        // bodies stay welded at the anchors — distance must hold ~2.0
        let t1 = world.bodies.get(world.kontras[&1].body).unwrap().translation();
        let t2 = world.bodies.get(world.kontras[&2].body).unwrap().translation();
        let d = ((t2.x-t1.x).powi(2) + (t2.y-t1.y).powi(2) + (t2.z-t1.z).powi(2)).sqrt();
        assert!((d - 2.0).abs() < 0.3, "joint drifted apart: distance {d}");
    }

    #[test]
    fn revolute_motor_reaches_its_commanded_speed_quickly() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[80.0], 0, [0.0, 100.0, 0.0]);
        world.spawn_kontraktion_from_offsets(
            2, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [2.0, 100.0, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 2,
            anchor_a: [1.0, 0.0, 0.0],
            anchor_b: [-1.0, 0.0, 0.0],
            axis: [1.0, 0.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetMotor {
            joint_id: 8,
            target_vel: 16.75,
            max_force: 5_120.0,
        });
        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }
        let speed = world.build_snapshot().joints[&8][1].abs();
        assert!(speed > 15.0, "motor only reached {speed} rad/s after one second");
    }

    #[test]
    fn position_motor_holds_a_limited_steering_bearing() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[20.0], 0, [0.0, 100.0, 0.0]);
        world.spawn_kontraktion_from_offsets(
            2, vec![[0.0, 0.0, 0.0]], &[2.0], 0, [2.0, 100.0, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 9,
            a: 1,
            b: 2,
            anchor_a: [1.0, 0.0, 0.0],
            anchor_b: [-1.0, 0.0, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetLimits {
            joint_id: 9, min: -0.6, max: 0.6,
        });
        world.process_cmd(PhysicsCmd::JointSetMotorPosition {
            joint_id: 9,
            target_pos: 0.5,
            stiffness: 65.0,
            damping: 14.0,
            max_force: 800.0,
            force_based: false,
        });
        for _ in 0..180 { world.step(1.0 / 60.0); }
        let turned = world.build_snapshot().joints[&9];
        assert!(turned[0] > 0.35 && turned[0] <= 0.61,
            "steering missed or crossed its limit: angle={}", turned[0]);

        world.process_cmd(PhysicsCmd::JointSetMotorPosition {
            joint_id: 9,
            target_pos: 0.0,
            stiffness: 65.0,
            damping: 14.0,
            max_force: 800.0,
            force_based: false,
        });
        for _ in 0..180 { world.step(1.0 / 60.0); }
        let centered = world.build_snapshot().joints[&9];
        assert!(centered[0].abs() < 0.04 && centered[1].abs() < 0.04,
            "steering kept rotating after release: state={centered:?}");
    }

    #[test]
    fn world_revolute_keeps_a_static_bearing_in_place() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[2.0], 0, [4.0, 20.0, 7.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 0,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [4.0, 20.0, 7.0],
            axis: [0.0, 1.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: 8, target_vel: 5.0, max_force: 300.0 });
        for _ in 0..120 { world.step(1.0 / 60.0); }
        let snap = world.build_snapshot();
        let pos = snap.kontras[&1].pos;
        assert!((Vec3::from(pos) - Vec3::new(4.0, 20.0, 7.0)).length() < 0.15);
        assert!(snap.joints[&8][1].abs() > 1.0);
    }

    #[test]
    fn bearing_angle_keeps_counting_past_one_turn() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[2.0], 0, [4.0, 20.0, 7.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8, a: 1, b: 0,
            anchor_a: [0.0, 0.0, 0.0], anchor_b: [4.0, 20.0, 7.0], axis: [0.0, 1.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: 8, target_vel: 5.0, max_force: 300.0 });
        for _ in 0..240 { world.step(1.0 / 60.0); }
        let state = world.build_snapshot().joints[&8];
        // 4s at up to 5 rad/s is ~3 turns, the raw angle alone would still sit inside +-pi
        assert!(state[2].abs() > 2.0 * std::f32::consts::TAU, "unwrap lost turns: {state:?}");
        assert!(state[0].abs() <= std::f32::consts::PI + 1e-3);
    }

    #[test]
    fn idle_world_bearing_does_not_wobble_sideways() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[2.0], 0, [4.0, 20.0, 7.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 0,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [4.0, 20.0, 7.0],
            axis: [0.0, 1.0, 0.0],
        });
        for _ in 0..600 { world.step(1.0 / 60.0); }

        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!((body.translation() - Vec3::new(4.0, 20.0, 7.0)).length() < 0.002);
        let sideways_spin = Vec3::new(body.angvel().x, 0.0, body.angvel().z).length();
        assert!(sideways_spin < 0.002, "idle bearing has sideways angular velocity {sideways_spin}");
        assert!(body.is_sleeping(), "idle bearing never settled to sleep");
    }

    #[test]
    fn translated_static_wheel_stays_on_its_rapier_anchor() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [4.0, 21.0, 7.0]);
        world.process_cmd(PhysicsCmd::SetTransform {
            id: 1,
            pos: [4.0, 20.0, 7.0],
            rot: [0.0, 0.0, 0.0, 1.0],
        });
        world.set_block_materials(1, &[[1.5, 0.05, 1.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 0,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [4.0, 20.0, 7.0],
            axis: [0.0, 1.0, 0.0],
        });
        world.process_cmd(PhysicsCmd::JointSetMotor {
            joint_id: 8,
            target_vel: 8.0,
            max_force: 500.0,
        });
        for _ in 0..600 { world.step(1.0 / 60.0); }

        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!((body.translation() - Vec3::new(4.0, 20.0, 7.0)).length() < 0.003);
        assert!(body.angvel().y.abs() > 6.0, "wheel motor did not reach its target speed");
        assert!(Vec3::new(body.angvel().x, 0.0, body.angvel().z).length() < 0.003);
    }

    #[test]
    fn four_motorized_wheels_drive_a_chassis() {
        let mut world = KhysWorld::new();
        let mut floor = Box::new([0u64; 64]);
        for z in 0..16usize {
            for x in 0..16usize {
                let idx = (15 * 16 + z) * 16 + x;
                floor[idx >> 6] |= 1u64 << (idx & 63);
            }
        }
        world.sections.upload([0, -1, 0], floor, 0);

        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[20.0], 0, [8.0, 0.5, 8.0]);
        let wheels = [
            (2, [7.0, 0.5, 7.0], [-1.0, 0.0, -1.0]),
            (3, [9.0, 0.5, 7.0], [ 1.0, 0.0, -1.0]),
            (4, [7.0, 0.5, 9.0], [-1.0, 0.0,  1.0]),
            (5, [9.0, 0.5, 9.0], [ 1.0, 0.0,  1.0]),
        ];
        for (index, (id, position, chassis_anchor)) in wheels.into_iter().enumerate() {
            world.spawn_kontraktion_from_offsets(id, vec![[0.0, 0.0, 0.0]], &[4.0], 0, position);
            world.set_block_materials(id, &[[1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
            let joint_id = 20 + index as i64;
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id,
                a: 1,
                b: id,
                anchor_a: chassis_anchor,
                anchor_b: [0.0, 0.0, 0.0],
                axis: [1.0, 0.0, 0.0],
            });
            world.process_cmd(PhysicsCmd::JointSetMotor {
                joint_id,
                target_vel: 8.0,
                max_force: 800.0,
            });
        }
        for _ in 0..240 { world.step(1.0 / 60.0); }
        let chassis = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!((chassis.translation().z - 8.0).abs() > 3.0,
            "wheel motors spun but chassis only reached z={}", chassis.translation().z);
    }

    // the free-spinning motor test above passes even with a useless motor, because nothing pushes back.
    // put a real car's weight on the wheels and a weak motor shows up immediately: it crawls.
    #[test]
    fn a_loaded_car_reaches_the_wheel_speed_it_was_told() {
        let mut world = KhysWorld::new();
        let mut floor = Box::new([0u64; 64]);
        for z in 0..16usize {
            for x in 0..16usize {
                let idx = (15 * 16 + z) * 16 + x;
                floor[idx >> 6] |= 1u64 << (idx & 63);
            }
        }
        world.sections.upload([0, -1, 0], floor, 0);

        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[240.0], 0, [8.0, 1.5, 8.0]);
        let wheels = [
            (2, [7.0, 0.5, 7.0], [-1.0, -1.0, -1.0]),
            (3, [9.0, 0.5, 7.0], [ 1.0, -1.0, -1.0]),
            (4, [7.0, 0.5, 9.0], [-1.0, -1.0,  1.0]),
            (5, [9.0, 0.5, 9.0], [ 1.0, -1.0,  1.0]),
        ];
        for (index, (id, position, chassis_anchor)) in wheels.into_iter().enumerate() {
            world.spawn_kontraktion_from_offsets(id, vec![[0.0, 0.0, 0.0]], &[8.0], 0, position);
            world.set_block_materials(id, &[[1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
            let joint_id = 30 + index as i64;
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id,
                a: 1,
                b: id,
                anchor_a: chassis_anchor,
                anchor_b: [0.0, 0.0, 0.0],
                axis: [1.0, 0.0, 0.0],
            });
            world.process_cmd(PhysicsCmd::JointSetMotor {
                joint_id,
                target_vel: 16.75,   // gear 5 out of the mechanics dream engine
                max_force: 6_500.0,
            });
        }
        for _ in 0..180 { world.step(1.0 / 60.0); }
        let speed = world.build_snapshot().joints[&30][1].abs();
        assert!(speed > 10.0,
            "loaded wheel crawled at {speed} rad/s of the 16.75 it was commanded — the motor is \
             not allowed to use the torque it was given");
    }

    // the test above only asks the wheel. a player asks the car: with its tyres turning at 8.9 m/s at
    // the rim it has to be doing most of that, not crawl at 0.4 while the wheels slip (omni's claims
    // caught every mechanics dream car doing exactly that)
    #[test]
    fn a_loaded_car_drives_at_the_speed_its_tyres_turn() {
        let mut world = KhysWorld::new();
        for sx in -1..=2i32 { for sz in -1..=3i32 {
            let mut floor = Box::new([0u64; 64]);
            for z in 0..16usize { for x in 0..16usize { let idx = (15 * 16 + z) * 16 + x; floor[idx >> 6] |= 1u64 << (idx & 63); } }
            world.sections.upload([sx, -1, sz], floor, 0);
        } }
        // a mechanics dream car: 3x5 planks with a bearing on each corner side, all at axle height, an
        // engine on top. a chassis a whole block over its axles on a short wheelbase does a wheelie
        // under this torque, which is physics, not a bug
        let mut cells: Vec<[f32; 3]> = Vec::new();
        let mut masses: Vec<f32> = Vec::new();
        for x in -1..=1i32 { for z in -2..=2i32 { cells.push([x as f32, 0.0, z as f32]); masses.push(2.5); } }
        for (x, z) in [(-2, -2), (2, -2), (-2, 2), (2, 2)] { cells.push([x as f32, 0.0, z as f32]); masses.push(3.0); }
        cells.push([0.0, 1.0, 0.0]); masses.push(12.0);
        let cy: f32 = cells.iter().map(|c| c[1]).sum::<f32>() / cells.len() as f32;
        let offsets: Vec<[f32; 3]> = cells.iter().map(|c| [c[0], c[1] - cy, c[2]]).collect();
        world.spawn_kontraktion_from_offsets(1, offsets, &masses, 0, [8.0, 0.5 + cy, 8.0]);
        for (index, (id, x, z)) in [(2, -2.06f32, -2.0f32), (3, 2.06, -2.0), (4, -2.06, 2.0), (5, 2.06, 2.0)].into_iter().enumerate() {
            world.spawn_kontraktion_from_offsets(id, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0 + x, 0.5, 8.0 + z]);
            let facing = if x < 0.0 { -1.0 } else { 1.0 };
            world.set_block_materials(id, &[[1.5, 0.05, 1.0, facing, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
            let joint_id = 40 + index as i64;
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint { joint_id, a: 1, b: id,
                anchor_a: [x, -cy, z], anchor_b: [0.0, 0.0, 0.0], axis: [facing, 0.0, 0.0] });
            world.process_cmd(PhysicsCmd::JointSetMotor { joint_id, target_vel: 16.75 * facing, max_force: 780.0 });
        }
        for _ in 0..120 { world.step(1.0 / 60.0); }
        let chassis = world.bodies.get(world.kontras[&1].body).unwrap();
        let v = chassis.linvel();
        let speed = (v.x * v.x + v.z * v.z).sqrt();
        let rim = world.build_snapshot().joints[&40][1].abs() * 0.53;
        assert!(speed > rim * 0.8,
            "the tyres turn at {rim} m/s at the rim but the car does {speed} m/s: they slip");
    }

    // a car pushed with nothing driving it rolls a while and stops by itself: rolling resistance.
    // a free wheel joint was a perfect bearing and the car rolled on for good (koper's perpetual motion)
    #[test]
    fn a_pushed_car_rolls_to_a_stop() {
        let mut world = KhysWorld::new();
        for sx in -1..=2i32 { for sz in -1..=6i32 {
            let mut floor = Box::new([0u64; 64]);
            for z in 0..16usize { for x in 0..16usize { let idx = (15 * 16 + z) * 16 + x; floor[idx >> 6] |= 1u64 << (idx & 63); } }
            world.sections.upload([sx, -1, sz], floor, 0);
        } }
        let mut cells: Vec<[f32; 3]> = Vec::new();
        let mut masses: Vec<f32> = Vec::new();
        for x in -1..=1i32 { for z in -2..=2i32 { cells.push([x as f32, 0.0, z as f32]); masses.push(2.5); } }
        for (x, z) in [(-2, -2), (2, -2), (-2, 2), (2, 2)] { cells.push([x as f32, 0.0, z as f32]); masses.push(3.0); }
        let cy: f32 = cells.iter().map(|c| c[1]).sum::<f32>() / cells.len() as f32;
        let offsets: Vec<[f32; 3]> = cells.iter().map(|c| [c[0], c[1] - cy, c[2]]).collect();
        world.spawn_kontraktion_from_offsets(1, offsets, &masses, 0, [8.0, 0.5 + cy, 8.0]);
        for (index, (id, x, z)) in [(2, -2.06f32, -2.0f32), (3, 2.06, -2.0), (4, -2.06, 2.0), (5, 2.06, 2.0)].into_iter().enumerate() {
            world.spawn_kontraktion_from_offsets(id, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0 + x, 0.5, 8.0 + z]);
            let facing = if x < 0.0 { -1.0 } else { 1.0 };
            world.set_block_materials(id, &[[1.5, 0.05, 1.0, facing, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint { joint_id: 40 + index as i64, a: 1, b: id,
                anchor_a: [x, -cy, z], anchor_b: [0.0, 0.0, 0.0], axis: [facing, 0.0, 0.0] });
        }
        for _ in 0..30 { world.step(1.0 / 60.0); }
        for id in 1..=5 { world.set_velocity(id, [0.0, 0.0, 5.0]); }
        for _ in 0..60 { world.step(1.0 / 60.0); }
        let rolling = world.bodies.get(world.kontras[&1].body).unwrap().linvel().z;
        assert!(rolling > 2.0, "a car pushed to 5 m/s should roll on for a while, it is at {rolling} after 1 s");
        for _ in 0..900 { world.step(1.0 / 60.0); }
        let v = world.bodies.get(world.kontras[&1].body).unwrap().linvel();
        assert!((v.x * v.x + v.z * v.z).sqrt() < 0.3, "15 s after the push the car still rolls: {v:?}");
    }

    // rolling resistance lives in the tyre on the ground, not in the hub: a wheel spun in the air
    // keeps spinning
    #[test]
    fn a_wheel_spun_in_the_air_keeps_spinning() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[20.0], 0, [0.0, 100.0, 0.0]);
        world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [1.0, 100.0, 0.0]);
        world.set_block_materials(2, &[[1.5, 0.05, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint { joint_id: 9, a: 1, b: 2,
            anchor_a: [1.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [1.0, 0.0, 0.0] });
        world.step(1.0 / 60.0);
        let h = world.kontras[&2].body;
        world.bodies.get_mut(h).unwrap().set_angvel(Vec3::new(20.0, 0.0, 0.0), true);
        for _ in 0..120 { world.step(1.0 / 60.0); }
        let w = world.build_snapshot().joints[&9][1].abs();
        assert!(w > 15.0, "a wheel spun at 20 rad/s in the air slowed to {w} in 2 s");
    }

    // replays a build omni wrote down when it failed (mechanics dream OmniScene): KOPER_SCENE=<file>
    // cargo test --release -p koperlib-khysics replays_an_omni_scene -- --nocapture
    // without the variable it has nothing to do and passes
    #[test]
    fn replays_an_omni_scene() {
        let Ok(path) = std::env::var("KOPER_SCENE") else { return };
        let text = std::fs::read_to_string(&path).expect("scene file");
        let scene: serde_json::Value = serde_json::from_str(&text).expect("scene json");
        let f = |v: &serde_json::Value| v.as_f64().unwrap_or(0.0) as f32;
        let v3 = |v: &serde_json::Value| [f(&v[0]), f(&v[1]), f(&v[2])];
        let mut world = KhysWorld::new();
        world.gravity = Vec3::new(0.0, f(&scene["gravity"]), 0.0);
        let bodies = scene["bodies"].as_array().expect("bodies");
        if let Some(cells) = scene["terrain"].as_array() {
            // the terrain the game had around the build, block by block
            let mut sections: HashMap<[i32; 3], Box<[u64; 64]>> = HashMap::new();
            for c in cells {
                let (x, y, z) = (c[0].as_i64().unwrap() as i32, c[1].as_i64().unwrap() as i32, c[2].as_i64().unwrap() as i32);
                let key = [x.div_euclid(16), y.div_euclid(16), z.div_euclid(16)];
                let bits = sections.entry(key).or_insert_with(|| Box::new([0u64; 64]));
                let idx = ((y.rem_euclid(16) * 16 + z.rem_euclid(16)) * 16 + x.rem_euclid(16)) as usize;
                bits[idx >> 6] |= 1u64 << (idx & 63);
            }
            // every section the build could touch, the empty ones too, so nothing counts as unknown
            for sx in -4..=4i32 { for sy in -2..=1i32 { for sz in -4..=4i32 {
                let bits = sections.remove(&[sx, sy, sz]).unwrap_or_else(|| Box::new([0u64; 64]));
                world.sections.upload([sx, sy, sz], bits, 0);
            } } }
            for (key, bits) in sections { world.sections.upload(key, bits, 0); }
        } else {
            // a flat floor under everything, its top face at y = 0
            for sx in -4..=4i32 { for sz in -4..=4i32 {
                let mut floor = Box::new([0u64; 64]);
                for z in 0..16usize { for x in 0..16usize { let idx = (15 * 16 + z) * 16 + x; floor[idx >> 6] |= 1u64 << (idx & 63); } }
                world.sections.upload([sx, -1, sz], floor, 0);
            } }
        }
        for body in bodies {
            let id = body["id"].as_i64().unwrap();
            let offsets: Vec<[f32; 3]> = body["offsets"].as_array().unwrap().iter().map(|o| v3(o)).collect();
            let masses: Vec<f32> = body["masses"].as_array().unwrap().iter().map(|m| f(m)).collect();
            world.spawn_kontraktion_from_offsets(id, offsets, &masses, 0, v3(&body["pos"]));
            let raw: Vec<f32> = body["materials"].as_array().unwrap().iter().map(|m| f(m)).collect();
            let materials: Vec<[f32; 15]> = raw.chunks_exact(15).map(|c| c.try_into().unwrap()).collect();
            world.set_block_materials(id, &materials);
            let raw: Vec<f32> = body["shapes"].as_array().unwrap().iter().map(|m| f(m)).collect();
            let shapes: Vec<[f32; 11]> = raw.chunks_exact(11).map(|c| c.try_into().unwrap()).collect();
            if !shapes.is_empty() { world.set_block_shapes(id, &shapes); }
        }
        let joints = scene["joints"].as_array().expect("joints");
        for joint in joints {
            let (joint_id, a, b) = (joint["id"].as_i64().unwrap(), joint["a"].as_i64().unwrap(), joint["b"].as_i64().unwrap());
            let (anchor_a, anchor_b, axis) = (v3(&joint["anchor_a"]), v3(&joint["anchor_b"]), v3(&joint["axis"]));
            world.process_cmd(if joint["prismatic"].as_bool().unwrap_or(false) {
                PhysicsCmd::CreatePrismaticJoint { joint_id, a, b, anchor_a, anchor_b, axis }
            } else {
                PhysicsCmd::CreateRevoluteJoint { joint_id, a, b, anchor_a, anchor_b, axis }
            });
            if let Some(l) = joint["limits"].as_array() {
                world.process_cmd(PhysicsCmd::JointSetLimits { joint_id, min: f(&l[0]), max: f(&l[1]) });
            }
            world.process_cmd(PhysicsCmd::JointSetMotor { joint_id, target_vel: f(&joint["motor_vel"]), max_force: f(&joint["motor_force"]) });
            // KOPER_SCENE_MOTOR=vel:force switches the drive on the way mechanics dream does, for a
            // scene taken before the engine ran: forward is against the axle's sideways sign
            if let Ok(motor) = std::env::var("KOPER_SCENE_MOTOR") {
                let mut parts = motor.split(':').map(|p| p.parse::<f32>().unwrap_or(0.0));
                let (vel, force) = (parts.next().unwrap_or(0.0), parts.next().unwrap_or(0.0));
                let sign = -(axis[0] + axis[2]).signum();
                world.process_cmd(PhysicsCmd::JointSetMotor { joint_id, target_vel: vel * sign, max_force: force });
            }
        }
        // the pose and speed it had in the game, only now: joints are made between unrotated bodies
        for body in bodies {
            let id = body["id"].as_i64().unwrap();
            let r = &body["rot"];
            world.set_transform(id, v3(&body["pos"]), [f(&r[0]), f(&r[1]), f(&r[2]), f(&r[3])]);
            world.set_velocity(id, v3(&body["linvel"]));
            if let Some(b) = world.kontras.get(&id).and_then(|k| world.bodies.get_mut(k.body)) {
                b.set_angvel(Vec3::from(v3(&body["angvel"])), true);
            }
        }
        // the body with the most blocks is the chassis; the others are what hangs off it
        let first = bodies.iter().max_by_key(|b| b["offsets"].as_array().map_or(0, |o| o.len()))
            .and_then(|b| b["id"].as_i64()).unwrap();
        let joint_ids: Vec<i64> = joints.iter().map(|j| j["id"].as_i64().unwrap()).collect();
        let resend = std::env::var("KOPER_SCENE_RESEND").is_ok();
        let reupload = std::env::var("KOPER_SCENE_REUPLOAD").is_ok();
        for i in 0..240 {
            // the game re-sends every motor once per server tick, three physics steps apart
            if resend && i % 3 == 0 {
                for joint in joints {
                    world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: joint["id"].as_i64().unwrap(),
                        target_vel: f(&joint["motor_vel"]), max_force: f(&joint["motor_force"]) });
                }
            }
            // what the game did: the same unchanged floor sections uploaded again, every step
            if reupload {
                for sx in -1..=1i32 { for sz in -1..=1i32 {
                    let mut floor = Box::new([0u64; 64]);
                    for z in 0..16usize { for x in 0..16usize { let idx = (15 * 16 + z) * 16 + x; floor[idx >> 6] |= 1u64 << (idx & 63); } }
                    world.process_cmd(PhysicsCmd::UploadSection { pos: [sx, -1, sz], bits: floor });
                } }
            }
            world.step(1.0 / 60.0);
            if i % 20 == 0 {
                let c = world.bodies.get(world.kontras[&first].body).unwrap();
                let snap = world.build_snapshot();
                let wheels: Vec<String> = joint_ids.iter().filter_map(|j| snap.joints.get(j)).map(|s| format!("{:.1}", s[1])).collect();
                println!("SCENE step {i}: y={:.2} v=({:.2},{:.2},{:.2}) joints {:?}", c.translation().y,
                    c.linvel().x, c.linvel().y, c.linvel().z, wheels);
            }
        }
    }

    // a tyre must bite sideways and still roll freely forward — otherwise steered wheels turn
    // and the car keeps going straight
    #[test]
    fn a_wheel_grips_sideways_but_still_rolls_forward() {
        let mut world = KhysWorld::new();
        let mut floor = Box::new([0u64; 64]);
        for z in 0..16usize {
            for x in 0..16usize {
                let idx = (15 * 16 + z) * 16 + x;
                floor[idx >> 6] |= 1u64 << (idx & 63);
            }
        }
        world.sections.upload([0, -1, 0], floor, 0);

        // axle along X, so X is sideways for this wheel and Z is where it rolls
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0, 0.5, 8.0]);
        world.set_block_materials(1, &[[1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]);
        world.process_cmd(PhysicsCmd::SetVelocity { id: 1, v: [6.0, 0.0, 6.0] });
        // short window on purpose: a lone wheel with no chassis holding it upright starts to topple
        // after a while and then curves like a dropped coin, which is honest physics, not grip
        for _ in 0..6 { world.step(1.0 / 60.0); }

        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        let v = body.linvel();
        assert!(v.x.abs() < 1.2,
            "wheel kept sliding along its own axle at {} blocks/s — no sideways grip", v.x);
        assert!(v.z.abs() > 3.5,
            "wheel lost its rolling speed too ({}), grip must not brake the car", v.z);
    }

    // the lift: a body on a vertical rail to the world, told to go up by a position motor
    #[test]
    fn a_world_prismatic_motor_lifts_its_body() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[10.0], 0, [8.0, 100.0, 8.0]);
        world.process_cmd(PhysicsCmd::CreatePrismaticJoint {
            joint_id: 40, a: 1, b: 0,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [8.0, 100.0, 8.0],
            axis: [0.0, 1.0, 0.0],
        });
        // world end is body2, so the sign is mirrored: -2 on the joint puts the body 2 blocks UP
        world.process_cmd(PhysicsCmd::JointSetLimits { joint_id: 40, min: -5.0, max: 0.0 });
        world.process_cmd(PhysicsCmd::JointSetMotorPosition {
            joint_id: 40, target_pos: -2.0, stiffness: 900.0, damping: 120.0, max_force: 400_000.0,
            force_based: false,
        });
        for _ in 0..120 { world.step(1.0 / 60.0); }
        let y = world.bodies.get(world.kontras[&1].body).unwrap().translation().y;
        assert!((y - 102.0).abs() < 0.3,
            "platform was told to rise 2 blocks and sits at {y} instead of 102");
    }

    // koper's build: a stack of blocks where every block is its own body on a bearing. the pieces that
    // are NOT directly jointed still overlap, and without a filter the contact solver throws the whole
    // thing across the map
    #[test]
    fn a_chain_of_jointed_parts_does_not_blow_itself_apart() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        // a bearing and its head share a cell — that is what "flush mount" means, and every mounted
        // part in the game overlaps its bearing exactly like this
        for i in 0..4i64 {
            world.spawn_kontraktion_from_offsets(
                i + 1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 100.0, 0.0]);
        }
        for i in 0..3i64 {
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id: 50 + i,
                a: i + 1,
                b: i + 2,
                anchor_a: [0.0, 0.0, 0.0],
                anchor_b: [0.0, 0.0, 0.0],
                axis: [0.0, 1.0, 0.0],
            });
        }
        for _ in 0..120 { world.step(1.0 / 60.0); }

        // the two ends are NOT jointed to each other, only through the middle — exactly the pair that
        // used to grind against itself. the filter must leave them without a single contact
        let first = world.kontras[&1].colliders[0];
        let last = world.kontras[&4].colliders[0];
        let touching = world.narrow_phase.contact_pairs_with(first)
            .any(|pair| (pair.collider1 == last || pair.collider2 == last)
                && pair.has_any_active_contact());
        assert!(!touching,
            "two parts of one creation are shoving each other — self-collision filter is not applied");

        for i in 0..4i64 {
            let body = world.bodies.get(world.kontras[&(i + 1)].body).unwrap();
            let t = body.translation();
            let drift = Vec3::new(t.x, t.y - 100.0, t.z).length();
            assert!(drift < 0.5,
                "part {i} of the assembly was flung {drift:.2} blocks away — the build is fighting itself");
        }
    }

    // koper's tower: bearings stacked on bearings, each part hanging off the one below. the doctor
    // measures how far a joint's two anchors have drifted apart — anything you can see is too much
    #[test]
    fn a_tower_of_bearings_keeps_its_anchors_together() {
        let mut world = KhysWorld::new();
        let parts = 6i64;
        // sideways, not stacked: gravity then hangs the whole arm off the first joint, which is what
        // a wheel on a bearing on a bearing actually does to the anchors
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[400.0], 0, [0.0, 100.0, 0.0]);
        if let Some(b) = world.bodies.get_mut(world.kontras[&1].body) {
            b.set_body_type(RigidBodyType::Fixed, true);   // the wall the arm hangs from
        }
        for i in 1..parts {
            world.spawn_kontraktion_from_offsets(
                i + 1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [i as f32, 100.0, 0.0]);
        }
        for i in 0..parts - 1 {
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id: 70 + i,
                a: i + 1,
                b: i + 2,
                anchor_a: [0.5, 0.0, 0.0],
                anchor_b: [-0.5, 0.0, 0.0],
                axis: [0.0, 0.0, 1.0],
            });
        }
        for _ in 0..240 { world.step(1.0 / 60.0); }

        for i in 0..parts - 1 {
            let a = world.bodies.get(world.kontras[&(i + 1)].body).unwrap();
            let b = world.bodies.get(world.kontras[&(i + 2)].body).unwrap();
            let (ta, tb) = (a.translation(), b.translation());
            let qa = a.rotation();
            let qb = b.rotation();
            let anchor_a = Vec3::new(ta.x, ta.y, ta.z)
                + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.5, 0.0, 0.0);
            let anchor_b = Vec3::new(tb.x, tb.y, tb.z)
                + Quat::from_xyzw(qb.x, qb.y, qb.z, qb.w) * Vec3::new(-0.5, 0.0, 0.0);
            let slip = (anchor_a - anchor_b).length();
            assert!(slip < 0.005,
                "joint {i} of the arm slipped {slip:.3} blocks — the chain is visibly stretching");
        }
    }

    // ── koper's report: several FREE bearing rigs in one world ───────────────
    // one rig = base kontra (block + bearing one cell above it) + a head kontra flush-mounted on the
    // bearing. Nothing is pinned to the world. A single rig behaves; spawn a few and they take off.
    fn ground(world: &mut KhysWorld, wy: i32) {
        let sy = sections::sec_coord(wy);
        let ly = sections::sec_local(wy);
        for sx in -6..=6 { for sz in -2..=2 {
            let mut bits = Box::new([0u64; 64]);
            for lz in 0..16 { for lx in 0..16 {
                let idx = ((ly * 16 + lz) * 16 + lx) as usize;
                bits[idx >> 6] |= 1u64 << (idx & 63);
            }}
            world.sections.upload([sx, sy, sz], bits, 0);
        }}
    }

    // base body origin sits between its two blocks; the head shares the bearing's cell (flush mount)
    fn bearing_rig(world: &mut KhysWorld, n: i64, x: f32) {
        let base = n * 2 + 1;
        let head = n * 2 + 2;
        world.spawn_kontraktion_from_offsets(
            base, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [x, 101.0, 0.0]);
        world.spawn_kontraktion_from_offsets(
            head, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 101.5, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 900 + n,
            a: base, b: head,
            anchor_a: [0.0, 0.5, 0.0],
            anchor_b: [0.0, 0.0, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
    }

    // koper's recipe: a build is a block, a bearing on it, and a block on the bearing. Stand four
    // of them close enough to touch and they fire themselves across the map; three are fine. Four is
    // not an arbitrary number — it is SIMD_WIDTH. Rapier batches joint constraints four at a time and
    // only takes that path once a full batch exists in ONE island, so three joints stay on the scalar
    // path and behave. Builds that merely stand near each other are separate islands and never form a
    // batch; one build touching four others (koper's 3x3 with a middle) merges them into a single
    // island and the batch appears. Anchors are the ones his khys log shows.
    fn block_bearing_block(world: &mut KhysWorld, n: i64, x: f32, z: f32) {
        let (base, head) = (n * 2 + 1, n * 2 + 2);
        world.spawn_kontraktion_from_offsets(
            base, vec![[0.0, 0.0, 0.0], [0.0, 1.0, 0.0]], &[4.0, 4.0], 0, [x, 100.5, z]);
        world.spawn_kontraktion_from_offsets(
            head, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 102.5625, z]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 600 + n, a: base, b: head,
            anchor_a: [0.0, 1.5625, 0.0], anchor_b: [0.0, -0.5, 0.0], axis: [0.0, 1.0, 0.0],
        });
    }

    fn worst_speed(world: &mut KhysWorld, steps: usize) -> f32 {
        let mut peak = 0.0f32;
        for _ in 0..steps {
            world.step(1.0 / 60.0);
            for k in world.kontras.values() {
                let b = world.bodies.get(k.body).unwrap();
                let v = b.linvel();
                peak = peak.max(Vec3::new(v.x, v.y, v.z).length());
            }
        }
        peak
    }

    #[test]
    fn touching_bearing_builds_do_not_launch_themselves() {
        // a row of builds one block apart, so every one touches its neighbour and they share an island
        for count in 1..=6i64 {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            for n in 0..count { block_bearing_block(&mut world, n, n as f32, 0.0); }
            let peak = worst_speed(&mut world, 300);
            assert!(peak < 1.0,
                "{count} touching builds launched themselves at {peak:.2} blocks/s");
        }

        // koper's 3x3: one on each corner and one in the middle. The middle is what merges the four
        // corners into a single island, which is why four corners alone are calm and five are not.
        let mut world = KhysWorld::new();
        ground(&mut world, 99);
        for (n, (x, z)) in [(-1.0f32, -1.0f32), (1.0, -1.0), (-1.0, 1.0), (1.0, 1.0), (0.0, 0.0)]
            .iter().enumerate() {
            block_bearing_block(&mut world, n as i64, *x, *z);
        }
        let peak = worst_speed(&mut world, 300);
        assert!(peak < 1.0, "the 3x3 corners-and-middle patch launched at {peak:.2} blocks/s");
    }

    #[test]
    fn several_free_bearing_rigs_stay_put() {
        for count in 1..=8i64 {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            for n in 0..count { bearing_rig(&mut world, n, n as f32 * 4.0); }
            for _ in 0..600 { world.step(1.0 / 60.0); }

            let mut worst_drift = 0.0f32;
            let mut worst_slip = 0.0f32;
            let mut worst_speed = 0.0f32;
            for n in 0..count {
                let (base, head) = (n * 2 + 1, n * 2 + 2);
                let spawn = Vec3::new(n as f32 * 4.0, 101.0, 0.0);
                let a = world.bodies.get(world.kontras[&base].body).unwrap();
                let b = world.bodies.get(world.kontras[&head].body).unwrap();
                let (ta, tb) = (a.translation(), b.translation());
                let pa = Vec3::new(ta.x, ta.y, ta.z);
                let pb = Vec3::new(tb.x, tb.y, tb.z);
                let qa = a.rotation();
                let anchor_a = pa + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.0, 0.5, 0.0);
                worst_drift = worst_drift.max((pa - spawn).length());
                worst_slip = worst_slip.max((anchor_a - pb).length());
                let va = a.linvel();
                worst_speed = worst_speed.max(Vec3::new(va.x, va.y, va.z).length());
            }
            assert!(worst_drift < 0.5,
                "{count} free bearing rigs in one world: one was flung {worst_drift:.2} blocks");
            assert!(worst_slip < 0.01,
                "{count} free bearing rigs in one world: a bearing stretched {worst_slip:.3} blocks");
        }
    }


    // the lift path: spawnAssembly parks every body (RigidBodyType::Fixed) BEFORE the joints are
    // created, so the revolute is born between two fixed ends. Release unparks them again.
    fn bearing_rig_parked(world: &mut KhysWorld, n: i64, x: f32) {
        let base = n * 2 + 1;
        let head = n * 2 + 2;
        world.spawn_kontraktion_from_offsets(
            base, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [x, 101.0, 0.0]);
        world.process_cmd(PhysicsCmd::SetParked { id: base, parked: true });
        world.spawn_kontraktion_from_offsets(
            head, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 101.5, 0.0]);
        world.process_cmd(PhysicsCmd::SetParked { id: head, parked: true });
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 900 + n,
            a: base, b: head,
            anchor_a: [0.0, 0.5, 0.0],
            anchor_b: [0.0, 0.0, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
    }

    fn rig_report(world: &KhysWorld, count: i64) -> (f32, f32, f32) {
        let (mut drift, mut slip, mut speed) = (0.0f32, 0.0f32, 0.0f32);
        for n in 0..count {
            let (base, head) = (n * 2 + 1, n * 2 + 2);
            let spawn = Vec3::new(n as f32 * 4.0, 101.0, 0.0);
            let a = world.bodies.get(world.kontras[&base].body).unwrap();
            let b = world.bodies.get(world.kontras[&head].body).unwrap();
            let (ta, tb) = (a.translation(), b.translation());
            let pa = Vec3::new(ta.x, ta.y, ta.z);
            let pb = Vec3::new(tb.x, tb.y, tb.z);
            let qa = a.rotation();
            let anchor_a = pa + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.0, 0.5, 0.0);
            drift = drift.max((pa - spawn).length());
            slip = slip.max((anchor_a - pb).length());
            let va = a.linvel();
            speed = speed.max(Vec3::new(va.x, va.y, va.z).length());
        }
        (drift, slip, speed)
    }

    #[test]
    fn lift_released_bearing_rigs_do_not_blow_up() {
        for count in 1..=8i64 {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            for n in 0..count { bearing_rig_parked(&mut world, n, n as f32 * 4.0); }
            for _ in 0..60 { world.step(1.0 / 60.0); }        // sitting on the lift, parked
            for n in 0..count {                                // release
                world.process_cmd(PhysicsCmd::SetParked { id: n * 2 + 1, parked: false });
                world.process_cmd(PhysicsCmd::SetParked { id: n * 2 + 2, parked: false });
            }
            for _ in 0..600 { world.step(1.0 / 60.0); }

            let (drift, slip, speed) = rig_report(&world, count);
            assert!(drift < 0.5,
                "{count} lift-released rigs: one was flung {drift:.2} blocks off its drop point");
            assert!(slip < 0.01,
                "{count} lift-released rigs: a bearing stretched {slip:.3} blocks");
            assert!(speed < 1.0,
                "{count} lift-released rigs: one is still moving at {speed:.2} blocks/s");
            // the lift creates every joint while BOTH ends are still RigidBodyType::Fixed, which is
            // the path that skips island merging entirely. If the re-join on unpark ever regresses,
            // the joint silently spans two islands and stops being solved — catch that here.
            let problems = world.islands.island_state_problems(&world.bodies);
            assert!(problems.is_empty(),
                "{count} lift-released rigs left rapier's island bookkeeping inconsistent: {problems:?}");
        }
    }


    // koper's "bearing snake": bearings stacked straight onto bearings, flush, so every pair shares a
    // cell. The existing chain test runs this in ZERO gravity, which hides the case koper actually
    // plays: a real chain resting on the ground under real gravity. It also gets worse the longer the
    // chain is, and blocks between the bearings only slow it down.
    #[test]
    // suspension has to behave like a spring: sag under the weight it carries, squash further on
    // a landing, and come back. SuspensionManager drives exactly this — limits plus a FORCE based
    // position motor at rest 0, with the profile's stiffness and damping.
    #[test]
    fn a_sprung_prismatic_sags_compresses_on_landing_and_returns() {
        // rate sized from the load, the way SuspensionManager does it now: mass * g / (travel * 0.4)
        for (name, mass, travel) in [
            ("sport", 40.0f32, 4.0f32 / 16.0),
            ("offroad", 40.0, 12.0 / 16.0),
        ] {
            let stiffness = mass * 28.0 / (travel * 0.4);
            let damping = stiffness * 0.1;
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            // hub bolted in place, wheel hanging under it on the spring
            world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 106.0, 0.0]);
            world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[mass], 0, [0.0, 106.0, 0.0]);
            world.process_cmd(PhysicsCmd::CreatePrismaticJoint {
                joint_id: 50, a: 1, b: 2,
                anchor_a: [0.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
            });
            world.process_cmd(PhysicsCmd::JointSetLimits { joint_id: 50, min: -travel, max: 0.0 });
            world.process_cmd(PhysicsCmd::JointSetMotorPosition {
                joint_id: 50, target_pos: 0.0, stiffness, damping,
                max_force: 650_000.0, force_based: true,
            });
            // pin the hub so only the spring can move
            world.process_cmd(PhysicsCmd::SetParked { id: 1, parked: true });

            for _ in 0..240 { world.step(1.0 / 60.0); }
            let resting = joint_travel(&world, 50);
            assert!(resting < -0.005,
                "{name}: the spring never sagged under the load it carries ({resting:.4})");
            assert!(resting > -travel + 0.005,
                "{name}: the spring bottomed out just standing there ({resting:.4})");

            // drop the wheel hard and watch it squash further
            let body = world.kontras[&2].body;
            world.bodies.get_mut(body).unwrap().set_linvel(Vector::new(0.0, -14.0, 0.0), true);
            let mut deepest = resting;
            for _ in 0..30 {
                world.step(1.0 / 60.0);
                deepest = deepest.min(joint_travel(&world, 50));
            }
            assert!(deepest < resting - 0.01,
                "{name}: a landing did not compress it any further (rest {resting:.4}, deepest {deepest:.4})");

            for _ in 0..240 { world.step(1.0 / 60.0); }
            let back = joint_travel(&world, 50);
            assert!((back - resting).abs() < 0.05,
                "{name}: never came back to its ride height (rest {resting:.4}, now {back:.4})");
        }
    }

    fn joint_travel(world: &KhysWorld, joint_id: i64) -> f32 {
        let rec = world.joints.get(&joint_id).expect("joint gone");
        let j = world.impulse_joints.get(rec.handle).expect("joint handle gone");
        let a = world.bodies.get(j.body1).unwrap().translation();
        let b = world.bodies.get(j.body2).unwrap().translation();
        b.y - a.y
    }

    // koper's car: chassis, four wheels on bearings with drive motors, each wheel hung off a
    // sprung prismatic the way SuspensionManager does it. drive it and watch for the two things
    // he reported — anchors creeping apart, and the body teleporting instead of moving.
    #[test]
    fn a_driven_car_holds_together_and_moves_smoothly() {
        let mut world = KhysWorld::new();
        ground(&mut world, 99);

        let chassis = 1i64;
        world.spawn_kontraktion_from_offsets(
            chassis,
            vec![[-1.0, 0.0, -1.0], [1.0, 0.0, -1.0], [-1.0, 0.0, 1.0], [1.0, 0.0, 1.0]],
            &[4.0; 4], 0, [0.0, 102.0, 0.0]);

        let corners = [[-1.0f32, -1.0, -1.0], [1.0, -1.0, -1.0], [-1.0, -1.0, 1.0], [1.0, -1.0, 1.0]];
        for (i, c) in corners.iter().enumerate() {
            let hub = 10 + i as i64;
            let wheel = 20 + i as i64;
            world.spawn_kontraktion_from_offsets(
                hub, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [c[0], 102.0 + c[1], c[2]]);
            world.spawn_kontraktion_from_offsets(
                wheel, vec![[0.0, 0.0, 0.0]], &[2.0], 0, [c[0], 102.0 + c[1], c[2]]);

            // suspension: hub slides on the chassis along Y, sprung to rest at 0
            let susp = 100 + i as i64;
            world.process_cmd(PhysicsCmd::CreatePrismaticJoint {
                joint_id: susp, a: chassis, b: hub,
                anchor_a: *c, anchor_b: [0.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
            });
            world.process_cmd(PhysicsCmd::JointSetLimits { joint_id: susp, min: -0.75, max: 0.0 });
            world.process_cmd(PhysicsCmd::JointSetMotorPosition {
                joint_id: susp, target_pos: 0.0, stiffness: 1400.0, damping: 140.0,
                max_force: 650_000.0, force_based: true,
            });

            // wheel on a bearing, driven
            let bearing = 200 + i as i64;
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id: bearing, a: hub, b: wheel,
                anchor_a: [0.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [1.0, 0.0, 0.0],
            });
            world.process_cmd(PhysicsCmd::JointSetMotor {
                joint_id: bearing, target_vel: 10.0, max_force: 2000.0,
            });
        }

        // settle, then drive
        for _ in 0..120 { world.step(1.0 / 60.0); }

        let mut prev = {
            let b = world.bodies.get(world.kontras[&chassis].body).unwrap();
            let t = b.translation();
            Vec3::new(t.x, t.y, t.z)
        };
        let mut worst_jump = 0.0f32;
        let mut worst_anchor = 0.0f32;

        for _ in 0..600 {
            world.step(1.0 / 60.0);

            let ct = {
                let b = world.bodies.get(world.kontras[&chassis].body).unwrap();
                let t = b.translation();
                Vec3::new(t.x, t.y, t.z)
            };
            // at 1/60s nothing sane moves a whole block per step
            worst_jump = worst_jump.max((ct - prev).length());
            prev = ct;

            for i in 0..4 {
                let hub = world.bodies.get(world.kontras[&(10 + i as i64)].body).unwrap();
                let wheel = world.bodies.get(world.kontras[&(20 + i as i64)].body).unwrap();
                let (h, w) = (hub.translation(), wheel.translation());
                // the bearing pins these two together, they share an anchor
                worst_anchor = worst_anchor.max(
                    Vec3::new(h.x - w.x, h.y - w.y, h.z - w.z).length());
            }
        }

        assert!(worst_anchor < 0.35,
            "a wheel drifted {worst_anchor:.2} blocks off its bearing while driving");
        assert!(worst_jump < 0.9,
            "the chassis teleported {worst_jump:.2} blocks in one 1/60s step — that is the stutter");
    }

    fn a_bearing_snake_under_real_gravity_does_not_crawl_away() {
        for links in [4usize, 6, 8, 12] {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            // every link is its own body sharing the same cell as its neighbour — that is what
            // "bearing flush on a bearing" means
            for i in 0..links as i64 {
                world.spawn_kontraktion_from_offsets(
                    i + 1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 100.5, 0.0]);
            }
            for i in 0..links as i64 - 1 {
                world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                    joint_id: 700 + i, a: i + 1, b: i + 2,
                    anchor_a: [0.0, 0.0, 0.0], anchor_b: [0.0, 0.0, 0.0],
                    axis: [0.0, 1.0, 0.0],
                });
            }
            for _ in 0..600 { world.step(1.0 / 60.0); }

            let mut worst = 0.0f32;
            let mut worst_speed = 0.0f32;
            for i in 0..links as i64 {
                let body = world.bodies.get(world.kontras[&(i + 1)].body).unwrap();
                let t = body.translation();
                worst = worst.max(Vec3::new(t.x, t.y - 100.5, t.z).length());
                let v = body.linvel();
                worst_speed = worst_speed.max(Vec3::new(v.x, v.y, v.z).length());
            }
            assert!(worst < 0.5,
                "{links}-link bearing snake: a link crawled {worst:.2} blocks off the stack");
            assert!(worst_speed < 0.5,
                "{links}-link bearing snake: still moving at {worst_speed:.2} blocks/s after 10s");
        }
    }


    // koper's actual trigger, finally pinned down: the rigs are NOT free — each one hangs off a world
    // joint (that is why they levitate instead of falling). Placing a lift block runs the orphan-rail /
    // static-bearing sweeps, which destroy every orphan world joint in one tick. `destroy_joint` removes
    // the world joint's anchor RigidBody, and that goes straight through rapier's island bookkeeping —
    // N times in the same tick. One rig is fine; three and it lets go.
    fn levitating_rig(world: &mut KhysWorld, n: i64, x: f32) {
        let base = n * 2 + 1;
        let head = n * 2 + 2;
        world.spawn_kontraktion_from_offsets(
            base, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [x, 101.0, 0.0]);
        world.spawn_kontraktion_from_offsets(
            head, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 101.5, 0.0]);
        // the bearing itself, pinned to the world — this is what holds the thing in mid air
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 800 + n, a: base, b: 0,
            anchor_a: [0.0, 0.5, 0.0],
            anchor_b: [x, 101.5, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
        // the head mounted flush on that bearing
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 850 + n, a: base, b: head,
            anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.0, 0.0, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
    }

    // a bearing's turning half: centre in the floor cell under its bearing, held there by its world
    // joint. anti_clip used to lift it every 5 steps and the joint yanked it back
    #[test]
    fn a_turning_half_in_the_floor_cell_is_left_alone() {
        let mut world = KhysWorld::new();
        ground(&mut world, 99);
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[0.05], 0, [0.5, 99.44, 0.5]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 801, a: 1, b: 0,
            anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.5, 99.94, 0.5], axis: [0.0, 1.0, 0.0],
        });
        for i in 0..120 {
            world.step(1.0 / 60.0);
            let y = world.bodies.get(world.kontras[&1].body).unwrap().translation().y;
            assert!((y - 99.44).abs() < 0.05, "turning half moved off its bearing to y={y} at step {i}");
        }
    }

    // KNOWN FAILING — reproduces the state koper's koperlib_khys.log is full of: 235 identical
    // copies of "anticlip k2 depth=0.51 y=-57.18", one body pinned half a block inside solid terrain
    // forever. A world joint whose anchor sits where the body cannot go does exactly that:
    // project_joint_hierarchy teleports the body back onto the anchor EVERY step with set_translation
    // and never touches velocity, so damping cannot bleed it off and the contact solver can never win.
    // anti_clip lifts it 0.11 every 5 steps and the projection drags it straight back down.
    // In game the bearing's own cell is meant to be removed from the section terrain
    // (StaticBearingManager calls suppressTerrainCollision on mount) — this is what it looks like when
    // that suppression is missing or has not been re-applied yet after a reload.
    #[test]
    #[ignore = "known bug: a world joint anchored inside terrain never settles — see the comment above"]
    fn a_world_jointed_body_inside_terrain_eventually_settles() {
        let mut world = KhysWorld::new();
        ground(&mut world, 99);
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 100.5, 0.0]);
        // anchor 1.5 blocks BELOW the resting height — inside the ground
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 800, a: 1, b: 0,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [0.0, 99.0, 0.0],
            axis: [0.0, 1.0, 0.0],
        });
        for _ in 0..600 { world.step(1.0 / 60.0); }
        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!(body.is_sleeping(),
            "body pinned inside terrain by a world joint is still awake after 10s — anti_clip and the \
             joint projection are fighting each other forever");
    }

    #[test]
    fn diagnose_violated_joint_recovery() {
        // Which solver setting actually lets rapier close a badly violated joint? khysics lowers
        // normalized_max_corrective_velocity to 4.0 to stop terrain rebuilds punting resting kontras,
        // and that same cap throttles JOINT error correction.
        for (label, corrective, iters) in [
            ("khysics now  (cap 4)", 4.0f32, 6usize),
            ("rapier stock (cap 10)", 10.0, 6),
            ("uncapped", f32::MAX, 6),
            ("cap 4, 32 iters", 4.0, 32),
        ] {
            let mut world = KhysWorld::new();
            world.gravity = Vec3::ZERO;
            world.params.normalized_max_corrective_velocity = corrective;
            world.params.num_solver_iterations = iters;
            world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 100.0, 0.0]);
            world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 101.0, 0.0]);
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id: 900, a: 1, b: 2,
                anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.0, -0.5, 0.0],
                axis: [0.0, 1.0, 0.0],
            });
            for _ in 0..30 { world.step(1.0 / 60.0); }
            // yank the head 1.5 blocks sideways WITHOUT going through create_joint's guard
            let h = world.kontras[&2].body;
            if let Some(b) = world.bodies.get_mut(h) {
                b.set_translation(Vec3::new(1.5, 101.0, 0.0), true);
            }
            for _ in 0..600 { world.step(1.0 / 60.0); }
            let a = world.bodies.get(world.kontras[&1].body).unwrap();
            let b = world.bodies.get(world.kontras[&2].body).unwrap();
            let (ta, tb) = (a.translation(), b.translation());
            let qa = a.rotation();
            let qb = b.rotation();
            let anchor_a = Vec3::new(ta.x, ta.y, ta.z)
                + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.0, 0.5, 0.0);
            let anchor_b = Vec3::new(tb.x, tb.y, tb.z)
                + Quat::from_xyzw(qb.x, qb.y, qb.z, qb.w) * Vec3::new(0.0, -0.5, 0.0);
            println!("  {label:<22} residual after 10s = {:.4}", (anchor_a - anchor_b).length());
        }
    }

    // TerrainSlurper re-uploads wanted sections every server tick, and primeArea re-uploads a whole
    // region before every spawn — almost always with byte-identical occupancy. If an unchanged upload
    // still re-meshes the section, every kontra resting on it loses its resting contacts, wakes, and
    // gets shoved by penetration recovery. That is the "place a block anywhere and every build nearby
    // kicks off at once" bug, and it fires on every spawn too.
    #[test]
    fn re_uploading_an_unchanged_section_leaves_resting_builds_alone() {
        let mut world = KhysWorld::new();
        ground(&mut world, 99);
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [0.0, 101.0, 0.0]);
        world.spawn_kontraktion_from_offsets(
            2, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 101.5, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 850, a: 1, b: 2,
            anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
        });
        for _ in 0..240 { world.step(1.0 / 60.0); }
        let settled = world.bodies.get(world.kontras[&1].body).unwrap().translation();
        assert!(world.bodies.get(world.kontras[&1].body).unwrap().is_sleeping(),
            "the build never settled, so this test cannot tell us anything");

        // the slurper doing its normal job: same occupancy, over and over
        let sy = sections::sec_coord(99);
        let ly = sections::sec_local(99);
        for _ in 0..60 {
            for sx in -1..=1 { for sz in -1..=1 {
                let mut bits = Box::new([0u64; 64]);
                for lz in 0..16 { for lx in 0..16 {
                    let idx = ((ly * 16 + lz) * 16 + lx) as usize;
                    bits[idx >> 6] |= 1u64 << (idx & 63);
                }}
                world.sections.upload([sx, sy, sz], bits, world.step_count());
            }}
            for _ in 0..4 { world.step(1.0 / 60.0); }
        }

        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        let now = body.translation();
        let moved = Vec3::new(now.x - settled.x, now.y - settled.y, now.z - settled.z).length();
        assert!(moved < 0.01,
            "re-uploading identical terrain moved a settled build {moved:.3} blocks");
        assert!(body.is_sleeping(),
            "re-uploading identical terrain woke a settled build and it never got back to sleep");
    }

    #[test]
    fn diagnose_staggered_spawn_of_four_rigs() {
        // koper's exact routine: spawn them ONE AT A TIME, letting each settle. The moment the fourth
        // appears, all four launch in the same tick.
        let mut world = KhysWorld::new();
        ground(&mut world, 99);
        let mut worst_seen = 0.0f32;
        for n in 0..4i64 {
            let x = n as f32 * 4.0;
            let (base, head) = (n * 2 + 1, n * 2 + 2);
            world.spawn_kontraktion_from_offsets(
                base, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [x, 101.0, 0.0]);
            world.spawn_kontraktion_from_offsets(
                head, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 101.5, 0.0]);
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                joint_id: 850 + n, a: base, b: head,
                anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
            });
            // watch every single step for a one-tick velocity spike anywhere in the world
            for step in 0..180 {
                world.step(1.0 / 60.0);
                let mut worst = 0.0f32;
                for k in world.kontras.values() {
                    if let Some(b) = world.bodies.get(k.body) {
                        let v = b.linvel();
                        worst = worst.max(Vec3::new(v.x, v.y, v.z).length());
                    }
                }
                if worst > worst_seen {
                    worst_seen = worst;
                    if worst > 1.0 {
                        println!("  rig {} step {step}: something is moving at {worst:.2} blocks/s", n + 1);
                    }
                }
            }
            println!("  after rig {} settled: fastest body {:.3} blocks/s", n + 1, worst_seen);
        }
    }

    #[test]
    fn diagnose_terrain_flip_under_resting_rigs() {
        // koper: placing AND destroying a lift breaks them, and you need a few. Both are just a world
        // block change. In khysics that marks the 16^3 section dirty, and rebuild_dirty REMOVES the
        // section collider and inserts a fresh one — waking every body resting on it and throwing away
        // its resting contacts.
        for count in [1i64, 3, 5] {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            for n in 0..count {
                let x = n as f32 * 3.0;
                world.spawn_kontraktion_from_offsets(
                    n * 2 + 1, vec![[0.0, -0.5, 0.0], [0.0, 0.5, 0.0]], &[4.0, 4.0], 0, [x, 101.0, 0.0]);
                world.spawn_kontraktion_from_offsets(
                    n * 2 + 2, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [x, 101.5, 0.0]);
                world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
                    joint_id: 850 + n, a: n * 2 + 1, b: n * 2 + 2,
                    anchor_a: [0.0, 0.5, 0.0], anchor_b: [0.0, 0.0, 0.0],
                    axis: [0.0, 1.0, 0.0],
                });
            }
            for _ in 0..180 { world.step(1.0 / 60.0); }   // let them settle and sleep

            let report = |world: &KhysWorld, tag: &str| {
                let mut drift = 0.0f32; let mut slip = 0.0f32; let mut speed = 0.0f32;
                for n in 0..count {
                    let a = world.bodies.get(world.kontras[&(n * 2 + 1)].body).unwrap();
                    let b = world.bodies.get(world.kontras[&(n * 2 + 2)].body).unwrap();
                    let (ta, tb) = (a.translation(), b.translation());
                    let pa = Vec3::new(ta.x, ta.y, ta.z);
                    let qa = a.rotation();
                    let anchor = pa + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.0, 0.5, 0.0);
                    drift = drift.max((pa - Vec3::new(n as f32 * 3.0, 101.0, 0.0)).length());
                    slip = slip.max((anchor - Vec3::new(tb.x, tb.y, tb.z)).length());
                    let v = a.linvel();
                    speed = speed.max(Vec3::new(v.x, v.y, v.z).length());
                }
                println!("  rigs={count} {tag:<22} drift={drift:.3} slip={slip:.4} speed={speed:.3}");
            };
            report(&world, "settled");

            // place the lift block: one cell flips solid in the same section
            world.process_cmd(PhysicsCmd::SetTerrainBlock { pos: [8, 100, 4], solid: true });
            for _ in 0..180 { world.step(1.0 / 60.0); }
            report(&world, "after lift PLACED");

            // break it again
            world.process_cmd(PhysicsCmd::SetTerrainBlock { pos: [8, 100, 4], solid: false });
            for _ in 0..180 { world.step(1.0 / 60.0); }
            report(&world, "after lift BROKEN");
        }
    }

    #[test]
    fn cutting_several_world_joints_at_once_does_not_blow_up_the_rigs() {
        for count in 1..=4i64 {
            let mut world = KhysWorld::new();
            ground(&mut world, 99);
            for n in 0..count { levitating_rig(&mut world, n, n as f32 * 4.0); }
            for _ in 0..120 { world.step(1.0 / 60.0); }   // hovering, held by the world joints

            let before = world.islands.island_state_problems(&world.bodies);
            assert!(before.is_empty(), "{count} levitating rigs already broke islands: {before:?}");

            // placing the lift: every orphan world joint cut in the SAME tick
            for n in 0..count { world.process_cmd(PhysicsCmd::DestroyJoint(800 + n)); }
            for _ in 0..600 { world.step(1.0 / 60.0); }

            let problems = world.islands.island_state_problems(&world.bodies);
            assert!(problems.is_empty(),
                "cutting {count} world joints at once broke rapier's island bookkeeping: {problems:?}");

            for n in 0..count {
                let (base, head) = (n * 2 + 1, n * 2 + 2);
                let a = world.bodies.get(world.kontras[&base].body).unwrap();
                let b = world.bodies.get(world.kontras[&head].body).unwrap();
                let (ta, tb) = (a.translation(), b.translation());
                let pa = Vec3::new(ta.x, ta.y, ta.z);
                let qa = a.rotation();
                let anchor_a = pa + Quat::from_xyzw(qa.x, qa.y, qa.z, qa.w) * Vec3::new(0.0, 0.5, 0.0);
                let slip = (anchor_a - Vec3::new(tb.x, tb.y, tb.z)).length();
                let drift_x = (pa.x - n as f32 * 4.0).abs();
                assert!(slip < 0.01,
                    "rig {n} of {count}: the bearing stretched {slip:.3} blocks after the cut");
                assert!(drift_x < 0.5,
                    "rig {n} of {count}: flung {drift_x:.2} blocks sideways after the cut");
                assert!((pa.y - 101.0).abs() < 0.3,
                    "rig {n} of {count}: should have fallen to 101, sits at {:.2}", pa.y);
            }
        }
    }

    #[test]
    fn liquid_slot_does_not_stop_a_body_and_solid_replacement_does() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::new(0.0, -28.0, 0.0);
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 0.0, 0.0]);
        world.set_parked(1, true);
        let mut material = [0.0; 15];
        material[6] = -1.0;
        world.set_block_materials(1, &[material]);
        world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 3.0, 0.0]);
        for _ in 0..45 { world.step(1.0 / 60.0); }
        assert!(world.bodies[world.kontras[&2].body].translation().y < -1.0,
            "liquid cargo acted as a solid floor");
        material[6] = 0.0;
        world.set_block_materials(1, &[material]);
        world.set_transform(2, [0.0, 3.0, 0.0], [0.0, 0.0, 0.0, 1.0]);
        for _ in 0..60 { world.step(1.0 / 60.0); }
        let height = world.bodies[world.kontras[&2].body].translation().y;
        assert!((height - 1.0).abs() < 0.1, "solid replacement lost collision: y={height}");
    }

    #[test]
    fn liquid_slot_removal_keeps_following_materials_in_wire_order() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(1,
            vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [2.0, 0.0, 0.0]], &[1.0; 3], 0, [0.0, 10.0, 0.0]);
        let mut materials = [[0.0; 15]; 3];
        for (i, m) in materials.iter_mut().enumerate() { m[9] = i as f32; m[12] = i as f32; }
        materials[1][6] = -1.0;
        materials[2][0] = 0.73;
        world.set_block_materials(1, &materials);
        assert!(!world.colliders[world.kontras[&1].colliders[1]].is_enabled(), "liquid slot is collidable");
        assert!(world.remove_block_at_local_offset(1, [1, 0, 0]));
        world.set_block_materials(1, &[materials[0], materials[2]]);
        let last = &world.colliders[world.kontras[&1].colliders[1]];
        assert!(last.is_enabled() && (last.friction() - 0.73).abs() < 1.0e-6,
            "removing fluid changed the following solid's material");
        assert!(world.add_block_at_local_offset(1, [1.0, 0.0, 0.0], 1.0));
        world.set_block_materials(1, &[materials[0], materials[2], materials[1]]);
        assert!(!world.colliders[world.kontras[&1].colliders[2]].is_enabled(), "new liquid slot remained solid");
    }

    #[test]
    fn micro_grid_material_builds_exact_subcell_collider() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 10.0, 0.0]);
        let cells = 1u64;
        world.set_block_materials(1, &[[
            0.8, 0.0, 0.0, 0.0, 1.0, 0.0, 4.0,
            f32::from_bits(cells as u32), f32::from_bits((cells >> 32) as u32),
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ]]);
        let collider = world.colliders.get(world.kontras[&1].colliders[0]).unwrap();
        let aabb = collider.shape().compute_local_aabb();
        assert!((aabb.extents().x - 0.25).abs() < 1.0e-4);
        assert!((aabb.extents().y - 0.25).abs() < 1.0e-4);
        assert!((aabb.extents().z - 0.25).abs() < 1.0e-4);
        assert!((aabb.center() - Vec3::splat(-0.375)).length() < 1.0e-4);
    }

    #[test]
    fn wheel_material_moves_the_real_cylinder_to_the_rendered_wheel() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [0.0, 5.0, 0.0]);
        world.set_block_materials(1, &[[
            1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.5, 0.125, -0.25, 0.0, 0.0, 0.0,
        ]]);
        let collider = world.colliders.get(world.kontras[&1].colliders[0]).unwrap();
        assert!((collider.position_wrt_parent().unwrap().translation
            - Vec3::new(0.5, 0.125, -0.25)).length() < 1.0e-5);
        let aabb = collider.shape().compute_local_aabb();
        assert!((aabb.extents() - Vec3::new(1.06, 0.25, 1.06)).length() < 1.0e-5);
    }

    #[test]
    fn idle_shifted_wheel_keeps_the_revolute_anchors_together() {
        let mut world = KhysWorld::new();
        let mut floor = Box::new([0u64; 64]);
        for z in 0..16usize {
            for x in 0..16usize {
                let idx = (15 * 16 + z) * 16 + x;
                floor[idx >> 6] |= 1u64 << (idx & 63);
            }
        }
        world.sections.upload([0, -1, 0], floor, 0);
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0, 0.53, 8.0]);
        world.spawn_kontraktion_from_offsets(
            2, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0, 0.53, 8.0]);
        world.set_block_materials(2, &[[
            1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.5, 0.125, -0.25, 0.0, 0.0, 0.0,
        ]]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 2,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [0.0, 0.0, 0.0],
            axis: [1.0, 0.0, 0.0],
        });
        for _ in 0..600 { world.step(1.0 / 60.0); }

        let a = world.bodies.get(world.kontras[&1].body).unwrap().translation();
        let b = world.bodies.get(world.kontras[&2].body).unwrap().translation();
        assert!((a - b).length() < 0.003, "idle wheel anchors separated by {}", (a - b).length());
        let state = world.build_snapshot().joints[&8];
        assert!(state[1].abs() < 0.01, "idle wheel spun without input at {}", state[1]);
    }

    #[test]
    fn loaded_wheel_does_not_shake_sideways_on_its_revolute() {
        let mut world = KhysWorld::new();
        let mut floor = Box::new([0u64; 64]);
        for z in 0..16usize {
            for x in 0..16usize {
                let idx = (15 * 16 + z) * 16 + x;
                floor[idx >> 6] |= 1u64 << (idx & 63);
            }
        }
        world.sections.upload([0, -1, 0], floor, 0);
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0, 0.53, 8.0]);
        world.spawn_kontraktion_from_offsets(
            2, vec![[0.0, 0.0, 0.0]], &[8.0], 0, [8.0, 0.53, 8.0]);
        world.set_block_materials(2, &[[
            1.5, 0.0, 1.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ]]);
        assert!(world.add_block_at_local_offset(2, [0.0, 1.0, 0.0], 8.0));
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 8,
            a: 1,
            b: 2,
            anchor_a: [0.0, 0.0, 0.0],
            anchor_b: [0.0, 0.0, 0.0],
            axis: [1.0, 0.0, 0.0],
        });

        let mut max_anchor_error = 0.0f32;
        for _ in 0..600 {
            world.step(1.0 / 60.0);
            let a = world.bodies.get(world.kontras[&1].body).unwrap().translation();
            let b = world.bodies.get(world.kontras[&2].body).unwrap().translation();
            max_anchor_error = max_anchor_error.max((a - b).length());
        }
        let wheel = world.bodies.get(world.kontras[&2].body).unwrap();
        let sideways_spin = Vec3::new(0.0, wheel.angvel().y, wheel.angvel().z).length();
        assert!(max_anchor_error < 0.01, "loaded wheel anchor shook by {max_anchor_error}");
        assert!(sideways_spin < 0.03, "loaded wheel gained sideways spin {sideways_spin}");
    }

    #[test]
    fn model_hitbox_boxes_become_a_rapier_compound() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 5.0, 0.0]);
        world.set_block_materials(1, &[[
            0.8, 0.0, 0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ]]);
        world.set_block_shapes(1, &[
            [0.0, -0.25, 0.0, 0.0, 0.25, 0.5, 0.5, 0.0, 0.0, 0.0, 1.0],
            [0.0, 0.375, 0.0, 0.0, 0.125, 0.25, 0.25, 0.0, 0.0, 0.0, 1.0],
        ]);
        let collider = world.colliders.get(world.kontras[&1].colliders[0]).unwrap();
        let aabb = collider.shape().compute_local_aabb();
        assert!((aabb.mins.x + 0.5).abs() < 1.0e-5);
        assert!((aabb.maxs.x - 0.5).abs() < 1.0e-5);
        assert!((aabb.extents().y - 1.0).abs() < 1.0e-5);
    }

    #[test]
    fn block_material_refresh_keeps_each_collider_on_its_block_after_removal() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1,
            vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [2.0, 0.0, 0.0]],
            &[1.0, 1.0, 1.0],
            0,
            [0.0, 10.0, 0.0],
        );
        assert!(world.remove_block_at_local_offset(1, [1, 0, 0]));
        world.set_block_materials(1, &[
            [0.8, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
            [0.8, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0,
                2.0, 0.0, 0.0, 2.0, 0.0, 0.0],
        ]);
        let kontra = &world.kontras[&1];
        let positions: Vec<Vec3> = kontra.colliders.iter()
            .map(|handle| world.colliders[*handle].position_wrt_parent().unwrap().translation)
            .collect();
        assert_eq!(positions, vec![Vec3::ZERO, Vec3::new(2.0, 0.0, 0.0)]);

        assert!(world.remove_block_at_local_offset(1, [2, 0, 0]));
        let kontra = &world.kontras[&1];
        assert_eq!(kontra.colliders.len(), 1);
        assert_eq!(
            world.colliders[kontra.colliders[0]].position_wrt_parent().unwrap().translation,
            Vec3::ZERO,
        );
    }

    #[test]
    fn model_shape_refresh_does_not_drag_an_offset_block_to_body_origin() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(
            1, vec![[2.0, 0.0, 0.0]], &[1.0], 0, [0.0, 10.0, 0.0]);
        world.set_block_materials(1, &[[
            0.8, 0.0, 0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 2.0, 0.0, 0.0,
        ]]);
        world.set_block_shapes(1, &[[
            0.0, 0.0, 0.0, 0.0, 0.25, 0.25, 0.25, 0.0, 0.0, 0.0, 1.0,
        ]]);
        let collider = world.colliders[world.kontras[&1].colliders[0]].position_wrt_parent().unwrap();
        assert_eq!(collider.translation, Vec3::new(2.0, 0.0, 0.0));
    }

    #[test]
    fn off_center_hit_moves_and_turns_the_body_in_the_hit_direction() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::ZERO;
        world.spawn_kontraktion_from_offsets(
            1, vec![[0.0, 0.0, 0.0]], &[4.0], 0, [0.0, 10.0, 0.0]);
        world.apply_impulse_at_point(1, [12.0, 0.0, 0.0], [0.0, 11.0, 0.0]);

        let body = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!(body.linvel().x > 0.1, "hit did not move the body along the impulse");
        assert!(body.angvel().z.abs() > 0.1, "off-center hit did not turn the body");
    }

    #[test]
    fn destroying_a_jointed_kontra_keeps_the_world_alive() {
        let mut world = KhysWorld::new();
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 100.0, 0.0]);
        world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [2.0, 100.0, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 9, a: 1, b: 2,
            anchor_a: [1.0, 0.0, 0.0], anchor_b: [-1.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
        });
        for _ in 0..10 { world.step(1.0 / 60.0); }
        world.destroy_contraption(1);
        assert!(world.joints.is_empty(), "joint record survived its kontra");
        for _ in 0..10 { world.step(1.0 / 60.0); } // must not panic on the dangling handle
        assert!(world.build_snapshot().joints.is_empty());
    }

    // a plus of four flat plates, spun about +Y and held in place: the average push per step.
    // flat is how every real micro block rotor looks, blocks can't be twisted
    fn koper_rotor(spin: f32, tilt_deg: f32) -> f32 {
        let mut world = KhysWorld::new();
        let offsets = vec![[1.0, 0.0, 0.0], [0.0, 0.0, 1.0], [-1.0, 0.0, 0.0], [0.0, 0.0, -1.0]];
        world.spawn_kontraktion_from_offsets(1, offsets.clone(), &[1.0, 1.0, 1.0, 1.0], 0, [0.0, 100.0, 0.0]);
        let kontra = world.kontras.get_mut(&1).unwrap();
        kontra.aero_mode = AeroMode::Correct;
        let tilt = tilt_deg.to_radians();
        for off in &offsets {
            let arm = Vec3::from(*off);
            let mut surface = wing();
            surface.off = arm;
            // tilt 90 = a paddle standing across the spin, not a blade
            surface.normal = (Vec3::Y * tilt.cos() + arm.normalize() * tilt.sin()).normalize();
            kontra.aero.push(surface);
        }
        let handle = kontra.body;
        // on a hub like on a bearing: the joint axis (+Y, out of the hub) is the way it pushes
        world.spawn_kontraktion_from_offsets(2, vec![[0.0, 0.0, 0.0]], &[1.0], 0, [0.0, 98.0, 0.0]);
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: 5, a: 2, b: 1, anchor_a: [0.0, 2.0, 0.0], anchor_b: [0.0, 0.0, 0.0], axis: [0.0, 1.0, 0.0],
        });
        let hub = world.kontras[&2].body;
        let mut sum = 0.0;
        for _ in 0..30 {
            for h in [handle, hub] { world.bodies.get_mut(h).unwrap().set_linvel(Vec3::ZERO, true); }
            world.bodies.get_mut(handle).unwrap().set_angvel(Vec3::new(0.0, spin, 0.0), true);
            world.bodies.get_mut(hub).unwrap().set_angvel(Vec3::ZERO, true);
            world.step(1.0 / 60.0);
            sum += world.bodies.get(handle).unwrap().linvel().y;
        }
        sum / 30.0
    }

    #[test]
    fn a_spinning_plus_of_flat_plates_is_a_propeller() {
        let still = koper_rotor(0.0, 0.0);
        let forward = koper_rotor(20.0, 0.0);
        let backward = koper_rotor(-20.0, 0.0);
        let faster = koper_rotor(30.0, 0.0);
        assert!(forward > still + 0.2, "spinning plus gave no thrust: still={still} spun={forward}");
        // no motor ever drove it, so the blades keep their default twist: dragged round the wrong way
        // it pushes back in, like a real prop. a motor-driven reverse re-twists (counter pair test)
        assert!(backward < still - 0.2, "turned against its twist it should push in: still={still} back={backward}");
        assert!(((forward - still) + (backward - still)).abs() < 0.05,
            "one spin direction pushes harder than the other: up={forward} down={backward}");
        assert!(faster > forward, "spinning faster should push harder: 20={forward} 30={faster}");
        let paddles = koper_rotor(20.0, 90.0);
        assert!((paddles - still).abs() < 0.2, "plates standing across the spin are not blades: {paddles}");
    }

    #[test]
    fn proper_wings_keep_gliding_without_becoming_a_motor() {
        let low = glider(AeroMode::Low);
        let correct = glider(AeroMode::Correct);
        let extreme = glider(AeroMode::Extreme);
        assert!(low.0 > 10.0 && low.1 < correct.1, "Low lost its basic arcade glide: {low:?}");
        assert!(correct.0 > 28.0, "Correct stopped instead of gliding: {correct:?}");
        assert!(extreme.0 > 28.0, "Extreme stopped instead of gliding: {extreme:?}");
        // energy height: a glider launched below flying speed trades height for speed first, so judge
        // height + v²/2g, not height. the start is y=100 at ~16 m/s
        let energy = |g: (f32, f32, f32)| g.1 + g.2 * g.2 / (2.0 * 28.0);
        let start = 100.0 + (1.0f32 + 256.0) / (2.0 * 28.0);
        for (name, g) in [("Correct", correct), ("Extreme", extreme)] {
            let lost = start - energy(g);
            assert!(lost > 0.0, "{name} glider manufactured energy: {g:?}");
            assert!(g.0 / lost > 2.5, "{name} glide ratio is still a brick: {g:?}, lost {lost}");
        }
        assert!((correct.0 - extreme.0).abs() > 1.0 || (correct.1 - extreme.1).abs() > 1.0,
            "Correct and Extreme collapsed into the same model");
    }

    // ── the vehicle toolkit ──────────────────────────────────────────────────

    fn lone_block(gravity: Vec3, mass: f32) -> KhysWorld {
        let mut world = KhysWorld::new();
        world.gravity = gravity;
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0]], &[mass, mass], 0,
            [0.0, 100.0, 0.0]);
        world
    }

    fn y_of(world: &KhysWorld) -> f32 { world.bodies.get(world.kontras[&1].body).unwrap().translation().y }

    #[test]
    fn a_held_push_keeps_working_every_step() {
        let mut world = lone_block(Vec3::new(0.0, -28.0, 0.0), 1.0);
        // exactly the weight: a one-step force would let it fall at 2/3 g, a held one hovers
        world.process_cmd(PhysicsCmd::SetHeldPush { id: 1, force: [0.0, 2.0 * 28.0, 0.0], torque: [0.0; 3] });
        for _ in 0..120 { world.step(1.0 / 60.0); }
        let y = y_of(&world);
        assert!((y - 100.0).abs() < 0.5, "held push should hover the body, it went to y={y}");
        world.process_cmd(PhysicsCmd::SetHeldPush { id: 1, force: [0.0; 3], torque: [0.0; 3] });
        for _ in 0..60 { world.step(1.0 / 60.0); }
        assert!(y_of(&world) < 95.0, "clearing the push should let it fall");
    }

    static PUSH_CALLS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
    extern "C" fn hover_pusher(id: i64, state: *const f32, out: *mut f32) {
        if id != 4242 { return; }
        PUSH_CALLS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let s = unsafe { std::slice::from_raw_parts(state, BODY_STATE_LEN) };
        let o = unsafe { std::slice::from_raw_parts_mut(out, 6) };
        // cancel gravity and brake the vertical speed with fresh velocity — a servo that only works
        // if the state really is this step's
        let mass = s[19];
        o[1] = -s[30] * mass - s[8] * mass * 10.0;
    }

    #[test]
    fn the_pusher_sees_fresh_state_and_holds_a_body() {
        let mut world = KhysWorld::new();
        world.gravity = Vec3::new(0.0, -28.0, 0.0);
        world.spawn_kontraktion_from_offsets(4242, vec![[0.0, 0.0, 0.0]], &[3.0], 0, [0.0, 100.0, 0.0]);
        PUSHER.store(hover_pusher as usize, std::sync::atomic::Ordering::Release);
        world.process_cmd(PhysicsCmd::SetPushed { id: 4242, on: true });
        for _ in 0..180 { world.step(1.0 / 60.0); }
        let y = world.bodies.get(world.kontras[&4242].body).unwrap().translation().y;
        assert!(PUSH_CALLS.load(std::sync::atomic::Ordering::Relaxed) >= 180, "pusher not called every step");
        assert!((y - 100.0).abs() < 0.5, "pusher failed to hold the body, y={y}");
    }

    #[test]
    fn an_angular_impulse_spins_without_moving() {
        let mut world = lone_block(Vec3::ZERO, 1.0);
        world.process_cmd(PhysicsCmd::ApplyAngularImpulse { id: 1, imp: [0.0, 2.0, 0.0] });
        world.step(1.0 / 60.0);
        let b = world.bodies.get(world.kontras[&1].body).unwrap();
        assert!(b.angvel().y > 0.5, "no spin: {:?}", b.angvel());
        assert!(b.linvel().length() < 1e-3, "spin kick moved the body: {:?}", b.linvel());
    }

    #[test]
    fn body_state_reports_mass_com_and_inertia() {
        let mut world = lone_block(Vec3::new(0.0, -28.0, 0.0), 2.0);
        world.step(1.0 / 60.0);
        let snap = world.build_snapshot();
        let s = snap.kontras[&1].state;
        assert!((s[19] - 4.0).abs() < 1e-3, "mass {}", s[19]);
        // two equal blocks at x=0 and x=1: COM halfway
        assert!((s[16] - 0.5).abs() < 1e-3, "local com x {}", s[16]);
        // about the x axis each cube is m/6, about y/z the pair adds m*0.5^2 each
        assert!((s[20] - 2.0 * 2.0 / 6.0).abs() < 1e-2, "Ixx {}", s[20]);
        assert!((s[24] - (2.0 * 2.0 / 6.0 + 4.0 * 0.25)).abs() < 1e-2, "Iyy {}", s[24]);
        assert!((s[30] + 28.0).abs() < 1e-3, "gravity {}", s[30]);
    }

    #[test]
    fn gravity_scale_zero_floats_in_air() {
        let mut world = lone_block(Vec3::new(0.0, -28.0, 0.0), 1.0);
        world.process_cmd(PhysicsCmd::SetGravityScale { id: 1, scale: 0.0 });
        for _ in 0..60 { world.step(1.0 / 60.0); }
        assert!((y_of(&world) - 100.0).abs() < 0.05);
    }

    #[test]
    fn buoyancy_scale_zero_sinks_a_floater() {
        let mut world = KhysWorld::new();
        flood(&mut world, 0, 40);
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0]], &[0.5], 0, [8.0, 38.0, 8.0]);
        world.process_cmd(PhysicsCmd::SetBuoyancyScale { id: 1, scale: 0.0 });
        for _ in 0..600 { world.step(1.0 / 60.0); }
        assert!(y_of(&world) < 34.0, "a zero buoyancy block still floats at {}", y_of(&world));
    }

    #[test]
    fn block_mass_can_change_in_place() {
        let mut world = lone_block(Vec3::ZERO, 1.0);
        world.process_cmd(PhysicsCmd::SetBlockMass { id: 1, offset: [1.0, 0.0, 0.0], mass: 9.0 });
        world.step(1.0 / 60.0);
        let s = world.build_snapshot().kontras[&1].state;
        assert!((s[19] - 10.0).abs() < 1e-3, "mass after change {}", s[19]);
        assert!((s[16] - 0.9).abs() < 1e-3, "com should follow the heavy block, {}", s[16]);
    }
}

#[cfg(test)]
mod koper_plane_tests {
    use super::*;

    fn plate(off: Vec3, normal: Vec3) -> AeroSurface {
        AeroSurface { off, normal, area: 1.0, cd: 0.8, cl: 2.0, buoy: 0.0, chunky: false }
    }

    // a plus of four flat plates on its own body, jointed to `chassis` at `at` spinning about `axis`
    fn prop(world: &mut KhysWorld, id: i64, chassis: i64, chassis_pos: Vec3, at: Vec3, axis: Vec3, joint: i64, vel: f32) {
        let (u, v) = if axis.x.abs() > 0.5 { (Vec3::Y, Vec3::Z) } else if axis.y.abs() > 0.5 { (Vec3::X, Vec3::Z) } else { (Vec3::X, Vec3::Y) };
        let arms = [u, -u, v, -v];
        let offsets: Vec<[f32; 3]> = arms.iter().map(|a| [a.x, a.y, a.z]).collect();
        let centre = chassis_pos + at + axis;
        world.spawn_kontraktion_from_offsets(id, offsets, &[0.2, 0.2, 0.2, 0.2], 0, [centre.x, centre.y, centre.z]);
        let k = world.kontras.get_mut(&id).unwrap();
        k.aero_mode = AeroMode::Correct;
        for a in arms { k.aero.push(plate(a, axis)); }
        world.process_cmd(PhysicsCmd::CreateRevoluteJoint {
            joint_id: joint, a: chassis, b: id,
            anchor_a: [at.x + axis.x * 0.5, at.y + axis.y * 0.5, at.z + axis.z * 0.5],
            anchor_b: [-axis.x * 0.5, -axis.y * 0.5, -axis.z * 0.5],
            axis: [axis.x, axis.y, axis.z],
        });
        world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: joint, target_vel: vel, max_force: 4000.0 });
    }

    // fuselage along +z (nose at +z), wing at z=0, tailplane at z=-4
    fn airframe(world: &mut KhysWorld, pos: Vec3) { airframe_with(world, pos, true) }

    fn airframe_with(world: &mut KhysWorld, pos: Vec3, tail: bool) {
        let mut offsets = Vec::new();
        let mut masses = Vec::new();
        for z in -4..=3 { offsets.push([0.0, 0.0, z as f32]); masses.push(1.0); }
        for x in [-4, -3, -2, -1, 1, 2, 3, 4] { offsets.push([x as f32, 0.0, 0.0]); masses.push(0.3); }
        for x in [-1, 1] { offsets.push([x as f32, 0.0, -4.0]); masses.push(0.3); }
        world.spawn_kontraktion_from_offsets(1, offsets, &masses, 0, [pos.x, pos.y, pos.z]);
        let k = world.kontras.get_mut(&1).unwrap();
        k.aero_mode = AeroMode::Correct;
        for x in -4..=4 { k.aero.push(plate(Vec3::new(x as f32, 0.0, 0.0), Vec3::Y)); }
        if tail { for x in -1..=1 { k.aero.push(plate(Vec3::new(x as f32, 0.0, -4.0), Vec3::Y)); } }
    }

    fn body(world: &KhysWorld, id: i64) -> &RigidBody { world.bodies.get(world.kontras[&id].body).unwrap() }

    // two props on one airframe, one engine reversed to cancel the torque: both still pull forward,
    // so it flies straight instead of yawing off sideways
    #[test]
    fn counter_rotating_props_both_pull_forward() {
        let mut world = KhysWorld::new();
        let pos = Vec3::new(0.0, 150.0, 0.0);
        world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 0.0, -1.0]],
            &[1.0, 1.0, 1.0], 0, [pos.x, pos.y, pos.z]);
        prop(&mut world, 2, 1, pos, Vec3::new(-2.0, 0.0, 1.0), Vec3::Z, 10, 60.0);
        prop(&mut world, 3, 1, pos, Vec3::new(2.0, 0.0, 1.0), Vec3::Z, 11, -60.0);
        // a bare stick with props in front and no tail loops soon after, like a real one would
        for _ in 0..12 { world.step(1.0 / 60.0); }
        let b = body(&world, 1);
        let r = b.rotation();
        let nose = Quat::from_xyzw(r.x, r.y, r.z, r.w) * Vec3::Z;
        assert!(b.linvel().z > 15.0, "the pair did not pull forward: {:?}", b.linvel());
        assert!(b.linvel().x.abs() < 1.0 && nose.z > 0.98, "went sideways: vel={:?} nose={nose:?}", b.linvel());
    }

    #[test]
    fn koper_big_prop_probe() {
        let mut world = KhysWorld::new();
        let pos = Vec3::new(0.0, 150.0, 0.0);
        let mut chassis = Vec::new();
        for x in -6..=6 { for z in -5..=4 { chassis.push([x as f32, 0.0, z as f32]); } }
        let masses = vec![1.0; chassis.len()];
        world.spawn_kontraktion_from_offsets(1, chassis, &masses, 0, [pos.x, pos.y, pos.z]);
        for (id, x, vel, joint) in [(4i64, -5.0f32, 88.0f32, 3i64), (5, 6.0, -88.0, 4)] {
            let axis = Vec3::new(0.0, 0.0, -1.0);
            let mut offsets = vec![[0.0, 0.0, 0.0]];
            let mut arms = Vec::new();
            for r in 1..=3 { for a in [Vec3::X, -Vec3::X, Vec3::Y, -Vec3::Y] { let o = a * r as f32 + axis; offsets.push([o.x, o.y, o.z]); arms.push(o); } }
            let centre = pos + Vec3::new(x, 2.0, -6.0);
            let m = vec![0.2; offsets.len()];
            world.spawn_kontraktion_from_offsets(id, offsets, &m, 0, [centre.x, centre.y, centre.z]);
            let k = world.kontras.get_mut(&id).unwrap();
            k.aero_mode = AeroMode::Correct;
            for a in arms { k.aero.push(plate(a, Vec3::Z)); }
            world.process_cmd(PhysicsCmd::CreateRevoluteJoint { joint_id: joint, a: 1, b: id,
                anchor_a: [x, 2.0, -5.5], anchor_b: [0.0, 0.0, 0.5], axis: [0.0, 0.0, -1.0] });
            world.process_cmd(PhysicsCmd::JointSetMotor { joint_id: joint, target_vel: vel, max_force: 20000.0 });
        }
        for i in 0..300 {
            world.step(1.0 / 60.0);
            if i % 60 == 59 {
                let snap = world.build_snapshot();
                let b = body(&world, 1);
                eprintln!("KOPERBIG t={} j3={:?} j4={:?} vel={:?} ang={:?}", (i + 1) / 60, snap.joints.get(&3), snap.joints.get(&4), b.linvel(), b.angvel());
            }
        }
    }

    // a prop built from full blocks (a cube has no face of its own) pulls along its axle, both
    // spin directions, instead of paddling against the motor
    #[test]
    fn a_prop_of_full_blocks_pulls_along_its_axle() {
        for vel in [88.0f32, -88.0] {
            let mut world = KhysWorld::new();
            let pos = Vec3::new(0.0, 150.0, 0.0);
            world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 0.0, -1.0]],
                &[3.0, 3.0, 3.0], 0, [pos.x, pos.y, pos.z]);
            prop(&mut world, 2, 1, pos, Vec3::new(0.0, 0.0, 1.0), Vec3::Z, 10, vel);
            for s in world.kontras.get_mut(&2).unwrap().aero.iter_mut() { s.chunky = true; s.normal = Vec3::Y; }
            for _ in 0..30 { world.step(1.0 / 60.0); }
            let spin = world.build_snapshot().joints[&10][1];
            let v = body(&world, 1).linvel();
            assert!(spin.abs() > 60.0, "cube prop could not spin up: {spin}");
            assert!(v.z > 3.0, "cube prop spun {vel} did not pull along its axle: {v:?}");
        }
    }

    // the same prop under extreme aero. the rotor rules lived only in correct mode, so under extreme
    // (what koper plays on) a prop of full blocks was a stack of plates facing up: no pull
    #[test]
    fn a_prop_of_full_blocks_pulls_in_extreme_mode_too() {
        for vel in [88.0f32, -88.0] {
            let mut world = KhysWorld::new();
            let pos = Vec3::new(0.0, 150.0, 0.0);
            world.spawn_kontraktion_from_offsets(1, vec![[0.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 0.0, -1.0]],
                &[3.0, 3.0, 3.0], 0, [pos.x, pos.y, pos.z]);
            prop(&mut world, 2, 1, pos, Vec3::new(0.0, 0.0, 1.0), Vec3::Z, 10, vel);
            for s in world.kontras.get_mut(&2).unwrap().aero.iter_mut() { s.chunky = true; s.normal = Vec3::Y; }
            for id in [1, 2] { world.process_cmd(PhysicsCmd::SetAeroMode { id, mode: AeroMode::Extreme }); }
            for _ in 0..30 { world.step(1.0 / 60.0); }
            let spin = world.build_snapshot().joints[&10][1];
            let v = body(&world, 1).linvel();
            assert!(spin.abs() > 60.0, "cube prop could not spin up under extreme: {spin}");
            assert!(v.z > 3.0, "cube prop spun {vel} did not pull along its axle under extreme: {v:?}");
        }
    }

    // a plain block plane: wing on the centre, flat tailplane and a fin at the back, two counter
    // props. it has to keep flying level on its own, not dive, loop or tumble
    #[test]
    fn a_block_plane_with_a_tail_flies_level() {
        let mut world = KhysWorld::new();
        let pos = Vec3::new(0.0, 150.0, 0.0);
        airframe(&mut world, pos);
        world.kontras.get_mut(&1).unwrap().aero.push(plate(Vec3::new(0.0, 1.0, -4.0), Vec3::X));
        prop(&mut world, 2, 1, pos, Vec3::new(-2.0, 0.0, 1.0), Vec3::Z, 10, 60.0);
        prop(&mut world, 3, 1, pos, Vec3::new(2.0, 0.0, 1.0), Vec3::Z, 11, -60.0);
        let h = world.kontras[&1].body;
        world.bodies.get_mut(h).unwrap().set_linvel(Vec3::new(0.0, 0.0, 20.0), true);
        for i in 0..600 {
            world.step(1.0 / 60.0);
            let b = body(&world, 1);
            let r = b.rotation();
            let nose = Quat::from_xyzw(r.x, r.y, r.z, r.w) * Vec3::Z;
            assert!(nose.z > 0.9, "lost its heading or flipped at step {i}: nose={nose:?}");
            assert!((b.translation().y - 150.0).abs() < 12.0, "could not hold height at step {i}: {:?}", b.translation());
        }
        assert!(body(&world, 1).linvel().z > 25.0, "props could not get it to flying speed");
    }
}
