// koper voxel peeker — mc terrain as 16³ bitsets, 512 bytes per section.
// java feeds sections on demand: a point that walks into an unknown section freezes
// and asks for it, so we never hold terrain nobody is standing on

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};
use std::sync::atomic::{AtomicU32, Ordering};

#[derive(Default)]
pub struct KoperFxHasher(u64);

impl Hasher for KoperFxHasher {
    fn finish(&self) -> u64 { self.0 }
    fn write(&mut self, bytes: &[u8]) {
        for b in bytes { self.write_u64(*b as u64); }
    }
    #[inline(always)]
    fn write_u64(&mut self, v: u64) {
        self.0 = (self.0.rotate_left(5) ^ v).wrapping_mul(0x517c_c1b7_2722_0a95);
    }
}

pub type KoperFxMap<K, V> = HashMap<K, V, BuildHasherDefault<KoperFxHasher>>;

#[inline(always)]
pub fn pack_section(sx: i32, sy: i32, sz: i32) -> u64 {
    ((sx as u64 & 0x3F_FFFF) << 42) | ((sz as u64 & 0x3F_FFFF) << 20) | (sy as u64 & 0xF_FFFF)
}

pub fn unpack_section(k: u64) -> [i32; 3] {
    // sign extend each field back
    let sx = (((k >> 42) as i64) << 42 >> 42) as i32;
    let sz = ((((k >> 20) & 0x3F_FFFF) as i64) << 42 >> 42) as i32;
    let sy = (((k & 0xF_FFFF) as i64) << 44 >> 44) as i32;
    [sx, sy, sz]
}

pub struct KoperSection {
    // None = all air, the most common section in the sky by far
    pub bits: Option<Box<[u64; 64]>>,
    pub used: AtomicU32,
}

#[derive(Default)]
pub struct KoperTerrain {
    pub map: KoperFxMap<u64, KoperSection>,
    pub asked: KoperFxMap<u64, ()>,
    pub requests: Vec<u64>,
    // points waiting for a section to show up
    pub frozen: KoperFxMap<u64, Vec<u32>>,
}

#[inline(always)]
fn bit_index(x: i32, y: i32, z: i32) -> usize {
    (((y & 15) << 8) | ((z & 15) << 4) | (x & 15)) as usize
}

pub enum Peek { Air, Solid, Unknown(u64) }

impl KoperTerrain {
    #[inline]
    pub fn peek(&self, x: i32, y: i32, z: i32, stamp: u32, cache: &mut (u64, *const KoperSection)) -> Peek {
        let key = pack_section(x >> 4, y >> 4, z >> 4);
        let sec: &KoperSection = if cache.0 == key && !cache.1.is_null() {
            unsafe { &*cache.1 }
        } else {
            match self.map.get(&key) {
                Some(s) => {
                    // relaxed store is fine, this is just a "someone looked at it lately" stamp
                    if s.used.load(Ordering::Relaxed) != stamp { s.used.store(stamp, Ordering::Relaxed); }
                    *cache = (key, s as *const _);
                    s
                }
                None => return Peek::Unknown(key),
            }
        };
        match &sec.bits {
            None => Peek::Air,
            Some(b) => {
                let i = bit_index(x, y, z);
                if (b[i >> 6] >> (i & 63)) & 1 != 0 { Peek::Solid } else { Peek::Air }
            }
        }
    }

    pub fn set_section(&mut self, key: u64, bits: Option<Box<[u64; 64]>>, stamp: u32) -> Vec<u32> {
        // an all zero bitset is just air, dont waste 512 bytes on it
        let bits = bits.filter(|b| b.iter().any(|w| *w != 0));
        self.map.insert(key, KoperSection { bits, used: AtomicU32::new(stamp) });
        self.asked.remove(&key);
        self.frozen.remove(&key).unwrap_or_default()
    }

    // returns Some(true/false) if the section is cached, None if we never had it
    pub fn set_block(&mut self, x: i32, y: i32, z: i32, solid: bool) -> bool {
        let key = pack_section(x >> 4, y >> 4, z >> 4);
        let Some(sec) = self.map.get_mut(&key) else { return false };
        let i = bit_index(x, y, z);
        match (&mut sec.bits, solid) {
            (None, false) => {}
            (None, true) => {
                let mut b = Box::new([0u64; 64]);
                b[i >> 6] |= 1 << (i & 63);
                sec.bits = Some(b);
            }
            (Some(b), s) => {
                if s { b[i >> 6] |= 1 << (i & 63); } else { b[i >> 6] &= !(1 << (i & 63)); }
            }
        }
        true
    }

    pub fn ask(&mut self, key: u64) {
        if self.map.contains_key(&key) { return; }
        if self.asked.insert(key, ()).is_none() { self.requests.push(key); }
    }

    // forget sections nobody touched for `age` stamps. sleeping stuff doesnt need terrain
    pub fn forget_old(&mut self, now: u32, age: u32) -> usize {
        let before = self.map.len();
        self.map.retain(|_, s| now.wrapping_sub(s.used.load(Ordering::Relaxed)) < age);
        before - self.map.len()
    }
}
