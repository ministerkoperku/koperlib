//! Fast native .kodel engine.
//!
//! Parses the binary model.bin / animation.anim.bin from the .kodel spec and
//! samples per-bone world matrices, so Java never has to touch JSON and only
//! reads floats back across Panama. Mirrors KodelSampler exactly; if a value
//! drifts here and there, the Java fallback is the source of truth.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

const MAGIC: u32 = 0x4C444F4B; // "KODL"
const ENDK: u32 = 0x454E444B;   // "ENDK"

const CHANNEL_ROTATION: u8 = 1;
const CHANNEL_POSITION: u8 = 2;
const CHANNEL_SCALE: u8 = 4;

// ──────────────────────────────────────────────────────────────────────────
// Data types
// ──────────────────────────────────────────────────────────────────────────

#[derive(Clone, Copy)]
#[allow(dead_code)]
struct Face {
    u: f32,
    v: f32,
    uw: f32,
    vh: f32,
    rot: u8,
    mirror: bool,
}

#[allow(dead_code)]
#[derive(Clone)]
struct Cube {
    origin: [f32; 3],
    size: [f32; 3],
    inflate: f32,
    pivot: [f32; 3],
    rotation: [f32; 4],
    mirror: bool,
    faces: [Face; 6],
}

#[allow(dead_code)]
#[derive(Clone)]
struct Bone {
    name: String,
    parent: i32,
    pivot: [f32; 3],
    position: [f32; 3],
    rotation: [f32; 4],
    scale: [f32; 3],
    visible: bool,
    cubes: Vec<Cube>,
}

#[derive(Clone, Default)]
struct Channel {
    times: Vec<f32>,
    values: Vec<f32>, // 3 per key
    easing: Vec<u8>,
    ctrl_a: Vec<f32>, // 3 per key
    ctrl_b: Vec<f32>,
}

#[allow(dead_code)]
struct Anim {
    name: String,
    length: f32,
    loop_mode: bool,
    tracks: Vec<ResolvedTrack>,
    /// bone index -> index into tracks, one lane per channel, -1 for none.
    /// sample_pose used to scan the whole track list once per bone which is
    /// bones*tracks every single frame. split per channel so two tracks on one
    /// bone merge the same way java's ResolvedTracks does
    rot_of: Vec<i32>,
    pos_of: Vec<i32>,
    scale_of: Vec<i32>,
}

struct ResolvedTrack {
    bone: usize,
    rotation: Option<Channel>,
    position: Option<Channel>,
    scale: Option<Channel>,
}

struct NativeModel {
    bones: Vec<Bone>,
    anims: Vec<Anim>,
}

// ──────────────────────────────────────────────────────────────────────────
// Binary reader
// ──────────────────────────────────────────────────────────────────────────

struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Reader { data, at: 0 }
    }

    fn need(&self, n: usize) -> bool {
        self.at + n <= self.data.len()
    }

    fn u8(&mut self) -> Option<u8> {
        if !self.need(1) {
            return None;
        }
        let v = self.data[self.at];
        self.at += 1;
        Some(v)
    }

    fn u16(&mut self) -> Option<u16> {
        if !self.need(2) {
            return None;
        }
        let v = u16::from_le_bytes([self.data[self.at], self.data[self.at + 1]]);
        self.at += 2;
        Some(v)
    }

    fn i32(&mut self) -> Option<i32> {
        if !self.need(4) {
            return None;
        }
        let v = i32::from_le_bytes([
            self.data[self.at],
            self.data[self.at + 1],
            self.data[self.at + 2],
            self.data[self.at + 3],
        ]);
        self.at += 4;
        Some(v)
    }

    /// u32 element count, refused when the rest of the stream cannot hold that
    /// many. kodels come from content packs so a junk count must not turn into a
    /// multi-gigabyte reserve before the first read fails.
    fn count(&mut self, min_bytes_each: usize) -> Option<usize> {
        let n = self.u32()? as usize;
        let room = self.data.len().saturating_sub(self.at);
        if min_bytes_each > 0 && n > room / min_bytes_each {
            return None;
        }
        Some(n)
    }

    fn u32(&mut self) -> Option<u32> {
        self.i32().map(|v| v as u32)
    }

    fn f32(&mut self) -> Option<f32> {
        self.i32().map(|v| f32::from_bits(v as u32))
    }

    fn vec3(&mut self) -> Option<[f32; 3]> {
        Some([self.f32()?, self.f32()?, self.f32()?])
    }

    fn quat(&mut self) -> Option<[f32; 4]> {
        Some([self.f32()?, self.f32()?, self.f32()?, self.f32()?])
    }

    fn string(&mut self) -> Option<String> {
        let n = self.u16()? as usize;
        if !self.need(n) {
            return None;
        }
        let s = match std::str::from_utf8(&self.data[self.at..self.at + n]) {
            Ok(s) => s.to_string(),
            Err(_) => return None,
        };
        self.at += n;
        Some(s)
    }
}

fn header(r: &mut Reader) -> Option<()> {
    if r.u32()? != MAGIC {
        return None;
    }
    let major = r.u8()?;
    if major > 1 {
        return None;
    }
    r.u8(); // minor
    r.u16(); // flags
    Some(())
}

fn read_face(r: &mut Reader) -> Option<Face> {
    Some(Face {
        u: r.f32()?,
        v: r.f32()?,
        uw: r.f32()?,
        vh: r.f32()?,
        rot: r.u8()?,
        mirror: r.u8()? != 0,
    })
}

// smallest on-disk size of each record, mirrors KodelModel/KodelAnimation in java
const BONE_MIN_BYTES: usize = 63;
const CUBE_MIN_BYTES: usize = 165;
const MESH_MIN_BYTES: usize = 12;
const VERTEX_BYTES: usize = 32;
const ANIM_MIN_BYTES: usize = 12;
const TRACK_MIN_BYTES: usize = 3;
const KEY_MIN_BYTES: usize = 17;

fn read_model(data: &[u8]) -> Option<NativeModel> {
    let mut r = Reader::new(data);
    header(&mut r)?;
    let mut bones = Vec::new();
    let bone_count = r.count(BONE_MIN_BYTES)?;
    for _ in 0..bone_count {
        let name = r.string()?;
        let parent = r.i32()?;
        let pivot = r.vec3()?;
        let position = r.vec3()?;
        let rotation = r.quat()?;
        let scale = r.vec3()?;
        let visible = r.u8()? != 0;
        let cube_count = r.count(CUBE_MIN_BYTES)?;
        let mut cubes = Vec::with_capacity(cube_count);
        for _ in 0..cube_count {
            let origin = r.vec3()?;
            let size = r.vec3()?;
            let inflate = r.f32()?;
            let pivot2 = r.vec3()?;
            let rotation2 = r.quat()?;
            let mirror = r.u8()? != 0;
            let mut faces = [Face {
                u: 0.0,
                v: 0.0,
                uw: 0.0,
                vh: 0.0,
                rot: 0,
                mirror: false,
            }; 6];
            for f in faces.iter_mut() {
                *f = read_face(&mut r)?;
            }
            cubes.push(Cube {
                origin,
                size,
                inflate,
                pivot: pivot2,
                rotation: rotation2,
                mirror,
                faces,
            });
        }
        bones.push(Bone {
            name,
            parent,
            pivot,
            position,
            rotation,
            scale,
            visible,
            cubes,
        });
    }
    let mesh_count = r.count(MESH_MIN_BYTES)?;
    for _ in 0..mesh_count {
        r.u32()?; // bone
        let vc = r.count(VERTEX_BYTES)?;
        for _ in 0..vc {
            r.vec3()?; // position
            r.f32()?; // u
            r.f32()?; // v
            r.vec3()?; // normal
        }
        let ic = r.count(4)?;
        for _ in 0..ic {
            r.u32()?; // index
        }
    }
    if r.u32()? != ENDK {
        return None;
    }

    // sample_pose composes bone i against its parent's already built matrix, so a
    // forward or out of range parent has to die here and not mid frame
    for (i, b) in bones.iter().enumerate() {
        if b.parent == -1 {
            continue;
        }
        if b.parent < 0 || b.parent as usize >= i {
            return None;
        }
    }

    Some(NativeModel {
        bones,
        anims: Vec::new(),
    })
}

fn read_channel(r: &mut Reader) -> Option<Channel> {
    let n = r.count(KEY_MIN_BYTES)?;
    let mut c = Channel::default();
    c.times.reserve(n);
    c.values.reserve(n * 3);
    c.easing.reserve(n);
    c.ctrl_a.reserve(n * 3);
    c.ctrl_b.reserve(n * 3);
    for _ in 0..n {
        c.times.push(r.f32()?);
        c.easing.push(r.u8()?);
        let v = r.vec3()?;
        c.values.extend_from_slice(&v);
        if c.easing.last() == Some(&2) {
            let a = r.vec3()?;
            let b = r.vec3()?;
            c.ctrl_a.extend_from_slice(&a);
            c.ctrl_b.extend_from_slice(&b);
        } else {
            c.ctrl_a.extend_from_slice(&[0.0; 3]);
            c.ctrl_b.extend_from_slice(&[0.0; 3]);
        }
    }
    Some(c)
}

fn read_anims(data: &[u8], bones: &HashMap<String, usize>, bone_count: usize) -> Option<Vec<Anim>> {
    let mut r = Reader::new(data);
    header(&mut r)?;
    let mut anims = Vec::new();
    let count = r.count(ANIM_MIN_BYTES)?;
    for _ in 0..count {
        let name = r.string()?;
        let length = r.f32()?;
        let loop_mode = r.u8()? != 0;
        r.u8(); // flags
        let track_count = r.count(TRACK_MIN_BYTES)?;
        let mut tracks = Vec::with_capacity(track_count);
        for _ in 0..track_count {
            let bone_name = r.string()?;
            // a track for a bone this model does not have still has to be READ, only
            // then dropped. skipping straight to the next track left the reader
            // parked mid channel and shredded every track after it
            let bone = bones.get(&bone_name).copied();
            let channels = r.u8()?;
            let rotation = if channels & CHANNEL_ROTATION != 0 {
                Some(read_channel(&mut r)?)
            } else {
                None
            };
            let position = if channels & CHANNEL_POSITION != 0 {
                Some(read_channel(&mut r)?)
            } else {
                None
            };
            let scale = if channels & CHANNEL_SCALE != 0 {
                Some(read_channel(&mut r)?)
            } else {
                None
            };
            if let Some(bone) = bone {
                tracks.push(ResolvedTrack {
                    bone,
                    rotation,
                    position,
                    scale,
                });
            }
        }
        let mut rot_of = vec![-1i32; bone_count];
        let mut pos_of = vec![-1i32; bone_count];
        let mut scale_of = vec![-1i32; bone_count];
        for (i, tr) in tracks.iter().enumerate() {
            if tr.bone >= bone_count {
                continue;
            }
            if tr.rotation.is_some() {
                rot_of[tr.bone] = i as i32;
            }
            if tr.position.is_some() {
                pos_of[tr.bone] = i as i32;
            }
            if tr.scale.is_some() {
                scale_of[tr.bone] = i as i32;
            }
        }
        anims.push(Anim {
            name,
            length,
            loop_mode,
            tracks,
            rot_of,
            pos_of,
            scale_of,
        });
    }
    if r.u32()? != ENDK {
        return None;
    }
    Some(anims)
}

// ──────────────────────────────────────────────────────────────────────────
// Store
// ──────────────────────────────────────────────────────────────────────────

fn store() -> &'static Mutex<HashMap<i64, NativeModel>> {
    static STORE: OnceLock<Mutex<HashMap<i64, NativeModel>>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

// ──────────────────────────────────────────────────────────────────────────
// Matrix / quaternion math (column-major, same as Java fallback)
// ──────────────────────────────────────────────────────────────────────────

fn quat_from_euler(x: f32, y: f32, z: f32) -> [f32; 4] {
    let (cx, sx) = (x * 0.5).cos_sin();
    let (cy, sy) = (y * 0.5).cos_sin();
    let (cz, sz) = (z * 0.5).cos_sin();
    [
        sx * cy * cz - cx * sy * sz,
        cx * sy * cz + sx * cy * sz,
        cx * cy * sz - sx * sy * cz,
        cx * cy * cz + sx * sy * sz,
    ]
}

trait CosSin {
    fn cos_sin(self) -> (f32, f32);
}

impl CosSin for f32 {
    fn cos_sin(self) -> (f32, f32) {
        (self.cos(), self.sin())
    }
}

fn quat_normalize(q: [f32; 4]) -> [f32; 4] {
    let n = (q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).sqrt();
    if n == 0.0 {
        return [0.0, 0.0, 0.0, 1.0];
    }
    [q[0] / n, q[1] / n, q[2] / n, q[3] / n]
}

fn quat_to_mat4(q: [f32; 4]) -> Mat4 {
    let q = quat_normalize(q);
    let (x, y, z, w) = (q[0], q[1], q[2], q[3]);
    Mat4([
        1.0 - 2.0 * (y * y + z * z),
        2.0 * (x * y + w * z),
        2.0 * (x * z - w * y),
        0.0,
        2.0 * (x * y - w * z),
        1.0 - 2.0 * (x * x + z * z),
        2.0 * (y * z + w * x),
        0.0,
        2.0 * (x * z + w * y),
        2.0 * (y * z - w * x),
        1.0 - 2.0 * (x * x + y * y),
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
    ])
}

#[derive(Clone, Copy)]
struct Mat4([f32; 16]);

impl Mat4 {
    fn translation(t: [f32; 3]) -> Mat4 {
        let mut m = Mat4::identity();
        m.0[12] = t[0];
        m.0[13] = t[1];
        m.0[14] = t[2];
        m
    }

    fn scale(s: [f32; 3]) -> Mat4 {
        Mat4([
            s[0], 0.0, 0.0, 0.0,
            0.0, s[1], 0.0, 0.0,
            0.0, 0.0, s[2], 0.0,
            0.0, 0.0, 0.0, 1.0,
        ])
    }

    fn identity() -> Mat4 {
        Mat4([
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        ])
    }

    fn mul(self, b: Mat4) -> Mat4 {
        let a = self.0;
        let b = b.0;
        let mut out = [0.0f32; 16];
        for col in 0..4 {
            for row in 0..4 {
                let mut acc = 0.0;
                for k in 0..4 {
                    acc += a[k * 4 + row] * b[col * 4 + k];
                }
                out[col * 4 + row] = acc;
            }
        }
        Mat4(out)
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Channel interpolation (mirrors KodelSampler)
// ──────────────────────────────────────────────────────────────────────────

fn sample_channel(ch: &Channel, t: f32, out: &mut [f32; 3]) {
    if ch.times.is_empty() {
        *out = [0.0; 3];
        return;
    }
    if ch.times.len() == 1 || t <= ch.times[0] {
        copy_value(ch, 0, out);
        return;
    }
    let last = ch.times.len() - 1;
    if t >= ch.times[last] {
        copy_value(ch, last, out);
        return;
    }
    let k = find_segment(ch, t);
    let k1 = k + 1;
    let span = ch.times[k1] - ch.times[k];
    let u = if span <= 0.0 { 0.0 } else { (t - ch.times[k]) / span };
    let easing = ch.easing[k];
    match easing {
        0 => {
            let w = 1.0 - u;
            for i in 0..3 {
                let j = k * 3 + i;
                out[i] = ch.values[j] * w + ch.values[j + 3] * u;
            }
        }
        1 => copy_value(ch, k, out),
        2 => {
            let i = k * 3;
            let smooth = ch.ctrl_a[i] == 0.0
                && ch.ctrl_a[i + 1] == 0.0
                && ch.ctrl_a[i + 2] == 0.0
                && ch.ctrl_b[i] == 0.0
                && ch.ctrl_b[i + 1] == 0.0
                && ch.ctrl_b[i + 2] == 0.0;
            if smooth {
                catmull(ch, k, u, out);
            } else {
                bezier(ch, k, u, out);
            }
        }
        _ => copy_value(ch, k, out),
    }
}

fn copy_value(ch: &Channel, key: usize, out: &mut [f32; 3]) {
    let i = key * 3;
    out[0] = ch.values[i];
    out[1] = ch.values[i + 1];
    out[2] = ch.values[i + 2];
}

fn find_segment(ch: &Channel, t: f32) -> usize {
    let (mut lo, mut hi) = (0usize, ch.times.len() - 1);
    while lo < hi {
        let mid = (lo + hi) / 2;
        if ch.times[mid] <= t {
            if mid + 1 >= ch.times.len() || ch.times[mid + 1] > t {
                return mid;
            }
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    lo
}

fn catmull(ch: &Channel, k: usize, u: f32, out: &mut [f32; 3]) {
    let n = ch.times.len();
    let p0 = if k > 0 { k - 1 } else { 0 };
    let p2 = k + 1;
    let p3 = if k + 2 < n { k + 2 } else { n - 1 };
    let u2 = u * u;
    let u3 = u2 * u;
    for i in 0..3 {
        let v0 = ch.values[p0 * 3 + i];
        let v1 = ch.values[k * 3 + i];
        let v2 = ch.values[p2 * 3 + i];
        let v3 = ch.values[p3 * 3 + i];
        out[i] = 0.5
            * (2.0 * v1
                + (-v0 + v2) * u
                + (2.0 * v0 - 5.0 * v1 + 4.0 * v2 - v3) * u2
                + (-v0 + 3.0 * v1 - 3.0 * v2 + v3) * u3);
    }
}

fn bezier(ch: &Channel, k: usize, u: f32, out: &mut [f32; 3]) {
    let k1 = k + 1;
    let u2 = u * u;
    let u3 = u2 * u;
    let w0 = 1.0 - 3.0 * u + 3.0 * u2 - u3;
    let w1 = 3.0 * u - 6.0 * u2 + 3.0 * u3;
    let w2 = 3.0 * u2 - 3.0 * u3;
    let w3 = u3;
    for i in 0..3 {
        let p0 = ch.values[k * 3 + i];
        let p3 = ch.values[k1 * 3 + i];
        let c0 = p0 + ch.ctrl_b[k * 3 + i];
        let c1 = p3 + ch.ctrl_a[k1 * 3 + i];
        out[i] = w0 * p0 + w1 * c0 + w2 * c1 + w3 * p3;
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Sampling
// ──────────────────────────────────────────────────────────────────────────

fn sample_pose(model: &NativeModel, anim: &Anim, t: f32, out: &mut [f32]) {
    let n = model.bones.len();
    let mut world: Vec<Mat4> = Vec::with_capacity(n);
    let mut scratch = [0.0f32; 3];

    for i in 0..n {
        let b = &model.bones[i];

        let pick = |lane: &Vec<i32>| {
            lane.get(i)
                .copied()
                .filter(|&t| t >= 0)
                .and_then(|t| anim.tracks.get(t as usize))
        };
        let track_rot = pick(&anim.rot_of).and_then(|t| t.rotation.as_ref());
        let track_pos = pick(&anim.pos_of).and_then(|t| t.position.as_ref());
        let track_scale = pick(&anim.scale_of).and_then(|t| t.scale.as_ref());

        let (qx, qy, qz, qw) = if let Some(ch) = track_rot {
            sample_channel(ch, t, &mut scratch);
            let q = quat_from_euler(scratch[0], scratch[1], scratch[2]);
            (q[0], q[1], q[2], q[3])
        } else {
            (b.rotation[0], b.rotation[1], b.rotation[2], b.rotation[3])
        };
        let (px, py, pz) = if let Some(ch) = track_pos {
            sample_channel(ch, t, &mut scratch);
            (scratch[0], scratch[1], scratch[2])
        } else {
            (b.position[0], b.position[1], b.position[2])
        };
        let (sx, sy, sz) = if let Some(ch) = track_scale {
            sample_channel(ch, t, &mut scratch);
            (scratch[0], scratch[1], scratch[2])
        } else {
            (b.scale[0], b.scale[1], b.scale[2])
        };

        // translate(position) * translate(pivot) * rotate * scale * translate(-pivot)
        let local = Mat4::translation([px, py, pz])
            .mul(Mat4::translation(b.pivot))
            .mul(quat_to_mat4([qx, qy, qz, qw]))
            .mul(Mat4::scale([sx, sy, sz]))
            .mul(Mat4::translation([-b.pivot[0], -b.pivot[1], -b.pivot[2]]));

        let m = if b.parent >= 0 && (b.parent as usize) < world.len() {
            world[b.parent as usize].mul(local)
        } else {
            local
        };
        world.push(m);
    }

    let needed = n * 16;
    let take = out.len().min(needed);
    for (i, m) in world.iter().enumerate() {
        let at = i * 16;
        for j in 0..take.saturating_sub(at).min(16) {
            out[at + j] = m.0[j];
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Exported FFI surface
// ──────────────────────────────────────────────────────────────────────────

/// Parses a model.bin into the store. Returns a positive handle or 0.
#[no_mangle]
pub extern "C" fn kodel_load_model(ptr: *const u8, len: u32) -> i64 {
    if ptr.is_null() {
        return 0;
    }
    let data = unsafe {
        if len == 0 {
            return 0;
        }
        std::slice::from_raw_parts(ptr, len as usize)
    };
    let Some(model) = read_model(data) else {
        return 0;
    };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        NEXT_HANDLE.store(1, Ordering::Relaxed);
        return 0;
    }
    store().lock().unwrap().insert(handle, model);
    handle
}

/// Binds a raw animation.anim.bin to a loaded model. 0 = ok, -1 = failed.
#[no_mangle]
pub extern "C" fn kodel_load_animations(handle: i64, ptr: *const u8, len: u32) -> i32 {
    if handle <= 0 || ptr.is_null() {
        return -1;
    }
    let data = unsafe {
        if len == 0 {
            return -1;
        }
        std::slice::from_raw_parts(ptr, len as usize)
    };
    let mut store = store().lock().unwrap();
    let Some(model) = store.get_mut(&handle) else {
        return -1;
    };
    let name_map: HashMap<String, usize> = model
        .bones
        .iter()
        .enumerate()
        .map(|(i, b)| (b.name.clone(), i))
        .collect();
    match read_anims(data, &name_map, model.bones.len()) {
        Some(anims) => {
            model.anims = anims;
            0
        }
        None => -1,
    }
}

/// Number of bones in a loaded model.
#[no_mangle]
pub extern "C" fn kodel_bone_count(handle: i64) -> i32 {
    let store = store().lock().unwrap();
    store
        .get(&handle)
        .map(|m| m.bones.len() as i32)
        .unwrap_or(0)
}

/// Samples clip anim_idx at t into `out` (16 floats per bone, column-major).
#[no_mangle]
pub extern "C" fn kodel_sample(
    handle: i64,
    anim_idx: i32,
    t: f32,
    out: *mut f32,
    out_len: u32,
) -> i32 {
    if handle <= 0 || out.is_null() {
        return -1;
    }
    let store = store().lock().unwrap();
    let Some(model) = store.get(&handle) else {
        return -1;
    };
    if anim_idx < 0 || anim_idx as usize >= model.anims.len() {
        return -2;
    }
    let anim = &model.anims[anim_idx as usize];
    let len = out_len as usize;
    let slice = unsafe { std::slice::from_raw_parts_mut(out, len) };
    sample_pose(model, anim, t, slice);
    0
}

/// Frees a loaded model handle.
#[no_mangle]
pub extern "C" fn kodel_free(handle: i64) {
    if handle > 0 {
        store().lock().unwrap().remove(&handle);
    }
}

/// Drops every loaded model.
#[no_mangle]
pub extern "C" fn kodel_clear() {
    store().lock().unwrap().clear();
}
// ──────────────────────────────────────────────────────────────────────────
// Bedrock client entities (aktor.rs). one call per mob per frame
// ──────────────────────────────────────────────────────────────────────────

mod aktor;

fn put_bytes(bytes: &[u8], out: *mut u8, cap: u32) -> i32 {
    if !out.is_null() && bytes.len() <= cap as usize {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    }
    bytes.len() as i32
}

/// json from java (bones, animations, controllers, scripts, render controllers) -> def handle, 0 on junk
#[no_mangle]
pub extern "C" fn kodel_br_define(ptr: *const u8, len: u32) -> i64 {
    if ptr.is_null() {
        return 0;
    }
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    let Ok(src) = serde_json::from_slice::<serde_json::Value>(bytes) else { return 0 };
    let def = std::sync::Arc::new(aktor::Def::build(&src));
    Box::into_raw(Box::new(def)) as i64
}

fn def_ref<'a>(h: i64) -> Option<&'a std::sync::Arc<aktor::Def>> {
    if h == 0 { None } else { Some(unsafe { &*(h as *const std::sync::Arc<aktor::Def>) }) }
}

/// what the def needs from java and what it may fire: {"queries":[..],"events":[..],"errors":[..],"bones":n}
#[no_mangle]
pub extern "C" fn kodel_br_describe(h: i64, out: *mut u8, cap: u32) -> i32 {
    let Some(def) = def_ref(h) else { return -1 };
    let j = serde_json::json!({
        "queries": def.query_names(),
        "strings": def.strings(),
        "contexts": def.contexts(),
        "vars": def.var_names(),
        "events": def.events,
        "errors": def.errors,
        "bones": def.bone_count(),
        "anims": def.anim_names(),
    });
    put_bytes(j.to_string().as_bytes(), out, cap)
}

/// entity.playAnimation / playanimation on one live actor. strings are utf8 (len 0 = not given):
/// the animation (full id or the entity's short name), the stop expression, the controller name.
/// 1 = playing, 0 = this def has no such animation
#[no_mangle]
pub extern "C" fn kodel_br_play(inst: i64, anim: *const u8, anim_len: u32, blend_out: f32,
                                stop: *const u8, stop_len: u32, ctrl: *const u8, ctrl_len: u32) -> i32 {
    if inst == 0 || anim.is_null() {
        return -1;
    }
    let s = |p: *const u8, n: u32| -> Option<String> {
        if p.is_null() || n == 0 { None } else { std::str::from_utf8(unsafe { std::slice::from_raw_parts(p, n as usize) }).ok().map(String::from) }
    };
    let i = unsafe { &mut *(inst as *mut aktor::Inst) };
    let Some(a) = s(anim, anim_len) else { return -1 };
    i.play_animation(&a, blend_out, s(stop, stop_len).as_deref(), s(ctrl, ctrl_len).as_deref()) as i32
}

#[no_mangle]
pub extern "C" fn kodel_br_undefine(h: i64) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut std::sync::Arc<aktor::Def>) });
    }
}

#[no_mangle]
pub extern "C" fn kodel_br_spawn(def: i64, seed: u32) -> i64 {
    let Some(d) = def_ref(def) else { return 0 };
    Box::into_raw(Box::new(aktor::Inst::new(d.clone(), seed))) as i64
}

#[no_mangle]
pub extern "C" fn kodel_br_free(inst: i64) {
    if inst != 0 {
        drop(unsafe { Box::from_raw(inst as *mut aktor::Inst) });
    }
}

/// queries in, pose out. mats = 16 floats per bone, vis = 1 byte per bone, local = 9 floats per
/// bone or null, info = 8 ints (texture, geometry, scale x/y/z bits, event count, overlay, hurt),
/// events = fired effect ids. returns the bone count
#[no_mangle]
pub extern "C" fn kodel_br_tick(inst: i64, queries: *const f32, qstr: *const i32, nq: u32,
                                context: *const f32, cstr: *const i32, nctx: u32, dt: f32,
                                mats: *mut f32, mats_len: u32, vis: *mut u8, vis_len: u32,
                                local: *mut f32, local_len: u32, info: *mut i32, events: *mut i32, events_len: u32) -> i32 {
    if inst == 0 || mats.is_null() || vis.is_null() || info.is_null() {
        return -1;
    }
    let i = unsafe { &mut *(inst as *mut aktor::Inst) };
    let q: &[f32] = if queries.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(queries, nq as usize) } };
    let out = aktor::Out {
        mats: unsafe { std::slice::from_raw_parts_mut(mats, mats_len as usize) },
        vis: unsafe { std::slice::from_raw_parts_mut(vis, vis_len as usize) },
        local: if local.is_null() { None } else { Some(unsafe { std::slice::from_raw_parts_mut(local, local_len as usize) }) },
        info: unsafe { std::slice::from_raw_parts_mut(info, 8) },
        events: if events.is_null() { &mut [] } else { unsafe { std::slice::from_raw_parts_mut(events, events_len as usize) } },
    };
    let qs: &[i32] = if qstr.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(qstr, nq as usize) } };
    let cs: &[f32] = if context.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(context, nctx as usize) } };
    let css: &[i32] = if cstr.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(cstr, nctx as usize) } };
    i.tick_c(q, qs, cs, css, dt, out) as i32
}

/// variables bedrock's engine sets on an actor before its scripts (v.attack_time, v.is_holding_right..):
/// ids are indices into describe's "vars". they stay until the scripts overwrite them
#[no_mangle]
pub extern "C" fn kodel_br_vars(inst: i64, ids: *const i32, vals: *const f32, n: u32) {
    if inst == 0 || ids.is_null() || vals.is_null() {
        return;
    }
    let i = unsafe { &mut *(inst as *mut aktor::Inst) };
    let ids = unsafe { std::slice::from_raw_parts(ids, n as usize) };
    let vals = unsafe { std::slice::from_raw_parts(vals, n as usize) };
    for (id, v) in ids.iter().zip(vals) {
        i.set_var(*id as usize, *v);
    }
}

/// runs an attachable's parent_setup on its holder, after the attachable's own tick
#[no_mangle]
pub extern "C" fn kodel_br_parent_setup(inst: i64, owner: i64) {
    if inst == 0 || owner == 0 || inst == owner {
        return;
    }
    let i = unsafe { &*(inst as *const aktor::Inst) };
    let o = unsafe { &mut *(owner as *mut aktor::Inst) };
    i.parent_setup(o);
}

/// debugging: variable i of an instance as a number (strings give their book id), NaN when unset
#[no_mangle]
pub extern "C" fn kodel_br_var_get(inst: i64, i: i32) -> f32 {
    if inst == 0 || i < 0 {
        return f32::NAN;
    }
    let x = unsafe { &*(inst as *const aktor::Inst) };
    x.var_value(i as usize).unwrap_or(f32::NAN)
}

/// the holder of an attachable, for c.owning_entity->v.x in the very next tick only
#[no_mangle]
pub extern "C" fn kodel_br_owner(inst: i64, owner: i64) {
    if inst == 0 {
        return;
    }
    let i = unsafe { &mut *(inst as *mut aktor::Inst) };
    i.owner = owner as *const aktor::Inst;
}

/// what render controller rc's "materials" entries evaluated to last tick, as book string ids in
/// list order (-1 = no string). returns how many entries it has
#[no_mangle]
pub extern "C" fn kodel_br_rc_materials(inst: i64, rc: i32, out: *mut i32, max: u32) -> i32 {
    if inst == 0 || rc < 0 {
        return 0;
    }
    let i = unsafe { &*(inst as *const aktor::Inst) };
    let Some(m) = i.rc_mats.get(rc as usize) else { return 0 };
    if !out.is_null() {
        let o = unsafe { std::slice::from_raw_parts_mut(out, max as usize) };
        for (k, v) in m.iter().take(max as usize).enumerate() {
            o[k] = *v;
        }
    }
    m.len() as i32
}

/// every texture render controller rc picked last tick, def texture indices in list order. returns how many
#[no_mangle]
pub extern "C" fn kodel_br_rc_textures(inst: i64, rc: i32, out: *mut i32, max: u32) -> i32 {
    if inst == 0 || rc < 0 {
        return 0;
    }
    let i = unsafe { &*(inst as *const aktor::Inst) };
    let Some(t) = i.rc_tex.get(rc as usize) else { return 0 };
    if !out.is_null() {
        let o = unsafe { std::slice::from_raw_parts_mut(out, max as usize) };
        for (k, v) in t.iter().take(max as usize).enumerate() {
            o[k] = *v;
        }
    }
    t.len() as i32
}

/// render controllers that drew in the last tick, 3 ints each: controller index, texture, geometry.
/// returns how many (can be more than fit)
#[no_mangle]
pub extern "C" fn kodel_br_layers(inst: i64, out: *mut i32, max: u32) -> i32 {
    if inst == 0 {
        return 0;
    }
    let i = unsafe { &*(inst as *const aktor::Inst) };
    if !out.is_null() {
        let o = unsafe { std::slice::from_raw_parts_mut(out, max as usize * 3) };
        for (k, l) in i.layers.iter().take(max as usize).enumerate() {
            o[k * 3..k * 3 + 3].copy_from_slice(l);
        }
    }
    i.layers.len() as i32
}

/// one render controller's own part_visibility after the last tick, a byte per bone (1 shown). returns the bone count
#[no_mangle]
pub extern "C" fn kodel_br_layer_vis(inst: i64, rc: i32, out: *mut u8, max: u32) -> i32 {
    if inst == 0 || rc < 0 {
        return 0;
    }
    let i = unsafe { &*(inst as *const aktor::Inst) };
    let Some(v) = i.rc_vis.get(rc as usize) else { return 0 };
    if !out.is_null() {
        let o = unsafe { std::slice::from_raw_parts_mut(out, max as usize) };
        for (k, b) in v.iter().take(max as usize).enumerate() {
            o[k] = *b;
        }
    }
    v.len() as i32
}

// ──────────────────────────────────────────────────────────────────────────
// Bedrock particle effects (czastki.rs)
// ──────────────────────────────────────────────────────────────────────────

mod czastki;

/// particle_effect json -> def handle. {"texture","material","lit","errors"} via kodel_px_describe
#[no_mangle]
pub extern "C" fn kodel_px_define(ptr: *const u8, len: u32) -> i64 {
    if ptr.is_null() { return 0; }
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    let Ok(src) = serde_json::from_slice::<serde_json::Value>(bytes) else { return 0 };
    Box::into_raw(Box::new(std::sync::Arc::new(czastki::Def::build(&src)))) as i64
}

#[no_mangle]
pub extern "C" fn kodel_px_describe(h: i64, out: *mut u8, cap: u32) -> i32 {
    if h == 0 { return -1; }
    let d = unsafe { &*(h as *const std::sync::Arc<czastki::Def>) };
    let j = serde_json::json!({"texture": d.texture, "material": d.material, "lit": d.lit(), "errors": d.errors});
    put_bytes(j.to_string().as_bytes(), out, cap)
}

#[no_mangle]
pub extern "C" fn kodel_px_undefine(h: i64) {
    if h != 0 { drop(unsafe { Box::from_raw(h as *mut std::sync::Arc<czastki::Def>) }); }
}

#[no_mangle]
pub extern "C" fn kodel_px_spawn(def: i64, x: f32, y: f32, z: f32, seed: u32) -> i64 {
    if def == 0 { return 0; }
    let d = unsafe { &*(def as *const std::sync::Arc<czastki::Def>) };
    Box::into_raw(Box::new(czastki::Emitter::new(d.clone(), [x, y, z], seed))) as i64
}

/// moves an emitter that rides an entity; aabb is min xyz max xyz in world blocks
#[no_mangle]
pub extern "C" fn kodel_px_move(em: i64, x: f32, y: f32, z: f32, aabb: *const f32) {
    if em == 0 { return; }
    let e = unsafe { &mut *(em as *mut czastki::Emitter) };
    e.origin = [x, y, z];
    if !aabb.is_null() {
        let a = unsafe { std::slice::from_raw_parts(aabb, 6) };
        e.aabb.copy_from_slice(a);
    }
}

#[no_mangle]
pub extern "C" fn kodel_px_expire(em: i64) {
    if em != 0 { unsafe { &mut *(em as *mut czastki::Emitter) }.expired = true; }
}

#[no_mangle]
pub extern "C" fn kodel_px_free(em: i64) {
    if em != 0 { drop(unsafe { Box::from_raw(em as *mut czastki::Emitter) }); }
}

thread_local! {
    static PX_OUT: std::cell::RefCell<Vec<f32>> = std::cell::RefCell::new(Vec::new());
    static PX_REQ: std::cell::RefCell<Vec<u8>> = std::cell::RefCell::new(Vec::new());
}

/// steps the emitter and writes quads (19 floats each) into out. returns the quad count,
/// -1 when the emitter is done for good. a quad count bigger than cap/19 = call again with more room
#[no_mangle]
pub extern "C" fn kodel_px_tick(em: i64, dt: f32, cx: f32, cy: f32, cz: f32, out: *mut f32, cap: u32) -> i32 {
    if em == 0 { return -1; }
    let e = unsafe { &mut *(em as *mut czastki::Emitter) };
    PX_OUT.with(|buf| {
        let mut b = buf.borrow_mut();
        b.clear();
        e.tick(dt, [cx, cy, cz], &mut b);
        let n = b.len() / czastki::QUAD_FLOATS;
        let fits = (cap as usize).min(b.len());
        if !out.is_null() { unsafe { std::ptr::copy_nonoverlapping(b.as_ptr(), out, fits) }; }
        // requests go out as json lines for the java side to pick up with kodel_px_requests
        PX_REQ.with(|r| {
            let mut r = r.borrow_mut();
            r.clear();
            for (kind, name, at) in &e.requests {
                r.extend_from_slice(format!("{}|{}|{}|{}|{}\n", kind, name, at[0], at[1], at[2]).as_bytes());
            }
        });
        if !e.alive() { -1 } else { n as i32 }
    })
}

#[no_mangle]
pub extern "C" fn kodel_px_requests(out: *mut u8, cap: u32) -> i32 {
    PX_REQ.with(|r| put_bytes(&r.borrow(), out, cap))
}
