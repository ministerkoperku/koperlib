// per-kontraktion geometry cache — dirty tracking, lazy rebuild
#![allow(dead_code)]
// global singleton behind OnceLock<Mutex> — fine for our single-threaded caller pattern

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use crate::types::{KontraBlock, KontraGeometry};

static KACHE: OnceLock<Mutex<HashMap<i64, KontraGeometry>>> = OnceLock::new();

// yeah this is a bit silly but its consistent with the rest of the codebase lol
pub fn kache() -> &'static Mutex<HashMap<i64, KontraGeometry>> {
    KACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn set_blocks(id: i64, blocks: Vec<KontraBlock>) {
    let mut c = kache().lock().unwrap();
    let geo = c.entry(id).or_insert_with(KontraGeometry::new);
    geo.blocks = blocks;
    geo.dirty = true;
}

/// Rebuild if dirty, then return (ptr, float_count). None if no blocks.
pub fn get_mesh_ptr(id: i64) -> Option<(*const f32, u32)> {
    let mut c = kache().lock().unwrap();
    let geo = c.get_mut(&id)?;
    if geo.dirty {
        crate::geometry::rebuild_mesh(geo);
    }
    if geo.vertex_buf.is_empty() {
        return None;
    }
    Some((geo.vertex_buf.as_ptr(), geo.vertex_buf.len() as u32))
}

/// Total visible face count after last rebuild. -1 if id unknown.
pub fn face_count(id: i64) -> i32 {
    let c = kache().lock().unwrap();
    c.get(&id).map(|g| g.face_count as i32).unwrap_or(-1)
}

/// Copy per-block face masks into caller-provided buffer.
/// Triggers rebuild if dirty. Returns -1 if id unknown.
pub fn face_masks_copy(id: i64, out: *mut u8, out_count: *mut i32) -> i32 {
    let mut c = kache().lock().unwrap();
    let geo = match c.get_mut(&id) {
        Some(g) => g,
        None => {
            unsafe { *out_count = 0; }
            return -1;
        }
    };
    if geo.dirty {
        crate::geometry::rebuild_mesh(geo);
    }
    let n = geo.face_masks.len();
    unsafe {
        *out_count = n as i32;
        if !out.is_null() && n > 0 {
            std::ptr::copy_nonoverlapping(geo.face_masks.as_ptr(), out, n);
        }
    }
    0
}

pub fn mark_dirty(id: i64) {
    let mut c = kache().lock().unwrap();
    if let Some(g) = c.get_mut(&id) {
        g.dirty = true;
    }
}

pub fn remove(id: i64) {
    kache().lock().unwrap().remove(&id);
}

pub fn clear_all() {
    kache().lock().unwrap().clear();
}
