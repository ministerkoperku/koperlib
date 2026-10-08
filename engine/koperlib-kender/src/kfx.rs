use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct KfxEndpoint {
    pub sx: f32,
    pub sy: f32,
    pub sz: f32,
    pub ex: f32,
    pub ey: f32,
    pub ez: f32,
    pub born_ticks: f32,
}

pub struct KfxProgram {
    pub ops: Vec<f32>,
    pub endpoint: KfxEndpoint,
    pub batch: Vec<f32>,
}

const GRAPH_MAGIC: &[u8; 4] = b"KFX2";
const GRAPH_MAJOR: u16 = 2;
const GRAPH_HEADER_BYTES: usize = 64;
const GRAPH_NODE_BYTES: usize = 32;
const GRAPH_MAX_NODES: usize = 1024;
const GRAPH_MAX_CONSTANTS: usize = 32_768;
const GRAPH_MAX_PARTICLES: usize = 4096;
const GRAPH_GLOBAL_PARTICLES: usize = 65_536;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GraphOverflow {
    ScaleRate = 0,
    DropOldest = 1,
    SkipDecorative = 2,
    Reject = 3,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GraphNode {
    pub stable_id: u32,
    pub opcode: u16,
    pub flags: u16,
    pub input_a: u32,
    pub input_b: u32,
    pub property_offset: u32,
    pub event_offset: u32,
    pub cost: u32,
}

impl GraphNode {
    pub fn decorative(&self) -> bool { self.flags & 1 != 0 }
}

#[derive(Clone, Debug, PartialEq)]
pub struct GraphProgram {
    pub graph_hash: u64,
    pub nodes: Vec<GraphNode>,
    pub constants: Vec<f32>,
    pub expression_count: u32,
    pub material_count: u32,
    pub max_particles: u32,
    pub max_lifetime: u32,
    pub overflow: GraphOverflow,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GraphBatch {
    pub particles: Vec<[f32; 10]>,
    pub core_count: usize,
    pub decorative_count: usize,
}

impl GraphProgram {
    pub fn decode(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() < GRAPH_HEADER_BYTES { return Err("truncated KFX2 header".into()); }
        if &bytes[0..4] != GRAPH_MAGIC { return Err("bad KFX2 magic".into()); }
        let major = read_u16(bytes, 4)?;
        if major != GRAPH_MAJOR { return Err(format!("unsupported KFX graph major {major}")); }
        let graph_hash = read_u64(bytes, 8)?;
        let node_count = read_u32(bytes, 16)? as usize;
        let constant_count = read_u32(bytes, 20)? as usize;
        let expression_count = read_u32(bytes, 24)?;
        let material_count = read_u32(bytes, 28)?;
        let max_particles = read_u32(bytes, 32)?;
        let max_lifetime = read_u32(bytes, 36)?;
        let overflow = match bytes[40] {
            0 => GraphOverflow::ScaleRate,
            1 => GraphOverflow::DropOldest,
            2 => GraphOverflow::SkipDecorative,
            3 => GraphOverflow::Reject,
            value => return Err(format!("unknown KFX overflow policy {value}")),
        };
        let nodes_offset = read_u32(bytes, 44)? as usize;
        let constants_offset = read_u32(bytes, 48)? as usize;
        let total_len = read_u32(bytes, 52)? as usize;
        if total_len != bytes.len() { return Err("KFX2 byte length mismatch".into()); }
        if node_count > GRAPH_MAX_NODES || constant_count > GRAPH_MAX_CONSTANTS {
            return Err("KFX2 table exceeds native hard cap".into());
        }
        if max_particles == 0 || max_particles as usize > GRAPH_MAX_PARTICLES || max_lifetime == 0 {
            return Err("KFX2 budget exceeds native hard cap".into());
        }
        let nodes_end = nodes_offset.checked_add(node_count.checked_mul(GRAPH_NODE_BYTES)
            .ok_or("KFX2 node table overflow")?).ok_or("KFX2 node table overflow")?;
        let constants_end = constants_offset.checked_add(constant_count.checked_mul(4)
            .ok_or("KFX2 constant table overflow")?).ok_or("KFX2 constant table overflow")?;
        if nodes_offset < GRAPH_HEADER_BYTES || nodes_end > bytes.len()
            || constants_offset < nodes_end || constants_end != bytes.len() {
            return Err("KFX2 invalid table offsets".into());
        }
        let mut nodes = Vec::with_capacity(node_count);
        for index in 0..node_count {
            let at = nodes_offset + index * GRAPH_NODE_BYTES;
            let node = GraphNode {
                stable_id: read_u32(bytes, at)?,
                opcode: read_u16(bytes, at + 4)?,
                flags: read_u16(bytes, at + 6)?,
                input_a: read_u32(bytes, at + 8)?,
                input_b: read_u32(bytes, at + 12)?,
                property_offset: read_u32(bytes, at + 16)?,
                event_offset: read_u32(bytes, at + 20)?,
                cost: read_u32(bytes, at + 24)?,
            };
            if node.property_offset as usize > constant_count {
                return Err(format!("KFX2 node {} property offset out of range", node.stable_id));
            }
            nodes.push(node);
        }
        let mut constants = Vec::with_capacity(constant_count);
        for at in (constants_offset..constants_end).step_by(4) {
            let value = f32::from_bits(read_u32(bytes, at)?);
            if !value.is_finite() { return Err("KFX2 contains non-finite constant".into()); }
            constants.push(value);
        }
        let declared: usize = nodes.iter().map(|node| node.cost as usize).sum();
        if overflow == GraphOverflow::Reject && declared > max_particles as usize {
            return Err("KFX2 declared cost exceeds REJECT budget".into());
        }
        Ok(Self { graph_hash, nodes, constants, expression_count, material_count,
            max_particles, max_lifetime, overflow })
    }

    #[cfg(test)]
    pub fn encode(&self) -> Vec<u8> {
        let nodes_offset = GRAPH_HEADER_BYTES;
        let constants_offset = nodes_offset + self.nodes.len() * GRAPH_NODE_BYTES;
        let total_len = constants_offset + self.constants.len() * 4;
        let mut bytes = vec![0u8; total_len];
        bytes[0..4].copy_from_slice(GRAPH_MAGIC);
        write_u16(&mut bytes, 4, GRAPH_MAJOR);
        write_u16(&mut bytes, 6, 0);
        write_u64(&mut bytes, 8, self.graph_hash);
        write_u32(&mut bytes, 16, self.nodes.len() as u32);
        write_u32(&mut bytes, 20, self.constants.len() as u32);
        write_u32(&mut bytes, 24, self.expression_count);
        write_u32(&mut bytes, 28, self.material_count);
        write_u32(&mut bytes, 32, self.max_particles);
        write_u32(&mut bytes, 36, self.max_lifetime);
        bytes[40] = self.overflow as u8;
        write_u32(&mut bytes, 44, nodes_offset as u32);
        write_u32(&mut bytes, 48, constants_offset as u32);
        write_u32(&mut bytes, 52, total_len as u32);
        for (index, node) in self.nodes.iter().enumerate() {
            let at = nodes_offset + index * GRAPH_NODE_BYTES;
            write_u32(&mut bytes, at, node.stable_id);
            write_u16(&mut bytes, at + 4, node.opcode);
            write_u16(&mut bytes, at + 6, node.flags);
            write_u32(&mut bytes, at + 8, node.input_a);
            write_u32(&mut bytes, at + 12, node.input_b);
            write_u32(&mut bytes, at + 16, node.property_offset);
            write_u32(&mut bytes, at + 20, node.event_offset);
            write_u32(&mut bytes, at + 24, node.cost);
        }
        for (index, value) in self.constants.iter().enumerate() {
            write_u32(&mut bytes, constants_offset + index * 4, value.to_bits());
        }
        bytes
    }

    #[cfg(test)]
    pub fn evaluate(&self, cast_seed: u64, requested_budget: usize) -> GraphBatch {
        self.evaluate_at(cast_seed, requested_budget, 0.0, 1.0)
    }

    pub fn evaluate_at(&self, cast_seed: u64, requested_budget: usize, age: f32, endpoint_length: f32) -> GraphBatch {
        let budget = requested_budget.min(self.max_particles as usize).min(GRAPH_MAX_PARTICLES);
        let core_requested: usize = self.nodes.iter().filter(|node| !node.decorative())
            .map(|node| node.cost as usize).sum();
        let decorative_requested: usize = self.nodes.iter().filter(|node| node.decorative())
            .map(|node| node.cost as usize).sum();
        let (core_count, decorative_count) = match self.overflow {
            GraphOverflow::ScaleRate => {
                let total = core_requested + decorative_requested;
                if total <= budget { (core_requested, decorative_requested) } else {
                    let scale = budget as f64 / total.max(1) as f64;
                    let core = ((core_requested as f64 * scale).floor() as usize).min(budget);
                    let decorative = ((decorative_requested as f64 * scale).floor() as usize)
                        .min(budget.saturating_sub(core));
                    (core + budget.saturating_sub(core + decorative), decorative)
                }
            }
            GraphOverflow::Reject if core_requested + decorative_requested > budget => (0, 0),
            GraphOverflow::DropOldest | GraphOverflow::SkipDecorative | GraphOverflow::Reject => {
                let core = core_requested.min(budget);
                (core, decorative_requested.min(budget.saturating_sub(core)))
            }
        };
        let mut particles = Vec::with_capacity(core_count + decorative_count);
        let mut core_left = core_count;
        let mut decorative_left = decorative_count;
        for node in &self.nodes {
            let available = if node.decorative() { &mut decorative_left } else { &mut core_left };
            let count = (*available).min(node.cost as usize);
            *available -= count;
            for serial in 0..count {
                let key = cast_seed ^ (node.stable_id as u64).rotate_left(17) ^ serial as u64;
                particles.push(self.particle(node, serial, count, key, age, endpoint_length));
            }
        }
        GraphBatch { particles, core_count, decorative_count }
    }

    fn particle(&self, node: &GraphNode, serial: usize, count: usize, key: u64,
                age: f32, endpoint_length: f32) -> [f32; 10] {
        let p = |index: usize, fallback: f32| self.constants
            .get(node.property_offset as usize + index).copied().unwrap_or(fallback);
        let from = p(0, 0.0);
        let to = p(1, self.max_lifetime as f32);
        let progress = if to <= from { (age >= from) as u8 as f32 }
            else { ((age - from) / (to - from)).clamp(0.0, 1.0) };
        let radius = lerp(p(2, 1.0), p(3, p(2, 1.0)), progress);
        let size = p(5, 0.05).max(0.001);
        let spin = p(7, 0.0).to_radians() * age;
        let wobble = p(8, 0.0) * random_signed(key.wrapping_add(7));
        let depth = p(9, 0.0);
        let lane = serial as f32 / count.max(1) as f32;
        let angle = lane * std::f32::consts::TAU + spin + random_signed(key) * 0.08;
        let (x, y, z) = match node.opcode {
            1 => (angle.cos() * (radius + wobble), depth, angle.sin() * (radius + wobble)),
            6 => {
                let r = radius * progress;
                (angle.cos() * (r + wobble), depth * (progress - 0.5), angle.sin() * (r + wobble))
            }
            8 => {
                let turns = p(10, 2.0).abs().max(1.0);
                let a = lane * std::f32::consts::TAU * turns + spin;
                (a.cos() * radius, (lane - 0.5) * depth.max(1.0), a.sin() * radius)
            }
            5 => {
                let r = p(4, 0.08) * (0.35 + random01(key) * 0.65);
                (angle.cos() * r, lane * endpoint_length, angle.sin() * r)
            }
            9 => (angle.sin() * p(4, 0.12), lane * endpoint_length,
                (angle * 0.7).cos() * p(4, 0.12) * 0.35),
            10 => {
                let tail = 1.0 - lane;
                (random_signed(key) * p(4, 0.08) * tail, tail * endpoint_length,
                    random_signed(key.wrapping_add(31)) * p(4, 0.08) * tail)
            }
            2 => {
                // Star polygon {n/k}: lane walks the edge list, t walks one edge.
                let points = p(16, 5.0).round().clamp(3.0, 16.0);
                let skip = p(17, 2.0).round().clamp(1.0, (points * 0.5).floor().max(1.0));
                let rot = spin - std::f32::consts::FRAC_PI_2;
                let step = std::f32::consts::TAU / points;
                let walk = lane * points;
                let edge = walk.floor();
                let t = walk - edge;
                let a0 = rot + edge * step;
                let a1 = rot + (edge + skip) * step;
                (lerp(a0.cos() * radius, a1.cos() * radius, t), depth,
                    lerp(a0.sin() * radius, a1.sin() * radius, t))
            }
            4 => (0.0, depth, 0.0),
            7 => (random_signed(key) * p(8, 0.0), lane * endpoint_length,
                random_signed(key.wrapping_add(31)) * p(8, 0.0)),
            11 => (0.0, endpoint_length * 0.5, 0.0),
            12 => (angle.cos() * p(5, radius), endpoint_length, angle.sin() * p(5, radius)),
            _ => (random_signed(key), random_signed(key.wrapping_add(0x9E3779B97F4A7C15)),
                random_signed(key.wrapping_add(0xD1B54A32D192ED03))),
        };
        [x, y, z, size, p(12, 1.0), p(13, 1.0), p(14, 1.0), p(15, 1.0) * p(6, 1.0),
            p(11, node.opcode as f32), random01(key)]
    }

    #[cfg(test)]
    fn fixture(core: u32, decorative: u32) -> Self {
        let mut nodes = Vec::new();
        if core > 0 { nodes.push(GraphNode { stable_id: 11, opcode: 1, flags: 0, input_a: 0,
            input_b: 0, property_offset: 0, event_offset: 0, cost: core }); }
        if decorative > 0 { nodes.push(GraphNode { stable_id: 29, opcode: 1, flags: 1, input_a: 0,
            input_b: 0, property_offset: 0, event_offset: 0, cost: decorative }); }
        Self { graph_hash: 0x1234, nodes, constants: Vec::new(), expression_count: 0,
            material_count: 1, max_particles: (core + decorative).max(1), max_lifetime: 40,
            overflow: GraphOverflow::SkipDecorative }
    }
}

fn splitmix64(mut value: u64) -> u64 {
    value = (value ^ (value >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94D049BB133111EB);
    value ^ (value >> 31)
}

fn random01(key: u64) -> f32 { ((splitmix64(key) >> 40) as f32) / 16_777_216.0 }
fn random_signed(key: u64) -> f32 { random01(key) * 2.0 - 1.0 }

fn read_u16(bytes: &[u8], at: usize) -> Result<u16, String> {
    let raw = bytes.get(at..at + 2).ok_or("truncated KFX2 u16")?;
    Ok(u16::from_le_bytes([raw[0], raw[1]]))
}
fn read_u32(bytes: &[u8], at: usize) -> Result<u32, String> {
    let raw = bytes.get(at..at + 4).ok_or("truncated KFX2 u32")?;
    Ok(u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]))
}
fn read_u64(bytes: &[u8], at: usize) -> Result<u64, String> {
    let raw = bytes.get(at..at + 8).ok_or("truncated KFX2 u64")?;
    Ok(u64::from_le_bytes(raw.try_into().map_err(|_| "truncated KFX2 u64")?))
}
#[cfg(test)]
fn write_u16(bytes: &mut [u8], at: usize, value: u16) { bytes[at..at + 2].copy_from_slice(&value.to_le_bytes()); }
#[cfg(test)]
fn write_u32(bytes: &mut [u8], at: usize, value: u32) { bytes[at..at + 4].copy_from_slice(&value.to_le_bytes()); }
#[cfg(test)]
fn write_u64(bytes: &mut [u8], at: usize, value: u64) { bytes[at..at + 8].copy_from_slice(&value.to_le_bytes()); }

static KFX: OnceLock<Mutex<HashMap<i64, KfxProgram>>> = OnceLock::new();

#[derive(Clone, Copy)]
struct GraphInstance {
    graph_hash: u64,
    seed: u64,
    endpoint: KfxEndpoint,
    particle_budget: usize,
}

static GRAPH_PROGRAMS: OnceLock<Mutex<HashMap<u64, GraphProgram>>> = OnceLock::new();
static GRAPH_INSTANCES: OnceLock<Mutex<HashMap<i64, GraphInstance>>> = OnceLock::new();

fn graph_programs() -> &'static Mutex<HashMap<u64, GraphProgram>> {
    GRAPH_PROGRAMS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn graph_instances() -> &'static Mutex<HashMap<i64, GraphInstance>> {
    GRAPH_INSTANCES.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn upload_graph(bytes: &[u8]) -> Result<u64, String> {
    let program = GraphProgram::decode(bytes)?;
    let hash = program.graph_hash;
    graph_programs().lock().unwrap().insert(hash, program);
    Ok(hash)
}

pub fn spawn_graph(id: i64, graph_hash: u64, seed: u64, endpoint: KfxEndpoint, particle_budget: usize) -> bool {
    if id == 0 || !graph_programs().lock().unwrap().contains_key(&graph_hash) { return false; }
    graph_instances().lock().unwrap().insert(id, GraphInstance {
        graph_hash, seed, endpoint,
        particle_budget: particle_budget.clamp(1, GRAPH_MAX_PARTICLES),
    });
    true
}

fn store() -> &'static Mutex<HashMap<i64, KfxProgram>> {
    KFX.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn upload(id: i64, ops: &[f32], endpoint: KfxEndpoint) {
    store().lock().unwrap().insert(id, KfxProgram { ops: ops.to_vec(), endpoint, batch: Vec::new() });
}

pub fn remove(id: i64) {
    store().lock().unwrap().remove(&id);
    graph_instances().lock().unwrap().remove(&id);
}

pub fn update_endpoint(id: i64, sx: f32, sy: f32, sz: f32, ex: f32, ey: f32, ez: f32) {
    if let Some(program) = store().lock().unwrap().get_mut(&id) {
        program.endpoint.sx = sx;
        program.endpoint.sy = sy;
        program.endpoint.sz = sz;
        program.endpoint.ex = ex;
        program.endpoint.ey = ey;
        program.endpoint.ez = ez;
    }
    if let Some(emitter) = emitters().lock().unwrap().get_mut(&id) {
        emitter.def.sx = sx;
        emitter.def.sy = sy;
        emitter.def.sz = sz;
        emitter.def.ex = ex;
        emitter.def.ey = ey;
        emitter.def.ez = ez;
    }
    if let Some(instance) = graph_instances().lock().unwrap().get_mut(&id) {
        instance.endpoint.sx = sx;
        instance.endpoint.sy = sy;
        instance.endpoint.sz = sz;
        instance.endpoint.ex = ex;
        instance.endpoint.ey = ey;
        instance.endpoint.ez = ez;
    }
}

pub fn clear() {
    store().lock().unwrap().clear();
    graph_instances().lock().unwrap().clear();
    graph_programs().lock().unwrap().clear();
}

pub fn count() -> usize {
    store().lock().unwrap().len() + graph_instances().lock().unwrap().len()
}

pub fn float_count() -> usize {
    store().lock().unwrap().values().map(|program| program.ops.len()).sum()
}

pub fn latest_born_ticks() -> f32 {
    store()
        .lock()
        .unwrap()
        .values()
        .map(|program| program.endpoint.born_ticks)
        .fold(0.0, f32::max)
}

pub fn eval_ptr(id: i64, age_ticks: f32, out_float_count: *mut i32) -> i64 {
    if out_float_count.is_null() { return 0; }
    let mut s = store().lock().unwrap();
    let Some(program) = s.get_mut(&id) else {
        unsafe { *out_float_count = 0; }
        return 0;
    };
    rebuild_batch(program, age_ticks);
    unsafe { *out_float_count = program.batch.len() as i32; }
    if program.batch.is_empty() { 0 } else { program.batch.as_ptr() as i64 }
}

// flat instance buffer for the GPU particle pipeline. 10 floats/particle: xyz, size, rgba, style, seed.
// rebuilds each live program at its own current age, so the draw is fully self-driving on the GPU side.
pub fn gather_instances(now_ticks: f32, cam: [f64; 3]) -> Vec<f32> {
    use rayon::prelude::*;
    let s = store().lock().unwrap();
    // each program evals independently → fan the whole live set across CPU cores, then concat.
    let chunks: Vec<Vec<f32>> = s.par_iter().filter_map(|(_, program)| {
        let age = now_ticks - program.endpoint.born_ticks;
        if age < 0.0 { return None; }
        let mut batch: Vec<f32> = Vec::new();
        eval_into(&program.ops, program.endpoint, age, &mut batch);
        if batch.is_empty() { return None; }
        let sx = program.endpoint.sx as f64;
        let sy = program.endpoint.sy as f64;
        let sz = program.endpoint.sz as f64;
        let mut inst: Vec<f32> = Vec::with_capacity(batch.len());
        for p in batch.chunks_exact(10) {
            inst.push((sx + p[0] as f64 - cam[0]) as f32);
            inst.push((sy + p[1] as f64 - cam[1]) as f32);
            inst.push((sz + p[2] as f64 - cam[2]) as f32);
            inst.push(p[3]);                       // size
            inst.push(p[4]); inst.push(p[5]); inst.push(p[6]); inst.push(p[7]); // rgba
            inst.push(p[8]); inst.push(p[9]);      // procedural GPU shape + stable rotation
        }
        Some(inst)
    }).collect();

    let total: usize = chunks.iter().map(|c| c.len()).sum();
    let mut out = Vec::with_capacity(total);
    for c in &chunks { out.extend_from_slice(c); }
    out.extend(gather_graph_instances(now_ticks, cam));
    out
}

fn gather_graph_instances(now_ticks: f32, cam: [f64; 3]) -> Vec<f32> {
    let programs = graph_programs().lock().unwrap();
    let instances = graph_instances().lock().unwrap();
    let mut ordered: Vec<_> = instances.iter().collect();
    ordered.sort_unstable_by_key(|(handle, _)| **handle);
    let mut remaining = GRAPH_GLOBAL_PARTICLES;
    let mut out = Vec::new();
    for (_, instance) in ordered {
        if remaining == 0 { break; }
        let Some(program) = programs.get(&instance.graph_hash) else { continue; };
        let age = now_ticks - instance.endpoint.born_ticks;
        if age < 0.0 || age > program.max_lifetime as f32 { continue; }
        let budget = instance.particle_budget.min(remaining);
        let dx = instance.endpoint.ex - instance.endpoint.sx;
        let dy = instance.endpoint.ey - instance.endpoint.sy;
        let dz = instance.endpoint.ez - instance.endpoint.sz;
        let endpoint_length = (dx * dx + dy * dy + dz * dz).sqrt();
        let batch = program.evaluate_at(instance.seed, budget, age, endpoint_length);
        remaining = remaining.saturating_sub(batch.particles.len());
        let basis = Basis::from_endpoint(instance.endpoint);
        for particle in batch.particles {
            let (x, y, z) = basis.apply(particle[0], particle[1], particle[2]);
            out.push((instance.endpoint.sx as f64 + x as f64 - cam[0]) as f32);
            out.push((instance.endpoint.sy as f64 + y as f64 - cam[1]) as f32);
            out.push((instance.endpoint.sz as f64 + z as f64 - cam[2]) as f32);
            out.extend_from_slice(&particle[3..10]);
        }
    }
    out
}

fn rebuild_batch(program: &mut KfxProgram, age: f32) {
    program.batch.clear();
    // disjoint field borrows: read ops + endpoint, write batch — no clone
    eval_into(&program.ops, program.endpoint, age, &mut program.batch);
}

// pure eval: ops + endpoint -> 10-float particle batch. no shared state, safe to run per-program in parallel.
fn eval_into(ops: &[f32], endpoint: KfxEndpoint, age: f32, out: &mut Vec<f32>) {
    let basis = Basis::from_endpoint(endpoint);
    let endpoint_length = ((endpoint.ex - endpoint.sx).powi(2)
        + (endpoint.ey - endpoint.sy).powi(2) + (endpoint.ez - endpoint.sz).powi(2)).sqrt();
    for op in ops.chunks_exact(24) {
        let from = op[1];
        let to = op[2];
        let raw_t = if to <= from {
            if age >= from { 1.0 } else { 0.0 }
        } else {
            ((age - from) / (to - from)).clamp(0.0, 1.0)
        };
        if raw_t <= 0.0 && age < from { continue; }
        let progress = ease(raw_t, op[9] as i32);
        let color = [op[14], op[15], op[16], op[17] * op[7]];
        match op[0] as i32 {
            1 => ring_particles(out, &basis, op, progress, age, color),
            2 => pentagram(out, &basis, op, progress, age, color),
            4 => point(out, &basis, op, color),
            5 => beam_particles(out, &basis, op, endpoint_length, color),
            6 => burst_ring(out, &basis, op, progress, age, color),
            7 => stream(out, &basis, op, progress, color),
            8 => spiral(out, &basis, op, progress, age, color),
            9 => ribbon_particles(out, &basis, op, endpoint_length, age, color),
            10 => trail_particles(out, &basis, op, endpoint_length, color),
            11 => mesh_particle(out, &basis, op, endpoint_length, color),
            12 => decal_particles(out, &basis, op, endpoint_length, color),
            _ => {}
        }
    }
}

fn ring_particles(out: &mut Vec<f32>, basis: &Basis, op: &[f32], progress: f32, age: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(1) as usize;
    let visible = ((count as f32 * progress) as usize).clamp(1, count);
    let radius = if op[4] > 0.0 { op[4] } else { 1.0 };
    let size = if op[5] > 0.0 { op[5] } else { 0.035 };
    let style = op[8];
    for i in 0..visible {
        let lane = i as f32 / count as f32;
        let appear = if op[10] as i32 == 1 { (progress * count as f32 - i as f32).clamp(0.0, 1.0) } else { progress };
        let appear = smooth(appear);
        let a = lane * std::f32::consts::TAU + age * 0.017453292 * op[11];
        let wobble = (i as f32 * 1.7 + age * 0.034906584).sin() * op[12];
        push_particle(out, basis, a.cos() * (radius + wobble) * appear,
            (i as f32 * 0.61 + age * 0.017453292).sin() * op[13] * appear,
            a.sin() * (radius + wobble) * appear,
            size * (0.7 + appear * 0.5), color, style, op[18] + i as f32 * 31.0);
    }
}

fn pentagram(out: &mut Vec<f32>, basis: &Basis, op: &[f32], progress: f32, age: f32, color: [f32; 4]) {
    let radius = if op[4] > 0.0 { op[4] } else { 1.0 };
    let total = (op[3] as i32).max(15) as usize;
    let visible = ((total as f32 * progress) as usize).clamp(1, total);
    let size = if op[5] > 0.0 { op[5] } else { 0.035 };
    let style = op[8];
    let rot = age * 0.017453292 * op[11] - std::f32::consts::FRAC_PI_2;
    let mut pts = [[0.0f32; 2]; 5];
    for (i, p) in pts.iter_mut().enumerate() {
        let a = rot + i as f32 * std::f32::consts::TAU / 5.0;
        p[0] = a.cos() * radius;
        p[1] = a.sin() * radius;
    }
    let lines = [[0usize, 2usize], [2, 4], [4, 1], [1, 3], [3, 0]];
    let dots = (total / lines.len()).max(1);
    let mut drawn = 0usize;
    for line in lines {
        for i in 0..dots {
            if drawn >= visible { return; }
            let t = if dots <= 1 { 0.0 } else { i as f32 / (dots - 1) as f32 };
            push_particle(out, basis,
                lerp(pts[line[0]][0], pts[line[1]][0], t),
                op[13],
                lerp(pts[line[0]][1], pts[line[1]][1], t),
                size, color, style, op[18] + drawn as f32 * 13.0);
            drawn += 1;
        }
    }
}

fn point(out: &mut Vec<f32>, basis: &Basis, op: &[f32], color: [f32; 4]) {
        push_particle(out, basis, op[21], op[22], op[23], if op[5] > 0.0 { op[5] } else { 0.1 }, color, op[8], op[18]);
}

fn beam_particles(out: &mut Vec<f32>, basis: &Basis, op: &[f32], length: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(32) as usize;
    let radius = op[6].abs().max(0.025);
    let size = op[5].abs().max(radius * 0.7);
    for i in 0..count {
        let lane = i as f32 / (count - 1).max(1) as f32;
        let a = i as f32 * 2.3999631 + op[18];
        push_particle(out, basis, a.cos() * radius, lane * length, a.sin() * radius,
            size, color, if op[8] > 0.0 { op[8] } else { 7.0 }, op[18] + i as f32 * 19.0);
    }
}

fn ribbon_particles(out: &mut Vec<f32>, basis: &Basis, op: &[f32], length: f32, age: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(16) as usize;
    let width = op[6].abs().max(0.06);
    for i in 0..count {
        let lane = i as f32 / (count - 1).max(1) as f32;
        let wave = (lane * 12.0 + age * 0.08 + op[18]).sin();
        push_particle(out, basis, wave * width, lane * length, wave.cos() * width * 0.3,
            width, color, 7.0, op[18] + i as f32 * 23.0);
    }
}

fn trail_particles(out: &mut Vec<f32>, basis: &Basis, op: &[f32], length: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(12) as usize;
    let size = op[6].abs().max(0.04);
    for i in 0..count {
        let lane = i as f32 / (count - 1).max(1) as f32;
        let mut faded = color;
        faded[3] *= 1.0 - lane * 0.8;
        push_particle(out, basis, 0.0, (1.0 - lane) * length, 0.0,
            size * (1.0 - lane * 0.65), faded, 7.0, op[18] + i as f32 * 29.0);
    }
}

fn mesh_particle(out: &mut Vec<f32>, basis: &Basis, op: &[f32], length: f32, color: [f32; 4]) {
    push_particle(out, basis, op[21], length * 0.5 + op[22], op[23],
        op[5].abs().max(0.2), color, if op[8] > 0.0 { op[8] } else { 5.0 }, op[18]);
}

fn decal_particles(out: &mut Vec<f32>, basis: &Basis, op: &[f32], length: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(16) as usize;
    let radius = op[5].abs().max(op[4].abs()).max(0.4);
    for i in 0..count {
        let a = i as f32 / count as f32 * std::f32::consts::TAU;
        push_particle(out, basis, a.cos() * radius, length, a.sin() * radius,
            radius * 0.08, color, 3.0, op[18] + i as f32 * 11.0);
    }
}

fn burst_ring(out: &mut Vec<f32>, basis: &Basis, op: &[f32], progress: f32, age: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(8) as usize;
    let radius = lerp(op[4].max(0.0), if op[19] > 0.0 { op[19] } else { op[4].max(0.0) + 1.0 }, progress);
    let size = if op[5] > 0.0 { op[5] } else { 0.035 };
    let mut c = color;
    c[3] *= 1.0 - progress * 0.45;
    for i in 0..count {
        let a = i as f32 / count as f32 * std::f32::consts::TAU + age * 0.017453292 * op[11];
        let wave = (progress * 9.0 + i as f32 * 1.31 + op[18]).sin() * op[12];
        push_particle(out, basis, a.cos() * (radius + wave), op[13] * (progress - 0.5), a.sin() * (radius + wave),
            size, c, op[8], op[18] + i as f32 * 17.0);
    }
}

fn stream(out: &mut Vec<f32>, basis: &Basis, op: &[f32], progress: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(2) as usize;
    let visible = ((count as f32 * progress) as usize).clamp(1, count);
    let size = if op[5] > 0.0 { op[5] } else { 0.025 };
    for i in 0..visible {
        let t = i as f32 / (count - 1).max(1) as f32;
        let wob = (i as f32 * 1.43 + op[18]).sin() * op[12];
        let mut c = color;
        c[3] *= 1.0 - t * 0.6;
        push_particle(out, basis, op[21] + wob, op[22] + t * op[20].max(1.0), op[23] + (i as f32 * 1.17 + op[18]).cos() * op[12],
            size * (1.0 - t * 0.35), c, op[8], op[18] + i as f32 * 23.0);
    }
}

fn spiral(out: &mut Vec<f32>, basis: &Basis, op: &[f32], progress: f32, age: f32, color: [f32; 4]) {
    let count = (op[3] as i32).max(8) as usize;
    let visible = ((count as f32 * progress) as usize).clamp(1, count);
    let radius = if op[4] > 0.0 { op[4] } else { 1.0 };
    let height = if op[13] != 0.0 { op[13] } else { 1.5 };
    let turns = op[20].max(1.0);
    let size = if op[5] > 0.0 { op[5] } else { 0.025 };
    for i in 0..visible {
        let t = i as f32 / (count - 1).max(1) as f32;
        let a = t * std::f32::consts::TAU * turns + age * 0.017453292 * op[11];
        let r = if op[10] as i32 == 1 { radius * t } else { radius };
        push_particle(out, basis, a.cos() * r, (t - 0.5) * height, a.sin() * r, size, color, op[8], op[18] + i as f32 * 29.0);
    }
}

fn push_particle(out: &mut Vec<f32>, basis: &Basis, x: f32, y: f32, z: f32,
                 size: f32, color: [f32; 4], style: f32, seed: f32) {
    let (rx, ry, rz) = basis.apply(x, y, z);
    out.extend_from_slice(&[rx, ry, rz, size, color[0], color[1], color[2], color[3], style, seed]);
}

#[derive(Clone, Copy)]
struct Basis {
    side: [f32; 3],
    up: [f32; 3],
    forward: [f32; 3],
}

impl Basis {
    fn from_endpoint(e: KfxEndpoint) -> Self {
        let mut forward = norm([e.ex - e.sx, e.ey - e.sy, e.ez - e.sz]);
        if len2(forward) < 1.0e-5 { forward = [0.0, 0.0, 1.0]; }
        let mut side = cross([0.0, 1.0, 0.0], forward);
        if len2(side) < 1.0e-5 { side = [1.0, 0.0, 0.0]; }
        side = norm(side);
        let up = norm(cross(forward, side));
        Self { side, up, forward }
    }

    fn apply(&self, x: f32, y: f32, z: f32) -> (f32, f32, f32) {
        (
            self.side[0] * x + self.forward[0] * y + self.up[0] * z,
            self.side[1] * x + self.forward[1] * y + self.up[1] * z,
            self.side[2] * x + self.forward[2] * y + self.up[2] * z,
        )
    }
}

fn ease(t: f32, code: i32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    match code {
        0 => t,
        2 => t * t,
        3 => 1.0 - (1.0 - t) * (1.0 - t),
        _ => smooth(t),
    }
}

fn smooth(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn lerp(a: f32, b: f32, t: f32) -> f32 { a + (b - a) * t }
fn len2(v: [f32; 3]) -> f32 { v[0] * v[0] + v[1] * v[1] + v[2] * v[2] }
fn norm(v: [f32; 3]) -> [f32; 3] {
    let len = len2(v).sqrt();
    if len > 1.0e-6 { [v[0] / len, v[1] / len, v[2] / len] } else { v }
}
fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

// ── emitter sim (ported from the java EmitterState) ──────────────────────────────
// stateful particle sim lives here so it ticks across all CPU cores (rayon) with zero per-frame java work.
// java uploads the def once on spawn; rust spawns/integrates/prunes + builds instances each draw.

#[derive(Clone, Copy)]
pub struct EmitterDef {
    pub sx: f32, pub sy: f32, pub sz: f32,
    pub ex: f32, pub ey: f32, pub ez: f32,
    pub color: u32, pub color2: u32,
    pub radius: f32, pub rate: f32, pub burst: i32, pub particle_lifetime: i32,
    pub spread: f32, pub speed: f32, pub gravity: f32, pub drag: f32,
    pub size_end: f32, pub turbulence: f32, pub max_particles: i32,
    pub shape: i32, pub motion: i32,
    pub style: i32,
    pub lifetime: i32, pub fade_in: f32, pub fade_out: f32, pub loop_on: bool,
    pub born_ticks: f32,
}

struct EParticle {
    x: f32, y: f32, z: f32, vx: f32, vy: f32, vz: f32,
    age: f32, life: f32, size: f32, seed: f32, orbit: f32, orbit_radius: f32,
}

struct EmitterRt {
    def: EmitterDef,
    parts: Vec<EParticle>,
    last_age: f32,
    carry: f32,
    burst_done: bool,
    rng: u32,
    collision: Option<CollisionField>,
}

#[derive(Clone)]
struct CollisionField {
    origin: [i32; 3],
    side: usize,
    cells: Vec<u8>,
    response: u8,
    restitution: f32,
    friction: f32,
}

impl CollisionField {
    fn occupied(&self, world: [f32; 3]) -> bool {
        let x = world[0].floor() as i32 - self.origin[0];
        let y = world[1].floor() as i32 - self.origin[1];
        let z = world[2].floor() as i32 - self.origin[2];
        if x < 0 || y < 0 || z < 0 || x >= self.side as i32 || y >= self.side as i32 || z >= self.side as i32 {
            return false;
        }
        self.cells[(y as usize * self.side + z as usize) * self.side + x as usize] & 3 != 0
    }

    fn contact(&self, from: [f32; 3], to: [f32; 3]) -> Option<([f32; 3], [f32; 3])> {
        let delta = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
        let length = len2(delta).sqrt();
        let samples = ((length * 4.0).ceil() as usize).clamp(1, 64);
        let mut previous = from;
        for step in 1..=samples {
            let t = step as f32 / samples as f32;
            let point = [from[0] + delta[0] * t, from[1] + delta[1] * t, from[2] + delta[2] * t];
            if self.occupied(point) {
                let previous_cell = [previous[0].floor(), previous[1].floor(), previous[2].floor()];
                let cell = [point[0].floor(), point[1].floor(), point[2].floor()];
                let mut normal = [previous_cell[0] - cell[0], previous_cell[1] - cell[1], previous_cell[2] - cell[2]];
                if len2(normal) < 0.5 {
                    let axis = if delta[0].abs() >= delta[1].abs() && delta[0].abs() >= delta[2].abs() { 0 }
                        else if delta[1].abs() >= delta[2].abs() { 1 } else { 2 };
                    normal = [0.0; 3];
                    normal[axis] = -delta[axis].signum();
                }
                return Some((previous, norm(normal)));
            }
            previous = point;
        }
        None
    }
}

fn collision_velocity(velocity: [f32; 3], normal: [f32; 3], response: u8,
                      restitution: f32, friction: f32) -> [f32; 3] {
    let inward = velocity[0] * normal[0] + velocity[1] * normal[1] + velocity[2] * normal[2];
    let tangent = [velocity[0] - normal[0] * inward, velocity[1] - normal[1] * inward,
        velocity[2] - normal[2] * inward];
    match response {
        1 => [tangent[0] * (1.0 - friction) - normal[0] * inward * restitution,
            tangent[1] * (1.0 - friction) - normal[1] * inward * restitution,
            tangent[2] * (1.0 - friction) - normal[2] * inward * restitution],
        2 => [tangent[0] * (1.0 - friction), tangent[1] * (1.0 - friction),
            tangent[2] * (1.0 - friction)],
        _ => [0.0; 3],
    }
}

static EMITTERS: OnceLock<Mutex<HashMap<i64, EmitterRt>>> = OnceLock::new();
fn emitters() -> &'static Mutex<HashMap<i64, EmitterRt>> {
    EMITTERS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn emitter_spawn(id: i64, def: EmitterDef) {
    emitters().lock().unwrap().insert(id, EmitterRt {
        def, parts: Vec::new(), last_age: -1.0, carry: 0.0, burst_done: false,
        rng: (id as u32).wrapping_mul(2654435761).max(1) | 1,
        collision: None,
    });
}
pub fn emitter_collision(id: i64, origin: [i32; 3], side: usize, cells: &[u8], response: u8,
                         restitution: f32, friction: f32) -> bool {
    if !(1..=33).contains(&side) || cells.len() != side * side * side || !(1..=4).contains(&response)
        || !restitution.is_finite() || !(0.0..=1.0).contains(&restitution)
        || !friction.is_finite() || !(0.0..=1.0).contains(&friction) { return false; }
    let mut live = emitters().lock().unwrap();
    let Some(emitter) = live.get_mut(&id) else { return false; };
    emitter.collision = Some(CollisionField { origin, side, cells: cells.to_vec(), response, restitution, friction });
    true
}
pub fn emitter_remove(id: i64) { emitters().lock().unwrap().remove(&id); }
pub fn emitter_clear() { emitters().lock().unwrap().clear(); }
pub fn emitter_count() -> usize { emitters().lock().unwrap().len() }

pub fn gather_emitters(now_ticks: f32, cam: [f64; 3]) -> Vec<f32> {
    use rayon::prelude::*;
    let mut s = emitters().lock().unwrap();
    let chunks: Vec<Vec<f32>> = s.par_iter_mut().map(|(_, e)| {
        e.tick(now_ticks);
        let mut out = Vec::with_capacity(e.parts.len() * 10);
        e.gather(cam, &mut out);
        out
    }).collect();
    let total: usize = chunks.iter().map(|c| c.len()).sum();
    let mut out = Vec::with_capacity(total);
    for c in &chunks { out.extend_from_slice(c); }
    out
}

impl EmitterRt {
    fn rnd01(&mut self) -> f32 {
        let mut x = self.rng;
        x ^= x << 13; x ^= x >> 17; x ^= x << 5;
        self.rng = x;
        (x >> 8) as f32 / 16_777_216.0
    }
    fn rnd(&mut self, lo: f32, hi: f32) -> f32 { lo + self.rnd01() * (hi - lo) }

    fn tick(&mut self, now: f32) {
        let age = now - self.def.born_ticks;
        let dt = if self.last_age < 0.0 { 1.0 } else { (age - self.last_age).clamp(0.0, 4.0) };
        self.last_age = age;
        let d = self.def;
        for p in &mut self.parts {
            p.age += dt;
            p.vx += ((p.seed + p.age) * 0.37).sin() * d.turbulence * dt;
            p.vz += ((p.seed - p.age) * 0.31).cos() * d.turbulence * dt;
            if d.motion != 0 { apply_motion(p, &d, dt); }
            p.vy += d.gravity * dt;
            let drag = d.drag.powf(dt);
            p.vx *= drag; p.vy *= drag; p.vz *= drag;
            let previous = [d.sx + p.x, d.sy + p.y, d.sz + p.z];
            let next = [previous[0] + p.vx * dt, previous[1] + p.vy * dt, previous[2] + p.vz * dt];
            if let Some((contact, normal)) = self.collision.as_ref().and_then(|field| field.contact(previous, next)) {
                let field = self.collision.as_ref().unwrap();
                let outgoing = collision_velocity([p.vx, p.vy, p.vz], normal, field.response,
                    field.restitution, field.friction);
                p.x = contact[0] - d.sx; p.y = contact[1] - d.sy; p.z = contact[2] - d.sz;
                p.vx = outgoing[0]; p.vy = outgoing[1]; p.vz = outgoing[2];
                if field.response == 4 { p.life = 0.0; }
            } else {
                p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            }
        }
        let dead = d.lifetime >= 0 && !d.loop_on && age > d.lifetime as f32;
        if !dead {
            if !self.burst_done {
                self.burst_done = true;
                self.spawn(d.burst.max(0));
            }
            self.carry += d.rate * dt / 20.0;
            let n = (self.carry as i32).min(96);
            if n > 0 { self.carry -= n as f32; self.spawn(n); }
        }
        self.parts.retain(|p| p.age < p.life);
    }

    fn spawn(&mut self, count: i32) {
        let cap = self.def.max_particles.max(0) as usize;
        for _ in 0..count {
            if self.parts.len() >= cap { break; }
            let seed = self.rnd01() * 1024.0;
            let orbit = self.rnd01() * std::f32::consts::TAU;
            let orbit_radius = self.def.spread * (0.45 + self.rnd01() * 0.65);
            let mut rx = self.rnd(-1.0, 1.0);
            let mut ry = self.rnd(-0.45, 0.85);
            let mut rz = self.rnd(-1.0, 1.0);
            let len = (rx * rx + ry * ry + rz * rz).sqrt().max(0.001);
            rx /= len; ry /= len; rz /= len;
            let (px, py, pz) = self.place(rx, ry, rz);
            let speed = self.def.speed * (0.45 + self.rnd01() * 0.9);
            let vx = rx * speed + self.rnd(-0.012, 0.012);
            let vy = ry * speed + 0.035 + self.rnd(-0.006, 0.018);
            let vz = rz * speed + self.rnd(-0.012, 0.012);
            let life = (self.def.particle_lifetime as f32 + self.rnd(-6.0, 8.0)).max(4.0);
            let size = (self.def.radius * (0.35 + self.rnd01() * 0.9)).max(0.015);
            self.parts.push(EParticle { x: px, y: py, z: pz, vx, vy, vz, age: 0.0, life, size, seed, orbit, orbit_radius });
        }
    }

    fn place(&mut self, rx: f32, ry: f32, rz: f32) -> (f32, f32, f32) {
        let d = self.def;
        let shell = self.rnd01() * d.spread;
        match d.shape {
            1 => (self.rnd(-0.025, 0.025), self.rnd(-0.025, 0.025), self.rnd(-0.025, 0.025)),
            2 => {
                let a = self.rnd01() * std::f32::consts::TAU;
                let r = d.spread * (0.82 + self.rnd01() * 0.18);
                (a.cos() * r, self.rnd(-0.04, 0.04), a.sin() * r)
            }
            3 => {
                let t = self.rnd01();
                ((d.ex - d.sx) * t + self.rnd(-d.spread, d.spread) * 0.12,
                 (d.ey - d.sy) * t + self.rnd(-d.spread, d.spread) * 0.12,
                 (d.ez - d.sz) * t + self.rnd(-d.spread, d.spread) * 0.12)
            }
            4 => (rx * shell * 0.55, self.rnd01() * d.spread * 0.25, rz * shell * 0.55),
            _ => (rx * shell, ry * shell, rz * shell),
        }
    }

    fn gather(&self, cam: [f64; 3], out: &mut Vec<f32>) {
        let d = self.def;
        let fade = fade_of(&d, self.last_age);
        for p in &self.parts {
            let t = p.age / p.life.max(1.0);
            let size = lerp(p.size, d.size_end, t);
            let a = fade * (1.0 - t) * (p.age / 4.0).min(1.0);
            let col = mix_rgb(d.color2, d.color, t);
            out.push((d.sx as f64 + p.x as f64 - cam[0]) as f32);
            out.push((d.sy as f64 + p.y as f64 - cam[1]) as f32);
            out.push((d.sz as f64 + p.z as f64 - cam[2]) as f32);
            out.push(size);
            out.push(((col >> 16) & 255) as f32 / 255.0);
            out.push(((col >> 8) & 255) as f32 / 255.0);
            out.push((col & 255) as f32 / 255.0);
            out.push(a);
            out.push(d.style as f32);
            out.push(p.seed);
        }
    }
}

fn apply_motion(p: &mut EParticle, d: &EmitterDef, dt: f32) {
    if d.motion == 2 {
        let len = (p.x * p.x + p.y * p.y + p.z * p.z).sqrt().max(0.001);
        let pull = d.speed * 0.08 * dt;
        p.vx -= p.x / len * pull;
        p.vy -= p.y / len * pull;
        p.vz -= p.z / len * pull;
    } else {
        let spin = if d.motion == 3 { 0.18 } else { 0.11 } * dt;
        p.orbit += spin + d.turbulence * 0.3;
        let target_x = p.orbit.cos() * p.orbit_radius;
        let target_z = p.orbit.sin() * p.orbit_radius;
        let pull = if d.motion == 3 { 0.045 } else { 0.028 };
        p.vx += (target_x - p.x) * pull * dt;
        p.vz += (target_z - p.z) * pull * dt;
        if d.motion == 3 { p.vy += (p.orbit * 1.7 + p.seed).sin() * 0.0025 * dt; }
    }
}

fn fade_of(d: &EmitterDef, age: f32) -> f32 {
    let mut f = 1.0f32;
    if d.fade_in > 0.0 { f = f.min(age / d.fade_in); }
    if d.lifetime >= 0 && d.fade_out > 0.0 { f = f.min(((d.lifetime as f32 - age) / d.fade_out).max(0.0)); }
    f.clamp(0.0, 1.0)
}

// ── portable draw: build a POSITION_COLOR vertex buffer (camera-facing billboards) for ALL live particles ──
// MC draws this in ONE RenderPass on Vulkan OR OpenGL. zero per-vertex java work; eval already parallel above.
// layout per vertex: pos 3×f32 (12 bytes) + rgba 4×u8 (4 bytes) = 16 bytes. 6 verts per particle (2 tris).
static VBUF: OnceLock<Mutex<Vec<u8>>> = OnceLock::new();

pub fn build_vertices(now: f32, cam: [f64; 3], right: [f32; 3], up: [f32; 3], out_vcount: *mut i32) -> i64 {
    let mut insts = gather_instances(now, cam);
    let mut emit = gather_emitters(now, cam);
    insts.append(&mut emit);
    let particles = insts.len() / 10;

    let buf_mutex = VBUF.get_or_init(|| Mutex::new(Vec::new()));
    let mut buf = buf_mutex.lock().unwrap();
    buf.clear();
    buf.reserve(particles * 6 * 16);

    for p in insts.chunks_exact(10) {
        let (cx, cy, cz, s) = (p[0], p[1], p[2], p[3]);
        let col = [
            (p[4].clamp(0.0, 1.0) * 255.0) as u8,
            (p[5].clamp(0.0, 1.0) * 255.0) as u8,
            (p[6].clamp(0.0, 1.0) * 255.0) as u8,
            (p[7].clamp(0.0, 1.0) * 255.0) as u8,
        ];
        let (rx, ry, rz) = (right[0] * s, right[1] * s, right[2] * s);
        let (ux, uy, uz) = (up[0] * s, up[1] * s, up[2] * s);
        let bl = [cx - rx - ux, cy - ry - uy, cz - rz - uz];
        let br = [cx + rx - ux, cy + ry - uy, cz + rz - uz];
        let tr = [cx + rx + ux, cy + ry + uy, cz + rz + uz];
        let tl = [cx - rx + ux, cy - ry + uy, cz - rz + uz];
        for v in [bl, br, tr, bl, tr, tl] {
            buf.extend_from_slice(&v[0].to_le_bytes());
            buf.extend_from_slice(&v[1].to_le_bytes());
            buf.extend_from_slice(&v[2].to_le_bytes());
            buf.extend_from_slice(&col);
        }
    }

    unsafe { *out_vcount = (particles * 6) as i32; }
    if buf.is_empty() { 0 } else { buf.as_ptr() as i64 }
}

fn mix_rgb(a: u32, b: u32, t: f32) -> u32 {
    let ar = (a >> 16) & 255; let ag = (a >> 8) & 255; let ab = a & 255;
    let br = (b >> 16) & 255; let bg = (b >> 8) & 255; let bb = b & 255;
    let r = lerp(ar as f32, br as f32, t) as u32;
    let g = lerp(ag as f32, bg as f32, t) as u32;
    let bl = lerp(ab as f32, bb as f32, t) as u32;
    (r << 16) | (g << 8) | bl
}

#[cfg(test)]
mod tests {
    use super::*;

    fn global_store_test_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }

    fn shape_fixture(opcode: u16, count: u32, constants: Vec<f32>) -> GraphProgram {
        GraphProgram {
            graph_hash: 0x5161,
            nodes: vec![GraphNode { stable_id: 7, opcode, flags: 0, input_a: 0, input_b: 0,
                property_offset: 0, event_offset: 0, cost: count }],
            constants, expression_count: 0, material_count: 1,
            max_particles: count, max_lifetime: 40, overflow: GraphOverflow::SkipDecorative,
        }
    }

    #[test]
    fn sigil_particles_sit_on_star_polygon_edges_not_on_random_noise() {
        // from,to,radius,radius_to,thickness,size,alpha,spin,wobble,depth,speed,style,r,g,b,a,points,skip
        let mut constants = vec![0.0, 40.0, 2.0, 2.0, 0.0, 0.05, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            1.0, 1.0, 1.0, 1.0];
        constants.push(5.0);
        constants.push(2.0);
        let batch = shape_fixture(2, 60, constants).evaluate(99, 60);

        assert_eq!(60, batch.particles.len());
        // Every point of a {5/2} star of radius 2 lies inside the circumscribed circle and never
        // nearer the centre than an edge's perpendicular distance, R*cos(2*PI/5) = 0.618.
        for particle in &batch.particles {
            let radius = (particle[0] * particle[0] + particle[2] * particle[2]).sqrt();
            assert!(radius <= 2.001, "sigil point escaped its radius: {radius}");
            assert!(radius >= 0.617, "sigil point collapsed toward the centre: {radius}");
            assert_eq!(0.0, particle[1], "a flat sigil must stay in its plane");
        }
    }

    #[test]
    fn sigil_point_count_changes_the_drawn_shape() {
        let base = vec![0.0, 40.0, 2.0, 2.0, 0.0, 0.05, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            1.0, 1.0, 1.0, 1.0];
        let mut five = base.clone(); five.push(5.0); five.push(2.0);
        let mut seven = base.clone(); seven.push(7.0); seven.push(3.0);

        assert_ne!(shape_fixture(2, 48, five).evaluate(5, 48).particles,
                   shape_fixture(2, 48, seven).evaluate(5, 48).particles);
    }

    #[test]
    fn stream_particles_climb_the_endpoint_line_instead_of_scattering() {
        let constants = vec![0.0, 40.0, 0.0, 0.0, 0.0, 0.05, 1.0, 0.0, 0.25, 0.0, 0.0, 0.0,
            1.0, 1.0, 1.0, 1.0];
        let batch = shape_fixture(7, 32, constants).evaluate(3, 32);

        let mut previous = -1.0;
        for particle in &batch.particles {
            assert!(particle[1] >= previous, "stream must advance along its line");
            previous = particle[1];
            assert!(particle[0].abs() <= 0.2501, "stream wobble must stay bounded");
            assert!(particle[2].abs() <= 0.2501, "stream wobble must stay bounded");
        }
    }

    #[test]
    fn graph_seed_varies_casts_but_replays_exactly() {
        let program = GraphProgram::fixture(64, 0);
        let a = program.evaluate(17, 64);
        let b = program.evaluate(18, 64);
        let replay = program.evaluate(17, 64);
        assert_ne!(a.particles, b.particles);
        assert_eq!(a.particles, replay.particles);
    }

    #[test]
    fn decorative_particles_scale_before_core_particles() {
        let program = GraphProgram::fixture(40, 80);
        let batch = program.evaluate(17, 100);
        assert_eq!(batch.core_count, 40);
        assert_eq!(batch.decorative_count, 60);
    }

    #[test]
    fn graph_program_rejects_unknown_major_version_and_truncation() {
        let bytes = GraphProgram::fixture(12, 8).encode();
        assert!(GraphProgram::decode(&bytes).is_ok());
        let mut wrong = bytes.clone();
        wrong[4] = 3;
        assert!(GraphProgram::decode(&wrong).is_err());
        assert!(GraphProgram::decode(&bytes[..bytes.len() - 1]).is_err());
    }

    #[test]
    fn splitmix64_matches_the_java_golden_vector() {
        assert_eq!(splitmix64(0), 0);
        assert_eq!(splitmix64(1), 0x5692_161d_100b_05e5);
    }

    #[test]
    fn uploaded_graph_spawns_a_hash_referencing_instance() {
        let _guard = global_store_test_lock();
        let program = GraphProgram::fixture(8, 8);
        let hash = upload_graph(&program.encode()).expect("valid fixture upload");
        let id = i64::MIN + 9901;
        assert!(spawn_graph(id, hash, 123, KfxEndpoint::default(), 12));
        assert!(!spawn_graph(id + 1, hash.wrapping_add(1), 123, KfxEndpoint::default(), 12));
        remove(id);
    }

    #[test]
    fn cosmetic_collision_sweeps_and_applies_all_responses_without_events() {
        let mut cells = vec![0u8; 27];
        cells[(1 * 3 + 1) * 3 + 1] = 1;
        let field = CollisionField { origin: [0, 0, 0], side: 3, cells,
            response: 1, restitution: 0.5, friction: 0.25 };
        let (_, normal) = field.contact([1.5, 2.2, 1.5], [1.5, 0.8, 1.5]).expect("floor contact");
        assert_eq!(normal, [0.0, 1.0, 0.0]);
        assert_eq!(collision_velocity([1.0, -2.0, 0.0], normal, 1, 0.5, 0.25), [0.75, 1.0, 0.0]);
        assert_eq!(collision_velocity([1.0, -2.0, 0.0], normal, 2, 0.5, 0.25), [0.75, 0.0, 0.0]);
        assert_eq!(collision_velocity([1.0, -2.0, 0.0], normal, 3, 0.5, 0.25), [0.0; 3]);
        assert_eq!(collision_velocity([1.0, -2.0, 0.0], normal, 4, 0.5, 0.25), [0.0; 3]);
    }

    #[test]
    fn gpu_instances_keep_style_seed_and_follow_endpoint_updates() {
        let _guard = global_store_test_lock();
        let id = i64::MIN + 731;
        let mut op = [0.0f32; 24];
        op[0] = 4.0; // point
        op[2] = 20.0;
        op[5] = 0.25;
        op[7] = 1.0;
        op[8] = 5.0;
        op[14] = 1.0;
        op[17] = 1.0;
        op[18] = 123.0;
        op[21] = 1.0;
        op[22] = 2.0;
        op[23] = 3.0;
        upload(id, &op, KfxEndpoint { sx: 10.0, sy: 20.0, sz: 30.0, ex: 10.0, ey: 20.0, ez: 31.0, born_ticks: 0.0 });

        let first = gather_instances(1.0, [0.0, 0.0, 0.0]);
        assert_eq!(first.len(), 10);
        assert_eq!(&first[0..4], &[11.0, 23.0, 32.0, 0.25]);
        assert_eq!(&first[8..10], &[5.0, 123.0]);

        update_endpoint(id, 40.0, 50.0, 60.0, 40.0, 50.0, 61.0);
        let moved = gather_instances(1.0, [0.0, 0.0, 0.0]);
        assert_eq!(&moved[0..3], &[41.0, 53.0, 62.0]);
        remove(id);
    }
}
