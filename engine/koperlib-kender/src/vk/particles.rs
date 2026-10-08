// kender KFX particle instancing: one instance buffer, grouped by style, one instanced draw per style
// mesh (vk/meshes.rs) and one additive halo draw over all of it. the GPU places, tumbles and lights each
// mesh from its camera-relative center+size+color+seed, instead of the CPU tessellating every frame.

#[cfg(feature = "vk")]
use ash::{vk, Device};
#[cfg(feature = "vk")]
use std::sync::Mutex;
#[cfg(feature = "vk")]
use crate::vk::buffer::GpuBuf;
#[cfg(feature = "vk")]
use crate::vk::pipeline::{shader_mod, compile_glsl, bytemuck_cast};
#[cfg(feature = "vk")]
use super::KenderVkCtx;
#[cfg(feature = "vk")]
use super::meshes;

#[cfg(feature = "vk")]
const PART_VERT: &str = r#"
#version 450
layout(location=0) in vec3 i_center;
layout(location=1) in float i_size;
layout(location=2) in vec4 i_color;
layout(location=3) in float i_style;
layout(location=4) in float i_seed;
layout(location=5) in vec3 v_pos;
layout(location=6) in vec3 v_normal;
layout(push_constant) uniform Push { mat4 vp; vec4 params; } p;
layout(location=0) out vec3 o_normal;
layout(location=1) out vec4 o_color;
layout(location=2) out vec3 o_world;
float h(float x) { return fract(sin(x) * 43758.5453); }
vec3 turn(vec3 v, vec3 axis, float c, float s) {
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}
void main() {
    float s = i_seed * 12.9898 + 1.0;
    vec3 axis = vec3(h(s), h(s + 1.7), h(s + 3.1)) * 2.0 - 1.0;
    if (length(axis) < 0.001) { axis = vec3(0.0, 1.0, 0.0); }
    axis = normalize(axis);
    // every particle tumbles about its own axis at its own slow rate
    float ang = h(s + 7.9) * 6.28318530718 + p.params.x * (h(s + 5.3) - 0.5) * 0.12;
    float c = cos(ang);
    float sn = sin(ang);
    vec3 world = i_center + turn(v_pos, axis, c, sn) * i_size;
    gl_Position = p.vp * vec4(world, 1.0);
    gl_Position.y = -gl_Position.y;
    o_normal = turn(v_normal, axis, c, sn);
    o_color = vec4(i_color.rgb * (0.88 + h(s + 11.3) * 0.24), i_color.a);
    o_world = world;
}
"#;

// key light + sky fill + a pale fresnel rim. KfxRenderer.litColor does the same per vertex
#[cfg(feature = "vk")]
const PART_FRAG: &str = r#"
#version 450
layout(location=0) in vec3 o_normal;
layout(location=1) in vec4 o_color;
layout(location=2) in vec3 o_world;
layout(location=0) out vec4 col;
void main() {
    vec3 n = normalize(o_normal);
    vec3 v = normalize(-o_world);
    float key = max(dot(n, vec3(0.3363, 0.9034, 0.2649)), 0.0);
    float lit = 0.58 + 0.3 * key + 0.17 * (0.5 + 0.5 * n.y);
    float rim = 1.0 - abs(dot(n, v));
    float fres = rim * rim * 0.85;
    vec3 rgb = min(o_color.rgb * lit + (o_color.rgb * 0.4 + vec3(0.6)) * fres, vec3(1.0));
    col = vec4(rgb, o_color.a * (0.82 + 0.18 * fres));
}
"#;

// one camera-facing quad per particle; the camera axes come from the view-projection rows
#[cfg(feature = "vk")]
const GLOW_VERT: &str = r#"
#version 450
layout(location=0) in vec3 i_center;
layout(location=1) in float i_size;
layout(location=2) in vec4 i_color;
layout(location=3) in float i_style;
layout(location=4) in float i_seed;
layout(push_constant) uniform Push { mat4 vp; vec4 params; } p;
layout(location=0) out vec2 o_uv;
layout(location=1) out vec4 o_color;
layout(location=2) out float o_sprite;
void main() {
    int id = int(gl_VertexIndex);
    float cx = (id == 1 || id == 2 || id == 4) ? 1.0 : -1.0;
    float cy = (id == 2 || id == 4 || id == 5) ? 1.0 : -1.0;
    vec3 right = normalize(vec3(p.vp[0][0], p.vp[1][0], p.vp[2][0]));
    vec3 up = normalize(vec3(p.vp[0][1], p.vp[1][1], p.vp[2][1]));
    int style = int(round(i_style));
    float radius = 3.2;
    float strength = 0.5;
    if (style == 0) { radius = 4.2; strength = 0.85; }
    else if (style == 1 || style == 4) { radius = 2.4; }
    vec3 world = i_center + (right * cx + up * cy) * i_size * radius;
    gl_Position = p.vp * vec4(world, 1.0);
    gl_Position.y = -gl_Position.y;
    o_uv = vec2(cx, cy);
    o_color = vec4(mix(i_color.rgb, vec3(1.0), 0.22), i_color.a * strength);
    o_sprite = style == 0 ? 1.0 : 0.0;
}
"#;

#[cfg(feature = "vk")]
const GLOW_FRAG: &str = r#"
#version 450
layout(location=0) in vec2 o_uv;
layout(location=1) in vec4 o_color;
layout(location=2) in float o_sprite;
layout(location=0) out vec4 col;
void main() {
    float r2 = dot(o_uv, o_uv);
    if (r2 >= 1.0) { discard; }
    float halo = (exp(-r2 * 4.0) - 0.0183) / 0.9817;
    // a sprite is mostly glow, so it gets a white-hot middle of its own
    float core = o_sprite * exp(-r2 * 30.0);
    col = vec4(mix(o_color.rgb, vec3(1.0), core), o_color.a * halo + core * 0.9);
}
"#;

#[cfg(feature = "vk")]
#[repr(C)]
struct PartPush { vp: [f32; 16], params: [f32; 4] }

#[cfg(feature = "vk")]
const INST_STRIDE: u32 = 40; // center3 + size + rgba + style + seed
#[cfg(feature = "vk")]
const INST_FLOATS: usize = 10;
#[cfg(feature = "vk")]
const MESH_STRIDE: u32 = 24; // pos3 + normal3

#[cfg(feature = "vk")]
struct PartState {
    layout:     vk::PipelineLayout,
    pipe:       vk::Pipeline,
    glow:       vk::Pipeline,
    mesh:       GpuBuf,
    ranges:     [(u32, u32); meshes::STYLE_COUNT],
    inst:       Vec<GpuBuf>,
    inst_cap:   u64, // bytes
    inst_cur:   usize,
    retired:    Vec<GpuBuf>,
}

#[cfg(feature = "vk")]
static PART: Mutex<Option<PartState>> = Mutex::new(None);

#[cfg(feature = "vk")]
fn iattr(loc: u32, fmt: vk::Format, off: u32) -> vk::VertexInputAttributeDescription {
    vk::VertexInputAttributeDescription::default().location(loc).binding(0).format(fmt).offset(off)
}

#[cfg(feature = "vk")]
unsafe fn ensure(ctx: &KenderVkCtx, g: &mut Option<PartState>) -> Result<(), i32> {
    if g.is_some() { return Ok(()); }
    let dev = &ctx.device;
    let push = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX).offset(0).size(std::mem::size_of::<PartPush>() as u32);
    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default().push_constant_ranges(std::slice::from_ref(&push)),
        None,
    ).map_err(|_| -42i32)?;

    let pipe = build_pipe(ctx, layout, PART_VERT, PART_FRAG, false)?;
    let glow = build_pipe(ctx, layout, GLOW_VERT, GLOW_FRAG, true)?;

    let (data, ranges) = meshes::all();
    let bytes = std::slice::from_raw_parts(data.as_ptr() as *const u8, data.len() * MESH_STRIDE as usize);
    let mesh = GpuBuf::new(dev, &ctx.mem_props, bytes.len() as u64, vk::BufferUsageFlags::VERTEX_BUFFER)
        .map_err(|_| -45i32)?;
    mesh.upload(dev, bytes);

    *g = Some(PartState {
        layout, pipe, glow, mesh, ranges, inst: Vec::new(), inst_cap: 0, inst_cur: 0, retired: Vec::new(),
    });
    super::stash_pso();
    Ok(())
}

// meshes: per-instance particle + per-vertex unit mesh, alpha blended, depth written so a mesh hides
// its own back. glow: per-instance quad only, additive, depth tested but never written
#[cfg(feature = "vk")]
unsafe fn build_pipe(ctx: &KenderVkCtx, layout: vk::PipelineLayout, vert: &str, frag: &str, glow: bool)
    -> Result<vk::Pipeline, i32> {
    let dev = &ctx.device;
    let vspv = compile_glsl(vert, naga::ShaderStage::Vertex).map_err(|_| -40i32)?;
    let fspv = compile_glsl(frag, naga::ShaderStage::Fragment).map_err(|_| -41i32)?;
    let vm = shader_mod(dev, &vspv)?;
    let fm = shader_mod(dev, &fspv)?;
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vm).name(entry),
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(fm).name(entry),
    ];

    let bindings = [
        vk::VertexInputBindingDescription::default().binding(0).stride(INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
        vk::VertexInputBindingDescription::default().binding(1).stride(MESH_STRIDE).input_rate(vk::VertexInputRate::VERTEX),
    ];
    let attrs = [
        iattr(0, vk::Format::R32G32B32_SFLOAT,    0),  // center
        iattr(1, vk::Format::R32_SFLOAT,          12), // size
        iattr(2, vk::Format::R32G32B32A32_SFLOAT, 16), // rgba
        iattr(3, vk::Format::R32_SFLOAT,          32), // style
        iattr(4, vk::Format::R32_SFLOAT,          36), // seed
        iattr(5, vk::Format::R32G32B32_SFLOAT,    0).binding(1),  // mesh position
        iattr(6, vk::Format::R32G32B32_SFLOAT,    12).binding(1), // mesh normal
    ];
    let (binding_count, attr_count) = if glow { (1, 5) } else { (2, 7) };

    let color_fmts = std::slice::from_ref(&ctx.color_fmt);
    let mut rendering = vk::PipelineRenderingCreateInfo::default()
        .color_attachment_formats(color_fmts)
        .depth_attachment_format(ctx.depth_fmt);

    let dyn_s = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let blend_att = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(if glow { vk::BlendFactor::ONE } else { vk::BlendFactor::ONE_MINUS_SRC_ALPHA })
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(if glow { vk::BlendFactor::ZERO } else { vk::BlendFactor::ONE })
        .dst_alpha_blend_factor(if glow { vk::BlendFactor::ONE } else { vk::BlendFactor::ONE_MINUS_SRC_ALPHA })
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA);

    let vi = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(&bindings[..binding_count]).vertex_attribute_descriptions(&attrs[..attr_count]);
    let ia = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let vps = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
    let rs = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let ms = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let ds = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(true).depth_write_enable(!glow)
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

    let made = dev.create_graphics_pipelines(ctx.pipe_cache, std::slice::from_ref(&ci), None)
        .map_err(|_| -44i32)
        .map(|v| v[0]);
    dev.destroy_shader_module(vm, None);
    dev.destroy_shader_module(fm, None);
    made
}

// mesh style the GPU draws for an instance; addon styles have no GPU drawer and show as orbs
#[cfg(feature = "vk")]
fn gpu_style(raw: f32) -> usize {
    let style = raw.round();
    if style >= 0.0 && (style as usize) < meshes::STYLE_COUNT { style as usize } else { 7 }
}

// instances grouped by style so each style is one instanced draw of its own mesh
#[cfg(feature = "vk")]
fn by_style(insts: &[f32]) -> (Vec<f32>, [(u32, u32); meshes::STYLE_COUNT]) {
    let mut counts = [0u32; meshes::STYLE_COUNT];
    for p in insts.chunks_exact(INST_FLOATS) { counts[gpu_style(p[8])] += 1; }
    let mut ranges = [(0u32, 0u32); meshes::STYLE_COUNT];
    let mut first = 0u32;
    for (style, n) in counts.iter().enumerate() {
        ranges[style] = (first, 0);
        first += n;
    }
    let mut out = vec![0f32; insts.len()];
    for p in insts.chunks_exact(INST_FLOATS) {
        let r = &mut ranges[gpu_style(p[8])];
        let at = (r.0 + r.1) as usize * INST_FLOATS;
        out[at..at + INST_FLOATS].copy_from_slice(p);
        r.1 += 1;
    }
    (out, ranges)
}

// gather every live particle, upload as instances, one instanced draw per mesh style plus one halo draw
#[cfg(feature = "vk")]
unsafe fn draw_kfx(
    ctx: &KenderVkCtx,
    cmd_raw: i64,
    attachments: Option<(i64, i64)>,
    width: u32,
    height: u32,
    view_proj: &[f32],
    now_ticks: f32,
    cam: [f64; 3],
) -> i32 {
    use ash::vk::Handle;
    // both eval'd + simulated in Rust across cores (rayon): program-op particles + emitter particles
    let mut gathered = crate::kfx::gather_instances(now_ticks, cam);
    let mut emit = crate::kfx::gather_emitters(now_ticks, cam);
    gathered.append(&mut emit);
    if gathered.is_empty() { return 0; }
    let (insts, groups) = by_style(&gathered);
    let inst_count = (insts.len() / INST_FLOATS) as u32;

    let mut guard = PART.lock().unwrap();
    if let Err(code) = ensure(ctx, &mut guard) { return code; }
    let g = guard.as_mut().unwrap();

    let need = (insts.len() * 4) as u64;
    if g.inst.is_empty() || need > g.inst_cap {
        g.retired.append(&mut g.inst); // destroy on renderer shutdown; these may still be in an in-flight frame
        let cap = need.next_power_of_two();
        for _ in 0..3 {
            let buf = match GpuBuf::new(&ctx.device, &ctx.mem_props, cap, vk::BufferUsageFlags::VERTEX_BUFFER) {
                Ok(b) => b, Err(_) => return -45,
            };
            g.inst.push(buf);
        }
        g.inst_cap = cap;
        g.inst_cur = 0;
    } else {
        g.inst_cur = (g.inst_cur + 1) % g.inst.len();
    }
    let inst_buf = &g.inst[g.inst_cur];
    inst_buf.upload(&ctx.device, std::slice::from_raw_parts(insts.as_ptr() as *const u8, insts.len() * 4));

    let cmd  = vk::CommandBuffer::from_raw(cmd_raw  as u64);
    let vp = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let sc = vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width, height } };

    if let Some((color_raw, depth_raw)) = attachments {
        let colv = vk::ImageView::from_raw(color_raw as u64);
        let depv = vk::ImageView::from_raw(depth_raw as u64);
        crate::vk::pass::begin(&ctx.dyn_rendering, cmd, colv, depv, width, height);
    }
    ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vp));
    ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&sc));

    let vparr: [f32; 16] = view_proj.try_into().unwrap_or([0f32; 16]);
    let pc = PartPush { vp: vparr, params: [now_ticks, 0.0, 0.0, 0.0] };
    ctx.device.cmd_push_constants(cmd, g.layout, vk::ShaderStageFlags::VERTEX, 0, bytemuck_cast(&pc));
    ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[inst_buf.buf, g.mesh.buf], &[0, 0]);

    ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, g.pipe);
    for (style, (first, count)) in groups.iter().enumerate() {
        let (first_vertex, vertex_count) = g.ranges[style];
        if *count == 0 || vertex_count == 0 { continue; }
        ctx.device.cmd_draw(cmd, vertex_count, *count, first_vertex, *first);
    }
    ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, g.glow);
    ctx.device.cmd_draw(cmd, 6, inst_count, 0, 0);

    if attachments.is_some() {
        crate::vk::pass::end(&ctx.dyn_rendering, cmd);
    }
    1
}

#[cfg(feature = "vk")]
pub unsafe fn draw_frame_kfx(
    ctx: &KenderVkCtx, cmd_raw: i64, color_raw: i64, depth_raw: i64,
    width: u32, height: u32, view_proj: &[f32], now_ticks: f32, cam: [f64; 3],
) -> i32 {
    draw_kfx(ctx, cmd_raw, Some((color_raw, depth_raw)), width, height, view_proj, now_ticks, cam)
}

// MC already began dynamic rendering here. Beginning a second pass corrupts the command buffer.
#[cfg(feature = "vk")]
pub unsafe fn draw_in_pass_kfx(
    ctx: &KenderVkCtx, cmd_raw: i64, width: u32, height: u32,
    view_proj: &[f32], now_ticks: f32, cam: [f64; 3],
) -> i32 {
    draw_kfx(ctx, cmd_raw, None, width, height, view_proj, now_ticks, cam)
}

#[cfg(feature = "vk")]
pub unsafe fn destroy(ctx: &KenderVkCtx) {
    let mut guard = PART.lock().unwrap();
    if let Some(g) = guard.take() {
        for b in g.inst { b.destroy(&ctx.device); }
        for b in g.retired { b.destroy(&ctx.device); }
        g.mesh.destroy(&ctx.device);
        ctx.device.destroy_pipeline(g.pipe, None);
        ctx.device.destroy_pipeline(g.glow, None);
        ctx.device.destroy_pipeline_layout(g.layout, None);
    }
}

// stubs — vk feature off
#[cfg(not(feature = "vk"))]
pub unsafe fn draw_frame_kfx(_ctx: &super::KenderVkCtx, _cmd: i64, _color: i64, _depth: i64,
    _w: u32, _h: u32, _vp: &[f32], _now: f32, _cam: [f64; 3]) -> i32 { -1 }

#[cfg(not(feature = "vk"))]
pub unsafe fn draw_in_pass_kfx(_ctx: &super::KenderVkCtx, _cmd: i64, _w: u32, _h: u32,
    _vp: &[f32], _now: f32, _cam: [f64; 3]) -> i32 { -1 }

#[cfg(all(test, feature = "vk"))]
mod tests {
    use super::*;

    #[test]
    fn particle_shaders_compile() {
        compile_glsl(PART_VERT, naga::ShaderStage::Vertex).unwrap();
        compile_glsl(PART_FRAG, naga::ShaderStage::Fragment).unwrap();
        compile_glsl(GLOW_VERT, naga::ShaderStage::Vertex).unwrap();
        compile_glsl(GLOW_FRAG, naga::ShaderStage::Fragment).unwrap();
    }

    #[test]
    fn instances_group_by_style_and_keep_their_data() {
        let mut insts = Vec::new();
        for (i, style) in [7.0f32, 5.0, 7.0, 0.0, 123.0, 5.0].iter().enumerate() {
            insts.extend_from_slice(&[i as f32, 0.0, 0.0, 0.1, 1.0, 1.0, 1.0, 1.0, *style, i as f32]);
        }
        let (out, ranges) = by_style(&insts);
        assert_eq!(out.len(), insts.len());
        assert_eq!(ranges[0], (0, 1));
        assert_eq!(ranges[5], (1, 2));
        // the addon style 123 has no GPU mesh and joins the orbs
        assert_eq!(ranges[7], (3, 3));
        let orbs: Vec<f32> = out[30..60].chunks_exact(10).map(|p| p[9]).collect();
        assert_eq!(orbs, vec![0.0, 2.0, 4.0]);
    }
}
