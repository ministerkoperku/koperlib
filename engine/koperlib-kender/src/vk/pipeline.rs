// kender Vulkan pipeline — face-colored AO debug for K1, atlas bindless lands in K1.5
// per-kontraktion vertex buffers uploaded on dirty, drawn with transform push constants

#[cfg(feature = "vk")]
use ash::{vk, Device};
#[cfg(feature = "vk")]
use std::{collections::HashMap, sync::Mutex};
#[cfg(feature = "vk")]
use glam::{Mat4, Quat, Vec3};
#[cfg(feature = "vk")]
use crate::vk::buffer::GpuBuf;

// face-direction debug coloring + AO — real atlas lookup in K1.5
#[cfg(feature = "vk")]
const VERT_SRC: &str = r#"
#version 450
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in float a_ao;
layout(location = 3) in float a_face;
layout(location = 4) in float a_bid;
layout(push_constant) uniform Push { mat4 vp; mat4 m; } p;
layout(location = 0) out float o_ao;
layout(location = 1) out float o_face;
void main() {
    gl_Position = p.vp * p.m * vec4(a_pos, 1.0);
    o_ao = a_ao; o_face = a_face;
}
"#;

#[cfg(feature = "vk")]
const FRAG_SRC: &str = r#"
#version 450
layout(location = 0) in float o_ao;
layout(location = 1) in float o_face;
layout(location = 0) out vec4 col;
void main() {
    int f = clamp(int(o_face), 0, 5);
    float r = (f == 0) ? 0.80 : (f == 1) ? 0.50 : 0.25;
    float g = (f == 2) ? 0.85 : (f == 3) ? 0.40 : 0.25;
    float b = (f == 4) ? 0.85 : (f == 5) ? 0.50 : 0.25;
    col = vec4(r * o_ao, g * o_ao, b * o_ao, 1.0);
}
"#;

#[cfg(feature = "vk")]
const DEPTH_VERT_SRC: &str = r#"
#version 450
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in float a_ao;
layout(location = 3) in float a_face;
layout(location = 4) in float a_bid;
layout(push_constant) uniform Push { mat4 vp; mat4 m; } p;
void main() { gl_Position = p.vp * p.m * vec4(a_pos, 1.0); }
"#;

// ── public types ─────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
pub struct KenderPipeline {
    pub layout:     vk::PipelineLayout,
    pub main_pipe:  vk::Pipeline,
    pub depth_pipe: vk::Pipeline,
    pub meshes:     Mutex<HashMap<i64, KontraMesh>>,
}

#[cfg(not(feature = "vk"))]
pub struct KenderPipeline;

#[cfg(feature = "vk")]
pub struct KontraMesh {
    pub buf:        GpuBuf,
    pub vert_count: u32,
}

#[cfg(feature = "vk")]
#[repr(C)]
struct PushConsts {
    view_proj: [f32; 16],
    model:     [f32; 16],
}

// ── init ─────────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
pub unsafe fn init_pipeline(
    dev:       &Device,
    cache:     vk::PipelineCache,
    color_fmt: vk::Format,
    depth_fmt: vk::Format,
) -> Result<KenderPipeline, i32> {
    let vert_spv  = compile_glsl(VERT_SRC,       naga::ShaderStage::Vertex)  .map_err(|_| -10i32)?;
    let frag_spv  = compile_glsl(FRAG_SRC,       naga::ShaderStage::Fragment).map_err(|_| -11i32)?;
    let depth_spv = compile_glsl(DEPTH_VERT_SRC, naga::ShaderStage::Vertex)  .map_err(|_| -12i32)?;

    let vm = shader_mod(dev, &vert_spv)?;
    let fm = shader_mod(dev, &frag_spv)?;
    let dm = shader_mod(dev, &depth_spv)?;

    let push_range = vk::PushConstantRange::default()
        .stage_flags(vk::ShaderStageFlags::VERTEX)
        .offset(0)
        .size(std::mem::size_of::<PushConsts>() as u32); // 128 bytes

    let layout = dev.create_pipeline_layout(
        &vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range)),
        None,
    ).map_err(|_| -13i32)?;

    let main_pipe  = build_pipe(dev, cache, layout, vm, fm, color_fmt, depth_fmt, false)?;
    let depth_pipe = build_pipe(dev, cache, layout, dm, vk::ShaderModule::null(), color_fmt, depth_fmt, true)?;

    dev.destroy_shader_module(vm, None);
    dev.destroy_shader_module(fm, None);
    dev.destroy_shader_module(dm, None);

    Ok(KenderPipeline {
        layout,
        main_pipe,
        depth_pipe,
        meshes: Mutex::new(HashMap::new()),
    })
}

// ── draw ─────────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
pub unsafe fn draw_frame(
    ctx:        &super::KenderVkCtx,
    cmd:        vk::CommandBuffer,
    shadow:     bool,
    ids:        &[i64],
    transforms: &[f32],  // 7 floats per kontraktion: [px py pz  rx ry rz rw]
    view_proj:  &[f32],  // 16 floats col-major
) {
    let pipe = if shadow { ctx.pipeline.depth_pipe } else { ctx.pipeline.main_pipe };
    ctx.device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);

    let mut meshes = ctx.pipeline.meshes.lock().unwrap();

    for (i, &kid) in ids.iter().enumerate() {
        let Some(mesh) = refresh_mesh(kid, &mut meshes, &ctx.device, &ctx.mem_props)
            else { continue };

        let t = &transforms[i * 7..];
        let model = Mat4::from_rotation_translation(
            Quat::from_xyzw(t[3], t[4], t[5], t[6]),
            Vec3::new(t[0], t[1], t[2]),
        );

        let vp: [f32; 16] = view_proj.try_into().unwrap_or([0f32; 16]);
        let pc = PushConsts { view_proj: vp, model: model.to_cols_array() };
        let pc_bytes = bytemuck_cast(&pc);

        ctx.device.cmd_push_constants(
            cmd, ctx.pipeline.layout, vk::ShaderStageFlags::VERTEX, 0, pc_bytes,
        );
        ctx.device.cmd_bind_vertex_buffers(cmd, 0, &[mesh.buf.buf], &[0]);
        ctx.device.cmd_draw(cmd, mesh.vert_count, 1, 0, 0);
    }
}

// ── mesh cache ────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
unsafe fn refresh_mesh<'a>(
    kid:      i64,
    meshes:   &'a mut HashMap<i64, KontraMesh>,
    dev:      &Device,
    mem_props:&vk::PhysicalDeviceMemoryProperties,
) -> Option<&'a KontraMesh> {
    let (ptr, float_count) = crate::cache::get_mesh_ptr(kid)?;
    if float_count == 0 { return None; }
    let bytes = float_count as usize * 4;
    let verts = (float_count as usize / crate::types::FLOATS_PER_VERT) as u32;

    let mesh = meshes.entry(kid).or_insert_with(|| {
        let buf = GpuBuf::new(dev, mem_props, bytes as u64, vk::BufferUsageFlags::VERTEX_BUFFER)
            .expect("kender: OOM allocating mesh buffer");
        KontraMesh { buf, vert_count: 0 }
    });

    if mesh.vert_count != verts {
        let data = std::slice::from_raw_parts(ptr as *const u8, bytes);
        mesh.buf.upload(dev, data);
        mesh.vert_count = verts;
    }
    Some(mesh)
}

// ── destroy ───────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
pub unsafe fn destroy_pipeline(dev: &Device, p: &KenderPipeline) {
    let meshes = p.meshes.lock().unwrap();
    for m in meshes.values() { m.buf.destroy(dev); }
    dev.destroy_pipeline(p.main_pipe,  None);
    dev.destroy_pipeline(p.depth_pipe, None);
    dev.destroy_pipeline_layout(p.layout, None);
}

// ── helpers ───────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
pub(crate) fn attr(loc: u32, fmt: vk::Format, off: u32) -> vk::VertexInputAttributeDescription {
    vk::VertexInputAttributeDescription::default().location(loc).binding(0).format(fmt).offset(off)
}

#[cfg(feature = "vk")]
pub(crate) unsafe fn shader_mod(dev: &Device, spv: &[u32]) -> Result<vk::ShaderModule, i32> {
    dev.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(spv), None)
        .map_err(|_| -20i32)
}

#[cfg(feature = "vk")]
// WGSL -> SPIR-V. naga is a WGSL-native compiler, so unlike its half-baked GLSL frontend this handles
// textures/samplers/push-constants/etc. properly. all our shaders live in WGSL now.
pub(crate) fn compile_wgsl(src: &str) -> Result<Vec<u32>, String> {
    let module = naga::front::wgsl::parse_str(src).map_err(|e| format!("{e:?}"))?;
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    ).validate(&module).map_err(|e| format!("{e:?}"))?;
    naga::back::spv::write_vec(
        &module, &info,
        &naga::back::spv::Options { lang_version: (1, 3), ..Default::default() },
        None,
    ).map_err(|e| format!("{e:?}"))
}

pub(crate) fn compile_glsl(src: &str, stage: naga::ShaderStage) -> Result<Vec<u32>, String> {
    let opts = naga::front::glsl::Options { stage, defines: Default::default() };
    let module = naga::front::glsl::Frontend::default()
        .parse(&opts, src)
        .map_err(|e| format!("{:?}", e))?;
    let info = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    ).validate(&module).map_err(|e| format!("{:?}", e))?;
    naga::back::spv::write_vec(
        &module, &info,
        &naga::back::spv::Options { lang_version: (1, 3), ..Default::default() },
        None,
    ).map_err(|e| format!("{:?}", e))
}

#[cfg(feature = "vk")]
pub(crate) unsafe fn bytemuck_cast<T: Sized>(v: &T) -> &[u8] {
    std::slice::from_raw_parts(v as *const T as *const u8, std::mem::size_of::<T>())
}

#[cfg(feature = "vk")]
unsafe fn build_pipe(
    dev:        &Device,
    cache:      vk::PipelineCache,
    layout:     vk::PipelineLayout,
    vert_mod:   vk::ShaderModule,
    frag_mod:   vk::ShaderModule,
    color_fmt:  vk::Format,
    depth_fmt:  vk::Format,
    depth_only: bool,
) -> Result<vk::Pipeline, i32> {
    let entry = c"main";
    let mut stages = vec![
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX).module(vert_mod).name(entry),
    ];
    if !depth_only {
        stages.push(vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT).module(frag_mod).name(entry));
    }

    let binding = [vk::VertexInputBindingDescription::default()
        .binding(0).stride(36).input_rate(vk::VertexInputRate::VERTEX)];
    let attrs = [
        attr(0, vk::Format::R32G32B32_SFLOAT, 0),
        attr(1, vk::Format::R32G32_SFLOAT,    12),
        attr(2, vk::Format::R32_SFLOAT,        20),
        attr(3, vk::Format::R32_SFLOAT,        24),
        attr(4, vk::Format::R32_SFLOAT,        28),
    ];

    let color_fmts = if depth_only { &[][..] } else { std::slice::from_ref(&color_fmt) };
    let mut rendering = vk::PipelineRenderingCreateInfo::default()
        .color_attachment_formats(color_fmts)
        .depth_attachment_format(depth_fmt);

    let dyn_s = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let blend_att = vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(vk::ColorComponentFlags::RGBA);

    // ash builder borrows are non-'static — must be bound to lets before use in the chain
    let vi = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(&binding).vertex_attribute_descriptions(&attrs);
    let ia = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let vps = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
    let rs = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::BACK)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let ms = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let ds = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(true).depth_write_enable(true)
        .depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
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
        .map_err(|_| -14i32)
        .map(|v| v[0])
}

// stubs — compiled when vk feature is off, keep the module importable
#[cfg(not(feature = "vk"))]
pub unsafe fn init_pipeline() -> Result<KenderPipeline, i32> { Ok(KenderPipeline) }
#[cfg(not(feature = "vk"))]
pub unsafe fn draw_frame() {}
#[cfg(not(feature = "vk"))]
pub unsafe fn destroy_pipeline() {}
