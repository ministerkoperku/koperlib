// kender geo — the GPU-driven instancing core.
// static: ONE mesh per model + per-instance transform/light, frustum-culled ON THE GPU (compute -> indirect draw).
// skinned: same, plus a bone-matrix SSBO — vertex shader applies the bone, so N animated blocks cost the CPU
// only K bone-tree walks (K = unique anim states), not N mesh bakes. this is the "beat Flywheel" path.

#[cfg(feature = "vk")]
use ash::{vk, Device};
#[cfg(feature = "vk")]
use glam::{Mat4, Quat, Vec3};
#[cfg(feature = "vk")]
use std::{collections::HashMap, sync::Mutex};
#[cfg(feature = "vk")]
use crate::vk::buffer::GpuBuf;
#[cfg(feature = "vk")]
use crate::vk::pipeline::{attr, shader_mod, compile_wgsl, bytemuck_cast};
#[cfg(feature = "vk")]
use super::KenderVkCtx;

// WGSL. instance matrix comes in as 4 vec4 vertex attrs; vp+sky are push constants (80 B, vert+frag).
#[cfg(feature = "vk")]
const GEO_VERT: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, pad2: f32 };
var<push_constant> pc: Push;

struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) normal: vec3<f32>,
    @location(3) m0: vec4<f32>,
    @location(4) m1: vec4<f32>,
    @location(5) m2: vec4<f32>,
    @location(6) m3: vec4<f32>,
    @location(7) light: vec2<f32>,
    @location(8) tintf: f32,
};
struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) light: vec2<f32>,
    @location(2) tint: vec3<f32>,
    @location(3) normal: vec3<f32>,
};
// tint rides as a VALUE r + g*256 + b*65536 (fits float's 24-bit mantissa exactly) —
// raw RGBA8 bit patterns in a float land in NaN space and get eaten, don't try it
fn tint3(v: f32) -> vec3<f32> {
    // NO +0.5 here: white = 16777215 is the LAST exact int in f32 — nudging it rounds to 2^24,
    // whose low bits are all zero -> every untinted block rendered pure black. been there.
    let i = u32(v);
    return vec3<f32>(f32(i & 255u), f32((i >> 8u) & 255u), f32((i >> 16u) & 255u)) / 255.0;
}
@vertex
fn main(v: VsIn) -> VsOut {
    let m = mat4x4<f32>(v.m0, v.m1, v.m2, v.m3);
    var o: VsOut;
    o.clip = pc.vp * m * vec4<f32>(v.pos, 1.0);
    o.clip.y = -o.clip.y;   // Vulkan clip-space Y points down; MC's proj expects the GL flip we don't get here
    o.uv = v.uv;
    o.light = v.light;
    o.tint = tint3(v.tintf);
    o.normal = normalize((m * vec4<f32>(v.normal, 0.0)).xyz);
    return o;
}
"#;

// skinned variant: per-vertex bone id (float-encoded) + per-instance bone_base index into the bone SSBO.
// verts are CUBE-LOCAL (cube rot pre-baked); the bone matrix is the full animated bone-chain transform.
#[cfg(feature = "vk")]
const SKIN_VERT: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, pad2: f32 };
var<push_constant> pc: Push;
@group(0) @binding(2) var<storage, read> bones: array<f32>;

struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) normal: vec3<f32>,
    @location(3) bone: f32,
    @location(4) m0: vec4<f32>,
    @location(5) m1: vec4<f32>,
    @location(6) m2: vec4<f32>,
    @location(7) m3: vec4<f32>,
    @location(8) light: vec2<f32>,
    @location(9) bone_base: f32,
    @location(10) tintf: f32,
    @location(11) uv_offset: vec2<f32>,
};
struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) light: vec2<f32>,
    @location(2) tint: vec3<f32>,
    @location(3) normal: vec3<f32>,
};
fn bmat(idx: u32) -> mat4x4<f32> {
    let o = idx * 16u;
    return mat4x4<f32>(
        vec4<f32>(bones[o],      bones[o+1u],  bones[o+2u],  bones[o+3u]),
        vec4<f32>(bones[o+4u],  bones[o+5u],  bones[o+6u],  bones[o+7u]),
        vec4<f32>(bones[o+8u],  bones[o+9u],  bones[o+10u], bones[o+11u]),
        vec4<f32>(bones[o+12u], bones[o+13u], bones[o+14u], bones[o+15u]));
}
fn tint3(v: f32) -> vec3<f32> {
    // NO +0.5 here: white = 16777215 is the LAST exact int in f32 — nudging it rounds to 2^24,
    // whose low bits are all zero -> every untinted block rendered pure black. been there.
    let i = u32(v);
    return vec3<f32>(f32(i & 255u), f32((i >> 8u) & 255u), f32((i >> 16u) & 255u)) / 255.0;
}
@vertex
fn main(v: VsIn) -> VsOut {
    let m = mat4x4<f32>(v.m0, v.m1, v.m2, v.m3);
    let b = bmat(u32(v.bone_base + 0.5) + u32(v.bone + 0.5));
    var o: VsOut;
    o.clip = pc.vp * m * b * vec4<f32>(v.pos, 1.0);
    o.clip.y = -o.clip.y;
    o.uv = v.uv + v.uv_offset;
    o.light = v.light;
    o.tint = tint3(v.tintf);
    o.normal = normalize((m * b * vec4<f32>(v.normal, 0.0)).xyz);
    return o;
}
"#;

// kinetic spin done ON THE GPU. instance data is static — pos/axis/speed/offset/pivot go up once and
// only pc.time moves, so a spinning cog costs the CPU nothing per frame. this is what Create's own
// rotating_pivot.vert does; we were baking the angle into a mat4 on the CPU every frame instead.
//
// CPU chain we're replacing:  T(c)·T(piv)·R(spin)·T(-piv)·R(rot)·T(-0.5)·pos
// folds exactly into:         A · [ R(spin)·( R(rot)·(pos-0.5) - piv ) + piv ]     with A static
#[cfg(feature = "vk")]
const SPIN_VERT: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, time: f32 };
var<push_constant> pc: Push;

struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) normal: vec3<f32>,
    @location(3) m0: vec4<f32>,
    @location(4) m1: vec4<f32>,
    @location(5) m2: vec4<f32>,
    @location(6) m3: vec4<f32>,
    @location(7) light: vec2<f32>,
    @location(8) tintf: f32,
    @location(9) rot: vec4<f32>,          // base rotation quat (xyzw)
    @location(10) axis_speed: vec4<f32>,  // xyz = kinetic axis, w = degrees/sec
    @location(11) pivot_off: vec4<f32>,   // xyz = pivot, w = degrees offset
};
struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) light: vec2<f32>,
    @location(2) tint: vec3<f32>,
    @location(3) normal: vec3<f32>,
};
fn tint3(v: f32) -> vec3<f32> {
    let i = u32(v);
    return vec3<f32>(f32(i & 255u), f32((i >> 8u) & 255u), f32((i >> 16u) & 255u)) / 255.0;
}
fn qrot(q: vec4<f32>, v: vec3<f32>) -> vec3<f32> {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}
@vertex
fn main(v: VsIn) -> VsOut {
    let m = mat4x4<f32>(v.m0, v.m1, v.m2, v.m3);
    let ax = v.axis_speed.xyz;
    let len2 = dot(ax, ax);
    // a zero axis means "not actually kinetic" — identity quat, no normalize by zero
    var kin = vec4<f32>(0.0, 0.0, 0.0, 1.0);
    if (len2 > 1e-8) {
        let h = radians(v.pivot_off.w + pc.time * v.axis_speed.w) * 0.5;
        kin = vec4<f32>(ax * inverseSqrt(len2) * sin(h), cos(h));
    }
    let piv = v.pivot_off.xyz;
    let base = qrot(v.rot, v.pos - vec3<f32>(0.5));
    let spun = qrot(kin, base - piv) + piv;
    var o: VsOut;
    o.clip = pc.vp * m * vec4<f32>(spun, 1.0);
    o.clip.y = -o.clip.y;
    o.uv = v.uv;
    o.light = v.light;
    o.tint = tint3(v.tintf);
    o.normal = normalize((m * vec4<f32>(qrot(kin, qrot(v.rot, v.normal)), 0.0)).xyz);
    return o;
}
"#;

// Flywheel entity shadows. UV comes from the fragment's WORLD xz relative to the entity, not from the
// mesh, so no mat4 can express it — hence its own tiny pipeline. Straight port of flywheel's shadow.vert.
#[cfg(feature = "vk")]
const SHADOW_VERT: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, time: f32 };
var<push_constant> pc: Push;

struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) normal: vec3<f32>,
    @location(3) m0: vec4<f32>,
    @location(4) m1: vec4<f32>,
    @location(5) m2: vec4<f32>,
    @location(6) m3: vec4<f32>,
    @location(7) light: vec2<f32>,
    @location(8) alpha: f32,
    @location(9) entity_r: vec3<f32>,   // xy = entity xz, z = radius
};
struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) light: vec2<f32>,
    @location(2) tint: vec3<f32>,
    @location(3) normal: vec3<f32>,
};
@vertex
fn main(v: VsIn) -> VsOut {
    let m = mat4x4<f32>(v.m0, v.m1, v.m2, v.m3);
    let world = m * vec4<f32>(v.pos, 1.0);
    var o: VsOut;
    o.clip = pc.vp * world;
    o.clip.y = -o.clip.y;
    // offset from the quad's own origin, not absolute world — otherwise a render-origin shift
    // (every 256 blocks walked) would slide every shadow's UV
    let local_xz = world.xz - vec2<f32>(v.m3.x, v.m3.z);
    let r = max(v.entity_r.z, 1e-4);
    o.uv = (local_xz - v.entity_r.xy) * 0.5 / r + vec2<f32>(0.5);
    o.light = v.light;
    o.tint = vec3<f32>(v.alpha, 0.0, 0.0);  // alpha rides the unused tint slot
    o.normal = vec3<f32>(0.0, 1.0, 0.0);
    return o;
}
"#;

#[cfg(feature = "vk")]
const SHADOW_FRAG: &str = r#"
@group(0) @binding(0) var tex: texture_2d<f32>;
@group(0) @binding(1) var samp: sampler;
@fragment
fn main(@location(0) uv: vec2<f32>, @location(1) light: vec2<f32>, @location(2) tint: vec3<f32>,
        @location(3) normal: vec3<f32>) -> @location(0) vec4<f32> {
    // outside the quad the shadow must not wrap around — clamp kills the repeat
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { discard; }
    let c = textureSample(tex, samp, uv);
    return vec4<f32>(0.0, 0.0, 0.0, c.a * tint.x);
}
"#;

// separate texture + sampler (WGSL-native, group 0 binding 0/1) — pushed inline via push-descriptor.
// binding 3/4 = MC's own level lightmap + its linear sampler. has_lm rides in the push const.
#[cfg(feature = "vk")]
const GEO_LIGHT_FNS: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, pad2: f32 };
var<push_constant> pc: Push;
@group(0) @binding(0) var tex: texture_2d<f32>;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(3) var lightmap: texture_2d<f32>;
@group(0) @binding(4) var lm_samp: sampler;

// straight port of MC's sample_lightmap.glsl:
//   texture(lm, clamp(UV2/256 + 0.5/16, 0.5/16, 15.5/16))
// UV2 is level*16, our instance light is level/15 -> level/16 == light*15/16.
// this returns an RGB, not a scalar: block light is warm, sky light is cool, and MC mixes
// them per channel. the old max()+curve collapsed that to gray, which is why night looked wrong.
fn light_rgb(light: vec2<f32>) -> vec3<f32> {
    if (pc.has_lm > 0.5) {
        let uv = clamp(light * (15.0 / 16.0) + vec2<f32>(0.5 / 16.0),
                       vec2<f32>(0.5 / 16.0), vec2<f32>(15.5 / 16.0));
        return textureSampleLevel(lightmap, lm_samp, uv, 0.0).rgb;
    }
    // MC hasn't handed us the view yet — old approximation so nothing renders black
    let lv = max(light.x, max(light.y - pc.sky, 0.0));
    return vec3<f32>(mix(0.05, 1.0, lv / (4.0 - 3.0 * lv)));
}

// vanilla face shade: down .5, up 1, N/S .8, E/W .6. n*n sums to 1 so diagonals blend right —
// abs(n) does NOT (a 45° normal came out ~40% too bright).
fn face_shade(n: vec3<f32>) -> f32 {
    let n2 = n * n * vec3<f32>(0.6, 0.25, 0.8);
    return min(n2.x + n2.y * (3.0 + n.y) + n2.z, 1.0);
}
"#;

#[cfg(feature = "vk")]
const GEO_FRAG_BODY: &str = r#"
@fragment
fn main(@location(0) uv: vec2<f32>, @location(1) light: vec2<f32>, @location(2) tint: vec3<f32>,
        @location(3) normal: vec3<f32>) -> @location(0) vec4<f32> {
    let c = textureSample(tex, samp, uv);
    if (abs(pc.material - 2.0) > 0.1 && c.a < 0.1) { discard; }
    let emissive = pc.material > 2.5 && pc.material < 4.5;
    let shade = select(face_shade(normalize(normal)), 1.0, pc.material > 4.5);
    let lit = select(light_rgb(light) * shade, vec3<f32>(1.0), emissive);
    let alpha = select(1.0, c.a, (pc.material > 0.5 && pc.material < 1.5) || pc.material > 2.5);
    return vec4<f32>(c.rgb * lit * tint, alpha);
}
"#;

// Entity layers (villager clothes, armor, wool) reuse the exact body surface, but MC builds their
// depth on the CPU while Kender builds body depth in the shader. Move only the body depth a fixed
// number of float ULPs farther in reverse-Z so later vanilla layers win deterministically.
#[cfg(feature = "vk")]
const GEO_FRAG_ENTITY_BODY: &str = r#"
struct FsOut {
    @location(0) color: vec4<f32>,
    @builtin(frag_depth) depth: f32,
};
@fragment
fn main(@location(0) uv: vec2<f32>, @location(1) light: vec2<f32>, @location(2) tint: vec3<f32>,
        @location(3) normal: vec3<f32>, @builtin(position) frag: vec4<f32>) -> FsOut {
    let c = textureSample(tex, samp, uv);
    if (abs(pc.material - 2.0) > 0.1 && c.a < 0.1) { discard; }
    let emissive = pc.material > 2.5 && pc.material < 4.5;
    let shade = select(face_shade(normalize(normal)), 1.0, pc.material > 4.5);
    let lit = select(light_rgb(light) * shade, vec3<f32>(1.0), emissive);
    let alpha = select(1.0, c.a, (pc.material > 0.5 && pc.material < 1.5) || pc.material > 2.5);
    var o: FsOut;
    o.color = vec4<f32>(c.rgb * lit * tint, alpha);
    let bits = bitcast<u32>(frag.z);
    o.depth = bitcast<f32>(select(0u, bits - 16u, bits > 16u));
    return o;
}
"#;

// both frags share the light helpers — glue them at build time (concat! only eats literals)
#[cfg(feature = "vk")]
fn geo_frag() -> String { format!("{GEO_LIGHT_FNS}{GEO_FRAG_BODY}") }
#[cfg(feature = "vk")]
fn geo_frag_entity() -> String { format!("{GEO_LIGHT_FNS}{GEO_FRAG_ENTITY_BODY}") }

// Shadow-MAP casting (not the entity blob shadow above — different thing entirely). A shader mod
// like Sulkan fills cascade depth targets from the light's point of view; anything Kender draws with
// its own pipeline is invisible to that, which is why kontraktions, static geo blocks and cogwheels
// let the player's shadow pass straight through them. Same vertex+instance layout as the static
// pipe so it re-draws the buffers already on the GPU — only the matrix and the target differ.
#[cfg(feature = "vk")]
const SMAP_VERT: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, pad2: f32 };
var<push_constant> pc: Push;
struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) normal: vec3<f32>,
    @location(3) m0: vec4<f32>,
    @location(4) m1: vec4<f32>,
    @location(5) m2: vec4<f32>,
    @location(6) m3: vec4<f32>,
    @location(7) light: vec2<f32>,
    @location(8) tintf: f32,
};
struct VsOut { @builtin(position) clip: vec4<f32>, @location(0) uv: vec2<f32> };
@vertex
fn main(v: VsIn) -> VsOut {
    let m = mat4x4<f32>(v.m0, v.m1, v.m2, v.m3);
    var o: VsOut;
    o.clip = pc.vp * m * vec4<f32>(v.pos, 1.0);
    o.clip.y = -o.clip.y;   // same flip the main pass needs; if shadows land mirrored, this is the line
    o.uv = v.uv;
    return o;
}
"#;

// occlusion mask only. their cascade colour clears to white and casters write black; depth does the
// real work. cutout still has to discard or leaves would cast solid blocks of shade.
#[cfg(feature = "vk")]
const SMAP_FRAG: &str = r#"
struct Push { vp: mat4x4<f32>, sky: f32, material: f32, has_lm: f32, pad2: f32 };
var<push_constant> pc: Push;
@group(0) @binding(0) var tex: texture_2d<f32>;
@group(0) @binding(1) var samp: sampler;
@fragment
fn main(@location(0) uv: vec2<f32>) -> @location(0) vec4<f32> {
    let c = textureSample(tex, samp, uv);
    if (abs(pc.material - 2.0) > 0.1 && c.a < 0.1) { discard; }
    return vec4<f32>(0.0, 0.0, 0.0, 1.0);
}
"#;

// GPU frustum cull: sphere-vs-6-planes per instance, survivors compacted into the culled buffer,
// instanceCount bumped atomically in the indirect command. this is what makes 100k blocks cost
// ~nothing when they're off screen — the vertex shader never sees them.
#[cfg(feature = "vk")]
const CULL_COMP: &str = r#"
struct CullPush {
    p0: vec4<f32>, p1: vec4<f32>, p2: vec4<f32>, p3: vec4<f32>, p4: vec4<f32>, p5: vec4<f32>,
    center_r: vec4<f32>,
    count: u32, stride: u32, pb: u32, pcx: u32,
};
var<push_constant> pc: CullPush;
@group(0) @binding(0) var<storage, read> src: array<f32>;
@group(0) @binding(1) var<storage, read_write> dst: array<f32>;
struct Indirect { index_count: u32, instance_count: atomic<u32>, first_index: u32, vertex_offset: i32, first_instance: u32 };
@group(0) @binding(2) var<storage, read_write> ind: Indirect;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= pc.count) { return; }
    let o = i * pc.stride;
    let c0 = vec3<f32>(src[o],      src[o+1u],  src[o+2u]);
    let c1 = vec3<f32>(src[o+4u],  src[o+5u],  src[o+6u]);
    let c2 = vec3<f32>(src[o+8u],  src[o+9u],  src[o+10u]);
    let c3 = vec3<f32>(src[o+12u], src[o+13u], src[o+14u]);
    let ctr = pc.center_r.xyz;
    let wc = c0 * ctr.x + c1 * ctr.y + c2 * ctr.z + c3;
    let sc = max(length(c0), max(length(c1), length(c2)));
    let r = pc.center_r.w * sc;
    if (dot(pc.p0.xyz, wc) + pc.p0.w < -r) { return; }
    if (dot(pc.p1.xyz, wc) + pc.p1.w < -r) { return; }
    if (dot(pc.p2.xyz, wc) + pc.p2.w < -r) { return; }
    if (dot(pc.p3.xyz, wc) + pc.p3.w < -r) { return; }
    if (dot(pc.p4.xyz, wc) + pc.p4.w < -r) { return; }
    if (dot(pc.p5.xyz, wc) + pc.p5.w < -r) { return; }
    let slot = atomicAdd(&ind.instance_count, 1u);
    let d = slot * pc.stride;
    for (var k = 0u; k < pc.stride; k = k + 1u) { dst[d + k] = src[o + k]; }
}
"#;

#[cfg(feature = "vk")]
#[repr(C)]
struct GeoPush { vp: [f32; 16], sky: f32, material: f32, has_lm: f32, time: f32 } // 80 B, must match the WGSL Push

// kinetic clock, seconds, session-relative (small — f32 sin/cos stays precise). Java pushes it once
// per frame instead of threading a param through every draw entry point.
#[cfg(feature = "vk")]
static SPIN_TIME: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
#[cfg(feature = "vk")]
pub fn set_spin_time(t: f32) { SPIN_TIME.store(t.to_bits(), std::sync::atomic::Ordering::Relaxed); }
#[cfg(feature = "vk")]
fn spin_time() -> f32 { f32::from_bits(SPIN_TIME.load(std::sync::atomic::Ordering::Relaxed)) }
#[cfg(feature = "vk")]
#[repr(C)]
struct CullPushC { planes: [[f32; 4]; 6], center_r: [f32; 4], count: u32, stride: u32, _pad: [u32; 2] } // 128 B exactly

#[cfg(feature = "vk")]
const VERT_STRIDE: u32 = 32;       // pos3 + uv2 + normal3 (8 floats) — KgeckoModelRender.bake
#[cfg(feature = "vk")]
const SKIN_VERT_STRIDE: u32 = 36;  // + bone id (9 floats) — KgeckoModelRender.bakeSkinned
#[cfg(feature = "vk")]
const INST_STRIDE: u32 = 76;       // mat4 + light vec2 + tint (19 floats)
#[cfg(feature = "vk")]
// mat4 + light2 + tint + rotQuat4 + axis3 + speed + pivot3 + offset + pad (32 floats).
// fat, but it goes up ONCE — the whole point is that nothing rewrites it per frame.
const SPIN_INST_STRIDE: u32 = 128;
#[cfg(feature = "vk")]
// mat4 + light2 + alpha + entityXZ + radius + pad (24 floats)
const SHADOW_INST_STRIDE: u32 = 96;
#[cfg(feature = "vk")]
pub const FLOATS_PER_SHADOW: usize = 24;
#[cfg(feature = "vk")]
pub const FLOATS_PER_SPIN: usize = 32;
#[cfg(feature = "vk")]
const SKIN_INST_STRIDE: u32 = 88;  // mat4 + light vec2 + bone_base + tint + uv offset (22 floats)
#[cfg(feature = "vk")]
const FLOATS_PER_INST: usize = 19;
#[cfg(feature = "vk")]
const TINT_WHITE: f32 = 16777215.0; // r + g*256 + b*65536, all 255
#[cfg(feature = "vk")]
const IND_BYTES: u64 = 20;         // VkDrawIndexedIndirectCommand

// the bake emits QUADS (4 verts/face); Vulkan has no quad topology, so we draw indexed as 2 tris per quad.
#[cfg(feature = "vk")]
struct GeoModel {
    mesh: GpuBuf, vert_count: u32, cap_bytes: u64,
    idx: GpuBuf, idx_count: u32, idx_cap: u32,
    tex_view: vk::ImageView,
    center: [f32; 3], radius: f32,   // local bounding sphere for the GPU cull
    skinned: bool,
    material: u32,
    pass: u32,
    order: u32,
    bone_source: i64,
    dynamic: bool, // instances re-fed every frame (kontra) — cull would compact LAST frame's transforms -> jitter
    spin: bool,    // kinetic instances, angle resolved in the vertex shader from pc.time
    shadow: bool,  // flywheel entity shadow — UV derived from world position
    // pose applied before every instance of this model. lets kontra instances stay in kontra-LOCAL
    // space and never get rewritten when the ship moves — only these 16 floats change per frame.
    parent: Option<[f32; 16]>,
}

// column-major 4x4, same layout JOML's Matrix4f.get(float[]) hands us
#[cfg(feature = "vk")]
fn mat4_mul(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut o = [0f32; 16];
    for c in 0..4 {
        for r in 0..4 {
            o[c * 4 + r] = a[r] * b[c * 4]
                + a[4 + r] * b[c * 4 + 1]
                + a[8 + r] * b[c * 4 + 2]
                + a[12 + r] * b[c * 4 + 3];
        }
    }
    o
}

// ring of buffers — CPU/compute writes the next slot while the GPU may still read the last from an
// in-flight frame. without it, mid-flight re-upload tore data (glitch) or freed live memory (DEVICE_LOST).
#[cfg(feature = "vk")]
const INST_RING: usize = 3;

#[cfg(feature = "vk")]
struct GeoInst {
    bufs: Vec<GpuBuf>, count: u32, cap_bytes: u64, cur: usize,
    // GPU-cull outputs (static models only): compacted instances + the indirect command
    culled: Vec<GpuBuf>, indirect: Vec<GpuBuf>, cull_cap: u64, cull_cur: usize, cull_frame: u64,
    // bone-matrix SSBO ring (skinned models only)
    bones: Vec<GpuBuf>, bones_cap: u64, bones_cur: usize,
}

#[cfg(feature = "vk")]
impl GeoInst {
    fn empty() -> Self {
        GeoInst { bufs: Vec::new(), count: 0, cap_bytes: 0, cur: 0,
                  culled: Vec::new(), indirect: Vec::new(), cull_cap: 0, cull_cur: 0, cull_frame: 0,
                  bones: Vec::new(), bones_cap: 0, bones_cur: 0 }
    }
}

// skinned pipeline is optional — if its shader/pipe fails, static still works and Java falls back to CPU anim
#[cfg(feature = "vk")]
struct SkinPipe {
    layout: vk::PipelineLayout,
    pipe: vk::Pipeline, blend_pipe: vk::Pipeline,
    entity_pipe: vk::Pipeline, entity_blend_pipe: vk::Pipeline,
    entity_no_depth_pipe: vk::Pipeline, entity_add_pipe: vk::Pipeline,
    set_layout: vk::DescriptorSetLayout,
}
#[cfg(feature = "vk")]
struct CullPipe { layout: vk::PipelineLayout, pipe: vk::Pipeline, set_layout: vk::DescriptorSetLayout }

#[cfg(feature = "vk")]
struct GeoState {
    layout:     vk::PipelineLayout,
    pipe:       vk::Pipeline,
    blend_pipe: vk::Pipeline,
    spin_pipe:  vk::Pipeline,
    spin_blend_pipe: vk::Pipeline,
    shadow_pipe: vk::Pipeline,
    set_layout: vk::DescriptorSetLayout,
    sampler:    vk::Sampler,
    lm_sampler: vk::Sampler,
    lightmap:   vk::ImageView,   // MC's level lightmap; null until Java feeds it
    push_desc:  ash::khr::push_descriptor::Device,
    skin:       Option<SkinPipe>,
    cull:       Option<CullPipe>,
    models:     HashMap<i64, GeoModel>,
    instances:  HashMap<i64, GeoInst>,
    // shadow-map casting. modules stay alive because the pipelines are built lazily: we only learn a
    // shader mod's cascade attachment formats when it opens the pass, and they're not ours to guess.
    smap_vm:    vk::ShaderModule,
    smap_fm:    vk::ShaderModule,
    smap_pipes: HashMap<u64, vk::Pipeline>,   // key = color_fmt << 32 | depth_fmt
}

#[cfg(feature = "vk")]
static GEO: Mutex<Option<GeoState>> = Mutex::new(None);

// deferred buffer destruction: never free a GpuBuf the same frame we replace it — an in-flight frame may still be
// reading it on the GPU (that was the VK_ERROR_DEVICE_LOST when spamming blocks -> instance buffer regrows -> old
// buffer freed while frame N-1 still draws from it). retire it, free it BUF_KEEP frames later when it's idle.
#[cfg(feature = "vk")]
const BUF_KEEP: u64 = 4;
#[cfg(feature = "vk")]
static GEO_FRAME: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
#[cfg(feature = "vk")]
static GRAVEYARD: Mutex<Vec<(GpuBuf, u64)>> = Mutex::new(Vec::new());
#[cfg(feature = "vk")]
fn retire(buf: GpuBuf) {
    let at = GEO_FRAME.load(std::sync::atomic::Ordering::Relaxed) + BUF_KEEP;
    GRAVEYARD.lock().unwrap().push((buf, at));
}

#[cfg(feature = "vk")]
static DREW_ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
#[cfg(feature = "vk")]
static NOGEO_ONCE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
// once the pipeline build fails, stop retrying every frame (deterministic failure -> would just spam)
#[cfg(feature = "vk")]
static ENSURE_FAILED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

// instance-rate vertex attribute (binding 1) — the per-frame attr() helper hardcodes binding 0
#[cfg(feature = "vk")]
fn iattr(loc: u32, fmt: vk::Format, off: u32) -> vk::VertexInputAttributeDescription {
    vk::VertexInputAttributeDescription::default().location(loc).binding(1).format(fmt).offset(off)
}

#[cfg(feature = "vk")]
unsafe fn ensure(ctx: &KenderVkCtx, g: &mut Option<GeoState>) -> Result<(), i32> {
    use std::sync::atomic::Ordering;
    if g.is_some() { return Ok(()); }
    if ENSURE_FAILED.load(Ordering::Relaxed) { return Err(-99); } // failed once -> don't retry/spam
    match ensure_inner(ctx, g) {
        Ok(()) => Ok(()),
        Err(e) => { ENSURE_FAILED.store(true, Ordering::Relaxed); Err(e) }
    }
}

#[cfg(feature = "vk")]
unsafe fn ensure_inner(ctx: &KenderVkCtx, g: &mut Option<GeoState>) -> Result<(), i32> {
    crate::klog("[Kender] ensure: building geo pipelines (first time)...");
    let dev = &ctx.device;
    let vspv = compile_wgsl(GEO_VERT)
        .map_err(|e| { crate::kerr(&format!("[Kender] GEO_VERT (wgsl) compile FAILED: {e}")); -30i32 })?;
    let fspv = compile_wgsl(&geo_frag())
        .map_err(|e| { crate::kerr(&format!("[Kender] GEO_FRAG (wgsl) compile FAILED: {e}")); -31i32 })?;
    let efspv = compile_wgsl(&geo_frag_entity())
        .map_err(|e| { crate::kerr(&format!("[Kender] GEO_FRAG_ENTITY compile FAILED: {e}")); -34i32 })?;
    let vm = shader_mod(dev, &vspv)?;
    let fm = shader_mod(dev, &fspv)?;
    let efm = shader_mod(dev, &efspv)?;

    // set 0: binding 0 = sampled texture, binding 1 = sampler (WGSL splits them). PUSH_DESCRIPTOR -> bound inline per draw
    let binds = [
        vk::DescriptorSetLayoutBinding::default()
            .binding(0).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(1).descriptor_type(vk::DescriptorType::SAMPLER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(3).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(4).descriptor_type(vk::DescriptorType::SAMPLER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
    ];
    let set_layout = dev.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default()
            .flags(vk::DescriptorSetLayoutCreateFlags::PUSH_DESCRIPTOR_KHR)
            .bindings(&binds),
        None,
    ).map_err(|_| -34i32)?;

    // nearest + clamp = crisp pixel-art blocks
    let sampler = dev.create_sampler(
        &vk::SamplerCreateInfo::default()
            .mag_filter(vk::Filter::NEAREST).min_filter(vk::Filter::NEAREST)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE),
        None,
    ).map_err(|_| -35i32)?;

    // lightmap wants LINEAR — vanilla samples it with texture(), not texelFetch, so the 16x16
    // table gets interpolated. NEAREST here banded every torch gradient.
    let lm_sampler = dev.create_sampler(
        &vk::SamplerCreateInfo::default()
            .mag_filter(vk::Filter::LINEAR).min_filter(vk::Filter::LINEAR)
            .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
            .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE),
        None,
    ).map_err(|_| -35i32)?;

    let push = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
        .offset(0).size(std::mem::size_of::<GeoPush>() as u32);
    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(std::slice::from_ref(&set_layout))
            .push_constant_ranges(std::slice::from_ref(&push)),
        None,
    ).map_err(|_| -32i32)?;

    let static_bindings = [
        vk::VertexInputBindingDescription::default().binding(0).stride(VERT_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
        vk::VertexInputBindingDescription::default().binding(1).stride(INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
    ];
    let static_attrs = [
        attr(0,  vk::Format::R32G32B32_SFLOAT,    0),   // pos
        attr(1,  vk::Format::R32G32_SFLOAT,       12),  // uv
        attr(2,  vk::Format::R32G32B32_SFLOAT,    20),  // normal
        iattr(3, vk::Format::R32G32B32A32_SFLOAT, 0),   // model col0
        iattr(4, vk::Format::R32G32B32A32_SFLOAT, 16),  // model col1
        iattr(5, vk::Format::R32G32B32A32_SFLOAT, 32),  // model col2
        iattr(6, vk::Format::R32G32B32A32_SFLOAT, 48),  // model col3
        iattr(7, vk::Format::R32G32_SFLOAT,       64),  // light (block, sky)
        iattr(8, vk::Format::R32_SFLOAT,          72),  // tint (value-encoded rgb)
    ];
    let pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, fm, ctx.color_fmt, ctx.depth_fmt, &static_bindings, &static_attrs, 0, true)
        .map_err(|e| { crate::kerr(&format!("[Kender] geo pipeline build FAILED: code {e}")); e })?;
    let blend_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, fm, ctx.color_fmt, ctx.depth_fmt, &static_bindings, &static_attrs, 1, true)
        .map_err(|e| { crate::kerr(&format!("[Kender] geo blend pipeline build FAILED: code {e}")); e })?;
    dev.destroy_shader_module(vm, None);

    // kinetic pipeline — same frag, fatter instance stride, angle from pc.time.
    // if it fails to build we fall back to treating spin models as plain static ones (Java keeps
    // baking the angle on the CPU), so a driver that hates this shader costs fps, not pixels.
    let spin_spv = compile_wgsl(SPIN_VERT)
        .map_err(|e| { crate::kerr(&format!("[Kender] SPIN_VERT compile FAILED: {e}")); -36i32 });
    let svm = match spin_spv.as_ref().ok().map(|spv| shader_mod(dev, spv)) {
        Some(Ok(m)) => m,
        _ => vk::ShaderModule::null(),
    };
    let spin_bindings = [
        vk::VertexInputBindingDescription::default().binding(0).stride(VERT_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
        vk::VertexInputBindingDescription::default().binding(1).stride(SPIN_INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
    ];
    let spin_attrs = [
        attr(0,  vk::Format::R32G32B32_SFLOAT,    0),
        attr(1,  vk::Format::R32G32_SFLOAT,       12),
        attr(2,  vk::Format::R32G32B32_SFLOAT,    20),
        iattr(3, vk::Format::R32G32B32A32_SFLOAT, 0),
        iattr(4, vk::Format::R32G32B32A32_SFLOAT, 16),
        iattr(5, vk::Format::R32G32B32A32_SFLOAT, 32),
        iattr(6, vk::Format::R32G32B32A32_SFLOAT, 48),
        iattr(7, vk::Format::R32G32_SFLOAT,       64),  // light
        iattr(8, vk::Format::R32_SFLOAT,          72),  // tint
        iattr(9, vk::Format::R32G32B32A32_SFLOAT, 76),  // base rotation quat
        iattr(10, vk::Format::R32G32B32A32_SFLOAT, 92), // axis xyz + speed
        iattr(11, vk::Format::R32G32B32A32_SFLOAT, 108),// pivot xyz + offset
    ];
    // optional, exactly like skin/cull: a driver that refuses this shader costs fps, not pixels —
    // set_spin then answers -1 and Java keeps baking the angle on the CPU
    let (spin_pipe, spin_blend_pipe) = if svm == vk::ShaderModule::null() {
        crate::kerr("[Kender] spin shader unavailable — kinetic instances stay on the CPU path");
        (vk::Pipeline::null(), vk::Pipeline::null())
    } else {
        let a = build_geo_pipe(dev, ctx.pipe_cache, layout, svm, fm, ctx.color_fmt, ctx.depth_fmt, &spin_bindings, &spin_attrs, 0, true);
        let b = build_geo_pipe(dev, ctx.pipe_cache, layout, svm, fm, ctx.color_fmt, ctx.depth_fmt, &spin_bindings, &spin_attrs, 1, true);
        dev.destroy_shader_module(svm, None);
        match (a, b) {
            (Ok(x), Ok(y)) => (x, y),
            _ => { crate::kerr("[Kender] spin pipeline build failed — kinetic instances stay on the CPU path");
                   (vk::Pipeline::null(), vk::Pipeline::null()) }
        }
    };

    // skinned pipeline — optional. failure = Java keeps CPU-animating those models, static still flies.
    let skin = build_skin(ctx, dev, fm, efm).map_err(|e| {
        crate::kerr(&format!("[Kender] skin pipeline build failed (code {e}) — animated stay on CPU"));
        e
    }).ok();

    // cull pipeline — optional. failure = plain full-instance draws (still correct, just no GPU cull).
    let cull = build_cull(dev, ctx.pipe_cache).map_err(|e| {
        crate::kerr(&format!("[Kender] cull pipeline build failed (code {e}) — drawing uncullled"));
        e
    }).ok();

    dev.destroy_shader_module(fm, None);
    dev.destroy_shader_module(efm, None);

    let push_desc = ash::khr::push_descriptor::Device::new(&ctx.instance, &ctx.device);

    // shadows: blend-only, own vert+frag. soft-fails like spin — no pipeline just means no instanced shadows.
    let shadow_pipe = (|| -> Option<vk::Pipeline> {
        let vspv = compile_wgsl(SHADOW_VERT).ok()?;
        let fspv = compile_wgsl(SHADOW_FRAG).ok()?;
        let vm2 = shader_mod(dev, &vspv).ok()?;
        let fm2 = shader_mod(dev, &fspv).ok()?;
        let binds = [
            vk::VertexInputBindingDescription::default().binding(0).stride(VERT_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
            vk::VertexInputBindingDescription::default().binding(1).stride(SHADOW_INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
        ];
        let attrs = [
            attr(0,  vk::Format::R32G32B32_SFLOAT,    0),
            attr(1,  vk::Format::R32G32_SFLOAT,       12),
            attr(2,  vk::Format::R32G32B32_SFLOAT,    20),
            iattr(3, vk::Format::R32G32B32A32_SFLOAT, 0),
            iattr(4, vk::Format::R32G32B32A32_SFLOAT, 16),
            iattr(5, vk::Format::R32G32B32A32_SFLOAT, 32),
            iattr(6, vk::Format::R32G32B32A32_SFLOAT, 48),
            iattr(7, vk::Format::R32G32_SFLOAT,       64),  // light
            iattr(8, vk::Format::R32_SFLOAT,          72),  // alpha
            iattr(9, vk::Format::R32G32B32_SFLOAT,    76),  // entity xz + radius
        ];
        let p = build_geo_pipe(dev, ctx.pipe_cache, layout, vm2, fm2, ctx.color_fmt, ctx.depth_fmt, &binds, &attrs, 1, true).ok();
        dev.destroy_shader_module(vm2, None);
        dev.destroy_shader_module(fm2, None);
        p
    })().unwrap_or(vk::Pipeline::null());
    if shadow_pipe == vk::Pipeline::null() {
        crate::kerr("[Kender] shadow pipeline unavailable — entity shadows stay on the CPU path");
    }

    // shadow-map modules: compiled now, pipelines built later once a cascade pass shows its formats
    let (smap_vm, smap_fm) = (|| -> Option<(vk::ShaderModule, vk::ShaderModule)> {
        let v = shader_mod(dev, &compile_wgsl(SMAP_VERT).ok()?).ok()?;
        let f = shader_mod(dev, &compile_wgsl(SMAP_FRAG).ok()?).ok()?;
        Some((v, f))
    })().unwrap_or((vk::ShaderModule::null(), vk::ShaderModule::null()));
    if smap_vm == vk::ShaderModule::null() {
        crate::kerr("[Kender] shadow-map shaders failed — kender geometry won't cast into shader-mod cascades");
    }

    *g = Some(GeoState { layout, pipe, blend_pipe, spin_pipe, spin_blend_pipe, shadow_pipe, set_layout, sampler, lm_sampler,
                         lightmap: vk::ImageView::null(), push_desc, skin, cull,
                         models: HashMap::new(), instances: HashMap::new(),
                         smap_vm, smap_fm, smap_pipes: HashMap::new() });
    crate::klog(&format!("[Kender] geo pipelines built OK (color={} depth={} skin={} cull={})",
        ctx.color_fmt.as_raw(), ctx.depth_fmt.as_raw(),
        g.as_ref().unwrap().skin.is_some(), g.as_ref().unwrap().cull.is_some()));
    super::stash_pso(); // 9 fresh pipelines just landed in the cache — bank them before any crash
    Ok(())
}

#[cfg(feature = "vk")]
unsafe fn build_skin(ctx: &KenderVkCtx, dev: &Device, fm: vk::ShaderModule,
                     entity_fm: vk::ShaderModule) -> Result<SkinPipe, i32> {
    let vspv = compile_wgsl(SKIN_VERT)
        .map_err(|e| { crate::kerr(&format!("[Kender] SKIN_VERT compile FAILED: {e}")); -40i32 })?;
    let vm = shader_mod(dev, &vspv)?;

    let binds = [
        vk::DescriptorSetLayoutBinding::default()
            .binding(0).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(1).descriptor_type(vk::DescriptorType::SAMPLER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(2).descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::VERTEX),
        vk::DescriptorSetLayoutBinding::default()
            .binding(3).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        vk::DescriptorSetLayoutBinding::default()
            .binding(4).descriptor_type(vk::DescriptorType::SAMPLER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
    ];
    let set_layout = dev.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default()
            .flags(vk::DescriptorSetLayoutCreateFlags::PUSH_DESCRIPTOR_KHR)
            .bindings(&binds),
        None,
    ).map_err(|_| -41i32)?;

    let push = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
        .offset(0).size(std::mem::size_of::<GeoPush>() as u32);
    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(std::slice::from_ref(&set_layout))
            .push_constant_ranges(std::slice::from_ref(&push)),
        None,
    ).map_err(|_| -42i32)?;

    let bindings = [
        vk::VertexInputBindingDescription::default().binding(0).stride(SKIN_VERT_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
        vk::VertexInputBindingDescription::default().binding(1).stride(SKIN_INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
    ];
    let attrs = [
        attr(0,  vk::Format::R32G32B32_SFLOAT,    0),   // pos
        attr(1,  vk::Format::R32G32_SFLOAT,       12),  // uv
        attr(2,  vk::Format::R32G32B32_SFLOAT,    20),  // normal
        attr(3,  vk::Format::R32_SFLOAT,          32),  // bone id
        iattr(4, vk::Format::R32G32B32A32_SFLOAT, 0),
        iattr(5, vk::Format::R32G32B32A32_SFLOAT, 16),
        iattr(6, vk::Format::R32G32B32A32_SFLOAT, 32),
        iattr(7, vk::Format::R32G32B32A32_SFLOAT, 48),
        iattr(8, vk::Format::R32G32_SFLOAT,       64),  // light
        iattr(9, vk::Format::R32_SFLOAT,          72),  // bone_base
        iattr(10, vk::Format::R32_SFLOAT,         76),  // tint
        iattr(11, vk::Format::R32G32_SFLOAT,      80),  // animated texture offset
    ];
    let pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 0, true)?;
    let blend_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 1, true)?;
    let entity_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, entity_fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 0, true)?;
    let entity_blend_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, entity_fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 1, true)?;
    let entity_no_depth_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, entity_fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 1, false)?;
    let entity_add_pipe = build_geo_pipe(dev, ctx.pipe_cache, layout, vm, entity_fm, ctx.color_fmt, ctx.depth_fmt, &bindings, &attrs, 2, true)?;
    dev.destroy_shader_module(vm, None);
    Ok(SkinPipe { layout, pipe, blend_pipe, entity_pipe, entity_blend_pipe,
        entity_no_depth_pipe, entity_add_pipe, set_layout })
}

#[cfg(feature = "vk")]
unsafe fn build_cull(dev: &Device, cache: vk::PipelineCache) -> Result<CullPipe, i32> {
    let cspv = compile_wgsl(CULL_COMP)
        .map_err(|e| { crate::kerr(&format!("[Kender] CULL_COMP compile FAILED: {e}")); -50i32 })?;
    let cm = shader_mod(dev, &cspv)?;

    let binds = [
        vk::DescriptorSetLayoutBinding::default()
            .binding(0).descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::COMPUTE),
        vk::DescriptorSetLayoutBinding::default()
            .binding(1).descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::COMPUTE),
        vk::DescriptorSetLayoutBinding::default()
            .binding(2).descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .descriptor_count(1).stage_flags(vk::ShaderStageFlags::COMPUTE),
    ];
    let set_layout = dev.create_descriptor_set_layout(
        &vk::DescriptorSetLayoutCreateInfo::default()
            .flags(vk::DescriptorSetLayoutCreateFlags::PUSH_DESCRIPTOR_KHR)
            .bindings(&binds),
        None,
    ).map_err(|_| -51i32)?;

    let push = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::COMPUTE)
        .offset(0).size(std::mem::size_of::<CullPushC>() as u32); // 128 B — the guaranteed max
    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .set_layouts(std::slice::from_ref(&set_layout))
            .push_constant_ranges(std::slice::from_ref(&push)),
        None,
    ).map_err(|_| -52i32)?;

    let entry = c"main";
    let stage = vk::PipelineShaderStageCreateInfo::default()
        .stage(vk::ShaderStageFlags::COMPUTE).module(cm).name(entry);
    let ci = vk::ComputePipelineCreateInfo::default().stage(stage).layout(layout);
    let pipe = dev.create_compute_pipelines(cache, std::slice::from_ref(&ci), None)
        .map_err(|_| -53i32)?[0];
    dev.destroy_shader_module(cm, None);
    Ok(CullPipe { layout, pipe, set_layout })
}

#[cfg(feature = "vk")]
unsafe fn build_geo_pipe(
    dev: &Device, cache: vk::PipelineCache, layout: vk::PipelineLayout,
    vm: vk::ShaderModule, fm: vk::ShaderModule,
    color_fmt: vk::Format, depth_fmt: vk::Format,
    bindings: &[vk::VertexInputBindingDescription],
    attrs: &[vk::VertexInputAttributeDescription],
    blend: u32,
    depth_write: bool,
) -> Result<vk::Pipeline, i32> {
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vm).name(entry),
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(fm).name(entry),
    ];

    let color_fmts = std::slice::from_ref(&color_fmt);
    let mut rendering = vk::PipelineRenderingCreateInfo::default()
        .color_attachment_formats(color_fmts)
        .depth_attachment_format(depth_fmt);

    let dyn_s = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let mut blend_att = vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(vk::ColorComponentFlags::RGBA);
    if blend != 0 {
        blend_att = blend_att.blend_enable(true)
            .src_color_blend_factor(if blend == 2 { vk::BlendFactor::ONE } else { vk::BlendFactor::SRC_ALPHA })
            .dst_color_blend_factor(if blend == 2 { vk::BlendFactor::ONE } else { vk::BlendFactor::ONE_MINUS_SRC_ALPHA })
            .color_blend_op(vk::BlendOp::ADD)
            .src_alpha_blend_factor(vk::BlendFactor::ONE)
            .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
            .alpha_blend_op(vk::BlendOp::ADD);
    }

    let vi = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(bindings).vertex_attribute_descriptions(attrs);
    let ia = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let vps = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
    // NONE cull: geo winding isn't verified consistent; fragment cost isn't the bottleneck (vertex count was)
    let rs = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let ms = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    // MC 26.2 uses REVERSE-Z depth (near plane -> 1, far -> 0; depth buffer cleared to 0). proven by the diag:
    // a block 14.8 away gave z/w = near/dist = 0.0034. so nearer = larger depth -> compare GREATER_OR_EQUAL.
    let ds = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(true).depth_write_enable(depth_write)
        .depth_compare_op(vk::CompareOp::GREATER_OR_EQUAL);
    let cb = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_att));
    let dyn_state = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dyn_s);

    let ci = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vi)
        .input_assembly_state(&ia)
        .viewport_state(&vps)
        .rasterization_state(&rs)
        .multisample_state(&ms)
        .depth_stencil_state(&ds)
        .color_blend_state(&cb)
        .dynamic_state(&dyn_state)
        .layout(layout)
        .push_next(&mut rendering);

    dev.create_graphics_pipelines(cache, std::slice::from_ref(&ci), None)
        .map_err(|_| -33i32)
        .map(|v| v[0])
}

// ── public API (called from FFM) ───────────────────────────────────────────────

/// Upload (or replace) a model's baked mesh (stride 8: static, KgeckoModelRender.bake).
#[cfg(feature = "vk")]
pub unsafe fn upload_model(ctx: &KenderVkCtx, id: i64, verts: &[f32]) {
    upload_impl(ctx, id, verts, 8, false);
}

/// Upload a SKINNED mesh (stride 9: + bone id, KgeckoModelRender.bakeSkinned). Returns -1 if the
/// skin pipeline isn't available (Java then keeps CPU-animating this model).
#[cfg(feature = "vk")]
pub unsafe fn upload_skinned(ctx: &KenderVkCtx, id: i64, verts: &[f32]) -> i32 {
    {
        let mut guard = GEO.lock().unwrap();
        if ensure(ctx, &mut guard).is_err() { return -2; }
        if guard.as_ref().unwrap().skin.is_none() { return -1; }
    }
    upload_impl(ctx, id, verts, 9, true);
    0
}

#[cfg(feature = "vk")]
unsafe fn upload_impl(ctx: &KenderVkCtx, id: i64, verts: &[f32], stride: usize, skinned: bool) {
    if verts.is_empty() { return; }
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    let g = guard.as_mut().unwrap();

    let bytes_len = verts.len() * 4;
    let vcount = (verts.len() / stride) as u32;
    let need = bytes_len as u64;

    // local bounding sphere for the GPU cull (static models; skinned aren't culled)
    let (mut mn, mut mx) = ([f32::MAX; 3], [f32::MIN; 3]);
    let mut i = 0;
    while i + 2 < verts.len() {
        for k in 0..3 {
            if verts[i + k] < mn[k] { mn[k] = verts[i + k]; }
            if verts[i + k] > mx[k] { mx[k] = verts[i + k]; }
        }
        i += stride;
    }
    let center = [(mn[0] + mx[0]) * 0.5, (mn[1] + mx[1]) * 0.5, (mn[2] + mx[2]) * 0.5];
    let radius = (((mx[0] - mn[0]).powi(2) + (mx[1] - mn[1]).powi(2) + (mx[2] - mn[2]).powi(2)).sqrt()) * 0.5;

    let entry = g.models.entry(id).or_insert_with(|| {
        let buf = GpuBuf::new(&ctx.device, &ctx.mem_props, need, vk::BufferUsageFlags::VERTEX_BUFFER)
            .expect("kender geo: OOM mesh");
        let ibuf = GpuBuf::new(&ctx.device, &ctx.mem_props, 4, vk::BufferUsageFlags::INDEX_BUFFER)
            .expect("kender geo: OOM idx");
        GeoModel { mesh: buf, vert_count: 0, cap_bytes: need, idx: ibuf, idx_count: 0, idx_cap: 0,
                   tex_view: vk::ImageView::null(), center, radius, skinned, material: 0, pass: 0,
                   order: 0, bone_source: id, dynamic: false, spin: false, shadow: false, parent: None }
    });
    if need > entry.cap_bytes {
        let cap = fatcap(need);
        let nb = GpuBuf::new(&ctx.device, &ctx.mem_props, cap, vk::BufferUsageFlags::VERTEX_BUFFER)
            .expect("kender geo: OOM mesh grow");
        retire(std::mem::replace(&mut entry.mesh, nb));
        entry.cap_bytes = cap;
    }
    let data = std::slice::from_raw_parts(verts.as_ptr() as *const u8, bytes_len);
    entry.mesh.upload(&ctx.device, data);
    entry.vert_count = vcount;
    entry.center = center;
    entry.radius = radius;
    entry.skinned = skinned;

    // quads -> 2 triangles each: per quad q, indices (4q, 4q+1, 4q+2, 4q, 4q+2, 4q+3). u32 indices.
    let quads = vcount / 4;
    let idx_count = quads * 6;
    if idx_count > entry.idx_cap {
        // pattern is deterministic so prefill up to the fattened cap — later grows within cap skip this whole branch
        let cap_quads = fatcap(quads as u64) as u32;
        let cap_idx = cap_quads * 6;
        let mut inds: Vec<u32> = Vec::with_capacity(cap_idx as usize);
        for q in 0..cap_quads {
            let b = q * 4;
            inds.extend_from_slice(&[b, b + 1, b + 2, b, b + 2, b + 3]);
        }
        let nb = GpuBuf::new(&ctx.device, &ctx.mem_props, (cap_idx as u64) * 4, vk::BufferUsageFlags::INDEX_BUFFER)
            .expect("kender geo: OOM idx grow");
        retire(std::mem::replace(&mut entry.idx, nb));
        let ib = std::slice::from_raw_parts(inds.as_ptr() as *const u8, inds.len() * 4);
        entry.idx.upload(&ctx.device, ib);
        entry.idx_cap = cap_idx;
    }
    entry.idx_count = idx_count;
}

// pow2 fatten — exact-fit caps meant +1 block on a kontra = whole ring realloc, driver hiccup mid flight
#[cfg(feature = "vk")]
fn fatcap(need: u64) -> u64 { need.next_power_of_two().max(64) }

// (re)build a ring of buffers sized `need`, retiring the old ones (in-flight safety)
#[cfg(feature = "vk")]
unsafe fn regrow_ring(ctx: &KenderVkCtx, ring: &mut Vec<GpuBuf>, need: u64, usage: vk::BufferUsageFlags) {
    for b in ring.drain(..) { retire(b); }
    *ring = (0..INST_RING)
        .map(|_| GpuBuf::new(&ctx.device, &ctx.mem_props, need.max(4), usage).expect("kender geo: OOM ring"))
        .collect();
}

/// Set the live instances for a model. static stride 18 floats, skinned stride 20.
#[cfg(feature = "vk")]
pub unsafe fn set_instances(ctx: &KenderVkCtx, id: i64, data: &[f32]) {
    // a model marked kinetic carries the fat stride — set_spin has to land before its first instances
    let (spin, shadow) = {
        let guard = GEO.lock().unwrap();
        let m = guard.as_ref().and_then(|g| g.models.get(&id));
        (m.is_some_and(|m| m.spin), m.is_some_and(|m| m.shadow))
    };
    if shadow    { set_instances_stride(ctx, id, data, FLOATS_PER_SHADOW, SHADOW_INST_STRIDE); }
    else if spin { set_instances_stride(ctx, id, data, FLOATS_PER_SPIN, SPIN_INST_STRIDE); }
    else         { set_instances_stride(ctx, id, data, FLOATS_PER_INST, INST_STRIDE); }
}

#[cfg(feature = "vk")]
pub fn remove_model(id: i64) {
    let mut guard = GEO.lock().unwrap();
    let Some(g) = guard.as_mut() else { return; };
    if let Some(model) = g.models.remove(&id) {
        retire(model.mesh);
        retire(model.idx);
    }
    if let Some(mut inst) = g.instances.remove(&id) {
        for b in inst.bufs.drain(..) { retire(b); }
        for b in inst.culled.drain(..) { retire(b); }
        for b in inst.indirect.drain(..) { retire(b); }
        for b in inst.bones.drain(..) { retire(b); }
    }
}

#[cfg(feature = "vk")]
pub unsafe fn set_instances_skinned(ctx: &KenderVkCtx, id: i64, data: &[f32]) {
    set_instances_stride(ctx, id, data, 22, SKIN_INST_STRIDE);
}

#[cfg(feature = "vk")]
unsafe fn set_instances_stride(ctx: &KenderVkCtx, id: i64, data: &[f32], floats: usize, stride: u32) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    set_instances_locked(ctx, guard.as_mut().unwrap(), id, data, floats, stride);
}

#[cfg(feature = "vk")]
unsafe fn set_instances_locked(ctx: &KenderVkCtx, g: &mut GeoState, id: i64,
                               data: &[f32], floats: usize, stride: u32) {
    let count = (data.len() / floats) as u32;
    // bug A hunt: create part meshes ride negative ids — where do their instances actually land?
    if id < 0 && count > 0 && data.len() >= 15 {
        static SPY: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);
        if SPY.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 16 {
            crate::klog(&format!("[Kender] inst spy id={} n={} t=({:.1},{:.1},{:.1})",
                id, count, data[12], data[13], data[14]));
        }
    }
    let need = count.max(1) as u64 * stride as u64;
    let entry = g.instances.entry(id).or_insert_with(GeoInst::empty);
    if entry.bufs.is_empty() || need > entry.cap_bytes {
        let cap = fatcap(need);
        // instances double as the compute-cull input -> STORAGE
        regrow_ring(ctx, &mut entry.bufs, cap,
            vk::BufferUsageFlags::VERTEX_BUFFER | vk::BufferUsageFlags::STORAGE_BUFFER);
        entry.cap_bytes = cap;
    }
    // advance to the next ring slot so we never overwrite a buffer the GPU is still reading this frame
    entry.cur = (entry.cur + 1) % INST_RING;
    if count > 0 {
        let bytes = std::slice::from_raw_parts(data.as_ptr() as *const u8, data.len() * 4);
        entry.bufs[entry.cur].upload(&ctx.device, bytes);
    }
    entry.count = count;
}

/// Per-frame bone matrices for a skinned model (16 floats per bone, all anim states concatenated).
#[cfg(feature = "vk")]
pub unsafe fn set_bones(ctx: &KenderVkCtx, id: i64, data: &[f32]) {
    if data.is_empty() { return; }
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    set_bones_locked(ctx, guard.as_mut().unwrap(), id, data);
}

#[cfg(feature = "vk")]
unsafe fn set_bones_locked(ctx: &KenderVkCtx, g: &mut GeoState, id: i64, data: &[f32]) {
    let need = (data.len() * 4) as u64;
    let entry = g.instances.entry(id).or_insert_with(GeoInst::empty);
    if entry.bones.is_empty() || need > entry.bones_cap {
        let cap = fatcap(need);
        regrow_ring(ctx, &mut entry.bones, cap, vk::BufferUsageFlags::STORAGE_BUFFER);
        entry.bones_cap = cap;
    }
    entry.bones_cur = (entry.bones_cur + 1) % INST_RING;
    let bytes = std::slice::from_raw_parts(data.as_ptr() as *const u8, data.len() * 4);
    entry.bones[entry.bones_cur].upload(&ctx.device, bytes);
}

/// All entity batch uploads for one frame under one FFM call and one GEO lock.
/// meta per id: bone offset/count, instance offset/count (all in floats).
#[cfg(feature = "vk")]
pub unsafe fn set_skinned_frame(ctx: &KenderVkCtx, ids: &[i64], meta: &[i32],
                                bones: &[f32], instances: &[f32]) -> i32 {
    if meta.len() < ids.len() * 4 { return -2; }
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return -3; }
    let g = guard.as_mut().unwrap();
    for (i, id) in ids.iter().copied().enumerate() {
        let m = &meta[i * 4..i * 4 + 4];
        if m.iter().any(|v| *v < 0) { return -4; }
        let (bo, bc, io, ic) = (m[0] as usize, m[1] as usize, m[2] as usize, m[3] as usize);
        if bo.checked_add(bc).is_none_or(|end| end > bones.len())
            || io.checked_add(ic).is_none_or(|end| end > instances.len()) { return -5; }
        if bc > 0 { set_bones_locked(ctx, g, id, &bones[bo..bo + bc]); }
        set_instances_locked(ctx, g, id, &instances[io..io + ic], 22, SKIN_INST_STRIDE);
    }
    0
}

/// Rust-side instance build. Input stride:
/// [px, py, pz, qx, qy, qz, qw, sx, sy, sz, blockLight, skyLight].
#[cfg(feature = "vk")]
pub unsafe fn set_instances_trs(ctx: &KenderVkCtx, id: i64, trs: &[f32]) {
    const TRS_STRIDE: usize = 12;
    if trs.is_empty() {
        set_instances(ctx, id, &[]);
        return;
    }

    let count = trs.len() / TRS_STRIDE;
    let mut packed = Vec::with_capacity(count * FLOATS_PER_INST);

    for inst in trs.chunks_exact(TRS_STRIDE) {
        let translation = Vec3::new(inst[0], inst[1], inst[2]);
        let q = Quat::from_xyzw(inst[3], inst[4], inst[5], inst[6]);
        let rotation = if q.length_squared() > 0.0 { q.normalize() } else { Quat::IDENTITY };
        let scale = Vec3::new(inst[7], inst[8], inst[9]);
        let model = Mat4::from_scale_rotation_translation(scale, rotation, translation);

        packed.extend_from_slice(&model.to_cols_array());
        packed.extend_from_slice(&[inst[10], inst[11], TINT_WHITE]);
    }

    set_instances(ctx, id, &packed);
}

/// Parent pose for a model: applied before every instance, so instances can live in kontra-local
/// space and stay put while the ship moves. Empty slice clears it. 16 floats, column major.
#[cfg(feature = "vk")]
pub unsafe fn set_parent(ctx: &KenderVkCtx, id: i64, m: &[f32]) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(model) = guard.as_mut().unwrap().models.get_mut(&id) {
        model.parent = if m.len() >= 16 { Some(m[..16].try_into().unwrap()) } else { None };
    }
}

/// Mark a model's instances as per-frame dynamic (kontra) — the GPU cull skips it entirely.
#[cfg(feature = "vk")]
pub unsafe fn set_dynamic(ctx: &KenderVkCtx, id: i64) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(m) = guard.as_mut().unwrap().models.get_mut(&id) { m.dynamic = true; }
}

#[cfg(feature = "vk")]
pub unsafe fn set_material(ctx: &KenderVkCtx, id: i64, material: u32) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(m) = guard.as_mut().unwrap().models.get_mut(&id) {
        m.material = material.min(5);
    }
}

// 0 = world/block, 1 = opaque entity, 2 = translucent entity
#[cfg(feature = "vk")]
pub unsafe fn set_pass(ctx: &KenderVkCtx, id: i64, pass: u32) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(m) = guard.as_mut().unwrap().models.get_mut(&id) { m.pass = pass; }
}

#[cfg(feature = "vk")]
pub unsafe fn set_order(ctx: &KenderVkCtx, id: i64, order: u32) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(m) = guard.as_mut().unwrap().models.get_mut(&id) { m.order = order.min(15); }
}

#[cfg(feature = "vk")]
pub unsafe fn set_bone_source(ctx: &KenderVkCtx, id: i64, source: i64) {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    if let Some(m) = guard.as_mut().unwrap().models.get_mut(&id) { m.bone_source = source; }
}

/// Set a model's texture (raw VkImageView from MC). Bound via push-descriptor with the shared nearest sampler.
#[cfg(feature = "vk")]
pub unsafe fn set_texture(ctx: &KenderVkCtx, id: i64, image_view: i64) {
    use ash::vk::Handle;
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    let g = guard.as_mut().unwrap();
    if let Some(m) = g.models.get_mut(&id) {
        m.tex_view = vk::ImageView::from_raw(image_view as u64);
    }
}

/// Mark a model kinetic: instances carry axis/speed/offset/pivot and the vertex shader spins them.
/// Implies the fat 32-float instance stride.
#[cfg(feature = "vk")]
pub unsafe fn set_spin(ctx: &KenderVkCtx, id: i64) -> i32 {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return -1; }
    let g = guard.as_mut().unwrap();
    if g.spin_pipe == vk::Pipeline::null() { return -1; }
    if let Some(m) = g.models.get_mut(&id) {
        // spinning geometry sweeps a bigger sphere than its rest pose — widen before the cull sees it.
        // pivot is per-instance so we can't be exact here; a block of slack is cheap and never wrong.
        if !m.spin {
            let c = (m.center[0].powi(2) + m.center[1].powi(2) + m.center[2].powi(2)).sqrt();
            m.radius += c + 1.0;
        }
        m.spin = true;
        return 0;
    }
    -1
}

/// 1 = the kinetic pipeline built. Java asks before committing a model to the fat stride.
#[cfg(feature = "vk")]
pub unsafe fn spin_available(ctx: &KenderVkCtx) -> i32 {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return 0; }
    (guard.as_ref().unwrap().spin_pipe != vk::Pipeline::null()) as i32
}

/// Mark a model as a flywheel entity shadow. Implies the 24-float instance stride.
#[cfg(feature = "vk")]
pub unsafe fn set_shadow(ctx: &KenderVkCtx, id: i64) -> i32 {
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return -1; }
    let g = guard.as_mut().unwrap();
    if g.shadow_pipe == vk::Pipeline::null() { return -1; }
    if let Some(m) = g.models.get_mut(&id) { m.shadow = true; return 0; }
    -1
}

#[cfg(feature = "vk")]
pub unsafe fn set_lightmap(ctx: &KenderVkCtx, image_view: i64) {
    use ash::vk::Handle;
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return; }
    let g = guard.as_mut().unwrap();
    let view = vk::ImageView::from_raw(image_view as u64);
    if g.lightmap != view {
        g.lightmap = view;
        crate::klog(&format!("[Kender] lightmap view -> 0x{:x}", image_view));
    }
}

// Gribb–Hartmann plane extraction from the view-proj (col-major). works for reverse-Z as-is; the
// shader's Y-flip is a reflection so containment tested on the UNflipped matrix is still exact.
#[cfg(feature = "vk")]
fn extract_planes(m: &[f32]) -> [[f32; 4]; 6] {
    let row = |r: usize| [m[r], m[4 + r], m[8 + r], m[12 + r]];
    let (r0, r1, r2, r3) = (row(0), row(1), row(2), row(3));
    let add = |a: [f32; 4], b: [f32; 4]| [a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]];
    let sub = |a: [f32; 4], b: [f32; 4]| [a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]];
    let mut planes = [add(r3, r0), sub(r3, r0), add(r3, r1), sub(r3, r1), r2, sub(r3, r2)];
    for p in planes.iter_mut() {
        let n = (p[0] * p[0] + p[1] * p[1] + p[2] * p[2]).sqrt();
        if n > 1e-6 { for k in 0..4 { p[k] /= n; } }
    }
    planes
}

// which frame draw() will run as — used to tag this frame's cull outputs
#[cfg(feature = "vk")]
fn next_frame() -> u64 { GEO_FRAME.load(std::sync::atomic::Ordering::Relaxed) + 1 }

// GPU cull engages per model from this instance count up; below it the dispatch+copy costs more than it saves
#[cfg(feature = "vk")]
const CULL_MIN: u32 = 512;

/// 1 = at least one model is big enough for the GPU cull this frame — Java skips the whole
/// transient-buffer dance otherwise (that overhead was the static fps dip on small scenes).
#[cfg(feature = "vk")]
pub unsafe fn cull_wanted() -> i32 {
    let guard = GEO.lock().unwrap();
    let Some(g) = guard.as_ref() else { return 0 };
    if g.cull.is_none() { return 0; }
    for (id, m) in g.models.iter() {
        if m.skinned || m.dynamic || m.shadow || m.vert_count == 0 || m.tex_view == vk::ImageView::null() { continue; }
        if g.instances.get(id).map(|i| i.count >= CULL_MIN).unwrap_or(false) { return 1; }
    }
    0
}

/// Packed cull telemetry: high 32 = models actually above the threshold, low 32 = biggest model's
/// instance count. Lets you see how far a scene is from the cull paying for itself.
#[cfg(feature = "vk")]
pub unsafe fn cull_stats() -> i64 {
    let guard = GEO.lock().unwrap();
    let Some(g) = guard.as_ref() else { return 0 };
    let (mut culled, mut max) = (0i64, 0i64);
    for (id, m) in g.models.iter() {
        if m.skinned || m.dynamic || m.vert_count == 0 { continue; }
        let c = g.instances.get(id).map(|i| i.count).unwrap_or(0) as i64;
        if c > max { max = c; }
        if c >= CULL_MIN as i64 { culled += 1; }
    }
    (culled << 32) | (max & 0xFFFF_FFFF)
}

/// Record the GPU frustum-cull into a transient command buffer that MC will execute BEFORE the world
/// passes (submission order). Java allocated+begun `cmd`; on 0 we've ENDED it (ready for execute()).
#[cfg(feature = "vk")]
pub unsafe fn record_cull(ctx: &KenderVkCtx, cmd_raw: i64, view_proj: &[f32]) -> i32 {
    use ash::vk::Handle;
    let cmd = vk::CommandBuffer::from_raw(cmd_raw as u64);
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return -2; }
    let g = guard.as_mut().unwrap();
    let Some(cull) = g.cull.as_ref() else { return -1; };
    if g.models.is_empty() { return -3; } // nothing to cull — Java ends the buffer itself

    let planes = extract_planes(view_proj);
    let f = next_frame();
    let models = &g.models;
    let instances = &mut g.instances;
    let mut any = false;

    for (id, m) in models.iter() {
        if m.skinned || m.dynamic || m.shadow || m.vert_count == 0 || m.idx_count == 0 || m.tex_view == vk::ImageView::null() { continue; }
        let Some(inst) = instances.get_mut(id) else { continue };
        // below the threshold a direct draw is cheaper than dispatch + compacted copy — the cull only
        // pays once the vertex shader would chew through walls of off-screen instances
        if inst.count < CULL_MIN || inst.bufs.is_empty() { continue; }

        let stride_b = if m.spin { SPIN_INST_STRIDE } else { INST_STRIDE };
        let need = inst.count as u64 * stride_b as u64;
        if inst.culled.is_empty() || need > inst.cull_cap {
            let cap = fatcap(need);
            regrow_ring(ctx, &mut inst.culled, cap,
                vk::BufferUsageFlags::VERTEX_BUFFER | vk::BufferUsageFlags::STORAGE_BUFFER);
            inst.cull_cap = cap;
        }
        if inst.indirect.is_empty() {
            regrow_ring(ctx, &mut inst.indirect, IND_BYTES,
                vk::BufferUsageFlags::INDIRECT_BUFFER | vk::BufferUsageFlags::STORAGE_BUFFER);
        }
        inst.cull_cur = (inst.cull_cur + 1) % INST_RING;

        // host-prefill this frame's indirect command: [indexCount, 0 (GPU fills), firstIndex, vertexOffset, firstInstance]
        let ind: [u32; 5] = [m.idx_count, 0, 0, 0, 0];
        inst.indirect[inst.cull_cur].upload(&ctx.device,
            std::slice::from_raw_parts(ind.as_ptr() as *const u8, 20));

        if !any {
            ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::COMPUTE, cull.pipe);
            any = true;
        }
        let infos = [
            vk::DescriptorBufferInfo::default().buffer(inst.bufs[inst.cur].buf).offset(0).range(vk::WHOLE_SIZE),
            vk::DescriptorBufferInfo::default().buffer(inst.culled[inst.cull_cur].buf).offset(0).range(vk::WHOLE_SIZE),
            vk::DescriptorBufferInfo::default().buffer(inst.indirect[inst.cull_cur].buf).offset(0).range(vk::WHOLE_SIZE),
        ];
        let writes = [
            vk::WriteDescriptorSet::default().dst_binding(0)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&infos[0])),
            vk::WriteDescriptorSet::default().dst_binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&infos[1])),
            vk::WriteDescriptorSet::default().dst_binding(2)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .buffer_info(std::slice::from_ref(&infos[2])),
        ];
        g.push_desc.cmd_push_descriptor_set(cmd, vk::PipelineBindPoint::COMPUTE, cull.layout, 0, &writes);

        let pc = CullPushC {
            planes,
            center_r: [m.center[0], m.center[1], m.center[2], m.radius],
            count: inst.count,
            stride: stride_b / 4,
            _pad: [0; 2],
        };
        ctx.device.cmd_push_constants(cmd, cull.layout, vk::ShaderStageFlags::COMPUTE, 0, bytemuck_cast(&pc));
        ctx.device.cmd_dispatch(cmd, (inst.count + 63) / 64, 1, 1);
        inst.cull_frame = f;
    }

    if any {
        // compute writes -> visible to the vertex fetch + indirect fetch of the world-pass draws that follow
        let barrier = vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::VERTEX_ATTRIBUTE_READ | vk::AccessFlags::INDIRECT_COMMAND_READ);
        ctx.device.cmd_pipeline_barrier(cmd,
            vk::PipelineStageFlags::COMPUTE_SHADER,
            vk::PipelineStageFlags::VERTEX_INPUT | vk::PipelineStageFlags::DRAW_INDIRECT,
            vk::DependencyFlags::empty(),
            std::slice::from_ref(&barrier), &[], &[]);
    }
    if ctx.device.end_command_buffer(cmd).is_err() { return -4; }
    0
}

/// Draw every model's instances. Static: indirect draw of the GPU-culled set (falls back to a
/// full direct draw when the cull didn't run this frame). Skinned: direct draw + bone SSBO.
#[cfg(feature = "vk")]
/// Draw every static/kinetic model into a shader mod's shadow cascade, using the light-space matrix
/// it hands us. Reuses the instance buffers already uploaded for the camera pass — nothing is rebuilt,
/// this is the same geometry seen from somewhere else. Skinned models sit this one out for now.
#[cfg(feature = "vk")]
/// `want_dynamic`: 1 = only kontra/moving models, 0 = only world-static ones, 2 = everything.
/// A shader mod keeps separate cascade targets for terrain and for entities, refreshed on different
/// intervals — moving geometry belongs in the entity one or its shadow lags whole frames behind.
#[cfg(feature = "vk")]
pub unsafe fn draw_shadow_map(ctx: &KenderVkCtx, cmd_raw: i64, width: u32, height: u32,
                              view_proj: &[f32], color_fmt: u32, depth_fmt: u32,
                              want_dynamic: i32) -> i32 {
    use ash::vk::Handle;
    let cmd = vk::CommandBuffer::from_raw(cmd_raw as u64);
    let mut guard = GEO.lock().unwrap();
    if ensure(ctx, &mut guard).is_err() { return -1; }
    let g = guard.as_mut().unwrap();
    if g.smap_vm == vk::ShaderModule::null() || g.models.is_empty() { return -2; }

    let key = ((color_fmt as u64) << 32) | depth_fmt as u64;
    let pipe = match g.smap_pipes.get(&key) {
        Some(p) => *p,
        None => {
            let binds = [
                vk::VertexInputBindingDescription::default().binding(0).stride(VERT_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
                vk::VertexInputBindingDescription::default().binding(1).stride(INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
            ];
            let attrs = [
                attr(0,  vk::Format::R32G32B32_SFLOAT,    0),
                attr(1,  vk::Format::R32G32_SFLOAT,       12),
                attr(2,  vk::Format::R32G32B32_SFLOAT,    20),
                iattr(3, vk::Format::R32G32B32A32_SFLOAT, 0),
                iattr(4, vk::Format::R32G32B32A32_SFLOAT, 16),
                iattr(5, vk::Format::R32G32B32A32_SFLOAT, 32),
                iattr(6, vk::Format::R32G32B32A32_SFLOAT, 48),
                iattr(7, vk::Format::R32G32_SFLOAT,       64),
                iattr(8, vk::Format::R32_SFLOAT,          72),
            ];
            let built = build_geo_pipe(&ctx.device, ctx.pipe_cache, g.layout, g.smap_vm, g.smap_fm,
                vk::Format::from_raw(color_fmt as i32), vk::Format::from_raw(depth_fmt as i32),
                &binds, &attrs, 0, true);
            let p = match built {
                Ok(p) => p,
                Err(e) => {
                    crate::kerr(&format!("[Kender] shadow-map pipeline build failed (color={color_fmt} depth={depth_fmt}) code {e}"));
                    g.smap_pipes.insert(key, vk::Pipeline::null());  // don't retry every frame
                    return -3;
                }
            };
            crate::klog(&format!("[Kender] shadow-map pipeline built for color={color_fmt} depth={depth_fmt}"));
            g.smap_pipes.insert(key, p);
            p
        }
    };
    if pipe == vk::Pipeline::null() { return -3; }

    let vp: [f32; 16] = view_proj.try_into().unwrap_or([0f32; 16]);
    let vport = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let scissor = vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent: vk::Extent2D { width, height } };
    ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vport));
    ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));
    ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);

    let mut pc = GeoPush { vp, sky: 1.0, material: 0.0, has_lm: 0.0, time: spin_time() };
    let mut drawn = 0i32;
    for (id, model) in g.models.iter() {
        // translucent casters would need sorted shadow alpha; skip them rather than blot the cascade
        if model.skinned || model.shadow || model.material == 1 { continue; }
        if want_dynamic != 2 && model.dynamic != (want_dynamic == 1) { continue; }
        let Some(inst) = g.instances.get(id) else { continue };
        if inst.count == 0 || inst.bufs.is_empty() || model.vert_count == 0
            || model.tex_view == vk::ImageView::null() { continue; }
        pc.material = model.material as f32;
        pc.vp = match &model.parent { Some(p) => mat4_mul(&vp, p), None => vp };
        ctx.device.cmd_push_constants(cmd, g.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT, 0, bytemuck_cast(&pc));
        push_tex(ctx, g, cmd, g.layout, model.tex_view, None);
        ctx.device.cmd_bind_index_buffer(cmd, model.idx.buf, 0, vk::IndexType::UINT32);
        // always the uncull'd buffer: the camera frustum cull threw away what the LIGHT can still see
        ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.bufs[inst.cur].buf], &[0, 0]);
        ctx.device.cmd_draw_indexed(cmd, model.idx_count, inst.count, 0, 0, 0);
        drawn += 1;
    }
    drawn
}

pub unsafe fn draw(ctx: &KenderVkCtx, cmd: vk::CommandBuffer, view_proj: &[f32], sky: f32, pass: Option<u32>) {
    // once per frame: tick the clock and free any retired buffer that's now BUF_KEEP frames old (GPU-idle)
    let f = if pass.is_none() || pass == Some(0) {
        GEO_FRAME.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1
    } else {
        GEO_FRAME.load(std::sync::atomic::Ordering::Relaxed)
    };
    {
        let mut gy = GRAVEYARD.lock().unwrap();
        let mut i = 0;
        while i < gy.len() {
            if gy[i].1 <= f { let (b, _) = gy.swap_remove(i); b.destroy(&ctx.device); }
            else { i += 1; }
        }
    }
    let guard = GEO.lock().unwrap();
    let Some(g) = guard.as_ref() else {
        if !NOGEO_ONCE.swap(true, std::sync::atomic::Ordering::Relaxed) {
            crate::klog("[Kender] draw: no GeoState (pipeline never built) — upload/set_instances never reached ensure()");
        }
        return;
    };
    if g.models.is_empty() { return; }

    let vp: [f32; 16] = view_proj.try_into().unwrap_or([0f32; 16]);
    let has_lm = if g.lightmap == vk::ImageView::null() { 0.0 } else { 1.0 };
    let mut pc = GeoPush { vp, sky, material: 0.0, has_lm, time: spin_time() };

    // one-shot diagnostic: what is the draw ACTUALLY doing?
    if !DREW_ONCE.swap(true, std::sync::atomic::Ordering::Relaxed) {
        for (id, m) in g.models.iter() {
            let (ic, cull) = g.instances.get(id).map(|i| (i.count, i.cull_frame == f)).unwrap_or((0, false));
            crate::klog(&format!("[Kender] draw model {}: verts={} inst={} tex={} skinned={} gpucull={} mat={} pass={} dyn={}",
                id, m.vert_count, ic, if m.tex_view == vk::ImageView::null() {"NULL"} else {"ok"}, m.skinned, cull,
                m.material, m.pass, m.dynamic));
        }
    }
    // count what this invocation actually records — a healthy model list with 0 recorded = routing bug
    static DRAW_SPY: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);
    let spy = DRAW_SPY.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 24;
    let mut recorded = 0u32;

    // pass 1: static models
    for material in [0u32, 2, 1] {
        let mut bound_static = false;
        pc.material = material as f32;
        for (id, model) in g.models.iter() {
            if model.skinned || model.spin || model.shadow || model.material != material
                || pass.is_some_and(|p| model.pass != p) { continue; }
            let Some(inst) = g.instances.get(id) else { continue };
            if inst.count == 0 || inst.bufs.is_empty() || model.vert_count == 0 || model.tex_view == vk::ImageView::null() { continue; }
            if !bound_static {
                let pipe = if material == 1 { g.blend_pipe } else { g.pipe };
                ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);
                bound_static = true;
            }
            // per model now, not once per pipeline: a kontra model folds its ship pose into vp here,
            // which is what lets its instance buffer stay static while the ship flies around. 80 B.
            pc.vp = match &model.parent { Some(p) => mat4_mul(&vp, p), None => vp };
            ctx.device.cmd_push_constants(cmd, g.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT, 0, bytemuck_cast(&pc));
            push_tex(ctx, g, cmd, g.layout, model.tex_view, None);
            ctx.device.cmd_bind_index_buffer(cmd, model.idx.buf, 0, vk::IndexType::UINT32);
            if inst.cull_frame == f && !inst.culled.is_empty() && !inst.indirect.is_empty() {
                ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.culled[inst.cull_cur].buf], &[0, 0]);
                ctx.device.cmd_draw_indexed_indirect(cmd, inst.indirect[inst.cull_cur].buf, 0, 1, IND_BYTES as u32);
            } else {
                ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.bufs[inst.cur].buf], &[0, 0]);
                ctx.device.cmd_draw_indexed(cmd, model.idx_count, inst.count, 0, 0, 0);
            }
            recorded += 1;
        }
    }

    // pass 1b: kinetic models — same frag, spin resolved in the vertex shader from pc.time.
    // instance buffers here are STATIC, so the GPU cull applies exactly like the plain static pass.
    for material in [0u32, 2, 1] {
        if g.spin_pipe == vk::Pipeline::null() { break; }
        let mut bound_spin = false;
        pc.material = material as f32;
        for (id, model) in g.models.iter() {
            if !model.spin || model.skinned || model.shadow || model.material != material
                || pass.is_some_and(|p| model.pass != p) { continue; }
            let Some(inst) = g.instances.get(id) else { continue };
            if inst.count == 0 || inst.bufs.is_empty() || model.vert_count == 0
                || model.tex_view == vk::ImageView::null() { continue; }
            if !bound_spin {
                let pipe = if material == 1 { g.spin_blend_pipe } else { g.spin_pipe };
                ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);
                bound_spin = true;
            }
            pc.vp = match &model.parent { Some(p) => mat4_mul(&vp, p), None => vp };
            ctx.device.cmd_push_constants(cmd, g.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT, 0, bytemuck_cast(&pc));
            push_tex(ctx, g, cmd, g.layout, model.tex_view, None);
            ctx.device.cmd_bind_index_buffer(cmd, model.idx.buf, 0, vk::IndexType::UINT32);
            if inst.cull_frame == f && !inst.culled.is_empty() && !inst.indirect.is_empty() {
                ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.culled[inst.cull_cur].buf], &[0, 0]);
                ctx.device.cmd_draw_indexed_indirect(cmd, inst.indirect[inst.cull_cur].buf, 0, 1, IND_BYTES as u32);
            } else {
                ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.bufs[inst.cur].buf], &[0, 0]);
                ctx.device.cmd_draw_indexed(cmd, model.idx_count, inst.count, 0, 0, 0);
            }
            recorded += 1;
        }
    }

    // pass 1c: entity shadows — always blended, UV resolved in their own vertex shader
    if g.shadow_pipe != vk::Pipeline::null() {
        let mut bound_shadow = false;
        pc.material = 1.0;
        for (id, model) in g.models.iter() {
            if !model.shadow || pass.is_some_and(|p| model.pass != p) { continue; }
            let Some(inst) = g.instances.get(id) else { continue };
            if inst.count == 0 || inst.bufs.is_empty() || model.vert_count == 0
                || model.tex_view == vk::ImageView::null() { continue; }
            if !bound_shadow {
                ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, g.shadow_pipe);
                ctx.device.cmd_push_constants(cmd, g.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT, 0, bytemuck_cast(&pc));
                bound_shadow = true;
            }
            push_tex(ctx, g, cmd, g.layout, model.tex_view, None);
            ctx.device.cmd_bind_index_buffer(cmd, model.idx.buf, 0, vk::IndexType::UINT32);
            ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.bufs[inst.cur].buf], &[0, 0]);
            ctx.device.cmd_draw_indexed(cmd, model.idx_count, inst.count, 0, 0, 0);
            recorded += 1;
        }
    }

    // pass 2: skinned models (bone SSBO at binding 2)
    let Some(skin) = g.skin.as_ref() else {
        if spy { crate::klog(&format!("[Kender] draw(pass={:?}) recorded={} (no skin pipe)", pass, recorded)); }
        return;
    };
    let max_order = if pass.is_some_and(|p| p != 0) { 15 } else { 0 };
    for order in 0..=max_order {
      for material in [0u32, 2, 1, 5, 3, 4] {
        let mut bound_skin = false;
        pc.material = material as f32;
        for (id, model) in g.models.iter() {
            if !model.skinned || model.material != material || model.order != order
                || pass.is_some_and(|p| model.pass != p) { continue; }
            let Some(inst) = g.instances.get(id) else { continue };
            let Some(bone_inst) = g.instances.get(&model.bone_source) else { continue };
            if inst.count == 0 || inst.bufs.is_empty() || bone_inst.bones.is_empty()
                || model.vert_count == 0 || model.tex_view == vk::ImageView::null() { continue; }
            if !bound_skin {
                let entity = pass.is_some_and(|p| p != 0);
                let pipe = if entity && material == 4 { skin.entity_add_pipe }
                    else if entity && material == 3 { skin.entity_no_depth_pipe }
                    else { match (entity, material == 1 || material == 5) {
                        (true, true) => skin.entity_blend_pipe,
                        (true, false) => skin.entity_pipe,
                        (false, true) => skin.blend_pipe,
                        (false, false) => skin.pipe,
                    }};
                ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);
                bound_skin = true;
            }
            pc.vp = match &model.parent { Some(p) => mat4_mul(&vp, p), None => vp };
            ctx.device.cmd_push_constants(cmd, skin.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT, 0, bytemuck_cast(&pc));
            push_tex(ctx, g, cmd, skin.layout, model.tex_view, Some(bone_inst.bones[bone_inst.bones_cur].buf));
            ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[model.mesh.buf, inst.bufs[inst.cur].buf], &[0, 0]);
            ctx.device.cmd_bind_index_buffer(cmd, model.idx.buf, 0, vk::IndexType::UINT32);
            ctx.device.cmd_draw_indexed(cmd, model.idx_count, inst.count, 0, 0, 0);
            recorded += 1;
        }
      }
    }
    if spy { crate::klog(&format!("[Kender] draw(pass={:?}) recorded={}", pass, recorded)); }
}

// push the texture (binding 0) + shared sampler (binding 1) [+ bone SSBO (binding 2)] inline — no pool
#[cfg(feature = "vk")]
unsafe fn push_tex(ctx: &KenderVkCtx, g: &GeoState, cmd: vk::CommandBuffer,
                   layout: vk::PipelineLayout, tex: vk::ImageView, bones: Option<vk::Buffer>) {
    let _ = ctx;
    let img = vk::DescriptorImageInfo::default()
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .image_view(tex);
    let smp = vk::DescriptorImageInfo::default().sampler(g.sampler);
    // lightmap binding must ALWAYS be a live view or the set is incomplete — before MC hands us
    // its lightmap we point it at the model texture and the shader ignores it (has_lm = 0).
    let lm_view = if g.lightmap == vk::ImageView::null() { tex } else { g.lightmap };
    let lm_img = vk::DescriptorImageInfo::default()
        .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
        .image_view(lm_view);
    let lm_smp = vk::DescriptorImageInfo::default().sampler(g.lm_sampler);
    let binfo;
    let mut writes = vec![
        vk::WriteDescriptorSet::default().dst_binding(0)
            .descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .image_info(std::slice::from_ref(&img)),
        vk::WriteDescriptorSet::default().dst_binding(1)
            .descriptor_type(vk::DescriptorType::SAMPLER)
            .image_info(std::slice::from_ref(&smp)),
        vk::WriteDescriptorSet::default().dst_binding(3)
            .descriptor_type(vk::DescriptorType::SAMPLED_IMAGE)
            .image_info(std::slice::from_ref(&lm_img)),
        vk::WriteDescriptorSet::default().dst_binding(4)
            .descriptor_type(vk::DescriptorType::SAMPLER)
            .image_info(std::slice::from_ref(&lm_smp)),
    ];
    if let Some(b) = bones {
        binfo = vk::DescriptorBufferInfo::default().buffer(b).offset(0).range(vk::WHOLE_SIZE);
        writes.push(vk::WriteDescriptorSet::default().dst_binding(2)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
            .buffer_info(std::slice::from_ref(&binfo)));
    }
    g.push_desc.cmd_push_descriptor_set(cmd, vk::PipelineBindPoint::GRAPHICS, layout, 0, &writes);
}

/// Hook B — draw into MC's ALREADY-OPEN world render pass. no begin/end: the color+depth
/// attachments AND MC's viewport/scissor are still bound (we inject at submitRenderPass HEAD,
/// right before vkCmdEndRendering). we just append our instanced draws to its command buffer.
#[cfg(feature = "vk")]
pub unsafe fn draw_in_pass(ctx: &KenderVkCtx, cmd_raw: i64, width: u32, height: u32, view_proj: &[f32], sky: f32) {
    use ash::vk::Handle;
    let cmd = vk::CommandBuffer::from_raw(cmd_raw as u64);
    // MC bakes viewport into its static pipelines -> the cmd buffer has NO dynamic viewport/scissor set.
    // our pipeline declares them dynamic, so we MUST set them or the draw rasterizes nothing (undefined state).
    let vp = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let sc = vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width, height } };
    ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vp));
    ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&sc));
    draw(ctx, cmd, view_proj, sky, Some(0));
}

#[cfg(feature = "vk")]
pub unsafe fn draw_entity_in_pass(ctx: &KenderVkCtx, cmd_raw: i64, width: u32, height: u32,
                                  view_proj: &[f32], sky: f32, pass: u32) {
    use ash::vk::Handle;
    let cmd = vk::CommandBuffer::from_raw(cmd_raw as u64);
    let vp = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let sc = vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width, height } };
    ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vp));
    ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&sc));
    draw(ctx, cmd, view_proj, sky, Some(pass));
}

/// full-frame geo draw — begin a LOAD pass into MC's color/depth, instanced-draw every model, end.
/// dead bring-up path, kept for the standalone debug route.
#[cfg(feature = "vk")]
pub unsafe fn draw_frame(ctx: &KenderVkCtx, cmd_raw: i64, color_raw: i64, depth_raw: i64,
                         width: u32, height: u32, view_proj: &[f32]) {
    use ash::vk::Handle;
    let cmd  = vk::CommandBuffer::from_raw(cmd_raw  as u64);
    let colv = vk::ImageView::from_raw(color_raw as u64);
    let depv = vk::ImageView::from_raw(depth_raw as u64);
    let vp = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let sc = vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width, height } };

    crate::vk::pass::begin(&ctx.dyn_rendering, cmd, colv, depv, width, height);
    ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vp));
    ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&sc));
    draw(ctx, cmd, view_proj, 1.0, None);
    crate::vk::pass::end(&ctx.dyn_rendering, cmd);
}

#[cfg(feature = "vk")]
pub unsafe fn destroy(ctx: &KenderVkCtx) {
    let mut guard = GEO.lock().unwrap();
    if let Some(g) = guard.take() {
        for m in g.models.values() { m.mesh.destroy(&ctx.device); m.idx.destroy(&ctx.device); }
        for i in g.instances.values() {
            for b in &i.bufs { b.destroy(&ctx.device); }
            for b in &i.culled { b.destroy(&ctx.device); }
            for b in &i.indirect { b.destroy(&ctx.device); }
            for b in &i.bones { b.destroy(&ctx.device); }
        }
        for (b, _) in GRAVEYARD.lock().unwrap().drain(..) { b.destroy(&ctx.device); }
        ctx.device.destroy_pipeline(g.pipe, None);
        ctx.device.destroy_pipeline(g.blend_pipe, None);
        if g.spin_pipe != vk::Pipeline::null() {
            ctx.device.destroy_pipeline(g.spin_pipe, None);
            ctx.device.destroy_pipeline(g.spin_blend_pipe, None);
        }
        if g.shadow_pipe != vk::Pipeline::null() { ctx.device.destroy_pipeline(g.shadow_pipe, None); }
        ctx.device.destroy_pipeline_layout(g.layout, None);
        ctx.device.destroy_descriptor_set_layout(g.set_layout, None);
        if let Some(s) = g.skin {
            ctx.device.destroy_pipeline(s.pipe, None);
            ctx.device.destroy_pipeline(s.blend_pipe, None);
            ctx.device.destroy_pipeline(s.entity_pipe, None);
            ctx.device.destroy_pipeline(s.entity_blend_pipe, None);
            ctx.device.destroy_pipeline(s.entity_no_depth_pipe, None);
            ctx.device.destroy_pipeline(s.entity_add_pipe, None);
            ctx.device.destroy_pipeline_layout(s.layout, None);
            ctx.device.destroy_descriptor_set_layout(s.set_layout, None);
        }
        if let Some(c) = g.cull {
            ctx.device.destroy_pipeline(c.pipe, None);
            ctx.device.destroy_pipeline_layout(c.layout, None);
            ctx.device.destroy_descriptor_set_layout(c.set_layout, None);
        }
        ctx.device.destroy_sampler(g.sampler, None);
        ctx.device.destroy_sampler(g.lm_sampler, None);
    }
}

// naga only validates WGSL at runtime — this test catches shader typos at `cargo test` time instead of in-game
#[cfg(all(test, feature = "vk"))]
mod tests {
    #[test]
    fn wgsl_shaders_compile() {
        for (name, src) in [("GEO_VERT", super::GEO_VERT.to_string()),
                            ("GEO_FRAG", super::geo_frag()),
                            ("GEO_FRAG_ENTITY", super::geo_frag_entity()),
                            ("SKIN_VERT", super::SKIN_VERT.to_string()),
                            ("SPIN_VERT", super::SPIN_VERT.to_string()),
                            ("SHADOW_VERT", super::SHADOW_VERT.to_string()),
                            ("SHADOW_FRAG", super::SHADOW_FRAG.to_string()),
                            ("SMAP_VERT", super::SMAP_VERT.to_string()),
                            ("SMAP_FRAG", super::SMAP_FRAG.to_string()),
                            ("CULL_COMP", super::CULL_COMP.to_string())] {
            if let Err(e) = crate::vk::pipeline::compile_wgsl(&src) {
                panic!("{name} failed to compile: {e}");
            }
        }
    }
}

// stubs — vk feature off
#[cfg(not(feature = "vk"))]
pub unsafe fn upload_model(_ctx: &KenderVkCtx, _id: i64, _verts: &[f32]) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn upload_skinned(_ctx: &KenderVkCtx, _id: i64, _verts: &[f32]) -> i32 { -1 }
#[cfg(not(feature = "vk"))]
pub unsafe fn set_instances(_ctx: &KenderVkCtx, _id: i64, _data: &[f32]) {}
#[cfg(not(feature = "vk"))]
pub fn remove_model(_id: i64) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_instances_skinned(_ctx: &KenderVkCtx, _id: i64, _data: &[f32]) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_instances_trs(_ctx: &KenderVkCtx, _id: i64, _trs: &[f32]) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_bones(_ctx: &KenderVkCtx, _id: i64, _data: &[f32]) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_texture(_ctx: &KenderVkCtx, _id: i64, _image_view: i64) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_lightmap(_ctx: &KenderVkCtx, _image_view: i64) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_spin(_ctx: &KenderVkCtx, _id: i64) -> i32 { -1 }
#[cfg(not(feature = "vk"))]
pub unsafe fn spin_available(_ctx: &KenderVkCtx) -> i32 { 0 }
#[cfg(not(feature = "vk"))]
pub unsafe fn set_shadow(_ctx: &KenderVkCtx, _id: i64) -> i32 { -1 }
#[cfg(not(feature = "vk"))]
pub unsafe fn cull_stats() -> i64 { 0 }
#[cfg(not(feature = "vk"))]
pub unsafe fn set_dynamic(_ctx: &KenderVkCtx, _id: i64) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_material(_ctx: &KenderVkCtx, _id: i64, _material: u32) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_pass(_ctx: &KenderVkCtx, _id: i64, _pass: u32) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_order(_ctx: &KenderVkCtx, _id: i64, _order: u32) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_bone_source(_ctx: &KenderVkCtx, _id: i64, _source: i64) {}
#[cfg(not(feature = "vk"))]
pub unsafe fn set_skinned_frame(_ctx: &KenderVkCtx, _ids: &[i64], _meta: &[i32],
                                _bones: &[f32], _instances: &[f32]) -> i32 { -1 }
#[cfg(not(feature = "vk"))]
pub unsafe fn record_cull(_ctx: &KenderVkCtx, _cmd: i64, _vp: &[f32]) -> i32 { -1 }
#[cfg(not(feature = "vk"))]
pub unsafe fn cull_wanted() -> i32 { 0 }
