// kender Vulkan backend
// ash "loaded" = dlopen(vulkan-1.dll) at kender_vk_init time — never at DLL load time
// MC hands us its VkInstance/Device/Queue; we wrap them with ash and build our pipeline on top
// zero separate device creation, zero VRAM duplication — shared MC GPU context

pub mod pipeline;
pub mod pass;
pub mod buffer;
pub mod geo;
pub mod particles;
#[cfg_attr(not(feature = "vk"), allow(dead_code))]
pub mod meshes;

use std::sync::OnceLock;

// ── context ───────────────────────────────────────────────────────────────────

#[cfg(feature = "vk")]
use ash::{vk, Device, Instance, Entry, khr};
#[cfg(feature = "vk")]
use ash::vk::Handle; // brings from_raw() into scope for all Vk handle types

#[cfg(feature = "vk")]
pub struct KenderVkCtx {
    pub _entry:       Entry,         // must outlive instance (drop order matters)
    pub instance:     Instance,
    pub phys_dev:     vk::PhysicalDevice,
    pub device:       Device,
    pub dyn_rendering:khr::dynamic_rendering::Device,
    pub queue:        vk::Queue,
    pub queue_family: u32,
    pub mem_props:    vk::PhysicalDeviceMemoryProperties,
    pub color_fmt:    vk::Format,
    pub depth_fmt:    vk::Format,
    pub pipe_cache:   vk::PipelineCache,
    pub pipeline:     pipeline::KenderPipeline,
}

// driver PSO cache lives here between sessions (cwd = run/ in dev, instance dir on prism)
#[cfg(feature = "vk")]
const PSO_STASH: &str = "kender_pso_stash.bin";

#[cfg(not(feature = "vk"))]
pub struct KenderVkCtx {
    pub queue_family: u32,
}

static CTX: OnceLock<KenderVkCtx> = OnceLock::new();

pub fn ctx() -> Option<&'static KenderVkCtx> { CTX.get() }

// ── init ──────────────────────────────────────────────────────────────────────

pub unsafe fn init_from_handles(
    vk_instance:  i64,
    vk_device:    i64,
    vk_queue:     i64,
    queue_family: u32,
    color_fmt_raw: i32,   // MC's real main-target VkFormat (VulkanConst.toVk). 0 = fall back to a guess.
    depth_fmt_raw: i32,
) -> Result<(), i32> {
    if CTX.get().is_some() { return Ok(()); }

    #[cfg(not(feature = "vk"))]
    {
        let _ = (color_fmt_raw, depth_fmt_raw);
        CTX.set(KenderVkCtx { queue_family }).ok();
        return Ok(());
    }

    #[cfg(feature = "vk")]
    {
        // Entry::load() does dlopen("vulkan-1.dll") — safe here, GameRenderer init already ran
        let entry = Entry::load().map_err(|e| {
            eprintln!("[Kender] vulkan-1.dll load failed: {e}");
            -2i32
        })?;

        let raw_inst = vk::Instance::from_raw(vk_instance as u64);
        let instance = Instance::load(entry.static_fn(), raw_inst);

        // pick first physical device — single-GPU machines only have one
        // multi-GPU: take the one MC's device was created on (heuristic: first = discrete)
        let phys_devs = instance.enumerate_physical_devices().map_err(|_| -3i32)?;
        let phys_dev  = phys_devs.into_iter().next().ok_or(-4i32)?;
        let mem_props = instance.get_physical_device_memory_properties(phys_dev);

        let raw_dev = vk::Device::from_raw(vk_device as u64);
        let device  = Device::load(instance.fp_v1_0(), raw_dev);
        // use MC's actual queue handle directly — don't enumerate again
        let queue = vk::Queue::from_raw(vk_queue as u64);

        // dynamic rendering extension — Vulkan 1.3 core or KHR extension
        let dyn_rendering = khr::dynamic_rendering::Device::new(&instance, &device);

        // use MC's ACTUAL main-target formats (Java read them via VulkanConst.toVk). the old hardcoded
        // guess was a silent killer — a format mismatch makes the pipeline invalid so nothing rasterizes.
        let color_fmt = if color_fmt_raw != 0 { vk::Format::from_raw(color_fmt_raw) } else { vk::Format::B8G8R8A8_SRGB };
        let depth_fmt = if depth_fmt_raw != 0 { vk::Format::from_raw(depth_fmt_raw) } else { vk::Format::D32_SFLOAT };
        crate::klog(&format!("[Kender] pipeline formats: color={} depth={}", color_fmt.as_raw(), depth_fmt.as_raw()));

        // warm PSO cache from last session — driver skips spirv->isa for pipelines it's seen before.
        // header mismatch (driver update etc) -> driver ignores the blob, worst case we retry empty.
        let stash = std::fs::read(PSO_STASH).unwrap_or_default();
        let pipe_cache = device.create_pipeline_cache(
                &vk::PipelineCacheCreateInfo::default().initial_data(&stash), None)
            .or_else(|_| device.create_pipeline_cache(&vk::PipelineCacheCreateInfo::default(), None))
            .unwrap_or(vk::PipelineCache::null());
        if !stash.is_empty() { crate::klog(&format!("[Kender] pso stash loaded ({} bytes)", stash.len())); }

        let pipeline = pipeline::init_pipeline(&device, pipe_cache, color_fmt, depth_fmt).map_err(|e| {
            eprintln!("[Kender] pipeline init failed: {e}");
            e
        })?;

        let ctx = KenderVkCtx {
            _entry: entry, instance, phys_dev, device, dyn_rendering,
            queue, queue_family, mem_props, color_fmt, depth_fmt, pipe_cache, pipeline,
        };
        CTX.set(ctx).ok();
        Ok(())
    }
}

// ── per-frame draw call ───────────────────────────────────────────────────────

/// Called from Java (KenderBridge.drawFrame) after LevelRenderer.render().
/// cmd/color/depth are VkCommandBuffer/ImageView handles from VulkanStateManager via reflection.
pub unsafe fn do_draw_frame(
    cmd_raw:    i64,
    color_raw:  i64,
    depth_raw:  i64,
    width:      u32,
    height:     u32,
    ids:        &[i64],
    transforms: &[f32],
    view_proj:  &[f32],
    shadow:     bool,
) {
    #[cfg(not(feature = "vk"))]
    { return; }

    #[cfg(feature = "vk")]
    {
        let ctx = match CTX.get() { Some(c) => c, None => return };
        let cmd  = vk::CommandBuffer::from_raw(cmd_raw  as u64);
        let colv = vk::ImageView   ::from_raw(color_raw as u64);
        let depv = vk::ImageView   ::from_raw(depth_raw as u64);

        // set full-framebuffer viewport + scissor
        let vp = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
        let sc = vk::Rect2D { offset: vk::Offset2D::default(), extent: vk::Extent2D { width, height } };

        pass::begin(&ctx.dyn_rendering, cmd, colv, depv, width, height);
        ctx.device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&vp));
        ctx.device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&sc));

        pipeline::draw_frame(ctx, cmd, shadow, ids, transforms, view_proj);

        pass::end(&ctx.dyn_rendering, cmd);
    }
}

// dump the driver's PSO cache to disk. called after lazy pipeline builds + at destroy,
// so a crash mid-session doesn't eat the warmup we just paid for
#[cfg(feature = "vk")]
pub unsafe fn stash_pso() {
    let Some(ctx) = CTX.get() else { return };
    if ctx.pipe_cache == vk::PipelineCache::null() { return; }
    if let Ok(data) = ctx.device.get_pipeline_cache_data(ctx.pipe_cache) {
        if !data.is_empty() && std::fs::write(PSO_STASH, &data).is_ok() {
            crate::klog(&format!("[Kender] pso stash saved ({} bytes)", data.len()));
        }
    }
}

// ── destroy ───────────────────────────────────────────────────────────────────

pub unsafe fn destroy() {
    #[cfg(feature = "vk")]
    {
        if let Some(ctx) = CTX.get() {
            stash_pso();
            pipeline::destroy_pipeline(&ctx.device, &ctx.pipeline);
            particles::destroy(ctx);
            if ctx.pipe_cache != vk::PipelineCache::null() {
                ctx.device.destroy_pipeline_cache(ctx.pipe_cache, None);
            }
            // don't destroy device/instance — they belong to MC's LWJGL
        }
    }
}
