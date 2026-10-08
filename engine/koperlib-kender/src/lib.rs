// kender — geometry engine for koperlib physics blocks
// CPU: face culling + AO + vertex buffer generation (always on)
// GPU: Vulkan MDI + mesh shaders — behind "vk" feature, off by default
//      (static link to vulkan-1.dll breaks GLFW init; enabled post-K1)

mod cache;
mod geometry;
mod kfx;
mod light;
mod types;

pub mod vk; // always compiled — stubs only until feature "vk" is on

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

pub(crate) static GPU_CAPS: AtomicU32 = AtomicU32::new(0);
static DEBUG_LOG: AtomicBool = AtomicBool::new(false);

// bring-up logging that survives — eprintln goes to the terminal only (log4j ignores raw stderr),
// so also append to run/koperlib_kender_debug.log which we can actually read back.
pub(crate) fn klog(msg: &str) {
    if !DEBUG_LOG.load(Ordering::Relaxed) { return; }
    use std::io::Write;
    eprintln!("{msg}");
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open("koperlib_kender_debug.log") {
        let _ = writeln!(f, "{msg}");
    }
}

pub(crate) fn kerr(msg: &str) {
    eprintln!("{msg}");
}

const CAP_VULKAN:       u32 = 1 << 0;
const CAP_MESH_SHADERS: u32 = 1 << 1;
const CAP_BINDLESS:     u32 = 1 << 2;

#[no_mangle]
pub extern "C" fn kender_init() -> i32 { 0 }

#[no_mangle]
pub extern "C" fn kender_set_debug(enabled: i32) {
    DEBUG_LOG.store(enabled != 0, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn kender_set_gpu_caps(flags: u32) {
    GPU_CAPS.store(flags, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn kender_has_mesh_shaders() -> i32 {
    (GPU_CAPS.load(Ordering::Relaxed) & CAP_MESH_SHADERS != 0) as i32
}

#[no_mangle]
pub extern "C" fn kender_has_bindless() -> i32 {
    (GPU_CAPS.load(Ordering::Relaxed) & CAP_BINDLESS != 0) as i32
}

// ── geometry API ─────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn kender_kontra_set_blocks(
    id:          i64,
    offsets_xyz: *const f32,
    block_ids:   *const i32,
    count:       i32,
) {
    if offsets_xyz.is_null() || block_ids.is_null() || count <= 0 { return; }
    let n = count as usize;
    let mut blocks = Vec::with_capacity(n);
    unsafe {
        for i in 0..n {
            blocks.push(types::KontraBlock {
                ox: (*offsets_xyz.add(i * 3    )).round() as i32,
                oy: (*offsets_xyz.add(i * 3 + 1)).round() as i32,
                oz: (*offsets_xyz.add(i * 3 + 2)).round() as i32,
                block_id: *block_ids.add(i) as u32,
            });
        }
    }
    cache::set_blocks(id, blocks);
}

#[no_mangle]
pub extern "C" fn kender_kontra_mark_dirty(id: i64) { cache::mark_dirty(id); }

#[no_mangle]
pub extern "C" fn kender_kontra_face_count(id: i64) -> i32 {
    let mut c = cache::kache().lock().unwrap();
    match c.get_mut(&id) {
        Some(g) => { if g.dirty { geometry::rebuild_mesh(g); } g.face_count as i32 }
        None    => -1,
    }
}

#[no_mangle]
pub extern "C" fn kender_kontra_get_face_masks(
    id:        i64,
    out_masks: *mut u8,
    out_count: *mut i32,
) -> i32 {
    if out_masks.is_null() || out_count.is_null() { return -1; }
    cache::face_masks_copy(id, out_masks, out_count)
}

#[no_mangle]
pub extern "C" fn kender_kontra_get_mesh_ptr(id: i64, out_float_count: *mut i32) -> i64 {
    if out_float_count.is_null() { return 0; }
    match cache::get_mesh_ptr(id) {
        Some((ptr, len)) => { unsafe { *out_float_count = len as i32; } ptr as i64 }
        None             => { unsafe { *out_float_count = 0; } 0 }
    }
}

#[no_mangle]
pub extern "C" fn kender_kontra_remove(id: i64) { cache::remove(id); }

#[no_mangle]
pub extern "C" fn kender_kontra_clear_all() { cache::clear_all(); }

#[no_mangle]
pub extern "C" fn kender_shutdown() {
    GPU_CAPS.store(0, Ordering::Relaxed);
    cache::clear_all();
}

// ── Vulkan GPU path ───────────────────────────────────────────────────────────
// ash is NOT statically linked — vulkan-1.dll loads lazily at kender_vk_init call time
// pipeline compilation (naga GLSL→SPIR-V) also happens at init time, not DLL load

#[no_mangle]
pub extern "C" fn kender_vk_init(
    vk_instance:  i64,
    vk_device:    i64,
    vk_queue:     i64,
    queue_family: u32,
    color_fmt:    i32,
    depth_fmt:    i32,
) -> i32 {
    match unsafe { vk::init_from_handles(vk_instance, vk_device, vk_queue, queue_family, color_fmt, depth_fmt) } {
        Ok(()) => {
            let prev = GPU_CAPS.load(Ordering::Relaxed);
            GPU_CAPS.store(prev | CAP_VULKAN, Ordering::Relaxed);
            0
        }
        Err(e) => e,
    }
}

/// Per-frame draw — called from Java inside LevelRenderer.render() TAIL when Vulkan is active.
/// cmd/color/depth: VkCommandBuffer/ImageView handles from MC's VulkanStateManager.
/// ids: array of kontraktion IDs (i64 × kontra_count).
/// transforms: 7 floats per kontraktion [px py pz  rx ry rz rw].
/// view_proj: 16 floats column-major mat4.
/// shadow: 1 = depth-only pass, 0 = color pass.
#[no_mangle]
pub extern "C" fn kender_vk_draw_frame(
    cmd_buf:      i64,
    color_view:   i64,
    depth_view:   i64,
    width:        u32,
    height:       u32,
    kontra_ids:   *const i64,
    transforms:   *const f32,
    kontra_count: i32,
    view_proj:    *const f32,
    shadow:       i32,
) -> i32 {
    if vk::ctx().is_none() { return -1; }
    if kontra_ids.is_null() || transforms.is_null() || view_proj.is_null() { return -2; }
    if kontra_count <= 0 { return 0; }
    let n = kontra_count as usize;
    unsafe {
        let ids  = std::slice::from_raw_parts(kontra_ids, n);
        let trs  = std::slice::from_raw_parts(transforms, n * 7);
        let vp   = std::slice::from_raw_parts(view_proj, 16);
        vk::do_draw_frame(cmd_buf, color_view, depth_view, width, height, ids, trs, vp, shadow != 0);
    }
    0
}

#[no_mangle]
pub extern "C" fn kender_vk_destroy() {
    unsafe { vk::destroy(); }
}

// ── geo instancing path (2b) ────────────────────────────────────────────────────
// optional accelerator: only does anything once the device is shared (kender_vk_init ok).
// when Vulkan/handles aren't available these no-op (-1) and Java keeps using COLLECT_SUBMITS.

#[no_mangle]
pub extern "C" fn kender_geo_upload_model(id: i64, verts: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        klog(&format!("[Kender] FFM upload_model id={} fc={} ctx_some={}", id, float_count, vk::ctx().is_some()));
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if verts.is_null() || float_count <= 0 { return -2; }
        unsafe { vk::geo::upload_model(ctx, id, std::slice::from_raw_parts(verts, float_count as usize)); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, verts, float_count); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_instances(id: i64, data: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        {
            use std::sync::atomic::{AtomicBool, Ordering};
            static ONCE: AtomicBool = AtomicBool::new(false);
            if !ONCE.swap(true, Ordering::Relaxed) {
                klog(&format!("[Kender] FFM set_instances id={} fc={} ctx_some={}", id, float_count, vk::ctx().is_some()));
            }
        }
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if float_count < 0 { return -2; }
        unsafe {
            let d: &[f32] = if data.is_null() || float_count == 0 { &[] }
                            else { std::slice::from_raw_parts(data, float_count as usize) };
            vk::geo::set_instances(ctx, id, d);
        }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, data, float_count); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_remove(id: i64) {
    vk::geo::remove_model(id);
}

#[no_mangle]
pub extern "C" fn kender_geo_set_instances_trs(id: i64, data: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if float_count < 0 { return -2; }
        if float_count % 12 != 0 { return -3; }
        unsafe {
            let d: &[f32] = if data.is_null() || float_count == 0 { &[] }
                            else { std::slice::from_raw_parts(data, float_count as usize) };
            vk::geo::set_instances_trs(ctx, id, d);
        }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, data, float_count); -1 }
}

/// parent pose folded in before every instance of this model. 16 floats column major, null clears.
/// this is what lets kontra instances stay in ship-local space instead of being rewritten every frame.
#[no_mangle]
pub extern "C" fn kender_geo_set_parent(id: i64, m: *const f32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe {
            let d: &[f32] = if m.is_null() { &[] } else { std::slice::from_raw_parts(m, 16) };
            vk::geo::set_parent(ctx, id, d);
        }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, m); -1 }
}

/// mark instances as per-frame dynamic (kontra) — the GPU cull skips this model
#[no_mangle]
pub extern "C" fn kender_geo_set_dynamic(id: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_dynamic(ctx, id); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = id; -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_material(id: i64, material: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_material(ctx, id, material.max(0) as u32); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, material); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_pass(id: i64, pass: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_pass(ctx, id, pass.max(0) as u32); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, pass); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_order(id: i64, order: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_order(ctx, id, order.max(0) as u32); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, order); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_bone_source(id: i64, source: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_bone_source(ctx, id, source); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, source); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_skinned_frame(
    ids: *const i64, meta: *const i32, batch_count: i32,
    bones: *const f32, bone_count: i32, instances: *const f32, instance_count: i32,
) -> i32 {
    #[cfg(feature = "vk")]
    {
        if batch_count < 0 || bone_count < 0 || instance_count < 0 { return -2; }
        if batch_count > 0 && (ids.is_null() || meta.is_null()) { return -3; }
        if bone_count > 0 && bones.is_null() { return -4; }
        if instance_count > 0 && instances.is_null() { return -5; }
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe {
            let ids = if batch_count == 0 { &[] } else { std::slice::from_raw_parts(ids, batch_count as usize) };
            let meta = if batch_count == 0 { &[] } else { std::slice::from_raw_parts(meta, batch_count as usize * 4) };
            let bones = if bone_count == 0 { &[] } else { std::slice::from_raw_parts(bones, bone_count as usize) };
            let instances = if instance_count == 0 { &[] } else { std::slice::from_raw_parts(instances, instance_count as usize) };
            return vk::geo::set_skinned_frame(ctx, ids, meta, bones, instances);
        }
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (ids, meta, batch_count, bones, bone_count, instances, instance_count); -1 }
}

/// Mark a model kinetic — instances carry axis/speed/offset/pivot, the vertex shader spins them.
/// Must land BEFORE the model's first set_instances (it selects the fat instance stride).
#[no_mangle]
pub extern "C" fn kender_geo_set_spin(id: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        return unsafe { vk::geo::set_spin(ctx, id) };
    }
    #[cfg(not(feature = "vk"))]
    { let _ = id; -1 }
}

/// 1 = kinetic pipeline is live. 0 = Java must keep baking spin angles on the CPU.
#[no_mangle]
pub extern "C" fn kender_geo_spin_available() -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return 0 };
        return unsafe { vk::geo::spin_available(ctx) };
    }
    #[cfg(not(feature = "vk"))]
    { 0 }
}

/// high 32 = models above the cull threshold, low 32 = largest model's instance count.
#[no_mangle]
pub extern "C" fn kender_geo_cull_stats() -> i64 {
    #[cfg(feature = "vk")]
    { return unsafe { vk::geo::cull_stats() }; }
    #[cfg(not(feature = "vk"))]
    { 0 }
}

/// Kinetic clock in seconds, session-relative. Pushed once per frame.
#[no_mangle]
pub extern "C" fn kender_geo_set_time(seconds: f32) -> i32 {
    #[cfg(feature = "vk")]
    { vk::geo::set_spin_time(seconds); return 0; }
    #[cfg(not(feature = "vk"))]
    { let _ = seconds; -1 }
}

/// Mark a model as a flywheel entity shadow (own vertex shader, 24-float instance stride).
#[no_mangle]
pub extern "C" fn kender_geo_set_shadow(id: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        return unsafe { vk::geo::set_shadow(ctx, id) };
    }
    #[cfg(not(feature = "vk"))]
    { let _ = id; -1 }
}

/// MC's level lightmap image view. re-fed every frame — MC can recreate it on resource reload.
#[no_mangle]
pub extern "C" fn kender_geo_set_lightmap(image_view: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_lightmap(ctx, image_view); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = image_view; -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_set_texture(id: i64, image_view: i64) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        unsafe { vk::geo::set_texture(ctx, id, image_view); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, image_view); -1 }
}

/// skinned mesh upload (stride 9: pos3+uv2+normal3+boneId). -1 = skin pipeline unavailable -> Java stays on CPU anim
#[no_mangle]
pub extern "C" fn kender_geo_upload_skinned(id: i64, verts: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if verts.is_null() || float_count <= 0 { return -2; }
        return unsafe { vk::geo::upload_skinned(ctx, id, std::slice::from_raw_parts(verts, float_count as usize)) };
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, verts, float_count); -1 }
}

/// skinned instances (stride 20: mat16 + light2 + boneBase + pad)
#[no_mangle]
pub extern "C" fn kender_geo_set_instances_skinned(id: i64, data: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if float_count < 0 { return -2; }
        unsafe {
            let d: &[f32] = if data.is_null() || float_count == 0 { &[] }
                            else { std::slice::from_raw_parts(data, float_count as usize) };
            vk::geo::set_instances_skinned(ctx, id, d);
        }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, data, float_count); -1 }
}

/// per-frame bone matrices for a skinned model (16 floats per bone, all anim states concatenated)
#[no_mangle]
pub extern "C" fn kender_geo_set_bones(id: i64, data: *const f32, float_count: i32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if data.is_null() || float_count <= 0 { return -2; }
        unsafe { vk::geo::set_bones(ctx, id, std::slice::from_raw_parts(data, float_count as usize)); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (id, data, float_count); -1 }
}

/// 1 = a model is big enough that the GPU cull pays this frame; Java skips the transient buffer otherwise
#[no_mangle]
pub extern "C" fn kender_geo_cull_wanted() -> i32 {
    #[cfg(feature = "vk")]
    { return unsafe { vk::geo::cull_wanted() }; }
    #[cfg(not(feature = "vk"))]
    { 0 }
}

/// GPU frustum cull, recorded into a transient cmd buffer MC executes before the world passes.
/// 0 = recorded AND the buffer is ended (ready for encoder.execute). != 0 = nothing recorded.
#[no_mangle]
pub extern "C" fn kender_geo_record_cull(cmd: i64, view_proj: *const f32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        return unsafe { vk::geo::record_cull(ctx, cmd, std::slice::from_raw_parts(view_proj, 16)) };
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, view_proj); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_draw(cmd: i64, color: i64, depth: i64, width: u32, height: u32, view_proj: *const f32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe { vk::geo::draw_frame(ctx, cmd, color, depth, width, height, std::slice::from_raw_parts(view_proj, 16)); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, color, depth, width, height, view_proj); -1 }
}

/// Hook B — draw geo INSIDE MC's already-open world render pass. `cmd` is the VulkanRenderPass's
/// live VkCommandBuffer (grabbed in the submitRenderPass mixin). no attachments needed: MC's are bound.
#[no_mangle]
pub extern "C" fn kender_geo_draw_in_pass(cmd: i64, width: u32, height: u32, view_proj: *const f32, sky: f32) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe { vk::geo::draw_in_pass(ctx, cmd, width, height, std::slice::from_raw_parts(view_proj, 16), sky); }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, width, height, view_proj, sky); -1 }
}

/// Draw kender geometry into a shader mod's shadow cascade. Returns models recorded, or negative on
/// refusal. Formats come from the pass's own attachments — we build a matching pipeline on first use.
#[no_mangle]
pub extern "C" fn kender_geo_draw_shadow_map(
    cmd: i64, width: u32, height: u32, view_proj: *const f32, color_fmt: u32, depth_fmt: u32,
    want_dynamic: i32
) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe {
            return vk::geo::draw_shadow_map(ctx, cmd, width, height,
                std::slice::from_raw_parts(view_proj, 16), color_fmt, depth_fmt, want_dynamic);
        }
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, width, height, view_proj, color_fmt, depth_fmt, want_dynamic); -1 }
}

#[no_mangle]
pub extern "C" fn kender_geo_draw_entity_in_pass(
    cmd: i64, width: u32, height: u32, view_proj: *const f32, sky: f32, pass: i32
) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe {
            vk::geo::draw_entity_in_pass(ctx, cmd, width, height,
                std::slice::from_raw_parts(view_proj, 16), sky, pass.max(1) as u32);
        }
        return 0;
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, width, height, view_proj, sky, pass); -1 }
}

// KFX native program storage.
// Java/fullpacks compile particle programs into this flat IR; Vulkan/GL paths can consume it without reparsing JSON.

#[no_mangle]
pub extern "C" fn kender_kfx_upload_program(
    id: i64,
    ops: *const f32,
    float_count: i32,
    sx: f32,
    sy: f32,
    sz: f32,
    ex: f32,
    ey: f32,
    ez: f32,
    born_ticks: f32,
) -> i32 {
    if ops.is_null() || float_count <= 0 { return -2; }
    if float_count % 24 != 0 { return -3; }
    let slice = unsafe { std::slice::from_raw_parts(ops, float_count as usize) };
    kfx::upload(id, slice, kfx::KfxEndpoint { sx, sy, sz, ex, ey, ez, born_ticks });
    0
}

/// Upload one immutable KFX2 byte program. Returns its graph hash, or 0 when validation fails.
#[no_mangle]
pub extern "C" fn kender_kfx_graph_upload(bytes: *const u8, byte_count: i32) -> i64 {
    if bytes.is_null() || byte_count <= 0 { return 0; }
    let slice = unsafe { std::slice::from_raw_parts(bytes, byte_count as usize) };
    match kfx::upload_graph(slice) {
        Ok(hash) if hash != 0 => hash as i64,
        Ok(_) | Err(_) => 0,
    }
}

/// Spawn a cheap instance referencing an already uploaded graph hash.
#[no_mangle]
pub extern "C" fn kender_kfx_graph_spawn(
    id: i64, graph_hash: i64, seed: i64, particle_budget: i32,
    sx: f32, sy: f32, sz: f32, ex: f32, ey: f32, ez: f32, born_ticks: f32,
) -> i32 {
    if particle_budget <= 0 { return -2; }
    if kfx::spawn_graph(id, graph_hash as u64, seed as u64,
        kfx::KfxEndpoint { sx, sy, sz, ex, ey, ez, born_ticks }, particle_budget as usize) { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn kender_kfx_remove(id: i64) {
    kfx::remove(id);
}

#[no_mangle]
pub extern "C" fn kender_kfx_update(
    id: i64, sx: f32, sy: f32, sz: f32, ex: f32, ey: f32, ez: f32,
) {
    kfx::update_endpoint(id, sx, sy, sz, ex, ey, ez);
}

// emitter sim lives in rust so it ticks across cores. java uploads the def once; p = 24 floats (see KenderBridge).
#[no_mangle]
pub extern "C" fn kender_emitter_spawn(id: i64, color: i32, color2: i32, p: *const f32, count: i32) -> i32 {
    if p.is_null() || count < 24 { return -2; }
    let f = unsafe { std::slice::from_raw_parts(p, count as usize) };
    kfx::emitter_spawn(id, kfx::EmitterDef {
        sx: f[0], sy: f[1], sz: f[2], ex: f[3], ey: f[4], ez: f[5],
        color: color as u32, color2: color2 as u32,
        radius: f[6], rate: f[7], burst: f[8] as i32, particle_lifetime: f[9] as i32,
        spread: f[10], speed: f[11], gravity: f[12], drag: f[13],
        size_end: f[14], turbulence: f[15], max_particles: f[16] as i32,
        shape: f[17] as i32, motion: f[18] as i32,
        style: if count > 24 { f[24] as i32 } else { 7 },
        lifetime: f[19] as i32, fade_in: f[20], fade_out: f[21], loop_on: f[22] != 0.0,
        born_ticks: f[23],
    });
    0
}

/// Attach a bounded visual-only occupancy field to one native emitter.
#[no_mangle]
pub extern "C" fn kender_emitter_collision(
    id: i64, cells: *const u8, cell_count: i32, origin_x: i32, origin_y: i32, origin_z: i32,
    side: i32, response: i32, restitution: f32, friction: f32,
) -> i32 {
    if cells.is_null() || cell_count <= 0 || side <= 0 { return -2; }
    let data = unsafe { std::slice::from_raw_parts(cells, cell_count as usize) };
    if kfx::emitter_collision(id, [origin_x, origin_y, origin_z], side as usize, data,
        response as u8, restitution, friction) { 0 } else { -1 }
}

// portable path: build the full POSITION_COLOR vertex buffer (camera-facing billboards) for all live particles.
// returns ptr (or 0); *out_vcount = vertex count. Java memcpys this into a GpuBuffer and draws one RenderPass.
#[no_mangle]
pub extern "C" fn kender_kfx_build_vertices(
    now: f32, cam_x: f64, cam_y: f64, cam_z: f64,
    right_x: f32, right_y: f32, right_z: f32,
    up_x: f32, up_y: f32, up_z: f32,
    out_vcount: *mut i32,
) -> i64 {
    if out_vcount.is_null() { return 0; }
    kfx::build_vertices(now, [cam_x, cam_y, cam_z], [right_x, right_y, right_z], [up_x, up_y, up_z], out_vcount)
}

#[no_mangle]
pub extern "C" fn kender_emitter_remove(id: i64) { kfx::emitter_remove(id); }

#[no_mangle]
pub extern "C" fn kender_emitter_clear() { kfx::emitter_clear(); }

#[no_mangle]
pub extern "C" fn kender_emitter_count() -> i32 { kfx::emitter_count() as i32 }

#[no_mangle]
pub extern "C" fn kender_kfx_clear() {
    kfx::clear();
}

#[no_mangle]
pub extern "C" fn kender_kfx_count() -> i32 {
    kfx::count() as i32
}

#[no_mangle]
pub extern "C" fn kender_kfx_float_count() -> i32 {
    kfx::float_count() as i32
}

#[no_mangle]
pub extern "C" fn kender_kfx_latest_born_ticks() -> f32 {
    kfx::latest_born_ticks()
}

#[no_mangle]
pub extern "C" fn kender_kfx_eval_ptr(id: i64, age_ticks: f32, out_float_count: *mut i32) -> i64 {
    kfx::eval_ptr(id, age_ticks, out_float_count)
}

/// GPU particle draw — gathers every live KFX program, instanced-draws the whole set in one call.
/// cmd/color/depth: raw VkCommandBuffer/ImageView from MC. view_proj: 16 floats col-major.
/// now_ticks: session-relative ticks (small float, NOT wall clock). cam: camera world pos.
#[no_mangle]
pub extern "C" fn kender_kfx_draw(
    cmd: i64, color: i64, depth: i64, width: u32, height: u32,
    view_proj: *const f32, now_ticks: f32, cam_x: f64, cam_y: f64, cam_z: f64,
) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe {
            return vk::particles::draw_frame_kfx(
                ctx, cmd, color, depth, width, height,
                std::slice::from_raw_parts(view_proj, 16), now_ticks, [cam_x, cam_y, cam_z],
            );
        }
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, color, depth, width, height, view_proj, now_ticks, cam_x, cam_y, cam_z); -1 }
}

/// Same particle draw, but MC's world pass is already open. 1 = drew, 0 = no particles, <0 = fallback.
#[no_mangle]
pub extern "C" fn kender_kfx_draw_in_pass(
    cmd: i64, width: u32, height: u32, view_proj: *const f32,
    now_ticks: f32, cam_x: f64, cam_y: f64, cam_z: f64,
) -> i32 {
    #[cfg(feature = "vk")]
    {
        let ctx = match vk::ctx() { Some(c) => c, None => return -1 };
        if view_proj.is_null() { return -2; }
        unsafe {
            return vk::particles::draw_in_pass_kfx(
                ctx, cmd, width, height, std::slice::from_raw_parts(view_proj, 16),
                now_ticks, [cam_x, cam_y, cam_z],
            );
        }
    }
    #[cfg(not(feature = "vk"))]
    { let _ = (cmd, width, height, view_proj, now_ticks, cam_x, cam_y, cam_z); -1 }
}
