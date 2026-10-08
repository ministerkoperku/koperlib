// kender KFX particle instancing — ONE unit octahedron, ONE instance buffer, ONE instanced draw.
// the whole live particle set draws in a single call: GPU expands the octa per instance from
// camera-relative center+size+color, instead of the CPU tessellating a mesh per particle each frame.

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
const PART_VERT: &str = r#"
#version 450
layout(location=0) in vec3 i_center;
layout(location=1) in float i_size;
layout(location=2) in vec4 i_color;
layout(location=3) in float i_style;
layout(location=4) in float i_seed;
layout(push_constant) uniform Push { mat4 vp; } p;
layout(location=0) out vec3 o_normal;
layout(location=1) out vec4 o_color;
void main() {
    int style = int(round(i_style));
    int id = int(gl_VertexIndex);
    vec3 shape = vec3(0);
    if (style == 5) {
        int face = id / 6;
        int q = id - face * 6;
        int corner = q == 0 || q == 3 ? 0 : (q == 1 || q == 5 ? 2 : (q == 2 ? 1 : 3));
        float cu = corner == 0 || corner == 3 ? -1.0 : 1.0;
        float cv = corner < 2 ? -1.0 : 1.0;
        vec3 n = face == 0 ? vec3(0,0,-1) : (face == 1 ? vec3(0,0,1) :
            (face == 2 ? vec3(-1,0,0) : (face == 3 ? vec3(1,0,0) :
            (face == 4 ? vec3(0,1,0) : vec3(0,-1,0)))));
        vec3 u = abs(n.x) > 0.5 ? vec3(0,0,1) : vec3(1,0,0);
        vec3 v = abs(n.y) > 0.5 ? vec3(0,0,1) : vec3(0,1,0);
        shape = (n + u * cu + v * cv) * 0.72;
    } else if (style == 4 || style == 6) {
        int corner = id == 0 || id == 3 || id == 6 ? 0 :
            (id == 2 || id == 7 || id == 9 ? 1 :
            (id == 1 || id == 5 || id == 10 ? 2 : 3));
        if (id < 12) shape = corner == 0 ? vec3(0,1,0) :
            (corner == 1 ? vec3(-0.94,-0.34,0) :
            (corner == 2 ? vec3(0.47,-0.34,0.81) : vec3(0.47,-0.34,-0.81)));
    } else if (id < 24) {
        int tri = id / 3;
        int vertex = id - tri * 3;
        bool top = tri < 4;
        int sector = tri - (top ? 0 : 4);
        float a0 = float(sector) * 1.57079632679;
        float a1 = float(sector + 1) * 1.57079632679;
        vec3 r0 = vec3(cos(a0), 0, sin(a0));
        vec3 r1 = vec3(cos(a1), 0, sin(a1));
        shape = vertex == 0 ? vec3(0, top ? 1 : -1, 0) :
            (vertex == 1 ? (top ? r0 : r1) : (top ? r1 : r0));
    }
    vec3 normal = length(shape) > 0.001 ? normalize(shape) : vec3(0,1,0);
    if (style == 1) shape *= vec3(0.28, 2.8, 0.28);
    else if (style == 2) shape *= vec3(1.7, 2.25, 1.7);
    else if (style == 3) shape *= vec3(1.5, 0.18, 1.5);
    else if (style == 4) shape *= vec3(0.42, 2.2, 0.62);
    float turn = fract(i_seed * 0.61803398875) * 6.28318530718;
    float c = cos(turn), s = sin(turn);
    shape = vec3(c*shape.x + s*shape.z, shape.y, -s*shape.x + c*shape.z);
    normal = vec3(c*normal.x + s*normal.z, normal.y, -s*normal.x + c*normal.z);
    vec3 world = i_center + shape * i_size;
    gl_Position = p.vp * vec4(world, 1.0);
    gl_Position.y = -gl_Position.y;
    o_normal = normal;
    o_color = i_color;
}
"#;

#[cfg(feature = "vk")]
const PART_FRAG: &str = r#"
#version 450
layout(location=0) in vec3 o_normal;
layout(location=1) in vec4 o_color;
layout(location=0) out vec4 col;
void main() {
    vec3 n = normalize(o_normal);
    float d = 0.55 + 0.45 * clamp(dot(n, normalize(vec3(0.32, 0.86, 0.36))), 0.0, 1.0);
    col = vec4(o_color.rgb * d, o_color.a);
}
"#;

#[cfg(feature = "vk")]
#[repr(C)]
struct PartPush { vp: [f32; 16] }

#[cfg(feature = "vk")]
const INST_STRIDE: u32 = 40; // center3 + size + rgba + style + seed

#[cfg(feature = "vk")]
struct PartState {
    layout:     vk::PipelineLayout,
    pipe:       vk::Pipeline,
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
    let vspv = compile_glsl(PART_VERT, naga::ShaderStage::Vertex).map_err(|_| -40i32)?;
    let fspv = compile_glsl(PART_FRAG, naga::ShaderStage::Fragment).map_err(|_| -41i32)?;
    let vm = shader_mod(dev, &vspv)?;
    let fm = shader_mod(dev, &fspv)?;

    let push = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX).offset(0).size(64);
    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default().push_constant_ranges(std::slice::from_ref(&push)),
        None,
    ).map_err(|_| -42i32)?;

    let pipe = build_part_pipe(dev, ctx.pipe_cache, layout, vm, fm, ctx.color_fmt, ctx.depth_fmt)?;
    dev.destroy_shader_module(vm, None);
    dev.destroy_shader_module(fm, None);

    *g = Some(PartState {
        layout, pipe, inst: Vec::new(), inst_cap: 0, inst_cur: 0, retired: Vec::new(),
    });
    super::stash_pso();
    Ok(())
}

#[cfg(feature = "vk")]
unsafe fn build_part_pipe(
    dev: &Device, cache: vk::PipelineCache, layout: vk::PipelineLayout,
    vm: vk::ShaderModule, fm: vk::ShaderModule,
    color_fmt: vk::Format, depth_fmt: vk::Format,
) -> Result<vk::Pipeline, i32> {
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vm).name(entry),
        vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(fm).name(entry),
    ];

    let bindings = [
        vk::VertexInputBindingDescription::default().binding(0).stride(INST_STRIDE).input_rate(vk::VertexInputRate::INSTANCE),
    ];
    let attrs = [
        iattr(0, vk::Format::R32G32B32_SFLOAT,    0),  // center
        iattr(1, vk::Format::R32_SFLOAT,          12), // size
        iattr(2, vk::Format::R32G32B32A32_SFLOAT, 16), // rgba
        iattr(3, vk::Format::R32_SFLOAT,          32), // style
        iattr(4, vk::Format::R32_SFLOAT,          36), // seed
    ];

    let color_fmts = std::slice::from_ref(&color_fmt);
    let mut rendering = vk::PipelineRenderingCreateInfo::default()
        .color_attachment_formats(color_fmts)
        .depth_attachment_format(depth_fmt);

    let dyn_s = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    // translucent additive-ish glow: alpha blend, no depth write (particles don't occlude each other)
    let blend_att = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        .color_write_mask(vk::ColorComponentFlags::RGBA);

    let vi = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(&bindings).vertex_attribute_descriptions(&attrs);
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
        .depth_test_enable(true).depth_write_enable(false)
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
        .map_err(|_| -44i32)
        .map(|v| v[0])
}

// gather every live particle, upload as instances, one instanced draw of the octa into MC's frame.
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
    // both eval'd + simulated in Rust across cores (rayon): program-op particles + emitter particles, one draw
    let mut insts = crate::kfx::gather_instances(now_ticks, cam);
    let mut emit = crate::kfx::gather_emitters(now_ticks, cam);
    insts.append(&mut emit);
    if insts.is_empty() { return 0; }
    let inst_count = (insts.len() / 10) as u32;

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

    ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, g.pipe);
    let vparr: [f32; 16] = view_proj.try_into().unwrap_or([0f32; 16]);
    let pc = PartPush { vp: vparr };
    ctx.device.cmd_push_constants(cmd, g.layout, vk::ShaderStageFlags::VERTEX, 0, bytemuck_cast(&pc));
    ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[inst_buf.buf], &[0]);
    ctx.device.cmd_draw(cmd, 36, inst_count, 0, 0);

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
        ctx.device.destroy_pipeline(g.pipe, None);
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
    }
}
