// section terrain — 16^3 occupancy bitsets streamed from Java, one static collider per section.
// replaces the old scan-bubble global compound: only the touched section re-meshes, every other
// section keeps its contacts alive → no stale-contact ejects, no radius cap, huge kontras covered.
use rapier3d::prelude::*;
use glam::Vec3;
use std::collections::{HashMap, HashSet};

use crate::terrain;

pub const SEC: i32 = 16;
// a section nobody wanted for this many steps gets dropped (60Hz → ~10s)
const STALE_STEPS: u64 = 600;

pub struct Section {
    pub bits: Box<[u64; 64]>, // 4096 cells, bit index = (ly*16 + lz)*16 + lx
    // water occupancy, same indexing. None = dry section (most of them) — no 512B for deserts
    pub fluid: Option<Box<[u64; 64]>>,
    pub collider: Option<ColliderHandle>,
    pub dirty: bool,
    pub last_wanted: u64,
}

impl Section {
    #[inline]
    pub fn get(&self, lx: i32, ly: i32, lz: i32) -> bool {
        let idx = ((ly * 16 + lz) * 16 + lx) as usize;
        self.bits[idx >> 6] & (1u64 << (idx & 63)) != 0
    }

    // returns true when the bit actually flipped
    #[inline]
    pub fn set(&mut self, lx: i32, ly: i32, lz: i32, solid: bool) -> bool {
        let idx = ((ly * 16 + lz) * 16 + lx) as usize;
        let word = idx >> 6;
        let mask = 1u64 << (idx & 63);
        let was = self.bits[word] & mask != 0;
        if was == solid { return false; }
        if solid { self.bits[word] |= mask; } else { self.bits[word] &= !mask; }
        true
    }

    pub fn is_empty(&self) -> bool {
        self.bits.iter().all(|w| *w == 0)
    }

    #[inline]
    pub fn get_fluid(&self, lx: i32, ly: i32, lz: i32) -> bool {
        let f = match &self.fluid { Some(f) => f, None => return false };
        let idx = ((ly * 16 + lz) * 16 + lx) as usize;
        f[idx >> 6] & (1u64 << (idx & 63)) != 0
    }

    #[inline]
    pub fn set_fluid_bit(&mut self, lx: i32, ly: i32, lz: i32, fluid: bool) {
        if self.fluid.is_none() {
            if !fluid { return; } // clearing a bit in a dry section = nothing to do
            self.fluid = Some(Box::new([0u64; 64]));
        }
        let f = self.fluid.as_mut().unwrap();
        let idx = ((ly * 16 + lz) * 16 + lx) as usize;
        if fluid { f[idx >> 6] |= 1u64 << (idx & 63); } else { f[idx >> 6] &= !(1u64 << (idx & 63)); }
    }
}

// floor-div that works for negative world coords (Rust / truncates toward zero, we need floor)
#[inline]
pub fn sec_coord(v: i32) -> i32 { v.div_euclid(SEC) }
#[inline]
pub fn sec_local(v: i32) -> i32 { v.rem_euclid(SEC) }

pub struct SectionStore {
    pub sections: HashMap<[i32; 3], Section>,
    // wanted but not uploaded yet — Java polls this and feeds us the bitsets
    pub missing: Vec<[i32; 3]>,
    // count of sections that carry any fluid — lets the water pass bail in one branch on dry worlds
    wet_sections: u32,
}

impl SectionStore {
    pub fn new() -> Self {
        Self { sections: HashMap::new(), missing: Vec::new(), wet_sections: 0 }
    }

    pub fn upload(&mut self, pos: [i32; 3], bits: Box<[u64; 64]>, step: u64) {
        // Only re-mesh when the occupancy ACTUALLY changed. This used to set dirty unconditionally,
        // and TerrainSlurper re-uploads wanted sections every server tick (plus primeArea before
        // every spawn), so identical bits still tore the section collider down and built a new one.
        // Every rebuild removes the collider with wake_bodies=true: every kontra resting on that
        // section loses its resting contacts and their warm-start impulses, wakes up, sinks for a
        // step, and gets shoved back out by penetration recovery. That is why placing or breaking a
        // block — a lift, a bearing, anything — kicked every build standing nearby at once, and why
        // it happened again on every spawn.
        if let Some(s) = self.sections.get_mut(&pos) {
            if *s.bits != *bits { s.bits = bits; s.dirty = true; }
            s.last_wanted = step;
            return;
        }
        self.sections.insert(pos, Section {
            bits, fluid: None, collider: None, dirty: true, last_wanted: step });
    }

    // fluid bits land ONLY on a section whose solids were already uploaded — Java sends the pair
    // solids-first, so an unknown section here means the solids upload is right behind in the queue
    pub fn upload_fluids(&mut self, pos: [i32; 3], bits: Box<[u64; 64]>) {
        if let Some(s) = self.sections.get_mut(&pos) {
            let has_any = bits.iter().any(|w| *w != 0);
            let had_any = s.fluid.is_some();
            s.fluid = if has_any { Some(bits) } else { None };
            match (had_any, has_any) {
                (false, true) => self.wet_sections += 1,
                (true, false) => self.wet_sections = self.wet_sections.saturating_sub(1),
                _ => {}
            }
        }
    }

    pub fn set_fluid(&mut self, wx: i32, wy: i32, wz: i32, fluid: bool) {
        let key = [sec_coord(wx), sec_coord(wy), sec_coord(wz)];
        if let Some(s) = self.sections.get_mut(&key) {
            let had = s.fluid.is_some();
            s.set_fluid_bit(sec_local(wx), sec_local(wy), sec_local(wz), fluid);
            if !had && s.fluid.is_some() { self.wet_sections += 1; }
        }
    }

    pub fn fluid_at(&self, wx: i32, wy: i32, wz: i32) -> bool {
        let key = [sec_coord(wx), sec_coord(wy), sec_coord(wz)];
        match self.sections.get(&key) {
            Some(s) => s.get_fluid(sec_local(wx), sec_local(wy), sec_local(wz)),
            None => false,
        }
    }

    pub fn any_fluid(&self) -> bool { self.wet_sections > 0 }

    // single world cell flip from a block change — ignored if we don't cache that section
    pub fn set_block(&mut self, wx: i32, wy: i32, wz: i32, solid: bool) {
        let key = [sec_coord(wx), sec_coord(wy), sec_coord(wz)];
        if let Some(s) = self.sections.get_mut(&key) {
            if s.set(sec_local(wx), sec_local(wy), sec_local(wz), solid) {
                s.dirty = true;
            }
        }
    }

    pub fn solid_at(&self, wx: i32, wy: i32, wz: i32) -> bool {
        let key = [sec_coord(wx), sec_coord(wy), sec_coord(wz)];
        match self.sections.get(&key) {
            Some(s) => s.get(sec_local(wx), sec_local(wy), sec_local(wz)),
            None => false,
        }
    }

    pub fn has(&self, key: &[i32; 3]) -> bool {
        self.sections.contains_key(key)
    }

    // re-mesh every dirty section: swap ONLY its collider, neighbours keep their contacts.
    // wake_bodies=true on remove so a resting kontra re-detects the fresh surface.
    pub fn rebuild_dirty(
        &mut self,
        colliders: &mut ColliderSet,
        islands: &mut IslandManager,
        bodies: &mut RigidBodySet,
        groups: InteractionGroups,
    ) {
        for (key, s) in self.sections.iter_mut() {
            if !s.dirty { continue; }
            s.dirty = false;
            if let Some(old) = s.collider.take() {
                colliders.remove(old, islands, bodies, true);
            }
            if s.is_empty() { continue; }

            // collect local coords → greedy mesh → compound anchored at the section origin
            let mut cells: HashSet<[i32; 3]> = HashSet::new();
            for ly in 0..SEC { for lz in 0..SEC { for lx in 0..SEC {
                if s.get(lx, ly, lz) { cells.insert([lx, ly, lz]); }
            }}}
            let shapes = terrain::greedy_mesh(&cells);
            if shapes.is_empty() { continue; }
            // no contact_skin here or on kontra blocks — the skins added up to a visible
            // ~0.04 hover gap between a parked kontra and the ground
            let col = ColliderBuilder::compound(shapes)
                .translation(Vec3::new(
                    (key[0] * SEC) as f32,
                    (key[1] * SEC) as f32,
                    (key[2] * SEC) as f32,
                ))
                .friction(0.7)
                .restitution(0.0)
                .collision_groups(groups)
                .build();
            s.collider = Some(colliders.insert(col));
        }
    }

    // wanted = sections any kontra could touch soon. refresh stamps, list what's missing,
    // drop what nobody wanted for a while.
    pub fn update_wanted(
        &mut self,
        wanted: &HashSet<[i32; 3]>,
        step: u64,
        colliders: &mut ColliderSet,
        islands: &mut IslandManager,
        bodies: &mut RigidBodySet,
    ) {
        self.missing.clear();
        for key in wanted {
            match self.sections.get_mut(key) {
                Some(s) => s.last_wanted = step,
                None => self.missing.push(*key),
            }
        }
        // stale cleanup — rare, so the drain-and-reinsert dance is fine
        let stale: Vec<[i32; 3]> = self.sections.iter()
            .filter(|(_, s)| step.saturating_sub(s.last_wanted) > STALE_STEPS)
            .map(|(k, _)| *k)
            .collect();
        for key in stale {
            if let Some(s) = self.sections.remove(&key) {
                if s.fluid.is_some() { self.wet_sections = self.wet_sections.saturating_sub(1); }
                if let Some(col) = s.collider {
                    colliders.remove(col, islands, bodies, true);
                }
            }
        }
    }
}
