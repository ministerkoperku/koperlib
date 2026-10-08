// kender dynamic rendering pass — VK_KHR_dynamic_rendering (Vulkan 1.3 core)
// MC 26.2 targets modern Vulkan — dynamic rendering is safe to assume

#[cfg(feature = "vk")]
use ash::{vk, khr};

/// Begin our own isolated render pass for physics blocks.
/// Called inside LevelRenderer.render() at TAIL — MC's main pass already ended or hasn't started.
/// color_view/depth_view: VkImageView handles from VulkanStateManager via Java reflection.
#[cfg(feature = "vk")]
pub unsafe fn begin(
    dyn_rendering: &khr::dynamic_rendering::Device,
    cmd:           vk::CommandBuffer,
    color_view:    vk::ImageView,
    depth_view:    vk::ImageView,
    width:         u32,
    height:        u32,
) {
    let color_att = vk::RenderingAttachmentInfo::default()
        .image_view(color_view)
        .image_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
        .load_op(vk::AttachmentLoadOp::LOAD)   // preserve MC's rendered world
        .store_op(vk::AttachmentStoreOp::STORE)
        .clear_value(vk::ClearValue::default()); // unused with LOAD

    let depth_att = vk::RenderingAttachmentInfo::default()
        .image_view(depth_view)
        .image_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        .load_op(vk::AttachmentLoadOp::LOAD)
        .store_op(vk::AttachmentStoreOp::STORE)
        .clear_value(vk::ClearValue::default());

    let rendering_info = vk::RenderingInfo::default()
        .render_area(vk::Rect2D {
            offset: vk::Offset2D { x: 0, y: 0 },
            extent: vk::Extent2D { width, height },
        })
        .layer_count(1)
        .color_attachments(std::slice::from_ref(&color_att))
        .depth_attachment(&depth_att);

    dyn_rendering.cmd_begin_rendering(cmd, &rendering_info);

    // set viewport + scissor to full framebuffer
    let viewport = vk::Viewport { x: 0.0, y: 0.0, width: width as f32, height: height as f32, min_depth: 0.0, max_depth: 1.0 };
    let scissor  = vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent: vk::Extent2D { width, height } };
    // command buffer is from Java — we can't call device functions without device ref here
    // viewport/scissor are set in draw_frame via ctx.device
    let _ = (viewport, scissor); // set by caller with ctx.device
}

#[cfg(feature = "vk")]
pub unsafe fn end(
    dyn_rendering: &khr::dynamic_rendering::Device,
    cmd:           vk::CommandBuffer,
) {
    dyn_rendering.cmd_end_rendering(cmd);
}

// stubs
#[cfg(not(feature = "vk"))]
pub unsafe fn begin() {}
#[cfg(not(feature = "vk"))]
pub unsafe fn end() {}
