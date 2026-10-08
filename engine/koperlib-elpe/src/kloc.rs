// koper kloc layer — kontraptions for elpe.
//
// a kloc is a pile of mc blocks that slides around the world and NEVER turns. that one rule is
// what makes this cheap: every block stays axis aligned, so its collider is the unit cube it sits
// in and terrain collision is a bitset lookup instead of a shape query. no inertia tensor, no
// quaternion integration, no angular solver, nothing.
//
// the only thing in here that turns is a kloc hanging on a hinge joint, and it turns around that
// joint's one axis as a single f32. that's the wheel case, and it costs a sin/cos at read time.
//
// rapier does the real thing. this is the version for a server with 200 vehicles on it.

use crate::terrain::{KoperTerrain, Peek, KoperSection};

pub const KLOC_DEAD: u8 = 0;
pub const KLOC_AWAKE: u8 = 1;
pub const KLOC_ASLEEP: u8 = 2;
pub const KLOC_PARKED: u8 = 3;

pub const NONE: u32 = u32::MAX;

pub const HINGE: u8 = 0;
pub const SLIDER: u8 = 1;

#[inline(always)] fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
#[inline(always)] fn add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] + b[0], a[1] + b[1], a[2] + b[2]] }
#[inline(always)] fn mul(a: [f32; 3], s: f32) -> [f32; 3] { [a[0] * s, a[1] * s, a[2] * s] }
#[inline(always)] fn dot(a: [f32; 3], b: [f32; 3]) -> f32 { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }
#[inline(always)] fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

// rodrigues, one axis one angle. no quaternion type in here and we dont need one
fn spin_around(v: [f32; 3], axis: [f32; 3], angle: f32) -> [f32; 3] {
    let (s, c) = angle.sin_cos();
    let k = norm(axis);
    let kv = cross(k, v);
    let kd = dot(k, v) * (1.0 - c);
    [v[0] * c + kv[0] * s + k[0] * kd,
     v[1] * c + kv[1] * s + k[1] * kd,
     v[2] * c + kv[2] * s + k[2] * kd]
}

fn norm(a: [f32; 3]) -> [f32; 3] {
    let l = dot(a, a).sqrt();
    if l < 1e-9 { [0.0, 1.0, 0.0] } else { mul(a, 1.0 / l) }
}

pub struct KoperKloc {
    pub pos: [f32; 3],
    pub prev: [f32; 3],
    pub inv_mass: f32,
    // local block offsets from the body centre, block units. these are the colliders
    pub blocks: Vec<[i16; 3]>,
    // half extents of the whole thing, for the broadphase
    pub half: [f32; 3],
    pub state: u8,
    pub still: u8,
    pub sleep_ok: bool,
    pub damping: f32,
    // hinge spin: radians around the joint axis. 0 for everything that isnt a wheel
    pub spin: f32,
    pub spin_vel: f32,
    pub hinge: u32,
    // everything reachable through joints shares this. one machine = one group, and a group
    // never collides with itself — a chassis and its wheels are two joints apart, not one, so
    // pair-wise "are these jointed" was letting them shove each other around the map
    pub group: u32,
    // touched something solid this substep. traction and friction both need to know
    pub grounded: bool,
    pub gen: u32,
}

impl KoperKloc {
    fn recalc_half(&mut self) {
        let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
        for b in &self.blocks {
            for a in 0..3 {
                let v = b[a] as f32;
                if v < lo[a] { lo[a] = v; }
                if v > hi[a] { hi[a] = v; }
            }
        }
        if self.blocks.is_empty() { self.half = [0.5; 3]; return; }
        for a in 0..3 { self.half[a] = (hi[a] - lo[a]) * 0.5 + 0.5; }
    }
}

#[derive(Clone, Copy)]
pub struct KlocJoint {
    pub a: u32,
    // NONE = bolted to the world at anchor_a's world spot
    pub b: u32,
    pub anchor_a: [f32; 3],
    pub anchor_b: [f32; 3],
    pub axis: [f32; 3],
    pub kind: u8,
    pub min: f32,
    pub max: f32,
    pub limited: bool,
    pub motor_on: bool,
    pub motor_vel: f32,
    pub motor_force: f32,
    // spring: where the joint wants to sit and how hard it pulls. this is what a khysics
    // position motor is, and what suspension drives
    pub spring_on: bool,
    pub rest: f32,
    pub stiffness: f32,
    pub damping: f32,
    // where the joint currently sits: radians for a hinge, blocks for a slider
    pub pos: f32,
    pub vel: f32,
    pub alive: bool,
}

pub struct KlocStats {
    pub live: u32,
    pub awake: u32,
    pub joints: u32,
}

pub struct KoperKlocWorld {
    pub klocs: Vec<KoperKloc>,
    free: Vec<u32>,
    pub joints: Vec<KlocJoint>,
    joint_free: Vec<u32>,
    pub gravity: [f32; 3],
    pub friction: f32,
    pub sleep_speed: f32,
    pub sleep_ticks: u8,
    pub joint_iters: u32,
    pub max_step: f32,
    // flat floor for tests and non mc use. NaN = no floor, terrain does the work
    pub floor_y: f32,
    pub gen: u32,
}

impl Default for KoperKlocWorld {
    fn default() -> Self {
        KoperKlocWorld {
            klocs: Vec::new(),
            free: Vec::new(),
            joints: Vec::new(),
            joint_free: Vec::new(),
            gravity: [0.0, -28.0, 0.0],
            friction: 0.55,
            sleep_speed: 0.02,
            sleep_ticks: 30,
            joint_iters: 6,
            max_step: 0.45,
            floor_y: f32::NAN,
            gen: 1,
        }
    }
}

impl KoperKlocWorld {

    pub fn spawn(&mut self, blocks: Vec<[i16; 3]>, inv_mass: f32, at: [f32; 3]) -> u32 {
        let mut k = KoperKloc {
            pos: at,
            prev: at,
            inv_mass,
            blocks,
            half: [0.5; 3],
            state: KLOC_AWAKE,
            still: 0,
            sleep_ok: true,
            damping: 0.999,
            spin: 0.0,
            spin_vel: 0.0,
            hinge: NONE,
            group: 0,
            grounded: false,
            gen: self.gen,
        };
        k.recalc_half();
        self.gen = self.gen.wrapping_add(1).max(1);
        // its own group until a joint merges it into a machine. leaving this 0 meant every
        // kloc shared group 0 and nothing collided with anything
        let id = match self.free.pop() {
            Some(i) => { self.klocs[i as usize] = k; i }
            None => { self.klocs.push(k); (self.klocs.len() - 1) as u32 }
        };
        self.klocs[id as usize].group = id;
        id
    }

    // one flood fill over the joints. a few hundred bodies, only when the wiring changes
    fn regroup(&mut self) {
        for (i, k) in self.klocs.iter_mut().enumerate() { k.group = i as u32; }
        let mut moved = true;
        while moved {
            moved = false;
            for j in &self.joints {
                if !j.alive || j.b == NONE { continue; }
                let (a, b) = (j.a as usize, j.b as usize);
                if a >= self.klocs.len() || b >= self.klocs.len() { continue; }
                let lo = self.klocs[a].group.min(self.klocs[b].group);
                if self.klocs[a].group != lo { self.klocs[a].group = lo; moved = true; }
                if self.klocs[b].group != lo { self.klocs[b].group = lo; moved = true; }
            }
        }
    }

    pub fn despawn(&mut self, i: u32) {
        let Some(k) = self.klocs.get_mut(i as usize) else { return };
        if k.state == KLOC_DEAD { return; }
        k.state = KLOC_DEAD;
        k.blocks.clear();
        self.free.push(i);
        for j in self.joints.iter_mut() {
            if j.alive && (j.a == i || j.b == i) { j.alive = false; }
        }
        self.regroup();
    }

    // id is (gen << 32) | index. the gen stops a stale java handle from poking a recycled slot
    pub fn by_id(&self, id: u64) -> Option<u32> {
        let i = (id & 0xFFFF_FFFF) as u32;
        let g = (id >> 32) as u32;
        let k = self.klocs.get(i as usize)?;
        if k.state == KLOC_DEAD || k.gen != g { return None; }
        Some(i)
    }

    pub fn id_of(&self, i: u32) -> u64 {
        match self.klocs.get(i as usize) {
            Some(k) => ((k.gen as u64) << 32) | i as u64,
            None => u64::MAX,
        }
    }

    pub fn get(&self, i: u32) -> Option<&KoperKloc> {
        self.klocs.get(i as usize).filter(|k| k.state != KLOC_DEAD)
    }

    pub fn wake(&mut self, i: u32) {
        if let Some(k) = self.klocs.get_mut(i as usize) {
            if k.state == KLOC_ASLEEP { k.state = KLOC_AWAKE; k.still = 0; }
        }
    }

    pub fn set_pos(&mut self, i: u32, p: [f32; 3], keep_velocity: bool) {
        let Some(k) = self.klocs.get_mut(i as usize) else { return };
        let v = sub(k.pos, k.prev);
        k.pos = p;
        k.prev = if keep_velocity { sub(p, v) } else { p };
        if k.state == KLOC_ASLEEP { k.state = KLOC_AWAKE; k.still = 0; }
    }

    pub fn add_velocity(&mut self, i: u32, v: [f32; 3], h: f32) {
        let Some(k) = self.klocs.get_mut(i as usize) else { return };
        if k.inv_mass == 0.0 || k.state == KLOC_DEAD { return; }
        k.prev = sub(k.prev, mul(v, h));
        if k.state == KLOC_ASLEEP { k.state = KLOC_AWAKE; k.still = 0; }
    }

    pub fn set_velocity(&mut self, i: u32, v: [f32; 3], h: f32) {
        let Some(k) = self.klocs.get_mut(i as usize) else { return };
        if k.state == KLOC_DEAD { return; }
        k.prev = sub(k.pos, mul(v, h));
        if k.state == KLOC_ASLEEP { k.state = KLOC_AWAKE; k.still = 0; }
    }

    pub fn velocity(&self, i: u32, h: f32) -> [f32; 3] {
        match self.get(i) {
            Some(k) => mul(sub(k.pos, k.prev), 1.0 / h.max(1e-6)),
            None => [0.0; 3],
        }
    }

    // everything jointed to `i`, so shoving a machine moves the whole machine
    pub fn group_of(&self, i: u32, out: &mut Vec<u32>) {
        out.clear();
        out.push(i);
        let mut n = 0;
        while n < out.len() {
            let cur = out[n];
            n += 1;
            for j in &self.joints {
                if !j.alive { continue; }
                let other = if j.a == cur { j.b } else if j.b == cur { j.a } else { continue };
                if other == NONE || out.contains(&other) { continue; }
                out.push(other);
            }
        }
    }

    // everything hanging off the B side of this joint, not crossing back through A. that is
    // "the bearing and whatever is bolted to it"
    fn rider_side(&self, skip: u32, a: u32, b: u32, out: &mut Vec<u32>) {
        out.clear();
        out.push(b);
        let mut n = 0;
        while n < out.len() {
            let cur = out[n];
            n += 1;
            for (id, j) in self.joints.iter().enumerate() {
                if !j.alive || id as u32 == skip { continue; }
                let other = if j.a == cur { j.b } else if j.b == cur { j.a } else { continue };
                if other == NONE || other == a || out.contains(&other) { continue; }
                out.push(other);
            }
        }
    }

    pub fn add_block(&mut self, i: u32, off: [i16; 3]) -> bool {
        let Some(k) = self.klocs.get_mut(i as usize) else { return false };
        if k.state == KLOC_DEAD || k.blocks.contains(&off) { return false; }
        k.blocks.push(off);
        k.recalc_half();
        k.state = KLOC_AWAKE;
        k.still = 0;
        true
    }

    pub fn remove_block(&mut self, i: u32, off: [i16; 3]) -> bool {
        let Some(k) = self.klocs.get_mut(i as usize) else { return false };
        let Some(at) = k.blocks.iter().position(|b| *b == off) else { return false };
        k.blocks.swap_remove(at);
        k.recalc_half();
        k.state = KLOC_AWAKE;
        k.still = 0;
        true
    }

    // ── joints ───────────────────────────────────────────────────────────────

    pub fn joint(&mut self, mut j: KlocJoint) -> u32 {
        j.axis = norm(j.axis);
        j.alive = true;
        if j.kind == HINGE && j.b != NONE {
            // the hanging side is the one that gets to spin
            if let Some(k) = self.klocs.get_mut(j.b as usize) { k.spin = 0.0; k.spin_vel = 0.0; }
        }
        let id = match self.joint_free.pop() {
            Some(id) => { self.joints[id as usize] = j; id }
            None => { self.joints.push(j); (self.joints.len() - 1) as u32 }
        };
        if j.kind == HINGE && j.b != NONE {
            if let Some(k) = self.klocs.get_mut(j.b as usize) { k.hinge = id; }
        }
        self.regroup();
        id
    }

    pub fn unjoint(&mut self, id: u32) {
        let Some(j) = self.joints.get_mut(id as usize) else { return };
        if !j.alive { return; }
        let b = j.b;
        j.alive = false;
        self.joint_free.push(id);
        if b != NONE {
            if let Some(k) = self.klocs.get_mut(b as usize) {
                if k.hinge == id { k.hinge = NONE; k.spin_vel = 0.0; }
            }
        }
        self.regroup();
    }

    pub fn joint_state(&self, id: u32) -> Option<[f32; 2]> {
        self.joints.get(id as usize).filter(|j| j.alive).map(|j| [j.pos, j.vel])
    }

    pub fn set_motor(&mut self, id: u32, vel: f32, force: f32) {
        let Some(j) = self.joints.get_mut(id as usize) else { return };
        j.motor_on = force > 0.0;
        j.motor_vel = vel;
        j.motor_force = force;
        let (a, b) = (j.a, j.b);
        if a != NONE { self.wake(a); }
        if b != NONE { self.wake(b); }
    }

    // khysics position motor -> a real spring on the axis
    pub fn set_spring(&mut self, id: u32, rest: f32, stiffness: f32, damping: f32, max_force: f32) {
        let Some(j) = self.joints.get_mut(id as usize) else { return };
        j.spring_on = stiffness > 0.0;
        j.rest = rest;
        j.stiffness = stiffness.max(0.0);
        j.damping = damping.max(0.0);
        if max_force > 0.0 { j.motor_force = max_force; }
        let (a, b) = (j.a, j.b);
        if a != NONE { self.wake(a); }
        if b != NONE { self.wake(b); }
    }

    pub fn set_limits(&mut self, id: u32, min: f32, max: f32) {
        if let Some(j) = self.joints.get_mut(id as usize) { j.min = min; j.max = max; j.limited = true; }
    }

    pub fn clear_limits(&mut self, id: u32) {
        if let Some(j) = self.joints.get_mut(id as usize) { j.limited = false; }
    }

    // ── the step ─────────────────────────────────────────────────────────────

    // returns section keys a kloc walked into and we dont have. caller asks java for them
    pub fn step(&mut self, dt: f32, substeps: u32, terrain: &KoperTerrain, stamp: u32, use_terrain: bool,
                want: &mut Vec<u64>) {
        let substeps = substeps.clamp(1, 8);
        let h = (dt / substeps as f32).max(1e-5);
        for _ in 0..substeps {
            for k in self.klocs.iter_mut() { k.grounded = false; }
            self.predict(h);
            // joints and contacts have to converge together. solving joints once and THEN
            // pushing bodies out of the ground just tears the joints back open every substep,
            // which is what made everything slowly drift apart
            for pass in 0..self.joint_iters.max(1) {
                self.solve_joints(h, pass == 0);
                if !self.floor_y.is_nan() { self.solve_floor(); }
                if use_terrain { self.solve_terrain(terrain, stamp, want); }
                self.solve_klocs();
            }
            // last word goes to the joints, a wheel hanging off its axle looks worse than a
            // wheel a hair inside the ground
            self.solve_joints(h, false);
        }
        self.spin_and_sleep(h);
    }

    fn predict(&mut self, h: f32) {
        let g = mul(self.gravity, h * h);
        let ms = self.max_step;
        for k in self.klocs.iter_mut() {
            if k.state != KLOC_AWAKE || k.inv_mass == 0.0 { continue; }
            let mut v = mul(sub(k.pos, k.prev), k.damping);
            let v2 = dot(v, v);
            if v2 > ms * ms { v = mul(v, ms / v2.sqrt()); }
            k.prev = k.pos;
            k.pos = add(add(k.pos, v), g);
        }
    }

    fn solve_joints(&mut self, h: f32, bookkeep: bool) {
        for id in 0..self.joints.len() {
            let j = self.joints[id];
            if !j.alive { continue; }
            let a = j.a as usize;
            if self.klocs.get(a).map(|k| k.state) == Some(KLOC_DEAD) { self.joints[id].alive = false; continue; }
            if j.b != NONE && self.klocs.get(j.b as usize).map(|k| k.state) == Some(KLOC_DEAD) {
                self.joints[id].alive = false;
                continue;
            }

            // where both sides say the joint point is
            let world_a = add(self.klocs[a].pos, j.anchor_a);
            let (world_b, inv_b) = if j.b == NONE {
                (j.anchor_b, 0.0)
            } else {
                let kb = &self.klocs[j.b as usize];
                (add(kb.pos, j.anchor_b), kb.inv_mass)
            };
            let inv_a = self.klocs[a].inv_mass;
            let wsum = inv_a + inv_b;
            if wsum <= 0.0 { continue; }

            let mut err = sub(world_b, world_a);

            // a slider is free along its axis inside the limits, so take that part out of the error
            // and treat it as the joint coordinate instead
            if j.kind == SLIDER {
                let along = dot(err, j.axis);
                // where the axis separation should end up this substep
                let mut target = along;
                // once per substep, not once per pass: applying a compliant correction six
                // times in a row compounds into a rigid one and the spring stops deflecting
                if j.spring_on && bookkeep {
                    // a spring has to be allowed to deflect, otherwise the load never bends it
                    // and it reports zero travel forever. xpbd compliance: stiff = corrects most
                    // of the error each pass, soft = barely any, so a soft tune sags further
                    let stiff = j.stiffness.max(1e-3);
                    let soft = wsum / (wsum + 1.0 / (stiff * h * h));
                    let d = (j.damping * h / (1.0 + j.damping * h)).clamp(0.0, 1.0);
                    target = along - (along - j.rest) * soft - j.vel * h * d;
                }
                if j.limited { target = target.clamp(j.min, j.max); }
                // drive the axis separation TO target: the error keeps (along - target) and
                // loses the rest. this was inverted, so a slider locked at min == max left its
                // axis completely unconstrained and the wheel just fell off the car
                err = sub(err, mul(j.axis, target));
                if bookkeep {
                    let was = self.joints[id].pos;
                    self.joints[id].pos = target;
                    self.joints[id].vel = (target - was) / h;
                }

                if j.motor_on {
                    // shove both sides along the axis towards the motor's speed
                    let want = j.motor_vel * h;
                    let step = want.clamp(-j.motor_force * h, j.motor_force * h);
                    let push = mul(j.axis, step);
                    if inv_a > 0.0 { self.klocs[a].pos = sub(self.klocs[a].pos, mul(push, inv_a / wsum)); }
                    if j.b != NONE && inv_b > 0.0 {
                        let b = j.b as usize;
                        self.klocs[b].pos = add(self.klocs[b].pos, mul(push, inv_b / wsum));
                    }
                }
            }

            // pull the anchors back together. for a hinge that's the whole constraint —
            // the spin around the axis is free and nothing here touches it
            let corr = mul(err, 1.0 / wsum);
            if inv_a > 0.0 {
                self.klocs[a].pos = add(self.klocs[a].pos, mul(corr, inv_a));
                if self.klocs[a].state == KLOC_ASLEEP { self.klocs[a].state = KLOC_AWAKE; }
            }
            if j.b != NONE && inv_b > 0.0 {
                let b = j.b as usize;
                self.klocs[b].pos = sub(self.klocs[b].pos, mul(corr, inv_b));
                if self.klocs[b].state == KLOC_ASLEEP { self.klocs[b].state = KLOC_AWAKE; }
            }
        }
    }

    fn solve_floor(&mut self) {
        let (fy, friction) = (self.floor_y, self.friction);
        for k in self.klocs.iter_mut() {
            if k.state != KLOC_AWAKE || k.inv_mass == 0.0 { continue; }
            let bottom = k.pos[1] - k.half[1];
            if bottom >= fy { continue; }
            k.pos[1] += fy - bottom;
            k.grounded = true;
            // rub off sliding whenever we are touching, not only while sinking in. gating this
            // on "moving into the surface" meant anything resting on the ground slid forever
            let v = sub(k.pos, k.prev);
            let vt = [v[0], 0.0, v[2]];
            k.prev = add(sub(k.pos, mul(vt, 1.0 - friction)), [0.0, v[1], 0.0]);
        }
    }

    // every block of every awake kloc against the voxel it overlaps. blocks are axis aligned so
    // this is just "how deep am i in that cube" on three axes, take the shallowest, push out
    fn solve_terrain(&mut self, terrain: &KoperTerrain, stamp: u32, want: &mut Vec<u64>) {
        let friction = self.friction;
        for k in self.klocs.iter_mut() {
            if k.state != KLOC_AWAKE || k.inv_mass == 0.0 || k.blocks.is_empty() { continue; }
            let mut cache: (u64, *const KoperSection) = (u64::MAX, std::ptr::null());
            let mut push = [0.0f32; 3];
            let mut hit = false;

            for off in &k.blocks {
                let c = [k.pos[0] + off[0] as f32, k.pos[1] + off[1] as f32, k.pos[2] + off[2] as f32];
                let (lo, hi) = ([c[0] - 0.5, c[1] - 0.5, c[2] - 0.5], [c[0] + 0.5, c[1] + 0.5, c[2] + 0.5]);
                let (vx0, vy0, vz0) = (lo[0].floor() as i32, lo[1].floor() as i32, lo[2].floor() as i32);
                let (vx1, vy1, vz1) = ((hi[0] - 1e-4).floor() as i32, (hi[1] - 1e-4).floor() as i32, (hi[2] - 1e-4).floor() as i32);

                for vx in vx0..=vx1 { for vy in vy0..=vy1 { for vz in vz0..=vz1 {
                    match terrain.peek(vx, vy, vz, stamp, &mut cache) {
                        Peek::Solid => {}
                        // unknown: treat as air this tick but ask for it. a kloc is big and
                        // visible, freezing one mid air looks worse than one tick of overshoot
                        Peek::Unknown(key) => { if !want.contains(&key) { want.push(key); } continue; }
                        Peek::Air => continue,
                    }
                    let (vlo, vhi) = ([vx as f32, vy as f32, vz as f32],
                                      [vx as f32 + 1.0, vy as f32 + 1.0, vz as f32 + 1.0]);
                    let mut best = f32::MAX;
                    let mut axis = 1usize;
                    let mut sign = 1.0f32;
                    for a in 0..3 {
                        let d1 = vhi[a] - lo[a]; // push +
                        let d2 = hi[a] - vlo[a]; // push -
                        if d1 <= 0.0 || d2 <= 0.0 { best = f32::MAX; break; }
                        let (d, s) = if d1 < d2 { (d1, 1.0) } else { (d2, -1.0) };
                        if d < best { best = d; axis = a; sign = s; }
                    }
                    if best == f32::MAX { continue; }
                    hit = true;
                    let want = best * sign;
                    if want.abs() > push[axis].abs() { push[axis] = want; }
                }}}
            }

            if !hit { continue; }
            k.pos = add(k.pos, push);
            // kill the velocity going into the wall, and rub off some of the sliding
            k.grounded = true;
            let n = norm(push);
            let v = sub(k.pos, k.prev);
            // kill whatever is going into the wall, then rub off the sliding. same fix as the
            // floor: friction used to only apply while sinking in, so nothing ever slowed down
            let into = dot(v, n);
            let vn = if into < 0.0 { mul(n, into) } else { [0.0; 3] };
            let vt = sub(v, mul(n, dot(v, n)));
            let kept = add(mul(vt, 1.0 - friction), sub(v, add(vt, vn)));
            k.prev = sub(k.pos, kept);
            // NOT k.still = 0 here. touching the ground is the normal resting state — zeroing
            // the counter on every contact meant nothing standing on terrain ever fell asleep
        }
    }

    // kloc against kloc, plain aabb. there are hundreds of these, not millions, so n² with an
    // early box reject is honestly fine
    fn solve_klocs(&mut self) {
        let n = self.klocs.len();
        for i in 0..n {
            if self.klocs[i].state == KLOC_DEAD || self.klocs[i].blocks.is_empty() { continue; }
            for j in (i + 1)..n {
                if self.klocs[j].state == KLOC_DEAD || self.klocs[j].blocks.is_empty() { continue; }
                let (ia, ib) = (self.klocs[i].inv_mass, self.klocs[j].inv_mass);
                let wsum = ia + ib;
                if wsum <= 0.0 { continue; }
                if self.klocs[i].state != KLOC_AWAKE && self.klocs[j].state != KLOC_AWAKE { continue; }
                if self.klocs[i].group == self.klocs[j].group { continue; }

                let d = sub(self.klocs[j].pos, self.klocs[i].pos);
                let (ha, hb) = (self.klocs[i].half, self.klocs[j].half);
                let mut best = f32::MAX;
                let mut axis = 1usize;
                let mut sign = 1.0f32;
                let mut touching = true;
                for a in 0..3 {
                    let overlap = ha[a] + hb[a] - d[a].abs();
                    if overlap <= 0.0 { touching = false; break; }
                    if overlap < best { best = overlap; axis = a; sign = if d[a] < 0.0 { 1.0 } else { -1.0 }; }
                }
                if !touching { continue; }

                let mut push = [0.0f32; 3];
                push[axis] = best * sign;
                if ia > 0.0 {
                    self.klocs[i].pos = add(self.klocs[i].pos, mul(push, ia / wsum));
                    if self.klocs[i].state == KLOC_ASLEEP { self.klocs[i].state = KLOC_AWAKE; self.klocs[i].still = 0; }
                }
                if ib > 0.0 {
                    self.klocs[j].pos = sub(self.klocs[j].pos, mul(push, ib / wsum));
                    if self.klocs[j].state == KLOC_ASLEEP { self.klocs[j].state = KLOC_AWAKE; self.klocs[j].still = 0; }
                }
            }
        }
    }

    // spin the wheels and put to sleep whatever stopped moving
    fn spin_and_sleep(&mut self, h: f32) {
        for id in 0..self.joints.len() {
            let j = self.joints[id];
            if !j.alive || j.kind != HINGE || j.b == NONE { continue; }

            let free_roll = {
                // no motor and no servo: roll with the ground. wheel radius off its own box
                let k = &self.klocs[j.b as usize];
                let r = k.half[0].max(k.half[2]).max(0.5);
                let v = sub(k.pos, k.prev);
                let tang = sub(v, mul(j.axis, dot(v, j.axis)));
                dot(tang, tang).sqrt() / h / r * if dot(tang, [1.0, 0.0, 1.0]) < 0.0 { -1.0 } else { 1.0 }
            };

            let spun_from = self.klocs[j.b as usize].spin;
            let kb = &mut self.klocs[j.b as usize];

            if j.spring_on {
                // steering servo: a khysics position motor on a bearing. drive the angle at the
                // rest angle instead of spinning the thing forever
                // one gain, damping just slows it down. feeding velocity back in made it
                // oscillate and overshoot the commanded angle
                let gain = (j.stiffness * h * h / (1.0 + j.damping * h)).clamp(0.0, 1.0);
                let step = (j.rest - kb.spin) * gain;
                kb.spin += step;
                kb.spin_vel = step / h;
            } else {
                let target = if j.motor_on { j.motor_vel } else { free_roll };
                let step = if j.motor_on { (target - kb.spin_vel).clamp(-j.motor_force * h, j.motor_force * h) }
                           else { target - kb.spin_vel };
                kb.spin_vel += step;
                kb.spin += kb.spin_vel * h;
            }

            // limits are a hard stop. a steering axle has them and used to spin 360 forever
            // because only the slider ever looked at them
            if j.limited {
                let lo = j.min.min(j.max);
                let hi = j.min.max(j.max);
                if kb.spin < lo { kb.spin = lo; if kb.spin_vel < 0.0 { kb.spin_vel = 0.0; } }
                else if kb.spin > hi { kb.spin = hi; if kb.spin_vel > 0.0 { kb.spin_vel = 0.0; } }
            } else {
                if kb.spin > std::f32::consts::TAU { kb.spin -= std::f32::consts::TAU; }
                if kb.spin < -std::f32::consts::TAU { kb.spin += std::f32::consts::TAU; }
            }

            let now = kb.spin;
            let delta = now - spun_from;

            // THE point of a bearing: it turns and carries what is bolted to it. spinning only
            // its own number made a bearing a cosmetic micro-wheel and left the wheel on it dead
            if delta.abs() > 1e-6 {
                let pivot = add(self.klocs[j.a as usize].pos, j.anchor_a);
                let mut riders = Vec::new();
                self.rider_side(id as u32, j.a, j.b, &mut riders);
                for r in &riders {
                    let k = &mut self.klocs[*r as usize];
                    if k.state == KLOC_DEAD || k.inv_mass == 0.0 { continue; }
                    k.pos = add(pivot, spin_around(sub(k.pos, pivot), j.axis, delta));
                    k.prev = add(pivot, spin_around(sub(k.prev, pivot), j.axis, delta));
                    k.hinge = id as u32;
                    k.spin = now;
                    k.still = 0;
                }
                // and the anchors inside the assembly, or the joint solver just drags everything
                // straight back: an anchor says "two blocks that way" in world axes and a kloc
                // never turns, so without this the bearing spins alone forever
                for (jid, j2) in self.joints.iter_mut().enumerate() {
                    if !j2.alive { continue; }
                    let touch_a = riders.contains(&j2.a);
                    let touch_b = j2.b != NONE && riders.contains(&j2.b);
                    if jid as u32 == id as u32 {
                        if touch_b { j2.anchor_b = spin_around(j2.anchor_b, j2.axis, delta); }
                        continue;
                    }
                    if touch_a {
                        j2.anchor_a = spin_around(j2.anchor_a, j.axis, delta);
                        j2.axis = spin_around(j2.axis, j.axis, delta);
                    }
                    if touch_b { j2.anchor_b = spin_around(j2.anchor_b, j.axis, delta); }
                    if touch_a && j2.b == NONE {
                        j2.anchor_b = add(pivot, spin_around(sub(j2.anchor_b, pivot), j.axis, delta));
                    }
                }
            }

            let was = self.joints[id].pos;
            self.joints[id].pos = now;
            self.joints[id].vel = (now - was) / h;
        }

        self.traction(h);

        let (ss, st) = (self.sleep_speed, self.sleep_ticks);
        for k in self.klocs.iter_mut() {
            if k.state != KLOC_AWAKE { continue; }
            let v = sub(k.pos, k.prev);
            if dot(v, v).sqrt() / h < ss && k.spin_vel.abs() < ss {
                if k.sleep_ok {
                    k.still = k.still.saturating_add(1);
                    if k.still >= st { k.state = KLOC_ASLEEP; k.prev = k.pos; }
                }
            } else {
                k.still = 0;
            }
        }
    }

    // a spinning wheel that is touching something drags its machine along. without this the
    // motors just span a cosmetic number and the car sat there while its wheels turned
    fn traction(&mut self, h: f32) {
        for id in 0..self.joints.len() {
            let j = self.joints[id];
            if !j.alive || j.kind != HINGE || j.b == NONE || j.spring_on { continue; }
            let w = j.b as usize;
            if !self.klocs[w].grounded || self.klocs[w].state != KLOC_AWAKE { continue; }

            // rolling direction: along the ground, square to the axle
            let up = [0.0f32, 1.0, 0.0];
            let roll = norm(cross(j.axis, up));
            if dot(roll, roll) < 0.5 { continue; }

            let r = self.klocs[w].half[0].max(self.klocs[w].half[2]).max(0.5);
            let want = self.klocs[w].spin_vel * r;
            let have = dot(sub(self.klocs[w].pos, self.klocs[w].prev), roll) / h;
            let slip = want - have;
            if slip.abs() < 1e-4 { continue; }

            // the wheel grips, and the joints drag the rest of the machine with it
            let grip = (self.friction).clamp(0.0, 1.0);
            let push = mul(roll, slip * grip * h);
            let group = self.klocs[w].group;

            for k in self.klocs.iter_mut() {
                if k.state != KLOC_AWAKE || k.inv_mass == 0.0 || k.group != group { continue; }
                k.prev = sub(k.prev, push);
                k.still = 0;
            }
        }
    }

    pub fn stats(&self) -> KlocStats {
        let mut s = KlocStats { live: 0, awake: 0, joints: 0 };
        for k in &self.klocs {
            if k.state == KLOC_DEAD { continue; }
            s.live += 1;
            if k.state == KLOC_AWAKE { s.awake += 1; }
        }
        s.joints = self.joints.iter().filter(|j| j.alive).count() as u32;
        s
    }

    // [id_lo, id_hi, cx,cy,cz, qx,qy,qz,qw, flags] per live kloc — the khysics transform layout.
    // quat is identity unless the thing is on a hinge, because nothing else here turns
    pub fn transforms(&self, out: &mut [f32]) -> usize {
        let mut n = 0;
        for (i, k) in self.klocs.iter().enumerate() {
            if k.state == KLOC_DEAD { continue; }
            let at = n * 10;
            if at + 10 > out.len() { break; }
            let id = ((k.gen as u64) << 32) | i as u64;
            out[at] = f32::from_bits(id as u32);
            out[at + 1] = f32::from_bits((id >> 32) as u32);
            out[at + 2] = k.pos[0];
            out[at + 3] = k.pos[1];
            out[at + 4] = k.pos[2];
            // a kloc body NEVER turns on elpe. only a thing hanging off a hinge does, around
            // that joint's one axis. koper's call, and it is what keeps this cheap
            let (qx, qy, qz, qw) = if k.hinge == NONE || k.spin == 0.0 {
                (0.0, 0.0, 0.0, 1.0)
            } else {
                let ax = self.joints[k.hinge as usize].axis;
                let (s, c) = (k.spin * 0.5).sin_cos();
                (ax[0] * s, ax[1] * s, ax[2] * s, c)
            };
            out[at + 5] = qx;
            out[at + 6] = qy;
            out[at + 7] = qz;
            out[at + 8] = qw;
            // bit 0 = sitting on the world grid. a sleeping kloc that never turned is exactly that
            let aligned = k.state == KLOC_ASLEEP || k.state == KLOC_PARKED;
            out[at + 9] = if aligned { 1.0 } else { 0.0 };
            n += 1;
        }
        n
    }
}
