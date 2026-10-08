// the whole elpe world. points only — no rotation, no inertia tensor, no quaternions.
// verlet integration + position based constraints (jacobi for contacts, gauss-seidel for joints).
// the trick for millions: sleeping points cost zero. step cost is O(awake), not O(alive)

use crate::crew::KoperCrew;
use crate::grid::{KoperGrid, NONE};
use std::sync::Mutex;
use crate::kloc::KoperKlocWorld;
use crate::terrain::{KoperTerrain, Peek, unpack_section};

pub const DEAD: u8 = 0;
pub const AWAKE: u8 = 1;
pub const ASLEEP: u8 = 2;
// standing in terrain we dont have yet — holds still until java sends the section
pub const FROZEN: u8 = 3;

#[derive(Clone, Copy)]
pub struct KoperElpeConfig {
    pub gravity: [f32; 3],
    // velocity kept per substep. 1.0 = no air drag
    pub damping: f32,
    // 0..1 how much sliding a terrain contact eats per substep
    pub friction: f32,
    // blocks/second below which a point starts counting towards sleep
    pub sleep_speed: f32,
    pub sleep_ticks: u8,
    // an awake point hitting a sleeper faster than this wakes it. slower = sleeper acts like a wall
    pub wake_speed: f32,
    pub iterations: u32,
    // extra joint passes per iteration. ropes stretch like chewing gum with just one
    pub joint_iterations: u32,
    // max blocks moved per substep, kills tunneling and keeps the grid honest
    pub max_step: f32,
    pub terrain: bool,
    // flat floor for non mc use and tests. NaN = no floor
    pub floor_y: f32,
    // below this the point just dies
    pub kill_y: f32,
    pub threads: usize,
    // forget terrain sections untouched for this many ticks. 0 = never
    pub terrain_forget_ticks: u32,
}

impl Default for KoperElpeConfig {
    fn default() -> Self {
        KoperElpeConfig {
            gravity: [0.0, -28.0, 0.0], // same as khysics, mc feels floaty with real 9.81
            damping: 0.999,
            friction: 0.4,
            sleep_speed: 0.08,
            sleep_ticks: 20,
            wake_speed: 1.5,
            iterations: 2,
            joint_iterations: 4,
            max_step: 0.45,
            terrain: true,
            floor_y: f32::NAN,
            kill_y: -2048.0,
            threads: std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
            terrain_forget_ticks: 1200,
        }
    }
}

#[derive(Clone, Copy)]
pub struct KoperJoint {
    pub a: u32,
    // NONE = pinned to `anchor` in the world
    pub b: u32,
    pub min: f32,
    pub max: f32,
    pub stiffness: f32,
    // stretch past the allowed range that snaps it. <= 0 = unbreakable
    pub snap: f32,
    pub anchor: [f32; 3],
    pub alive: bool,
}

#[derive(Default, Clone, Copy)]
pub struct KoperElpeStats {
    pub live: u32,
    pub awake: u32,
    pub asleep: u32,
    pub frozen: u32,
    pub joints: u32,
    pub sections: u32,
    pub step_us: u32,
    pub snapped: u32,
}

pub struct KoperElpeWorld {
    pub cfg: KoperElpeConfig,
    pub max_radius: f32,
    pub pos: Vec<[f32; 3]>,
    pub prev: Vec<[f32; 3]>,
    pub radius: Vec<f32>,
    pub inv_mass: Vec<f32>,
    pub group: Vec<u32>,
    pub state: Vec<u8>,
    still: Vec<u8>,
    free: Vec<u32>,
    // ids freed this tick. they only become reusable after the awake list got compacted,
    // otherwise a respawned id could sit in the awake list twice
    freed: Vec<u32>,
    pub awake: Vec<u32>,
    pub grid: KoperGrid,
    pub joints: Vec<KoperJoint>,
    joint_free: Vec<u32>,
    pub terrain: KoperTerrain,
    delta: Vec<[f32; 3]>,
    newk: Vec<u8>,
    pub h: f32,
    pub tick: u32,
    pub stats: KoperElpeStats,
    live: u32,
    frozen_n: u32,
    crew: KoperCrew,
    // kontraptions. own list, own solver, shares our terrain
    pub klocs: KoperKlocWorld,
}

// raw pointer that we promise to only touch at unique indices from each thread
#[derive(Clone, Copy)]
struct Koptr<T>(*mut T);
unsafe impl<T> Send for Koptr<T> {}
unsafe impl<T> Sync for Koptr<T> {}
impl<T> Koptr<T> {
    #[inline(always)]
    unsafe fn at(self, i: usize) -> &'static mut T { &mut *self.0.add(i) }
}

// splits 0..n into chunks for the crew. small jobs stay on the calling thread
fn koper_par<R: Send>(crew: &KoperCrew, n: usize, f: impl Fn(usize, usize) -> R + Sync) -> Vec<R> {
    const MIN_CHUNK: usize = 1024;
    // a few more chunks than threads so a slow core doesnt hold everyone up
    let parts = (crew.size() * 4).min(n / MIN_CHUNK).max(1);
    if parts == 1 { return vec![f(0, n)]; }
    let per = n.div_ceil(parts);
    let slots: Vec<Mutex<Option<R>>> = (0..parts).map(|_| Mutex::new(None)).collect();
    crew.run(parts, &|k| {
        let (a, b) = (k * per, ((k + 1) * per).min(n));
        let r = f(a, b);
        *slots[k].lock().unwrap() = Some(r);
    });
    slots.into_iter().filter_map(|m| m.into_inner().unwrap()).collect()
}

#[inline(always)] fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] - b[0], a[1] - b[1], a[2] - b[2]] }
#[inline(always)] fn add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] { [a[0] + b[0], a[1] + b[1], a[2] + b[2]] }
#[inline(always)] fn mul(a: [f32; 3], s: f32) -> [f32; 3] { [a[0] * s, a[1] * s, a[2] * s] }
#[inline(always)] fn dot(a: [f32; 3], b: [f32; 3]) -> f32 { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }

impl KoperElpeWorld {
    pub fn new(capacity: usize, max_radius: f32) -> Self {
        let max_radius = max_radius.clamp(0.01, 8.0);
        let mut w = KoperElpeWorld {
            cfg: KoperElpeConfig::default(),
            max_radius,
            pos: Vec::with_capacity(capacity),
            prev: Vec::with_capacity(capacity),
            radius: Vec::with_capacity(capacity),
            inv_mass: Vec::with_capacity(capacity),
            group: Vec::with_capacity(capacity),
            state: Vec::with_capacity(capacity),
            still: Vec::with_capacity(capacity),
            free: Vec::new(),
            freed: Vec::new(),
            awake: Vec::new(),
            grid: KoperGrid::new(max_radius * 2.0, capacity),
            joints: Vec::new(),
            joint_free: Vec::new(),
            terrain: KoperTerrain::default(),
            delta: Vec::new(),
            newk: Vec::new(),
            h: 1.0 / 40.0,
            tick: 0,
            stats: KoperElpeStats::default(),
            live: 0,
            frozen_n: 0,
            crew: KoperCrew::new(1),
            klocs: KoperKlocWorld::default(),
        };
        w.grid.grow_points(capacity);
        w.crew = KoperCrew::new(w.cfg.threads);
        w
    }

    pub fn high_water(&self) -> usize { self.pos.len() }

    #[inline(always)]
    fn alive(&self, i: u32) -> bool { (i as usize) < self.state.len() && self.state[i as usize] != DEAD }

    pub fn spawn(&mut self, p: [f32; 3], r: f32, inv_mass: f32, group: u32) -> u32 {
        if !(r > 0.0 && r <= self.max_radius) || !p.iter().all(|v| v.is_finite()) { return NONE; }
        let i = match self.free.pop() {
            Some(i) => {
                let iu = i as usize;
                self.pos[iu] = p; self.prev[iu] = p; self.radius[iu] = r;
                self.inv_mass[iu] = inv_mass.max(0.0); self.group[iu] = group;
                self.state[iu] = AWAKE; self.still[iu] = 0;
                i
            }
            None => {
                let i = self.pos.len() as u32;
                if i == NONE { return NONE; }
                self.pos.push(p); self.prev.push(p); self.radius.push(r);
                self.inv_mass.push(inv_mass.max(0.0)); self.group.push(group);
                self.state.push(AWAKE); self.still.push(0);
                self.grid.grow_points(self.pos.len().next_power_of_two());
                i
            }
        };
        self.live += 1;
        if self.live as usize > self.grid.buckets() {
            let hw = self.pos.len();
            let live: Vec<(u32, [f32; 3])> = (0..hw as u32).filter(|&j| self.state[j as usize] != DEAD && j != i)
                .map(|j| (j, self.pos[j as usize])).collect();
            self.grid.rehash(hw * 2, live.into_iter());
        }
        self.grid.insert(i, p);
        self.awake.push(i);
        i
    }

    // for loading saved stuff: goes straight to sleep, costs nothing until something touches it
    pub fn spawn_asleep(&mut self, p: [f32; 3], r: f32, inv_mass: f32, group: u32) -> u32 {
        let i = self.spawn(p, r, inv_mass, group);
        if i != NONE {
            self.state[i as usize] = ASLEEP;
            self.awake.pop();
        }
        i
    }

    pub fn despawn(&mut self, i: u32) {
        if !self.alive(i) { return; }
        self.grid.remove(i);
        if self.state[i as usize] == FROZEN { self.frozen_n -= 1; }
        self.state[i as usize] = DEAD;
        self.freed.push(i);
        self.live -= 1;
        // joints pointing at it die lazily in solve_joints, awake list drops it at tick end
    }

    pub fn wake(&mut self, i: u32) {
        let iu = i as usize;
        if iu < self.state.len() && (self.state[iu] == ASLEEP || self.state[iu] == FROZEN) {
            if self.state[iu] == FROZEN { self.frozen_n -= 1; }
            self.state[iu] = AWAKE;
            self.still[iu] = 0;
            self.awake.push(i);
        }
    }

    pub fn set_pos(&mut self, i: u32, p: [f32; 3], keep_velocity: bool) {
        if !self.alive(i) || !p.iter().all(|v| v.is_finite()) { return; }
        let iu = i as usize;
        let v = sub(self.pos[iu], self.prev[iu]);
        self.pos[iu] = p;
        self.prev[iu] = if keep_velocity { sub(p, v) } else { p };
        self.grid.moved(i, p);
        self.wake(i);
    }

    // velocity in blocks/second, verlet stores it as displacement per substep
    pub fn add_velocity(&mut self, i: u32, v: [f32; 3]) {
        if !self.alive(i) { return; }
        let iu = i as usize;
        self.prev[iu] = sub(self.prev[iu], mul(v, self.h));
        self.wake(i);
    }

    pub fn velocity(&self, i: u32) -> [f32; 3] {
        if !self.alive(i) { return [0.0; 3]; }
        mul(sub(self.pos[i as usize], self.prev[i as usize]), 1.0 / self.h)
    }

    pub fn joint(&mut self, j: KoperJoint) -> u32 {
        if !self.alive(j.a) || (j.b != NONE && (!self.alive(j.b) || j.b == j.a)) { return NONE; }
        let (a, b) = (j.a, j.b);
        let id = match self.joint_free.pop() {
            Some(id) => { self.joints[id as usize] = j; id }
            None => { self.joints.push(j); (self.joints.len() - 1) as u32 }
        };
        self.wake(a);
        if b != NONE { self.wake(b); }
        id
    }

    pub fn unjoint(&mut self, id: u32) {
        if let Some(j) = self.joints.get_mut(id as usize) {
            if j.alive {
                j.alive = false;
                self.joint_free.push(id);
                let (a, b) = (j.a, j.b);
                self.wake(a);
                if b != NONE { self.wake(b); }
            }
        }
    }

    pub fn query_sphere(&self, c: [f32; 3], r: f32, mut f: impl FnMut(u32)) {
        let m = r + self.max_radius;
        self.grid.for_box(sub(c, [m; 3]), add(c, [m; 3]), |j| {
            let d = sub(self.pos[j as usize], c);
            let rr = r + self.radius[j as usize];
            if dot(d, d) <= rr * rr { f(j); }
        });
    }

    pub fn wake_sphere(&mut self, c: [f32; 3], r: f32) -> u32 {
        let mut hit = Vec::new();
        self.query_sphere(c, r, |j| hit.push(j));
        let n = hit.len() as u32;
        for j in hit { self.wake(j); }
        n
    }

    // explosion-ish: push everything away from c, falloff linear to 0 at r
    pub fn blast(&mut self, c: [f32; 3], r: f32, speed: f32) -> u32 {
        let mut hit = Vec::new();
        self.query_sphere(c, r, |j| hit.push(j));
        for &j in &hit {
            let d = sub(self.pos[j as usize], c);
            let len = dot(d, d).sqrt().max(1e-4);
            let k = speed * (1.0 - len / r).max(0.0) / len;
            if self.inv_mass[j as usize] > 0.0 { self.add_velocity(j, mul(d, k)); }
        }
        hit.len() as u32
    }

    pub fn set_section(&mut self, key: u64, bits: Option<Box<[u64; 64]>>) {
        let waiting = self.terrain.set_section(key, bits, self.tick);
        for i in waiting { if self.state[i as usize] == FROZEN { self.wake(i); } }
    }

    pub fn set_block(&mut self, x: i32, y: i32, z: i32, solid: bool) -> bool {
        let had = self.terrain.set_block(x, y, z, solid);
        // stuff resting on a block that vanished (or stuck in one that appeared) has to notice.
        // even when the section is not cached — sleepers dont keep their terrain around
        self.wake_sphere([x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5], 1.0);
        had
    }

    pub fn step(&mut self, dt: f32, substeps: u32) {
        let t0 = std::time::Instant::now();
        let substeps = substeps.clamp(1, 16);
        if self.crew.size() != self.cfg.threads.max(1) { self.crew = KoperCrew::new(self.cfg.threads); }
        self.h = (dt / substeps as f32).max(1e-5);
        self.stats.snapped = 0;
        for _ in 0..substeps { self.substep(); }
        self.sleep_and_compact();
        if !self.klocs.klocs.is_empty() {
            self.klocs.gravity = self.cfg.gravity;
            self.klocs.max_step = self.cfg.max_step;
            let mut want: Vec<u64> = Vec::new();
            self.klocs.step(dt, substeps.min(4), &self.terrain, self.tick, self.cfg.terrain, &mut want);
            for key in want { self.terrain.ask(key); }
        }
        self.tick = self.tick.wrapping_add(1);
        if self.cfg.terrain_forget_ticks > 0 && self.tick % 200 == 0 {
            self.terrain.forget_old(self.tick, self.cfg.terrain_forget_ticks);
        }
        self.stats.step_us = t0.elapsed().as_micros().min(u32::MAX as u128) as u32;
    }

    fn substep(&mut self) {
        self.predict();
        for _ in 0..self.cfg.iterations.max(1) {
            if !self.joints.is_empty() {
                for k in 0..self.cfg.joint_iterations.max(1) { self.solve_joints(k & 1 == 1); }
            }
            self.solve_contacts();
        }
        self.solve_world();
    }

    fn predict(&mut self) {
        let n = self.awake.len();
        if self.newk.len() < n { self.newk.resize(n, 0); }
        let (h, cfg) = (self.h, self.cfg);
        let g = mul(cfg.gravity, h * h);
        let ms = cfg.max_step;
        let pos = Koptr(self.pos.as_mut_ptr());
        let prev = Koptr(self.prev.as_mut_ptr());
        let moved = Koptr(self.newk.as_mut_ptr());
        let (awake, state, inv_mass, grid) = (&self.awake, &self.state, &self.inv_mass, &self.grid);
        koper_par(&self.crew, n, |a, b| {
            for k in a..b {
                let i = awake[k] as usize;
                let m = unsafe { moved.at(k) };
                *m = 0;
                if state[i] != AWAKE { continue; }
                let (p, q) = unsafe { (pos.at(i), prev.at(i)) };
                if inv_mass[i] == 0.0 { *q = *p; continue; }
                let mut v = mul(sub(*p, *q), cfg.damping);
                let v2 = dot(v, v);
                if v2 > ms * ms { v = mul(v, ms / v2.sqrt()); }
                *q = *p;
                *p = add(add(*p, v), g);
                let (cx, cy, cz) = grid.cell_of(*p);
                if grid.cellk[i] != crate::grid::pack_cell(cx, cy, cz) { *m = 1; }
            }
        });
        // relink only the ones that changed cell. sequential but cheap
        for k in 0..n {
            if self.newk[k] != 0 {
                let i = self.awake[k];
                self.grid.moved(i, self.pos[i as usize]);
            }
        }
    }

    // backwards every other pass so a chain pulls from both ends, not just the pinned one
    fn solve_joints(&mut self, backwards: bool) {
        let n = self.joints.len();
        for k in 0..n {
            let id = if backwards { n - 1 - k } else { k };
            let j = self.joints[id];
            if !j.alive { continue; }
            let (a, b) = (j.a as usize, j.b);
            if self.state[a] == DEAD || (b != NONE && self.state[b as usize] == DEAD) {
                self.joints[id].alive = false;
                self.joint_free.push(id as u32);
                continue;
            }
            let sa = self.state[a];
            let sb = if b == NONE { ASLEEP } else { self.state[b as usize] };
            if sa != AWAKE && sb != AWAKE { continue; }
            let pb = if b == NONE { j.anchor } else { self.pos[b as usize] };
            let d = sub(self.pos[a], pb);
            let len = dot(d, d).sqrt();
            let target = len.clamp(j.min, j.max);
            let c = len - target;
            if c.abs() < 1e-6 { continue; }
            if j.snap > 0.0 && c.abs() > j.snap {
                self.joints[id].alive = false;
                self.joint_free.push(id as u32);
                self.stats.snapped += 1;
                continue;
            }
            // a sleeper on the other end of a stretched joint has to wake up
            if sa == ASLEEP { self.wake(a as u32); }
            if b != NONE && sb == ASLEEP { self.wake(b); }
            let wa = if self.state[a] == AWAKE { self.inv_mass[a] } else { 0.0 };
            let wb = if b != NONE && self.state[b as usize] == AWAKE { self.inv_mass[b as usize] } else { 0.0 };
            let w = wa + wb;
            if w <= 0.0 || len < 1e-6 { continue; }
            let corr = mul(d, c / len * j.stiffness / w);
            self.pos[a] = sub(self.pos[a], mul(corr, wa));
            if b != NONE { self.pos[b as usize] = add(self.pos[b as usize], mul(corr, wb)); }
        }
    }

    // point vs point, jacobi: each awake point only writes its own delta so threads never fight
    fn solve_contacts(&mut self) {
        let n = self.awake.len();
        if self.delta.len() < n { self.delta.resize(n, [0.0; 3]); }
        let delta = Koptr(self.delta.as_mut_ptr());
        let (h, wake_speed) = (self.h, self.cfg.wake_speed);
        let wake2 = (wake_speed * h) * (wake_speed * h);
        let mr = self.max_radius;
        // shock propagation: whoever is lower along gravity counts as heavier, stacks stop squishing
        let gl = dot(self.cfg.gravity, self.cfg.gravity).sqrt();
        let up = if gl > 1e-6 { mul(self.cfg.gravity, -1.0 / gl) } else { [0.0; 3] };
        let (awake, state, pos, prev, radius, inv_mass, group, grid) =
            (&self.awake, &self.state, &self.pos, &self.prev, &self.radius, &self.inv_mass, &self.group, &self.grid);
        let wakes = koper_par(&self.crew, n, |a, b| {
            let mut wakes: Vec<u32> = Vec::new();
            for k in a..b {
                let i = awake[k] as usize;
                let out = unsafe { delta.at(k) };
                *out = [0.0; 3];
                if state[i] != AWAKE || inv_mass[i] == 0.0 { continue; }
                let (p, r, gi) = (pos[i], radius[i], group[i]);
                let fast = { let v = sub(p, prev[i]); dot(v, v) > wake2 };
                let m = r + mr;
                let mut acc = [0.0f32; 3];
                let mut cnt = 0u32;
                grid.for_box(sub(p, [m; 3]), add(p, [m; 3]), |j| {
                    let ju = j as usize;
                    if ju == i || (gi != 0 && group[ju] == gi) { return; }
                    let d = sub(p, pos[ju]);
                    let rr = r + radius[ju];
                    let d2 = dot(d, d);
                    if d2 >= rr * rr { return; }
                    let len = d2.sqrt();
                    let nrm = if len > 1e-6 { mul(d, 1.0 / len) } else { [0.0, 1.0, 0.0] };
                    let sj = state[ju];
                    // sleepers and frozen ones are walls unless we hit them hard
                    let wi = if sj == AWAKE {
                        let lift = (dot(d, up) * -4.0).clamp(-8.0, 8.0).exp();
                        let wj = inv_mass[ju] * lift;
                        inv_mass[i] / (inv_mass[i] + wj)
                    } else {
                        if sj == ASLEEP && fast { wakes.push(j); }
                        1.0
                    };
                    acc = add(acc, mul(nrm, (rr - len) * wi));
                    cnt += 1;
                });
                if cnt > 0 {
                    // jacobi averaging with a bit of over relaxation, the usual pbd trick
                    let s = (1.5 / cnt as f32).min(1.0);
                    *out = mul(acc, s);
                }
            }
            wakes
        });
        let pos = Koptr(self.pos.as_mut_ptr());
        let (awake, delta) = (&self.awake, &self.delta);
        koper_par(&self.crew, n, |a, b| {
            for k in a..b {
                let d = delta[k];
                if d[0] != 0.0 || d[1] != 0.0 || d[2] != 0.0 {
                    let p = unsafe { pos.at(awake[k] as usize) };
                    *p = add(*p, d);
                }
            }
        });
        for list in wakes { for j in list { self.wake(j); } }
    }

    // terrain + floor + kill plane. every point only touches itself here
    fn solve_world(&mut self) {
        let n = self.awake.len();
        let cfg = self.cfg;
        let pos = Koptr(self.pos.as_mut_ptr());
        let prev = Koptr(self.prev.as_mut_ptr());
        let (awake, state, radius, terrain, stamp) = (&self.awake, &self.state, &self.radius, &self.terrain, self.tick);
        let results = koper_par(&self.crew, n, |a, b| {
            let mut frozen: Vec<(u32, u64)> = Vec::new();
            let mut dead: Vec<u32> = Vec::new();
            let mut cache: (u64, *const crate::terrain::KoperSection) = (u64::MAX, std::ptr::null());
            for k in a..b {
                let i = awake[k] as usize;
                if state[i] != AWAKE { continue; }
                let (p, q) = unsafe { (pos.at(i), prev.at(i)) };
                if p[1] < cfg.kill_y { dead.push(i as u32); continue; }
                let r = radius[i];
                let mut nacc = [0.0f32; 3];
                if !cfg.floor_y.is_nan() && p[1] - r < cfg.floor_y {
                    p[1] = cfg.floor_y + r;
                    nacc[1] += 1.0;
                }
                if cfg.terrain {
                    match koper_voxel_push(terrain, p, r, stamp, &mut cache) {
                        Ok(nv) => nacc = add(nacc, nv),
                        Err(key) => { *q = *p; frozen.push((i as u32, key)); continue; }
                    }
                }
                if nacc != [0.0; 3] && cfg.friction > 0.0 {
                    let nl = dot(nacc, nacc).sqrt();
                    let nrm = mul(nacc, 1.0 / nl);
                    let v = sub(*p, *q);
                    let vt = sub(v, mul(nrm, dot(v, nrm)));
                    *q = add(*q, mul(vt, cfg.friction));
                }
            }
            (frozen, dead)
        });
        for (frozen, dead) in results {
            for (i, key) in frozen {
                self.state[i as usize] = FROZEN;
                self.frozen_n += 1;
                self.terrain.frozen.entry(key).or_default().push(i);
                self.terrain.ask(key);
            }
            for i in dead { self.despawn(i); }
        }
    }

    fn sleep_and_compact(&mut self) {
        let n = self.awake.len();
        let cfg = self.cfg;
        let lim = cfg.sleep_speed * self.h;
        let lim2 = lim * lim;
        let prev = Koptr(self.prev.as_mut_ptr());
        let still = Koptr(self.still.as_mut_ptr());
        let state = Koptr(self.state.as_mut_ptr());
        let (awake, pos) = (&self.awake, &self.pos);
        koper_par(&self.crew, n, |a, b| {
            for k in a..b {
                let i = awake[k] as usize;
                let st = unsafe { state.at(i) };
                if *st != AWAKE { continue; }
                let q = unsafe { prev.at(i) };
                let v = sub(pos[i], *q);
                let s = unsafe { still.at(i) };
                if dot(v, v) < lim2 {
                    *s = s.saturating_add(1);
                    if *s >= cfg.sleep_ticks { *st = ASLEEP; *q = pos[i]; }
                } else {
                    *s = 0;
                }
            }
        });
        let state = &self.state;
        self.awake.retain(|&i| state[i as usize] == AWAKE);
        // ids spawned together are usually near each other, walking them in order is way kinder to the cache
        if self.awake.len() <= 1 << 18 { self.awake.sort_unstable(); }
        self.free.append(&mut self.freed);
        self.recount();
    }

    // counters only, never scan the state array — at 10M that alone was 8ms
    fn recount(&mut self) {
        self.stats.live = self.live;
        self.stats.awake = self.awake.len() as u32;
        self.stats.frozen = self.frozen_n;
        self.stats.asleep = self.live - self.stats.awake - self.frozen_n;
        self.stats.joints = (self.joints.len() - self.joint_free.len()) as u32;
        self.stats.sections = self.terrain.map.len() as u32;
    }

    pub fn drain_requests(&mut self, out: &mut [i32]) -> usize {
        let n = (out.len() / 3).min(self.terrain.requests.len());
        let drained: Vec<u64> = self.terrain.requests.drain(..n).collect();
        for (k, key) in drained.into_iter().enumerate() {
            let s = unpack_section(key);
            out[k * 3..k * 3 + 3].copy_from_slice(&s);
        }
        n
    }
}

// sphere vs solid unit voxels. returns summed push normals, or the section we dont have
#[inline]
fn koper_voxel_push(
    t: &KoperTerrain, p: &mut [f32; 3], r: f32, stamp: u32,
    cache: &mut (u64, *const crate::terrain::KoperSection),
) -> Result<[f32; 3], u64> {
    let mut nacc = [0.0f32; 3];
    let x0 = (p[0] - r).floor() as i32; let x1 = (p[0] + r).floor() as i32;
    let y0 = (p[1] - r).floor() as i32; let y1 = (p[1] + r).floor() as i32;
    let z0 = (p[2] - r).floor() as i32; let z1 = (p[2] + r).floor() as i32;
    // the voxel the center is in goes first, it decides the deep-inside case
    for y in y0..=y1 {
        for z in z0..=z1 {
            for x in x0..=x1 {
                match t.peek(x, y, z, stamp, cache) {
                    Peek::Air => continue,
                    Peek::Unknown(k) => return Err(k),
                    Peek::Solid => {}
                }
                let v = [x as f32, y as f32, z as f32];
                let qx = p[0].clamp(v[0], v[0] + 1.0);
                let qy = p[1].clamp(v[1], v[1] + 1.0);
                let qz = p[2].clamp(v[2], v[2] + 1.0);
                let d = [p[0] - qx, p[1] - qy, p[2] - qz];
                let d2 = dot(d, d);
                if d2 >= r * r { continue; }
                if d2 > 1e-10 {
                    let len = d2.sqrt();
                    let nrm = mul(d, 1.0 / len);
                    *p = add(*p, mul(nrm, r - len));
                    nacc = add(nacc, nrm);
                } else {
                    // center inside the block. leave through the closest face that has air behind it
                    let faces: [([i32; 3], f32, usize, f32); 6] = [
                        ([0, 1, 0], v[1] + 1.0 - p[1], 1, 1.0),
                        ([0, -1, 0], p[1] - v[1], 1, -1.0),
                        ([1, 0, 0], v[0] + 1.0 - p[0], 0, 1.0),
                        ([-1, 0, 0], p[0] - v[0], 0, -1.0),
                        ([0, 0, 1], v[2] + 1.0 - p[2], 2, 1.0),
                        ([0, 0, -1], p[2] - v[2], 2, -1.0),
                    ];
                    let mut best: Option<(f32, usize, f32)> = None;
                    for (o, dist, axis, sign) in faces {
                        let free = matches!(t.peek(x + o[0], y + o[1], z + o[2], stamp, cache), Peek::Air);
                        if free && best.map_or(true, |b| dist < b.0) { best = Some((dist, axis, sign)); }
                    }
                    // buried completely: go up, mc players do the same
                    let (dist, axis, sign) = best.unwrap_or((v[1] + 1.0 - p[1], 1, 1.0));
                    p[axis] += sign * (dist + r);
                    nacc[axis] += sign;
                }
            }
        }
    }
    Ok(nacc)
}
